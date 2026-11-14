package com.example.network

import com.example.BuildConfig
import com.example.model.Coupon
import com.example.model.PlatformCredentials
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@JsonClass(generateAdapter = true)
data class CloudflareColumnPayload(
    val index: Int,
    val predictions: List<String>
)

@JsonClass(generateAdapter = true)
data class CloudflareAutoPlayRequest(
    val username: String,
    val password: String,
    val couponTitle: String,
    val totalCostTL: Double,
    val dryRun: Boolean = true,
    val columns: List<CloudflareColumnPayload>
)

@JsonClass(generateAdapter = true)
data class CloudflareAutoPlayResponse(
    val success: Boolean = false,
    val status: String = "ERROR",
    val sessionId: String = "",
    val playedColumnsCount: Int = 0,
    val steps: List<String> = emptyList(),
    val screenshotBase64: String? = null,
    val errorMessage: String? = null,
    val error: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val code: String? = null
)

data class CloudflareRestPreviewResult(
    val success: Boolean,
    val screenshotBytes: ByteArray? = null,
    val statusMessage: String,
    val httpCode: Int = 0
)

enum class CloudflareRunStatus {
    COMPLETED,
    NO_BULLETIN,
    INSUFFICIENT_BALANCE,
    REQUIRES_2FA,
    WAF_BLOCKED,
    LOGIN_FAILED,
    NOT_CONFIGURED,
    ERROR
}

data class CloudflareWorkerRunResult(
    val status: CloudflareRunStatus,
    val sessionId: String = "",
    val playedColumnsCount: Int = 0,
    val steps: List<String> = emptyList(),
    val screenshotBase64: String? = null,
    val errorMessage: String? = null
)

data class CloudflareWorkerHealthResult(
    val ok: Boolean,
    val browserBindingReady: Boolean = false,
    val authEnabled: Boolean = false,
    val message: String,
    val httpCode: Int = 0
)

@JsonClass(generateAdapter = true)
data class CloudflareWorkerHealthPayload(
    val ok: Boolean = false,
    val browserBindingReady: Boolean = false,
    val authEnabled: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
    val detail: String? = null
)

object CloudflareBrowserRunner {

    private const val WORKER_CALL_TIMEOUT_SECONDS = 40L
    private const val WORKER_COROUTINE_GUARD_MS = 42_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(WORKER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun executeCancellable(request: Request): Response {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }
    }

    fun isRestApiConfigured(
        accountId: String = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
        apiToken: String = BuildConfig.CLOUDFLARE_API_TOKEN
    ): Boolean {
        return accountId.isNotBlank() &&
            !accountId.startsWith("YOUR_") &&
            apiToken.isNotBlank() &&
            !apiToken.startsWith("YOUR_")
    }

    fun isWorkerConfigured(
        workerUrl: String = BuildConfig.CLOUDFLARE_BROWSER_WORKER_URL
    ): Boolean {
        val trimmed = workerUrl.trim()
        if (trimmed.isBlank() || trimmed.contains("example.workers.dev")) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return false
        if (host.contains("dash.cloudflare.com")) return false
        return scheme == "https" ||
            (scheme == "http" && (host == "127.0.0.1" || host == "localhost"))
    }

