package com.example.repository

import android.content.Context
import com.example.BuildConfig
import com.example.model.CouponPoolEvaluator
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.network.ApiFootballLiveFixture
import com.example.network.ApiFootballLiveResponse
import com.example.network.ApiFootballLiveService
import com.example.network.BILYONER_LIVE_SCORE_URL
import com.example.network.BILYONER_SPORTOTO_RESULTS_URL
import com.example.network.BILYONER_SPORTOTO_URL
import com.example.network.BilyonerLiveScraper
import com.example.network.BilyonerScrapedMatch
import com.example.network.BilyonerSporTotoScraper
import com.example.network.EspnEvent
import com.example.network.EspnSoccerService
import com.example.network.IDDAA_LIVE_FOOTBALL_URL
import com.example.network.IddaaLiveScraper
import com.example.network.IddaaScrapedMatch
import com.example.network.NESINE_SPORTOTO_RESULTS_URL
import com.example.network.NESINE_SPORTOTO_URL
import com.example.network.NetworkModule
import com.example.network.NesineSporTotoScraper
import com.example.network.ScoreCardsLiveService
import com.example.network.SporTotoApiService
import com.example.network.resolveStatus
import com.example.network.toMatchLiveScore
import com.example.network.model.BulletinMatchDto
import com.example.network.model.LiveBettingOddsDto
import com.example.network.model.MatchProbabilitiesDto
import com.example.network.model.NO_NEW_BULLETIN_ERROR
import com.example.network.model.PastBulletinMatchResult
import com.example.network.model.PreviousWeekBulletinReport
import com.example.network.model.WeeklyBulletinResponse
import com.example.network.model.isPlayable
import com.example.network.model.toDomainTotoMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class LiveScoreSource(
    val displayName: String,
    val badgeLabel: String
) {
    ESPN("ESPN (Ücretsiz • Anahtarsız)", "ESPN"),
    NESINE_RESULTS("Nesine.com (Anlık Sonuçlar)", "Nesine"),
    BILYONER_LIVE("Bilyoner.com (Canlı Skor)", "Bilyoner"),
    IDDAA_SCRAPE("İddaa.com (Canlı Scrape)", "İddaa"),
    API_FOOTBALL("API-FOOTBALL (Free)", "API-Football")
}

class NoNewBulletinException(
    message: String = NO_NEW_BULLETIN_ERROR
) : IllegalStateException(message)

/**
 * Repository interface for managing weekly Spor Toto bulletin, matches, dates, live odds, and real-time scores.
 */
interface SporTotoBulletinRepository {
    val cachedBulletin: StateFlow<WeeklyBulletinResponse?>
    val liveScores: StateFlow<List<MatchLiveScore>>
    val liveScoreProvider: StateFlow<String>
    val activeLiveScoreSource: StateFlow<LiveScoreSource>
        get() = DEFAULT_SOURCE

    fun setLiveScoreSource(source: LiveScoreSource) {}
    fun requiresApiKeyForActiveSource(): Boolean =
        activeLiveScoreSource.value == LiveScoreSource.API_FOOTBALL

    /** Free API-Football settings. Implementations used by tests may omit these. */
    fun hasApiFootballKey(): Boolean = false
    fun configureApiFootballKey(key: String) {}
    fun freeScoreRequestsRemaining(): Int? = null

    /**
     * Fetches current weekly bulletin from external API (or returns cached if available).
     */
    suspend fun getWeeklyBulletin(forceRefresh: Boolean = false): Result<WeeklyBulletinResponse>

    /**
     * Fetches a specific weekly bulletin by week number.
     */
    suspend fun getBulletinByWeek(weekNumber: Int): Result<WeeklyBulletinResponse>

    /**
     * Refreshes real-time live betting odds for the current 15 matches.
     */
    suspend fun refreshLiveOdds(): Result<List<BulletinMatchDto>>

    /**
     * Polls the latest real-time match results and scores for the 15 matches.
     */
    suspend fun pollLiveScores(): Result<List<MatchLiveScore>>

    /**
     * Retrieves the 15 matches mapped to the domain TotoMatch model.
     */
    suspend fun getDomainMatches(forceRefresh: Boolean = false): Result<List<TotoMatch>>

    /**
     * Synchronizes the 15-match Spor Toto bulletin with Turkish League & European fixtures
     * from the active provider and immediately updates aligned live/finished scores.
     */
    suspend fun syncTurkishLeagueBulletinFromApi(): Result<WeeklyBulletinResponse> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes live and upcoming football fixtures directly from https://www.iddaa.com/canli-skor/futbol
     * and updates both the 15-match bulletin and live scoreboard.
     */
    suspend fun scrapeIddaaLiveBulletin(): Result<WeeklyBulletinResponse> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes the official 15-match Spor Toto program directly from https://www.nesine.com/sportoto.
     */
    suspend fun scrapeNesineBulletin(
        forceRefresh: Boolean = true
    ): Result<WeeklyBulletinResponse> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes instant Spor Toto match results directly from https://www.nesine.com/sportoto/mac-sonuclari
     * and updates the live scoreboard (and optionally synchronizes the 15-match Spor Toto bulletin).
     */
    suspend fun scrapeNesineResultsAndBulletin(
        updateBulletin: Boolean = true
    ): Result<WeeklyBulletinResponse> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes live/upcoming/finished football matches from https://www.bilyoner.com/canli-skor
     * and updates the live scoreboard (aligning overlapping Spor Toto bulletin fixtures).
     */
    suspend fun scrapeBilyonerLiveScores(
        forceRefresh: Boolean = true
    ): Result<List<MatchLiveScore>> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes football fixtures from https://www.bilyoner.com/canli-skor and assembles
     * a playable 15-match bulletin + live scoreboard.
     */
    suspend fun scrapeBilyonerLiveBulletin(): Result<WeeklyBulletinResponse> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    /**
     * Scrapes the official 15-match Spor Toto bulletin directly from https://www.bilyoner.com/spor-toto.
     */
    suspend fun scrapeBilyonerSporTotoBulletin(): Result<WeeklyBulletinResponse> =
        scrapeBilyonerLiveBulletin()

    val previousWeekReport: StateFlow<PreviousWeekBulletinReport?>
        get() = EMPTY_PREVIOUS_WEEK

    /**
     * Fetches verified finished fixtures and final scores for the previous week's bulletin window.
     */
    suspend fun fetchPreviousWeekBulletinAndResults(
        forceRefresh: Boolean = false
    ): Result<PreviousWeekBulletinReport> =
        Result.failure(IllegalStateException("Desteklenmiyor"))

    suspend fun fetchPreviousWeekFromEspn(
        forceRefresh: Boolean = true
    ): Result<PreviousWeekBulletinReport> =
        fetchPreviousWeekBulletinAndResults(forceRefresh)

    companion object {
        val EMPTY_PREVIOUS_WEEK: StateFlow<PreviousWeekBulletinReport?> =
            MutableStateFlow<PreviousWeekBulletinReport?>(null).asStateFlow()
        val DEFAULT_SOURCE: StateFlow<LiveScoreSource> =
            MutableStateFlow(LiveScoreSource.API_FOOTBALL).asStateFlow()
    }
}

/**
 * Concrete implementation of SporTotoBulletinRepository utilizing Retrofit.
 */
