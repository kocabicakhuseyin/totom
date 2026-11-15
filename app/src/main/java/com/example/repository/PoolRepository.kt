package com.example.repository

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import com.example.model.CouponPoolEvaluator
import com.example.model.MatchLiveScore
import com.example.model.PoolColumnRank
import com.example.model.PoolCouponDto
import com.example.model.PoolCouponUpload
import com.example.network.PoolApiService
import com.example.network.PoolAuthCredentials
import com.example.network.PoolAuthResponse
import com.example.network.PoolAuthUser
import com.example.network.PoolNetworkModule
import com.example.network.PoolPkceExchangeRequest
import com.example.network.PoolRecoverRequest
import com.example.network.PoolRefreshRequest
import com.example.network.PoolSignUpRequest
import com.example.network.PoolUpdateUserRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

enum class OAuthProvider(val id: String, val displayName: String) {
    GOOGLE("google", "Google"),
    APPLE("apple", "Apple ID")
}

data class MemberSessionState(
    val isSignedIn: Boolean = false,
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val provider: String = "",
    val favoriteTeamId: String = "",
    val createdAtText: String = "",
    val emailVerified: Boolean = false
) {
    val providerDisplayLabel: String
        get() = when (provider.lowercase()) {
            "google" -> "Google Hesabı"
            "apple" -> "Apple ID"
            else -> "E-posta Üyeliği"
        }
}

