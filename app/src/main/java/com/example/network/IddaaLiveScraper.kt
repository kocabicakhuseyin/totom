package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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

const val IDDAA_LIVE_FOOTBALL_URL = "https://www.iddaa.com/canli-skor/futbol"

data class IddaaScrapedMatch(
    val externalId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val status: MatchStatus = MatchStatus.UPCOMING,
    val minuteText: String = "",
    val matchDate: String = "",
    val league: String = "İddaa Canlı Bülten",
    val homeOdd: Double = 2.15,
    val drawOdd: Double = 3.25,
    val awayOdd: Double = 3.30
) {
    fun toMatchLiveScore(fallbackNumber: Int): MatchLiveScore {
        val number = externalId.toIntOrNull()?.takeIf { it > 0 }
            ?: (abs("${homeTeam}|${awayTeam}".hashCode()) % 900_000 + 100_000 + fallbackNumber)
        return MatchLiveScore(
            matchNumber = number,
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
        val dc1X = if (homeOdd > 1.0 && drawOdd > 1.0) ((1.0 / ((1.0 / homeOdd) + (1.0 / drawOdd))).coerceAtLeast(1.01)) else 1.30
        val dc12 = if (homeOdd > 1.0 && awayOdd > 1.0) ((1.0 / ((1.0 / homeOdd) + (1.0 / awayOdd))).coerceAtLeast(1.01)) else 1.25
        val dcX2 = if (drawOdd > 1.0 && awayOdd > 1.0) ((1.0 / ((1.0 / drawOdd) + (1.0 / awayOdd))).coerceAtLeast(1.01)) else 1.60
        return BulletinMatchDto(
            matchNumber = matchNumber,
            homeTeam = homeTeam.uppercase(trLocale).trim(),
            awayTeam = awayTeam.uppercase(trLocale).trim(),
            matchDate = matchDate,
            league = league.ifBlank { "İddaa Canlı Bülten" },
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

data class IddaaScrapeResult(
    val sourceUrl: String = IDDAA_LIVE_FOOTBALL_URL,
    val methodLabel: String,
    val matches: List<IddaaScrapedMatch>,
    val scrapedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Multi-stage live scraper for https://www.iddaa.com/canli-skor/futbol:
 * 1) Fast HTTP fetch with browser headers -> parses SSR __NEXT_DATA__ JSON, RSC chunks, and HTML fixture blocks.
 * 2) Automatic headless Android WebView JS/DOM fallback when the target page uses client-side hydration
 *    or CDN JS checks that return an empty SPA shell to plain HTTP clients.
 */
class IddaaLiveScraper(
    private val applicationContext: Context? = null,
    private val httpFetcher: (suspend (String) -> Result<String>)? = null,
    private val webViewFetcher: (suspend (String) -> Result<List<IddaaScrapedMatch>>)? = null
) {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun scrapeLiveFootball(
        targetUrl: String = IDDAA_LIVE_FOOTBALL_URL
    ): Result<IddaaScrapeResult> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        // Stage 1: Direct HTTP HTML + __NEXT_DATA__ / JSON / DOM scrape
        val htmlResult = if (httpFetcher != null) {
            httpFetcher.invoke(targetUrl)
        } else {
            fetchHtmlWithOkHttp(targetUrl)
        }

        htmlResult.onSuccess { html ->
            val parsed = parseHtmlDocument(html)
            if (parsed.isNotEmpty()) {
                return@withContext Result.success(
                    IddaaScrapeResult(
                        sourceUrl = targetUrl,
                        methodLabel = "HTTP HTML/__NEXT_DATA__",
                        matches = deduplicateMatches(parsed)
                    )
                )
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            lastError = err
        }

        // Stage 2: Headless Android Chromium WebView JS/DOM live scrape
        val customWebFetcher = webViewFetcher
        if (customWebFetcher != null) {
            val webResult = customWebFetcher.invoke(targetUrl)
            webResult.onSuccess { list ->
                val cleaned = deduplicateMatches(list)
                if (cleaned.isNotEmpty()) {
                    return@withContext Result.success(
                        IddaaScrapeResult(
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
                        IddaaScrapeResult(
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

        val reason = lastError?.message?.takeIf { it.isNotBlank() }
            ?: "iddaa.com/canli-skor/futbol sayfasından aktif maç verisi ayrıştırılamadı."
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
                .header("Referer", "https://www.iddaa.com/")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(
                        IllegalStateException(
                            "iddaa.com HTTP ${response.code} döndürdü (WebView fallback deneniyor)."
                        )
                    )
                } else if (body.isBlank()) {
                    Result.failure(IllegalStateException("iddaa.com boş sayfa döndürdü."))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "iddaa.com HTTP bağlantısı kurulamadı: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeViaHeadlessWebView(
        context: Context,
        targetUrl: String
    ): Result<List<IddaaScrapedMatch>> = HeadlessWebViewSupport.sessionMutex.withLock {
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

                    // Wait for DOM & Next.js/React hydration to render matches (poll up to ~12s)
                    var bestPayload: String? = null
                    repeat(15) { attempt ->
                        delay(800L)
                        if (rendererGone) {
                            return@withTimeoutOrNull bestPayload
                        }
                        if (pageErrorMsg != null && attempt >= 3) {
                            return@withTimeoutOrNull null
                        }
                        if (!pageLoaded && attempt < 3) return@repeat
                        val extracted = evaluateJsSuspend(wv, JS_DOM_AND_NEXTDATA_EXTRACTOR) { rendererGone }
                        if (!extracted.isNullOrBlank()) {
                            val candidate = decodeJsEvaluatedString(extracted)
                            val testParsed = parseWebViewExtractorPayload(candidate)
                            if (testParsed.isNotEmpty()) {
                                bestPayload = candidate
                                return@withTimeoutOrNull candidate
                            }
                        }
                    }
                    bestPayload
                }

                if (rawPayload.isNullOrBlank()) {
                    Result.failure(
                        IllegalStateException(
                            "iddaa.com/canli-skor/futbol canlı DOM üzerinden maç verisi vermedi."
                        )
                    )
                } else {
                    val parsed = parseWebViewExtractorPayload(rawPayload)
                    if (parsed.isEmpty()) {
                        Result.failure(
                            IllegalStateException("iddaa.com DOM içeriğinden maç satırı çözümlenemedi.")
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
                        "WebView canlı scraping hatası: ${e.localizedMessage ?: e.javaClass.simpleName}"
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
        private val LD_JSON_REGEX = Regex(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val NEXT_FLIGHT_PUSH_REGEX = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*"(.*?)"\s*\]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_ROW_BLOCK_REGEX = Regex(
            """<(?:tr|li|article|div)\b[^>]*class=["'][^"']*(?:match|fixture|event|scoreboard|row|game|card)[^"']*["'][^>]*>(.*?)</(?:tr|li|article|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val STRIP_SCRIPT_STYLE_REGEX = Regex(
            """<(?:script|style|noscript|svg)[^>]*>.*?</(?:script|style|noscript|svg)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val SCORE_REGEX = Regex("""^\s*(\d{1,2})\s*[-:]\s*(\d{1,2})\s*$""")
        private val INLINE_SCORE_REGEX = Regex("""\b(\d{1,2})\s*[-:]\s*(\d{1,2})\b""")
        private val MINUTE_REGEX = Regex("""^(\d{1,3}\s*(?:\+\s*\d{1,2})?\s*['’]|İY|I\.Y\.|DEVRE|MS|M\.S\.|BİTTİ|CANLI|ERT|İPT)$""", RegexOption.IGNORE_CASE)
        private val CLOCK_TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")

        private const val JS_PASSIVE_NETWORK_HOOK = """
            (function() {
                if (window.__totomHooked) return;
                window.__totomHooked = true;
                window.__totomCaptured = window.__totomCaptured || [];
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        return origFetch.apply(this, arguments).then(function(res) {
                            try {
                                var ct = res.headers && res.headers.get ? (res.headers.get('content-type') || '') : '';
                                if (ct.indexOf('json') !== -1) {
                                    res.clone().text().then(function(t) {
                                        if (t && t.length > 20 && t.length <= 280000 && window.__totomCaptured.length < 8) {
                                            window.__totomCaptured.push(t);
                                        }
                                    }).catch(function(){});
                                }
                            } catch(e) {}
                            return res;
                        });
                    };
                }
            })();
        """

        private const val JS_DOM_AND_NEXTDATA_EXTRACTOR = """
            (function() {
                try {
                    var nextEl = document.getElementById('__NEXT_DATA__');
                    if (nextEl && nextEl.textContent && nextEl.textContent.length > 50 && nextEl.textContent.length <= 280000) {
                        var nd = nextEl.textContent;
                        if (nd.indexOf('home') !== -1 || nd.indexOf('Home') !== -1 || nd.indexOf('skor') !== -1 || nd.indexOf('score') !== -1) {
                            return JSON.stringify({ kind: 'next_data', raw: nd });
                        }
                    }
                    if (window.__totomCaptured && window.__totomCaptured.length > 0) {
                        for (var i = 0; i < window.__totomCaptured.length; i++) {
                            var c = window.__totomCaptured[i];
                            if (c.indexOf('home') !== -1 || c.indexOf('away') !== -1 || c.indexOf('teams') !== -1) {
                                return JSON.stringify({ kind: 'captured_json', raw: c });
                            }
                        }
                    }
                    var items = [];
                    var seen = {};
                    var selectors = '[class*="match"],[class*="Match"],[class*="event"],[class*="Event"],[class*="fixture"],[class*="Fixture"],[class*="row"],[data-testid*="match"],tr';
                    var nodes = document.querySelectorAll(selectors);
                    for (var n = 0; n < nodes.length && items.length < 80; n++) {
                        var el = nodes[n];
                        var txt = (el.innerText || '').trim();
                        if (!txt || txt.length < 6 || txt.length > 240) continue;
                        var lines = txt.split(/\n+/).map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });
                        if (lines.length < 3 || lines.length > 10) continue;
                        var key = lines.join('|');
                        if (seen[key]) continue;
                        seen[key] = true;
                        items.push({ lines: lines });
                    }
                    var bodyText = document.body ? (document.body.innerText || '') : '';
                    return JSON.stringify({ kind: 'dom', items: items, bodyText: bodyText.slice(0, 12000) });
                } catch(e) {
                    return JSON.stringify({ kind: 'error', message: String(e) });
                }
            })();
        """

        fun parseHtmlDocument(html: String): List<IddaaScrapedMatch> {
            if (html.isBlank()) return emptyList()
            val results = mutableListOf<IddaaScrapedMatch>()

            // 1. Check __NEXT_DATA__ JSON
            NEXT_DATA_REGEX.findAll(html).forEach { match ->
                val jsonText = match.groupValues[1].trim()
                if (jsonText.isNotEmpty()) {
                    results.addAll(extractMatchesFromJsonString(jsonText))
                }
            }

            // 2. Check JSON-LD SportsEvent objects
            LD_JSON_REGEX.findAll(html).forEach { match ->
                val jsonText = match.groupValues[1].trim()
                if (jsonText.isNotEmpty()) {
                    results.addAll(extractMatchesFromJsonString(jsonText))
                }
            }

            // 3. Check Next.js App Router RSC chunks (`self.__next_f.push`)
            if (results.isEmpty()) {
                NEXT_FLIGHT_PUSH_REGEX.findAll(html).forEach { match ->
                    val unescaped = match.groupValues[1]
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    val jsonStart = unescaped.indexOf('{')
                    val arrStart = unescaped.indexOf('[')
                    val startIdx = listOf(jsonStart, arrStart).filter { it >= 0 }.minOrNull()
                    if (startIdx != null) {
                        results.addAll(extractMatchesFromJsonString(unescaped.substring(startIdx)))
                    }
                }
            }

            // 4. Fallback to HTML fixture block + visible text scraping
            if (results.isEmpty()) {
                HTML_ROW_BLOCK_REGEX.findAll(html).forEach { blockMatch ->
                    val lines = cleanHtmlFragmentToLines(blockMatch.value)
                    parseLinesIntoMatch(lines, currentLeague = "İddaa Canlı Bülten")?.let {
                        results.add(it)
                    }
                }
            }
            if (results.isEmpty()) {
                val stripped = STRIP_SCRIPT_STYLE_REGEX.replace(html, "\n")
                val visibleLines = cleanHtmlFragmentToLines(stripped)
                results.addAll(parseSequentialVisibleLines(visibleLines))
            }

            return deduplicateMatches(results)
        }

        fun parseWebViewExtractorPayload(rawPayload: String): List<IddaaScrapedMatch> {
            val trimmed = rawPayload.trim()
            if (trimmed.isEmpty()) return emptyList()
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return extractMatchesFromJsonString(trimmed)
            val kind = root.optString("kind")
            return when (kind) {
                "next_data", "captured_json" -> extractMatchesFromJsonString(root.optString("raw"))
                "dom" -> {
                    val out = mutableListOf<IddaaScrapedMatch>()
                    val itemsArr = root.optJSONArray("items")
                    var currentLeague = "İddaa Canlı Futbol"
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
                            if (lines.size == 1 && looksLikeLeagueHeader(lines[0])) {
                                currentLeague = lines[0]
                            } else {
                                parseLinesIntoMatch(lines, currentLeague)?.let { out.add(it) }
                            }
                        }
                    }
                    if (out.isEmpty()) {
                        val bodyText = root.optString("bodyText")
                        if (bodyText.isNotBlank()) {
                            val lines = bodyText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            out.addAll(parseSequentialVisibleLines(lines))
                        }
                    }
                    deduplicateMatches(out)
                }
                else -> extractMatchesFromJsonString(trimmed)
            }
        }

        fun extractMatchesFromJsonString(jsonString: String): List<IddaaScrapedMatch> {
            val parsedToken = runCatching { JSONTokener(jsonString).nextValue() }.getOrNull() ?: return emptyList()
            val out = mutableListOf<IddaaScrapedMatch>()
            walkJsonForMatches(parsedToken, inheritedLeague = "İddaa Canlı Bülten", out = out, depth = 0)
            return deduplicateMatches(out)
        }

        private fun walkJsonForMatches(
            node: Any?,
            inheritedLeague: String,
            out: MutableList<IddaaScrapedMatch>,
            depth: Int
        ) {
            if (node == null || depth > 14) return
            when (node) {
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walkJsonForMatches(node.opt(i), inheritedLeague, out, depth + 1)
                    }
                }
                is JSONObject -> {
                    val ownLeague = extractLeagueFromObject(node)?.takeIf { it.isNotBlank() } ?: inheritedLeague
                    tryParseJsonMatchObject(node, ownLeague)?.let { match ->
                        out.add(match)
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = node.opt(key)
                        if (child is JSONObject || child is JSONArray) {
                            walkJsonForMatches(child, ownLeague, out, depth + 1)
                        }
                    }
                }
            }
        }

        private fun tryParseJsonMatchObject(obj: JSONObject, inheritedLeague: String): IddaaScrapedMatch? {
            // Pattern A: explicit home/away fields (string or nested object { name, shortName, score })
            var homeName = extractTeamName(obj, listOf("homeTeam", "home", "homeName", "ht", "team1", "hn"))
            var awayName = extractTeamName(obj, listOf("awayTeam", "away", "awayName", "at", "team2", "an"))

            // Pattern B: competitors / participants / teams array of 2 entries
            if (homeName == null || awayName == null) {
                val pair = extractTeamPairFromArray(obj)
                if (pair != null) {
                    homeName = pair.first
                    awayName = pair.second
                }
            }

            if (homeName.isNullOrBlank() || awayName.isNullOrBlank()) return null
            if (homeName.equals(awayName, ignoreCase = true)) return null
            if (!looksLikeTeamName(homeName) || !looksLikeTeamName(awayName)) return null

            val scoresPair = extractScoresFromObject(obj)
            val rawStatus = listOf(
                obj.optString("status"),
                obj.optString("state"),
                obj.optString("statusText"),
                obj.optString("statusCode"),
                obj.optString("period"),
                obj.optString("liveStatus"),
                obj.optString("st")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val rawMinute = listOf(
                obj.optString("minute"),
                obj.optString("minuteText"),
                obj.optString("min"),
                obj.optString("elapsed"),
                obj.optString("timeText"),
                obj.optString("clock")
            ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

            val isLiveFlag = obj.optBoolean("isLive", false) || obj.optBoolean("live", false)
            val resolvedStatus = resolveStatusFromStrings(
                rawStatus = rawStatus,
                rawMinute = rawMinute,
                isLiveFlag = isLiveFlag,
                hasScore = scoresPair.first != null && scoresPair.second != null
            )
            val formattedMinute = normalizeMinuteLabel(resolvedStatus, rawMinute, rawStatus)

            val rawDate = listOf(
                obj.optString("matchDate"),
                obj.optString("date"),
                obj.optString("startDate"),
                obj.optString("kickoff"),
                obj.optString("startTime"),
                obj.optString("d")
            ).firstOrNull { it.isNotBlank() && it != "null" }
                ?: obj.optLong("timestamp", -1L).takeIf { it > 0L }?.toString()
                ?: obj.optLong("startTimeStamp", -1L).takeIf { it > 0L }?.toString()
                ?: ""

            val league = extractLeagueFromObject(obj) ?: inheritedLeague
            val extId = listOf(
                obj.optString("id"),
                obj.optString("eventId"),
                obj.optString("matchId"),
                obj.optString("fixtureId"),
                obj.optString("bulletinId"),
                obj.optString("code")
            ).firstOrNull { it.isNotBlank() && it != "null" && it != "0" }
                ?: "${homeName}_$awayName"

            val (hOdd, dOdd, aOdd) = extractOddsFromObject(obj)

            return IddaaScrapedMatch(
                externalId = extId,
                homeTeam = cleanTeamName(homeName),
                awayTeam = cleanTeamName(awayName),
                homeScore = scoresPair.first,
                awayScore = scoresPair.second,
                status = resolvedStatus,
                minuteText = formattedMinute,
                matchDate = normalizeDateString(rawDate),
                league = league,
                homeOdd = hOdd,
                drawOdd = dOdd,
                awayOdd = aOdd
            )
        }

        private fun extractTeamName(obj: JSONObject, keys: List<String>): String? {
            for (k in keys) {
                val v = obj.opt(k) ?: continue
                when (v) {
                    is String -> if (v.isNotBlank() && !v.equals("null", true)) return v.trim()
                    is JSONObject -> {
                        val n = v.optString("name").ifBlank {
                            v.optString("displayName").ifBlank {
                                v.optString("shortName").ifBlank { v.optString("title") }
                            }
                        }
                        if (n.isNotBlank()) return n.trim()
                    }
                }
            }
            return null
        }

        private fun extractTeamPairFromArray(obj: JSONObject): Pair<String, String>? {
            val arrayKeys = listOf("competitors", "participants", "teams")
            for (k in arrayKeys) {
                val arr = obj.optJSONArray(k) ?: continue
                if (arr.length() >= 2) {
                    val o1 = arr.optJSONObject(0)
                    val o2 = arr.optJSONObject(1)
                    if (o1 != null && o2 != null) {
                        val side1 = o1.optString("homeAway").ifBlank { o1.optString("side") }
                        val n1 = o1.optString("name").ifBlank { o1.optString("displayName").ifBlank { o1.optString("shortName") } }
                        val n2 = o2.optString("name").ifBlank { o2.optString("displayName").ifBlank { o2.optString("shortName") } }
                        if (n1.isNotBlank() && n2.isNotBlank()) {
                            return if (side1.equals("away", ignoreCase = true)) n2 to n1 else n1 to n2
                        }
                    }
                }
            }
            return null
        }

        private fun extractScoresFromObject(obj: JSONObject): Pair<Int?, Int?> {
            val hsDirect = listOf("homeScore", "hs", "scoreHome", "homeGoals").firstNotNullOfOrNull { key ->
                obj.opt(key)?.toString()?.toIntOrNull()
            }
            val asDirect = listOf("awayScore", "as", "scoreAway", "awayGoals").firstNotNullOfOrNull { key ->
                obj.opt(key)?.toString()?.toIntOrNull()
            }
            if (hsDirect != null && asDirect != null) return hsDirect to asDirect

            val homeObj = obj.optJSONObject("home") ?: obj.optJSONObject("homeTeam")
            val awayObj = obj.optJSONObject("away") ?: obj.optJSONObject("awayTeam")
            val hsNested = homeObj?.let { it.opt("score")?.toString()?.toIntOrNull() ?: it.opt("goals")?.toString()?.toIntOrNull() }
            val asNested = awayObj?.let { it.opt("score")?.toString()?.toIntOrNull() ?: it.opt("goals")?.toString()?.toIntOrNull() }
            if (hsNested != null && asNested != null) return hsNested to asNested

            val scoreObj = obj.optJSONObject("score") ?: obj.optJSONObject("scores") ?: obj.optJSONObject("result")
            if (scoreObj != null) {
                val ft = scoreObj.optJSONObject("fullTime") ?: scoreObj.optJSONObject("current") ?: scoreObj
                val h = listOf("home", "h", "ht", "homeScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                val a = listOf("away", "a", "at", "awayScore").firstNotNullOfOrNull { ft.opt(it)?.toString()?.toIntOrNull() }
                if (h != null && a != null) return h to a
            }

            val scoreStr = listOf("score", "result", "scoreDisplay", "sc").firstNotNullOfOrNull { key ->
                obj.optString(key).takeIf { it.isNotBlank() }
            }
            if (scoreStr != null) {
                SCORE_REGEX.find(scoreStr)?.let { m ->
                    return m.groupValues[1].toIntOrNull() to m.groupValues[2].toIntOrNull()
                }
            }
            return null to null
        }

        private fun extractLeagueFromObject(obj: JSONObject): String? {
            val direct = listOf("leagueName", "tournamentName", "competitionName", "league", "tournament", "competition", "ln")
                .firstNotNullOfOrNull { k ->
                    when (val v = obj.opt(k)) {
                        is String -> v.trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                        is JSONObject -> v.optString("name").ifBlank { v.optString("title") }.trim().takeIf { it.isNotBlank() }
                        else -> null
                    }
                }
            return direct
        }

        private fun extractOddsFromObject(obj: JSONObject): Triple<Double, Double, Double> {
            val oddsObj = obj.optJSONObject("odds") ?: obj.optJSONObject("ms") ?: obj
            val h = listOf("1", "home", "ms1", "homeOdd").firstNotNullOfOrNull {
                oddsObj.opt(it)?.toString()?.replace(',', '.')?.toDoubleOrNull()
            } ?: 2.15
            val d = listOf("X", "x", "0", "draw", "msx", "drawOdd").firstNotNullOfOrNull {
                oddsObj.opt(it)?.toString()?.replace(',', '.')?.toDoubleOrNull()
            } ?: 3.25
            val a = listOf("2", "away", "ms2", "awayOdd").firstNotNullOfOrNull {
                oddsObj.opt(it)?.toString()?.replace(',', '.')?.toDoubleOrNull()
            } ?: 3.30
            return Triple(h, d, a)
        }

        private fun resolveStatusFromStrings(
            rawStatus: String,
            rawMinute: String,
            isLiveFlag: Boolean,
            hasScore: Boolean
        ): MatchStatus {
            val s = rawStatus.uppercase(Locale.forLanguageTag("tr-TR")).trim()
            val m = rawMinute.uppercase(Locale.forLanguageTag("tr-TR")).trim()
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

        private fun parseLinesIntoMatch(lines: List<String>, currentLeague: String): IddaaScrapedMatch? {
            if (lines.size < 3) return null
            var homeTeam: String? = null
            var awayTeam: String? = null
            var homeScore: Int? = null
            var awayScore: Int? = null
            var status = MatchStatus.UPCOMING
            var minute = ""
            var timeStr = ""

            for (line in lines) {
                if (CLOCK_TIME_REGEX.matches(line)) {
                    timeStr = line
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
                    status = resolveStatusFromStrings(minute, minute, isLiveFlag = true, hasScore = homeScore != null)
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
            return IddaaScrapedMatch(
                externalId = "${h}_$a",
                homeTeam = cleanTeamName(h),
                awayTeam = cleanTeamName(a),
                homeScore = homeScore,
                awayScore = awayScore,
                status = finalStatus,
                minuteText = normalizeMinuteLabel(finalStatus, minute.ifBlank { timeStr }, timeStr),
                matchDate = normalizeDateString(timeStr),
                league = currentLeague
            )
        }

        private fun parseSequentialVisibleLines(lines: List<String>): List<IddaaScrapedMatch> {
            val out = mutableListOf<IddaaScrapedMatch>()
            var currentLeague = "İddaa Canlı Futbol"
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (looksLikeLeagueHeader(line)) {
                    currentLeague = line
                    i++
                    continue
                }
                // Try sliding window of 3..5 lines starting at current index
                val window = lines.subList(i, minOf(i + 5, lines.size))
                val parsed = parseLinesIntoMatch(window, currentLeague)
                if (parsed != null) {
                    out.add(parsed)
                    i += 3
                } else {
                    i++
                }
            }
            return out
        }

        private fun looksLikeLeagueHeader(line: String): Boolean {
            val lower = line.lowercase(Locale.forLanguageTag("tr-TR"))
            return lower.contains("süper lig") ||
                lower.contains("1. lig") ||
                lower.contains("premier") ||
                lower.contains("la liga") ||
                lower.contains("bundesliga") ||
                lower.contains("serie a") ||
                lower.contains("ligue 1") ||
                lower.contains("şampiyonlar") ||
                lower.contains("avrupa ligi") ||
                lower.contains("uluslar ligi")
        }

        private fun looksLikeUiNoise(line: String): Boolean {
            val lower = line.lowercase(Locale.forLanguageTag("tr-TR"))
            return lower in setOf(
                "canlı", "tümü", "biten", "başlamadı", "futbol", "basketbol",
                "iddaa", "spor toto", "maçlar", "giriş yap", "kayıt ol",
                "ms", "iy", "i.y.", "detay", "istatistik", "oranlar", "v", "vs"
            )
        }

        private fun looksLikeTeamName(text: String): Boolean {
            val clean = text.trim()
            if (clean.length !in 2..48) return false
            if (clean.all { it.isDigit() || it in setOf('-', ':', '.', '/', '\'', ' ') }) return false
            if (CLOCK_TIME_REGEX.matches(clean) || SCORE_REGEX.matches(clean)) return false
            return clean.any { it.isLetter() }
        }

        private fun cleanTeamName(raw: String): String {
            return raw.trim().replace(Regex("\\s+"), " ")
        }

        private fun deduplicateMatches(list: List<IddaaScrapedMatch>): List<IddaaScrapedMatch> {
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