class SporTotoBulletinRepositoryImpl(
    private val apiService: SporTotoApiService = NetworkModule.apiService,
    private val liveScoreService: ApiFootballLiveService = NetworkModule.liveScoreService,
    private val liveScoreApiKey: String = BuildConfig.API_FOOTBALL_KEY,
    // Retained as constructor options for compatibility with older callers.
    private val scoreCardsService: ScoreCardsLiveService = NetworkModule.scoreCardsService,
    private val trackedLeagueIds: List<Int> = listOf(203, 204, 39, 140, 135, 78, 61, 5),
    applicationContext: Context? = null,
    private val preferTurkishLeagueBulletin: Boolean = false,
    private val espnService: EspnSoccerService = NetworkModule.espnService,
    private val espnLeagueSlugs: List<String> = DEFAULT_ESPN_LEAGUES,
    initialSource: LiveScoreSource = LiveScoreSource.API_FOOTBALL,
    private val iddaaScraper: IddaaLiveScraper = IddaaLiveScraper(applicationContext),
    private val nesineScraper: NesineSporTotoScraper = NesineSporTotoScraper(applicationContext),
    private val enforceNesineOnly: Boolean = false,
    private val bilyonerScraper: BilyonerLiveScraper = BilyonerLiveScraper(applicationContext),
    private val allowedSources: Set<LiveScoreSource> = LiveScoreSource.values().toSet(),
    private val allowOfflineBulletinFallback: Boolean = true,
    bilyonerSporTotoScraper: BilyonerSporTotoScraper? = null
) : SporTotoBulletinRepository {

    private val resolvedBilyonerSporTotoScraper: BilyonerSporTotoScraper? =
        bilyonerSporTotoScraper ?: applicationContext?.let { BilyonerSporTotoScraper(it) }

    companion object {
        /** Keep 20 of the Free plan's 100 daily calls in reserve. */
        const val FREE_DAILY_REQUEST_BUDGET = 80
        /** Free mode checks scores at most every three minutes per device. */
        const val FREE_MIN_POLL_INTERVAL_MS = 3 * 60 * 1000L
        /** Keyless ESPN cooldown to avoid duplicate rapid-fire requests. */
        const val ESPN_MIN_POLL_INTERVAL_MS = 20 * 1000L
        /** API-Football League IDs: Trendyol Süper Lig & TFF 1. Lig. */
        const val LEAGUE_TURKEY_SUPER_LIG = 203
        const val LEAGUE_TURKEY_FIRST_LIG = 204
        val DEFAULT_ESPN_LEAGUES = listOf(
            "tur.1",
            "uefa.nations",
            "fifa.worldq.uefa",
            "uefa.euroq",
            "eng.1",
            "esp.1",
            "ger.1",
            "ita.1",
            "fra.1",
            "uefa.champions"
        )
    }

    private val preferences = applicationContext?.applicationContext
        ?.getSharedPreferences("api_football_free", Context.MODE_PRIVATE)
    @Volatile private var configuredApiKey: String =
        preferences?.getString("api_key", null)?.trim().orEmpty()
            .ifBlank { liveScoreApiKey.trim() }
    @Volatile private var quotaDay: String = preferences?.getString("quota_day", "").orEmpty()
    @Volatile private var usedToday: Int = preferences?.getInt("used_today", 0) ?: 0
    @Volatile private var lastAttemptMs: Long = preferences?.getLong("last_attempt", 0L) ?: 0L
    @Volatile private var providerRemaining: Int? =
        preferences?.getInt("provider_remaining", -1)?.takeIf { it >= 0 }
    private var lastSuccessfulRequestMs = 0L
    private var lastEspnAttemptMs = 0L
    private var lastSuccessfulEspnMs = 0L
    private var lastIddaaAttemptMs = 0L
    private var lastSuccessfulIddaaMs = 0L
    private var lastNesineAttemptMs = 0L
    private var lastSuccessfulNesineMs = 0L
    private var lastBilyonerAttemptMs = 0L
    private var lastSuccessfulBilyonerMs = 0L
    private val pollMutex = Mutex()
    private val finishedDateFixturesCache = mutableMapOf<String, List<MatchLiveScore>>()

    private val _cachedBulletin = MutableStateFlow<WeeklyBulletinResponse?>(null)
    override val cachedBulletin: StateFlow<WeeklyBulletinResponse?> = _cachedBulletin.asStateFlow()

    private val _liveScores = MutableStateFlow<List<MatchLiveScore>>(emptyList())
    override val liveScores: StateFlow<List<MatchLiveScore>> = _liveScores.asStateFlow()

    private val _activeLiveScoreSource = MutableStateFlow(
        if (enforceNesineOnly) {
            LiveScoreSource.NESINE_RESULTS
        } else {
            val migrationVersion = preferences?.getInt("source_migration_v", 0) ?: 0
            if (migrationVersion < 2 && preferences != null) {
                preferences.edit()
                    .putInt("source_migration_v", 2)
                    .putString("live_score_source", initialSource.name)
                    .apply()
            }
            val saved = preferences?.getString("live_score_source", null)?.let { name ->
                LiveScoreSource.values().firstOrNull { it.name == name }
            }
            val candidate = saved ?: initialSource
            if (candidate in allowedSources) candidate else allowedSources.firstOrNull() ?: initialSource
        }
    )
    override val activeLiveScoreSource: StateFlow<LiveScoreSource> = _activeLiveScoreSource.asStateFlow()

    private val _liveScoreProvider = MutableStateFlow(_activeLiveScoreSource.value.displayName)
    override val liveScoreProvider: StateFlow<String> = _liveScoreProvider.asStateFlow()

    private val _previousWeekReport = MutableStateFlow<PreviousWeekBulletinReport?>(null)
    override val previousWeekReport: StateFlow<PreviousWeekBulletinReport?> = _previousWeekReport.asStateFlow()

    override fun setLiveScoreSource(source: LiveScoreSource) {
        if (enforceNesineOnly) {
            _activeLiveScoreSource.value = LiveScoreSource.NESINE_RESULTS
            _liveScoreProvider.value = LiveScoreSource.NESINE_RESULTS.displayName
            return
        }
        val target = if (source in allowedSources) {
            source
        } else {
            allowedSources.firstOrNull() ?: return
        }
        if (_activeLiveScoreSource.value != target) {
            _activeLiveScoreSource.value = target
            _liveScoreProvider.value = target.displayName
            _liveScores.value = emptyList()
            _previousWeekReport.value = null
            lastAttemptMs = 0L
            lastSuccessfulRequestMs = 0L
            lastEspnAttemptMs = 0L
            lastSuccessfulEspnMs = 0L
            lastIddaaAttemptMs = 0L
            lastSuccessfulIddaaMs = 0L
            lastNesineAttemptMs = 0L
            lastSuccessfulNesineMs = 0L
            lastBilyonerAttemptMs = 0L
            lastSuccessfulBilyonerMs = 0L
            finishedDateFixturesCache.clear()
            preferences?.edit()?.putString("live_score_source", target.name)?.apply()
        }
    }

    override fun requiresApiKeyForActiveSource(): Boolean =
        _activeLiveScoreSource.value == LiveScoreSource.API_FOOTBALL

    override fun hasApiFootballKey(): Boolean = configuredApiKey.isNotBlank()

    override fun configureApiFootballKey(key: String) {
        val normalized = key.trim()
        if (normalized != configuredApiKey) {
            configuredApiKey = normalized
            usedToday = 0
            lastAttemptMs = 0L
            lastSuccessfulRequestMs = 0L
            providerRemaining = null
            finishedDateFixturesCache.clear()
            _liveScores.value = emptyList()
            _previousWeekReport.value = null
            preferences?.edit()
                ?.putString("api_key", normalized)
                ?.putInt("used_today", 0)
                ?.putLong("last_attempt", 0L)
                ?.putInt("provider_remaining", -1)
                ?.apply()
        }
    }

    override fun freeScoreRequestsRemaining(): Int? {
        if (!hasApiFootballKey()) return null
        val today = utcDay()
        val localRemaining = if (today == quotaDay) {
            (FREE_DAILY_REQUEST_BUDGET - usedToday).coerceAtLeast(0)
        } else FREE_DAILY_REQUEST_BUDGET
        return minOf(localRemaining, providerRemaining ?: 100)
    }

    private fun utcDay(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun persistQuota() {
        preferences?.edit()
            ?.putString("quota_day", quotaDay)
            ?.putInt("used_today", usedToday)
            ?.putLong("last_attempt", lastAttemptMs)
            ?.putInt("provider_remaining", providerRemaining ?: -1)
            ?.apply()
    }

    private fun refreshDailyQuota() {
        val today = utcDay()
        if (quotaDay != today) {
            quotaDay = today
            usedToday = 0
            lastAttemptMs = 0L
            providerRemaining = null
            persistQuota()
        }
    }

    /**
     * API-Football is the only live-score source. Free quota is tracked locally
     * and provider response headers are also honored. Scores and minutes are
     * taken verbatim from the authenticated API; no fallback or mock games.
     */
    override suspend fun pollLiveScores(): Result<List<MatchLiveScore>> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                if (_activeLiveScoreSource.value == LiveScoreSource.ESPN) {
                    return@withLock pollLiveScoresFromEspnLocked()
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.NESINE_RESULTS) {
                    return@withLock pollLiveScoresFromNesineLocked()
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.BILYONER_LIVE) {
                    return@withLock pollLiveScoresFromBilyonerLocked(force = false)
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.IDDAA_SCRAPE) {
                    return@withLock pollLiveScoresFromIddaaLocked()
                }
                fun unavailable(message: String): Result<List<MatchLiveScore>> {
                    _liveScores.value = emptyList()
                    return Result.failure(IllegalStateException(message))
                }

                val key = configuredApiKey
                if (key.isBlank()) {
                    return@withLock unavailable(
                        "Canlı skor için ücretsiz API-Football anahtarınızı girin."
                    )
                }
                refreshDailyQuota()
                val now = System.currentTimeMillis()
                if (lastAttemptMs > 0L && now - lastAttemptMs < FREE_MIN_POLL_INTERVAL_MS) {
                    return@withLock if (lastSuccessfulRequestMs == lastAttemptMs) {
                        Result.success(_liveScores.value)
                    } else {
                        unavailable("Ücretsiz kotayı korumak için 3 dakika bekleyip tekrar deneyin.")
                    }
                }
                if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                    return@withLock unavailable(
                        "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. " +
                            "Kota 00:00 UTC'de yenilenir."
                    )
                }

                // Count attempts too; invalid keys and HTTP errors can consume quota.
                lastAttemptMs = now

                try {
                    val bulletinMatches = _cachedBulletin.value?.matches.orEmpty().map { it.toDomainTotoMatch() }
                    val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                    val todayIstanbul = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = istanbulTz
                    }.format(Date(now))
                    val eligibleDates = if (bulletinMatches.isNotEmpty()) {
                        extractEligibleBulletinDates(bulletinMatches, todayIstanbul, now)
                    } else {
                        emptyList()
                    }

                    val collectedRawScores = mutableListOf<MatchLiveScore>()

                    if (eligibleDates.isNotEmpty()) {
                        val weekKey = _cachedBulletin.value?.weekNumber ?: 0
                        for (dateStr in eligibleDates) {
                            val cacheKey = "$weekKey|$dateStr"
                            val cachedPast = if (dateStr < todayIstanbul) finishedDateFixturesCache[cacheKey] else null
                            if (cachedPast != null) {
                                collectedRawScores.addAll(cachedPast)
                                continue
                            }

                            if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                                return@withLock unavailable(
                                    "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. Kota 00:00 UTC'de yenilenir."
                                )
                            }
                            usedToday++
                            persistQuota()

                            val response = liveScoreService.getFixturesByDate(key, dateStr, "Europe/Istanbul")
                            response.headers()["x-ratelimit-requests-remaining"]
                                ?.toIntOrNull()?.let { providerRemaining = it.coerceAtLeast(0) }
                            persistQuota()

                            val body = response.body()
                            if (!response.isSuccessful || body == null) {
                                val problem = when (response.code()) {
                                    401, 403 -> "API-Football anahtarı geçersiz veya erişim izni yok."
                                    429 -> "API-Football ücretsiz istek kotası doldu."
                                    else -> "API-Football yanıt vermedi (HTTP ${response.code()})."
                                }
                                return@withLock unavailable(problem)
                            }
                            val providerErrors = body.errors
                            if ((providerErrors is Map<*, *> && providerErrors.isNotEmpty()) ||
                                (providerErrors is List<*> && providerErrors.isNotEmpty())
                            ) {
                                return@withLock unavailable(
                                    "API-Football isteği reddetti. Anahtarınızı ve ücretsiz plan kotanızı kontrol edin."
                                )
                            }

                            val parsedForDate = parseApiFootballFixtures(body)
                            val bulletinForDate = CouponPoolEvaluator.filterAndAlignToBulletin(
                                matches = bulletinMatches,
                                liveScores = parsedForDate
                            )
                            if (dateStr < todayIstanbul &&
                                parsedForDate.isNotEmpty() &&
                                bulletinForDate.none { it.isLive }
                            ) {
                                // Past bulletin day with only finished matches: cache to protect free quota.
                                finishedDateFixturesCache[cacheKey] = bulletinForDate
                            }
                            collectedRawScores.addAll(parsedForDate)
                        }
                    } else {
                        usedToday++
                        persistQuota()

                        val response = liveScoreService.getLiveFixtures(key)
                        response.headers()["x-ratelimit-requests-remaining"]
                            ?.toIntOrNull()?.let { providerRemaining = it.coerceAtLeast(0) }
                        persistQuota()
                        val body = response.body()
                        if (!response.isSuccessful || body == null) {
                            val problem = when (response.code()) {
                                401, 403 -> "API-Football anahtarı geçersiz veya erişim izni yok."
                                429 -> "API-Football ücretsiz istek kotası doldu."
                                else -> "API-Football yanıt vermedi (HTTP ${response.code()})."
                            }
                            return@withLock unavailable(problem)
                        }
                        val providerErrors = body.errors
                        if ((providerErrors is Map<*, *> && providerErrors.isNotEmpty()) ||
                            (providerErrors is List<*> && providerErrors.isNotEmpty())
                        ) {
                            return@withLock unavailable(
                                "API-Football isteği reddetti. Anahtarınızı ve ücretsiz plan kotanızı kontrol edin."
                            )
                        }
                        collectedRawScores.addAll(parseApiFootballFixtures(body))
                    }

                    val distinctRaw = collectedRawScores.distinctBy { it.matchNumber }
                    val actualScores = if (bulletinMatches.isNotEmpty()) {
                        CouponPoolEvaluator.filterAndAlignToBulletin(bulletinMatches, distinctRaw)
                    } else {
                        distinctRaw.sortedWith(
                            compareByDescending<MatchLiveScore> { it.isLive }
                                .thenByDescending { it.matchDate }
                        )
                    }

                    _liveScoreProvider.value = "API-FOOTBALL (Free)"
                    _liveScores.value = actualScores
                    lastSuccessfulRequestMs = lastAttemptMs
                    Result.success(actualScores)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unavailable("API-Football bağlantısı kurulamadı. İnterneti kontrol edin.")
                }
            }
        }

    private fun parseApiFootballFixtures(body: ApiFootballLiveResponse): List<MatchLiveScore> {
        return body.response.mapNotNull { item ->
            val status = when (item.fixture.status.short.uppercase()) {
                "HT" -> MatchStatus.HALF_TIME
                "FT", "AET", "PEN" -> MatchStatus.FINISHED
                "1H", "2H", "ET", "BT", "P", "LIVE", "INT", "SUSP" -> MatchStatus.LIVE
                else -> MatchStatus.UPCOMING
            }
            if (status == MatchStatus.UPCOMING) return@mapNotNull null
            MatchLiveScore(
                matchNumber = item.fixture.id,
                homeTeam = item.teams.home.name,
                awayTeam = item.teams.away.name,
                homeScore = item.goals.home,
                awayScore = item.goals.away,
                status = status,
                minuteText = when {
                    status == MatchStatus.HALF_TIME -> "İY"
                    status == MatchStatus.FINISHED -> "MS"
                    item.fixture.status.elapsed != null -> "${item.fixture.status.elapsed}'"
                    else -> "—"
                },
                matchDate = item.fixture.date.orEmpty(),
                league = item.league?.name.orEmpty()
            )
        }
    }

    private fun extractEligibleBulletinDates(
        bulletinMatches: List<TotoMatch>,
        todayIstanbul: String,
        nowMs: Long
    ): List<String> {
        val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istanbulTz }
        val inputPatterns = listOf("dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssXXX")
        val cutoffPastMs = nowMs - 10L * 24 * 60 * 60 * 1000L

        return bulletinMatches.mapNotNull { match ->
            val raw = match.matchDate.trim()
            if (raw.isEmpty()) return@mapNotNull null
            val parsedMs = inputPatterns.firstNotNullOfOrNull { pattern ->
                try {
                    SimpleDateFormat(pattern, Locale.ROOT).apply {
                        isLenient = false
                        timeZone = istanbulTz
                    }.parse(raw)?.time
                } catch (_: Exception) {
                    null
                }
            } ?: return@mapNotNull null
            val dayStr = dayFmt.format(Date(parsedMs))
            if ((dayStr <= todayIstanbul || parsedMs <= nowMs) && parsedMs >= cutoffPastMs) {
                dayStr
            } else {
                null
            }
        }.distinct().sorted().takeLast(5)
    }

    private fun defaultOfflineBulletin(
        weekNumber: Int = if (preferTurkishLeagueBulletin) 39 else 38
    ): WeeklyBulletinResponse =
        if (preferTurkishLeagueBulletin) {
            NetworkModule.createTurkishLeagueSporTotoBulletin(weekNumber)
        } else {
            NetworkModule.createFallbackBulletin(weekNumber)
        }

    override suspend fun syncTurkishLeagueBulletinFromApi(): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                if (enforceNesineOnly || _activeLiveScoreSource.value == LiveScoreSource.NESINE_RESULTS) {
                    return@withLock scrapeNesineBulletinLocked()
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.ESPN) {
                    return@withLock syncTurkishLeagueBulletinFromEspnLocked()
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.IDDAA_SCRAPE) {
                    return@withLock scrapeIddaaLiveBulletinLocked()
                }
                val key = configuredApiKey
                if (key.isBlank()) {
                    return@withLock Result.failure(
                        IllegalStateException(
                            "Türkiye Ligi maçlarını API'den çekmek için önce Canlı sekmesinden API-Football anahtarınızı girin."
                        )
                    )
                }
                refreshDailyQuota()
                if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                    return@withLock Result.failure(
                        IllegalStateException(
                            "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. Kota 00:00 UTC'de yenilenir."
                        )
                    )
                }

                try {
                    val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val season = if (month >= 7) year else year - 1
                    val isoDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istanbulTz }
                    val fromCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -2) }
                    val toCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 9) }
                    val fromDate = isoDayFmt.format(fromCal.time)
                    val toDate = isoDayFmt.format(toCal.time)

                    suspend fun fetchLeague(leagueId: Int): Result<ApiFootballLiveResponse> {
                        if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                            return Result.failure(
                                IllegalStateException(
                                    "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. Kota 00:00 UTC'de yenilenir."
                                )
                            )
                        }
                        usedToday++
                        persistQuota()
                        val response = liveScoreService.getFixturesByLeague(
                            apiKey = key,
                            leagueId = leagueId,
                            season = season,
                            fromDate = fromDate,
                            toDate = toDate,
                            timezone = "Europe/Istanbul"
                        )
                        response.headers()["x-ratelimit-requests-remaining"]
                            ?.toIntOrNull()?.let { providerRemaining = it.coerceAtLeast(0) }
                        persistQuota()

                        val body = response.body()
                        if (!response.isSuccessful || body == null) {
                            val problem = when (response.code()) {
                                401, 403 -> "API-Football anahtarı geçersiz veya erişim izni yok."
                                429 -> "API-Football ücretsiz istek kotası doldu."
                                else -> "API-Football yanıt vermedi (HTTP ${response.code()})."
                            }
                            return Result.failure(IllegalStateException(problem))
                        }
                        val providerErrors = body.errors
                        if ((providerErrors is Map<*, *> && providerErrors.isNotEmpty()) ||
                            (providerErrors is List<*> && providerErrors.isNotEmpty())
                        ) {
                            return Result.failure(
                                IllegalStateException(
                                    "API-Football isteği reddetti. Anahtarınızı ve ücretsiz plan kotanızı kontrol edin."
                                )
                            )
                        }
                        return Result.success(body)
                    }

                    val rawResponses = mutableListOf<ApiFootballLiveResponse>()
                    val superLigBody = fetchLeague(LEAGUE_TURKEY_SUPER_LIG).getOrElse {
                        return@withLock Result.failure(it)
                    }
                    rawResponses.add(superLigBody)
                    val superLigFixtures = superLigBody.response
                        .sortedBy { it.fixture.date.orEmpty() }

                    val firstLigFixtures = if (superLigFixtures.size < 15 &&
                        usedToday < FREE_DAILY_REQUEST_BUDGET &&
                        providerRemaining != 0
                    ) {
                        val res = fetchLeague(LEAGUE_TURKEY_FIRST_LIG)
                        res.getOrNull()?.let { body ->
                            rawResponses.add(body)
                            body.response.sortedBy { it.fixture.date.orEmpty() }
                        }.orEmpty()
                    } else {
                        emptyList()
                    }

                    val selectedFixtures = buildList {
                        addAll(superLigFixtures.take(10))
                        val remainingSlots = 15 - size
                        if (remainingSlots > 0) {
                            addAll(
                                firstLigFixtures
                                    .filter { f -> none { it.fixture.id == f.fixture.id } }
                                    .take(remainingSlots)
                            )
                        }
                        if (size < 15) {
                            addAll(
                                superLigFixtures
                                    .filter { f -> none { it.fixture.id == f.fixture.id } }
                                    .take(15 - size)
                            )
                        }
                    }.sortedBy { it.fixture.date.orEmpty() }

                    val bulletinMatches = mutableListOf<BulletinMatchDto>()
                    selectedFixtures.forEachIndexed { idx, fixture ->
                        bulletinMatches.add(fixture.toBulletinMatchDto(idx + 1, istanbulTz))
                    }

                    if (bulletinMatches.size < 15) {
                        val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin().matches
                        val existingPairs = bulletinMatches.map {
                            "${it.homeTeam.lowercase(Locale.ROOT)}|${it.awayTeam.lowercase(Locale.ROOT)}"
                        }.toMutableSet()
                        for (candidate in fallbackMatches) {
                            if (bulletinMatches.size >= 15) break
                            val pair = "${candidate.homeTeam.lowercase(Locale.ROOT)}|${candidate.awayTeam.lowercase(Locale.ROOT)}"
                            if (existingPairs.add(pair)) {
                                bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                            }
                        }
                    }

                    val finalMatches = bulletinMatches.take(15).mapIndexed { index, dto ->
                        dto.copy(matchNumber = index + 1)
                    }
                    val weekNum = cal.get(java.util.Calendar.WEEK_OF_YEAR).coerceIn(1, 53)
                    val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                        ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                    val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate

                    val bulletin = WeeklyBulletinResponse(
                        weekNumber = weekNum,
                        season = "$season-${season + 1}",
                        bulletinTitle = "Türkiye Ligi Spor Toto $weekNum. Hafta (API)",
                        startDate = startDate,
                        endDate = endDate,
                        jackpotEstimateTL = 0.0,
                        isActive = true,
                        matches = finalMatches
                    )
                    _cachedBulletin.value = bulletin

                    // Immediately update live/finished scores from the fetched Turkish league fixtures
                    val parsedScores = rawResponses
                        .flatMap { parseApiFootballFixtures(it) }
                        .distinctBy { it.matchNumber }
                    val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
                    val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(domainMatches, parsedScores)
                    _liveScoreProvider.value = "API-FOOTBALL (Free)"
                    _liveScores.value = aligned

                    Result.success(bulletin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Result.failure(
                        IllegalStateException("Türkiye Ligi API-Football bağlantısı kurulamadı. İnterneti kontrol edin.")
                    )
                }
            }
        }

    override suspend fun fetchPreviousWeekBulletinAndResults(
        forceRefresh: Boolean
    ): Result<PreviousWeekBulletinReport> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                if (!forceRefresh) {
                    _previousWeekReport.value?.let { cached ->
                        return@withLock Result.success(cached)
                    }
                }

                if (resolvedBilyonerSporTotoScraper != null && !enforceNesineOnly) {
                    return@withLock fetchPreviousWeekFromBilyonerLocked()
                }
                if (enforceNesineOnly || LiveScoreSource.ESPN !in allowedSources) {
                    return@withLock fetchPreviousWeekFromNesineLocked()
                }
                if (_activeLiveScoreSource.value == LiveScoreSource.ESPN || configuredApiKey.isBlank()) {
                    return@withLock fetchPreviousWeekFromEspnLocked()
                }

                val key = configuredApiKey
                refreshDailyQuota()
                if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                    return@withLock Result.failure(
                        IllegalStateException(
                            "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. Kota 00:00 UTC'de yenilenir."
                        )
                    )
                }

                try {
                    val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val season = if (month >= 7) year else year - 1
                    val isoDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istanbulTz }
                    val fromCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -11) }
                    val toCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -3) }
                    val fromDate = isoDayFmt.format(fromCal.time)
                    val toDate = isoDayFmt.format(toCal.time)

                    suspend fun queryLeague(leagueId: Int): Result<List<ApiFootballLiveFixture>> {
                        if (usedToday >= FREE_DAILY_REQUEST_BUDGET || providerRemaining == 0) {
                            return Result.failure(
                                IllegalStateException(
                                    "API-Football ücretsiz günlük sorgu sınırına ulaşıldı. Kota 00:00 UTC'de yenilenir."
                                )
                            )
                        }
                        usedToday++
                        persistQuota()
                        val response = liveScoreService.getFixturesByLeague(
                            apiKey = key,
                            leagueId = leagueId,
                            season = season,
                            fromDate = fromDate,
                            toDate = toDate,
                            timezone = "Europe/Istanbul"
                        )
                        response.headers()["x-ratelimit-requests-remaining"]
                            ?.toIntOrNull()?.let { providerRemaining = it.coerceAtLeast(0) }
                        persistQuota()

                        val body = response.body()
                        if (!response.isSuccessful || body == null) {
                            val problem = when (response.code()) {
                                401, 403 -> "API-Football anahtarı geçersiz veya erişim izni yok."
                                429 -> "API-Football ücretsiz istek kotası doldu."
                                else -> "API-Football yanıt vermedi (HTTP ${response.code()})."
                            }
                            return Result.failure(IllegalStateException(problem))
                        }
                        val providerErrors = body.errors
                        if ((providerErrors is Map<*, *> && providerErrors.isNotEmpty()) ||
                            (providerErrors is List<*> && providerErrors.isNotEmpty())
                        ) {
                            return Result.failure(
                                IllegalStateException(
                                    "API-Football isteği reddetti. Anahtarınızı ve ücretsiz plan kotanızı kontrol edin."
                                )
                            )
                        }
                        val finishedOnly = body.response.filter { item ->
                            val short = item.fixture.status.short.uppercase(Locale.ROOT)
                            short in setOf("FT", "AET", "PEN") &&
                                item.goals.home != null &&
                                item.goals.away != null
                        }.sortedBy { it.fixture.date.orEmpty() }
                        return Result.success(finishedOnly)
                    }

                    val superLig = queryLeague(LEAGUE_TURKEY_SUPER_LIG).getOrElse {
                        return@withLock Result.failure(it)
                    }
                    val firstLig = if (superLig.size < 15 &&
                        usedToday < FREE_DAILY_REQUEST_BUDGET &&
                        providerRemaining != 0
                    ) {
                        queryLeague(LEAGUE_TURKEY_FIRST_LIG).getOrNull().orEmpty()
                    } else {
                        emptyList()
                    }
                    val europeanTop = if (superLig.size + firstLig.size < 15 &&
                        usedToday < FREE_DAILY_REQUEST_BUDGET &&
                        providerRemaining != 0
                    ) {
                        queryLeague(39).getOrNull().orEmpty()
                    } else {
                        emptyList()
                    }

                    val picked = buildList {
                        addAll(superLig.take(10))
                        if (size < 15) {
                            addAll(firstLig.filter { f -> none { it.fixture.id == f.fixture.id } }.take(15 - size))
                        }
                        if (size < 15) {
                            addAll(superLig.filter { f -> none { it.fixture.id == f.fixture.id } }.take(15 - size))
                        }
                        if (size < 15) {
                            addAll(europeanTop.filter { f -> none { it.fixture.id == f.fixture.id } }.take(15 - size))
                        }
                    }.distinctBy { it.fixture.id }
                        .sortedBy { it.fixture.date.orEmpty() }
                        .take(15)

                    if (picked.isEmpty()) {
                        return@withLock Result.failure(
                            IllegalStateException(
                                "Geçen hafta ($fromDate - $toDate) için tamamlanmış API-Football maçı bulunamadı."
                            )
                        )
                    }

                    val mapped = picked.mapIndexed { idx, item ->
                        val homeGoals = item.goals.home
                        val awayGoals = item.goals.away
                        val winning = when {
                            homeGoals == null || awayGoals == null -> null
                            homeGoals > awayGoals -> PredictionOutcome.HOME
                            homeGoals == awayGoals -> PredictionOutcome.DRAW
                            else -> PredictionOutcome.AWAY
                        }
                        PastBulletinMatchResult(
                            matchNumber = idx + 1,
                            homeTeam = item.teams.home.name.uppercase(Locale.ROOT).trim(),
                            awayTeam = item.teams.away.name.uppercase(Locale.ROOT).trim(),
                            matchDate = formatIsoDateForBulletin(item.fixture.date, istanbulTz),
                            league = item.league?.name?.takeIf { it.isNotBlank() } ?: "Türkiye Ligi",
                            homeScore = homeGoals,
                            awayScore = awayGoals,
                            status = MatchStatus.FINISHED,
                            winningOutcome = winning
                        )
                    }

                    val currentWeek = _cachedBulletin.value?.weekNumber ?: cal.get(java.util.Calendar.WEEK_OF_YEAR)
                    val prevWeek = (currentWeek - 1).coerceAtLeast(1)
                    val firstDay = mapped.firstOrNull()?.matchDate?.substringBefore(" ") ?: fromDate
                    val lastDay = mapped.lastOrNull()?.matchDate?.substringBefore(" ") ?: toDate
                    val report = PreviousWeekBulletinReport(
                        weekNumber = prevWeek,
                        weekLabel = "Geçen Hafta ($prevWeek. Hafta)",
                        dateRangeLabel = "$firstDay - $lastDay",
                        sourceLabel = "API-FOOTBALL • Doğrulanmış Sonuçlar",
                        matches = mapped
                    )
                    _previousWeekReport.value = report
                    Result.success(report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Result.failure(
                        IllegalStateException("Geçen haftanın sonuçları için API-Football bağlantısı kurulamadı.")
                    )
                }
            }
        }

    private fun ApiFootballLiveFixture.toBulletinMatchDto(
        matchNumber: Int,
        istanbulTz: TimeZone
    ): BulletinMatchDto {
        val formattedDate = formatIsoDateForBulletin(fixture.date, istanbulTz)
        val statusLabel = when (fixture.status.short.uppercase(Locale.ROOT)) {
            "FT", "AET", "PEN" -> "FINISHED"
            "1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT", "SUSP" -> "LIVE"
            else -> "UPCOMING"
        }
        val leagueLabel = league?.name?.takeIf { it.isNotBlank() } ?: "Türkiye Ligi"
        return BulletinMatchDto(
            matchNumber = matchNumber,
            homeTeam = teams.home.name.uppercase(Locale.ROOT).trim(),
            awayTeam = teams.away.name.uppercase(Locale.ROOT).trim(),
            matchDate = formattedDate,
            league = leagueLabel,
            venue = "",
            status = statusLabel,
            liveOdds = LiveBettingOddsDto(
                homeWinOdd = 2.15,
                drawOdd = 3.25,
                awayWinOdd = 3.30,
                doubleChance1X = 1.32,
                doubleChance12 = 1.28,
                doubleChanceX2 = 1.62
            ),
            probabilities = MatchProbabilitiesDto(
                homeProbability = 42,
                drawProbability = 29,
                awayProbability = 29
            )
        )
    }

    private fun formatIsoDateForBulletin(rawDate: String?, istanbulTz: TimeZone): String {
        val outFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = istanbulTz }
        if (rawDate.isNullOrBlank()) return outFmt.format(Date())
        val trimmed = rawDate.trim()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mmXXX",
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "dd.MM.yyyy HH:mm"
        )
        val parsed = patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.ROOT).apply {
                    isLenient = false
                    if (pattern.endsWith("'Z'")) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    } else if (!pattern.contains("XXX")) {
                        timeZone = istanbulTz
                    }
                }.parse(trimmed)
            } catch (_: Exception) {
                null
            }
        }
        return outFmt.format(parsed ?: Date())
    }

    private fun espnLeagueLabel(slug: String, fallbackName: String?): String = when (slug) {
        "tur.1" -> "Trendyol Süper Lig"
        "eng.1" -> "İngiltere Premier Lig"
        "esp.1" -> "İspanya La Liga"
        "ger.1" -> "Almanya Bundesliga"
        "ita.1" -> "İtalya Serie A"
        "fra.1" -> "Fransa Ligue 1"
        "uefa.nations" -> "UEFA Uluslar Ligi"
        "uefa.champions" -> "UEFA Şampiyonlar Ligi"
        "uefa.europa" -> "UEFA Avrupa Ligi"
        "uefa.euroq" -> "UEFA Euro Elemeleri"
        "uefa.euro" -> "UEFA Avrupa Şampiyonası"
        "fifa.worldq.uefa" -> "Dünya Kupası Elemeleri"
        "fifa.friendly" -> "Uluslararası Hazırlık"
        else -> fallbackName?.takeIf { it.isNotBlank() } ?: slug.uppercase(Locale.ROOT)
    }

    private suspend fun pollLiveScoresFromEspnLocked(): Result<List<MatchLiveScore>> {
        val now = System.currentTimeMillis()
        if (lastEspnAttemptMs > 0L && now - lastEspnAttemptMs < ESPN_MIN_POLL_INTERVAL_MS &&
            lastSuccessfulEspnMs == lastEspnAttemptMs
        ) {
            return Result.success(_liveScores.value)
        }
        lastEspnAttemptMs = now
        return try {
            val bulletinMatches = _cachedBulletin.value?.matches.orEmpty().map { it.toDomainTotoMatch() }
            val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
            val todayIstanbul = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = istanbulTz
            }.format(Date(now))
            val eligibleDates = if (bulletinMatches.isNotEmpty()) {
                extractEligibleBulletinDates(bulletinMatches, todayIstanbul, now)
            } else {
                emptyList()
            }
            val datesParam = if (eligibleDates.isNotEmpty()) {
                val startCompact = eligibleDates.first().replace("-", "")
                val endCompact = eligibleDates.last().replace("-", "")
                if (startCompact == endCompact) startCompact else "$startCompact-$endCompact"
            } else {
                null
            }

            var anySuccess = false
            val leagueResponses = coroutineScope {
                espnLeagueSlugs.map { slug ->
                    async {
                        try {
                            val response = espnService.getScoreboard(slug, datesParam, limit = 100)
                            if (response.isSuccessful && response.body() != null) {
                                slug to response.body()!!
                            } else {
                                null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }

            val collectedRaw = mutableListOf<MatchLiveScore>()
            leagueResponses.filterNotNull().forEach { (slug, body) ->
                anySuccess = true
                val label = espnLeagueLabel(slug, body.leagues.firstOrNull()?.name)
                body.events.forEach { event ->
                    event.toMatchLiveScore(label)?.let { collectedRaw.add(it) }
                }
            }

            if (!anySuccess) {
                _liveScores.value = emptyList()
                return Result.failure(
                    IllegalStateException("ESPN bağlantısı kurulamadı. İnterneti kontrol edin.")
                )
            }

            val distinctRaw = collectedRaw.distinctBy { it.matchNumber }
            val actualScores = if (bulletinMatches.isNotEmpty()) {
                CouponPoolEvaluator.filterAndAlignToBulletin(bulletinMatches, distinctRaw)
            } else {
                distinctRaw.sortedWith(
                    compareByDescending<MatchLiveScore> { it.isLive }
                        .thenByDescending { it.matchDate }
                )
            }

            _liveScoreProvider.value = LiveScoreSource.ESPN.displayName
            _liveScores.value = actualScores
            lastSuccessfulEspnMs = lastEspnAttemptMs
            Result.success(actualScores)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _liveScores.value = emptyList()
            Result.failure(IllegalStateException("ESPN bağlantısı kurulamadı. İnterneti kontrol edin."))
        }
    }

    private suspend fun syncTurkishLeagueBulletinFromEspnLocked(): Result<WeeklyBulletinResponse> {
        return try {
            val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val season = if (month >= 7) year else year - 1
            val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = istanbulTz }
            val fromCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -1) }
            val toCal = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 10) }
            val datesRange = "${compactFmt.format(fromCal.time)}-${compactFmt.format(toCal.time)}"

            val internationalSyncSlugs = setOf("uefa.nations", "fifa.worldq.uefa", "uefa.euroq")
            val syncSlugs = listOf(
                "tur.1",
                "uefa.nations",
                "fifa.worldq.uefa",
                "uefa.euroq",
                "eng.1",
                "esp.1",
                "ger.1",
                "ita.1",
                "fra.1",
                "uefa.champions",
                "uefa.europa"
            )
            var results = coroutineScope {
                syncSlugs.map { slug ->
                    async {
                        try {
                            val response = espnService.getScoreboard(slug, datesRange, limit = 100)
                            if (response.isSuccessful && response.body() != null) {
                                slug to response.body()!!
                            } else {
                                null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }.filterNotNull()

            val totalDatedEvents = results.sumOf { it.second.events.size }
            if (totalDatedEvents < 15) {
                val fallbackLatest = coroutineScope {
                    syncSlugs.map { slug ->
                        async {
                            try {
                                val response = espnService.getScoreboard(slug, null, limit = 100)
                                if (response.isSuccessful && response.body() != null) {
                                    slug to response.body()!!
                                } else {
                                    null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()
                if (fallbackLatest.isNotEmpty()) {
                    results = (results + fallbackLatest)
                        .groupBy { it.first }
                        .map { (slug, entries) ->
                            val info = entries.firstOrNull()?.second?.leagues.orEmpty()
                            val mergedEvents = entries.flatMap { it.second.events }
                                .distinctBy { ev -> ev.id.ifBlank { "${ev.name}|${ev.date}" } }
                            slug to com.example.network.EspnScoreboardResponse(leagues = info, events = mergedEvents)
                        }
                }
            }

            if (results.isEmpty()) {
                return Result.failure(
                    IllegalStateException("ESPN Türkiye Ligi bağlantısı kurulamadı. İnterneti kontrol edin.")
                )
            }

            val statusPriorityComparator = compareBy<EspnEvent>(
                { it.resolveStatus() == MatchStatus.FINISHED },
                { it.date.orEmpty() }
            )

            val superLigEvents = results.firstOrNull { it.first == "tur.1" }?.second?.events.orEmpty()
                .sortedWith(statusPriorityComparator)
            val internationalEvents = results.filter { it.first in internationalSyncSlugs }
                .flatMap { (slug, body) ->
                    val label = espnLeagueLabel(slug, body.leagues.firstOrNull()?.name)
                    body.events.map { it to label }
                }
                .sortedWith(
                    compareBy<Pair<EspnEvent, String>>(
                        { it.first.resolveStatus() == MatchStatus.FINISHED },
                        {
                            val name = it.first.name.orEmpty()
                            !(name.contains("Turkey", ignoreCase = true) ||
                                name.contains("Türkiye", ignoreCase = true))
                        },
                        { it.first.date.orEmpty() }
                    )
                )
            val europeClubEvents = results.filter { it.first != "tur.1" && it.first !in internationalSyncSlugs }
                .flatMap { (slug, body) ->
                    val label = espnLeagueLabel(slug, body.leagues.firstOrNull()?.name)
                    body.events.map { it to label }
                }
                .sortedWith(
                    compareBy<Pair<EspnEvent, String>>(
                        { it.first.resolveStatus() == MatchStatus.FINISHED },
                        { it.first.date.orEmpty() }
                    )
                )

            val hasActiveSuperLig = superLigEvents.any { it.resolveStatus() != MatchStatus.FINISHED }
            val hasActiveInternational = internationalEvents.any { it.first.resolveStatus() != MatchStatus.FINISHED }

            val pickedWithLeague = buildList<Pair<EspnEvent, String>> {
                if (hasActiveSuperLig || !hasActiveInternational) {
                    superLigEvents.take(10).forEach { add(it to "Trendyol Süper Lig") }
                    if (size < 15) {
                        (internationalEvents + europeClubEvents)
                            .filter { (ev, _) -> none { it.first.id == ev.id && ev.id.isNotBlank() } }
                            .take(15 - size)
                            .forEach { add(it) }
                    }
                    if (size < 15) {
                        superLigEvents
                            .filter { ev -> none { it.first.id == ev.id && ev.id.isNotBlank() } }
                            .take(15 - size)
                            .forEach { add(it to "Trendyol Süper Lig") }
                    }
                } else {
                    // International break: upcoming/live Nations League & qualifiers take priority
                    internationalEvents.take(10).forEach { add(it) }
                    if (size < 15) {
                        (europeClubEvents + superLigEvents.map { it to "Trendyol Süper Lig" })
                            .filter { (ev, _) -> none { it.first.id == ev.id && ev.id.isNotBlank() } }
                            .take(15 - size)
                            .forEach { add(it) }
                    }
                }
            }.sortedBy { it.first.date.orEmpty() }

            val bulletinMatches = mutableListOf<BulletinMatchDto>()
            pickedWithLeague.forEachIndexed { idx, (event, leagueLabel) ->
                event.toBulletinMatchDto(idx + 1, leagueLabel, istanbulTz)?.let {
                    bulletinMatches.add(it)
                }
            }

            if (bulletinMatches.size < 15) {
                val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin().matches
                val existingPairs = bulletinMatches.map {
                    "${it.homeTeam.lowercase(Locale.ROOT)}|${it.awayTeam.lowercase(Locale.ROOT)}"
                }.toMutableSet()
                for (candidate in fallbackMatches) {
                    if (bulletinMatches.size >= 15) break
                    val pair = "${candidate.homeTeam.lowercase(Locale.ROOT)}|${candidate.awayTeam.lowercase(Locale.ROOT)}"
                    if (existingPairs.add(pair)) {
                        bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                    }
                }
            }

            val finalMatches = bulletinMatches.take(15).mapIndexed { index, dto ->
                dto.copy(matchNumber = index + 1)
            }
            val weekNum = cal.get(java.util.Calendar.WEEK_OF_YEAR).coerceIn(1, 53)
            val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
            val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate

            val bulletin = WeeklyBulletinResponse(
                weekNumber = weekNum,
                season = "$season-${season + 1}",
                bulletinTitle = "Türkiye Ligi Spor Toto $weekNum. Hafta (ESPN)",
                startDate = startDate,
                endDate = endDate,
                jackpotEstimateTL = 0.0,
                isActive = true,
                matches = finalMatches
            )
            _cachedBulletin.value = bulletin

            val parsedScores = results
                .flatMap { (slug, body) ->
                    val label = espnLeagueLabel(slug, body.leagues.firstOrNull()?.name)
                    body.events.mapNotNull { it.toMatchLiveScore(label) }
                }
                .distinctBy { it.matchNumber }
            val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
            val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(domainMatches, parsedScores)
            _liveScoreProvider.value = LiveScoreSource.ESPN.displayName
            _liveScores.value = aligned
            lastEspnAttemptMs = now
            lastSuccessfulEspnMs = now

            Result.success(bulletin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(
                IllegalStateException("ESPN Türkiye Ligi bağlantısı kurulamadı. İnterneti kontrol edin.")
            )
        }
    }

    override suspend fun scrapeIddaaLiveBulletin(): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                scrapeIddaaLiveBulletinLocked()
            }
        }

    private suspend fun pollLiveScoresFromIddaaLocked(): Result<List<MatchLiveScore>> {
        val now = System.currentTimeMillis()
        if (com.example.network.HeadlessWebViewSupport.isCrashCooldownActive(now)) {
            return Result.success(_liveScores.value)
        }
        val cooldownMs = if (lastSuccessfulIddaaMs == lastIddaaAttemptMs) 45_000L else 35_000L
        if (lastIddaaAttemptMs > 0L && now - lastIddaaAttemptMs < cooldownMs) {
            return Result.success(_liveScores.value)
        }
        lastIddaaAttemptMs = now
        val scrapeRes = iddaaScraper.scrapeLiveFootball(IDDAA_LIVE_FOOTBALL_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val rawScores = payload.matches.mapIndexed { idx, scraped ->
                    scraped.toMatchLiveScore(idx + 1)
                }
                val bulletinMatches = _cachedBulletin.value?.matches.orEmpty().map { it.toDomainTotoMatch() }
                val aligned = if (bulletinMatches.isNotEmpty()) {
                    val matched = CouponPoolEvaluator.filterAndAlignToBulletin(bulletinMatches, rawScores)
                    if (matched.isNotEmpty()) matched else rawScores
                } else {
                    rawScores.sortedWith(
                        compareByDescending<MatchLiveScore> { it.isLive }
                            .thenBy { it.status == MatchStatus.UPCOMING }
                    )
                }
                _liveScoreProvider.value = "İddaa.com (${payload.methodLabel})"
                _liveScores.value = aligned
                lastSuccessfulIddaaMs = lastIddaaAttemptMs
                Result.success(aligned)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "iddaa.com/canli-skor/futbol canlı scraping başarısız."
                    )
                )
            }
        )
    }

    private suspend fun scrapeIddaaLiveBulletinLocked(): Result<WeeklyBulletinResponse> {
        val scrapeRes = iddaaScraper.scrapeLiveFootball(IDDAA_LIVE_FOOTBALL_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                val season = if (month >= 7) year else year - 1
                val weekNum = cal.get(java.util.Calendar.WEEK_OF_YEAR).coerceIn(1, 53)

                // Prioritize Turkish Süper Lig, then 1. Lig, and active live/upcoming fixtures when assembling 15-match bulletin
                val sortedScraped = payload.matches.sortedWith(
                    compareByDescending<IddaaScrapedMatch> { m ->
                        when {
                            m.league.contains("Süper Lig", ignoreCase = true) -> 2
                            m.league.contains("1. Lig", ignoreCase = true) ||
                                m.league.contains("Türkiye", ignoreCase = true) -> 1
                            else -> 0
                        }
                    }.thenByDescending { m ->
                        m.status == MatchStatus.LIVE || m.status == MatchStatus.HALF_TIME
                    }.thenBy { m ->
                        m.status == MatchStatus.FINISHED
                    }
                )

                val bulletinMatches = mutableListOf<BulletinMatchDto>()
                sortedScraped.take(15).forEachIndexed { idx, item ->
                    bulletinMatches.add(item.toBulletinMatchDto(idx + 1))
                }

                if (bulletinMatches.size < 15) {
                    val trLocale = Locale.forLanguageTag("tr-TR")
                    val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin(weekNum).matches
                    val existingPairs = bulletinMatches.map {
                        "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
                    }.toMutableSet()
                    for (candidate in fallbackMatches) {
                        if (bulletinMatches.size >= 15) break
                        val pair = "${candidate.homeTeam.lowercase(trLocale)}|${candidate.awayTeam.lowercase(trLocale)}"
                        if (existingPairs.add(pair)) {
                            bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                        }
                    }
                }

                val finalMatches = bulletinMatches.take(15).mapIndexed { index, dto ->
                    dto.copy(matchNumber = index + 1)
                }
                val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                    ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate

                val bulletin = WeeklyBulletinResponse(
                    weekNumber = weekNum,
                    season = "$season-${season + 1}",
                    bulletinTitle = "İddaa Canlı Bülten $weekNum. Hafta (${sortedScraped.size} Maç Scrape)",
                    startDate = startDate,
                    endDate = endDate,
                    jackpotEstimateTL = 0.0,
                    isActive = true,
                    matches = finalMatches
                )
                _cachedBulletin.value = bulletin

                val rawScores = payload.matches.mapIndexed { idx, scraped ->
                    scraped.toMatchLiveScore(idx + 1)
                }
                val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
                val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(domainMatches, rawScores)
                val effectiveScores = if (aligned.isNotEmpty()) aligned else rawScores
                _activeLiveScoreSource.value = LiveScoreSource.IDDAA_SCRAPE
                preferences?.edit()?.putString("live_score_source", LiveScoreSource.IDDAA_SCRAPE.name)?.apply()
                _liveScoreProvider.value = "İddaa.com (${payload.methodLabel})"
                _liveScores.value = effectiveScores
                lastIddaaAttemptMs = now
                lastSuccessfulIddaaMs = now
                Result.success(bulletin)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "iddaa.com/canli-skor/futbol adresinden bülten çekilemedi."
                    )
                )
            }
        )
    }

    override suspend fun scrapeNesineBulletin(
        forceRefresh: Boolean
    ): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                if (!forceRefresh && _cachedBulletin.value.isPlayable()) {
                    return@withLock Result.success(_cachedBulletin.value!!)
                }
                scrapeNesineBulletinLocked()
            }
        }

    override suspend fun scrapeNesineResultsAndBulletin(
        updateBulletin: Boolean
    ): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                scrapeNesineResultsAndBulletinLocked(updateBulletin)
            }
        }

    private suspend fun scrapeNesineBulletinLocked(): Result<WeeklyBulletinResponse> {
        val scrapeRes = nesineScraper.scrapeBulletin(NESINE_SPORTOTO_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                if ((enforceNesineOnly || !allowOfflineBulletinFallback) && payload.matches.size < 15) {
                    return Result.failure(
                        IllegalStateException(
                            "nesine.com/sportoto üzerinde 15 maçlık aktif bülten bulunamadı (${payload.matches.size} maç)."
                        )
                    )
                }
                val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                val season = if (month >= 7) year else year - 1
                val weekNum = (payload.weekNumber ?: cal.get(java.util.Calendar.WEEK_OF_YEAR)).coerceIn(1, 9999)

                val bulletinMatches = payload.matches.take(15).mapIndexed { idx, scraped ->
                    scraped.toBulletinMatchDto().copy(matchNumber = idx + 1)
                }.toMutableList()

                if (!enforceNesineOnly && allowOfflineBulletinFallback && bulletinMatches.size < 15) {
                    val trLocale = Locale.forLanguageTag("tr-TR")
                    val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin(weekNum).matches
                    val existingPairs = bulletinMatches.map {
                        "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
                    }.toMutableSet()
                    for (candidate in fallbackMatches) {
                        if (bulletinMatches.size >= 15) break
                        val pair = "${candidate.homeTeam.lowercase(trLocale)}|${candidate.awayTeam.lowercase(trLocale)}"
                        if (existingPairs.add(pair)) {
                            bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                        }
                    }
                }

                val finalMatches = bulletinMatches.take(15).mapIndexed { index, dto ->
                    dto.copy(matchNumber = index + 1)
                }
                val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                    ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate
                val titleSuffix = payload.weekTitle.takeIf { it.isNotBlank() } ?: "$weekNum. Hafta"

                val bulletin = WeeklyBulletinResponse(
                    weekNumber = weekNum,
                    season = "$season-${season + 1}",
                    bulletinTitle = "Nesine Spor Toto $titleSuffix",
                    startDate = startDate,
                    endDate = endDate,
                    jackpotEstimateTL = 0.0,
                    isActive = true,
                    matches = finalMatches
                )
                _cachedBulletin.value = bulletin
                _activeLiveScoreSource.value = LiveScoreSource.NESINE_RESULTS
                preferences?.edit()?.putString("live_score_source", LiveScoreSource.NESINE_RESULTS.name)?.apply()

                val rawScores = payload.matches.map { it.toMatchLiveScore() }
                if (rawScores.any { it.homeScore != null && it.awayScore != null }) {
                    val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
                    val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(domainMatches, rawScores)
                    if (aligned.isNotEmpty()) {
                        _liveScoreProvider.value = "Nesine.com (${payload.methodLabel})"
                        _liveScores.value = aligned
                        lastNesineAttemptMs = now
                        lastSuccessfulNesineMs = now
                    }
                }
                Result.success(bulletin)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "nesine.com/sportoto adresinden bülten çekilemedi."
                    )
                )
            }
        )
    }

    private suspend fun pollLiveScoresFromNesineLocked(): Result<List<MatchLiveScore>> {
        val now = System.currentTimeMillis()
        if (com.example.network.HeadlessWebViewSupport.isCrashCooldownActive(now)) {
            return Result.success(_liveScores.value)
        }
        val cooldownMs = if (lastSuccessfulNesineMs == lastNesineAttemptMs) 45_000L else 35_000L
        if (lastNesineAttemptMs > 0L && now - lastNesineAttemptMs < cooldownMs) {
            return Result.success(_liveScores.value)
        }
        lastNesineAttemptMs = now
        val scrapeRes = nesineScraper.scrapeResults(NESINE_SPORTOTO_RESULTS_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val rawScores = payload.matches.map { it.toMatchLiveScore() }
                val bulletinMatches = _cachedBulletin.value?.matches.orEmpty().map { it.toDomainTotoMatch() }
                val aligned = if (bulletinMatches.isNotEmpty()) {
                    val matched = CouponPoolEvaluator.filterAndAlignToBulletin(bulletinMatches, rawScores)
                    if (matched.isNotEmpty()) matched else rawScores
                } else {
                    rawScores
                }
                _liveScoreProvider.value = "Nesine.com (${payload.methodLabel})"
                _liveScores.value = aligned
                lastSuccessfulNesineMs = lastNesineAttemptMs
                Result.success(aligned)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "nesine.com/sportoto/mac-sonuclari anlık sonuç çekme başarısız."
                    )
                )
            }
        )
    }

    private suspend fun scrapeNesineResultsAndBulletinLocked(
        updateBulletin: Boolean
    ): Result<WeeklyBulletinResponse> {
        val scrapeRes = nesineScraper.scrapeResults(NESINE_SPORTOTO_RESULTS_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                if ((enforceNesineOnly || !allowOfflineBulletinFallback) &&
                    updateBulletin && payload.matches.size < 15 &&
                    !_cachedBulletin.value.isPlayable()
                ) {
                    return Result.failure(
                        IllegalStateException(
                            "nesine.com/sportoto/mac-sonuclari üzerinde 15 maçlık liste bulunamadı (${payload.matches.size} maç)."
                        )
                    )
                }
                val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                val season = if (month >= 7) year else year - 1
                val weekNum = (payload.weekNumber ?: cal.get(java.util.Calendar.WEEK_OF_YEAR)).coerceIn(1, 9999)

                val existingBulletin = _cachedBulletin.value
                val finalBulletin = if ((updateBulletin && payload.matches.size >= 15) ||
                    (resolvedBilyonerSporTotoScraper == null && (existingBulletin == null || !existingBulletin.isPlayable()))
                ) {
                    val bulletinMatches = payload.matches.take(15).mapIndexed { idx, scraped ->
                        scraped.toBulletinMatchDto().copy(matchNumber = idx + 1)
                    }.toMutableList()

                    if (!enforceNesineOnly && allowOfflineBulletinFallback && bulletinMatches.size < 15) {
                        val trLocale = Locale.forLanguageTag("tr-TR")
                        val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin(weekNum).matches
                        val existingPairs = bulletinMatches.map {
                            "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
                        }.toMutableSet()
                        for (candidate in fallbackMatches) {
                            if (bulletinMatches.size >= 15) break
                            val pair = "${candidate.homeTeam.lowercase(trLocale)}|${candidate.awayTeam.lowercase(trLocale)}"
                            if (existingPairs.add(pair)) {
                                bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                            }
                        }
                    }

                    val finalMatches = bulletinMatches.take(15).mapIndexed { index, dto ->
                        dto.copy(matchNumber = index + 1)
                    }
                    val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                        ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                    val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate
                    val titleSuffix = payload.weekTitle.takeIf { it.isNotBlank() } ?: "$weekNum. Hafta"

                    WeeklyBulletinResponse(
                        weekNumber = weekNum,
                        season = "$season-${season + 1}",
                        bulletinTitle = "Nesine Spor Toto $titleSuffix (${payload.matches.size} Maç)",
                        startDate = startDate,
                        endDate = endDate,
                        jackpotEstimateTL = 0.0,
                        isActive = true,
                        matches = finalMatches
                    ).also { _cachedBulletin.value = it }
                } else {
                    existingBulletin ?: WeeklyBulletinResponse(
                        weekNumber = weekNum,
                        season = "$season-${season + 1}",
                        bulletinTitle = "",
                        startDate = "",
                        endDate = "",
                        jackpotEstimateTL = 0.0,
                        isActive = false,
                        matches = emptyList()
                    )
                }

                val rawScores = payload.matches.map { it.toMatchLiveScore() }
                val domainMatches = finalBulletin.matches.map { it.toDomainTotoMatch() }
                val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(domainMatches, rawScores)
                val effectiveScores = if (aligned.isNotEmpty()) aligned else rawScores

                _activeLiveScoreSource.value = LiveScoreSource.NESINE_RESULTS
                preferences?.edit()?.putString("live_score_source", LiveScoreSource.NESINE_RESULTS.name)?.apply()
                _liveScoreProvider.value = "Nesine.com (${payload.methodLabel})"
                _liveScores.value = effectiveScores
                lastNesineAttemptMs = now
                lastSuccessfulNesineMs = now
                Result.success(finalBulletin)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "nesine.com/sportoto/mac-sonuclari adresinden sonuçlar çekilemedi."
                    )
                )
            }
        )
    }

    override suspend fun scrapeBilyonerLiveScores(
        forceRefresh: Boolean
    ): Result<List<MatchLiveScore>> = withContext(Dispatchers.IO) {
        pollMutex.withLock {
            if (!enforceNesineOnly && LiveScoreSource.BILYONER_LIVE in allowedSources) {
                _activeLiveScoreSource.value = LiveScoreSource.BILYONER_LIVE
                preferences?.edit()?.putString("live_score_source", LiveScoreSource.BILYONER_LIVE.name)?.apply()
            }
            pollLiveScoresFromBilyonerLocked(force = forceRefresh)
        }
    }

    override suspend fun scrapeBilyonerLiveBulletin(): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                scrapeBilyonerLiveBulletinLocked()
            }
        }

    override suspend fun scrapeBilyonerSporTotoBulletin(): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                scrapeBilyonerLiveBulletinLocked()
            }
        }

    private suspend fun pollLiveScoresFromBilyonerLocked(force: Boolean): Result<List<MatchLiveScore>> {
        val now = System.currentTimeMillis()
        if (force) {
            com.example.network.HeadlessWebViewSupport.clearCrashCooldown()
        } else if (com.example.network.HeadlessWebViewSupport.isCrashCooldownActive(now)) {
            return Result.success(_liveScores.value)
        }
        val cooldownMs = if (lastSuccessfulBilyonerMs == lastBilyonerAttemptMs) 45_000L else 35_000L
        if (!force && lastBilyonerAttemptMs > 0L && now - lastBilyonerAttemptMs < cooldownMs) {
            return Result.success(_liveScores.value)
        }
        lastBilyonerAttemptMs = now
        val scrapeRes = bilyonerScraper.scrapeLiveScoreboard(BILYONER_LIVE_SCORE_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val rawScores = payload.matches.mapIndexed { idx, scraped ->
                    scraped.toMatchLiveScore(idx + 1)
                }
                val bulletinMatches = _cachedBulletin.value?.matches.orEmpty().map { it.toDomainTotoMatch() }
                val mergedScores = mergeBilyonerWithBulletin(bulletinMatches, rawScores)
                _liveScoreProvider.value = "Bilyoner.com (${payload.methodLabel})"
                _liveScores.value = mergedScores
                lastSuccessfulBilyonerMs = lastBilyonerAttemptMs
                Result.success(mergedScores)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "bilyoner.com/canli-skor canlı veri çekme başarısız."
                    )
                )
            }
        )
    }

    private fun mergeBilyonerWithBulletin(
        bulletinMatches: List<TotoMatch>,
        rawScores: List<MatchLiveScore>
    ): List<MatchLiveScore> {
        val alignedBulletin = if (bulletinMatches.isNotEmpty()) {
            CouponPoolEvaluator.filterAndAlignToBulletin(bulletinMatches, rawScores)
        } else {
            emptyList()
        }
        val alignedKeys = alignedBulletin.map {
            "${it.homeTeam.lowercase(Locale.ROOT)}|${it.awayTeam.lowercase(Locale.ROOT)}"
        }.toSet()
        val extraBilyoner = rawScores.filter { score ->
            "${score.homeTeam.lowercase(Locale.ROOT)}|${score.awayTeam.lowercase(Locale.ROOT)}" !in alignedKeys
        }.sortedWith(
            compareByDescending<MatchLiveScore> { it.isLive }
                .thenBy { it.status == MatchStatus.FINISHED }
                .thenBy { it.matchDate }
        )
        return alignedBulletin + extraBilyoner
    }

    private suspend fun scrapeBilyonerLiveBulletinLocked(): Result<WeeklyBulletinResponse> {
        val sporTotoScraper = resolvedBilyonerSporTotoScraper
        if (sporTotoScraper != null) {
            val totoRes = sporTotoScraper.scrapeBulletin(BILYONER_SPORTOTO_URL)
            return totoRes.fold(
                onSuccess = { payload ->
                    if ((!allowOfflineBulletinFallback || enforceNesineOnly) && payload.matches.size < 15) {
                        return Result.failure(
                            IllegalStateException(
                                "bilyoner.com/spor-toto üzerinde 15 maçlık bülten bulunamadı (${payload.matches.size} maç)."
                            )
                        )
                    }
                    val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val season = if (month >= 7) year else year - 1
                    val weekNum = (payload.weekNumber ?: cal.get(java.util.Calendar.WEEK_OF_YEAR)).coerceIn(1, 9999)

                    val bulletinMatches = payload.matches.take(15).mapIndexed { idx, scraped ->
                        scraped.toBulletinMatchDto().copy(matchNumber = idx + 1)
                    }.toMutableList()

                    if (allowOfflineBulletinFallback && !enforceNesineOnly && bulletinMatches.size < 15) {
                        val trLocale = Locale.forLanguageTag("tr-TR")
                        val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin(weekNum).matches
                        val existingPairs = bulletinMatches.map {
                            "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
                        }.toMutableSet()
                        for (candidate in fallbackMatches) {
                            if (bulletinMatches.size >= 15) break
                            val pair = "${candidate.homeTeam.lowercase(trLocale)}|${candidate.awayTeam.lowercase(trLocale)}"
                            if (existingPairs.add(pair)) {
                                bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                            }
                        }
                    }

                    val finalMatches = bulletinMatches.take(15).mapIndexed { idx, dto ->
                        dto.copy(matchNumber = idx + 1)
                    }
                    val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                        ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                    val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate
                    val titleSuffix = payload.weekTitle.takeIf { it.isNotBlank() } ?: "$weekNum. Hafta"

                    val bulletin = WeeklyBulletinResponse(
                        weekNumber = weekNum,
                        season = "$season-${season + 1}",
                        bulletinTitle = "Bilyoner Spor Toto $titleSuffix (${finalMatches.size} Maç)",
                        startDate = startDate,
                        endDate = endDate,
                        jackpotEstimateTL = 0.0,
                        isActive = true,
                        matches = finalMatches
                    )
                    _cachedBulletin.value = bulletin

                    val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
                    val existingScores = _liveScores.value
                    val baseScores = if (existingScores.isNotEmpty()) {
                        mergeBilyonerWithBulletin(domainMatches, existingScores)
                    } else {
                        payload.matches.take(15).mapIndexed { idx, scraped ->
                            scraped.copy(matchNumber = idx + 1).toMatchLiveScore()
                        }
                    }
                    if (!enforceNesineOnly && LiveScoreSource.BILYONER_LIVE in allowedSources) {
                        _activeLiveScoreSource.value = LiveScoreSource.BILYONER_LIVE
                        preferences?.edit()?.putString("live_score_source", LiveScoreSource.BILYONER_LIVE.name)?.apply()
                    }
                    _liveScoreProvider.value = "Bilyoner.com Spor Toto (${payload.methodLabel})"
                    _liveScores.value = baseScores
                    lastBilyonerAttemptMs = now
                    lastSuccessfulBilyonerMs = now
                    Result.success(bulletin)
                },
                onFailure = { err ->
                    Result.failure(
                        IllegalStateException(
                            err.message ?: "bilyoner.com/spor-toto üzerinden bülten çekilemedi."
                        )
                    )
                }
            )
        }

        return scrapeBilyonerScoreboardBulletinLocked()
    }

    private suspend fun scrapeBilyonerScoreboardBulletinLocked(): Result<WeeklyBulletinResponse> {
        val scrapeRes = bilyonerScraper.scrapeLiveScoreboard(BILYONER_LIVE_SCORE_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                if ((!allowOfflineBulletinFallback || enforceNesineOnly) && payload.matches.size < 15) {
                    return Result.failure(
                        IllegalStateException(
                            "bilyoner.com/canli-skor üzerinde 15 maçlık bülten için yeterli maç bulunamadı (${payload.matches.size} maç)."
                        )
                    )
                }
                val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                val season = if (month >= 7) year else year - 1
                val weekNum = cal.get(java.util.Calendar.WEEK_OF_YEAR).coerceIn(1, 53)

                val sortedScraped = payload.matches.sortedWith(
                    compareByDescending<BilyonerScrapedMatch> { m ->
                        when {
                            m.league.contains("Süper Lig", ignoreCase = true) -> 3
                            m.league.contains("1. Lig", ignoreCase = true) ||
                                m.league.contains("Türkiye", ignoreCase = true) -> 2
                            m.league.contains("Uluslar", ignoreCase = true) ||
                                m.league.contains("Şampiyonlar", ignoreCase = true) ||
                                m.league.contains("Avrupa", ignoreCase = true) -> 1
                            else -> 0
                        }
                    }.thenByDescending { m ->
                        m.status == MatchStatus.LIVE || m.status == MatchStatus.HALF_TIME
                    }.thenBy { m ->
                        m.status == MatchStatus.FINISHED
                    }
                )

                val bulletinMatches = sortedScraped.take(15).mapIndexed { idx, item ->
                    item.toBulletinMatchDto(idx + 1)
                }.toMutableList()

                if (allowOfflineBulletinFallback && !enforceNesineOnly && bulletinMatches.size < 15) {
                    val trLocale = Locale.forLanguageTag("tr-TR")
                    val fallbackMatches = NetworkModule.createTurkishLeagueSporTotoBulletin(weekNum).matches
                    val existingPairs = bulletinMatches.map {
                        "${it.homeTeam.lowercase(trLocale)}|${it.awayTeam.lowercase(trLocale)}"
                    }.toMutableSet()
                    for (candidate in fallbackMatches) {
                        if (bulletinMatches.size >= 15) break
                        val pair = "${candidate.homeTeam.lowercase(trLocale)}|${candidate.awayTeam.lowercase(trLocale)}"
                        if (existingPairs.add(pair)) {
                            bulletinMatches.add(candidate.copy(matchNumber = bulletinMatches.size + 1))
                        }
                    }
                }

                val finalMatches = bulletinMatches.take(15).mapIndexed { idx, dto ->
                    dto.copy(matchNumber = idx + 1)
                }
                val startDate = finalMatches.firstOrNull()?.matchDate?.substringBefore(" ")
                    ?: SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { timeZone = istanbulTz }.format(Date(now))
                val endDate = finalMatches.lastOrNull()?.matchDate?.substringBefore(" ") ?: startDate

                val bulletin = WeeklyBulletinResponse(
                    weekNumber = weekNum,
                    season = "$season-${season + 1}",
                    bulletinTitle = "Bilyoner Canlı Bülten $weekNum. Hafta (${payload.matches.size} Maç)",
                    startDate = startDate,
                    endDate = endDate,
                    jackpotEstimateTL = 0.0,
                    isActive = true,
                    matches = finalMatches
                )
                _cachedBulletin.value = bulletin

                val rawScores = payload.matches.mapIndexed { idx, scraped ->
                    scraped.toMatchLiveScore(idx + 1)
                }
                val domainMatches = finalMatches.map { it.toDomainTotoMatch() }
                val merged = mergeBilyonerWithBulletin(domainMatches, rawScores)

                if (!enforceNesineOnly && LiveScoreSource.BILYONER_LIVE in allowedSources) {
                    _activeLiveScoreSource.value = LiveScoreSource.BILYONER_LIVE
                    preferences?.edit()?.putString("live_score_source", LiveScoreSource.BILYONER_LIVE.name)?.apply()
                }
                _liveScoreProvider.value = "Bilyoner.com (${payload.methodLabel})"
                _liveScores.value = merged
                lastBilyonerAttemptMs = now
                lastSuccessfulBilyonerMs = now
                Result.success(bulletin)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "bilyoner.com/canli-skor üzerinden veriler çekilemedi."
                    )
                )
            }
        )
    }

    private suspend fun fetchPreviousWeekFromNesineLocked(): Result<PreviousWeekBulletinReport> {
        val scrapeRes = nesineScraper.scrapeResults(NESINE_SPORTOTO_RESULTS_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val finishedMatches = payload.matches.filter {
                    it.status == MatchStatus.FINISHED || (it.homeScore != null && it.awayScore != null)
                }
                if (finishedMatches.isEmpty()) {
                    return Result.failure(
                        IllegalStateException(
                            "nesine.com/sportoto/mac-sonuclari sayfasında henüz tamamlanmış maç sonucu bulunamadı."
                        )
                    )
                }
                val weekNum = payload.weekNumber ?: 1
                val mapped = finishedMatches.mapIndexed { idx, item ->
                    val hg = item.homeScore
                    val ag = item.awayScore
                    val outcome = item.explicitOutcome ?: when {
                        hg == null || ag == null -> null
                        hg > ag -> PredictionOutcome.HOME
                        hg == ag -> PredictionOutcome.DRAW
                        else -> PredictionOutcome.AWAY
                    }
                    PastBulletinMatchResult(
                        matchNumber = item.matchNumber.takeIf { it in 1..15 } ?: (idx + 1),
                        homeTeam = item.homeTeam.uppercase(Locale.forLanguageTag("tr-TR")).trim(),
                        awayTeam = item.awayTeam.uppercase(Locale.forLanguageTag("tr-TR")).trim(),
                        matchDate = item.matchDate,
                        league = item.league,
                        homeScore = hg,
                        awayScore = ag,
                        status = if (item.status == MatchStatus.FINISHED) MatchStatus.FINISHED else item.status,
                        winningOutcome = outcome
                    )
                }
                val firstDay = mapped.firstOrNull()?.matchDate?.substringBefore(" ").orEmpty()
                val lastDay = mapped.lastOrNull()?.matchDate?.substringBefore(" ") ?: firstDay
                val report = PreviousWeekBulletinReport(
                    weekNumber = weekNum,
                    weekLabel = payload.weekTitle.ifBlank { "Nesine Spor Toto ($weekNum. Hafta)" },
                    dateRangeLabel = if (firstDay.isNotBlank()) "$firstDay - $lastDay" else "Güncel Liste",
                    sourceLabel = "Nesine.com (${payload.methodLabel})",
                    matches = mapped.take(15),
                    allFinishedMatches = mapped
                )
                _previousWeekReport.value = report
                Result.success(report)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "nesine.com/sportoto/mac-sonuclari sonuç listesi çekilemedi."
                    )
                )
            }
        )
    }

    private suspend fun fetchPreviousWeekFromBilyonerLocked(): Result<PreviousWeekBulletinReport> {
        val scraper = resolvedBilyonerSporTotoScraper
            ?: return Result.failure(IllegalStateException("Bilyoner Spor Toto kazıyıcı tanımlı değil."))
        val scrapeRes = scraper.scrapeResults(BILYONER_SPORTOTO_RESULTS_URL)
        return scrapeRes.fold(
            onSuccess = { payload ->
                val finishedMatches = payload.matches.filter {
                    it.status == MatchStatus.FINISHED ||
                        (it.homeScore != null && it.awayScore != null) ||
                        it.explicitOutcome != null
                }
                if (finishedMatches.isEmpty()) {
                    return Result.failure(
                        IllegalStateException(
                            "bilyoner.com/spor-toto/sonuclar sayfasında geçen haftaya ait tamamlanmış maç sonucu bulunamadı."
                        )
                    )
                }
                val weekNum = payload.weekNumber ?: 1
                val trLocale = Locale.forLanguageTag("tr-TR")
                val mapped = finishedMatches.mapIndexed { idx, item ->
                    val hg = item.homeScore
                    val ag = item.awayScore
                    val outcome = item.explicitOutcome ?: when {
                        hg == null || ag == null -> null
                        hg > ag -> PredictionOutcome.HOME
                        hg == ag -> PredictionOutcome.DRAW
                        else -> PredictionOutcome.AWAY
                    }
                    PastBulletinMatchResult(
                        matchNumber = item.matchNumber.takeIf { it in 1..15 } ?: (idx + 1),
                        homeTeam = item.homeTeam.uppercase(trLocale).trim(),
                        awayTeam = item.awayTeam.uppercase(trLocale).trim(),
                        matchDate = item.matchDate,
                        league = item.league,
                        homeScore = hg,
                        awayScore = ag,
                        status = if (item.status == MatchStatus.FINISHED || outcome != null) MatchStatus.FINISHED else item.status,
                        winningOutcome = outcome
                    )
                }
                val firstDay = mapped.firstOrNull()?.matchDate?.substringBefore(" ").orEmpty()
                val lastDay = mapped.lastOrNull()?.matchDate?.substringBefore(" ") ?: firstDay
                val report = PreviousWeekBulletinReport(
                    weekNumber = weekNum,
                    weekLabel = payload.weekTitle.ifBlank { "Bilyoner Spor Toto ($weekNum. Hafta)" },
                    dateRangeLabel = if (firstDay.isNotBlank()) "$firstDay - $lastDay" else "Geçen Hafta Sonuçları",
                    sourceLabel = "bilyoner.com/spor-toto/sonuclar (${payload.methodLabel})",
                    matches = mapped.take(15),
                    allFinishedMatches = mapped
                )
                _previousWeekReport.value = report
                Result.success(report)
            },
            onFailure = { err ->
                Result.failure(
                    IllegalStateException(
                        err.message ?: "bilyoner.com/spor-toto/sonuclar üzerinden geçen hafta sonuçları çekilemedi."
                    )
                )
            }
        )
    }

    override suspend fun fetchPreviousWeekFromEspn(
        forceRefresh: Boolean
    ): Result<PreviousWeekBulletinReport> =
        withContext(Dispatchers.IO) {
            pollMutex.withLock {
                if (!forceRefresh) {
                    _previousWeekReport.value?.let { cached ->
                        return@withLock Result.success(cached)
                    }
                }
                if (enforceNesineOnly || LiveScoreSource.ESPN !in allowedSources) {
                    return@withLock fetchPreviousWeekFromNesineLocked()
                }
                fetchPreviousWeekFromEspnLocked()
            }
        }

    private suspend fun fetchPreviousWeekFromEspnLocked(): Result<PreviousWeekBulletinReport> {
        return try {
            val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance(istanbulTz).apply { timeInMillis = now }
            val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = istanbulTz }
            val displayDayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = istanbulTz }

            fun offsetDateRange(fromDaysAgo: Int, toDaysAgo: Int): String {
                val fromCal = (cal.clone() as java.util.Calendar).apply {
                    add(java.util.Calendar.DAY_OF_MONTH, -fromDaysAgo)
                }
                val toCal = (cal.clone() as java.util.Calendar).apply {
                    add(java.util.Calendar.DAY_OF_MONTH, -toDaysAgo)
                }
                return "${compactFmt.format(fromCal.time)}-${compactFmt.format(toCal.time)}"
            }

            val fromDate = displayDayFmt.format(
                (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -12) }.time
            )
            val toDate = displayDayFmt.format(
                (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -1) }.time
            )

            val internationalSlugs = listOf(
                "uefa.nations",
                "fifa.worldq.uefa",
                "uefa.euroq",
                "uefa.euro",
                "fifa.friendly"
            )
            val internationalSlugSet = internationalSlugs.toSet()
            val querySlugs = internationalSlugs + listOf(
                "tur.1",
                "eng.1",
                "esp.1",
                "ger.1",
                "ita.1",
                "fra.1",
                "uefa.champions",
                "uefa.europa"
            )

            var anyHttpSuccess = false
            suspend fun queryFinishedForRange(
                datesParam: String?,
                slugs: List<String> = querySlugs
            ): List<Pair<String, MatchLiveScore>> {
                val responses = coroutineScope {
                    slugs.map { slug ->
                        async {
                            try {
                                val response = espnService.getScoreboard(slug, datesParam, limit = 100)
                                if (response.isSuccessful && response.body() != null) {
                                    slug to response.body()!!
                                } else {
                                    null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (responses.isNotEmpty()) anyHttpSuccess = true

                return responses.flatMap { (slug, body) ->
                    val label = espnLeagueLabel(slug, body.leagues.firstOrNull()?.name)
                    body.events
                        .mapNotNull { it.toMatchLiveScore(label) }
                        .filter {
                            it.status == MatchStatus.FINISHED &&
                                it.homeScore != null &&
                                it.awayScore != null
                        }
                        .map { slug to it }
                }
            }

            val collected = mutableListOf<Pair<String, MatchLiveScore>>()
            // Pass 1: previous week including yesterday (-12d..-1d)
            collected.addAll(queryFinishedForRange(offsetDateRange(12, 1)))

            // Pass 2: wider 21-day window (-21d..-1d) if fewer than 5 finished matches found
            if (collected.distinctBy { "${it.second.homeTeam}|${it.second.awayTeam}|${it.second.matchDate}" }.size < 5) {
                collected.addAll(queryFinishedForRange(offsetDateRange(21, 1)))
            }

            // Pass 3: ESPN latest completed scoreboard (dates=null) when dated window had < 5 matches
            // or when no international/Nations League matches were captured in the dated window.
            val hasInternationalSoFar = collected.any { it.first in internationalSlugSet }
            val distinctCountSoFar = collected
                .distinctBy { "${it.second.homeTeam}|${it.second.awayTeam}|${it.second.matchDate}" }
                .size
            if (distinctCountSoFar < 5) {
                collected.addAll(queryFinishedForRange(null))
            } else if (!hasInternationalSoFar) {
                collected.addAll(queryFinishedForRange(null, internationalSlugs))
            }

            if (!anyHttpSuccess) {
                return Result.failure(
                    IllegalStateException("Geçen haftanın sonuçları için ESPN bağlantısı kurulamadı.")
                )
            }

            val deduplicated = collected
                .distinctBy { "${it.second.homeTeam}|${it.second.awayTeam}|${it.second.matchDate}" }

            val nations = deduplicated.filter { it.first in internationalSlugSet }.map { it.second }
                .sortedWith(
                    compareByDescending<MatchLiveScore> {
                        it.homeTeam.contains("Turkey", ignoreCase = true) ||
                            it.awayTeam.contains("Turkey", ignoreCase = true) ||
                            it.homeTeam.contains("Türkiye", ignoreCase = true) ||
                            it.awayTeam.contains("Türkiye", ignoreCase = true)
                    }.thenByDescending { it.matchDate }
                )
            val superLig = deduplicated.filter { it.first == "tur.1" }.map { it.second }
                .sortedByDescending { it.matchDate }
            val otherClubs = deduplicated.filter { it.first !in internationalSlugSet && it.first != "tur.1" }
                .map { it.second }
                .sortedByDescending { it.matchDate }

            val newestNationDate = nations.maxOfOrNull { it.matchDate }.orEmpty()
            val newestClubDate = (superLig + otherClubs).maxOfOrNull { it.matchDate }.orEmpty()
            val prioritizeNationsLeague = nations.isNotEmpty() &&
                (superLig.isEmpty() || newestNationDate >= newestClubDate || nations.size >= 6)

            val picked = buildList {
                if (prioritizeNationsLeague) {
                    addAll(nations.take(15))
                    if (size < 15) {
                        addAll(
                            superLig
                                .filter { m -> none { it.matchNumber == m.matchNumber } }
                                .take(15 - size)
                        )
                    }
                    if (size < 15) {
                        addAll(
                            otherClubs
                                .filter { m -> none { it.matchNumber == m.matchNumber } }
                                .take(15 - size)
                        )
                    }
                } else {
                    addAll(superLig.take(10))
                    if (size < 15) {
                        addAll(
                            (nations + otherClubs)
                                .filter { m -> none { it.matchNumber == m.matchNumber } }
                                .take(15 - size)
                        )
                    }
                    if (size < 15) {
                        addAll(
                            superLig
                                .filter { m -> none { it.matchNumber == m.matchNumber } }
                                .take(15 - size)
                        )
                    }
                }
            }.distinctBy { "${it.homeTeam}|${it.awayTeam}|${it.matchDate}" }
                .sortedBy { it.matchDate }
                .take(15)

            if (picked.isEmpty()) {
                return Result.failure(
                    IllegalStateException(
                        "Geçen hafta ($fromDate - $toDate) için tamamlanmış ESPN maçı bulunamadı."
                    )
                )
            }

            fun MatchLiveScore.toPastResult(idx: Int) = PastBulletinMatchResult(
                matchNumber = idx + 1,
                homeTeam = homeTeam.uppercase(Locale.ROOT).trim(),
                awayTeam = awayTeam.uppercase(Locale.ROOT).trim(),
                matchDate = formatIsoDateForBulletin(matchDate, istanbulTz),
                league = league.ifBlank { "Türkiye Ligi" },
                homeScore = homeScore,
                awayScore = awayScore,
                status = MatchStatus.FINISHED,
                winningOutcome = winningOutcome
            )

            val mapped = picked.mapIndexed { idx, score -> score.toPastResult(idx) }
            val pickedKeys = picked.map { "${it.homeTeam}|${it.awayTeam}|${it.matchDate}" }.toSet()
            val extraScores = (nations + superLig + otherClubs)
                .filter { "${it.homeTeam}|${it.awayTeam}|${it.matchDate}" !in pickedKeys }
                .distinctBy { "${it.homeTeam}|${it.awayTeam}|${it.matchDate}" }
                .take(30)
            val allMapped = mapped + extraScores.mapIndexed { idx, score ->
                score.toPastResult(mapped.size + idx)
            }

            val currentWeek = _cachedBulletin.value?.weekNumber ?: cal.get(java.util.Calendar.WEEK_OF_YEAR)
            val prevWeek = (currentWeek - 1).coerceAtLeast(1)
            val firstDay = mapped.firstOrNull()?.matchDate?.substringBefore(" ") ?: fromDate
            val lastDay = mapped.lastOrNull()?.matchDate?.substringBefore(" ") ?: toDate
            val weekTitle = if (prioritizeNationsLeague && nations.size >= 4) {
                "Geçen Hafta • Uluslar Ligi ($prevWeek. Hafta)"
            } else {
                "Geçen Hafta ($prevWeek. Hafta)"
            }
            val report = PreviousWeekBulletinReport(
                weekNumber = prevWeek,
                weekLabel = weekTitle,
                dateRangeLabel = "$firstDay - $lastDay",
                sourceLabel = "ESPN • Doğrulanmış Sonuçlar",
                matches = mapped,
                allFinishedMatches = allMapped
            )
            _previousWeekReport.value = report
            Result.success(report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(
                IllegalStateException("Geçen haftanın sonuçları için ESPN bağlantısı kurulamadı.")
            )
        }
    }

    private fun EspnEvent.toBulletinMatchDto(
        matchNumber: Int,
        leagueLabel: String,
        istanbulTz: TimeZone
    ): BulletinMatchDto? {
        val competition = competitions.firstOrNull() ?: return null
        val home = competition.competitors.firstOrNull { it.homeAway.equals("home", ignoreCase = true) }
            ?: return null
        val away = competition.competitors.firstOrNull { it.homeAway.equals("away", ignoreCase = true) }
            ?: return null
        val homeName = home.team.displayName.ifBlank { home.team.shortDisplayName }.trim()
        val awayName = away.team.displayName.ifBlank { away.team.shortDisplayName }.trim()
        if (homeName.isBlank() || awayName.isBlank()) return null

        val statusLabel = when (resolveStatus()) {
            MatchStatus.FINISHED -> "FINISHED"
            MatchStatus.LIVE, MatchStatus.HALF_TIME -> "LIVE"
            MatchStatus.UPCOMING -> "UPCOMING"
        }
        return BulletinMatchDto(
            matchNumber = matchNumber,
            homeTeam = homeName.uppercase(Locale.ROOT),
            awayTeam = awayName.uppercase(Locale.ROOT),
            matchDate = formatIsoDateForBulletin(competition.date ?: date, istanbulTz),
            league = leagueLabel,
            venue = competition.venue?.fullName.orEmpty(),
            status = statusLabel,
            liveOdds = LiveBettingOddsDto(
                homeWinOdd = 2.15,
                drawOdd = 3.25,
                awayWinOdd = 3.30,
                doubleChance1X = 1.32,
                doubleChance12 = 1.28,
                doubleChanceX2 = 1.62
            ),
            probabilities = MatchProbabilitiesDto(
                homeProbability = 42,
                drawProbability = 29,
                awayProbability = 29
            )
        )
    }

    override suspend fun getWeeklyBulletin(forceRefresh: Boolean): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh && _cachedBulletin.value.isPlayable()) {
                    return@withContext Result.success(_cachedBulletin.value!!)
                }

                if (enforceNesineOnly || !allowOfflineBulletinFallback) {
                    val bilyonerAllowed = !enforceNesineOnly && LiveScoreSource.BILYONER_LIVE in allowedSources
                    val preferBilyonerFirst = bilyonerAllowed &&
                        _activeLiveScoreSource.value == LiveScoreSource.BILYONER_LIVE
                    if (preferBilyonerFirst) {
                        val bilyonerBulletin = pollMutex.withLock {
                            scrapeBilyonerLiveBulletinLocked()
                        }
                        if (bilyonerBulletin.isSuccess && bilyonerBulletin.getOrNull().isPlayable()) {
                            return@withContext bilyonerBulletin
                        }
                        val nesineFallback = if (resolvedBilyonerSporTotoScraper == null &&
                            (forceRefresh || !com.example.network.HeadlessWebViewSupport.isCrashCooldownActive())
                        ) {
                            pollMutex.withLock {
                                scrapeNesineBulletinLocked()
                            }
                        } else {
                            null
                        }
                        if (nesineFallback?.isSuccess == true && nesineFallback.getOrNull().isPlayable()) {
                            return@withContext nesineFallback
                        }
                        _cachedBulletin.value = null
                        val err = bilyonerBulletin.exceptionOrNull() ?: nesineFallback?.exceptionOrNull()
                        return@withContext Result.failure(
                            NoNewBulletinException(
                                err?.message ?: "bilyoner.com/spor-toto üzerinden bülten çekilemedi."
                            )
                        )
                    }

                    val nesineBulletin = pollMutex.withLock {
                        scrapeNesineBulletinLocked()
                    }
                    if (nesineBulletin.isSuccess && nesineBulletin.getOrNull().isPlayable()) {
                        return@withContext nesineBulletin
                    }
                    if (bilyonerAllowed && (forceRefresh || !com.example.network.HeadlessWebViewSupport.isCrashCooldownActive())) {
                        val bilyonerFallback = pollMutex.withLock {
                            scrapeBilyonerLiveBulletinLocked()
                        }
                        if (bilyonerFallback.isSuccess && bilyonerFallback.getOrNull().isPlayable()) {
                            return@withContext bilyonerFallback
                        }
                    }
                    _cachedBulletin.value = null
                    val err = nesineBulletin.exceptionOrNull()
                    return@withContext Result.failure(
                        NoNewBulletinException(
                            err?.message ?: "nesine.com/sportoto üzerinden bülten çekilemedi."
                        )
                    )
                }

                // When using the default built-in endpoint with keyless ESPN enabled,
                // automatically pull real scheduled/active matches from ESPN first.
                if (apiService === NetworkModule.apiService &&
                    _activeLiveScoreSource.value == LiveScoreSource.ESPN
                ) {
                    val espnBulletin = pollMutex.withLock {
                        syncTurkishLeagueBulletinFromEspnLocked()
                    }
                    if (espnBulletin.isSuccess && espnBulletin.getOrNull().isPlayable()) {
                        return@withContext espnBulletin
                    }
                } else if (apiService === NetworkModule.apiService &&
                    _activeLiveScoreSource.value == LiveScoreSource.NESINE_RESULTS
                ) {
                    val nesineBulletin = pollMutex.withLock {
                        scrapeNesineBulletinLocked()
                    }
                    if (nesineBulletin.isSuccess && nesineBulletin.getOrNull().isPlayable()) {
                        return@withContext nesineBulletin
                    }
                } else if (apiService === NetworkModule.apiService &&
                    _activeLiveScoreSource.value == LiveScoreSource.BILYONER_LIVE
                ) {
                    val bilyonerBulletin = pollMutex.withLock {
                        scrapeBilyonerLiveBulletinLocked()
                    }
                    if (bilyonerBulletin.isSuccess && bilyonerBulletin.getOrNull().isPlayable()) {
                        return@withContext bilyonerBulletin
                    }
                } else if (apiService === NetworkModule.apiService &&
                    _activeLiveScoreSource.value == LiveScoreSource.IDDAA_SCRAPE
                ) {
                    val iddaaBulletin = pollMutex.withLock {
                        scrapeIddaaLiveBulletinLocked()
                    }
                    if (iddaaBulletin.isSuccess && iddaaBulletin.getOrNull().isPlayable()) {
                        return@withContext iddaaBulletin
                    }
                }

                val response = apiService.getCurrentWeeklyBulletin()
                if (response.isSuccessful && response.body() != null) {
                    val isOfflineInterceptorPayload =
                        response.raw().message == "OK - Offline Resilient Bulletin"
                    val bulletin = if (preferTurkishLeagueBulletin &&
                        apiService === NetworkModule.apiService &&
                        isOfflineInterceptorPayload
                    ) {
                        defaultOfflineBulletin()
                    } else {
                        response.body()!!
                    }
                    if (!bulletin.isPlayable()) {
                        _cachedBulletin.value = null
                        return@withContext Result.failure(NoNewBulletinException())
                    }
                    _cachedBulletin.value = bulletin
                    Result.success(bulletin)
                } else if (apiService === NetworkModule.apiService) {
                    // Fallback to offline standard bulletin only when using default built-in prototype endpoint
                    val fallback = defaultOfflineBulletin()
                    _cachedBulletin.value = fallback
                    Result.success(fallback)
                } else {
                    _cachedBulletin.value = null
                    Result.failure(NoNewBulletinException())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!enforceNesineOnly && allowOfflineBulletinFallback && apiService === NetworkModule.apiService) {
                    val fallback = _cachedBulletin.value?.takeIf { it.isPlayable() }
                        ?: defaultOfflineBulletin()
                    _cachedBulletin.value = fallback
                    Result.success(fallback)
                } else {
                    _cachedBulletin.value = null
                    Result.failure(NoNewBulletinException())
                }
            }
        }

    override suspend fun getBulletinByWeek(weekNumber: Int): Result<WeeklyBulletinResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = apiService.getBulletinByWeek(weekNumber)
                if (response.isSuccessful && response.body() != null) {
                    val isOfflineInterceptorPayload =
                        response.raw().message == "OK - Offline Resilient Bulletin"
                    val bulletin = if (preferTurkishLeagueBulletin &&
                        apiService === NetworkModule.apiService &&
                        isOfflineInterceptorPayload
                    ) {
                        defaultOfflineBulletin(weekNumber)
                    } else {
                        response.body()!!
                    }
                    Result.success(bulletin)
                } else {
                    val fallback = defaultOfflineBulletin(weekNumber)
                    Result.success(fallback)
                }
            } catch (e: Exception) {
                Result.success(defaultOfflineBulletin(weekNumber))
            }
        }

    override suspend fun refreshLiveOdds(): Result<List<BulletinMatchDto>> =
        withContext(Dispatchers.IO) {
            try {
                val response = apiService.getLiveOdds()
                if (response.isSuccessful && response.body() != null) {
                    val updatedMatches = response.body()!!
                    // Update in-memory cached bulletin with refreshed live odds
                    _cachedBulletin.value?.let { current ->
                        _cachedBulletin.value = current.copy(matches = updatedMatches)
                    }
                    Result.success(updatedMatches)
                } else {
                    Result.success(_cachedBulletin.value?.matches ?: emptyList())
                }
            } catch (e: Exception) {
                Result.success(_cachedBulletin.value?.matches ?: emptyList())
            }
        }

    override suspend fun getDomainMatches(forceRefresh: Boolean): Result<List<TotoMatch>> {
        val result = getWeeklyBulletin(forceRefresh)
        return result.fold(
            onSuccess = { bulletin ->
                if (!bulletin.isPlayable()) {
                    Result.failure(NoNewBulletinException())
                } else {
                    Result.success(bulletin.matches.map { it.toDomainTotoMatch() })
                }
            },
            onFailure = { error ->
                Result.failure(
                    if (error is NoNewBulletinException) error else NoNewBulletinException()
                )
            }
        )
    }
}
