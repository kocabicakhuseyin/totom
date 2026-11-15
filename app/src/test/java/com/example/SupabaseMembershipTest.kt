package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.network.NetworkModule
import com.example.network.PoolApiService
import com.example.repository.OAuthProvider
import com.example.repository.PoolRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseMembershipTest {

    private lateinit var context: Context
    private lateinit var server: HttpServer
    private lateinit var api: PoolApiService
    private lateinit var repository: PoolRepository

    private var lastRequestPath: String = ""
    private var lastRequestMethod: String = ""
    private var lastRequestBody: String = ""
    private var currentProvider: String = "email"
    private var currentEmail: String = "uye@example.com"
    private var currentUsername: String = "toto_master"
    private var currentFavoriteTeam: String = ""
    private var signUpRequiresConfirmation: Boolean = false
    private var logoutCalled: Boolean = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("toto_pool_session", Context.MODE_PRIVATE).edit().clear().apply()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            handleRequest(exchange)
        }
        server.start()

        val baseUrl = "http://127.0.0.1:${server.address.port}/"
        api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.moshi))
            .build()
            .create(PoolApiService::class.java)

        repository = PoolRepository(context, api)
    }

    @After
    fun tearDown() {
        server.stop(0)
        context.getSharedPreferences("toto_pool_session", Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun handleRequest(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val pathWithQuery = buildString {
            append(uri.path)
            if (!uri.rawQuery.isNullOrBlank()) {
                append('?')
                append(uri.rawQuery)
            }
        }
        val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        lastRequestPath = pathWithQuery
        lastRequestMethod = exchange.requestMethod
        lastRequestBody = body

        val (status, json) = when {
            uri.path == "/auth/v1/signup" && exchange.requestMethod == "POST" -> {
                if (signUpRequiresConfirmation) {
                    200 to """{"user":{"id":"u-confirm","email":"yeni@example.com","user_metadata":{"username":"YeniUye"}}}"""
                } else {
                    currentProvider = "email"
                    currentEmail = "yeni@example.com"
                    currentUsername = "YeniUye"
                    200 to authPayloadJson()
                }
            }
            uri.path == "/auth/v1/token" && uri.rawQuery?.contains("grant_type=pkce") == true -> {
                200 to authPayloadJson()
            }
            uri.path == "/auth/v1/token" && uri.rawQuery?.contains("grant_type=password") == true -> {
                currentProvider = "email"
                200 to authPayloadJson()
            }
            uri.path == "/auth/v1/user" && exchange.requestMethod == "GET" -> {
                200 to userJson()
            }
            uri.path == "/auth/v1/user" && exchange.requestMethod == "PUT" -> {
                if (body.contains("KartalToto")) currentUsername = "KartalToto"
                if (body.contains("BESIKTAS")) currentFavoriteTeam = "BESIKTAS"
                200 to userJson()
            }
            uri.path == "/auth/v1/recover" && exchange.requestMethod == "POST" -> {
                200 to "{}"
            }
            uri.path == "/auth/v1/logout" && exchange.requestMethod == "POST" -> {
                logoutCalled = true
                204 to ""
            }
            else -> 404 to """{"error":"not_found"}"""
        }

        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        } else {
            exchange.responseBody.close()
        }
    }

    private fun userJson(): String = """
        {
          "id": "user-1234-5678",
          "email": "$currentEmail",
          "created_at": "2026-11-14T12:00:00Z",
          "email_confirmed_at": "2026-11-14T12:00:00Z",
          "app_metadata": { "provider": "$currentProvider", "providers": ["$currentProvider"] },
          "user_metadata": { "username": "$currentUsername", "favorite_team": "$currentFavoriteTeam" }
        }
    """.trimIndent()

    private fun authPayloadJson(): String = """
        {
          "access_token": "acc_token_abc",
          "refresh_token": "ref_token_xyz",
          "expires_in": 3600,
          "user": ${userJson()}
        }
    """.trimIndent()

    @Test
    fun `buildOAuthAuthorizeUrl generates PKCE S256 redirect for Google and Apple`() {
        val googleUrl = repository.buildOAuthAuthorizeUrl(OAuthProvider.GOOGLE)
        assertTrue(googleUrl.contains("/auth/v1/authorize?provider=google"))
        assertTrue(googleUrl.contains("redirect_to=sportoto%3A%2F%2Fauth-callback"))
        assertTrue(googleUrl.contains("code_challenge_method=S256"))
        assertTrue(googleUrl.contains("code_challenge="))
        assertTrue(googleUrl.contains("prompt=select_account"))

        val appleUrl = repository.buildOAuthAuthorizeUrl(OAuthProvider.APPLE)
        assertTrue(appleUrl.contains("/auth/v1/authorize?provider=apple"))
        assertTrue(appleUrl.contains("redirect_to=sportoto%3A%2F%2Fauth-callback"))
        assertTrue(appleUrl.contains("code_challenge_method=S256"))
        assertFalse(appleUrl.contains("prompt=select_account"))
    }

    @Test
    fun `handleOAuthCallbackUrl exchanges PKCE code and populates Google session`() = runBlocking {
        currentProvider = "google"
        currentEmail = "google.user@gmail.com"
        currentUsername = "GoogleUye"
        repository.buildOAuthAuthorizeUrl(OAuthProvider.GOOGLE)

        val result = repository.handleOAuthCallbackUrl("sportoto://auth-callback?code=auth_code_google_123")
        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertTrue(session.isSignedIn)
        assertEquals("google", session.provider)
        assertEquals("Google Hesabı", session.providerDisplayLabel)
        assertEquals("google.user@gmail.com", session.email)
        assertEquals("GoogleUye", session.displayName)
        assertTrue(session.emailVerified)
    }

    @Test
    fun `handleOAuthCallbackUrl supports Apple ID implicit hash fragment fallback`() = runBlocking {
        currentProvider = "apple"
        currentEmail = "apple.id@privaterelay.appleid.com"
        currentUsername = "AppleUye"
        repository.buildOAuthAuthorizeUrl(OAuthProvider.APPLE)

        val result = repository.handleOAuthCallbackUrl(
            "sportoto://auth-callback#access_token=frag_access&refresh_token=frag_refresh&expires_in=3600"
        )
        assertTrue(result.isSuccess)
        val session = repository.sessionState.value
        assertTrue(session.isSignedIn)
        assertEquals("apple", session.provider)
        assertEquals("Apple ID", session.providerDisplayLabel)
        assertEquals("AppleUye", session.displayName)
    }

    @Test
    fun `handleOAuthCallbackUrl formats disabled provider error cleanly in Turkish`() = runBlocking {
        val result = repository.handleOAuthCallbackUrl(
            "sportoto://auth-callback?error=validation_failed&error_description=Unsupported+provider%3A+provider+is+not+enabled"
        )
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("Supabase"))
        assertTrue(msg.contains("sportoto://auth-callback"))
    }

    @Test
    fun `email registration signIn profile update password reset and logout work end to end`() = runBlocking {
        signUpRequiresConfirmation = false
        val regResult = repository.register("yeni@example.com", "secret123", "YeniUye")
        assertTrue(regResult.isSuccess)
        assertTrue(regResult.getOrThrow())
        assertTrue(repository.sessionState.value.isSignedIn)

        val updateResult = repository.updateMemberProfile(
            username = "KartalToto",
            favoriteTeamId = "BESIKTAS"
        )
        assertTrue(updateResult.isSuccess)
        assertEquals("KartalToto", updateResult.getOrThrow().displayName)
        assertEquals("BESIKTAS", updateResult.getOrThrow().favoriteTeamId)

        val resetResult = repository.sendPasswordReset("yeni@example.com")
        assertTrue(resetResult.isSuccess)
        assertEquals("/auth/v1/recover", lastRequestPath)

        repository.signOutAsync()
        assertTrue(logoutCalled)
        assertFalse(repository.hasSession)
        assertFalse(repository.sessionState.value.isSignedIn)
    }
}
