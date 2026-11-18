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
import kotlin.math.abs

const val BILYONER_LIVE_SCORE_URL = "https://www.bilyoner.com/canli-skor"

data class BilyonerScrapedMatch(
    val externalId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val status: MatchStatus = MatchStatus.UPCOMING,
    val minuteText: String = "",
    val matchDate: String = "",
    val league: String = "Bilyoner Canlı Skor",
    val halfTimeText: String = "",
    val hasLiveStream: Boolean = false,
    val homeOdd: Double = 2.15,
    val drawOdd: Double = 3.25,
    val awayOdd: Double = 3.30
) {
    fun toMatchLiveScore(fallbackNumber: Int): MatchLiveScore {
        val numericId = externalId.toIntOrNull()?.takeIf { it > 0 }
            ?: (abs("${homeTeam}|${awayTeam}".hashCode()) % 900_000 + 100_000 + fallbackNumber)
        return MatchLiveScore(
            matchNumber = numericId,
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
            league = league,
            homeWinOdd = homeOdd,
            drawOdd = drawOdd,
            awayWinOdd = awayOdd
        )
    }

    fun toBulletinMatchDto(matchNumber: Int): BulletinMatchDto {
        val statusLabel = when (status) {
            MatchStatus.FINISHED -> "FINISHED"
            MatchStatus.LIVE, MatchStatus.HALF_TIME -> "LIVE"
            MatchStatus.UPCOMING -> "UPCOMING"
        }
        val trLocale = Locale.forLanguageTag("tr-TR")
        val hProb: Int
        val dProb: Int
        val aProb: Int
        if (homeOdd > 1.01 && drawOdd > 1.01 && awayOdd > 1.01) {
            val invH = 1.0 / homeOdd
            val invD = 1.0 / drawOdd
            val invA = 1.0 / awayOdd
            val sum = invH + invD + invA
            val p1 = ((invH / sum) * 100).toInt().coerceIn(3, 94)
            val maxPx = (97 - p1).coerceAtLeast(3)
            val px = ((invD / sum) * 100).toInt().coerceIn(3, maxPx)
            val p2 = (100 - p1 - px).coerceAtLeast(3)
            hProb = p1
            dProb = px
            aProb = p2
        } else {
            hProb = 42
            dProb = 29
            aProb = 29
        }
        val dc1X = if (homeOdd > 1.0 && drawOdd > 1.0) (1.0 / ((1.0 / homeOdd) + (1.0 / drawOdd))).coerceAtLeast(1.01) else 1.30
        val dc12 = if (homeOdd > 1.0 && awayOdd > 1.0) (1.0 / ((1.0 / homeOdd) + (1.0 / awayOdd))).coerceAtLeast(1.01) else 1.25
        val dcX2 = if (drawOdd > 1.0 && awayOdd > 1.0) (1.0 / ((1.0 / drawOdd) + (1.0 / awayOdd))).coerceAtLeast(1.01) else 1.60
        return BulletinMatchDto(
            matchNumber = matchNumber.coerceIn(1, 15),
            homeTeam = homeTeam.uppercase(trLocale).trim(),
            awayTeam = awayTeam.uppercase(trLocale).trim(),
            matchDate = matchDate,
            league = league.ifBlank { "Bilyoner Canlı Skor" },
            venue = "",
            status = statusLabel,
            liveOdds = LiveBettingOddsDto(
                homeWinOdd = homeOdd,
                drawOdd = drawOdd,
                awayWinOdd = awayOdd,
                doubleChance1X = (dc1X * 100).toInt() / 100.0,
                doubleChance12 = (dc12 * 100).toInt() / 100.0,
                doubleChanceX2 = (dcX2 * 100).toInt() / 100.0
            ),
            probabilities = MatchProbabilitiesDto(
                homeProbability = hProb,
                drawProbability = dProb,
                awayProbability = aProb
            )
        )
    }
}

data class BilyonerScrapeResult(
    val sourceUrl: String = BILYONER_LIVE_SCORE_URL,
    val methodLabel: String,
    val matches: List<BilyonerScrapedMatch>,
    val scrapedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Multi-stage live football scraper for https://www.bilyoner.com/canli-skor:
 * 1) Direct HTTP fetch parsing Bilyoner sticky-box league headers & `a.event-content__row` cells,
 *    plus SSR __NEXT_DATA__ / RSC chunks if present.
 * 2) Automatic headless Chromium WebView fallback capturing passive XHR/Fetch API JSON
 *    and rendered `.event-content__row` DOM rows.
 */