    /**
     * Calls Cloudflare Browser Rendering REST API (/browser-rendering/screenshot)
     * to test live Cloudflare Browser Run rendering against nesine.com/spor-toto.
     */
    suspend fun captureNesineBulletinScreenshot(
        accountId: String = BuildConfig.CLOUDFLARE_ACCOUNT_ID,
        apiToken: String = BuildConfig.CLOUDFLARE_API_TOKEN
    ): CloudflareRestPreviewResult = withContext(Dispatchers.IO) {
        if (!isRestApiConfigured(accountId, apiToken)) {
            return@withContext CloudflareRestPreviewResult(
                success = false,
                statusMessage = "Cloudflare Secrets eksik: AI Studio Secrets panelinden CLOUDFLARE_ACCOUNT_ID ve CLOUDFLARE_API_TOKEN tanımlayın."
            )
        }

        val endpoint = "https://api.cloudflare.com/client/v4/accounts/${accountId.trim()}/browser-rendering/screenshot"
        val bodyJson = """
            {
              "url": "https://www.nesine.com/sportoto",
              "viewport": { "width": 1280, "height": 800 },
              "gotoOptions": { "waitUntil": "domcontentloaded", "timeout": 18000 },
              "screenshotOptions": { "fullPage": false, "type": "png" }
            }
        """.trimIndent()

        try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${apiToken.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            val result = withTimeoutOrNull(WORKER_COROUTINE_GUARD_MS) {
                executeCancellable(request).use { response ->
                    val rawBytes = response.body?.bytes()
                    if (response.isSuccessful && rawBytes != null && rawBytes.isNotEmpty()) {
                        CloudflareRestPreviewResult(
                            success = true,
                            screenshotBytes = rawBytes,
                            statusMessage = "Cloudflare Browser Run (/screenshot) başarılı! Nesine Spor Toto görüntüsü alındı.",
                            httpCode = response.code
                        )
                    } else {
                        val errorText = rawBytes?.decodeToString()?.take(220) ?: response.message
                        CloudflareRestPreviewResult(
                            success = false,
                            statusMessage = "Cloudflare Browser Run hatası (HTTP ${response.code}): $errorText",
                            httpCode = response.code
                        )
                    }
                }
            }
            result ?: CloudflareRestPreviewResult(
                success = false,
                statusMessage = "Cloudflare /screenshot zaman aşımına uğradı (${WORKER_CALL_TIMEOUT_SECONDS} sn). Uygulama İçi Tarayıcı Robotu (WebView) ile devam edebilirsiniz."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is SocketTimeoutException, is InterruptedIOException ->
                    "Cloudflare /screenshot bağlantısı zaman aşımına uğradı (${WORKER_CALL_TIMEOUT_SECONDS} sn)."
                else ->
                    "Cloudflare bağlantı hatası: ${e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
            }
            CloudflareRestPreviewResult(
                success = false,
                statusMessage = message
            )
        }
    }