data class OAuthCallbackStatus(
    val inProgress: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

/**
 * Unified Supabase membership & shared coupon repository.
 * Supports Email/Password, Google OAuth, and Apple ID OAuth (PKCE + implicit fallback)
 * using only the Supabase publishable key with Row Level Security.
 */
class PoolRepository(
    context: Context,
    private val api: PoolApiService = PoolNetworkModule.service
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("toto_pool_session", Context.MODE_PRIVATE)
    private val authMutex = Mutex()
    private val key = PoolNetworkModule.PUBLISHABLE_KEY

    private val _sessionState = MutableStateFlow(readStoredSessionState())
    val sessionState: StateFlow<MemberSessionState> = _sessionState.asStateFlow()

    private val _oauthStatus = MutableStateFlow(OAuthCallbackStatus())
    val oauthStatus: StateFlow<OAuthCallbackStatus> = _oauthStatus.asStateFlow()

    val hasSession: Boolean get() = !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty()
    val currentUserId: String get() = prefs.getString(KEY_USER_ID, null).orEmpty()

    suspend fun register(
        email: String,
        password: String,
        displayName: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isValidCredentials(email, password)) {
            return@withContext Result.failure(IllegalArgumentException("Geçerli e-posta ve en az 6 karakterli şifre girin."))
        }
        try {
            val cleanName = displayName.trim().take(30)
            val meta = if (cleanName.isNotEmpty()) mapOf("username" to cleanName) else null
            val response = api.signUp(
                publishableKey = key,
                body = PoolSignUpRequest(email.trim(), password, meta)
            )
            if (!response.isSuccessful) {
                val errBody = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
                val msg = when {
                    errBody.contains("already registered", ignoreCase = true) ->
                        "Bu e-posta adresi zaten kayıtlı. Giriş Yap sekmesini kullanın."
                    errBody.contains("password", ignoreCase = true) ->
                        "Şifre en az 6 karakter ve güvenli olmalıdır."
                    else -> "Kayıt yapılamadı. E-posta adresini ve şifrenizi kontrol edin."
                }
                return@withContext Result.failure(IllegalStateException(msg))
            }
            val auth = response.body()
            val signedIn = auth?.accessToken != null && auth.refreshToken != null
            if (signedIn) {
                save(auth!!, providerOverride = "email")
                refreshProfileFromServerInternal()
            }
            Result.success(signedIn) // false = email confirmation required.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Kayıt sırasında Supabase bağlantısı kurulamadı."))
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isValidCredentials(email, password)) {
            return@withContext Result.failure(IllegalArgumentException("Geçerli e-posta ve şifre girin."))
        }
        try {
            val response = api.signIn(key, body = PoolAuthCredentials(email.trim(), password))
            val auth = response.body()
            if (!response.isSuccessful || auth?.accessToken.isNullOrEmpty() || auth?.refreshToken.isNullOrEmpty()) {
                return@withContext Result.failure(IllegalStateException("Giriş başarısız. E-posta doğrulamasını, şifreyi ve hesabı kontrol edin."))
            }
            save(auth!!, providerOverride = "email")
            refreshProfileFromServerInternal()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Giriş sırasında bağlantı kurulamadı."))
        }
    }

    /**
     * Builds a PKCE-protected Supabase OAuth authorize URL for Google or Apple ID
     * redirecting back to `sportoto://auth-callback`.
     */
    fun buildOAuthAuthorizeUrl(provider: OAuthProvider): String {
        val verifier = generateCodeVerifier()
        val challenge = computeCodeChallenge(verifier)
        prefs.edit()
            .putString(KEY_PENDING_PKCE_VERIFIER, verifier)
            .putString(KEY_PENDING_OAUTH_PROVIDER, provider.id)
            .apply()

        val encodedRedirect = URLEncoder.encode(AUTH_REDIRECT_URI, "UTF-8")
        val encodedChallenge = URLEncoder.encode(challenge, "UTF-8")
        val extraParams = if (provider == OAuthProvider.GOOGLE) {
            "&prompt=select_account&access_type=offline"
        } else {
            ""
        }
        return "${PoolNetworkModule.SUPABASE_URL}/auth/v1/authorize" +
            "?provider=${provider.id}" +
            "&redirect_to=$encodedRedirect" +
            "&code_challenge=$encodedChallenge" +
            "&code_challenge_method=S256" +
            extraParams
    }

    /**
     * Opens Chrome Custom Tab / system browser for Google or Apple ID login via Supabase.
     * On completion, Supabase redirects back to `sportoto://auth-callback`.
     */
    fun launchOAuthSignIn(context: Context, provider: OAuthProvider): Result<Unit> {
        return try {
            val authUrl = buildOAuthAuthorizeUrl(provider)
            val extras = Bundle().apply {
                putBinder("android.support.customtabs.extra.SESSION", null)
                putInt("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                putExtras(extras)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (_: ActivityNotFoundException) {
            Result.failure(IllegalStateException("Cihazda ${provider.displayName} girişi açabilecek tarayıcı bulunamadı."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("${provider.displayName} girişi başlatılamadı: ${e.localizedMessage.orEmpty()}"))
        }
    }

    suspend fun handleOAuthCallbackUri(uri: Uri): Result<MemberSessionState> =
        handleOAuthCallbackUrl(uri.toString())

    suspend fun handleOAuthCallbackUrl(rawUrl: String): Result<MemberSessionState> = withContext(Dispatchers.IO) {
        val parsedUri = runCatching { java.net.URI(rawUrl) }.getOrNull()
        if (parsedUri == null) {
            val msg = "Geçersiz giriş dönüş bağlantısı."
            _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = msg, isError = true)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        if (!parsedUri.scheme.equals("sportoto", ignoreCase = true) ||
            !parsedUri.host.equals("auth-callback", ignoreCase = true)
        ) {
            val msg = "Bu bağlantı Spor Toto üyelik callback adresi değil."
            _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = msg, isError = true)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        _oauthStatus.value = OAuthCallbackStatus(
            inProgress = true,
            message = "Oturum doğrulanıyor, profil yükleniyor...",
            isError = false
        )

        val queryParams = parseQueryParams(parsedUri.rawQuery.orEmpty())
        val fragmentParams = parseQueryParams(parsedUri.rawFragment.orEmpty())

        val oauthError = fragmentParams["error_description"]
            ?: fragmentParams["error"]
            ?: queryParams["error_description"]
            ?: queryParams["error"]
        if (!oauthError.isNullOrBlank()) {
            val friendly = formatOAuthError(oauthError)
            _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = friendly, isError = true)
            return@withContext Result.failure(IllegalStateException(friendly))
        }

        val pendingProvider = prefs.getString(KEY_PENDING_OAUTH_PROVIDER, null).orEmpty()

        // 1. PKCE authorization code exchange (default for Supabase v2 + mobile PKCE)
        val authCode = queryParams["code"]
        if (!authCode.isNullOrBlank()) {
            val verifier = prefs.getString(KEY_PENDING_PKCE_VERIFIER, null).orEmpty()
            if (verifier.isBlank()) {
                val msg = "OAuth PKCE doğrulayıcısı bulunamadı. Lütfen Google veya Apple ID ile girişi yeniden başlatın."
                _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = msg, isError = true)
                return@withContext Result.failure(IllegalStateException(msg))
            }
            return@withContext try {
                val response = api.exchangePkceCode(
                    publishableKey = key,
                    body = PoolPkceExchangeRequest(authCode = authCode, codeVerifier = verifier)
                )
                val body = response.body()
                if (!response.isSuccessful || body?.accessToken.isNullOrEmpty() || body?.refreshToken.isNullOrEmpty()) {
                    val errText = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
                    val msg = "Supabase OAuth oturumu doğrulanamadı (HTTP ${response.code()}): ${errText.take(140)}"
                    _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = msg, isError = true)
                    Result.failure(IllegalStateException(msg))
                } else {
                    prefs.edit().remove(KEY_PENDING_PKCE_VERIFIER).remove(KEY_PENDING_OAUTH_PROVIDER).apply()
                    save(body!!, providerOverride = pendingProvider)
                    refreshProfileFromServerInternal()
                    val finalSession = _sessionState.value
                    _oauthStatus.value = OAuthCallbackStatus(
                        inProgress = false,
                        message = "✅ ${finalSession.providerDisplayLabel} ile oturum açıldı.",
                        isError = false
                    )
                    Result.success(finalSession)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val msg = "OAuth kod değişimi sırasında bağlantı/çözümleme hatası: ${e.localizedMessage.orEmpty()}"
                _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = msg, isError = true)
                Result.failure(IllegalStateException(msg))
            }
        }

        // 2. Implicit hash fragment fallback (#access_token=...&refresh_token=...)
        val accessToken = fragmentParams["access_token"]
        val refreshToken = fragmentParams["refresh_token"]
        if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
            val expiresIn = fragmentParams["expires_in"]?.toLongOrNull() ?: 3600L
            prefs.edit().remove(KEY_PENDING_PKCE_VERIFIER).remove(KEY_PENDING_OAUTH_PROVIDER).apply()
            save(
                PoolAuthResponse(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresIn = expiresIn
                ),
                providerOverride = pendingProvider
            )
            refreshProfileFromServerInternal()
            val finalSession = _sessionState.value
            _oauthStatus.value = OAuthCallbackStatus(
                inProgress = false,
                message = "✅ ${finalSession.providerDisplayLabel} ile oturum açıldı.",
                isError = false
            )
            return@withContext Result.success(finalSession)
        }

        val missingMsg = "Giriş dönüşünde oturum kodu veya token bulunamadı."
        _oauthStatus.value = OAuthCallbackStatus(inProgress = false, message = missingMsg, isError = true)
        Result.failure(IllegalStateException(missingMsg))
    }

    suspend fun refreshProfileFromServer(): Result<MemberSessionState> = withContext(Dispatchers.IO) {
        if (!hasSession) {
            return@withContext Result.failure(IllegalStateException("Aktif üye oturumu yok."))
        }
        val ok = refreshProfileFromServerInternal()
        if (ok) Result.success(_sessionState.value)
        else Result.failure(IllegalStateException("Üye profili sunucudan alınamadı."))
    }

    private suspend fun refreshProfileFromServerInternal(): Boolean {
        val token = usableToken() ?: return false
        return try {
            val response = api.getCurrentUser(key, "Bearer $token")
            val user = response.body()
            if (response.isSuccessful && user != null) {
                saveUserDetails(user)
                true
            } else {
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun updateMemberProfile(
        username: String? = null,
        favoriteTeamId: String? = null
    ): Result<MemberSessionState> = withContext(Dispatchers.IO) {
        val token = usableToken() ?: return@withContext Result.failure(
            IllegalStateException("Profil güncellemek için üye girişi yapın.")
        )
        val payload = buildMap {
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { put("username", it.take(30)) }
            favoriteTeamId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("favorite_team", it) }
        }
        if (payload.isEmpty()) return@withContext Result.success(_sessionState.value)

        return@withContext try {
            val response = api.updateCurrentUser(
                publishableKey = key,
                bearer = "Bearer $token",
                body = PoolUpdateUserRequest(data = payload)
            )
            val user = response.body()
            if (response.isSuccessful && user != null) {
                saveUserDetails(user)
                Result.success(_sessionState.value)
            } else {
                Result.failure(IllegalStateException("Profil buluta kaydedilemedi (HTTP ${response.code()})."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Profil güncellenirken bağlantı kurulamadı."))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = email.trim()
        if (!trimmed.contains('@')) {
            return@withContext Result.failure(IllegalArgumentException("Geçerli bir e-posta adresi girin."))
        }
        try {
            val response = api.recoverPassword(key, PoolRecoverRequest(trimmed))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IllegalStateException("Şifre sıfırlama e-postası gönderilemedi (HTTP ${response.code()})."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Şifre sıfırlama isteği gönderilirken bağlantı kurulamadı."))
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _oauthStatus.value = OAuthCallbackStatus()
        _sessionState.value = MemberSessionState()
    }

    suspend fun signOutAsync() = withContext(Dispatchers.IO) {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (!token.isNullOrBlank()) {
            runCatching { api.logout(key, "Bearer $token") }
        }
        signOut()
    }

    suspend fun getWeek(weekKey: String): Result<List<PoolCouponDto>> = withContext(Dispatchers.IO) {
        try {
            val token = usableToken() ?: return@withContext Result.failure(
                IllegalStateException("Kupon havuzu için üye girişi yapın.")
            )
            val response = api.listShared(key, "Bearer $token", "eq.$weekKey")
            if (!response.isSuccessful) {
                return@withContext Result.failure(IllegalStateException("Havuz verileri alınamadı (HTTP ${response.code()})."))
            }
            Result.success(response.body().orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Havuz sunucusuna bağlanılamıyor."))
        }
    }

    suspend fun getRanking(
        weekKey: String, matchLabels: List<String>, liveScores: List<MatchLiveScore>
    ): Result<List<PoolColumnRank>> = withContext(Dispatchers.IO) {
        if (matchLabels.size != 15) return@withContext Result.failure(
            IllegalArgumentException("Sıralama için 15 maçlık bülten gereklidir.")
        )
        try {
            val token = usableToken() ?: return@withContext Result.failure(
                IllegalStateException("Sıralama için üye girişi yapın.")
            )
            val (finalCodes, liveCodes) = CouponPoolEvaluator.resultCodes(matchLabels, liveScores)
            val response = api.rank(
                key, "Bearer $token",
                com.example.network.PoolRankRequest(weekKey, matchLabels, finalCodes, liveCodes)
            )
            if (response.isSuccessful) Result.success(response.body().orEmpty().map { it.asDomain() })
            else Result.failure(IllegalStateException("Canlı sıralama alınamadı (HTTP ${response.code()})."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Canlı sıralama sunucusuna bağlanılamadı."))
        }
    }

    suspend fun share(coupon: PoolCouponUpload): Result<Unit> = withContext(Dispatchers.IO) {
        if (coupon.matchLabels.size != 15 || coupon.columns.isEmpty() || coupon.columns.size > 4096 ||
            coupon.columns.any { !it.matches(Regex("[1X2]{15}")) } || !coupon.confirmedByMember
        ) {
            return@withContext Result.failure(IllegalArgumentException("Paylaşılacak kupon 15 maç içermeli ve oynandığı onaylanmalıdır."))
        }
        try {
            val token = usableToken() ?: return@withContext Result.failure(
                IllegalStateException("Paylaşmak için giriş yapın.")
            )
            val response = api.publish(key, "Bearer $token", coupon)
            if (!response.isSuccessful) {
                val message = if (response.code() == 409) "Bu kupon daha önce aynı hafta için paylaşıldı."
                else "Kupon paylaşılamadı (HTTP ${response.code()})."
                return@withContext Result.failure(IllegalStateException(message))
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Kupon paylaşılırken bağlantı kesildi."))
        }
    }

    suspend fun withdraw(couponId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = usableToken() ?: return@withContext Result.failure(
                IllegalStateException("Paylaşımı kaldırmak için giriş yapın.")
            )
            val response = api.withdraw(key, "Bearer $token", "eq.$couponId")
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(IllegalStateException("Kupon paylaşımı kaldırılamadı (HTTP ${response.code()})."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Paylaşım kaldırılırken bağlantı kesildi."))
        }
    }

    private suspend fun usableToken(): String? = authMutex.withLock {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (!token.isNullOrEmpty() && System.currentTimeMillis() + 60_000L < expiresAt) return@withLock token
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withLock null
        try {
            val response = api.refresh(key, body = PoolRefreshRequest(refreshToken))
            val body = response.body()
            if (response.isSuccessful && body?.accessToken != null && body.refreshToken != null) {
                save(body)
                return@withLock body.accessToken
            }
            signOut()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Transient failure should not discard a valid refresh token.
        }
    }

    private fun save(body: PoolAuthResponse, providerOverride: String = "") {
        val existingProvider = prefs.getString(KEY_PROVIDER, null).orEmpty()
        val resolvedProvider = body.user?.appMetadata?.provider
            ?.takeIf { it.isNotBlank() }
            ?: providerOverride.takeIf { it.isNotBlank() }
            ?: existingProvider.ifBlank { "email" }

        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, body.accessToken)
            .putString(KEY_REFRESH_TOKEN, body.refreshToken)
            .putString(KEY_USER_ID, body.user?.id?.takeIf { it.isNotBlank() } ?: prefs.getString(KEY_USER_ID, null))
            .putLong(KEY_EXPIRES_AT_MS, System.currentTimeMillis() + (body.expiresIn ?: 3600L) * 1000L)
            .putString(KEY_PROVIDER, resolvedProvider)

        body.user?.let { writeUserFields(editor, it) }
        editor.apply()
        _sessionState.value = readStoredSessionState()
    }

    private fun saveUserDetails(user: PoolAuthUser) {
        val editor = prefs.edit()
        writeUserFields(editor, user)
        editor.apply()
        _sessionState.value = readStoredSessionState()
    }

    private fun writeUserFields(editor: android.content.SharedPreferences.Editor, user: PoolAuthUser) {
        if (user.id.isNotBlank()) editor.putString(KEY_USER_ID, user.id)
        user.email?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_EMAIL, it) }
        user.appMetadata?.provider?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_PROVIDER, it) }
        val resolvedName = listOfNotNull(
            user.userMetadata?.username?.takeIf { it.isNotBlank() },
            user.userMetadata?.fullName?.takeIf { it.isNotBlank() },
            user.userMetadata?.name?.takeIf { it.isNotBlank() },
            user.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ).firstOrNull()
        resolvedName?.let { editor.putString(KEY_DISPLAY_NAME, it) }
        user.userMetadata?.favoriteTeam?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_FAVORITE_TEAM, it) }
        user.createdAt?.take(10)?.let { editor.putString(KEY_CREATED_AT, it) }
        editor.putBoolean(KEY_EMAIL_VERIFIED, !user.emailConfirmedAt.isNullOrBlank())
    }

    private fun readStoredSessionState(): MemberSessionState {
        val signedIn = hasSession
        return MemberSessionState(
            isSignedIn = signedIn,
            userId = prefs.getString(KEY_USER_ID, null).orEmpty(),
            email = prefs.getString(KEY_EMAIL, null).orEmpty(),
            displayName = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty(),
            provider = prefs.getString(KEY_PROVIDER, null).orEmpty(),
            favoriteTeamId = prefs.getString(KEY_FAVORITE_TEAM, null).orEmpty(),
            createdAtText = prefs.getString(KEY_CREATED_AT, null).orEmpty(),
            emailVerified = prefs.getBoolean(KEY_EMAIL_VERIFIED, false)
        )
    }

    private fun formatOAuthError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("provider is not enabled") || lower.contains("unsupported provider") ->
                "Bu giriş sağlayıcısı Supabase projenizde henüz aktif değil. Supabase Dashboard > Authentication > Providers bölümünden Google / Apple ID'yi etkinleştirin ve Redirect URL olarak '$AUTH_REDIRECT_URI' ekleyin."
            lower.contains("access_denied") || lower.contains("cancel") ->
                "Giriş işlemi kullanıcı tarafından iptal edildi."
            else -> "Giriş tamamlanamadı: $raw"
        }
    }

    private fun isValidCredentials(email: String, password: String): Boolean =
        email.trim().contains('@') && password.length >= 6

    companion object {
        const val AUTH_REDIRECT_URI = "sportoto://auth-callback"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
        private const val KEY_EMAIL = "member_email"
        private const val KEY_DISPLAY_NAME = "member_display_name"
        private const val KEY_PROVIDER = "member_provider"
        private const val KEY_FAVORITE_TEAM = "member_favorite_team"
        private const val KEY_CREATED_AT = "member_created_at"
        private const val KEY_EMAIL_VERIFIED = "member_email_verified"
        private const val KEY_PENDING_PKCE_VERIFIER = "pending_pkce_verifier"
        private const val KEY_PENDING_OAUTH_PROVIDER = "pending_oauth_provider"

        internal fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        internal fun computeCodeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        internal fun parseQueryParams(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split('&')
                .mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val idx = pair.indexOf('=')
                    val k = if (idx >= 0) pair.substring(0, idx) else pair
                    val v = if (idx >= 0) pair.substring(idx + 1) else ""
                    val decodedKey = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
                    val decodedVal = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                    decodedKey to decodedVal
                }
                .toMap()
        }
    }
}
