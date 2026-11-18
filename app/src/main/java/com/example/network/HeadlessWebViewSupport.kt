package com.example.network

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.sync.Mutex
import java.io.ByteArrayInputStream
import java.util.Locale

internal object HeadlessWebViewSupport {
    /**
     * Serializes background headless WebView scraping sessions so multiple
     * Chromium renderers never compete simultaneously in the same process.
     */
    val sessionMutex = Mutex()

    private const val CRASH_COOLDOWN_MS = 120_000L

    @Volatile
    private var lastRendererCrashAtMs: Long = 0L

    fun recordRendererCrash() {
        lastRendererCrashAtMs = System.currentTimeMillis()
    }

    fun clearCrashCooldown() {
        lastRendererCrashAtMs = 0L
    }

    fun isCrashCooldownActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastRendererCrashAtMs
        return last > 0L && (nowMs - last) < CRASH_COOLDOWN_MS
    }

    private val SafeDeadWebViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean = true
    }

    private val CHALLENGE_HOST_ALLOWLIST = listOf(
        "challenges.cloudflare.com",
        "hcaptcha.com"
    )

    private val BLOCKED_HOST_SUBSTRINGS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "facebook.net",
        "facebook.com/tr",
        "hotjar.com",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "mc.yandex.ru",
        "clarity.ms",
        "onesignal.com",
        "amplitude.com",
        "mixpanel.com",
        "sentry.io",
        "datadoghq.",
        "newrelic.com",
        "nr-data.net",
        "useinsider.com",
        "insider.",
        "segment.com",
        "segment.io",
        "branch.io",
        "adjust.com",
        "appsflyer.com",
        "gemius.pl",
        "scorecardresearch.com",
        "comscore.com",
        "quantserve.com",
        "fullstory.com",
        "smartlook.com",
        "mouseflow.com",
        "intercom.io",
        "zendesk.com",
        "crisp.chat",
        "tawk.to",
        "livechatinc.com",
        "cookiebot.com",
        "onetrust.com",
        "tiktok.com",
        "snapchat.com",
        "ads-twitter.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "braze.",
        "appboycdn.",
        "demdex.net",
        "omtrdc.net",
        "bat.bing.com",
        "ads.linkedin.com",
        "connect.facebook.net",
        "use.typekit.net"
    )

    private val BLOCKED_HEAVY_EXTENSIONS = listOf(
        ".mp4", ".webm", ".m3u8", ".mp3", ".wav", ".ogg", ".ts",
        ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".avif", ".bmp",
        ".map"
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun configureForScraping(
        webView: WebView,
        userAgent: String = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    ) {
        webView.apply {
            // Keep unattached scraper renderer at foreground priority while active so Android LMK
            // does not SIGKILL (code -1) the isolated sandboxed process as WAIVED background priority.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
            }
            // Proper measure + layout at a compact mobile viewport keeps responsive rows 1..15 visible
            // while avoiding huge backing buffers on unattached WebViews.
            val widthSpec = View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(915, View.MeasureSpec.EXACTLY)
            runCatching {
                measure(widthSpec, heightSpec)
                layout(0, 0, 412, 915)
            }
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            runCatching { settings.databaseEnabled = false }
            @Suppress("DEPRECATION")
            runCatching { settings.setGeolocationEnabled(false) }
            runCatching { settings.offscreenPreRaster = false }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { settings.safeBrowsingEnabled = false }
            }
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = userAgent
        }
    }

    fun maybeInterceptHeavyResource(request: WebResourceRequest?): WebResourceResponse? {
        val uri = request?.url ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (CHALLENGE_HOST_ALLOWLIST.any { host.contains(it) }) {
            return null
        }
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        val isTracker = BLOCKED_HOST_SUBSTRINGS.any { host.contains(it) }
        val isHeavyAsset = BLOCKED_HEAVY_EXTENSIONS.any { path.endsWith(it) }
        return if (isTracker || isHeavyAsset) {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "No Content",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        } else {
            null
        }
    }

    fun destroySafely(webView: WebView?, rendererAlreadyGone: Boolean) {
        if (webView == null) return
        if (!rendererAlreadyGone) {
            runCatching { webView.stopLoading() }
            runCatching { webView.onPause() }
        }
        runCatching {
            webView.webChromeClient = null
            webView.webViewClient = SafeDeadWebViewClient
            webView.removeAllViews()
        }
        runCatching { webView.destroy() }
    }
}
