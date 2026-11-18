package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.model.MatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

const val BILYONER_SPORTOTO_URL = "https://www.bilyoner.com/spor-toto"
const val BILYONER_SPORTOTO_RESULTS_URL = "https://www.bilyoner.com/spor-toto/sonuclar"

data class BilyonerSporTotoScrapeResult(
    val sourceUrl: String = BILYONER_SPORTOTO_URL,
    val methodLabel: String,
    val weekTitle: String = "",
    val weekNumber: Int? = null,
    val matches: List<NesineSporTotoScrapedMatch>,
    val scrapedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Multi-stage Spor Toto 15-match bulletin scraper for https://www.bilyoner.com/spor-toto:
 * 1) Direct HTTP HTML / __NEXT_DATA__ / RSC JSON / DOM extraction (with script/template sanitization).
 * 2) Automatic headless Android Chromium WebView fallback with CookieManager, passive Fetch/XHR
 *    JSON capture, and live rendered DOM extraction.
 */
class BilyonerSporTotoScraper(
    private val applicationContext: Context? = null,
    private val httpFetcher: (suspend (String) -> Result<String>)? = null,
    private val webViewFetcher: (suspend (String) -> Result<BilyonerSporTotoPayload>)? = null
) {
    data class BilyonerSporTotoPayload(
        val matches: List<NesineSporTotoScrapedMatch>,
        val weekTitle: String = "",
        val weekNumber: Int? = null
    )

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun scrapeResults(
        targetUrl: String = BILYONER_SPORTOTO_RESULTS_URL
    ): Result<BilyonerSporTotoScrapeResult> = scrapeBulletin(targetUrl)

    suspend fun scrapeBulletin(
        targetUrl: String = BILYONER_SPORTOTO_URL
    ): Result<BilyonerSporTotoScrapeResult> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var stage1Partial: BilyonerSporTotoScrapeResult? = null
        val canRunWebView = webViewFetcher != null || applicationContext != null

        // Stage 1: Direct HTTP fetch + HTML / SSR JSON / DOM parse
        val htmlResult = if (httpFetcher != null) {
            httpFetcher.invoke(targetUrl)
        } else {
            fetchHtmlWithOkHttp(targetUrl)
        }

        htmlResult.onSuccess { html ->
            val parsed = parseHtmlDocument(html)
            val normalized = normalizeOrder(parsed.matches)
            if (normalized.isNotEmpty()) {
                val candidate = BilyonerSporTotoScrapeResult(
                    sourceUrl = targetUrl,
                    methodLabel = "HTTP HTML/JSON",
                    weekTitle = parsed.weekTitle,
                    weekNumber = parsed.weekNumber,
                    matches = normalized
                )
                if (normalized.size >= 15 || !canRunWebView) {
                    return@withContext Result.success(candidate)
                }
                stage1Partial = candidate
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            lastError = err
        }

        // Stage 2: Headless Android WebView passive Fetch/XHR + DOM scrape
        val customWebFetcher = webViewFetcher
        if (customWebFetcher != null) {
            val webResult = customWebFetcher.invoke(targetUrl)
            webResult.onSuccess { payload ->
                val normalized = normalizeOrder(payload.matches)
                if (normalized.isNotEmpty()) {
                    return@withContext Result.success(
                        BilyonerSporTotoScrapeResult(
                            sourceUrl = targetUrl,
                            methodLabel = "WebView JS/DOM",
                            weekTitle = payload.weekTitle,
                            weekNumber = payload.weekNumber,
                            matches = normalized
                        )
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                lastError = err
            }
        } else if (applicationContext != null) {
            val webResult = scrapeViaHeadlessWebView(applicationContext, targetUrl)
            webResult.onSuccess { payload ->
                val normalized = normalizeOrder(payload.matches)
                if (normalized.isNotEmpty()) {
                    return@withContext Result.success(
                        BilyonerSporTotoScrapeResult(
                            sourceUrl = targetUrl,
                            methodLabel = "WebView Canlı DOM",
                            weekTitle = payload.weekTitle,
                            weekNumber = payload.weekNumber,
                            matches = normalized
                        )
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                lastError = err
            }
        }

        stage1Partial?.let { return@withContext Result.success(it) }

        val reason = lastError?.message?.takeIf { it.isNotBlank() }
            ?: "bilyoner.com/spor-toto sayfasından 15 maçlık Spor Toto bülteni çekilemedi."
        Result.failure(IllegalStateException(reason))
    }

    private fun fetchHtmlWithOkHttp(url: String): Result<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "https://www.bilyoner.com/")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(
                        IllegalStateException(
                            "bilyoner.com/spor-toto HTTP ${response.code} döndürdü (WebView canlı tarama deneniyor)."
                        )
                    )
                } else if (body.isBlank()) {
                    Result.failure(IllegalStateException("bilyoner.com/spor-toto boş sayfa döndürdü."))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "bilyoner.com/spor-toto bağlantısı kurulamadı: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeViaHeadlessWebView(
        context: Context,
        targetUrl: String
    ): Result<BilyonerSporTotoPayload> = HeadlessWebViewSupport.sessionMutex.withLock {
        withContext(Dispatchers.Main) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return@withContext Result.failure(IllegalStateException("WebView ana iş parçacığı gerektirir."))
            }
            var webView: WebView? = null
            var rendererGone = false
            try {
                val rawPayload: String? = withTimeoutOrNull(14_000L) {
                    var pageLoaded = false
                    var pageErrorMsg: String? = null

                    val wv = WebView(context).apply {
                        HeadlessWebViewSupport.configureForScraping(this)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (!rendererGone) {
                                    view?.evaluateJavascript(JS_PASSIVE_NETWORK_HOOK, null)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoaded = true
                                if (!rendererGone) {
                                    view?.evaluateJavascript(JS_PASSIVE_NETWORK_HOOK, null)
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? = HeadlessWebViewSupport.maybeInterceptHeavyResource(request)

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    pageErrorMsg = error?.description?.toString()
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                rendererGone = true
                                pageErrorMsg = "WebView tarayıcı işlemi kapandı"
                                HeadlessWebViewSupport.recordRendererCrash()
                                return true
                            }
                        }
                    }
                    webView = wv
                    wv.loadUrl(targetUrl)

                    var bestCandidate: String? = null
                    var bestCount = 0
                    var stableRounds = 0
                    repeat(15) { attempt ->
                        delay(800L)
                        if (rendererGone) {
                            return@withTimeoutOrNull bestCandidate
                        }
                        if (pageErrorMsg != null && attempt >= 3) {
                            return@withTimeoutOrNull bestCandidate
                        }
                        if (!pageLoaded && attempt < 2) return@repeat
                        val extracted = evaluateJsSuspend(wv, JS_DOM_AND_API_EXTRACTOR) { rendererGone }
                        if (!extracted.isNullOrBlank()) {
                            val candidate = decodeJsEvaluatedString(extracted)
                            val testParsed = parseWebViewExtractorPayload(candidate)
                            if (testParsed.matches.size > bestCount) {
                                bestCount = testParsed.matches.size
                                bestCandidate = candidate
                                stableRounds = 0
                            } else if (bestCount > 0) {
                                stableRounds++
                            }
                            if (testParsed.matches.size >= 15 || (bestCount > 0 && stableRounds >= 3)) {
                                return@withTimeoutOrNull bestCandidate
                            }
                        }
                    }
                    bestCandidate
                }

                if (rawPayload.isNullOrBlank()) {
                    Result.failure(
                        IllegalStateException(
                            "bilyoner.com/spor-toto canlı DOM/API üzerinden bülten vermedi."
                        )
                    )
                } else {
                    val parsed = parseWebViewExtractorPayload(rawPayload)
                    if (parsed.matches.isEmpty()) {
                        Result.failure(
                            IllegalStateException("bilyoner.com/spor-toto maç satırları ayrıştırılamadı.")
                        )
                    } else {
                        Result.success(parsed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException(
                        "Bilyoner Spor Toto WebView hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                )
            } finally {
                HeadlessWebViewSupport.destroySafely(webView, rendererGone)
            }
        }
    }

    private suspend fun evaluateJsSuspend(
        webView: WebView,
        script: String,
        isDisposed: () -> Boolean
    ): String? =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                if (isDisposed()) {
                    if (cont.isActive) cont.resume(null)
                    return@post
                }
                try {
                    webView.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    companion object {
        private val ISTANBUL_TZ: TimeZone = TimeZone.getTimeZone("Europe/Istanbul")

        private val NEXT_DATA_REGEX = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val INLINE_STATE_JSON_REGEX = Regex(
            """(?:__INITIAL_STATE__|__NUXT__|__SPORTOTO_DATA__|window\.sporTotoData)\s*=\s*(\{.*?\}|\[.*?\])\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val NEXT_FLIGHT_PUSH_REGEX = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*"(.*?)"\s*\]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val STRIP_SCRIPT_STYLE_REGEX = Regex(
            """<(?:script|template|style|noscript|svg)[^>]*>.*?</(?:script|template|style|noscript|svg)>|<!--.*?-->""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val STRIP_THEAD_TFOOT_REGEX = Regex(
            """<(?:thead|tfoot)\b[^>]*>.*?</(?:thead|tfoot)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TEMPLATE_SYNTAX_REGEX = Regex(
            """(\{\{|\}\}|\{%|%\<%=?|\$\{|\|\||&&|===|!==|=>|\b(?:event|model|item|match|data|state|props|row|coupon)\.[a-zA-Z_])""",
            RegexOption.IGNORE_CASE
        )
        private val HTML_ROW_BLOCK_REGEX = Regex(
            """<(?:tr|li|article|div|a)\b[^>]*(?:class=["'][^"']*(?:sportoto|toto|event-content__row|match|fixture|program|row)[^"']*["']|data-match-no|data-test-id=["'][^"']*(?:match|row|toto)[^"']*["'])[^>]*>(.*?)</(?:tr|li|article|div|a)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val GENERIC_TR_REGEX = Regex(
            """<tr\b[^>]*>(.*?)</tr>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TITLE_ATTR_REGEX = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val CELL_DIV_REGEX = Regex(
            """<(?:div|span)[^>]*class=["'][^"']*event-content__cell--(status|home-team|away-team|score|info)[^"']*["'][^>]*>(.*?)</(?:div|span)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val MATCH_NO_REGEX = Regex("""^#?0?([1-9]|1[0-5])\.?$""")
        private val CLOCK_TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")
        private val DATE_TIME_REGEX = Regex("""^(\d{2}\.\d{2}(?:\.\d{4})?\s+\d{2}:\d{2})$""")
        private val TURKISH_MONTHS = mapOf(
            "oca" to "01", "ocak" to "01",
            "şub" to "02", "şubat" to "02",
            "mar" to "03", "mart" to "03",
            "nis" to "04", "nisan" to "04",
            "may" to "05", "mayıs" to "05",
            "haz" to "06", "haziran" to "06",
            "tem" to "07", "temmuz" to "07",
            "ağu" to "08", "ağustos" to "08",
            "eyl" to "09", "eylül" to "09",
            "eki" to "10", "ekim" to "10",
            "kas" to "11", "kasım" to "11",
            "ara" to "12", "aralık" to "12"
        )
        private val TURKISH_DATE_OR_DAY_REGEX = Regex(
            """^(?:(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar|bugün|yarın|dün)[\s,./-]*)*(?:\d{1,2}\s+(?:oca|ocak|şub|şubat|mar|mart|nis|nisan|may|mayıs|haz|haziran|tem|temmuz|ağu|ağustos|eyl|eylül|eki|ekim|kas|kasım|ara|aralık)(?:\s+\d{4})?(?:[\s,]+(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar))?(?:[\s,./-]+(?:[01]?\d|2[0-3]):[0-5]\d)?|\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?(?:[\s,]+(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar))?(?:[\s,./-]+(?:[01]?\d|2[0-3]):[0-5]\d)?|(?:bugün|yarın|dün|pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar)(?:[\s,]+(?:[01]?\d|2[0-3]):[0-5]\d)?)$""",
            RegexOption.IGNORE_CASE
        )
        private val TURKISH_NAMED_DATE_CAPTURE_REGEX = Regex(
            """(\d{1,2})\s+(oca|ocak|şub|şubat|mar|mart|nis|nisan|may|mayıs|haz|haziran|tem|temmuz|ağu|ağustos|eyl|eylül|eki|ekim|kas|kasım|ara|aralık)(?:\s+(\d{4}))?(?:.*?([01]?\d|2[0-3]):([0-5]\d))?""",
            RegexOption.IGNORE_CASE
        )
        private val TEAM_SEPARATOR_REGEX = Regex("""\s+(?:[-–—]|vs\.?)\s+""", RegexOption.IGNORE_CASE)
        private val WEEK_NUMBER_REGEX = Regex("""(\d{1,4})\s*\.?\s*Hafta""", RegexOption.IGNORE_CASE)
        private val PROGRAM_DATE_RANGE_REGEX = Regex("""(\d{2}\.\d{2}\.\d{4}\s*-\s*\d{2}\.\d{2}\.\d{4})""")
        private val PERCENT_INLINE_REGEX = Regex("""%\s*(\d{1,2}(?:[.,]\d{1,2})?)|(\d{1,2}(?:[.,]\d{1,2})?)\s*%""")
        private val SCORE_REGEX = Regex("""^\s*(\d{1,2})\s*[-:]\s*(\d{1,2})\s*$""")

        private const val JS_PASSIVE_NETWORK_HOOK = """
            (function() {
                if (window.__bilyonerTotoHooked) return;
                window.__bilyonerTotoHooked = true;
                window.__bilyonerTotoCaptured = window.__bilyonerTotoCaptured || [];
                window.__bilyonerTotoSeen = window.__bilyonerTotoSeen || {};
                function pushJson(text) {
                    if (!text || text.length < 40 || text.length > 280000 || window.__bilyonerTotoCaptured.length >= 10) return;
                    var sig = text.length + ':' + text.slice(0, 48);
                    if (window.__bilyonerTotoSeen[sig]) return;
                    var low = text.toLowerCase();
                    if (text.length > 90000 && low.indexOf('toto') === -1 && low.indexOf('program') === -1 && low.indexOf('bulletin') === -1) return;
                    if (low.indexOf('hometeam') !== -1 || low.indexOf('home') !== -1 ||
                        low.indexOf('away') !== -1 || low.indexOf('sportoto') !== -1 ||
                        low.indexOf('toto') !== -1 || low.indexOf('matches') !== -1 ||
                        low.indexOf('events') !== -1 || low.indexOf('program') !== -1 ||
                        low.indexOf('result') !== -1 || low.indexOf('sonuc') !== -1 ||
                        low.indexOf('score') !== -1 || low.indexOf('draw') !== -1) {
                        window.__bilyonerTotoSeen[sig] = true;
                        window.__bilyonerTotoCaptured.push(text);
                    }
                }
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(res) {
                            try {
                                var ct = res.headers && res.headers.get ? (res.headers.get('content-type') || '') : '';
                                if (ct.indexOf('json') !== -1) {
                                    res.clone().text().then(pushJson).catch(function(){});
                                }
                            } catch(e) {}
                            return res;
                        });
                    };
                }
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(m, u) {
                    this.__url = u || '';
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        try {
                            if (this.responseType === '' || this.responseType === 'text') {
                                pushJson(this.responseText);
                            }
                        } catch(e) {}
                    });
                    return origSend.apply(this, arguments);
                };
            })();
        """

        private const val JS_DOM_AND_API_EXTRACTOR = """
            (function() {
                try {
                    var weekHeading = '';
                    var weekNumAttr = '';
                    var headingEls = document.querySelectorAll('h1,h2,[class*="hafta"],[class*="week"],[class*="period"],[class*="selected"],select option:checked,[aria-selected="true"]');
                    for (var hIdx = 0; hIdx < headingEls.length; hIdx++) {
                        var hTxt = (headingEls[hIdx].innerText || headingEls[hIdx].textContent || '').trim();
                        if (hTxt && hTxt.length <= 90) {
                            if (!weekHeading) weekHeading = hTxt;
                            var wm = hTxt.match(/(\d{1,4})\s*\.?\s*hafta|hafta\s*(\d{1,4})/i);
                            if (wm) {
                                weekHeading = hTxt;
                                weekNumAttr = wm[1] || wm[2] || '';
                                break;
                            }
                        }
                    }

                    var nextEl = document.getElementById('__NEXT_DATA__');
                    var nextDataRaw = (nextEl && nextEl.textContent && nextEl.textContent.length > 50 && nextEl.textContent.length <= 280000) ? nextEl.textContent : '';

                    var capturedList = [];
                    if (window.__bilyonerTotoCaptured && window.__bilyonerTotoCaptured.length > 0) {
                        for (var i = 0; i < window.__bilyonerTotoCaptured.length; i++) {
                            var c = window.__bilyonerTotoCaptured[i];
                            if (c && c.length > 40) capturedList.push(c);
                        }
                    }

                    function extractTwoTeamLines(rootEl) {
                        if (!rootEl) return [];
                        var explicitTeams = rootEl.querySelectorAll('.spor-toto-item__team');
                        if (explicitTeams && explicitTeams.length >= 2) {
                            var a0 = (explicitTeams[0].innerText || explicitTeams[0].textContent || '').trim();
                            var a1 = (explicitTeams[1].innerText || explicitTeams[1].textContent || '').trim();
                            if (a0 && a1) return [a0, a1];
                        }
                        var target = (explicitTeams && explicitTeams.length === 1) ? explicitTeams[0] : (rootEl.querySelector('.spor-toto-item__teams') || rootEl);
                        if (target.children && target.children.length >= 2) {
                            var cOut = [];
                            for (var ci = 0; ci < target.children.length; ci++) {
                                var ct = (target.children[ci].innerText || target.children[ci].textContent || '').trim();
                                if (ct) {
                                    ct.split(/\r?\n+/).forEach(function(p) {
                                        var pt = p.trim();
                                        if (pt) cOut.push(pt);
                                    });
                                }
                            }
                            if (cOut.length >= 2) return cOut;
                        }
                        var byInner = (target.innerText || '').trim().split(/\r?\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
                        if (byInner.length >= 2) return byInner;
                        var byTextContent = (target.textContent || '').trim().split(/\r?\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
                        if (byTextContent.length >= 2) return byTextContent;
                        if (target.childNodes && target.childNodes.length >= 2) {
                            var nOut = [];
                            for (var ni = 0; ni < target.childNodes.length; ni++) {
                                var nt = (target.childNodes[ni].textContent || '').trim();
                                if (nt) {
                                    nt.split(/\r?\n+/).forEach(function(p) {
                                        var pt = p.trim();
                                        if (pt) nOut.push(pt);
                                    });
                                }
                            }
                            if (nOut.length >= 2) return nOut;
                        }
                        return byInner.length > 0 ? byInner : byTextContent;
                    }

                    var structuredRows = [];
                    var bilyonerTotoItems = document.querySelectorAll('.spor-toto-item');
                    for (var tIdx = 0; tIdx < bilyonerTotoItems.length && structuredRows.length < 30; tIdx++) {
                        var ti = bilyonerTotoItems[tIdx];
                        var idxEl = ti.querySelector('.spor-toto-item__index');
                        var dMainEl = ti.querySelector('.spor-toto-item__date-main');
                        var dTimeEl = ti.querySelector('.spor-toto-item__date-time');
                        var tLines = extractTwoTeamLines(ti);
                        var homeName = tLines.length >= 2 ? tLines[0] : '';
                        var awayName = tLines.length >= 2 ? tLines[1] : '';
                        if (homeName && awayName) {
                            var pillEls = ti.querySelectorAll('.spor-toto-pill__percentage');
                            var pillParts = [];
                            for (var pIdx = 0; pIdx < pillEls.length; pIdx++) {
                                var pt = (pillEls[pIdx].innerText || pillEls[pIdx].textContent || '').trim();
                                if (pt) pillParts.push(pt);
                            }
                            var scoreText = '';
                            var scoreEl = ti.querySelector('[class*="score"]');
                            if (scoreEl) {
                                scoreText = (scoreEl.innerText || scoreEl.textContent || '').replace(/\s+/g, ' ').trim();
                            }
                            if (!scoreText || !/\d{1,2}\s*[-:]\s*\d{1,2}/.test(scoreText)) {
                                var fullRowText = (ti.innerText || ti.textContent || '').replace(/\s+/g, ' ');
                                var sMatch = fullRowText.match(/(?:^|\s)(\d{1,2}\s*[-:]\s*\d{1,2})(?:\s|$)/);
                                if (sMatch) scoreText = sMatch[1];
                            }
                            var outcomeText = '';
                            var activePill = ti.querySelector('.spor-toto-pill--active, .spor-toto-pill--selected, .spor-toto-pill--winner, .spor-toto-pill--won, .spor-toto-pill--correct, .spor-toto-pill--checked, [class*="pill"][class*="active"], [class*="pill"][class*="selected"], [class*="pill"][class*="winner"], [class*="pill"][class*="won"], [aria-checked="true"], [data-selected="true"]');
                            if (activePill) {
                                var lbl = activePill.querySelector('.spor-toto-pill__label');
                                outcomeText = ((lbl ? (lbl.innerText || lbl.textContent) : (activePill.innerText || activePill.textContent)) || '').trim();
                            }
                            if (!outcomeText) {
                                var resEl = ti.querySelector('[class*="result"], [class*="outcome"]');
                                if (resEl) outcomeText = (resEl.innerText || resEl.textContent || '').trim();
                            }
                            var dMain = dMainEl ? (dMainEl.innerText || dMainEl.textContent || '').trim() : '';
                            var dTime = dTimeEl ? (dTimeEl.innerText || dTimeEl.textContent || '').trim() : '';
                            structuredRows.push({
                                matchNo: idxEl ? (idxEl.innerText || idxEl.textContent || '').trim() : '',
                                title: '',
                                href: '',
                                home: homeName,
                                away: awayName,
                                status: [dMain, dTime].filter(Boolean).join(' '),
                                info: pillParts.join(' '),
                                score: scoreText,
                                outcome: outcomeText
                            });
                        }
                    }

                    if (structuredRows.length === 0) {
                        var bilyonerEventRows = document.querySelectorAll('a.event-content__row, .event-content__row');
                        for (var eIdx = 0; eIdx < bilyonerEventRows.length && structuredRows.length < 40; eIdx++) {
                            var er = bilyonerEventRows[eIdx];
                            var ht = er.querySelector('.event-content__cell--home-team');
                            var at = er.querySelector('.event-content__cell--away-team');
                            var st = er.querySelector('.event-content__cell--status');
                            var inf = er.querySelector('.event-content__cell--info');
                            if (ht && at) {
                                structuredRows.push({
                                    matchNo: '',
                                    title: er.getAttribute('title') || '',
                                    href: er.getAttribute('href') || '',
                                    home: (ht.innerText || ht.textContent || '').trim(),
                                    away: (at.innerText || at.textContent || '').trim(),
                                    status: st ? (st.innerText || st.textContent || '').trim() : '',
                                    info: inf ? (inf.innerText || inf.textContent || '').trim() : ''
                                });
                            }
                        }
                    }

                    var items = [];
                    var seen = {};
                    if (structuredRows.length === 0) {
                        var selectors = 'table tbody tr,[role="row"],[class*="sportoto"] [class*="row"],[class*="toto"] [class*="row"],[class*="toto"] li,[class*="match-row"],[class*="event-row"],[class*="program"] [class*="row"],[data-match-no]';
                        var nodes = document.querySelectorAll(selectors);
                        for (var n = 0; n < nodes.length && items.length < 40; n++) {
                            var el = nodes[n];
                            if (el.closest && el.closest('header,nav,footer,[role="tablist"]')) continue;
                            var nestedRows = el.querySelectorAll ? el.querySelectorAll('tr,[role="row"],[data-match-no],[class*="match-row"],[class*="event-row"],[class*="toto-row"],.spor-toto-item') : null;
                            if (nestedRows && nestedRows.length > 1) continue;
                            var cells = el.querySelectorAll ? el.querySelectorAll('th,td,[role="cell"]') : null;
                            var lines = [];
                            if (cells && cells.length >= 2) {
                                for (var k = 0; k < cells.length; k++) {
                                    var ct = (cells[k].innerText || cells[k].textContent || '').trim();
                                    if (ct) {
                                        var splitCell = ct.split(/\n+/);
                                        for (var scIdx = 0; scIdx < splitCell.length; scIdx++) {
                                            var sTrim = splitCell[scIdx].trim();
                                            if (sTrim) lines.push(sTrim);
                                        }
                                    }
                                }
                            } else {
                                var txt = (el.innerText || '').trim();
                                if (!txt || txt.length < 6 || txt.length > 900) continue;
                                lines = txt.split(/\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
                                if (lines.length < 2 && el.children && el.children.length >= 2) {
                                    var childLines = [];
                                    for (var cIdx = 0; cIdx < el.children.length; cIdx++) {
                                        var childTxt = (el.children[cIdx].innerText || el.children[cIdx].textContent || '').trim();
                                        if (childTxt) {
                                            childTxt.split(/\n+/).forEach(function(p) {
                                                var pt = p.trim();
                                                if (pt) childLines.push(pt);
                                            });
                                        }
                                    }
                                    if (childLines.length >= 2) lines = childLines;
                                }
                            }
                            if (lines.length < 2 || lines.length > 40) continue;
                            var titleAttr = el.getAttribute('title') || '';
                            if (!titleAttr && el.querySelector) {
                                var titledAnchor = el.querySelector('a[title]');
                                if (titledAnchor) titleAttr = titledAnchor.getAttribute('title') || '';
                            }
                            var key = lines.join('|');
                            if (seen[key]) continue;
                            seen[key] = true;
                            items.push({ lines: lines, title: titleAttr.trim() });
                        }
                    }

                    var hasFullDom = structuredRows.length >= 15 || items.length >= 15;
                    if (hasFullDom) {
                        window.__bilyonerTotoCaptured = [];
                        window.__bilyonerTotoSeen = {};
                    }
                    var bodyText = (structuredRows.length === 0 && !hasFullDom && document.body) ? (document.body.innerText || '').slice(0, 12000) : '';
                    return JSON.stringify({
                        kind: 'combined',
                        nextData: hasFullDom ? '' : nextDataRaw,
                        captured: hasFullDom ? [] : capturedList,
                        structuredRows: structuredRows,
                        items: items,
                        bodyText: bodyText,
                        weekTitle: weekHeading,
                        weekNumber: weekNumAttr
                    });
                } catch(e) {
                    return JSON.stringify({ kind: 'error', message: String(e) });
                }
            })();
        """

        fun parseHtmlDocument(html: String): BilyonerSporTotoPayload {
            if (html.isBlank()) return BilyonerSporTotoPayload(emptyList())
            val out = mutableListOf<NesineSporTotoScrapedMatch>()
            var weekTitle = ""
            var weekNumber: Int? = null

            fun detectWeekMeta(text: String) {
                if (weekNumber == null) {
                    WEEK_NUMBER_REGEX.find(text)?.let { m ->
                        weekNumber = m.groupValues[1].toIntOrNull()
                        if (weekTitle.isBlank()) weekTitle = m.value.trim()
                    }
                }
                if (weekTitle.isBlank()) {
                    PROGRAM_DATE_RANGE_REGEX.find(text)?.let { m ->
                        weekTitle = m.groupValues[1].trim()
                    }
                }
            }

            // 1. __NEXT_DATA__
            NEXT_DATA_REGEX.findAll(html).forEach { match ->
                val jsonText = match.groupValues[1].trim()
                if (jsonText.isNotEmpty()) {
                    val extracted = extractFromJsonString(jsonText)
                    out.addAll(extracted.matches)
                    if (weekTitle.isBlank()) weekTitle = extracted.weekTitle
                    if (weekNumber == null) weekNumber = extracted.weekNumber
                }
            }

            // 2. Inline state objects
            if (out.isEmpty()) {
                INLINE_STATE_JSON_REGEX.findAll(html).forEach { match ->
                    val jsonText = match.groupValues[1].trim()
                    if (jsonText.isNotEmpty()) {
                        val extracted = extractFromJsonString(jsonText)
                        out.addAll(extracted.matches)
                        if (weekTitle.isBlank()) weekTitle = extracted.weekTitle
                        if (weekNumber == null) weekNumber = extracted.weekNumber
                    }
                }
            }

            // 3. Next.js RSC payloads
            if (out.isEmpty()) {
                NEXT_FLIGHT_PUSH_REGEX.findAll(html).forEach { match ->
                    val unescaped = match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    val jsonStart = unescaped.indexOf('{')
                    val arrStart = unescaped.indexOf('[')
                    val startIdx = listOf(jsonStart, arrStart).filter { it >= 0 }.minOrNull()
                    if (startIdx != null) {
                        val extracted = extractFromJsonString(unescaped.substring(startIdx))
                        out.addAll(extracted.matches)
                        if (weekTitle.isBlank()) weekTitle = extracted.weekTitle
                        if (weekNumber == null) weekNumber = extracted.weekNumber
                    }
                }
            }

            val sanitizedHtml = STRIP_SCRIPT_STYLE_REGEX.replace(html, "\n")

            // 4a. Bilyoner structured event-content__row markup (with title untruncation)
            if (out.isEmpty()) {
                out.addAll(parseBilyonerStructuredRowsHtml(sanitizedHtml))
            }

            // 4b. Class/attribute-marked Spor Toto DOM row blocks (ignore outer wrapper blocks that contain nested rows)
            if (out.isEmpty()) {
                HTML_ROW_BLOCK_REGEX.findAll(sanitizedHtml).forEach { blockMatch ->
                    val innerBody = blockMatch.groupValues[1]
                    if (HTML_ROW_BLOCK_REGEX.containsMatchIn(innerBody)) return@forEach
                    val lines = cleanHtmlFragmentToLines(blockMatch.value)
                    parseLinesIntoMatch(lines, out.size + 1)?.let { out.add(it) }
                }
            }

            // 4c. Generic <tr>...</tr> table rows
            if (out.isEmpty()) {
                val cleanBody = STRIP_THEAD_TFOOT_REGEX.replace(sanitizedHtml, "\n")
                GENERIC_TR_REGEX.findAll(cleanBody).forEach { trMatch ->
                    val trBody = trMatch.groupValues[1]
                    if (trBody.contains("<td", ignoreCase = true)) {
                        val lines = cleanHtmlFragmentToLines(trBody)
                        parseLinesIntoMatch(lines, out.size + 1)?.let { out.add(it) }
                    }
                }
            }

            // 5. Sequential visible lines fallback (also runs if DOM rows missed Match #1 / < 15)
            if (out.size < 15) {
                val visibleLines = cleanHtmlFragmentToLines(sanitizedHtml)
                visibleLines.forEach { detectWeekMeta(it) }
                val fallbackSeq = parseSequentialVisibleLines(visibleLines)
                if (fallbackSeq.size > out.size) {
                    out.clear()
                    out.addAll(fallbackSeq)
                } else if (fallbackSeq.isNotEmpty() && out.isNotEmpty()) {
                    val trLocale = Locale.forLanguageTag("tr-TR")
                    val existingPairs = out.map { "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}" }.toSet()
                    fallbackSeq.forEach { cand ->
                        val pair = "${cand.homeTeam.lowercase(trLocale)}|${cand.awayTeam.lowercase(trLocale)}"
                        if (pair !in existingPairs) {
                            val insertAt = (cand.matchNumber - 1).coerceIn(0, out.size)
                            out.add(insertAt, cand)
                        }
                    }
                }
            }

            detectWeekMeta(sanitizedHtml.take(8000))
            return BilyonerSporTotoPayload(
                matches = normalizeOrder(out),
                weekTitle = weekTitle,
                weekNumber = weekNumber
            )
        }

        private fun parseBilyonerStructuredRowsHtml(html: String): List<NesineSporTotoScrapedMatch> {
            val sporTotoItemStartRegex = Regex(
                """<div\b[^>]*class=["'][^"']*spor-toto-item(?:\s|["'])""",
                RegexOption.IGNORE_CASE
            )
            val itemStarts = sporTotoItemStartRegex.findAll(html).map { it.range.first }.toList()
            if (itemStarts.isNotEmpty()) {
                val indexRegex = Regex("""class=["'][^"']*spor-toto-item__index[^"']*["'][^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val dateMainRegex = Regex("""class=["'][^"']*spor-toto-item__date-main[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val dateTimeRegex = Regex("""class=["'][^"']*spor-toto-item__date-time[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val teamsRegex = Regex("""class=["'][^"']*spor-toto-item__teams?[^"']*["'][^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val pillPctRegex = Regex("""class=["'][^"']*spor-toto-pill__percentage[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val parsedItems = mutableListOf<NesineSporTotoScrapedMatch>()
                for (i in itemStarts.indices) {
                    val end = if (i + 1 < itemStarts.size) itemStarts[i + 1] else html.length
                    val block = html.substring(itemStarts[i], end)
                    val idxText = indexRegex.find(block)?.groupValues?.getOrNull(1)?.let { cleanHtmlToText(it) }.orEmpty()
                    val matchNo = MATCH_NO_REGEX.matchEntire(idxText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: (parsedItems.size + 1)
                    val dateMain = dateMainRegex.find(block)?.groupValues?.getOrNull(1)?.let { cleanHtmlToText(it) }.orEmpty()
                    val dateTime = dateTimeRegex.find(block)?.groupValues?.getOrNull(1)?.let { cleanHtmlToText(it) }.orEmpty()
                    val rawDateStr = listOf(dateMain, dateTime).filter { it.isNotBlank() }.joinToString(" ")
                    val teamLines = teamsRegex.findAll(block)
                        .flatMap { m -> cleanHtmlFragmentToLines(m.groupValues[1]).asSequence() }
                        .filter { looksLikeTeamName(it) }
                        .toList()
                    if (teamLines.size >= 2) {
                        val home = cleanTeamName(teamLines[0])
                        val away = cleanTeamName(teamLines[1])
                        val pctText = pillPctRegex.findAll(block)
                            .map { cleanHtmlToText(it.groupValues[1]) }
                            .joinToString(" ")
                        val blockClean = cleanHtmlToText(block)
                        val probs = parseThreePercentages(pctText.ifBlank { blockClean })
                        val scorePair = extractScoreFromText(blockClean)
                        val explicitOutcome = extractWinnerPillOutcomeHtml(block)
                            ?: scorePair?.let { outcomeFromScores(it.first, it.second) }
                        val resolvedStatus = if (scorePair != null || explicitOutcome != null) {
                            MatchStatus.FINISHED
                        } else {
                            MatchStatus.UPCOMING
                        }
                        parsedItems.add(
                            NesineSporTotoScrapedMatch(
                                matchNumber = matchNo.coerceIn(1, 15),
                                homeTeam = home,
                                awayTeam = away,
                                homeScore = scorePair?.first,
                                awayScore = scorePair?.second,
                                status = resolvedStatus,
                                minuteText = if (resolvedStatus == MatchStatus.FINISHED) "MS" else rawDateStr,
                                matchDate = normalizeMatchDate(rawDateStr),
                                league = "Bilyoner Spor Toto",
                                explicitOutcome = explicitOutcome,
                                homeProb = probs?.first,
                                drawProb = probs?.second,
                                awayProb = probs?.third
                            )
                        )
                    }
                }
                if (parsedItems.isNotEmpty()) {
                    return parsedItems
                }
            }

            val rowRegex = Regex(
                """<a\b([^>]*class=["'][^"']*event-content__row[^"']*["'][^>]*)>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val out = mutableListOf<NesineSporTotoScrapedMatch>()
            rowRegex.findAll(html).forEach { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                val cells = mutableMapOf<String, String>()
                CELL_DIV_REGEX.findAll(body).forEach { cell ->
                    cells[cell.groupValues[1].lowercase(Locale.ROOT)] = cleanHtmlToText(cell.groupValues[2])
                }
                val titleAttr = cleanHtmlToText(TITLE_ATTR_REGEX.find(attrs)?.groupValues?.getOrNull(1).orEmpty())
                val (home, away) = resolveFullTeamNames(
                    cells["home-team"].orEmpty(),
                    cells["away-team"].orEmpty(),
                    titleAttr
                )
                if (!looksLikeTeamName(home) || !looksLikeTeamName(away)) return@forEach
                val rawDate = normalizeMatchDate(cells["status"].orEmpty())
                val probs = parseThreePercentages(cleanHtmlToText(body))
                out.add(
                    NesineSporTotoScrapedMatch(
                        matchNumber = out.size + 1,
                        homeTeam = cleanTeamName(home),
                        awayTeam = cleanTeamName(away),
                        status = MatchStatus.UPCOMING,
                        minuteText = cells["status"].orEmpty().trim(),
                        matchDate = rawDate,
                        league = "Bilyoner Spor Toto",
                        homeProb = probs?.first,
                        drawProb = probs?.second,
                        awayProb = probs?.third
                    )
                )
            }
            return out
        }

        fun parseWebViewExtractorPayload(rawPayload: String): BilyonerSporTotoPayload {
            val trimmed = rawPayload.trim()
            if (trimmed.isEmpty()) return BilyonerSporTotoPayload(emptyList())
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return extractFromJsonString(trimmed)
            val kind = root.optString("kind")
            val topWeekTitle = root.optString("weekTitle").trim()
            val topWeekNum = root.optString("weekNumber").trim().toIntOrNull()
                ?: WEEK_NUMBER_REGEX.find(topWeekTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

            fun parseStructuredRows(arr: JSONArray?): BilyonerSporTotoPayload {
                if (arr == null) return BilyonerSporTotoPayload(emptyList())
                val out = mutableListOf<NesineSporTotoScrapedMatch>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val href = obj.optString("href")
                    if (href.contains("/mac-karti/", ignoreCase = true) &&
                        !href.contains("/mac-karti/futbol/", ignoreCase = true)
                    ) continue
                    val (home, away) = resolveFullTeamNames(
                        obj.optString("home").trim(),
                        obj.optString("away").trim(),
                        cleanHtmlToText(obj.optString("title"))
                    )
                    if (!looksLikeTeamName(home) || !looksLikeTeamName(away)) continue
                    val explicitNo = obj.optString("matchNo").trim()
                        .let { MATCH_NO_REGEX.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    val st = obj.optString("status").trim()
                    val probs = parseThreePercentages(obj.optString("info"))
                    val scorePair = extractScoreFromText(obj.optString("score"))
                    val explicitOutcome = parseOutcomeBadge(obj.optString("outcome"))
                        ?: scorePair?.let { outcomeFromScores(it.first, it.second) }
                    val resolvedStatus = if (scorePair != null || explicitOutcome != null) {
                        MatchStatus.FINISHED
                    } else {
                        resolveStatus(st, false)
                    }
                    out.add(
                        NesineSporTotoScrapedMatch(
                            matchNumber = explicitNo ?: (out.size + 1),
                            homeTeam = cleanTeamName(home),
                            awayTeam = cleanTeamName(away),
                            homeScore = scorePair?.first,
                            awayScore = scorePair?.second,
                            status = resolvedStatus,
                            minuteText = if (resolvedStatus == MatchStatus.FINISHED && st.isBlank()) "MS" else st,
                            matchDate = normalizeMatchDate(st),
                            league = "Bilyoner Spor Toto",
                            explicitOutcome = explicitOutcome,
                            homeProb = probs?.first,
                            drawProb = probs?.second,
                            awayProb = probs?.third
                        )
                    )
                }
                return BilyonerSporTotoPayload(
                    matches = normalizeOrder(out),
                    weekTitle = topWeekTitle,
                    weekNumber = topWeekNum
                )
            }

            fun parseDomFromObject(obj: JSONObject): BilyonerSporTotoPayload {
                val out = mutableListOf<NesineSporTotoScrapedMatch>()
                val itemsArr = obj.optJSONArray("items")
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.optJSONObject(i) ?: continue
                        val linesArr = itemObj.optJSONArray("lines") ?: continue
                        val lines = buildList {
                            for (j in 0 until linesArr.length()) {
                                val s = linesArr.optString(j).trim()
                                if (s.isNotEmpty()) add(s)
                            }
                        }
                        val titleHint = itemObj.optString("title").trim()
                        parseLinesIntoMatch(lines, out.size + 1, titleHint)?.let { out.add(it) }
                    }
                }
                if (out.size < 15) {
                    val bodyText = obj.optString("bodyText")
                    if (bodyText.isNotBlank()) {
                        val lines = bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val seqMatches = parseSequentialVisibleLines(lines)
                        if (seqMatches.size > out.size) {
                            out.clear()
                            out.addAll(seqMatches)
                        } else if (seqMatches.isNotEmpty() && out.isNotEmpty()) {
                            val trLocale = Locale.forLanguageTag("tr-TR")
                            val existingPairs = out.map { "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}" }.toSet()
                            seqMatches.forEach { cand ->
                                val pair = "${cand.homeTeam.lowercase(trLocale)}|${cand.awayTeam.lowercase(trLocale)}"
                                if (pair !in existingPairs) {
                                    val insertAt = (cand.matchNumber - 1).coerceIn(0, out.size)
                                    out.add(insertAt, cand)
                                }
                            }
                        }
                    }
                }
                return BilyonerSporTotoPayload(
                    matches = normalizeOrder(out),
                    weekTitle = topWeekTitle,
                    weekNumber = topWeekNum
                )
            }

            return when (kind) {
                "next_data", "captured_json" -> {
                    val parsed = extractFromJsonString(root.optString("raw"))
                    parsed.copy(
                        weekTitle = parsed.weekTitle.ifBlank { topWeekTitle },
                        weekNumber = parsed.weekNumber ?: topWeekNum
                    )
                }
                "combined" -> {
                    val candidates = mutableListOf<BilyonerSporTotoPayload>()
                    val structured = parseStructuredRows(root.optJSONArray("structuredRows"))
                    if (structured.matches.size >= 15) {
                        return structured.copy(
                            weekTitle = structured.weekTitle.ifBlank { topWeekTitle },
                            weekNumber = structured.weekNumber ?: topWeekNum
                        )
                    }
                    if (structured.matches.isNotEmpty()) {
                        candidates.add(structured)
                    }
                    val nextRaw = root.optString("nextData")
                    if (nextRaw.isNotBlank()) {
                        candidates.add(extractFromJsonString(nextRaw))
                    }
                    val capturedArr = root.optJSONArray("captured")
                    if (capturedArr != null) {
                        for (i in 0 until capturedArr.length()) {
                            val cRaw = capturedArr.optString(i)
                            if (cRaw.isNotBlank()) {
                                candidates.add(extractFromJsonString(cRaw))
                            }
                        }
                    }
                    candidates.add(parseDomFromObject(root))
                    val best = candidates.maxByOrNull { it.matches.size }
                        ?: BilyonerSporTotoPayload(emptyList())
                    best.copy(
                        weekTitle = best.weekTitle.ifBlank { topWeekTitle },
                        weekNumber = best.weekNumber ?: topWeekNum
                    )
                }
                else -> extractFromJsonString(trimmed)
            }
        }

        fun extractFromJsonString(jsonString: String): BilyonerSporTotoPayload {
            val token = runCatching { JSONTokener(jsonString).nextValue() }.getOrNull()
                ?: return BilyonerSporTotoPayload(emptyList())
            val out = mutableListOf<NesineSporTotoScrapedMatch>()
            var weekTitle = ""
            var weekNumber: Int? = null

            fun walk(node: Any?, depth: Int) {
                if (node == null || depth > 14) return
                when (node) {
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            walk(node.opt(i), depth + 1)
                        }
                    }
                    is JSONObject -> {
                        if (node.has("sportId") && node.opt("sportId") is Number && node.optInt("sportId", 1) != 1) {
                            return
                        }
                        if (weekNumber == null) {
                            listOf("weekNumber", "weekNo", "week", "hafta", "haftaNo", "drawNo", "programNo", "couponNo")
                                .firstNotNullOfOrNull { node.opt(it)?.toString()?.toIntOrNull() }
                                ?.takeIf { it in 1..9999 }
                                ?.let { weekNumber = it }
                        }
                        if (weekTitle.isBlank()) {
                            listOf("weekName", "programName", "haftaAdi", "title")
                                .firstNotNullOfOrNull { node.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                                ?.takeIf { !it.contains(" - ") }
                                ?.let { weekTitle = it.trim() }
                        }

                        tryParseJsonMatchObject(node, out.size + 1)?.let { match ->
                            out.add(match)
                            return
                        }

                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val child = node.opt(key)
                            if (child is JSONObject || child is JSONArray) {
                                walk(child, depth + 1)
                            }
                        }
                    }
                }
            }

            walk(token, 0)
            if (weekNumber == null && weekTitle.isNotBlank()) {
                weekNumber = WEEK_NUMBER_REGEX.find(weekTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            return BilyonerSporTotoPayload(
                matches = normalizeOrder(out),
                weekTitle = weekTitle,
                weekNumber = weekNumber
            )
        }

        private fun tryParseJsonMatchObject(obj: JSONObject, fallbackNumber: Int): NesineSporTotoScrapedMatch? {
            var homeName = extractJsonTeam(
                obj,
                listOf("homeTeam", "homeTeamName", "home", "homeName", "homeParticipant", "evSahibi", "team1", "ht", "h")
            )
            var awayName = extractJsonTeam(
                obj,
                listOf("awayTeam", "awayTeamName", "away", "awayName", "awayParticipant", "deplasman", "team2", "at", "a")
            )

            if (homeName == null || awayName == null) {
                listOf("teams", "competitors", "participants", "sides").forEach { arrKey ->
                    val arr = obj.optJSONArray(arrKey)
                    if (arr != null && arr.length() >= 2 && (homeName == null || awayName == null)) {
                        val first = when (val v0 = arr.opt(0)) {
                            is String -> v0.trim()
                            is JSONObject -> v0.optString("name").ifBlank { v0.optString("shortName").ifBlank { v0.optString("title") } }.trim()
                            else -> ""
                        }
                        val second = when (val v1 = arr.opt(1)) {
                            is String -> v1.trim()
                            is JSONObject -> v1.optString("name").ifBlank { v1.optString("shortName").ifBlank { v1.optString("title") } }.trim()
                            else -> ""
                        }
                        if (first.isNotBlank() && second.isNotBlank()) {
                            homeName = first
                            awayName = second
                        }
                    }
                }
            }

            if (homeName == null || awayName == null) {
                val combined = listOf("matchName", "eventName", "teams", "name", "title")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.contains(" - ") } }
                if (combined != null) {
                    val parts = combined.split(" - ", limit = 2)
                    if (parts.size == 2 && looksLikeTeamName(parts[0]) && looksLikeTeamName(parts[1])) {
                        homeName = parts[0].trim()
                        awayName = parts[1].trim()
                    }
                }
            }

            if (homeName.isNullOrBlank() || awayName.isNullOrBlank()) return null
            if (homeName.equals(awayName, ignoreCase = true)) return null
            if (!looksLikeTeamName(homeName) || !looksLikeTeamName(awayName)) return null

            val matchNo = listOf("matchNumber", "no", "order", "lineNo", "programOrder", "eventOrder", "sortOrder")
                .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.toIntOrNull() }
                ?.takeIf { it in 1..15 }
                ?: fallbackNumber.coerceIn(1, 15)

            val rawDate = listOf(
                "matchDate", "startDate", "eventDate", "date", "kickOffTime", "eventHour", "time", "hour"
            ).firstNotNullOfOrNull { k ->
                obj.optString(k).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()

            val probs = extractJsonPercentages(obj)
            val league = listOf("leagueName", "tournamentName", "league", "competition")
                .firstNotNullOfOrNull { k ->
                    when (val v = obj.opt(k)) {
                        is String -> v.trim().takeIf { it.isNotBlank() && it != "null" }
                        is JSONObject -> v.optString("name").trim().takeIf { it.isNotBlank() }
                        else -> null
                    }
                } ?: "Bilyoner Spor Toto"

            var hScore = listOf("homeScore", "hs", "homeGoal", "homeGoals", "scoreHome", "homeResult")
                .firstNotNullOfOrNull { obj.opt(it)?.toString()?.toIntOrNull() }
            var aScore = listOf("awayScore", "as", "awayGoal", "awayGoals", "scoreAway", "awayResult")
                .firstNotNullOfOrNull { obj.opt(it)?.toString()?.toIntOrNull() }
            if (hScore == null || aScore == null) {
                val nestedScore = obj.optJSONObject("score") ?: obj.optJSONObject("scores") ?: obj.optJSONObject("result")
                if (nestedScore != null) {
                    val ft = nestedScore.optJSONObject("fullTime") ?: nestedScore.optJSONObject("ft")
                        ?: nestedScore.optJSONObject("current") ?: nestedScore
                    hScore = listOf("home", "Home", "h", "homeScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                    aScore = listOf("away", "Away", "a", "awayScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                }
            }
            if (hScore == null || aScore == null) {
                val scoreStr = listOf("score", "matchScore", "resultScore", "fullTimeScore", "skor", "sonuc")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                val pair = scoreStr?.let { extractScoreFromText(it) }
                if (pair != null) {
                    hScore = pair.first
                    aScore = pair.second
                }
            }
            val explicitOutcome = listOf("resultCode", "winningOutcome", "outcome", "result", "sonuc", "winner", "matchResult")
                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                ?.let { parseOutcomeBadge(it) }
                ?: if (hScore != null && aScore != null) outcomeFromScores(hScore, aScore) else null
            val stStr = listOf("status", "minuteText", "clock", "state", "matchStatus")
                .firstNotNullOfOrNull { obj.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                .orEmpty()
            val status = resolveStatus(stStr, (hScore != null && aScore != null) || explicitOutcome != null)

            return NesineSporTotoScrapedMatch(
                matchNumber = matchNo,
                homeTeam = cleanTeamName(homeName),
                awayTeam = cleanTeamName(awayName),
                homeScore = hScore,
                awayScore = aScore,
                status = status,
                minuteText = if (status == MatchStatus.FINISHED && stStr.isBlank()) "MS" else stStr,
                matchDate = normalizeMatchDate(rawDate),
                league = league,
                explicitOutcome = explicitOutcome,
                homeProb = probs?.first,
                drawProb = probs?.second,
                awayProb = probs?.third
            )
        }

        private fun extractJsonTeam(obj: JSONObject, keys: List<String>): String? {
            for (k in keys) {
                val v = obj.opt(k) ?: continue
                when (v) {
                    is String -> if (v.isNotBlank() && !v.equals("null", true)) return v.trim()
                    is JSONObject -> {
                        val n = v.optString("name").ifBlank { v.optString("shortName").ifBlank { v.optString("title") } }
                        if (n.isNotBlank()) return n.trim()
                    }
                }
            }
            return null
        }

        private fun extractJsonPercentages(obj: JSONObject): Triple<Int, Int, Int>? {
            fun readInt(target: JSONObject, keys: List<String>): Int? =
                keys.firstNotNullOfOrNull { k ->
                    target.optString(k).replace("%", "").trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
                }?.takeIf { it in 1..98 }

            val h = readInt(obj, listOf("homePercent", "rate1", "pct1", "o1", "ms1", "one", "1"))
            val d = readInt(obj, listOf("drawPercent", "rate0", "rateX", "pct0", "o0", "ms0", "zero", "0", "X"))
            val a = readInt(obj, listOf("awayPercent", "rate2", "pct2", "o2", "ms2", "two", "2"))
            if (h != null && d != null && a != null && (h + d + a) in 90..110) {
                return Triple(h, d, a)
            }

            listOf("rates", "percentages", "popularity", "bettingRates").forEach { nestedKey ->
                val nested = obj.optJSONObject(nestedKey)
                if (nested != null) {
                    val nh = readInt(nested, listOf("1", "home", "ms1", "rate1"))
                    val nd = readInt(nested, listOf("0", "X", "x", "draw", "ms0", "rate0"))
                    val na = readInt(nested, listOf("2", "away", "ms2", "rate2"))
                    if (nh != null && nd != null && na != null && (nh + nd + na) in 90..110) {
                        return Triple(nh, nd, na)
                    }
                }
            }
            return null
        }

        private fun parseLinesIntoMatch(
            rawLines: List<String>,
            fallbackMatchNo: Int,
            titleHint: String = ""
        ): NesineSporTotoScrapedMatch? {
            if (rawLines.isEmpty()) return null
            val lines = rawLines.flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            if (lines.any { TEMPLATE_SYNTAX_REGEX.containsMatchIn(it) }) return null

            var matchNo: Int? = null
            var datePart = ""
            var timePart = ""
            var home = ""
            var away = ""

            for (line in lines) {
                if (matchNo == null && home.isEmpty()) {
                    val m = MATCH_NO_REGEX.matchEntire(line)
                    if (m != null) {
                        matchNo = m.groupValues[1].toIntOrNull()
                        continue
                    }
                }
                if (CLOCK_TIME_REGEX.matches(line)) {
                    if (timePart.isEmpty()) timePart = line
                    continue
                }
                if (looksLikeTurkishDate(line)) {
                    if (datePart.isEmpty()) datePart = line
                    continue
                }
                if (home.isEmpty() && TEAM_SEPARATOR_REGEX.containsMatchIn(line)) {
                    val parts = line.split(TEAM_SEPARATOR_REGEX, limit = 2)
                    if (parts.size == 2 && looksLikeTeamName(parts[0]) && looksLikeTeamName(parts[1])) {
                        home = parts[0].trim()
                        away = parts[1].trim()
                        continue
                    }
                }
                if (looksLikeTeamName(line)) {
                    if (home.isEmpty()) {
                        home = line
                    } else if (away.isEmpty() && !line.equals(home, ignoreCase = true)) {
                        away = line
                    }
                }
            }

            if (titleHint.isNotBlank()) {
                val (resolvedHome, resolvedAway) = resolveFullTeamNames(home, away, titleHint)
                if (looksLikeTeamName(resolvedHome) && looksLikeTeamName(resolvedAway)) {
                    home = resolvedHome
                    away = resolvedAway
                }
            }

            if (!looksLikeTeamName(home) || !looksLikeTeamName(away)) return null
            val combinedDateRaw = listOf(datePart, timePart).filter { it.isNotBlank() }.joinToString(" ")
            val probs = parseThreePercentages(lines)
            val scorePair = lines.firstNotNullOfOrNull { extractScoreFromText(it) }
            val explicitOutcome = scorePair?.let { outcomeFromScores(it.first, it.second) }
                ?: lines.firstNotNullOfOrNull { parseOutcomeBadge(it) }
            val resolvedStatus = if (scorePair != null || explicitOutcome != null) {
                MatchStatus.FINISHED
            } else {
                MatchStatus.UPCOMING
            }
            if (matchNo == null && probs == null && scorePair == null && explicitOutcome == null && combinedDateRaw.isBlank() && titleHint.isBlank()) {
                return null
            }
            return NesineSporTotoScrapedMatch(
                matchNumber = matchNo ?: fallbackMatchNo.coerceIn(1, 15),
                homeTeam = cleanTeamName(home),
                awayTeam = cleanTeamName(away),
                homeScore = scorePair?.first,
                awayScore = scorePair?.second,
                status = resolvedStatus,
                minuteText = if (resolvedStatus == MatchStatus.FINISHED) "MS" else combinedDateRaw,
                matchDate = normalizeMatchDate(combinedDateRaw),
                league = "Bilyoner Spor Toto",
                explicitOutcome = explicitOutcome,
                homeProb = probs?.first,
                drawProb = probs?.second,
                awayProb = probs?.third
            )
        }

        private fun parseSequentialVisibleLines(lines: List<String>): List<NesineSporTotoScrapedMatch> {
            val results = mutableListOf<NesineSporTotoScrapedMatch>()
            var i = 0
            while (i < lines.size && results.size < 15) {
                val cur = lines[i]
                // Case A: Explicit match number 01..15 followed by fixture window
                val noMatch = MATCH_NO_REGEX.matchEntire(cur)
                if (noMatch != null) {
                    val mNo = noMatch.groupValues[1].toInt()
                    var endIdx = minOf(lines.size, i + 12)
                    var seenTeamCandidates = 0
                    for (j in (i + 1) until endIdx) {
                        val lj = lines[j]
                        if (seenTeamCandidates >= 2) {
                            val nextNo = MATCH_NO_REGEX.matchEntire(lj)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            if (nextNo != null && (nextNo == mNo + 1 || lj.startsWith("0"))) {
                                endIdx = j
                                break
                            }
                        }
                        if (TEAM_SEPARATOR_REGEX.containsMatchIn(lj)) {
                            val parts = lj.split(TEAM_SEPARATOR_REGEX, limit = 2)
                            if (parts.size == 2 && looksLikeTeamName(parts[0]) && looksLikeTeamName(parts[1])) {
                                seenTeamCandidates += 2
                            }
                        } else if (looksLikeTeamName(lj)) {
                            seenTeamCandidates++
                        }
                    }
                    val window = lines.subList(i, endIdx)
                    val parsed = parseLinesIntoMatch(window, mNo)
                    if (parsed != null) {
                        results.add(parsed.copy(matchNumber = mNo))
                        i = endIdx
                        continue
                    }
                }
                // Case B: Combined "HomeTeam - AwayTeam" line
                if (TEAM_SEPARATOR_REGEX.containsMatchIn(cur)) {
                    val parts = cur.split(TEAM_SEPARATOR_REGEX, limit = 2)
                    if (parts.size == 2 && looksLikeTeamName(parts[0]) && looksLikeTeamName(parts[1])) {
                        val window = lines.subList(maxOf(0, i - 2), minOf(lines.size, i + 6))
                        val probs = parseThreePercentages(window)
                        val dPart = window.firstOrNull { looksLikeTurkishDate(it) && !CLOCK_TIME_REGEX.matches(it) }.orEmpty()
                        val tPart = window.firstOrNull { CLOCK_TIME_REGEX.matches(it) }.orEmpty()
                        val dateLine = listOf(dPart, tPart).filter { it.isNotBlank() }.joinToString(" ")
                        results.add(
                            NesineSporTotoScrapedMatch(
                                matchNumber = results.size + 1,
                                homeTeam = cleanTeamName(parts[0]),
                                awayTeam = cleanTeamName(parts[1]),
                                status = MatchStatus.UPCOMING,
                                minuteText = dateLine,
                                matchDate = normalizeMatchDate(dateLine),
                                league = "Bilyoner Spor Toto",
                                homeProb = probs?.first,
                                drawProb = probs?.second,
                                awayProb = probs?.third
                            )
                        )
                        i += 2
                        continue
                    }
                }
                i++
            }
            return results
        }

        private fun parseThreePercentages(text: String): Triple<Int, Int, Int>? =
            parseThreePercentages(listOf(text))

        private fun parseThreePercentages(lines: List<String>): Triple<Int, Int, Int>? {
            val joined = lines.joinToString(" ")
            val explicitNums = PERCENT_INLINE_REGEX.findAll(joined).mapNotNull { m ->
                val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }.replace(',', '.')
                raw.toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it in 1..98 }
            }.toList()
            for (idx in 0..explicitNums.size - 3) {
                val p1 = explicitNums[idx]
                val px = explicitNums[idx + 1]
                val p2 = explicitNums[idx + 2]
                if ((p1 + px + p2) in 90..110) {
                    return Triple(p1, px, p2)
                }
            }
            val bareNums = lines.mapNotNull { line ->
                val trimmed = line.trim().removePrefix("%").removeSuffix("%").replace(',', '.').trim()
                trimmed.toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it in 2..96 }
            }
            for (idx in 0..bareNums.size - 3) {
                val p1 = bareNums[idx]
                val px = bareNums[idx + 1]
                val p2 = bareNums[idx + 2]
                if ((p1 + px + p2) in 98..102) {
                    return Triple(p1, px, p2)
                }
            }
            return null
        }

        private fun extractScoreFromText(text: String): Pair<Int, Int>? {
            val match = Regex("""(?:^|\s)(\d{1,2})\s*[-:]\s*(\d{1,2})(?:\s|$)""").find(text.trim()) ?: return null
            val h = match.groupValues[1].toIntOrNull() ?: return null
            val a = match.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..25 || a !in 0..25) return null
            return h to a
        }

        private fun outcomeFromScores(h: Int, a: Int): com.example.model.PredictionOutcome = when {
            h > a -> com.example.model.PredictionOutcome.HOME
            h == a -> com.example.model.PredictionOutcome.DRAW
            else -> com.example.model.PredictionOutcome.AWAY
        }

        private fun parseOutcomeBadge(raw: String): com.example.model.PredictionOutcome? {
            val upper = raw.trim().uppercase(Locale.forLanguageTag("tr-TR"))
            return when (upper) {
                "1", "MS 1", "HOME" -> com.example.model.PredictionOutcome.HOME
                "X", "0", "MS X", "MS 0", "DRAW" -> com.example.model.PredictionOutcome.DRAW
                "2", "MS 2", "AWAY" -> com.example.model.PredictionOutcome.AWAY
                else -> null
            }
        }

        private fun extractWinnerPillOutcomeHtml(block: String): com.example.model.PredictionOutcome? {
            val winnerRegex = Regex(
                """class=["'][^"']*spor-toto-pill[^"']*--(?:active|selected|winner|won|correct|checked)[^"']*["'][^>]*>(.*?)</div>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val body = winnerRegex.find(block)?.groupValues?.getOrNull(1) ?: return null
            val labelRegex = Regex("""class=["'][^"']*spor-toto-pill__label[^"']*["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val lbl = labelRegex.find(body)?.groupValues?.getOrNull(1)?.let { cleanHtmlToText(it) }
                ?: cleanHtmlToText(body)
            return parseOutcomeBadge(lbl)
        }

        private fun resolveStatus(raw: String, hasScore: Boolean): MatchStatus {
            val s = raw.uppercase(Locale.forLanguageTag("tr-TR")).trim()
            return when {
                s in setOf("MS", "M.S.", "BİTTİ", "BITTI", "FT", "FINISHED", "ENDED", "COMPLETE") -> MatchStatus.FINISHED
                s in setOf("İY", "I.Y.", "DEVRE", "HT") -> MatchStatus.HALF_TIME
                s in setOf("CANLI", "LIVE", "OYNANIYOR") || s.contains("'") -> MatchStatus.LIVE
                hasScore -> MatchStatus.FINISHED
                else -> MatchStatus.UPCOMING
            }
        }

        private fun resolveFullTeamNames(
            cellHome: String,
            cellAway: String,
            titleAttr: String
        ): Pair<String, String> {
            val parts = titleAttr.split(TEAM_SEPARATOR_REGEX, limit = 2)
            if (parts.size == 2) {
                val titleHome = parts[0].trim()
                val titleAway = parts[1].trim()
                val bestHome = when {
                    cellHome.isBlank() -> titleHome
                    titleHome.length > cellHome.length &&
                        titleHome.startsWith(cellHome, ignoreCase = true) -> titleHome
                    else -> cellHome
                }
                val bestAway = when {
                    cellAway.isBlank() -> titleAway
                    titleAway.length > cellAway.length &&
                        titleAway.startsWith(cellAway, ignoreCase = true) -> titleAway
                    else -> cellAway
                }
                return bestHome to bestAway
            }
            return cellHome to cellAway
        }

        private fun normalizeMatchDate(raw: String): String {
            val trimmed = raw.replace("•", " ").replace(Regex("\\s+"), " ").trim()
            val outFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = ISTANBUL_TZ }
            val currentYear = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = ISTANBUL_TZ }.format(Date())
            val todayStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = ISTANBUL_TZ }.format(Date())

            Regex("""^(\d{2})[./](\d{2})[./](\d{4})\s+(\d{2}:\d{2})$""").find(trimmed)?.let { m ->
                return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]} ${m.groupValues[4]}"
            }
            Regex("""^(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?(?:.*?(\d{2}:\d{2}))?$""").matchEntire(trimmed)?.let { m ->
                val day = m.groupValues[1].padStart(2, '0')
                val month = m.groupValues[2].padStart(2, '0')
                val rawYear = m.groupValues[3]
                val year = when (rawYear.length) {
                    2 -> "20$rawYear"
                    4 -> rawYear
                    else -> currentYear
                }
                val time = m.groupValues[4].ifBlank { "19:00" }
                return "$day.$month.$year $time"
            }
            TURKISH_NAMED_DATE_CAPTURE_REGEX.find(trimmed.lowercase(Locale.forLanguageTag("tr-TR")))?.let { m ->
                val day = m.groupValues[1].padStart(2, '0')
                val monthKey = m.groupValues[2].lowercase(Locale.forLanguageTag("tr-TR"))
                val month = TURKISH_MONTHS[monthKey] ?: "01"
                val year = m.groupValues[3].ifBlank { currentYear }
                val hh = m.groupValues[4].padStart(2, '0')
                val mm = m.groupValues[5]
                val time = if (m.groupValues[4].isNotBlank() && mm.isNotBlank()) "$hh:$mm" else "19:00"
                return "$day.$month.$year $time"
            }
            if (CLOCK_TIME_REGEX.matches(trimmed)) {
                return "$todayStr $trimmed"
            }
            val isoMatch = Regex("""(\d{4})-(\d{2})-(\d{2})[T\s](\d{2}:\d{2})""").find(trimmed)
            if (isoMatch != null) {
                return "${isoMatch.groupValues[3]}.${isoMatch.groupValues[2]}.${isoMatch.groupValues[1]} ${isoMatch.groupValues[4]}"
            }
            return outFmt.format(Date())
        }

        private fun normalizeOrder(list: List<NesineSporTotoScrapedMatch>): List<NesineSporTotoScrapedMatch> {
            val trLocale = Locale.forLanguageTag("tr-TR")
            return list
                .filter { looksLikeTeamName(it.homeTeam) && looksLikeTeamName(it.awayTeam) }
                .distinctBy { "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}" }
                .mapIndexed { idx, item ->
                    val finalNo = if (item.matchNumber in 1..15) item.matchNumber else idx + 1
                    item.copy(matchNumber = finalNo)
                }
                .sortedBy { it.matchNumber }
                .take(15)
                .mapIndexed { idx, item -> item.copy(matchNumber = idx + 1) }
        }

        private fun looksLikeTurkishDate(text: String): Boolean {
            val normalized = text
                .lowercase(Locale.forLanguageTag("tr-TR"))
                .replace("•", " ")
                .replace("|", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.isEmpty()) return false
            return CLOCK_TIME_REGEX.matches(normalized) ||
                DATE_TIME_REGEX.matches(normalized) ||
                TURKISH_DATE_OR_DAY_REGEX.matches(normalized)
        }

        private fun looksLikeTeamName(text: String): Boolean {
            val clean = text.trim()
            if (clean.length !in 2..64) return false
            if (TEMPLATE_SYNTAX_REGEX.containsMatchIn(clean)) return false
            if (clean.any { it in setOf('{', '}', '<', '>', ';', '[', ']', '=') }) return false
            if (looksLikeTurkishDate(clean)) return false
            val lower = clean.lowercase(Locale.forLanguageTag("tr-TR"))
            if (lower in setOf(
                    "toplam oran", "misli", "kupon bedeli", "maksimum kazanç",
                    "kolon sayısı", "hemen oyna", "kuponum", "sistem", "banko",
                    "canlı", "canlı yayın", "canlı tv", "canlı skor",
                    "tümü", "biten", "bitti", "başlamadı", "başlamak üzere",
                    "karşılaşma", "karşılaşmalar", "durum", "spor toto", "spor toto programı",
                    "program seçimi", "program", "bülten", "tarih", "tarih / saat",
                    "başlama saati", "sonuç", "oran", "oranlar", "oranı", "tahmin", "tahminler",
                    "yorumlar", "ms", "m.s.", "iy", "i.y.", "devre", "devre arası", "maç sonu",
                    "1. devre", "2. devre", "uzatma", "penaltılar", "ertelendi", "ert.", "iptal",
                    "ms 1", "ms 0", "ms 2", "mbs", "istatistik", "istatistikler",
                    "analiz", "detay", "detaylar", "maç detayı", "maç merkezi", "kadro",
                    "temizle", "ev sahibi", "deplasman", "takımlar", "maçlar",
                    "tek maç", "derbi", "günün maçı", "haftanın maçı",
                    "popüler", "yüksek oran", "favori", "sürpriz", "oynanma oranı",
                    "seçim", "kolon", "kupon", "filtrele", "sırala", "bugün", "yarın", "dün",
                    "süper lig", "trendyol süper lig", "1. lig", "trendyol 1. lig", "tff 1. lig",
                    "premier lig", "la liga", "serie a", "bundesliga", "ligue 1",
                    "şampiyonlar ligi", "avrupa ligi", "konferans ligi", "uluslar ligi",
                    "türkiye kupası", "oynanma yüzdesi", "oynanma yüzdeleri", "haftanın programı",
                    "oyna", "kuponlarım", "kuponlarim", "nasıl oynanır", "nasil oynanir",
                    "kurallar", "ikramiye", "ikramiyeler", "dağıtılan ikramiye", "kazananlar",
                    "sonuçlar", "sonuclar", "giriş", "giris", "giriş yap", "üye ol", "uye ol",
                    "bakiye", "favoriler", "popüler kuponlar", "hazır kuponlar", "hazir kuponlar",
                    "spor toto oyna", "spor toto kuponlarım", "spor toto sonuçları", "spor toto nedir",
                    "oto seçim", "otomatik seçim", "rastgele", "tümünü temizle", "kupona ekle",
                    "hepsini seç", "yüksek oranlar", "devam et", "önceki hafta", "sonraki hafta",
                    "bu hafta", "aktif hafta", "gelecek hafta", "geçmiş haftalar", "kolon bedeli"
                )
            ) return false
            if (SCORE_REGEX.matches(clean)) return false
            if (MATCH_NO_REGEX.matches(clean) || clean.startsWith("%")) return false
            return clean.any { it.isLetter() }
        }

        private fun cleanHtmlFragmentToLines(fragment: String): List<String> {
            val separated = fragment
                .replace(Regex("""</?(?:td|th|tr|div|li|p|span|b|strong|a|br)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            return HTML_TAG_REGEX.replace(separated, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .lines()
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
        }

        private fun cleanHtmlToText(fragment: String): String {
            return HTML_TAG_REGEX.replace(fragment, " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun cleanTeamName(raw: String): String =
            raw.trim().replace(Regex("\\s+"), " ")

        private fun decodeJsEvaluatedString(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return ""
            return runCatching {
                JSONTokener(trimmed).nextValue()?.toString().orEmpty()
            }.getOrElse {
                trimmed.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
        }
    }
}
