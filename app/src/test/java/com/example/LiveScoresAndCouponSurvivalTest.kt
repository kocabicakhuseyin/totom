package com.example

import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.network.ApiFootballLiveResponse
import com.example.network.ApiFootballLiveService
import com.example.network.NetworkModule
import com.example.network.ScoreCardsLiveService
import com.example.network.ScoreCardsWidgetResponse
import com.example.network.model.toDomainTotoMatch
import com.example.network.toMatchLiveScore
import com.example.repository.SporTotoBulletinRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class LiveScoresAndCouponSurvivalTest {
    @Test
    fun aRealScoreIsDisplayedWithoutEstimatedMinutes() {
        val match = MatchLiveScore(
            matchNumber = 556123,
            homeTeam = "Ev Sahibi",
            awayTeam = "Deplasman",
            homeScore = 2,
            awayScore = 1,
            status = MatchStatus.LIVE,
            minuteText = "67'"
        )
        assertEquals("2 - 1", match.scoreDisplay)
        assertEquals("67'", match.minuteText)
        assertEquals(PredictionOutcome.HOME, match.winningOutcome)
        assertTrue(match.isLive)
    }

    @Test
    fun missingScoreRemainsUnknownRatherThanZero() {
        val match = MatchLiveScore(556124, "A", "B")
        assertEquals("- : -", match.scoreDisplay)
        assertNull(match.winningOutcome)
        assertFalse(match.isLive)
    }

    @Test
    fun missingApiKeyNeverCallsOtherLiveScoreProviders() = runBlocking {
        var fallbackCalls = 0
        val publicService = object : ScoreCardsLiveService {
            override suspend fun getLeague(leagueId: Int): Response<ScoreCardsWidgetResponse> {
                fallbackCalls++
                return Response.success(ScoreCardsWidgetResponse(success = false))
            }
        }
        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            scoreCardsService = publicService,
            trackedLeagueIds = listOf(5)
        )
        val result = repository.pollLiveScores()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("anahtar"))
        assertEquals(0, fallbackCalls)
        assertTrue(repository.liveScores.value.isEmpty())
    }

    @Test
    fun apiFootballIsPrimaryAndRespectsFreePlanPollingCache() = runBlocking {
        val fixtureJson = """
            {
              "errors": [],
              "response": [{
                "fixture": {"id": 44821, "status": {"short": "2H", "elapsed": 73}},
                "teams": {"home": {"name": "Türkiye"}, "away": {"name": "Belçika"}},
                "goals": {"home": 2, "away": 1},
                "league": {"name": "UEFA"}
              }]
            }
        """.trimIndent()
        val parsed = NetworkModule.moshi.adapter(ApiFootballLiveResponse::class.java)
            .fromJson(fixtureJson)!!
        var apiCalls = 0
        val liveApi = object : ApiFootballLiveService {
            override suspend fun getLiveFixtures(
                apiKey: String, live: String
            ): Response<ApiFootballLiveResponse> {
                apiCalls++
                assertEquals("free-test-key", apiKey)
                assertEquals("all", live)
                return Response.success(parsed)
            }
        }
        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreService = liveApi, liveScoreApiKey = "free-test-key"
        )

        val first = repository.pollLiveScores().getOrThrow()
        assertEquals("API-FOOTBALL (Free)", repository.liveScoreProvider.value)
        assertEquals("2 - 1", first.single().scoreDisplay)
        assertEquals("73'", first.single().minuteText)
        assertEquals(1, apiCalls)
        assertEquals(79, repository.freeScoreRequestsRemaining())

        // Consecutive manual refreshes reuse the verified data and cost 0 requests.
        val second = repository.pollLiveScores().getOrThrow()
        assertEquals(first, second)
        assertEquals(1, apiCalls)
        assertEquals(79, repository.freeScoreRequestsRemaining())
    }

    @Test
    fun serviceFailureNeverCreatesMockMatches() = runBlocking {
        val unavailable = object : ScoreCardsLiveService {
            override suspend fun getLeague(leagueId: Int): Response<ScoreCardsWidgetResponse> =
                Response.success(ScoreCardsWidgetResponse(success = false))
        }
        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "", scoreCardsService = unavailable, trackedLeagueIds = listOf(5)
        )
        assertTrue(repository.liveScores.value.isEmpty())
        val result = repository.pollLiveScores()
        assertTrue(result.isFailure)
        assertTrue(repository.liveScores.value.isEmpty())
    }

    @Test
    fun providerOnlyShowsStartedGamesAndDoesNotInventMinutes() {
        val json = """
          {"success":true,"league":{"name":"UEFA Nations League"},
          "live":[{"fixture_id":1,"home_team":"A","away_team":"B","home_score":0,
                   "away_score":0,"state":"live","status":"1H","elapsed":null}],
          "recently_finished":[{"fixture_id":2,"home_team":"C","away_team":"D",
                   "home_score":1,"away_score":0,"state":"finished","status":"FT"}]}
        """.trimIndent()
        val response = NetworkModule.moshi.adapter(ScoreCardsWidgetResponse::class.java).fromJson(json)!!
        assertEquals("—", response.live.single().asLiveScore("League")!!.minuteText)
        assertEquals(MatchStatus.FINISHED, response.recentlyFinished.single().asLiveScore("League")!!.status)
        val upcoming = response.live.single().copy(state = "upcoming", status = "NS")
        assertNull(upcoming.asLiveScore("League"))
    }

    @Test
    fun providerJsonKeepsActualScoreAndElapsedMinute() {
        val json = """
            {
              "errors": [],
              "response": [{
                "fixture": {"id": 44821, "status": {"short": "2H", "elapsed": 73}},
                "teams": {"home": {"name": "Home"}, "away": {"name": "Away"}},
                "goals": {"home": 1, "away": 2},
                "league": {"name": "Test League"}
              }]
            }
        """.trimIndent()
        val parsed = NetworkModule.moshi.adapter(ApiFootballLiveResponse::class.java)
            .fromJson(json)!!
        assertEquals(1, parsed.response.size)
        assertEquals(73, parsed.response.single().fixture.status.elapsed)
        assertEquals(1, parsed.response.single().goals.home)
        assertEquals(2, parsed.response.single().goals.away)
    }

    @Test
    fun bulletinPollingShowsOnlyBulletinMatchesIncludingFinishedOnes() = runBlocking {
        val bulletin = NetworkModule.createFallbackBulletin()
        val queriedDates = mutableListOf<String>()

        val liveApi = object : ApiFootballLiveService {
            override suspend fun getLiveFixtures(
                apiKey: String,
                live: String
            ): Response<ApiFootballLiveResponse> {
                throw AssertionError("getLiveFixtures should not be used when eligible bulletin dates exist")
            }

            override suspend fun getFixturesByDate(
                apiKey: String,
                date: String,
                timezone: String
            ): Response<ApiFootballLiveResponse> {
                queriedDates.add(date)
                val fixtures = when (date) {
                    "2026-11-13" -> listOf(
                        // Bulletin #2 finished
                        com.example.network.ApiFootballLiveFixture(
                            fixture = com.example.network.ApiFootballFixtureInfo(
                                id = 99001,
                                date = "2026-11-13T19:45:00Z",
                                status = com.example.network.ApiFootballFixtureStatus(short = "FT")
                            ),
                            teams = com.example.network.ApiFootballTeams(
                                home = com.example.network.ApiFootballTeam("Bulgaria"),
                                away = com.example.network.ApiFootballTeam("Iceland")
                            ),
                            goals = com.example.network.ApiFootballGoals(2, 1),
                            league = com.example.network.ApiFootballLeague("UEFA Nations League")
                        ),
                        // Unrelated match on same day must be filtered out
                        com.example.network.ApiFootballLiveFixture(
                            fixture = com.example.network.ApiFootballFixtureInfo(
                                id = 99002,
                                date = "2026-11-13T20:00:00Z",
                                status = com.example.network.ApiFootballFixtureStatus(short = "FT")
                            ),
                            teams = com.example.network.ApiFootballTeams(
                                home = com.example.network.ApiFootballTeam("Arsenal"),
                                away = com.example.network.ApiFootballTeam("Chelsea")
                            ),
                            goals = com.example.network.ApiFootballGoals(3, 0),
                            league = com.example.network.ApiFootballLeague("Premier League")
                        )
                    )
                    "2026-11-14" -> listOf(
                        // Bulletin #8 currently live
                        com.example.network.ApiFootballLiveFixture(
                            fixture = com.example.network.ApiFootballFixtureInfo(
                                id = 99003,
                                date = "2026-11-14T19:45:00Z",
                                status = com.example.network.ApiFootballFixtureStatus(short = "2H", elapsed = 68)
                            ),
                            teams = com.example.network.ApiFootballTeams(
                                home = com.example.network.ApiFootballTeam("Portugal"),
                                away = com.example.network.ApiFootballTeam("Denmark")
                            ),
                            goals = com.example.network.ApiFootballGoals(1, 0),
                            league = com.example.network.ApiFootballLeague("UEFA Nations League")
                        )
                    )
                    else -> emptyList()
                }
                return Response.success(ApiFootballLiveResponse(response = fixtures))
            }
        }

        val bulletinApi = object : com.example.network.SporTotoApiService {
            override suspend fun getCurrentWeeklyBulletin() = Response.success(bulletin)
            override suspend fun getBulletinByWeek(weekNumber: Int) = Response.success(bulletin)
            override suspend fun getLiveOdds(weekNumber: Int?) = Response.success(bulletin.matches)
        }

        val repository = SporTotoBulletinRepositoryImpl(
            apiService = bulletinApi,
            liveScoreService = liveApi,
            liveScoreApiKey = "free-test-key"
        )
        // Load bulletin into _cachedBulletin
        assertTrue(repository.getWeeklyBulletin().isSuccess)

        val scores = repository.pollLiveScores().getOrThrow()
        assertTrue(queriedDates.contains("2026-11-13"))
        assertTrue(queriedDates.contains("2026-11-14"))

        // Only the 2 bulletin fixtures remain (Arsenal vs Chelsea filtered out); finished + live included.
        assertEquals(2, scores.size)
        // Live match first (#8 Portugal vs Denmark), finished match second (#2 Bulgaria vs Iceland)
        assertEquals(8, scores[0].matchNumber)
        assertEquals(MatchStatus.LIVE, scores[0].status)
        assertEquals("Portugal", scores[0].homeTeam)

        assertEquals(2, scores[1].matchNumber)
        assertEquals(MatchStatus.FINISHED, scores[1].status)
        assertEquals("Bulgaria", scores[1].homeTeam)
        assertEquals("2 - 1", scores[1].scoreDisplay)
    }

    @Test
    fun turkishLeagueTeamNamesMatchApiFootballFormats() {
        val bulletin = NetworkModule.createTurkishLeagueSporTotoBulletin()
        val domainMatches = bulletin.matches.map { it.toDomainTotoMatch() }
        val rawProviderScores = listOf(
            MatchLiveScore(
                matchNumber = 7001,
                homeTeam = "Genclerbirligi",
                awayTeam = "Gaziantep FK",
                homeScore = 1,
                awayScore = 1,
                status = MatchStatus.HALF_TIME,
                minuteText = "İY",
                matchDate = "2026-11-21T10:30:00Z",
                league = "Süper Lig"
            ),
            MatchLiveScore(
                matchNumber = 7002,
                homeTeam = "Amed",
                awayTeam = "Rizespor",
                homeScore = 2,
                awayScore = 0,
                status = MatchStatus.LIVE,
                minuteText = "62'",
                matchDate = "2026-11-21T13:00:00Z",
                league = "1. Lig"
            ),
            MatchLiveScore(
                matchNumber = 7003,
                homeTeam = "Galatasaray",
                awayTeam = "Samsunspor",
                homeScore = 3,
                awayScore = 1,
                status = MatchStatus.FINISHED,
                minuteText = "MS",
                matchDate = "2026-11-21T13:00:00Z",
                league = "Süper Lig"
            ),
            MatchLiveScore(
                matchNumber = 7004,
                homeTeam = "Istanbul Basaksehir",
                awayTeam = "Corum",
                homeScore = 2,
                awayScore = 2,
                status = MatchStatus.LIVE,
                minuteText = "80'",
                matchDate = "2026-11-23T17:00:00Z",
                league = "Süper Lig"
            )
        )

        val aligned = com.example.model.CouponPoolEvaluator.filterAndAlignToBulletin(
            matches = domainMatches,
            liveScores = rawProviderScores
        )
        assertEquals(4, aligned.size)
        val byMatch = aligned.associateBy { it.matchNumber }
        assertEquals(MatchStatus.HALF_TIME, byMatch[1]?.status)
        assertEquals(MatchStatus.LIVE, byMatch[2]?.status)
        assertEquals(MatchStatus.FINISHED, byMatch[3]?.status)
        assertEquals(MatchStatus.LIVE, byMatch[9]?.status)
    }

    @Test
    fun syncTurkishLeagueBulletinFromApiBuildsPlayableBulletinAndTracksScores() = runBlocking {
        val queriedLeagues = mutableListOf<Int>()
        val superLigFixtures = (1..10).map { idx ->
            com.example.network.ApiFootballLiveFixture(
                fixture = com.example.network.ApiFootballFixtureInfo(
                    id = 80000 + idx,
                    date = "2026-11-21T16:00:00+03:00",
                    status = com.example.network.ApiFootballFixtureStatus(
                        short = if (idx == 1) "2H" else "NS",
                        elapsed = if (idx == 1) 71 else null
                    )
                ),
                teams = com.example.network.ApiFootballTeams(
                    home = com.example.network.ApiFootballTeam(if (idx == 1) "Galatasaray" else "SüperEv$idx"),
                    away = com.example.network.ApiFootballTeam(if (idx == 1) "Fenerbahce" else "SüperDep$idx")
                ),
                goals = com.example.network.ApiFootballGoals(
                    home = if (idx == 1) 2 else null,
                    away = if (idx == 1) 1 else null
                ),
                league = com.example.network.ApiFootballLeague(
                    name = "Süper Lig",
                    id = 203,
                    country = "Turkey"
                )
            )
        }
        val firstLigFixtures = (1..5).map { idx ->
            com.example.network.ApiFootballLiveFixture(
                fixture = com.example.network.ApiFootballFixtureInfo(
                    id = 81000 + idx,
                    date = "2026-11-22T13:30:00+03:00",
                    status = com.example.network.ApiFootballFixtureStatus(
                        short = if (idx == 1) "FT" else "NS"
                    )
                ),
                teams = com.example.network.ApiFootballTeams(
                    home = com.example.network.ApiFootballTeam(if (idx == 1) "Amed" else "BirinciEv$idx"),
                    away = com.example.network.ApiFootballTeam(if (idx == 1) "Erzurum BB" else "BirinciDep$idx")
                ),
                goals = com.example.network.ApiFootballGoals(
                    home = if (idx == 1) 1 else null,
                    away = if (idx == 1) 0 else null
                ),
                league = com.example.network.ApiFootballLeague(
                    name = "1. Lig",
                    id = 204,
                    country = "Turkey"
                )
            )
        }

        val liveApi = object : ApiFootballLiveService {
            override suspend fun getLiveFixtures(
                apiKey: String,
                live: String
            ): Response<ApiFootballLiveResponse> = Response.success(ApiFootballLiveResponse())

            override suspend fun getFixturesByLeague(
                apiKey: String,
                leagueId: Int,
                season: Int,
                fromDate: String?,
                toDate: String?,
                timezone: String
            ): Response<ApiFootballLiveResponse> {
                assertEquals("free-test-key", apiKey)
                assertEquals("Europe/Istanbul", timezone)
                queriedLeagues.add(leagueId)
                val fixtures = when (leagueId) {
                    203 -> superLigFixtures
                    204 -> firstLigFixtures
                    else -> emptyList()
                }
                return Response.success(ApiFootballLiveResponse(response = fixtures))
            }
        }

        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreService = liveApi,
            liveScoreApiKey = "free-test-key",
            preferTurkishLeagueBulletin = true
        )

        val bulletin = repository.syncTurkishLeagueBulletinFromApi().getOrThrow()
        assertEquals(listOf(203, 204), queriedLeagues)
        assertEquals(15, bulletin.matches.size)
        assertEquals("GALATASARAY", bulletin.matches.first().homeTeam)
        assertEquals("FENERBAHCE", bulletin.matches.first().awayTeam)
        assertTrue(bulletin.matches.any { it.homeTeam == "AMED" && it.awayTeam == "ERZURUM BB" })

        val trackedScores = repository.liveScores.value
        assertEquals(2, trackedScores.size)
        assertEquals(MatchStatus.LIVE, trackedScores[0].status)
        assertEquals("2 - 1", trackedScores[0].scoreDisplay)
        assertEquals(MatchStatus.FINISHED, trackedScores[1].status)
        assertEquals("1 - 0", trackedScores[1].scoreDisplay)
        assertEquals(78, repository.freeScoreRequestsRemaining())
    }

    @Test
    fun fetchPreviousWeekBulletinAndResultsReturnsVerifiedFinishedOutcomesAndCaches() = runBlocking {
        var apiCalls = 0
        val superLigPast = (1..10).map { idx ->
            val goals = when (idx) {
                1 -> 2 to 0 // HOME (1)
                2 -> 1 to 1 // DRAW (X)
                else -> 0 to 3 // AWAY (2)
            }
            com.example.network.ApiFootballLiveFixture(
                fixture = com.example.network.ApiFootballFixtureInfo(
                    id = 91000 + idx,
                    date = "2026-11-08T16:00:00+03:00",
                    status = com.example.network.ApiFootballFixtureStatus(short = "FT")
                ),
                teams = com.example.network.ApiFootballTeams(
                    home = com.example.network.ApiFootballTeam("SL_Home_$idx"),
                    away = com.example.network.ApiFootballTeam("SL_Away_$idx")
                ),
                goals = com.example.network.ApiFootballGoals(goals.first, goals.second),
                league = com.example.network.ApiFootballLeague(name = "Süper Lig", id = 203)
            )
        }
        val firstLigPast = (1..5).map { idx ->
            com.example.network.ApiFootballLiveFixture(
                fixture = com.example.network.ApiFootballFixtureInfo(
                    id = 92000 + idx,
                    date = "2026-11-09T20:00:00+03:00",
                    status = com.example.network.ApiFootballFixtureStatus(short = "FT")
                ),
                teams = com.example.network.ApiFootballTeams(
                    home = com.example.network.ApiFootballTeam("TFF_Home_$idx"),
                    away = com.example.network.ApiFootballTeam("TFF_Away_$idx")
                ),
                goals = com.example.network.ApiFootballGoals(1, 0),
                league = com.example.network.ApiFootballLeague(name = "1. Lig", id = 204)
            )
        }

        val liveApi = object : ApiFootballLiveService {
            override suspend fun getLiveFixtures(apiKey: String, live: String) =
                Response.success(ApiFootballLiveResponse())

            override suspend fun getFixturesByLeague(
                apiKey: String,
                leagueId: Int,
                season: Int,
                fromDate: String?,
                toDate: String?,
                timezone: String
            ): Response<ApiFootballLiveResponse> {
                apiCalls++
                assertTrue(!fromDate.isNullOrBlank() && !toDate.isNullOrBlank() && fromDate!! < toDate!!)
                val list = when (leagueId) {
                    203 -> superLigPast
                    204 -> firstLigPast
                    else -> emptyList()
                }
                return Response.success(ApiFootballLiveResponse(response = list))
            }
        }

        val repo = SporTotoBulletinRepositoryImpl(
            liveScoreService = liveApi,
            liveScoreApiKey = "free-test-key",
            preferTurkishLeagueBulletin = true
        )

        val report = repo.fetchPreviousWeekBulletinAndResults(forceRefresh = false).getOrThrow()
        assertEquals(15, report.matches.size)
        assertEquals(2, apiCalls)
        assertEquals(PredictionOutcome.HOME, report.matches[0].winningOutcome)
        assertEquals("2 - 0", report.matches[0].scoreDisplay)
        assertEquals(PredictionOutcome.DRAW, report.matches[1].winningOutcome)
        assertEquals("1 - 1", report.matches[1].scoreDisplay)
        assertEquals(PredictionOutcome.AWAY, report.matches[2].winningOutcome)
        assertEquals("0 - 3", report.matches[2].scoreDisplay)

        // Second call without forceRefresh returns cached report without spending quota
        val cached = repo.fetchPreviousWeekBulletinAndResults(forceRefresh = false).getOrThrow()
        assertEquals(report, cached)
        assertEquals(2, apiCalls)
    }

    @Test
    fun espnScoreboardJsonParsesLiveHalftimeFinishedAndSkipsUpcoming() {
        val json = """
            {
              "leagues": [{"id": "1", "name": "Turkish Super Lig", "slug": "tur.1"}],
              "events": [
                {
                  "id": "701001",
                  "date": "2026-11-21T13:00Z",
                  "competitions": [{
                    "id": "701001",
                    "date": "2026-11-21T13:00Z",
                    "competitors": [
                      {"id": "1", "homeAway": "home", "score": "0", "team": {"displayName": "Galatasaray"}},
                      {"id": "2", "homeAway": "away", "score": "0", "team": {"displayName": "Samsunspor"}}
                    ],
                    "status": {"displayClock": "0'", "type": {"name": "STATUS_SCHEDULED", "state": "pre", "completed": false}}
                  }]
                },
                {
                  "id": "701002",
                  "date": "2026-11-21T10:30Z",
                  "competitions": [{
                    "id": "701002",
                    "date": "2026-11-21T10:30Z",
                    "competitors": [
                      {"id": "3", "homeAway": "home", "score": "1", "team": {"displayName": "Genclerbirligi"}},
                      {"id": "4", "homeAway": "away", "score": "1", "team": {"displayName": "Gaziantep FK"}}
                    ],
                    "status": {"displayClock": "45'", "type": {"name": "STATUS_HALFTIME", "state": "in", "completed": false}}
                  }]
                },
                {
                  "id": "701003",
                  "date": "2026-11-21T16:00Z",
                  "competitions": [{
                    "id": "701003",
                    "date": "2026-11-21T16:00Z",
                    "competitors": [
                      {"id": "5", "homeAway": "home", "score": "1", "team": {"displayName": "Kocaelispor"}},
                      {"id": "6", "homeAway": "away", "score": "2", "team": {"displayName": "Fenerbahce"}}
                    ],
                    "status": {"displayClock": "74'", "type": {"name": "STATUS_IN_PROGRESS", "state": "in", "completed": false}}
                  }]
                },
                {
                  "id": "701004",
                  "date": "2026-11-20T17:00Z",
                  "competitions": [{
                    "id": "701004",
                    "date": "2026-11-20T17:00Z",
                    "competitors": [
                      {"id": "7", "homeAway": "home", "score": "3", "team": {"displayName": "Trabzonspor"}},
                      {"id": "8", "homeAway": "away", "score": "0", "team": {"displayName": "Eyupspor"}}
                    ],
                    "status": {"displayClock": "90'", "type": {"name": "STATUS_FULL_TIME", "state": "post", "completed": true}}
                  }]
                }
              ]
            }
        """.trimIndent()

        val parsed = NetworkModule.moshi
            .adapter(com.example.network.EspnScoreboardResponse::class.java)
            .fromJson(json)!!
        assertEquals(4, parsed.events.size)

        val scores = parsed.events.mapNotNull {
            it.toMatchLiveScore("Trendyol Süper Lig")
        }
        assertEquals(3, scores.size)
        assertEquals(MatchStatus.HALF_TIME, scores[0].status)
        assertEquals("İY", scores[0].minuteText)
        assertEquals(MatchStatus.LIVE, scores[1].status)
        assertEquals("74'", scores[1].minuteText)
        assertEquals("1 - 2", scores[1].scoreDisplay)
        assertEquals(MatchStatus.FINISHED, scores[2].status)
        assertEquals("MS", scores[2].minuteText)
        assertEquals(PredictionOutcome.HOME, scores[2].winningOutcome)
    }

    @Test
    fun espnProviderPollsSyncsBulletinAndLoadsPreviousWeekWithoutApiKey() = runBlocking {
        val bulletin = NetworkModule.createTurkishLeagueSporTotoBulletin()
        val queriedLeagues = mutableListOf<String>()

        val espnFake = object : com.example.network.EspnSoccerService {
            override suspend fun getScoreboard(
                league: String,
                dates: String?
            ): Response<com.example.network.EspnScoreboardResponse> {
                queriedLeagues.add(league)
                val events = if (league == "tur.1") {
                    listOf(
                        com.example.network.EspnEvent(
                            id = "5001",
                            date = "2026-11-21T13:00Z",
                            competitions = listOf(
                                com.example.network.EspnCompetition(
                                    id = "5001",
                                    date = "2026-11-21T13:00Z",
                                    competitors = listOf(
                                        com.example.network.EspnCompetitor(
                                            homeAway = "home",
                                            score = "2",
                                            team = com.example.network.EspnTeam(displayName = "Galatasaray")
                                        ),
                                        com.example.network.EspnCompetitor(
                                            homeAway = "away",
                                            score = "0",
                                            team = com.example.network.EspnTeam(displayName = "Samsunspor")
                                        )
                                    ),
                                    status = com.example.network.EspnEventStatus(
                                        displayClock = "90'",
                                        type = com.example.network.EspnStatusType(
                                            name = "STATUS_FULL_TIME",
                                            state = "post",
                                            completed = true
                                        )
                                    )
                                )
                            )
                        )
                    )
                } else {
                    emptyList()
                }
                return Response.success(
                    com.example.network.EspnScoreboardResponse(
                        leagues = listOf(com.example.network.EspnLeagueInfo(name = "Turkish Super Lig", slug = league)),
                        events = events
                    )
                )
            }
        }

        val bulletinApi = object : com.example.network.SporTotoApiService {
            override suspend fun getCurrentWeeklyBulletin() = Response.success(bulletin)
            override suspend fun getBulletinByWeek(weekNumber: Int) = Response.success(bulletin)
            override suspend fun getLiveOdds(weekNumber: Int?) = Response.success(bulletin.matches)
        }

        val repo = SporTotoBulletinRepositoryImpl(
            apiService = bulletinApi,
            liveScoreApiKey = "",
            preferTurkishLeagueBulletin = true,
            espnService = espnFake,
            espnLeagueSlugs = listOf("tur.1", "eng.1"),
            initialSource = com.example.repository.LiveScoreSource.ESPN
        )

        assertFalse(repo.requiresApiKeyForActiveSource())
        assertTrue(repo.getWeeklyBulletin().isSuccess)

        val live = repo.pollLiveScores().getOrThrow()
        assertEquals(1, live.size)
        assertEquals(3, live[0].matchNumber)
        assertEquals("2 - 0", live[0].scoreDisplay)
        assertEquals(MatchStatus.FINISHED, live[0].status)
        assertTrue(queriedLeagues.containsAll(listOf("tur.1", "eng.1")))

        val synced = repo.syncTurkishLeagueBulletinFromApi().getOrThrow()
        assertEquals(15, synced.matches.size)
        assertEquals("GALATASARAY", synced.matches.first().homeTeam)

        val prev = repo.fetchPreviousWeekBulletinAndResults(forceRefresh = true).getOrThrow()
        assertEquals(1, prev.matches.size)
        assertEquals("GALATASARAY", prev.matches[0].homeTeam)
        assertEquals(PredictionOutcome.HOME, prev.matches[0].winningOutcome)
        assertTrue(prev.sourceLabel.contains("ESPN"))
    }
}
