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
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.network.model.BulletinMatchDto
import com.example.network.model.LiveBettingOddsDto
import com.example.network.model.MatchProbabilitiesDto
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

const val NESINE_SPORTOTO_URL = "https://www.nesine.com/sportoto"
const val NESINE_SPORTOTO_RESULTS_URL = "https://www.nesine.com/sportoto/mac-sonuclari"

data class NesineSporTotoScrapedMatch(
    val matchNumber: Int,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val status: MatchStatus = MatchStatus.UPCOMING,
    val minuteText: String = "",
    val matchDate: String = "",
    val league: String = "Spor Toto Listesi",
    val explicitOutcome: PredictionOutcome? = null,
    val homeProb: Int? = null,
    val drawProb: Int? = null,
    val awayProb: Int? = null
) {
    fun toMatchLiveScore(): MatchLiveScore {
        return MatchLiveScore(
            matchNumber = matchNumber.coerceAtLeast(1),
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = homeScore,
            awayScore = awayScore,
            status = status,
            minuteText = when {
                minuteText.isNotBlank() -> minuteText
                status == MatchStatus.HALF_TIME -> "İY"
                status == MatchStatus.FINISHED -> "MS"
                status == MatchStatus.LIVE -> "CANLI"
                else -> "—"
            },
            matchDate = matchDate,
            league = league
        )
    }

    fun toBulletinMatchDto(): BulletinMatchDto {
        val trLocale = Locale.forLanguageTag("tr-TR")
        val statusLabel = when (status) {
            MatchStatus.FINISHED -> "FINISHED"
            MatchStatus.LIVE, MatchStatus.HALF_TIME -> "LIVE"
            MatchStatus.UPCOMING -> "UPCOMING"
        }
        val rawH = homeProb?.coerceIn(2, 94)
        val rawD = drawProb?.coerceIn(2, 94)
        val rawA = awayProb?.coerceIn(2, 94)
        val (hProb, dProb, aProb) = if (rawH != null && rawD != null && rawA != null && (rawH + rawD + rawA) in 90..110) {
            if (rawH + rawD + rawA == 100) {
                Triple(rawH, rawD, rawA)
            } else {
                val total = (rawH + rawD + rawA).toDouble()
                val h = Math.round((rawH / total) * 100.0).toInt().coerceIn(3, 94)
                val maxD = (97 - h).coerceAtLeast(3)
                val d = Math.round((rawD / total) * 100.0).toInt().coerceIn(3, maxD)
                val a = (100 - h - d).coerceAtLeast(3)
                Triple(h, d, a)
            }
        } else {
            Triple(42, 29, 29)
        }
        val hOdd = ((94.0 / hProb).coerceIn(1.05, 18.0) * 100).toInt() / 100.0
        val dOdd = ((94.0 / dProb).coerceIn(1.15, 15.0) * 100).toInt() / 100.0
        val aOdd = ((94.0 / aProb).coerceIn(1.05, 18.0) * 100).toInt() / 100.0
        val dc1X = ((1.0 / ((1.0 / hOdd) + (1.0 / dOdd))).coerceAtLeast(1.01) * 100).toInt() / 100.0
        val dc12 = ((1.0 / ((1.0 / hOdd) + (1.0 / aOdd))).coerceAtLeast(1.01) * 100).toInt() / 100.0
        val dcX2 = ((1.0 / ((1.0 / dOdd) + (1.0 / aOdd))).coerceAtLeast(1.01) * 100).toInt() / 100.0
        return BulletinMatchDto(
            matchNumber = matchNumber.coerceIn(1, 15),
            homeTeam = homeTeam.uppercase(trLocale).trim(),
            awayTeam = awayTeam.uppercase(trLocale).trim(),
            matchDate = matchDate,
            league = league.ifBlank { "Spor Toto Listesi" },
            venue = "",
            status = statusLabel,
            liveOdds = LiveBettingOddsDto(
                homeWinOdd = hOdd,
                drawOdd = dOdd,
                awayWinOdd = aOdd,
                doubleChance1X = dc1X,
                doubleChance12 = dc12,
                doubleChanceX2 = dcX2
            ),
            probabilities = MatchProbabilitiesDto(
                homeProbability = hProb,
                drawProbability = dProb,
                awayProbability = aProb
            )
        )
    }
}

data class NesineSporTotoScrapeResult(
    val sourceUrl: String = NESINE_SPORTOTO_RESULTS_URL,
    val methodLabel: String,
    val weekTitle: String = "",
    val weekNumber: Int? = null,
    val matches: List<NesineSporTotoScrapedMatch>,
    val scrapedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Multi-stage instant live scraper for https://www.nesine.com/sportoto/mac-sonuclari:
 * 1) Fast HTTP HTML / embedded JSON (__NEXT_DATA__, SSR JSON, RSC chunks, table DOM rows).
 * 2) Automatic headless Android WebView fallback capturing both passive XHR/Fetch JSON
 *    responses and rendered Spor Toto scoreboard DOM rows.
 */