class BilyonerLiveScraper(
    private val applicationContext: Context? = null,
    private val httpFetcher: (suspend (String) -> Result<String>)? = null,
    private val webViewFetcher: (suspend (String) -> Result<List<BilyonerScrapedMatch>>)? = null
) {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun scrapeLiveScoreboard(
        targetUrl: String = BILYONER_LIVE_SCORE_URL
    ): Result<BilyonerScrapeResult> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var stage1Partial: BilyonerScrapeResult? = null
        val canRunWebView = webViewFetcher != null || applicationContext != null

        // Stage 1: Direct HTTP fetch + Bilyoner DOM/JSON parse
        val htmlResult = if (httpFetcher != null) {
            httpFetcher.invoke(targetUrl)
        } else {
            fetchHtmlWithOkHttp(targetUrl)
        }

        htmlResult.onSuccess { html ->
            val parsed = deduplicateMatches(parseHtmlDocument(html))
            if (parsed.isNotEmpty()) {
                val candidate = BilyonerScrapeResult(
                    sourceUrl = targetUrl,
                    methodLabel = "HTTP HTML/DOM",
                    matches = parsed
                )
                if (parsed.size >= 5 || !canRunWebView) {
                    return@withContext Result.success(candidate)
                }
                stage1Partial = candidate
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            lastError = err
        }

        // Stage 2: Headless Android Chromium WebView XHR/DOM live scrape
        val customWebFetcher = webViewFetcher
        if (customWebFetcher != null) {
            val webResult = customWebFetcher.invoke(targetUrl)
            webResult.onSuccess { list ->
                val cleaned = deduplicateMatches(list)
                if (cleaned.isNotEmpty()) {
                    return@withContext Result.success(
                        BilyonerScrapeResult(
                            sourceUrl = targetUrl,
                            methodLabel = "WebView JS/DOM",
                            matches = cleaned
                        )
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                lastError = err
            }
        } else if (applicationContext != null) {
            val webResult = scrapeViaHeadlessWebView(applicationContext, targetUrl)
            webResult.onSuccess { list ->
                val cleaned = deduplicateMatches(list)
                if (cleaned.isNotEmpty()) {
                    return@withContext Result.success(
                        BilyonerScrapeResult(
                            sourceUrl = targetUrl,
                            methodLabel = "WebView Canlı DOM",
                            matches = cleaned
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
            ?: "bilyoner.com/canli-skor sayfasından maç verisi ayrıştırılamadı."
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
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(
                        IllegalStateException(
                            "bilyoner.com HTTP ${response.code} döndürdü (WebView canlı tarama deneniyor)."
                        )
                    )
                } else if (body.isBlank()) {
                    Result.failure(IllegalStateException("bilyoner.com boş sayfa döndürdü."))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "bilyoner.com HTTP bağlantısı kurulamadı: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeViaHeadlessWebView(
        context: Context,
        targetUrl: String
    ): Result<List<BilyonerScrapedMatch>> = HeadlessWebViewSupport.sessionMutex.withLock {
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
                            if (testParsed.size > bestCount) {
                                bestCount = testParsed.size
                                bestCandidate = candidate
                                stableRounds = 0
                            } else if (bestCount > 0) {
                                stableRounds++
                            }
                            if (testParsed.size >= 15 || (bestCount > 0 && stableRounds >= 3)) {
                                return@withTimeoutOrNull bestCandidate
                            }
                        }
                    }
                    bestCandidate
                }

                if (rawPayload.isNullOrBlank()) {
                    Result.failure(
                        IllegalStateException(
                            "bilyoner.com/canli-skor canlı DOM üzerinden maç verisi vermedi."
                        )
                    )
                } else {
                    val parsed = parseWebViewExtractorPayload(rawPayload)
                    if (parsed.isEmpty()) {
                        Result.failure(
                            IllegalStateException("bilyoner.com/canli-skor satırları ayrıştırılamadı.")
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
                        "Bilyoner WebView canlı scraping hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
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

        private val TITLE_OR_ROW_TOKEN_REGEX = Regex(
            """<h2[^>]*class=["'][^"']*event-content__title[^"']*["'][^>]*>(.*?)</h2>|<a\b([^>]*class=["'][^"']*event-content__row[^"']*["'][^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val CELL_DIV_REGEX = Regex(
            """<div[^>]*class=["'][^"']*event-content__cell--(status|home-team|away-team|score|info|isLive)[^"']*["'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HREF_ATTR_REGEX = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TITLE_ATTR_REGEX = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val MATCH_CARD_ID_REGEX = Regex("""/mac-karti/[^/]+/(\d+)""", RegexOption.IGNORE_CASE)

        private val NEXT_DATA_REGEX = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val NEXT_FLIGHT_PUSH_REGEX = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*"(.*?)"\s*\]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val INLINE_SCORE_OR_PAIR_REGEX = Regex("""(\d{1,2})\s*[-:]\s*(\d{1,2})""")
        private val SPLIT_SCORE_DIGITS_REGEX = Regex("""^\s*(\d{1,2})\s+(\d{1,2})\s*$""")
        private val SCORE_REGEX = Regex("""^\s*(\d{1,2})\s*[-:]\s*(\d{1,2})\s*$""")
        private val CLOCK_TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")
        private val MINUTE_REGEX = Regex(
            """^(\d{1,3}\s*(?:\+\s*\d{1,2})?\s*['’]?|İY|I\.Y\.|DEVRE|MS|M\.S\.|BİTTİ|CANLI|ERT|İPT|UZ|PEN)$""",
            RegexOption.IGNORE_CASE
        )
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val STRIP_SCRIPT_STYLE_REGEX = Regex(
            """<(?:script|template|style|noscript|svg)[^>]*>.*?</(?:script|template|style|noscript|svg)>|<!--.*?-->""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val TEMPLATE_SYNTAX_REGEX = Regex(
            """(\{\{|\}\}|\{%|%\<%=?|\$\{|\|\||&&|===|!==|=>|\b(?:event|model|item|match|data|state|props|row|coupon)\.[a-zA-Z_])""",
            RegexOption.IGNORE_CASE
        )
        private val TURKISH_DATE_OR_DAY_REGEX = Regex(
            """^(?:(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar|bugün|yarın|dün)[\s,./-]*)*(?:\d{1,2}\s+(?:oca|ocak|şub|şubat|mar|mart|nis|nisan|may|mayıs|haz|haziran|tem|temmuz|ağu|ağustos|eyl|eylül|eki|ekim|kas|kasım|ara|aralık)(?:\s+\d{4})?(?:[\s,]+(?:pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar))?(?:[\s,./-]+(?:[01]?\d|2[0-3]):[0-5]\d)?|\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?(?:[\s,]+(?:[01]?\d|2[0-3]):[0-5]\d)?|(?:bugün|yarın|dün|pzt|pazartesi|sal|salı|çar|çarşamba|per|perşembe|cum|cuma|cmt|cumartesi|paz|pazar)(?:[\s,]+(?:[01]?\d|2[0-3]):[0-5]\d)?)$""",
            RegexOption.IGNORE_CASE
        )

        private const val JS_PASSIVE_NETWORK_HOOK = """
            (function() {
                if (window.__bilyonerHooked) return;
                window.__bilyonerHooked = true;
                window.__bilyonerCaptured = window.__bilyonerCaptured || [];
                window.__bilyonerSeen = window.__bilyonerSeen || {};
                function pushJson(text) {
                    if (!text || text.length < 40 || text.length > 280000 || window.__bilyonerCaptured.length >= 8) return;
                    var sig = text.length + ':' + text.slice(0, 48);
                    if (window.__bilyonerSeen[sig]) return;
                    if (text.indexOf('homeTeam') !== -1 || text.indexOf('home') !== -1 ||
                        text.indexOf('AwayTeam') !== -1 || text.indexOf('score') !== -1 ||
                        text.indexOf('events') !== -1 || text.indexOf('matches') !== -1) {
                        window.__bilyonerSeen[sig] = true;
                        window.__bilyonerCaptured.push(text);
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
                    var nextEl = document.getElementById('__NEXT_DATA__');
                    var nextDataRaw = (nextEl && nextEl.textContent && nextEl.textContent.length > 50 && nextEl.textContent.length <= 280000) ? nextEl.textContent : '';

                    var capturedList = [];
                    if (window.__bilyonerCaptured && window.__bilyonerCaptured.length > 0) {
                        for (var i = 0; i < window.__bilyonerCaptured.length; i++) {
                            var c = window.__bilyonerCaptured[i];
                            if (c && c.length > 40) capturedList.push(c);
                        }
                    }

                    var bilyonerRows = [];
                    var seen = {};
                    var boxes = document.querySelectorAll('.sticky-box');
                    for (var b = 0; b < boxes.length && bilyonerRows.length < 250; b++) {
                        var box = boxes[b];
                        var titleEl = box.querySelector('.event-content__title');
                        var league = titleEl ? (titleEl.innerText || titleEl.textContent || '').trim() : '';
                        var rows = box.querySelectorAll('a.event-content__row, .event-content__row');
                        for (var r = 0; r < rows.length && bilyonerRows.length < 250; r++) {
                            var row = rows[r];
                            var st = row.querySelector('.event-content__cell--status');
                            var ht = row.querySelector('.event-content__cell--home-team');
                            var sc = row.querySelector('.event-content__cell--score');
                            var at = row.querySelector('.event-content__cell--away-team');
                            var inf = row.querySelector('.event-content__cell--info');
                            var liveIcon = row.querySelector('.ic_live_stream, .ic_bilyonertv');
                            var homeTxt = ht ? (ht.innerText || ht.textContent || '').trim() : '';
                            var awayTxt = at ? (at.innerText || at.textContent || '').trim() : '';
                            if (!homeTxt || !awayTxt) continue;
                            var href = row.getAttribute('href') || '';
                            var key = href || (homeTxt + '|' + awayTxt);
                            if (seen[key]) continue;
                            seen[key] = true;
                            bilyonerRows.push({
                                league: league,
                                href: href,
                                title: row.getAttribute('title') || '',
                                status: st ? (st.innerText || st.textContent || '').trim() : '',
                                home: homeTxt,
                                score: sc ? (sc.innerText || sc.textContent || '').trim() : '',
                                away: awayTxt,
                                info: inf ? (inf.innerText || inf.textContent || '').trim() : '',
                                hasStream: !!liveIcon
                            });
                        }
                    }

                    if (bilyonerRows.length === 0) {
                        var directRows = document.querySelectorAll('a.event-content__row, .event-content__row');
                        for (var d = 0; d < directRows.length && bilyonerRows.length < 250; d++) {
                            var dr = directRows[d];
                            var ht2 = dr.querySelector('.event-content__cell--home-team');
                            var at2 = dr.querySelector('.event-content__cell--away-team');
                            if (!ht2 || !at2) continue;
                            var st2 = dr.querySelector('.event-content__cell--status');
                            var sc2 = dr.querySelector('.event-content__cell--score');
                            var inf2 = dr.querySelector('.event-content__cell--info');
                            var liveIcon2 = dr.querySelector('.ic_live_stream, .ic_bilyonertv');
                            bilyonerRows.push({
                                league: '',
                                href: dr.getAttribute('href') || '',
                                title: dr.getAttribute('title') || '',
                                status: st2 ? (st2.innerText || st2.textContent || '').trim() : '',
                                home: (ht2.innerText || ht2.textContent || '').trim(),
                                score: sc2 ? (sc2.innerText || sc2.textContent || '').trim() : '',
                                away: (at2.innerText || at2.textContent || '').trim(),
                                info: inf2 ? (inf2.innerText || inf2.textContent || '').trim() : '',
                                hasStream: !!liveIcon2
                            });
                        }
                    }

                    var hasDomRows = bilyonerRows.length >= 10;
                    var bodyText = (!hasDomRows && document.body) ? (document.body.innerText || '').slice(0, 12000) : '';
                    return JSON.stringify({
                        kind: 'combined',
                        nextData: hasDomRows ? '' : nextDataRaw,
                        captured: hasDomRows ? [] : capturedList,
                        rows: bilyonerRows,
                        bodyText: bodyText
                    });
                } catch(e) {
                    return JSON.stringify({ kind: 'error', message: String(e) });
                }
            })();
        """

        fun parseHtmlDocument(html: String): List<BilyonerScrapedMatch> {
            if (html.isBlank()) return emptyList()

            // Strip script/template/style/comments before any DOM/text regex matching
            val sanitizedHtml = STRIP_SCRIPT_STYLE_REGEX.replace(html, "\n")

            // 1. Primary: Exact Bilyoner live scoreboard DOM markup
            val domMatches = parseBilyonerStickyBoxHtml(sanitizedHtml)
            if (domMatches.isNotEmpty()) {
                return deduplicateMatches(domMatches)
            }

            // 2. __NEXT_DATA__ or RSC embedded JSON
            val jsonMatches = mutableListOf<BilyonerScrapedMatch>()
            NEXT_DATA_REGEX.findAll(html).forEach { match ->
                val jsonText = match.groupValues[1].trim()
                if (jsonText.isNotEmpty()) {
                    jsonMatches.addAll(extractMatchesFromJsonString(jsonText))
                }
            }
            if (jsonMatches.isEmpty()) {
                NEXT_FLIGHT_PUSH_REGEX.findAll(html).forEach { match ->
                    val unescaped = match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    val jsonStart = unescaped.indexOf('{')
                    val arrStart = unescaped.indexOf('[')
                    val startIdx = listOf(jsonStart, arrStart).filter { it >= 0 }.minOrNull()
                    if (startIdx != null) {
                        jsonMatches.addAll(extractMatchesFromJsonString(unescaped.substring(startIdx)))
                    }
                }
            }
            if (jsonMatches.isNotEmpty()) {
                return deduplicateMatches(jsonMatches)
            }

            // 3. Visible plain-text fallback
            val visibleLines = cleanHtmlToText(sanitizedHtml).lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            return deduplicateMatches(parseSequentialVisibleLines(visibleLines))
        }

        private fun parseBilyonerStickyBoxHtml(html: String): List<BilyonerScrapedMatch> {
            val results = mutableListOf<BilyonerScrapedMatch>()
            var currentLeague = "Bilyoner Canlı Skor"

            TITLE_OR_ROW_TOKEN_REGEX.findAll(html).forEach { token ->
                val titleHtml = token.groups[1]?.value
                if (titleHtml != null) {
                    val cleanedLeague = cleanHtmlToText(titleHtml).trim()
                    if (cleanedLeague.isNotBlank() && !TEMPLATE_SYNTAX_REGEX.containsMatchIn(cleanedLeague)) {
                        currentLeague = cleanedLeague
                    }
                    return@forEach
                }

                val attrs = token.groups[2]?.value.orEmpty()
                val rowBody = token.groups[3]?.value.orEmpty()

                val cells = mutableMapOf<String, String>()
                CELL_DIV_REGEX.findAll(rowBody).forEach { cellMatch ->
                    val cellType = cellMatch.groupValues[1].lowercase(Locale.ROOT)
                    val cellText = cleanHtmlToText(cellMatch.groupValues[2])
                    if (cellText.isNotBlank()) {
                        cells[cellType] = cellText
                    }
                }

                var homeTeam = cells["home-team"].orEmpty().trim()
                var awayTeam = cells["away-team"].orEmpty().trim()
                val titleAttr = cleanHtmlToText(
                    TITLE_ATTR_REGEX.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                )
                val resolvedTeams = resolveFullTeamNames(homeTeam, awayTeam, titleAttr)
                homeTeam = resolvedTeams.first
                awayTeam = resolvedTeams.second
                if (!looksLikeTeamName(homeTeam) || !looksLikeTeamName(awayTeam)) return@forEach

                val href = HREF_ATTR_REGEX.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
                if (href.contains("/mac-karti/", ignoreCase = true) &&
                    !href.contains("/mac-karti/futbol/", ignoreCase = true)
                ) return@forEach
                val extId = MATCH_CARD_ID_REGEX.find(href)?.groupValues?.getOrNull(1)
                    ?: "${homeTeam}_$awayTeam"
                val hasStream = rowBody.contains("ic_live_stream", ignoreCase = true) ||
                    rowBody.contains("ic_bilyonertv", ignoreCase = true)

                val rawStatus = cells["status"].orEmpty()
                val rawScore = cells["score"].orEmpty()
                val rawInfo = cells["info"].orEmpty()

                results.add(
                    buildBilyonerMatch(
                        externalId = extId,
                        homeTeam = homeTeam,
                        awayTeam = awayTeam,
                        rawStatus = rawStatus,
                        rawScore = rawScore,
                        rawInfo = rawInfo,
                        league = currentLeague,
                        hasLiveStream = hasStream
                    )
                )
            }
            return results
        }

        fun parseWebViewExtractorPayload(rawPayload: String): List<BilyonerScrapedMatch> {
            val trimmed = rawPayload.trim()
            if (trimmed.isEmpty()) return emptyList()
            val root = runCatching { JSONObject(trimmed) }.getOrNull()
                ?: return extractMatchesFromJsonString(trimmed)

            fun parseDomRows(rows: JSONArray?): List<BilyonerScrapedMatch> {
                if (rows == null) return emptyList()
                val out = mutableListOf<BilyonerScrapedMatch>()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val (home, away) = resolveFullTeamNames(
                        cellHome = row.optString("home").trim(),
                        cellAway = row.optString("away").trim(),
                        titleAttr = cleanHtmlToText(row.optString("title"))
                    )
                    if (!looksLikeTeamName(home) || !looksLikeTeamName(away)) continue
                    val href = row.optString("href")
                    if (href.contains("/mac-karti/", ignoreCase = true) &&
                        !href.contains("/mac-karti/futbol/", ignoreCase = true)
                    ) continue
                    val extId = MATCH_CARD_ID_REGEX.find(href)?.groupValues?.getOrNull(1)
                        ?: "${home}_$away"
                    out.add(
                        buildBilyonerMatch(
                            externalId = extId,
                            homeTeam = home,
                            awayTeam = away,
                            rawStatus = row.optString("status"),
                            rawScore = row.optString("score"),
                            rawInfo = row.optString("info"),
                            league = row.optString("league").ifBlank { "Bilyoner Canlı Skor" },
                            hasLiveStream = row.optBoolean("hasStream", false)
                        )
                    )
                }
                return deduplicateMatches(out)
            }

            return when (root.optString("kind")) {
                "next_data", "captured_json" -> extractMatchesFromJsonString(root.optString("raw"))
                "bilyoner_dom" -> parseDomRows(root.optJSONArray("rows"))
                "dom_text" -> {
                    val bodyText = root.optString("bodyText")
                    if (bodyText.isBlank()) emptyList()
                    else deduplicateMatches(
                        parseSequentialVisibleLines(
                            bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        )
                    )
                }
                "combined" -> {
                    val candidates = mutableListOf<List<BilyonerScrapedMatch>>()
                    val rowsMatches = parseDomRows(root.optJSONArray("rows"))
                    if (rowsMatches.isNotEmpty()) candidates.add(rowsMatches)

                    val capturedArr = root.optJSONArray("captured")
                    if (capturedArr != null) {
                        for (i in 0 until capturedArr.length()) {
                            val cRaw = capturedArr.optString(i)
                            if (cRaw.isNotBlank()) {
                                val parsed = extractMatchesFromJsonString(cRaw)
                                if (parsed.isNotEmpty()) candidates.add(parsed)
                            }
                        }
                    }
                    val nextRaw = root.optString("nextData")
                    if (nextRaw.isNotBlank()) {
                        val parsed = extractMatchesFromJsonString(nextRaw)
                        if (parsed.isNotEmpty()) candidates.add(parsed)
                    }
                    val bodyText = root.optString("bodyText")
                    if (bodyText.isNotBlank()) {
                        val parsed = deduplicateMatches(
                            parseSequentialVisibleLines(
                                bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        )
                        if (parsed.isNotEmpty()) candidates.add(parsed)
                    }
                    candidates.maxByOrNull { it.size } ?: emptyList()
                }
                else -> extractMatchesFromJsonString(trimmed)
            }
        }

        private fun resolveFullTeamNames(
            cellHome: String,
            cellAway: String,
            titleAttr: String
        ): Pair<String, String> {
            val parts = titleAttr.split(" - ")
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

        private fun buildBilyonerMatch(
            externalId: String,
            homeTeam: String,
            awayTeam: String,
            rawStatus: String,
            rawScore: String,
            rawInfo: String,
            league: String,
            hasLiveStream: Boolean
        ): BilyonerScrapedMatch {
            val parsedScores = parseScoreString(rawScore)
            val resolvedStatus = resolveStatusFromStrings(
                rawStatus = rawStatus,
                hasScore = parsedScores.first != null && parsedScores.second != null
            )
            val formattedMinute = normalizeMinuteLabel(resolvedStatus, rawStatus)
            val normalizedDate = normalizeDateFromStatus(rawStatus)

            return BilyonerScrapedMatch(
                externalId = externalId,
                homeTeam = cleanTeamName(homeTeam),
                awayTeam = cleanTeamName(awayTeam),
                homeScore = parsedScores.first,
                awayScore = parsedScores.second,
                status = resolvedStatus,
                minuteText = formattedMinute,
                matchDate = normalizedDate,
                league = league.ifBlank { "Bilyoner Canlı Skor" },
                halfTimeText = rawInfo.trim(),
                hasLiveStream = hasLiveStream
            )
        }

        private fun parseScoreString(raw: String): Pair<Int?, Int?> {
            val clean = raw.trim()
            if (clean.isEmpty() || clean == "-" || clean == "v" || clean == "vs") {
                return null to null
            }
            INLINE_SCORE_OR_PAIR_REGEX.find(clean)?.let { m ->
                return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
            }
            SPLIT_SCORE_DIGITS_REGEX.find(clean)?.let { m ->
                return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
            }
            return null to null
        }

        private fun resolveStatusFromStrings(
            rawStatus: String,
            hasScore: Boolean
        ): MatchStatus {
            val trLocale = Locale.forLanguageTag("tr-TR")
            val s = rawStatus.uppercase(trLocale).trim()
            return when {
                s in setOf("İY", "I.Y.", "DEVRE", "DEVRE ARASI", "HT", "HALF_TIME", "HALFTIME") ->
                    MatchStatus.HALF_TIME
                s in setOf("MS", "M.S.", "BİTTİ", "BITTI", "FT", "FINISHED", "ENDED", "AET", "PEN", "MAÇ SONUCU") ->
                    MatchStatus.FINISHED
                s in setOf("ERT", "İPT", "POSTPONED", "CANCELED", "BAŞLAMADI", "BASLAMADI", "UPCOMING", "NS") ->
                    MatchStatus.UPCOMING
                CLOCK_TIME_REGEX.matches(s) && !hasScore ->
                    MatchStatus.UPCOMING
                s in setOf("CANLI", "LIVE", "OYNANIYOR", "UZ", "1H", "2H") ||
                    s.contains("'") || s.contains("’") ||
                    (s.isNotEmpty() && s.all { it.isDigit() }) ->
                    MatchStatus.LIVE
                hasScore ->
                    MatchStatus.LIVE
                else ->
                    MatchStatus.UPCOMING
            }
        }

        private fun normalizeMinuteLabel(status: MatchStatus, rawStatus: String): String {
            val clean = rawStatus.trim()
            return when (status) {
                MatchStatus.HALF_TIME -> "İY"
                MatchStatus.FINISHED -> "MS"
                MatchStatus.LIVE -> when {
                    clean.isEmpty() || clean == "-" || clean == "—" -> "CANLI"
                    clean.all { it.isDigit() } -> "${clean}'"
                    else -> clean
                }
                MatchStatus.UPCOMING -> CLOCK_TIME_REGEX.find(clean)?.value ?: clean
            }
        }

        private fun normalizeDateFromStatus(rawStatus: String): String {
            val outFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = ISTANBUL_TZ }
            val trimmed = rawStatus.trim()
            if (CLOCK_TIME_REGEX.matches(trimmed)) {
                val todayStr = SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = ISTANBUL_TZ }.format(Date())
                return "$todayStr $trimmed"
            }
            return outFmt.format(Date())
        }

        fun extractMatchesFromJsonString(jsonString: String): List<BilyonerScrapedMatch> {
            val parsedToken = runCatching { JSONTokener(jsonString).nextValue() }.getOrNull() ?: return emptyList()
            val out = mutableListOf<BilyonerScrapedMatch>()
            walkJson(parsedToken, inheritedLeague = "Bilyoner Canlı Skor", out = out, depth = 0)
            return deduplicateMatches(out)
        }

        private fun walkJson(
            node: Any?,
            inheritedLeague: String,
            out: MutableList<BilyonerScrapedMatch>,
            depth: Int
        ) {
            if (node == null || depth > 14) return
            when (node) {
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walkJson(node.opt(i), inheritedLeague, out, depth + 1)
                    }
                }
                is JSONObject -> {
                    if (node.has("sportId") && node.opt("sportId") is Number && node.optInt("sportId", 1) != 1) {
                        return
                    }
                    val sportName = node.optString("sportName").trim()
                    if (sportName.isNotBlank() &&
                        !sportName.contains("Futbol", ignoreCase = true) &&
                        !sportName.contains("Soccer", ignoreCase = true) &&
                        !sportName.contains("Football", ignoreCase = true)
                    ) {
                        return
                    }

                    val ownLeague = listOf("leagueName", "tournamentName", "league", "competition", "l")
                        .firstNotNullOfOrNull { k ->
                            when (val v = node.opt(k)) {
                                is String -> v.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                                is JSONObject -> v.optString("name").ifBlank { v.optString("title") }.trim().takeIf { it.isNotBlank() }
                                else -> null
                            }
                        } ?: inheritedLeague

                    var home = extractJsonTeam(node, listOf("homeTeam", "homeTeamName", "home", "homeName", "ht", "hn", "team1"))
                    var away = extractJsonTeam(node, listOf("awayTeam", "awayTeamName", "away", "awayName", "at", "an", "team2"))
                    if (home.isNullOrBlank() || away.isNullOrBlank()) {
                        val combinedName = listOf("matchName", "eventName")
                            .firstNotNullOfOrNull { node.optString(it).takeIf { s -> s.contains(" - ") } }
                        if (combinedName != null) {
                            val parts = combinedName.split(" - ")
                            if (parts.size == 2) {
                                home = parts[0].trim()
                                away = parts[1].trim()
                            }
                        }
                    }
                    if (!home.isNullOrBlank() && !away.isNullOrBlank() &&
                        !home.equals(away, true) && looksLikeTeamName(home) && looksLikeTeamName(away)
                    ) {
                        val hs = listOf("homeScore", "hs", "homeGoals").firstNotNullOfOrNull {
                            node.opt(it)?.toString()?.toIntOrNull()
                        }
                        val aws = listOf("awayScore", "as", "awayGoals").firstNotNullOfOrNull {
                            node.opt(it)?.toString()?.toIntOrNull()
                        }
                        val st = listOf("status", "minute", "minuteText", "clock", "state", "statusText")
                            .firstNotNullOfOrNull { node.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                            .orEmpty()
                        val extId = listOf("id", "eventId", "matchId", "sbsEventId")
                            .firstNotNullOfOrNull { node.optString(it).takeIf { s -> s.isNotBlank() && s != "null" && s != "0" } }
                            ?: "${home}_$away"
                        val scoreText = if (hs != null && aws != null) "$hs - $aws" else ""
                        out.add(
                            buildBilyonerMatch(
                                externalId = extId,
                                homeTeam = home,
                                awayTeam = away,
                                rawStatus = st,
                                rawScore = scoreText,
                                rawInfo = "",
                                league = ownLeague,
                                hasLiveStream = node.optBoolean("hasLiveStream", false)
                            )
                        )
                        return
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = node.opt(key)
                        if (child is JSONObject || child is JSONArray) {
                            walkJson(child, ownLeague, out, depth + 1)
                        }
                    }
                }
            }
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

        private fun parseSequentialVisibleLines(lines: List<String>): List<BilyonerScrapedMatch> {
            val out = mutableListOf<BilyonerScrapedMatch>()
            var currentLeague = "Bilyoner Canlı Skor"
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.contains("Ligi", true) || line.contains("Kupası", true) || line.contains("Kup.", true)) {
                    currentLeague = line
                    i++
                    continue
                }
                if ((CLOCK_TIME_REGEX.matches(line) || MINUTE_REGEX.matches(line)) && i + 3 < lines.size) {
                    val c1 = lines[i + 1]
                    val c2 = lines[i + 2]
                    val c3 = lines[i + 3]
                    val score1 = parseScoreString(c2)
                    if (looksLikeTeamName(c1) && looksLikeTeamName(c3) && (c2 == "-" || score1.first != null)) {
                        out.add(
                            buildBilyonerMatch(
                                externalId = "${c1}_$c3",
                                homeTeam = c1,
                                awayTeam = c3,
                                rawStatus = line,
                                rawScore = c2,
                                rawInfo = "",
                                league = currentLeague,
                                hasLiveStream = false
                            )
                        )
                        i += 4
                        continue
                    }
                }
                i++
            }
            return out
        }

        private fun looksLikeTeamName(text: String): Boolean {
            val clean = text.trim()
            if (clean.length !in 2..48) return false
            if (TEMPLATE_SYNTAX_REGEX.containsMatchIn(clean)) return false
            if (TURKISH_DATE_OR_DAY_REGEX.matches(clean.lowercase(Locale.forLanguageTag("tr-TR")).replace("•", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim())) return false
            if (clean.any { it in setOf('{', '}', '<', '>', ';', '[', ']', '=') }) return false
            val lower = clean.lowercase(Locale.forLanguageTag("tr-TR"))
            if (lower in setOf(
                    "toplam oran", "misli", "kupon bedeli", "maksimum kazanç",
                    "kolon sayısı", "hemen oyna", "kuponum", "sistem", "banko",
                    "canlı", "tümü", "biten", "başlamadı", "karşılaşma", "durum"
                )
            ) return false
            if (CLOCK_TIME_REGEX.matches(clean) || SCORE_REGEX.matches(clean)) return false
            return clean.any { it.isLetter() }
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

        private fun deduplicateMatches(list: List<BilyonerScrapedMatch>): List<BilyonerScrapedMatch> {
            val trLocale = Locale.forLanguageTag("tr-TR")
            return list.distinctBy {
                "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
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