    /**
     * Fast <1s GET /health probe verifying Worker reachability, token auth, and BROWSER binding.
     */
    suspend fun testWorkerConnection(
        workerUrl: String = BuildConfig.CLOUDFLARE_BROWSER_WORKER_URL,
        workerToken: String = BuildConfig.CLOUDFLARE_API_TOKEN
    ): CloudflareWorkerHealthResult = withContext(Dispatchers.IO) {
        val normalizedUrl = workerUrl.trim().trimEnd('/')
        if (!isWorkerConfigured(normalizedUrl)) {
            return@withContext CloudflareWorkerHealthResult(
                ok = false,
                message = "Geçerli bir Cloudflare Worker HTTPS adresi girin (ör. https://nesine-browser-runner.<ad>.workers.dev)."
            )
        }

        val healthUrl = if (normalizedUrl.endsWith("/autoplay")) {
            normalizedUrl.removeSuffix("/autoplay") + "/health"
        } else {
            "$normalizedUrl/health"
        }

        try {
            val reqBuilder = Request.Builder().url(healthUrl).get()
            val cleanToken = workerToken.trim()
            if (cleanToken.isNotBlank() && !cleanToken.startsWith("YOUR_")) {
                reqBuilder.addHeader("Authorization", "Bearer $cleanToken")
                reqBuilder.addHeader("X-Worker-Token", cleanToken)
            }

            val result = withTimeoutOrNull(12_000L) {
                executeCancellable(reqBuilder.build()).use { response ->
                    val raw = response.body?.string().orEmpty()
                    val payloadAdapter = NetworkModule.moshi.adapter(CloudflareWorkerHealthPayload::class.java)
                    val parsed = runCatching { payloadAdapter.fromJson(raw) }.getOrNull()
                    if (response.code == 401 || response.code == 403) {
                        val serverErr = parsed?.errorMessage?.takeIf { it.isNotBlank() }
                            ?: parsed?.detail?.takeIf { it.isNotBlank() }
                        return@use CloudflareWorkerHealthResult(
                            ok = false,
                            httpCode = response.code,
                            message = "❌ Yetkisiz erişim (HTTP ${response.code}): ${serverErr ?: "WORKER_AUTH_TOKEN eşleşmiyor."}"
                        )
                    }
                    if (!response.isSuccessful) {
                        val serverMsg = parsed?.message?.takeIf { it.isNotBlank() }
                            ?: parsed?.errorMessage?.takeIf { it.isNotBlank() }
                            ?: raw.take(150).ifBlank { response.message }
                        return@use CloudflareWorkerHealthResult(
                            ok = false,
                            httpCode = response.code,
                            message = "❌ Worker hatası (HTTP ${response.code}): $serverMsg"
                        )
                    }
                    val ok = parsed?.ok == true
                    val browserReady = parsed?.browserBindingReady == true
                    val authEnabled = parsed?.authEnabled == true
                    val serverMsg = parsed?.message?.takeIf { it.isNotBlank() }
                    if (ok && browserReady) {
                        CloudflareWorkerHealthResult(
                            ok = true,
                            browserBindingReady = true,
                            authEnabled = authEnabled,
                            httpCode = response.code,
                            message = "✅ Bağlantı başarılı! Browser Rendering (BROWSER) aktif." +
                                if (authEnabled) " (Token korumalı)" else " (Token şifresiz)"
                        )
                    } else {
                        CloudflareWorkerHealthResult(
                            ok = false,
                            browserBindingReady = browserReady,
                            authEnabled = authEnabled,
                            httpCode = response.code,
                            message = "⚠️ ${serverMsg ?: "Worker ayarı eksik: Settings > Bindings > BROWSER ekleyin."}"
                        )
                    }
                }
            }

            result ?: CloudflareWorkerHealthResult(
                ok = false,
                message = "❌ Worker /health 12 sn içinde yanıt vermedi. URL adresini kontrol edin."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudflareWorkerHealthResult(
                ok = false,
                message = "❌ Bağlantı hatası: ${e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Sends the coupon columns and credentials to the Cloudflare Browser Rendering Worker
     * (@cloudflare/puppeteer) to run interactive browser automation on nesine.com/spor-toto.
     */
    suspend fun runNesineAutoPlayWorker(
        coupon: Coupon,
        credentials: PlatformCredentials,
        dryRun: Boolean = true,
        workerUrl: String = BuildConfig.CLOUDFLARE_BROWSER_WORKER_URL,
        apiToken: String = BuildConfig.CLOUDFLARE_API_TOKEN
    ): CloudflareWorkerRunResult = withContext(Dispatchers.IO) {
        val normalizedUrl = workerUrl.trim()
        if (!isWorkerConfigured(normalizedUrl)) {
            return@withContext CloudflareWorkerRunResult(
                status = CloudflareRunStatus.NOT_CONFIGURED,
                steps = listOf(
                    "Cloudflare Browser Worker URL geçerli değil veya yapılandırılmamış.",
                    "Proje kökündeki cloudflare-worker/ klasörünü `npx wrangler deploy` ile Cloudflare'a dağıtın.",
                    "AI Studio Secrets paneline gerçek `https://...workers.dev` adresini CLOUDFLARE_BROWSER_WORKER_URL olarak ekleyin."
                ),
                errorMessage = "Cloudflare Browser Worker yapılandırılmadı veya geçersiz URL. Uygulama İçi Tarayıcı Robotu (WebView) ile hemen oynayabilirsiniz."
            )
        }

        val payload = buildAutoPlayRequestPayload(coupon, credentials, dryRun)
        val adapter = NetworkModule.moshi.adapter(CloudflareAutoPlayRequest::class.java)
        val jsonBody = adapter.toJson(payload)

        try {
            val reqBuilder = Request.Builder()
                .url(normalizedUrl)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(jsonMediaType))

            val cleanToken = apiToken.trim()
            if (cleanToken.isNotBlank() && !cleanToken.startsWith("YOUR_")) {
                reqBuilder.addHeader("Authorization", "Bearer $cleanToken")
                reqBuilder.addHeader("X-Worker-Token", cleanToken)
            }

            val result = withTimeoutOrNull(WORKER_COROUTINE_GUARD_MS) {
                executeCancellable(reqBuilder.build()).use { response ->
                    val responseStr = response.body?.string().orEmpty()
                    val respAdapter = NetworkModule.moshi.adapter(CloudflareAutoPlayResponse::class.java)
                    val parsed = runCatching { respAdapter.fromJson(responseStr) }.getOrNull()

                    if (parsed != null) {
                        val rawServerError = listOfNotNull(
                            parsed.errorMessage?.takeIf { it.isNotBlank() },
                            parsed.detail?.takeIf { it.isNotBlank() },
                            parsed.error?.takeIf { it.isNotBlank() },
                            parsed.message?.takeIf { it.isNotBlank() }
                        ).firstOrNull()

                        val mappedStatus = when {
                            parsed.code.equals("NO_BULLETIN", ignoreCase = true) ||
                                parsed.status.equals("NO_BULLETIN", ignoreCase = true) ->
                                CloudflareRunStatus.NO_BULLETIN
                            parsed.code.equals("INSUFFICIENT_BALANCE", ignoreCase = true) ||
                                parsed.status.equals("INSUFFICIENT_BALANCE", ignoreCase = true) ->
                                CloudflareRunStatus.INSUFFICIENT_BALANCE
                            parsed.status.equals("COMPLETED", ignoreCase = true) && parsed.success ->
                                CloudflareRunStatus.COMPLETED
                            parsed.status.equals("REQUIRES_2FA", ignoreCase = true) ->
                                CloudflareRunStatus.REQUIRES_2FA
                            parsed.status.equals("WAF_BLOCKED", ignoreCase = true) ->
                                CloudflareRunStatus.WAF_BLOCKED
                            parsed.status.equals("LOGIN_FAILED", ignoreCase = true) ->
                                CloudflareRunStatus.LOGIN_FAILED
                            else -> CloudflareRunStatus.ERROR
                        }

                        val resolvedErrorMessage = when (mappedStatus) {
                            CloudflareRunStatus.COMPLETED -> null
                            CloudflareRunStatus.NO_BULLETIN -> rawServerError ?: "Yeni bülten yüklenmedi"
                            CloudflareRunStatus.INSUFFICIENT_BALANCE -> rawServerError ?: "Bakiye yetersiz"
                            else -> formatWorkerHttpOrPayloadError(
                                httpCode = response.code,
                                httpMessage = response.message,
                                rawError = rawServerError,
                                steps = parsed.steps,
                                rawBody = responseStr
                            )
                        }

                        val resolvedSteps = if (parsed.steps.isNotEmpty()) {
                            parsed.steps
                        } else if (!response.isSuccessful || mappedStatus == CloudflareRunStatus.ERROR) {
                            listOf("Cloudflare Worker HTTP ${response.code} yanıtı verdi.")
                        } else {
                            emptyList()
                        }

                        CloudflareWorkerRunResult(
                            status = mappedStatus,
                            sessionId = parsed.sessionId,
                            playedColumnsCount = parsed.playedColumnsCount,
                            steps = resolvedSteps,
                            screenshotBase64 = parsed.screenshotBase64,
                            errorMessage = resolvedErrorMessage
                        )
                    } else {
                        CloudflareWorkerRunResult(
                            status = CloudflareRunStatus.ERROR,
                            steps = listOf("Cloudflare Worker HTTP ${response.code} yanıtı verdi."),
                            errorMessage = formatWorkerHttpOrPayloadError(
                                httpCode = response.code,
                                httpMessage = response.message,
                                rawError = null,
                                steps = emptyList(),
                                rawBody = responseStr
                            )
                        )
                    }
                }
            }

            result ?: CloudflareWorkerRunResult(
                status = CloudflareRunStatus.ERROR,
                steps = listOf(
                    "Cloudflare Worker ${WORKER_CALL_TIMEOUT_SECONDS} saniye içinde yanıt vermedi (zaman aşımı).",
                    "Nesine bulut oturumu yavaşladıysa Uygulama İçi Tarayıcı Robotu (WebView) ile hemen işaretleyebilirsiniz."
                ),
                errorMessage = "Cloudflare sunucusu ${WORKER_CALL_TIMEOUT_SECONDS} sn içinde yanıt vermedi. Worker URL'nizi kontrol edin veya Uygulama İçi Tarayıcı Robotunu (WebView) kullanın."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isTimeout = e is SocketTimeoutException || e is InterruptedIOException
            val errMsg = if (isTimeout) {
                "Cloudflare sunucusu ${WORKER_CALL_TIMEOUT_SECONDS} sn içinde yanıt vermedi (zaman aşımı). Uygulama İçi Tarayıcı Robotunu (WebView) kullanabilirsiniz."
            } else {
                e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "${e.javaClass.simpleName}: Worker bağlantısı kurulamadı."
            }
            CloudflareWorkerRunResult(
                status = CloudflareRunStatus.ERROR,
                steps = listOf(if (isTimeout) "Worker bağlantısı zaman aşımına uğradı." else "Worker çağrısı sırasında ağ hatası oluştu."),
                errorMessage = errMsg
            )
        }
    }

    private fun formatWorkerHttpOrPayloadError(
        httpCode: Int,
        httpMessage: String,
        rawError: String?,
        steps: List<String>,
        rawBody: String
    ): String {
        val baseDetail = rawError
            ?: steps.lastOrNull()?.takeIf { it.isNotBlank() }
            ?: rawBody.trim().take(200).takeIf { it.isNotBlank() }
            ?: httpMessage.takeIf { it.isNotBlank() }

        return when (httpCode) {
            401, 403 -> "Yetkisiz erişim (HTTP $httpCode): Worker tarafındaki WORKER_AUTH_TOKEN ile uygulama anahtarı eşleşmiyor" +
                (baseDetail?.let { " ($it)" } ?: "") +
                ". İsteğe bağlı olarak Worker Bağlantı Ayarları'ndan WORKER_AUTH_TOKEN girebilirsiniz."
            404 -> "Worker adresi bulunamadı (HTTP 404): Worker URL'nizi ve cloudflare-worker/ dağıtımını kontrol edin" +
                (baseDetail?.let { " ($it)" } ?: ".")
            405 -> "Worker bu adreste POST isteği kabul etmiyor (HTTP 405): ${baseDetail ?: "Worker URL'sini kontrol edin."}"
            502, 504, 522, 524 -> "Cloudflare Worker zaman aşımı veya geçit hatası (HTTP $httpCode). Tarayıcı oturumu zamanında yanıt vermedi; Uygulama İçi Tarayıcı Robotunu (WebView) kullanabilirsiniz."
            else -> baseDetail ?: "Cloudflare Worker geçersiz yanıt döndürdü (HTTP $httpCode)."
        }
    }

    fun buildAutoPlayRequestPayload(
        coupon: Coupon,
        credentials: PlatformCredentials,
        dryRun: Boolean
    ): CloudflareAutoPlayRequest {
        return CloudflareAutoPlayRequest(
            username = credentials.username,
            password = credentials.password,
            couponTitle = coupon.title,
            totalCostTL = coupon.totalCostTL,
            dryRun = dryRun,
            columns = coupon.columns.map { col ->
                CloudflareColumnPayload(
                    index = col.index,
                    predictions = col.predictions.map { it.code }
                )
            }
        )
    }
}