class NesineSporTotoScraper(
    private val applicationContext: Context? = null,
    private val httpFetcher: (suspend (String) -> Result<String>)? = null,
    private val webViewFetcher: (suspend (String) -> Result<NesineWebViewPayload>)? = null
) {
    data class NesineWebViewPayload(
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

    suspend fun scrapeBulletin(
        targetUrl: String = NESINE_SPORTOTO_URL
    ): Result<NesineSporTotoScrapeResult> = scrapeResults(targetUrl)

    suspend fun scrapeResults(
        targetUrl: String = NESINE_SPORTOTO_RESULTS_URL
    ): Result<NesineSporTotoScrapeResult> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var stage1Partial: NesineSporTotoScrapeResult? = null
        val canRunWebView = webViewFetcher != null || applicationContext != null

        // Stage 1: Direct HTTP HTML + __NEXT_DATA__ / JSON / DOM scrape
        val htmlResult = if (httpFetcher != null) {
            httpFetcher.invoke(targetUrl)
        } else {
            fetchHtmlWithOkHttp(targetUrl)
        }

        htmlResult.onSuccess { html ->
            val parsed = parseHtmlDocument(html)
            val normalized = normalizeOrder(parsed.matches)
            if (normalized.isNotEmpty()) {
                val candidate = NesineSporTotoScrapeResult(
                    sourceUrl = targetUrl,
                    methodLabel = "HTTP HTML/JSON",
                    weekTitle = parsed.weekTitle,
                    weekNumber = parsed.weekNumber,
                    matches = normalized
                )
                val needsFull15Bulletin = targetUrl.equals(NESINE_SPORTOTO_URL, ignoreCase = true)
                if (!needsFull15Bulletin || normalized.size >= 15 || !canRunWebView) {
                    return@withContext Result.success(candidate)
                }
                stage1Partial = candidate
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            lastError = err
        }

        // Stage 2: Headless Android Chromium WebView XHR/Fetch + DOM live scrape
        val customWebFetcher = webViewFetcher
        if (customWebFetcher != null) {
            val webResult = customWebFetcher.invoke(targetUrl)
            webResult.onSuccess { payload ->
                val normalized = normalizeOrder(payload.matches)
                if (normalized.isNotEmpty()) {
                    return@withContext Result.success(
                        NesineSporTotoScrapeResult(
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
                        NesineSporTotoScrapeResult(
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

        val shortSource = targetUrl.removePrefix("https://www.")
        val reason = lastError?.message?.takeIf { it.isNotBlank() }
            ?: "$shortSource sayfasından Spor Toto verisi alınamadı."
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
                .header("Referer", "https://www.nesine.com/sportoto")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(
                        IllegalStateException(
                            "nesine.com HTTP ${response.code} döndürdü (WebView canlı tarama deneniyor)."
                        )
                    )
                } else if (body.isBlank()) {
                    Result.failure(IllegalStateException("nesine.com boş sayfa döndürdü."))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "nesine.com HTTP bağlantısı kurulamadı: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeViaHeadlessWebView(
        context: Context,
        targetUrl: String
    ): Result<NesineWebViewPayload> = HeadlessWebViewSupport.sessionMutex.withLock {
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
                            "nesine.com/sportoto/mac-sonuclari canlı DOM/API üzerinden maç sonucu vermedi."
                        )
                    )
                } else {
                    val parsed = parseWebViewExtractorPayload(rawPayload)
                    if (parsed.matches.isEmpty()) {
                        Result.failure(
                            IllegalStateException("nesine.com Spor Toto sonuç tablosu ayrıştırılamadı.")
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
                        "Nesine WebView canlı scraping hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
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
            """(?:__INITIAL_STATE__|__NUXT__|__SPORTOTO_DATA__|window\.sportotoResults)\s*=\s*(\{.*?\}|\[.*?\])\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val NEXT_FLIGHT_PUSH_REGEX = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*"(.*?)"\s*\]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_ROW_BLOCK_REGEX = Regex(
            """<(?:tr|li|article|div)\b[^>]*(?:class=["'][^"']*(?:sportoto|toto|match|fixture|event|result|row|game)[^"']*["']|data-match-no)[^>]*>(.*?)</(?:tr|li|article|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val GENERIC_TR_REGEX = Regex(
            """<tr\b[^>]*>(.*?)</tr>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val FILTER_WEEK_SELECT_REGEX = Regex(
            """<select[^>]*id=["']filter-week-select["'][^>]*>.*?<option[^>]*value=["'](\d{1,4})["'][^>]*>(.*?)</option>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val FILTER_WEEK_SELECTED_OPTION_REGEX = Regex(
            """<select[^>]*id=["']filter-week-select["'][^>]*>.*?<option[^>]*selected[^>]*value=["'](\d{1,4})["'][^>]*>(.*?)</option>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val PROGRAM_RESULT_CELL_REGEX = Regex(
            """<td[^>]*data-test-id=["']programResult-([a-zA-Z]+)["'][^>]*>(.*?)</td>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
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
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val SCORE_REGEX = Regex("""^\s*(\d{1,2})\s*[-:]\s*(\d{1,2})\s*$""")
        private val INLINE_SCORE_REGEX = Regex("""\b(\d{1,2})\s*[-:]\s*(\d{1,2})\b""")
        private val STATUS_SLASH_SCORE_REGEX = Regex(
            """^\s*([^/\n]+?)\s*/\s*(\d{1,2})\s*[-:]\s*(\d{1,2})\s*$"""
        )
        private val MINUTE_REGEX = Regex(
            """^(\d{1,3}\s*(?:\+\s*\d{1,2})?\s*['’]|İY|I\.Y\.|DEVRE|MS|M\.S\.|BİTTİ|CANLI|ERT|İPT|BAŞLAMADI)$""",
            RegexOption.IGNORE_CASE
        )
        private val CLOCK_TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")
        private val DATE_TIME_REGEX = Regex("""^\d{2}\.\d{2}(?:\.\d{4})?\s+\d{2}:\d{2}$""")
        private val WEEK_NUMBER_REGEX = Regex("""(\d{1,4})\s*\.?\s*Hafta""", RegexOption.IGNORE_CASE)
        private val PROGRAM_TITLE_REGEX = Regex(
            """(\d{2}\.\d{2}\.\d{4}\s*-\s*\d{2}\.\d{2}\.\d{4}\s+Program[ıi])""",
            RegexOption.IGNORE_CASE
        )
        private val MATCH_NO_REGEX = Regex("""^0?([1-9]|1[0-5])\.?$""")
        private val PERCENT_INLINE_REGEX = Regex("""%\s*(\d{1,2})|(\d{1,2})\s*%""")
        private val TURKISH_DATE_OR_DAY_REGEX = Regex(
            """^(?:(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar|bugün|yarın|dün)[\s,./-]*)*(?:\d{1,2}\s+(?:oca|ocak|şub|şubat|mar|mart|nis|nisan|may|mayıs|haz|haziran|tem|temmuz|ağu|ağustos|eyl|eylül|eki|ekim|kas|kasım|ara|aralık)(?:\s+\d{4})?(?:[\s,]+(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar))?(?:[\s,./-]+(?:[01]?\d|2[0-3]):[0-5]\d)?|\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?(?:[\s,]+(?:[01]?\d|2[0-3]):[0-5]\d)?|(?:bugün|yarın|dün|pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar)(?:[\s,]+(?:[01]?\d|2[0-3]):[0-5]\d)?)$""",
            RegexOption.IGNORE_CASE
        )

        private const val JS_PASSIVE_NETWORK_HOOK = """
            (function() {
                if (window.__nesineTotoHooked) return;
                window.__nesineTotoHooked = true;
                window.__nesineTotoCaptured = window.__nesineTotoCaptured || [];
                window.__nesineTotoSeen = window.__nesineTotoSeen || {};
                function pushJson(text) {
                    if (!text || text.length < 30 || text.length > 280000 || window.__nesineTotoCaptured.length >= 8) return;
                    var sig = text.length + ':' + text.slice(0, 48);
                    if (window.__nesineTotoSeen[sig]) return;
                    if (text.indexOf('Home') !== -1 || text.indexOf('home') !== -1 ||
                        text.indexOf('Ev') !== -1 || text.indexOf('Match') !== -1 ||
                        text.indexOf('Sportoto') !== -1 || text.indexOf('sportoto') !== -1 ||
                        text.indexOf('Matches') !== -1 || text.indexOf('Program') !== -1) {
                        window.__nesineTotoSeen[sig] = true;
                        window.__nesineTotoCaptured.push(text);
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
                    var selectEl = document.getElementById('filter-week-select');
                    if (selectEl) {
                        weekNumAttr = (selectEl.value || '').trim();
                        var opt = selectEl.options && selectEl.selectedIndex >= 0 ? selectEl.options[selectEl.selectedIndex] : null;
                        if (opt && opt.text) {
                            weekHeading = (weekNumAttr ? (weekNumAttr + '. Hafta (' + opt.text.trim() + ')') : opt.text.trim());
                        }
                    }
                    if (!weekHeading) {
                        var headingEl = document.querySelector('th[colspan="3"],h1,h2,[class*="week"],[class*="hafta"],select option:checked');
                        if (headingEl && headingEl.innerText) weekHeading = headingEl.innerText.trim();
                    }

                    var nextEl = document.getElementById('__NEXT_DATA__');
                    var nextDataRaw = (nextEl && nextEl.textContent && nextEl.textContent.length > 50 && nextEl.textContent.length <= 280000) ? nextEl.textContent : '';

                    var capturedList = [];
                    if (window.__nesineTotoCaptured && window.__nesineTotoCaptured.length > 0) {
                        for (var i = 0; i < window.__nesineTotoCaptured.length; i++) {
                            var c = window.__nesineTotoCaptured[i];
                            if (c && c.length > 40) capturedList.push(c);
                        }
                    }

                    var items = [];
                    var seen = {};
                    var selectors = 'table tbody tr,.sportoto-row,[data-match-row],tr[data-match-no],[data-test-id*="program"] tr,[role="row"],.toto-match-item,[class*="sportoto"] [class*="row"],[class*="match-row"],[class*="result-row"],[class*="fixture"]';
                    var nodes = document.querySelectorAll(selectors);
                    for (var n = 0; n < nodes.length && items.length < 40; n++) {
                        var el = nodes[n];
                        var cells = el.querySelectorAll ? el.querySelectorAll('td,[role="cell"]') : null;
                        var lines = [];
                        if (cells && cells.length >= 3) {
                            for (var k = 0; k < cells.length; k++) {
                                var ct = (cells[k].innerText || cells[k].textContent || '').trim();
                                if (ct) lines.push(ct);
                            }
                        } else {
                            var txt = (el.innerText || '').trim();
                            if (!txt || txt.length < 6 || txt.length > 500) continue;
                            lines = txt.split(/\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
                        }
                        if (lines.length < 2 || lines.length > 24) continue;
                        var key = lines.join('|');
                        if (seen[key]) continue;
                        seen[key] = true;
                        items.push({ lines: lines });
                    }
                    var hasFullDom = items.length >= 15;
                    var bodyText = (!hasFullDom && document.body) ? (document.body.innerText || '').slice(0, 12000) : '';
                    return JSON.stringify({
                        kind: 'combined',
                        nextData: hasFullDom ? '' : nextDataRaw,
                        captured: hasFullDom ? [] : capturedList,
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

        fun parseHtmlDocument(html: String): NesineWebViewPayload {
            if (html.isBlank()) return NesineWebViewPayload(emptyList())
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
            }

            // 0. Nesine #filter-week-select or bulletin "DD.MM.YYYY - DD.MM.YYYY Programı" metadata
            val selectWeekMatch = FILTER_WEEK_SELECTED_OPTION_REGEX.find(html)
                ?: FILTER_WEEK_SELECT_REGEX.find(html)
            if (selectWeekMatch != null) {
                val wNum = selectWeekMatch.groupValues[1].toIntOrNull()
                val wRange = HTML_TAG_REGEX.replace(selectWeekMatch.groupValues[2], "").trim()
                if (wNum != null) {
                    weekNumber = wNum
                    weekTitle = if (wRange.isNotBlank()) "$wNum. Hafta ($wRange)" else "$wNum. Hafta"
                }
            }
            if (weekTitle.isBlank()) {
                PROGRAM_TITLE_REGEX.find(html)?.let { progMatch ->
                    weekTitle = progMatch.groupValues[1].trim()
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

            // 2. Inline window state JSON objects
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

            // 4a. Exact Nesine programResult-* structured table rows
            if (out.isEmpty()) {
                out.addAll(parseProgramResultRows(sanitizedHtml))
            }

            // 4b. Class/attribute-marked HTML table / row blocks (script & template tags stripped!)
            if (out.isEmpty()) {
                HTML_ROW_BLOCK_REGEX.findAll(sanitizedHtml).forEach { blockMatch ->
                    val lines = cleanHtmlFragmentToLines(blockMatch.value)
                    parseLinesIntoMatch(lines, out.size + 1)?.let { out.add(it) }
                }
            }

            // 4c. Generic <tr>...</tr> rows inside body (excludes thead/tfoot/script/template)
            if (out.isEmpty()) {
                val cleanBodyHtml = STRIP_THEAD_TFOOT_REGEX.replace(sanitizedHtml, "\n")
                GENERIC_TR_REGEX.findAll(cleanBodyHtml).forEach { trMatch ->
                    val trBody = trMatch.groupValues[1]
                    if (trBody.contains("<td", ignoreCase = true)) {
                        val lines = cleanHtmlFragmentToLines(trBody)
                        parseLinesIntoMatch(lines, out.size + 1)?.let { out.add(it) }
                    }
                }
            }

            // 5. Sequential visible lines
            if (out.isEmpty()) {
                val visibleLines = cleanHtmlFragmentToLines(sanitizedHtml)
                visibleLines.forEach { detectWeekMeta(it) }
                out.addAll(parseSequentialVisibleLines(visibleLines))
            }

            detectWeekMeta(sanitizedHtml.take(8000))
            return NesineWebViewPayload(
                matches = normalizeOrder(out),
                weekTitle = weekTitle,
                weekNumber = weekNumber
            )
        }

        fun parseWebViewExtractorPayload(rawPayload: String): NesineWebViewPayload {
            val trimmed = rawPayload.trim()
            if (trimmed.isEmpty()) return NesineWebViewPayload(emptyList())
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return extractFromJsonString(trimmed)
            val kind = root.optString("kind")
            val topWeekTitle = root.optString("weekTitle").trim()
            val topWeekNum = root.optString("weekNumber").trim().toIntOrNull()
                ?: WEEK_NUMBER_REGEX.find(topWeekTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

            fun parseDomFromObject(obj: JSONObject): NesineWebViewPayload {
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
                        parseLinesIntoMatch(lines, out.size + 1)?.let { out.add(it) }
                    }
                }
                if (out.isEmpty()) {
                    val bodyText = obj.optString("bodyText")
                    if (bodyText.isNotBlank()) {
                        val lines = bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        out.addAll(parseSequentialVisibleLines(lines))
                    }
                }
                return NesineWebViewPayload(
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
                "dom" -> parseDomFromObject(root)
                "combined" -> {
                    val candidates = mutableListOf<NesineWebViewPayload>()
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
                        ?: NesineWebViewPayload(emptyList())
                    best.copy(
                        weekTitle = best.weekTitle.ifBlank { topWeekTitle },
                        weekNumber = best.weekNumber ?: topWeekNum
                    )
                }
                else -> extractFromJsonString(trimmed)
            }
        }

        fun extractFromJsonString(jsonString: String): NesineWebViewPayload {
            val token = runCatching { JSONTokener(jsonString).nextValue() }.getOrNull()
                ?: return NesineWebViewPayload(emptyList())
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
                        if (weekNumber == null) {
                            listOf("weekNumber", "weekNo", "WeekNo", "hafta", "Hafta", "DrawNo", "programNo")
                                .firstNotNullOfOrNull { node.opt(it)?.toString()?.toIntOrNull() }
                                ?.takeIf { it in 1..9999 }
                                ?.let { weekNumber = it }
                        }
                        if (weekTitle.isBlank()) {
                            listOf("weekName", "WeekName", "haftaAdi", "HaftaAdi", "title", "Title")
                                .firstNotNullOfOrNull { node.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
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
            return NesineWebViewPayload(
                matches = normalizeOrder(out),
                weekTitle = weekTitle,
                weekNumber = weekNumber
            )
        }

        private fun tryParseJsonMatchObject(obj: JSONObject, fallbackNumber: Int): NesineSporTotoScrapedMatch? {
            var homeName = extractTeamName(
                obj,
                listOf("homeTeam", "HomeTeam", "home", "Home", "homeName", "HomeTeamName", "evSahibi", "EvSahibi", "team1", "ht")
            )
            var awayName = extractTeamName(
                obj,
                listOf("awayTeam", "AwayTeam", "away", "Away", "awayName", "AwayTeamName", "deplasman", "Deplasman", "team2", "at")
            )

            // Split combined "HomeTeam - AwayTeam" fields if separate fields weren't present
            if (homeName == null || awayName == null) {
                val combined = listOf("matchName", "MatchName", "teams", "Teams", "name", "Name", "macAdi")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                if (combined != null && combined.contains(" - ")) {
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

            val scoresPair = extractScoresFromObject(obj)
            val rawStatus = listOf(
                "status", "Status", "state", "State", "statusText", "StatusText",
                "durum", "Durum", "period", "Period", "liveStatus"
            ).firstNotNullOfOrNull { k ->
                obj.optString(k).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()

            val rawMinute = listOf(
                "minute", "Minute", "minuteText", "dakika", "Dakika", "dk", "Dk", "min", "timeText", "clock"
            ).firstNotNullOfOrNull { k ->
                obj.optString(k).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()

            val isLiveFlag = obj.optBoolean("isLive", false) ||
                obj.optBoolean("IsLive", false) ||
                obj.optBoolean("canli", false)
            val isFinishedFlag = obj.optBoolean("isFinished", false) ||
                obj.optBoolean("IsFinished", false) ||
                obj.optBoolean("bitti", false)

            val resolvedStatus = resolveStatusFromStrings(
                rawStatus = if (isFinishedFlag && rawStatus.isBlank()) "FT" else rawStatus,
                rawMinute = rawMinute,
                isLiveFlag = isLiveFlag,
                hasScore = scoresPair.first != null && scoresPair.second != null
            )
            val formattedMinute = normalizeMinuteLabel(resolvedStatus, rawMinute, rawStatus)

            val rawDate = listOf(
                "matchDate", "MatchDate", "date", "Date", "startDate", "StartDate",
                "kickoff", "tarih", "Tarih", "saat"
            ).firstNotNullOfOrNull { k ->
                obj.optString(k).takeIf { it.isNotBlank() && it != "null" }
            } ?: obj.optLong("timestamp", -1L).takeIf { it > 0L }?.toString()
                ?: ""

            val league = listOf("leagueName", "LeagueName", "league", "League", "lig", "Lig")
                .firstNotNullOfOrNull { k ->
                    when (val v = obj.opt(k)) {
                        is String -> v.trim().takeIf { it.isNotBlank() && it != "null" }
                        is JSONObject -> v.optString("name").ifBlank { v.optString("title") }.trim().takeIf { it.isNotBlank() }
                        else -> null
                    }
                } ?: "Spor Toto Listesi"

            val matchNo = listOf("matchNumber", "MatchNumber", "matchNo", "MatchNo", "rowNo", "RowNo", "orderNo", "OrderNo", "sira", "Sira", "no")
                .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.toIntOrNull() }
                ?.takeIf { it in 1..15 }
                ?: fallbackNumber.coerceIn(1, 15)

            val explicitResult = listOf("resultCode", "ResultCode", "sonuc", "Sonuc", "winningOutcome")
                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }
                ?.let { parseOutcomeBadge(it) }

            val hProb = listOf("homeRate", "HomeRate", "rate1", "Rate1", "p1", "percent1", "evYuzde", "yuzde1")
                .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.replace("%", "")?.trim()?.toDoubleOrNull()?.toInt() }
            val dProb = listOf("drawRate", "DrawRate", "rateX", "RateX", "rate0", "px", "p0", "percentX", "beraberlikYuzde", "yuzdeX")
                .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.replace("%", "")?.trim()?.toDoubleOrNull()?.toInt() }
            val aProb = listOf("awayRate", "AwayRate", "rate2", "Rate2", "p2", "percent2", "depYuzde", "yuzde2")
                .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.replace("%", "")?.trim()?.toDoubleOrNull()?.toInt() }

            return NesineSporTotoScrapedMatch(
                matchNumber = matchNo,
                homeTeam = cleanTeamName(homeName),
                awayTeam = cleanTeamName(awayName),
                homeScore = scoresPair.first,
                awayScore = scoresPair.second,
                status = resolvedStatus,
                minuteText = formattedMinute,
                matchDate = normalizeDateString(rawDate),
                league = league,
                explicitOutcome = explicitResult,
                homeProb = hProb,
                drawProb = dProb,
                awayProb = aProb
            )
        }

        private fun extractTeamName(obj: JSONObject, keys: List<String>): String? {
            for (k in keys) {
                val v = obj.opt(k) ?: continue
                when (v) {
                    is String -> if (v.isNotBlank() && !v.equals("null", true)) return v.trim()
                    is JSONObject -> {
                        val n = v.optString("name").ifBlank {
                            v.optString("Name").ifBlank {
                                v.optString("shortName").ifBlank { v.optString("title") }
                            }
                        }
                        if (n.isNotBlank()) return n.trim()
                    }
                }
            }
            return null
        }

        private fun extractScoresFromObject(obj: JSONObject): Pair<Int?, Int?> {
            val hsDirect = listOf("homeScore", "HomeScore", "hs", "evSkor", "EvSkor", "msHome", "MsHome")
                .firstNotNullOfOrNull { key -> obj.opt(key)?.toString()?.toIntOrNull() }
            val asDirect = listOf("awayScore", "AwayScore", "as", "depSkor", "DepSkor", "msAway", "MsAway")
                .firstNotNullOfOrNull { key -> obj.opt(key)?.toString()?.toIntOrNull() }
            if (hsDirect != null && asDirect != null) return hsDirect to asDirect

            val homeObj = obj.optJSONObject("home") ?: obj.optJSONObject("homeTeam") ?: obj.optJSONObject("HomeTeam")
            val awayObj = obj.optJSONObject("away") ?: obj.optJSONObject("awayTeam") ?: obj.optJSONObject("AwayTeam")
            val hsNested = homeObj?.let { it.opt("score")?.toString()?.toIntOrNull() ?: it.opt("goals")?.toString()?.toIntOrNull() }
            val asNested = awayObj?.let { it.opt("score")?.toString()?.toIntOrNull() ?: it.opt("goals")?.toString()?.toIntOrNull() }
            if (hsNested != null && asNested != null) return hsNested to asNested

            val scoreObj = obj.optJSONObject("score") ?: obj.optJSONObject("scores") ?: obj.optJSONObject("result")
            if (scoreObj != null) {
                val ft = scoreObj.optJSONObject("fullTime") ?: scoreObj.optJSONObject("current") ?: scoreObj
                val h = listOf("home", "Home", "h", "homeScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                val a = listOf("away", "Away", "a", "awayScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                if (h != null && a != null) return h to a
            }

            val scoreStr = listOf("score", "Score", "skor", "Skor", "result", "Result", "ms", "MS")
                .firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() && !CLOCK_TIME_REGEX.matches(it.trim()) } }
            if (scoreStr != null) {
                SCORE_REGEX.find(scoreStr)?.let { m ->
                    return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
                }
            }
            return null to null
        }

        private fun resolveStatusFromStrings(
            rawStatus: String,
            rawMinute: String,
            isLiveFlag: Boolean,
            hasScore: Boolean
        ): MatchStatus {
            val trLocale = Locale.forLanguageTag("tr-TR")
            val s = rawStatus.uppercase(trLocale).trim()
            val m = rawMinute.uppercase(trLocale).trim()
            return when {
                s in setOf("HT", "HALF_TIME", "HALFTIME", "İY", "DEVRE", "DEVRE ARASI", "BREAK") ||
                    m in setOf("İY", "HT", "DEVRE") -> MatchStatus.HALF_TIME
                s in setOf("FT", "FINISHED", "ENDED", "COMPLETE", "COMPLETED", "MS", "BİTTİ", "AET", "PEN", "MAÇ SONUCU") ||
                    m in setOf("MS", "FT", "BİTTİ") -> MatchStatus.FINISHED
                s in setOf("UPCOMING", "PRE", "SCHEDULED", "NS", "NOT_STARTED", "BAŞLAMADI", "BEKLİYOR", "POSTPONED", "ERT") ->
                    MatchStatus.UPCOMING
                isLiveFlag ||
                    s in setOf("LIVE", "IN_PLAY", "INPLAY", "1H", "2H", "ET", "CANLI", "OYNANIYOR") ||
                    m.contains("'") -> MatchStatus.LIVE
                hasScore -> MatchStatus.LIVE
                else -> MatchStatus.UPCOMING
            }
        }

        private fun normalizeMinuteLabel(status: MatchStatus, rawMinute: String, rawStatus: String): String {
            val clean = rawMinute.trim()
            return when (status) {
                MatchStatus.HALF_TIME -> "İY"
                MatchStatus.FINISHED -> "MS"
                MatchStatus.LIVE -> when {
                    clean.isEmpty() || clean == "—" -> "CANLI"
                    clean.all { it.isDigit() } -> "${clean}'"
                    else -> clean
                }
                MatchStatus.UPCOMING -> {
                    CLOCK_TIME_REGEX.find(clean)?.value ?: rawStatus.takeIf { CLOCK_TIME_REGEX.matches(it.trim()) } ?: ""
                }
            }
        }

        private fun normalizeDateString(raw: String): String {
            val outFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = ISTANBUL_TZ }
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return outFmt.format(Date())

            trimmed.toLongOrNull()?.let { epoch ->
                val ms = if (epoch < 10_000_000_000L) epoch * 1000L else epoch
                return outFmt.format(Date(ms))
            }

            if (CLOCK_TIME_REGEX.matches(trimmed)) {
                val todayStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = ISTANBUL_TZ }.format(Date())
                return "$todayStr $trimmed"
            }

            val patterns = listOf(
                "dd.MM.yyyy HH:mm",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm"
            )
            for (pattern in patterns) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.ROOT).apply {
                        isLenient = false
                        if (pattern.endsWith("'Z'")) {
                            timeZone = TimeZone.getTimeZone("UTC")
                        } else if (!pattern.contains("XXX")) {
                            timeZone = ISTANBUL_TZ
                        }
                    }.parse(trimmed)
                }.getOrNull()
                if (parsed != null) return outFmt.format(parsed)
            }
            return trimmed
        }

        private fun cleanHtmlFragmentToLines(htmlFragment: String): List<String> {
            val withNewlines = htmlFragment
                .replace(Regex("</?(?:tr|div|p|li|td|th|span|h[1-6]|br)[^>]*>", RegexOption.IGNORE_CASE), "\n")
                .let { HTML_TAG_REGEX.replace(it, "") }
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
            return withNewlines.lines()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotBlank() }
        }

        private fun parseProgramResultRows(html: String): List<NesineSporTotoScrapedMatch> {
            val out = mutableListOf<NesineSporTotoScrapedMatch>()
            GENERIC_TR_REGEX.findAll(html).forEach { trMatch ->
                val trBody = trMatch.groupValues[1]
                val cellMap = mutableMapOf<String, String>()
                PROGRAM_RESULT_CELL_REGEX.findAll(trBody).forEach { cellMatch ->
                    val key = cellMatch.groupValues[1].lowercase(Locale.ROOT)
                    val value = cleanHtmlFragmentToLines(cellMatch.groupValues[2]).joinToString(" ")
                    if (value.isNotBlank()) cellMap[key] = value
                }
                val nameRaw = cellMap["name"] ?: return@forEach
                val teamPair = splitTeamPair(nameRaw) ?: return@forEach
                val matchNo = cellMap["number"]?.toIntOrNull()?.takeIf { it in 1..15 } ?: (out.size + 1)
                val dateRaw = cellMap["date"].orEmpty()
                val statusRaw = cellMap["status"].orEmpty()
                val resultBadge = cellMap["result"]?.let { parseOutcomeBadge(it) }

                val (status, minuteLabel, hs, aws) = parseCombinedStatusAndScore(statusRaw)
                out.add(
                    NesineSporTotoScrapedMatch(
                        matchNumber = matchNo,
                        homeTeam = cleanTeamName(teamPair.first),
                        awayTeam = cleanTeamName(teamPair.second),
                        homeScore = hs,
                        awayScore = aws,
                        status = status,
                        minuteText = minuteLabel.ifBlank {
                            if (status == MatchStatus.UPCOMING) normalizeDateString(dateRaw).takeLast(5) else ""
                        },
                        matchDate = normalizeDateString(dateRaw),
                        league = "Spor Toto Listesi",
                        explicitOutcome = resultBadge
                    )
                )
            }
            return out
        }

        private data class ParsedStatusScore(
            val status: MatchStatus,
            val minuteLabel: String,
            val homeScore: Int?,
            val awayScore: Int?
        )

        private fun parseCombinedStatusAndScore(raw: String): ParsedStatusScore {
            val clean = raw.trim()
            if (clean.isEmpty()) {
                return ParsedStatusScore(MatchStatus.UPCOMING, "", null, null)
            }
            STATUS_SLASH_SCORE_REGEX.find(clean)?.let { m ->
                val statusText = m.groupValues[1].trim()
                val hs = m.groupValues[2].toIntOrNull()
                val aws = m.groupValues[3].toIntOrNull()
                val resolved = resolveStatusFromStrings(
                    rawStatus = statusText,
                    rawMinute = statusText,
                    isLiveFlag = false,
                    hasScore = hs != null && aws != null
                )
                return ParsedStatusScore(
                    status = resolved,
                    minuteLabel = normalizeMinuteLabel(resolved, statusText, statusText),
                    homeScore = hs,
                    awayScore = aws
                )
            }
            SCORE_REGEX.find(clean)?.let { m ->
                val hs = m.groupValues[1].toIntOrNull()
                val aws = m.groupValues[2].toIntOrNull()
                return ParsedStatusScore(MatchStatus.LIVE, "CANLI", hs, aws)
            }
            val resolved = resolveStatusFromStrings(
                rawStatus = clean,
                rawMinute = clean,
                isLiveFlag = false,
                hasScore = false
            )
            return ParsedStatusScore(
                status = resolved,
                minuteLabel = normalizeMinuteLabel(resolved, clean, clean),
                homeScore = null,
                awayScore = null
            )
        }

        private fun splitTeamPair(line: String): Pair<String, String>? {
            val clean = line.trim()
            if (clean.contains(" - ")) {
                val parts = clean.split(" - ", limit = 2)
                if (parts.size == 2 && looksLikeTeamName(parts[0]) && looksLikeTeamName(parts[1])) {
                    return parts[0].trim() to parts[1].trim()
                }
            }
            if (clean.contains('-')) {
                val indices = clean.mapIndexedNotNull { idx, c -> if (c == '-') idx else null }
                val ordered = indices.sortedBy { kotlin.math.abs(it - clean.length / 2) }
                for (splitIdx in ordered) {
                    val left = clean.substring(0, splitIdx).trim()
                    val right = clean.substring(splitIdx + 1).trim()
                    if (looksLikeTeamName(left) && looksLikeTeamName(right)) {
                        return left to right
                    }
                }
            }
            return null
        }

        private fun parseLinesIntoMatch(lines: List<String>, fallbackNumber: Int): NesineSporTotoScrapedMatch? {
            if (lines.size < 2) return null
            var matchNo: Int? = null
            var homeTeam: String? = null
            var awayTeam: String? = null
            var homeScore: Int? = null
            var awayScore: Int? = null
            var status = MatchStatus.UPCOMING
            var minute = ""
            var timeStr = ""
            var explicitOutcome: PredictionOutcome? = null
            val collectedPercents = mutableListOf<Int>()

            for (line in lines) {
                if (CLOCK_TIME_REGEX.matches(line) || DATE_TIME_REGEX.matches(line)) {
                    timeStr = line
                    continue
                }
                if (line.contains("%")) {
                    var foundPct = false
                    PERCENT_INLINE_REGEX.findAll(line).forEach { pm ->
                        val rawDigits = pm.groupValues[1].ifBlank { pm.groupValues[2] }
                        val pctVal = rawDigits.toIntOrNull()
                        if (pctVal != null && pctVal in 1..98) {
                            collectedPercents.add(pctVal)
                            foundPct = true
                        }
                    }
                    if (foundPct) {
                        val stripped = PERCENT_INLINE_REGEX.replace(line, "").trim()
                        if (stripped.isEmpty()) continue
                    }
                }
                if (matchNo == null && homeTeam == null) {
                    val mNo = MATCH_NO_REGEX.find(line)
                    if (mNo != null) {
                        matchNo = mNo.groupValues[1].toIntOrNull()
                        continue
                    }
                }
                if (homeTeam != null && awayTeam != null && explicitOutcome == null) {
                    val badge = parseOutcomeBadge(line)
                    if (badge != null) {
                        explicitOutcome = badge
                        continue
                    }
                }
                val slashScore = STATUS_SLASH_SCORE_REGEX.find(line)
                if (slashScore != null && homeScore == null) {
                    val parsedCombo = parseCombinedStatusAndScore(line)
                    homeScore = parsedCombo.homeScore
                    awayScore = parsedCombo.awayScore
                    status = parsedCombo.status
                    minute = parsedCombo.minuteLabel
                    continue
                }
                val scoreMatch = SCORE_REGEX.find(line)
                if (scoreMatch != null && homeScore == null) {
                    homeScore = scoreMatch.groupValues[1].toIntOrNull()
                    awayScore = scoreMatch.groupValues[2].toIntOrNull()
                    if (status == MatchStatus.UPCOMING) status = MatchStatus.LIVE
                    continue
                }
                if (MINUTE_REGEX.matches(line)) {
                    minute = line.uppercase(Locale.forLanguageTag("tr-TR"))
                    status = resolveStatusFromStrings(
                        minute,
                        minute,
                        isLiveFlag = !minute.equals("BAŞLAMADI", ignoreCase = true),
                        hasScore = homeScore != null
                    )
                    continue
                }
                // Check inline "TeamA 2 - 1 TeamB" pattern (avoiding HH:MM clock times)
                val inlineScore = INLINE_SCORE_REGEX.find(line)
                if (inlineScore != null &&
                    !CLOCK_TIME_REGEX.matches(inlineScore.value.trim()) &&
                    inlineScore.range.first > 1 &&
                    inlineScore.range.last < line.lastIndex - 1
                ) {
                    val left = line.substring(0, inlineScore.range.first).trim()
                    val right = line.substring(inlineScore.range.last + 1).trim()
                    if (looksLikeTeamName(left) && looksLikeTeamName(right)) {
                        homeTeam = left
                        awayTeam = right
                        homeScore = inlineScore.groupValues[1].toIntOrNull()
                        awayScore = inlineScore.groupValues[2].toIntOrNull()
                        if (status == MatchStatus.UPCOMING) status = MatchStatus.LIVE
                        continue
                    }
                }
                // Check "HomeTeam - AwayTeam" or "HomeTeam-AwayTeam" combined line
                if (homeTeam == null) {
                    val teamPair = splitTeamPair(line)
                    if (teamPair != null) {
                        homeTeam = teamPair.first
                        awayTeam = teamPair.second
                        continue
                    }
                }
                if (looksLikeTeamName(line) && !looksLikeUiNoise(line)) {
                    if (homeTeam == null) {
                        homeTeam = line
                    } else if (awayTeam == null && !line.equals(homeTeam, ignoreCase = true)) {
                        awayTeam = line
                    }
                }
            }

            val h = homeTeam ?: return null
            val a = awayTeam ?: return null
            val finalStatus = resolveStatusFromStrings(minute, minute, status == MatchStatus.LIVE, homeScore != null)
            val (hp, dp, ap) = if (collectedPercents.size >= 3) {
                Triple(collectedPercents[0], collectedPercents[1], collectedPercents[2])
            } else {
                Triple(null, null, null)
            }
            return NesineSporTotoScrapedMatch(
                matchNumber = (matchNo ?: fallbackNumber).coerceIn(1, 15),
                homeTeam = cleanTeamName(h),
                awayTeam = cleanTeamName(a),
                homeScore = homeScore,
                awayScore = awayScore,
                status = finalStatus,
                minuteText = normalizeMinuteLabel(finalStatus, minute.ifBlank { timeStr }, timeStr),
                matchDate = normalizeDateString(timeStr),
                league = "Spor Toto Listesi",
                explicitOutcome = explicitOutcome,
                homeProb = hp,
                drawProb = dp,
                awayProb = ap
            )
        }

        private fun parseSequentialVisibleLines(lines: List<String>): List<NesineSporTotoScrapedMatch> {
            val out = mutableListOf<NesineSporTotoScrapedMatch>()
            var i = 0
            while (i < lines.size) {
                val window = lines.subList(i, minOf(i + 6, lines.size))
                val parsed = parseLinesIntoMatch(window, out.size + 1)
                if (parsed != null) {
                    out.add(parsed)
                    i += 3
                } else {
                    i++
                }
            }
            return out
        }

        private fun parseOutcomeBadge(raw: String): PredictionOutcome? {
            return when (raw.trim().uppercase(Locale.ROOT)) {
                "1" -> PredictionOutcome.HOME
                "X", "0" -> PredictionOutcome.DRAW
                "2" -> PredictionOutcome.AWAY
                else -> null
            }
        }

        private fun looksLikeUiNoise(line: String): Boolean {
            val lower = line.lowercase(Locale.forLanguageTag("tr-TR")).trim()
            if (TEMPLATE_SYNTAX_REGEX.containsMatchIn(line)) return true
            if (TURKISH_DATE_OR_DAY_REGEX.matches(lower.replace("•", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim())) return true
            return lower in setOf(
                "canlı", "tümü", "biten", "başlamadı", "spor toto", "maç sonuçları",
                "nesine", "nesine.com", "hafta", "program", "ms", "iy", "i.y.",
                "sonuç", "tercih", "1", "x", "2", "1 x 2", "v", "vs", "kolon",
                "no", "tarih", "karşılaşma", "durum", "program seçimi",
                "toplam oran", "misli", "kupon bedeli", "maksimum kazanç",
                "kolon sayısı", "hemen oyna", "kuponum", "sistem", "banko"
            )
        }

        private fun looksLikeTeamName(text: String): Boolean {
            val clean = text.trim()
            if (clean.length !in 2..48) return false
            if (TEMPLATE_SYNTAX_REGEX.containsMatchIn(clean)) return false
            if (clean.any { it in setOf('{', '}', '<', '>', ';', '[', ']', '=') }) return false
            if (looksLikeUiNoise(clean)) return false
            if (clean.all { it.isDigit() || it in setOf('-', ':', '.', '/', '\'', ' ') }) return false
            if (CLOCK_TIME_REGEX.matches(clean) || SCORE_REGEX.matches(clean)) return false
            return clean.any { it.isLetter() }
        }

        private fun cleanTeamName(raw: String): String {
            return raw.trim().replace(Regex("\\s+"), " ")
        }

        private fun normalizeOrder(list: List<NesineSporTotoScrapedMatch>): List<NesineSporTotoScrapedMatch> {
            val trLocale = Locale.forLanguageTag("tr-TR")
            return list
                .distinctBy { "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}" }
                .sortedBy { it.matchNumber }
                .take(15)
                .mapIndexed { idx, item ->
                    val resolvedNo = if (item.matchNumber in 1..15) item.matchNumber else (idx + 1)
                    item.copy(matchNumber = resolvedNo)
                }
        }

        private fun decodeJsEvaluatedString(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed == "null" || trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                runCatching { JSONTokener(trimmed).nextValue()?.toString().orEmpty() }.getOrDefault(trimmed)
            } else {
                trimmed
            }
        }
    }
}
