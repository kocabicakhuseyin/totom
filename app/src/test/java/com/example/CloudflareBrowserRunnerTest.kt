package com.example

import com.example.engine.NesineAutoPlayJsInjector
import com.example.model.BettingPlatform
import com.example.model.Coupon
import com.example.model.FormulaType
import com.example.model.PlatformCredentials
import com.example.model.PredictionOutcome
import com.example.model.TotoColumn
import com.example.network.CloudflareBrowserRunner
import com.example.network.CloudflareRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareBrowserRunnerTest {

    private fun sampleCoupon(): Coupon {
        val picks = List(15) { idx ->
            when (idx % 3) {
                0 -> PredictionOutcome.HOME
                1 -> PredictionOutcome.DRAW
                else -> PredictionOutcome.AWAY
            }
        }
        return Coupon(
            id = "c_test",
            title = "Test Kupon",
            formulaType = FormulaType.GUARANTEE_14,
            columns = listOf(
                TotoColumn(index = 1, predictions = picks),
                TotoColumn(index = 2, predictions = picks.reversed())
            )
        )
    }

    @Test
    fun testPlaceholderSecretsAreDetectedAsNotConfigured() {
        assertFalse(
            CloudflareBrowserRunner.isRestApiConfigured(
                accountId = "YOUR_CLOUDFLARE_ACCOUNT_ID",
                apiToken = "YOUR_CLOUDFLARE_API_TOKEN"
            )
        )
        assertFalse(
            CloudflareBrowserRunner.isWorkerConfigured(
                workerUrl = "https://nesine-browser-runner.example.workers.dev"
            )
        )
        assertTrue(
            CloudflareBrowserRunner.isRestApiConfigured(
                accountId = "a1b2c3d4e5f6",
                apiToken = "cf_live_token_123"
            )
        )
        assertTrue(
            CloudflareBrowserRunner.isWorkerConfigured(
                workerUrl = "https://nesine-browser-runner.myaccount.workers.dev"
            )
        )
    }

    @Test
    fun testBuildAutoPlayRequestPayloadSerializes15MatchesPerColumn() {
        val coupon = sampleCoupon()
        val creds = PlatformCredentials(
            platform = BettingPlatform.NESINE,
            username = "testuser",
            password = "secret"
        )
        val payload = CloudflareBrowserRunner.buildAutoPlayRequestPayload(coupon, creds, dryRun = true)

        assertEquals("testuser", payload.username)
        assertTrue(payload.dryRun)
        assertEquals(2, payload.columns.size)
        assertEquals(15, payload.columns[0].predictions.size)
        assertEquals(listOf("1", "X", "2"), payload.columns[0].predictions.take(3))
    }

    @Test
    fun testUnconfiguredWorkerReturnsNotConfiguredStatus() = runBlocking {
        val result = CloudflareBrowserRunner.runNesineAutoPlayWorker(
            coupon = sampleCoupon(),
            credentials = PlatformCredentials(BettingPlatform.NESINE, "u", "p"),
            dryRun = true,
            workerUrl = "https://nesine-browser-runner.example.workers.dev"
        )
        assertEquals(CloudflareRunStatus.NOT_CONFIGURED, result.status)
        assertEquals(0, result.playedColumnsCount)
    }

    @Test
    fun testJsInjectorEscapesCredentialsAndBuilds15MatchPicks() {
        val loginJs = NesineAutoPlayJsInjector.buildLoginFillScript("user'test", "pass\"123")
        assertTrue(loginJs.contains("user'test"))
        assertTrue(loginJs.contains("pass\\\"123"))
        assertTrue(loginJs.contains("if (true &&"))

        val loginNoSubmitJs = NesineAutoPlayJsInjector.buildLoginFillScript("u", "p", autoSubmit = false)
        assertTrue(loginNoSubmitJs.contains("if (false &&"))

        val colJs = NesineAutoPlayJsInjector.buildColumnFillScript(sampleCoupon().columns.first())
        assertTrue(colJs.contains("[\"1\",\"X\",\"2\""))
        assertTrue(colJs.contains("Kolon #1"))
        assertTrue(colJs.contains("m-c-\" + m + \"-0-"))

        val colSlot3Js = NesineAutoPlayJsInjector.buildColumnFillScript(sampleCoupon().columns[1], slotIndex = 3)
        assertTrue(colSlot3Js.contains("m-c-\" + m + \"-3-"))
        assertTrue(colSlot3Js.contains("Sütun 4"))
    }

    @Test
    fun testJsInjectorGeneratesSessionCheckAndPlayConfirmScripts() {
        val cookieJs = NesineAutoPlayJsInjector.buildCookieBannerDismissScript()
        assertTrue(cookieJs.contains("kabul et"))
        assertTrue(cookieJs.contains("onetrust-accept-btn-handler"))

        val sessionJs = NesineAutoPlayJsInjector.buildSessionCheckScript()
        assertTrue(sessionJs.contains("loggedIn"))
        assertTrue(sessionJs.contains("requires2fa"))
        assertTrue(sessionJs.contains("hasBulletin"))
        assertTrue(sessionJs.contains("hasConfirmView"))
        assertTrue(sessionJs.contains("hasReceiptView"))
        assertTrue(sessionJs.contains("insufficientBalance"))
        assertTrue(sessionJs.contains("hesabım"))
        assertTrue(sessionJs.contains("giriş yap"))
        assertTrue(sessionJs.contains("üye ol"))

        val returnJs = NesineAutoPlayJsInjector.buildReturnToBulletinScript()
        assertTrue(returnJs.contains("CLICKED_NEW_COUPON"))
        assertTrue(returnJs.contains("CLICKED_BACK_FROM_CONFIRM"))
        assertTrue(returnJs.contains("yeni kupon"))

        val clearJs = NesineAutoPlayJsInjector.buildClearGridScript()
        assertTrue(clearJs.contains("slot < 4"))
        assertTrue(clearJs.contains("m < 15"))
        assertTrue(clearJs.contains("m-c-"))

        val playJs = NesineAutoPlayJsInjector.buildPlayAndConfirmScript()
        assertTrue(playJs.contains("NOT_LOGGED_IN"))
        assertTrue(playJs.contains("REQUIRES_2FA"))
        assertTrue(playJs.contains("CONFIRMED"))
        assertTrue(playJs.contains("PLAY_CLICKED"))
        assertTrue(playJs.contains("kuponu onayla"))
        assertTrue(playJs.contains("hemen oyna"))
        assertTrue(playJs.contains("Kupon onayı tıklandı"))

        val autoSlot48Js = NesineAutoPlayJsInjector.buildColumnFillAutoSlotScript(
            column = sampleCoupon().columns.first(),
            rawIndex = 47
        )
        assertTrue(autoSlot48Js.contains("m-c-0-47-0"))
        assertTrue(autoSlot48Js.contains("var targetSlot = hasExtended ? 47 : 3;"))
        assertTrue(autoSlot48Js.contains("usedExtendedSlot"))

        val advanceJs = NesineAutoPlayJsInjector.buildSlipAdvanceOrStateScript(clickCartOrNewSlip = true)
        assertTrue(advanceJs.contains("sepete ekle"))
        assertTrue(advanceJs.contains("checkedCount"))
        assertTrue(advanceJs.contains("hasSuccessNotice"))
    }

    @Test
    fun testWorkerHttpErrorBodiesNeverProduceNullOrUnknownError() {
        runBlocking {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            var statusToReturn = 401
            var bodyToReturn = """{"error":"Unauthorized"}"""
            server.createContext("/") { exchange ->
                val bytes = bodyToReturn.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(statusToReturn, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}/"
                val coupon = sampleCoupon()
                val creds = PlatformCredentials(BettingPlatform.NESINE, "u", "p")

                // Case 1: 401 Unauthorized
                val res401 = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, workerUrl = url, apiToken = "bad"
                )
                assertEquals(CloudflareRunStatus.ERROR, res401.status)
                assertTrue(res401.errorMessage.orEmpty().contains("401"))
                assertFalse(res401.errorMessage.orEmpty().contains("Bilinmeyen hata"))

                // Case 2: 404 Not Found
                statusToReturn = 404
                bodyToReturn = """{"errorMessage":"Not Found"}"""
                val res404 = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.ERROR, res404.status)
                assertTrue(res404.errorMessage.orEmpty().contains("404"))
                assertTrue(res404.errorMessage.orEmpty().contains("cloudflare-worker"))

                // Case 3: NO_BULLETIN
                statusToReturn = 200
                bodyToReturn = """{"success":false,"status":"NO_BULLETIN","errorMessage":"Yeni bülten yüklenmedi"}"""
                val resNoBulletin = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.NO_BULLETIN, resNoBulletin.status)
                assertEquals("Yeni bülten yüklenmedi", resNoBulletin.errorMessage)

                // Case 4: INSUFFICIENT_BALANCE
                statusToReturn = 200
                bodyToReturn = """{"success":false,"status":"INSUFFICIENT_BALANCE","errorMessage":"Bakiye yetersiz"}"""
                val resNoBalance = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.INSUFFICIENT_BALANCE, resNoBalance.status)
                assertEquals("Bakiye yetersiz", resNoBalance.errorMessage)

                // Case 5: 504 Gateway Timeout
                statusToReturn = 504
                bodyToReturn = """Gateway Time-out"""
                val res504 = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.ERROR, res504.status)
                assertTrue(res504.errorMessage.orEmpty().contains("504"))
                assertTrue(res504.errorMessage.orEmpty().contains("WebView"))
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun testWorkerConnectionHealthProbeHandlesOkUnauthorizedAndMissingBinding() {
        runBlocking {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            var healthStatus = 200
            var healthBody = """{"ok":true,"browserBindingReady":true,"authEnabled":false}"""
            var capturedPath = ""
            var capturedTokenHeader: String? = null
            server.createContext("/") { exchange ->
                capturedPath = exchange.requestURI.path
                capturedTokenHeader = exchange.requestHeaders.getFirst("X-Worker-Token")
                val bytes = healthBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(healthStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}"

                // 1. Healthy Worker with BROWSER binding
                val okResult = CloudflareBrowserRunner.testWorkerConnection(
                    workerUrl = "$baseUrl/autoplay",
                    workerToken = "secret_123"
                )
                assertTrue(okResult.ok)
                assertTrue(okResult.browserBindingReady)
                assertEquals("/health", capturedPath)
                assertEquals("secret_123", capturedTokenHeader)

                // 2. 401 Unauthorized
                healthStatus = 401
                healthBody = """{"errorMessage":"WORKER_AUTH_TOKEN eşleşmiyor"}"""
                val unauthResult = CloudflareBrowserRunner.testWorkerConnection(
                    workerUrl = baseUrl,
                    workerToken = "wrong"
                )
                assertFalse(unauthResult.ok)
                assertEquals(401, unauthResult.httpCode)
                assertTrue(unauthResult.message.contains("401"))

                // 3. 500 Missing BROWSER binding
                healthStatus = 500
                healthBody = """{"ok":false,"browserBindingReady":false,"message":"Settings > Bindings altında BROWSER binding eksik."}"""
                val missingBindingResult = CloudflareBrowserRunner.testWorkerConnection(
                    workerUrl = baseUrl,
                    workerToken = ""
                )
                assertFalse(missingBindingResult.ok)
                assertFalse(missingBindingResult.browserBindingReady)
                assertEquals(500, missingBindingResult.httpCode)
                assertTrue(missingBindingResult.message.contains("BROWSER"))
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun testDashboardAndMalformedUrlsAreRejectedBeforeNetworkCall() {
        assertFalse(CloudflareBrowserRunner.isWorkerConfigured("https://dash.cloudflare.com/123/workers"))
        assertFalse(CloudflareBrowserRunner.isWorkerConfigured("not a url"))
        assertFalse(CloudflareBrowserRunner.isWorkerConfigured("   "))
    }

    @Test
    fun testLegacyUnverifiedWorkerCompletedIsFlaggedAsLoginRequiredWhenNotDryRun() {
        runBlocking {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            var responseBody = """{"success":true,"status":"COMPLETED","playedColumnsCount":1,"steps":["3/5 '123' hesabı ile giriş kontrol ediliyor...","1 kolon işaretlendi."]}"""
            server.createContext("/") { exchange ->
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}/"
                val coupon = sampleCoupon()
                val creds = PlatformCredentials(BettingPlatform.NESINE, "u", "p")

                // Old unverified worker with dryRun=false must NOT falsely report COMPLETED
                val unverifiedRes = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, dryRun = false, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.LOGIN_FAILED, unverifiedRes.status)
                assertTrue(unverifiedRes.errorMessage.orEmpty().contains("WebView"))

                // DryRun preview with same response stays COMPLETED
                val previewRes = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, dryRun = true, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.COMPLETED, previewRes.status)

                // Updated worker with sessionVerified=true and playConfirmed=true reports COMPLETED
                responseBody = """{"success":true,"status":"COMPLETED","playedColumnsCount":1,"sessionVerified":true,"playConfirmed":true,"steps":["✅ Nesine oynama onayı verildi (Onayla)."]}"""
                val verifiedRes = CloudflareBrowserRunner.runNesineAutoPlayWorker(
                    coupon = coupon, credentials = creds, dryRun = false, workerUrl = url, apiToken = "tok"
                )
                assertEquals(CloudflareRunStatus.COMPLETED, verifiedRes.status)
                assertEquals(true, verifiedRes.sessionVerified)
                assertEquals(true, verifiedRes.playConfirmed)
            } finally {
                server.stop(0)
            }
        }
    }
}
