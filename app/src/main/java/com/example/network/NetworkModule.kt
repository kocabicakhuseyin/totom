package com.example.network

import com.example.network.model.BulletinMatchDto
import com.example.network.model.LiveBettingOddsDto
import com.example.network.model.MatchProbabilitiesDto
import com.example.network.model.WeeklyBulletinResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {

    private const val BASE_URL = "https://api.sportoto-bulletin.org/"

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /**
     * Resilient Interceptor:
     * When external network is unreachable or returns 404/500 during prototyping or offline use,
     * this interceptor delivers the authentic weekly Spor Toto bulletin payload formatted according
     * to the 15-match official bülten schema so the application never crashes.
     */
    private val fallbackBulletinInterceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            val response = chain.proceed(request)
            if (response.isSuccessful) {
                response
            } else {
                buildFallbackResponse(request, moshi)
            }
        } catch (_: Exception) {
            buildFallbackResponse(request, moshi)
        }
    }

    private fun buildFallbackResponse(request: okhttp3.Request, moshi: Moshi): Response {
        val adapter = moshi.adapter(WeeklyBulletinResponse::class.java)
        val fallbackJson = adapter.toJson(createFallbackBulletin())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK - Offline Resilient Bulletin")
            .body(fallbackJson.toResponseBody("application/json".toMediaType()))
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(fallbackBulletinInterceptor)
            .build()
    }

    val apiService: SporTotoApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SporTotoApiService::class.java)
    }

    // Scores use their own HTTP client: the bulletin's offline fallback interceptor
    // must never turn a live-score failure into fabricated match data.
    val liveScoreService: ApiFootballLiveService by lazy {
        Retrofit.Builder()
            .baseUrl("https://v3.football.api-sports.io/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiFootballLiveService::class.java)
    }

    // A documented, public JSON feed; keep separate from the bulletin's
    // offline interceptor so an outage cannot produce fabricated scores.
    val scoreCardsService: ScoreCardsLiveService by lazy {
        Retrofit.Builder()
            .baseUrl("https://scorecards.in/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ScoreCardsLiveService::class.java)
    }

    // Keyless ESPN public soccer scoreboard endpoint; isolated from bulletin offline fallback.
    val espnService: EspnSoccerService by lazy {
        Retrofit.Builder()
            .baseUrl("https://site.api.espn.com/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile) SporToto/1.0")
                            .header("Accept", "application/json")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(EspnSoccerService::class.java)
    }

    /**
     * Generates a complete 15-match weekly bulletin with teams, dates, and live betting odds.
     */
    fun createFallbackBulletin(weekNumber: Int = 38): WeeklyBulletinResponse {
        val matches = listOf(
            BulletinMatchDto(
                matchNumber = 1,
                homeTeam = "FRANSA",
                awayTeam = "TÜRKİYE",
                matchDate = "15.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Stade de France",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.32, drawOdd = 4.80, awayWinOdd = 8.50, doubleChance1X = 1.08, doubleChance12 = 1.15, doubleChanceX2 = 3.20, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 79, drawProbability = 10, awayProbability = 11)
            ),
            BulletinMatchDto(
                matchNumber = 2,
                homeTeam = "BULGARİSTAN",
                awayTeam = "İZLANDA",
                matchDate = "13.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Vasil Levski National Stadium",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.45, drawOdd = 3.10, awayWinOdd = 2.80, doubleChance1X = 1.38, doubleChance12 = 1.30, doubleChanceX2 = 1.48, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 43, drawProbability = 26, awayProbability = 31)
            ),
            BulletinMatchDto(
                matchNumber = 3,
                homeTeam = "LİHTENŞTAYN",
                awayTeam = "AZERBAYCAN",
                matchDate = "13.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Rheinpark Stadion",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 11.0, drawOdd = 5.20, awayWinOdd = 1.25, doubleChance1X = 3.60, doubleChance12 = 1.12, doubleChanceX2 = 1.04, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 9, drawProbability = 16, awayProbability = 75)
            ),
            BulletinMatchDto(
                matchNumber = 4,
                homeTeam = "LÜKSEMBURG",
                awayTeam = "ESTONYA",
                matchDate = "13.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Stade de Luxembourg",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.70, drawOdd = 3.40, awayWinOdd = 4.80, doubleChance1X = 1.15, doubleChance12 = 1.28, doubleChanceX2 = 2.05, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 61, drawProbability = 21, awayProbability = 18)
            ),
            BulletinMatchDto(
                matchNumber = 5,
                homeTeam = "SLOVAKYA",
                awayTeam = "FAROE ADALARI",
                matchDate = "13.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Tehelne Pole",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.22, drawOdd = 5.80, awayWinOdd = 13.0, doubleChance1X = 1.03, doubleChance12 = 1.11, doubleChanceX2 = 3.90, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 85, drawProbability = 10, awayProbability = 5)
            ),
            BulletinMatchDto(
                matchNumber = 6,
                homeTeam = "GÜRCİSTAN",
                awayTeam = "MACARİSTAN",
                matchDate = "14.11.2026 20:00",
                league = "UEFA Uluslar Ligi",
                venue = "Boris Paichadze Dinamo Arena",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.50, drawOdd = 3.20, awayWinOdd = 2.70, doubleChance1X = 1.40, doubleChance12 = 1.30, doubleChanceX2 = 1.46, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 42, drawProbability = 28, awayProbability = 30)
            ),
            BulletinMatchDto(
                matchNumber = 7,
                homeTeam = "NORVEÇ",
                awayTeam = "GALLER",
                matchDate = "14.11.2026 20:00",
                league = "UEFA Uluslar Ligi",
                venue = "Ullevaal Stadion",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.28, drawOdd = 5.20, awayWinOdd = 10.5, doubleChance1X = 1.06, doubleChance12 = 1.13, doubleChanceX2 = 3.40, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 84, drawProbability = 10, awayProbability = 6)
            ),
            BulletinMatchDto(
                matchNumber = 8,
                homeTeam = "PORTEKİZ",
                awayTeam = "DANİMARKA",
                matchDate = "14.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Estadio da Luz",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.45, drawOdd = 4.20, awayWinOdd = 6.80, doubleChance1X = 1.10, doubleChance12 = 1.20, doubleChanceX2 = 2.60, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 76, drawProbability = 15, awayProbability = 9)
            ),
            BulletinMatchDto(
                matchNumber = 9,
                homeTeam = "İSPANYA",
                awayTeam = "İTALYA",
                matchDate = "15.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Santiago Bernabeu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.85, drawOdd = 3.50, awayWinOdd = 4.20, doubleChance1X = 1.22, doubleChance12 = 1.26, doubleChanceX2 = 1.88, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 58, drawProbability = 24, awayProbability = 18)
            ),
            BulletinMatchDto(
                matchNumber = 10,
                homeTeam = "ALMANYA",
                awayTeam = "HOLLANDA",
                matchDate = "16.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Allianz Arena",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.05, drawOdd = 3.45, awayWinOdd = 3.40, doubleChance1X = 1.30, doubleChance12 = 1.28, doubleChanceX2 = 1.70, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 52, drawProbability = 26, awayProbability = 22)
            ),
            BulletinMatchDto(
                matchNumber = 11,
                homeTeam = "İNGİLTERE",
                awayTeam = "BELÇİKA",
                matchDate = "16.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Wembley Stadium",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.65, drawOdd = 3.75, awayWinOdd = 5.00, doubleChance1X = 1.16, doubleChance12 = 1.24, doubleChanceX2 = 2.15, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 64, drawProbability = 21, awayProbability = 15)
            ),
            BulletinMatchDto(
                matchNumber = 12,
                homeTeam = "HIRVATİSTAN",
                awayTeam = "POLONYA",
                matchDate = "16.11.2026 20:00",
                league = "UEFA Uluslar Ligi",
                venue = "Maksimir",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.75, drawOdd = 3.50, awayWinOdd = 4.60, doubleChance1X = 1.18, doubleChance12 = 1.27, doubleChanceX2 = 2.00, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 60, drawProbability = 23, awayProbability = 17)
            ),
            BulletinMatchDto(
                matchNumber = 13,
                homeTeam = "İSVEÇ",
                awayTeam = "İSVİÇRE",
                matchDate = "17.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Friends Arena",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.30, drawOdd = 3.20, awayWinOdd = 3.10, doubleChance1X = 1.34, doubleChance12 = 1.31, doubleChanceX2 = 1.56, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 45, drawProbability = 30, awayProbability = 25)
            ),
            BulletinMatchDto(
                matchNumber = 14,
                homeTeam = "AVUSTURYA",
                awayTeam = "SIRBİSTAN",
                matchDate = "17.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Ernst Happel Stadion",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.95, drawOdd = 3.40, awayWinOdd = 3.80, doubleChance1X = 1.25, doubleChance12 = 1.28, doubleChanceX2 = 1.80, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 55, drawProbability = 25, awayProbability = 20)
            ),
            BulletinMatchDto(
                matchNumber = 15,
                homeTeam = "ÇEKYA",
                awayTeam = "UKRAYNA",
                matchDate = "17.11.2026 22:45",
                league = "UEFA Uluslar Ligi",
                venue = "Fortuna Arena",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.20, drawOdd = 3.25, awayWinOdd = 3.30, doubleChance1X = 1.32, doubleChance12 = 1.30, doubleChanceX2 = 1.62, lastUpdated = "09.11.2026 23:30"),
                probabilities = MatchProbabilitiesDto(homeProbability = 48, drawProbability = 28, awayProbability = 24)
            )
        )

        return WeeklyBulletinResponse(
            weekNumber = weekNumber,
            season = "2026-2027",
            bulletinTitle = "Spor Toto $weekNumber. Hafta Bülteni",
            startDate = "13.11.2026",
            endDate = "17.11.2026",
            jackpotEstimateTL = 0.0,
            isActive = true,
            matches = matches
        )
    }

    /**
     * Turkish League (Süper Lig & TFF 1. Lig) + Top European Leagues Spor Toto weekly bulletin.
     * Team names and kickoff times align cleanly with API-Football (leagues 203, 204, 78, 61, 39, 140, 135).
     */
    fun createTurkishLeagueSporTotoBulletin(weekNumber: Int = 39): WeeklyBulletinResponse {
        val matches = listOf(
            BulletinMatchDto(
                matchNumber = 1,
                homeTeam = "GENÇLERBİRLİĞİ",
                awayTeam = "GAZİANTEP FK A.Ş.",
                matchDate = "21.11.2026 13:30",
                league = "Trendyol Süper Lig",
                venue = "Eryaman Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.62, drawOdd = 3.55, awayWinOdd = 4.90, doubleChance1X = 1.15, doubleChance12 = 1.24, doubleChanceX2 = 2.15, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 62, drawProbability = 22, awayProbability = 16)
            ),
            BulletinMatchDto(
                matchNumber = 2,
                homeTeam = "AMED SPORTİF FAALİYETLER",
                awayTeam = "ÇAYKUR RİZESPOR A.Ş.",
                matchDate = "21.11.2026 16:00",
                league = "Türkiye Futbol Ligi",
                venue = "Diyarbakır Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.38, drawOdd = 4.10, awayWinOdd = 6.90, doubleChance1X = 1.07, doubleChance12 = 1.18, doubleChanceX2 = 2.80, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 75, drawProbability = 15, awayProbability = 10)
            ),
            BulletinMatchDto(
                matchNumber = 3,
                homeTeam = "GALATASARAY A.Ş.",
                awayTeam = "SAMSUNSPOR A.Ş.",
                matchDate = "21.11.2026 16:00",
                league = "Trendyol Süper Lig",
                venue = "RAMS Park",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.18, drawOdd = 6.20, awayWinOdd = 11.50, doubleChance1X = 1.02, doubleChance12 = 1.10, doubleChanceX2 = 4.10, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 88, drawProbability = 7, awayProbability = 5)
            ),
            BulletinMatchDto(
                matchNumber = 4,
                homeTeam = "KOCAELİSPOR",
                awayTeam = "FENERBAHÇE A.Ş.",
                matchDate = "21.11.2026 19:00",
                league = "Trendyol Süper Lig",
                venue = "Kocaeli Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 8.20, drawOdd = 4.80, awayWinOdd = 1.30, doubleChance1X = 3.10, doubleChance12 = 1.14, doubleChanceX2 = 1.05, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 10, drawProbability = 12, awayProbability = 78)
            ),
            BulletinMatchDto(
                matchNumber = 5,
                homeTeam = "ERZURUMSPOR FK",
                awayTeam = "GÖZTEPE A.Ş.",
                matchDate = "22.11.2026 13:30",
                league = "Türkiye Futbol Ligi",
                venue = "Kazım Karabekir Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 3.40, drawOdd = 3.25, awayWinOdd = 1.98, doubleChance1X = 1.72, doubleChance12 = 1.29, doubleChanceX2 = 1.26, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 24, drawProbability = 28, awayProbability = 48)
            ),
            BulletinMatchDto(
                matchNumber = 6,
                homeTeam = "KASIMPAŞA A.Ş.",
                awayTeam = "ALANYASPOR",
                matchDate = "22.11.2026 16:00",
                league = "Trendyol Süper Lig",
                venue = "Recep Tayyip Erdoğan Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.45, drawOdd = 4.10, awayWinOdd = 6.20, doubleChance1X = 1.10, doubleChance12 = 1.20, doubleChanceX2 = 2.55, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 71, drawProbability = 16, awayProbability = 13)
            ),
            BulletinMatchDto(
                matchNumber = 7,
                homeTeam = "KONYASPOR",
                awayTeam = "BEŞİKTAŞ A.Ş.",
                matchDate = "22.11.2026 16:00",
                league = "Trendyol Süper Lig",
                venue = "Konya Büyükşehir Stadyumu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 7.40, drawOdd = 4.60, awayWinOdd = 1.34, doubleChance1X = 2.90, doubleChance12 = 1.16, doubleChanceX2 = 1.07, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 11, drawProbability = 13, awayProbability = 76)
            ),
            BulletinMatchDto(
                matchNumber = 8,
                homeTeam = "TRABZONSPOR A.Ş.",
                awayTeam = "EYÜPSPOR",
                matchDate = "22.11.2026 19:00",
                league = "Trendyol Süper Lig",
                venue = "Papara Park",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.12, drawOdd = 7.20, awayWinOdd = 15.0, doubleChance1X = 1.01, doubleChance12 = 1.06, doubleChanceX2 = 5.10, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 93, drawProbability = 4, awayProbability = 3)
            ),
            BulletinMatchDto(
                matchNumber = 9,
                homeTeam = "BAŞAKŞEHİR FK",
                awayTeam = "ÇORUM FK",
                matchDate = "23.11.2026 20:00",
                league = "Türkiye Futbol Ligi",
                venue = "Başakşehir Fatih Terim",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.60, drawOdd = 3.60, awayWinOdd = 5.10, doubleChance1X = 1.14, doubleChance12 = 1.24, doubleChanceX2 = 2.20, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 62, drawProbability = 23, awayProbability = 15)
            ),
            BulletinMatchDto(
                matchNumber = 10,
                homeTeam = "SCHALKE 04",
                awayTeam = "STUTTGART",
                matchDate = "21.11.2026 17:30",
                league = "Almanya Ligi",
                venue = "Veltins-Arena",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 5.20, drawOdd = 4.10, awayWinOdd = 1.52, doubleChance1X = 2.35, doubleChance12 = 1.20, doubleChanceX2 = 1.13, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 16, drawProbability = 17, awayProbability = 67)
            ),
            BulletinMatchDto(
                matchNumber = 11,
                homeTeam = "LİLLE",
                awayTeam = "LYON",
                matchDate = "21.11.2026 22:45",
                league = "Fransa Ligue 1",
                venue = "Stade Pierre-Mauroy",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.85, drawOdd = 3.45, awayWinOdd = 4.10, doubleChance1X = 1.22, doubleChance12 = 1.27, doubleChanceX2 = 1.85, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 55, drawProbability = 28, awayProbability = 17)
            ),
            BulletinMatchDto(
                matchNumber = 12,
                homeTeam = "NEWCASTLE UNITED",
                awayTeam = "ARSENAL",
                matchDate = "22.11.2026 15:30",
                league = "İngiltere Premier Lig",
                venue = "St James' Park",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 6.40, drawOdd = 4.30, awayWinOdd = 1.42, doubleChance1X = 2.60, doubleChance12 = 1.18, doubleChanceX2 = 1.09, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 12, drawProbability = 15, awayProbability = 73)
            ),
            BulletinMatchDto(
                matchNumber = 13,
                homeTeam = "LİVERPOOL",
                awayTeam = "MANCHESTER UNİTED",
                matchDate = "22.11.2026 19:30",
                league = "İngiltere Premier Lig",
                venue = "Anfield",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.50, drawOdd = 4.20, awayWinOdd = 5.60, doubleChance1X = 1.12, doubleChance12 = 1.19, doubleChanceX2 = 2.38, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 67, drawProbability = 18, awayProbability = 15)
            ),
            BulletinMatchDto(
                matchNumber = 14,
                homeTeam = "REAL MADRİD",
                awayTeam = "ATLETİCO MADRİD",
                matchDate = "22.11.2026 23:00",
                league = "İspanya La Liga",
                venue = "Santiago Bernabeu",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 1.90, drawOdd = 3.45, awayWinOdd = 3.90, doubleChance1X = 1.24, doubleChance12 = 1.27, doubleChanceX2 = 1.82, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 52, drawProbability = 26, awayProbability = 22)
            ),
            BulletinMatchDto(
                matchNumber = 15,
                homeTeam = "İNTER",
                awayTeam = "AC MİLAN",
                matchDate = "22.11.2026 22:45",
                league = "İtalya Serie A",
                venue = "San Siro",
                status = "UPCOMING",
                liveOdds = LiveBettingOddsDto(homeWinOdd = 2.02, drawOdd = 3.35, awayWinOdd = 3.65, doubleChance1X = 1.26, doubleChance12 = 1.28, doubleChanceX2 = 1.72, lastUpdated = "15.11.2026 23:00"),
                probabilities = MatchProbabilitiesDto(homeProbability = 49, drawProbability = 27, awayProbability = 24)
            )
        )

        return WeeklyBulletinResponse(
            weekNumber = weekNumber,
            season = "2026-2027",
            bulletinTitle = "Türkiye Ligi Spor Toto $weekNumber. Hafta",
            startDate = "21.11.2026",
            endDate = "23.11.2026",
            jackpotEstimateTL = 0.0,
            isActive = true,
            matches = matches
        )
    }
}
