package com.example

import com.example.model.CouponPoolEvaluator
import com.example.model.MatchLiveScore
import com.example.model.MatchProbability
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.network.BILYONER_LIVE_SCORE_URL
import com.example.network.BilyonerLiveScraper
import com.example.network.BilyonerScrapedMatch
import com.example.network.NesineSporTotoScrapedMatch
import com.example.network.NesineSporTotoScraper
import com.example.network.NesineSporTotoScraper.NesineWebViewPayload
import com.example.repository.LiveScoreSource
import com.example.repository.SporTotoBulletinRepositoryImpl
import com.example.viewmodel.TotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BilyonerLiveScraperTest {

    @Test
    fun parseHtmlDocumentExtractsExactBilyonerStickyBoxMarkup() {
        val html = """
            <!doctype html>
            <html>
            <body>
            <div class="block-ui--theme-dark">
              <div class="sticky-box">
                <div class="event-content__header">
                  <h2 class="event-content__title">UEFA Uluslar Ligi, Lig B, 2. G</h2>
                </div>
                <a href="/mac-karti/futbol/3303421?islivescore=true" class="event-content__row" title="Ukrayna - Gürcistan">
                  <div class="event-content__cell event-content__cell--status">22:45</div>
                  <div class="event-content__cell event-content__cell--home-team">Ukrayna</div>
                  <div class="event-content__cell event-content__cell--score">-</div>
                  <div class="event-content__cell event-content__cell--away-team">Gürcistan</div>
                  <div class="event-content__cell event-content__cell--info"><i class="ic_live_stream"></i></div>
                </a>
                <a href="/mac-karti/futbol/3303422?islivescore=true" class="event-content__row" title="Arnavutluk - Çekya">
                  <div class="event-content__cell event-content__cell--status">68'</div>
                  <div class="event-content__cell event-content__cell--home-team">Arnavutluk</div>
                  <div class="event-content__cell event-content__cell--score">2 - 1</div>
                  <div class="event-content__cell event-content__cell--away-team">Çekya</div>
                  <div class="event-content__cell event-content__cell--info">İY: 1-0</div>
                </a>
              </div>
              <div class="sticky-box">
                <div class="event-content__header">
                  <h2 class="event-content__title">Trendyol Süper Lig</h2>
                </div>
                <a href="/mac-karti/futbol/3314007?islivescore=true" class="event-content__row">
                  <div class="event-content__cell event-content__cell--status">İY</div>
                  <div class="event-content__cell event-content__cell--home-team">Galatasaray</div>
                  <div class="event-content__cell event-content__cell--score">1 - 0</div>
                  <div class="event-content__cell event-content__cell--away-team">Samsunspor</div>
                  <div class="event-content__cell event-content__cell--info">İY: 1-0</div>
                </a>
                <a href="/mac-karti/futbol/3319509?islivescore=true" class="event-content__row">
                  <div class="event-content__cell event-content__cell--status">MS</div>
                  <div class="event-content__cell event-content__cell--home-team">Trabzonspor</div>
                  <div class="event-content__cell event-content__cell--score">3 - 0</div>
                  <div class="event-content__cell event-content__cell--away-team">Eyüpspor</div>
                  <div class="event-content__cell event-content__cell--info">İY: 2-0</div>
                </a>
                <a title="Hav&amp;Waterloo - Bracknell Town" href="/mac-karti/futbol/3386702?islivescore=true" class="event-content__row">
                  <div class="event-content__cell event-content__cell--status">22:45</div>
                  <div class="event-content__cell event-content__cell--home-team"><span>Hav&amp;Waterloo</span></div>
                  <div class="event-content__cell event-content__cell--score">-</div>
                  <div class="event-content__cell event-content__cell--away-team"><span>Bracknell Tow</span></div>
                  <div class="event-content__cell event-content__cell--info"></div>
                </a>
              </div>
            </div>
            </body>
            </html>
        """.trimIndent()

        val matches = BilyonerLiveScraper.parseHtmlDocument(html)
        assertEquals(5, matches.size)

        val upcoming = matches[0]
        assertEquals("3303421", upcoming.externalId)
        assertEquals("UEFA Uluslar Ligi, Lig B, 2. G", upcoming.league)
        assertEquals("Ukrayna", upcoming.homeTeam)
        assertEquals("Gürcistan", upcoming.awayTeam)
        assertEquals(null, upcoming.homeScore)
        assertEquals(null, upcoming.awayScore)
        assertEquals(MatchStatus.UPCOMING, upcoming.status)
        assertEquals("22:45", upcoming.minuteText)
        assertTrue(upcoming.hasLiveStream)

        val live = matches[1]
        assertEquals("3303422", live.externalId)
        assertEquals("UEFA Uluslar Ligi, Lig B, 2. G", live.league)
        assertEquals(2, live.homeScore)
        assertEquals(1, live.awayScore)
        assertEquals(MatchStatus.LIVE, live.status)
        assertEquals("68'", live.minuteText)
        assertEquals("İY: 1-0", live.halfTimeText)

        val halfTime = matches[2]
        assertEquals("Trendyol Süper Lig", halfTime.league)
        assertEquals("Galatasaray", halfTime.homeTeam)
        assertEquals("Samsunspor", halfTime.awayTeam)
        assertEquals(1, halfTime.homeScore)
        assertEquals(0, halfTime.awayScore)
        assertEquals(MatchStatus.HALF_TIME, halfTime.status)
        assertEquals("İY", halfTime.minuteText)

        val finished = matches[3]
        assertEquals("3319509", finished.externalId)
        assertEquals("Trabzonspor", finished.homeTeam)
        assertEquals("Eyüpspor", finished.awayTeam)
        assertEquals(3, finished.homeScore)
        assertEquals(0, finished.awayScore)
        assertEquals(MatchStatus.FINISHED, finished.status)
        assertEquals("MS", finished.minuteText)

        val untruncated = matches[4]
        assertEquals("3386702", untruncated.externalId)
        assertEquals("Hav&Waterloo", untruncated.homeTeam)
        assertEquals("Bracknell Town", untruncated.awayTeam)
    }

    @Test
    fun parseWebViewExtractorPayloadHandlesBilyonerDomAndCapturedJson() {
        val domPayload = """
            {
              "kind": "bilyoner_dom",
              "rows": [
                {
                  "league": "Premier Lig",
                  "href": "/mac-karti/futbol/4400112?islivescore=true",
                  "status": "72'",
                  "home": "Liverpool",
                  "score": "2 - 2",
                  "away": "Manchester United",
                  "info": "İY: 1-1",
                  "hasStream": true
                },
                {
                  "league": "La Liga",
                  "href": "/mac-karti/futbol/4400113?islivescore=true",
                  "status": "MS",
                  "home": "Sevilla",
                  "score": "1 - 0",
                  "away": "Real Betis",
                  "info": "İY: 0-0",
                  "hasStream": false
                }
              ]
            }
        """.trimIndent()

        val domMatches = BilyonerLiveScraper.parseWebViewExtractorPayload(domPayload)
        assertEquals(2, domMatches.size)
        assertEquals("4400112", domMatches[0].externalId)
        assertEquals("Premier Lig", domMatches[0].league)
        assertEquals(MatchStatus.LIVE, domMatches[0].status)
        assertEquals("72'", domMatches[0].minuteText)
        assertTrue(domMatches[0].hasLiveStream)
        assertEquals(MatchStatus.FINISHED, domMatches[1].status)
        assertEquals("MS", domMatches[1].minuteText)

        val jsonPayload = """
            {
              "kind": "captured_json",
              "raw": "{\"events\":[{\"id\":\"99812\",\"leagueName\":\"Serie A\",\"homeTeam\":{\"name\":\"Atalanta\"},\"awayTeam\":{\"name\":\"Inter\"},\"homeScore\":0,\"awayScore\":2,\"status\":\"FT\"}]}"
            }
        """.trimIndent()
        val jsonMatches = BilyonerLiveScraper.parseWebViewExtractorPayload(jsonPayload)
        assertEquals(1, jsonMatches.size)
        assertEquals("99812", jsonMatches[0].externalId)
        assertEquals("Serie A", jsonMatches[0].league)
        assertEquals("Atalanta", jsonMatches[0].homeTeam)
        assertEquals("Inter", jsonMatches[0].awayTeam)
        assertEquals(0, jsonMatches[0].homeScore)
        assertEquals(2, jsonMatches[0].awayScore)
        assertEquals(MatchStatus.FINISHED, jsonMatches[0].status)
    }

    @Test
    fun scrapeLiveScoreboardFallsBackToWebViewOnHttpBlockAndFailsHonestlyWhenEmpty() = runBlocking {
        var webCalls = 0
        val scraper = BilyonerLiveScraper(
            httpFetcher = { url ->
                assertEquals(BILYONER_LIVE_SCORE_URL, url)
                Result.failure(IllegalStateException("bilyoner.com HTTP 403"))
            },
            webViewFetcher = { url ->
                webCalls++
                assertEquals(BILYONER_LIVE_SCORE_URL, url)
                Result.success(
                    listOf(
                        BilyonerScrapedMatch(
                            externalId = "5501",
                            homeTeam = "Galatasaray",
                            awayTeam = "Beşiktaş",
                            homeScore = 2,
                            awayScore = 1,
                            status = MatchStatus.LIVE,
                            minuteText = "83'",
                            league = "Trendyol Süper Lig"
                        )
                    )
                )
            }
        )

        val res = scraper.scrapeLiveScoreboard().getOrThrow()
        assertEquals(1, webCalls)
        assertEquals("WebView JS/DOM", res.methodLabel)
        assertEquals(1, res.matches.size)
        assertEquals("Galatasaray", res.matches[0].homeTeam)

        val emptyScraper = BilyonerLiveScraper(
            httpFetcher = { Result.success("<html><body><div id='root'></div></body></html>") },
            webViewFetcher = { Result.success(emptyList()) }
        )
        assertTrue(emptyScraper.scrapeLiveScoreboard().isFailure)
    }

    @Test
    fun repositorySupportsBilyonerLiveScoresAndBulletinWithStrictSourceAllowlist() = runBlocking {
        val nesineBulletin15 = listOf(
            "Galatasaray A.Ş." to "Samsunspor A.Ş.",
            "Kocaelispor" to "Fenerbahçe A.Ş.",
            "Gençlerbirliği" to "Gaziantep FK A.Ş.",
            "Amed Sportif Faaliyetler" to "Çaykur Rizespor A.Ş.",
            "Erzurumspor FK" to "Göztepe A.Ş.",
            "Kasımpaşa A.Ş." to "Alanyaspor",
            "Konyaspor" to "Beşiktaş A.Ş.",
            "Trabzonspor A.Ş." to "Eyüpspor",
            "Başakşehir FK" to "Çorum FK",
            "Schalke 04" to "Stuttgart",
            "Lille" to "Lyon",
            "Newcastle United" to "Arsenal",
            "Liverpool" to "Manchester United",
            "Sevilla" to "Real Betis",
            "Atalanta" to "Inter"
        ).mapIndexed { index, (h, a) ->
            NesineSporTotoScrapedMatch(
                matchNumber = index + 1,
                homeTeam = h,
                awayTeam = a,
                status = MatchStatus.UPCOMING,
                minuteText = "19:00",
                homeProb = 52,
                drawProb = 26,
                awayProb = 22
            )
        }

        val nesineScraper = NesineSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = {
                Result.success(
                    NesineWebViewPayload(
                        matches = nesineBulletin15,
                        weekTitle = "368. Hafta",
                        weekNumber = 368
                    )
                )
            }
        )

        val bilyonerMatches = (1..16).map { idx ->
            when (idx) {
                1 -> BilyonerScrapedMatch(
                    externalId = "1001",
                    homeTeam = "Galatasaray",
                    awayTeam = "Samsunspor",
                    homeScore = 3,
                    awayScore = 1,
                    status = MatchStatus.LIVE,
                    minuteText = "74'",
                    league = "Trendyol Süper Lig"
                )
                2 -> BilyonerScrapedMatch(
                    externalId = "1002",
                    homeTeam = "Sevilla",
                    awayTeam = "Betis",
                    homeScore = 1,
                    awayScore = 1,
                    status = MatchStatus.FINISHED,
                    minuteText = "MS",
                    league = "La Liga"
                )
                else -> BilyonerScrapedMatch(
                    externalId = "${1000 + idx}",
                    homeTeam = "BilyonerEv$idx",
                    awayTeam = "BilyonerDep$idx",
                    homeScore = if (idx == 3) 2 else null,
                    awayScore = if (idx == 3) 0 else null,
                    status = if (idx == 3) MatchStatus.LIVE else MatchStatus.UPCOMING,
                    minuteText = if (idx == 3) "55'" else "21:00",
                    league = "Avrupa Ligleri"
                )
            }
        }
        val bilyonerScraper = BilyonerLiveScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = { Result.success(bilyonerMatches) }
        )

        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            nesineScraper = nesineScraper,
            bilyonerScraper = bilyonerScraper,
            initialSource = LiveScoreSource.NESINE_RESULTS,
            enforceNesineOnly = false,
            allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
            allowOfflineBulletinFallback = false
        )

        // Disallowed providers must never become active
        repository.setLiveScoreSource(LiveScoreSource.ESPN)
        assertEquals(LiveScoreSource.NESINE_RESULTS, repository.activeLiveScoreSource.value)
        repository.setLiveScoreSource(LiveScoreSource.IDDAA_SCRAPE)
        assertEquals(LiveScoreSource.NESINE_RESULTS, repository.activeLiveScoreSource.value)
        repository.setLiveScoreSource(LiveScoreSource.API_FOOTBALL)
        assertEquals(LiveScoreSource.NESINE_RESULTS, repository.activeLiveScoreSource.value)

        // Load Nesine 15-match bulletin first
        val nesineBulletin = repository.scrapeNesineBulletin(forceRefresh = true).getOrThrow()
        assertEquals(15, nesineBulletin.matches.size)

        // Switch to Bilyoner live scores and merge overlapping bulletin matches + extras
        repository.setLiveScoreSource(LiveScoreSource.BILYONER_LIVE)
        assertEquals(LiveScoreSource.BILYONER_LIVE, repository.activeLiveScoreSource.value)

        val liveScores = repository.scrapeBilyonerLiveScores(forceRefresh = true).getOrThrow()
        assertEquals(16, liveScores.size)
        assertTrue(repository.liveScoreProvider.value.startsWith("Bilyoner.com"))

        val gsScore = liveScores.first { it.homeTeam.equals("Galatasaray", ignoreCase = true) }
        assertEquals(1, gsScore.matchNumber)
        assertEquals(3, gsScore.homeScore)
        assertEquals(1, gsScore.awayScore)

        val sevillaScore = liveScores.first { it.homeTeam.equals("Sevilla", ignoreCase = true) }
        assertEquals(14, sevillaScore.matchNumber)
        assertEquals(PredictionOutcome.DRAW, sevillaScore.winningOutcome)

        // Build 15-match bulletin from Bilyoner fixtures too
        val bilyonerBulletin = repository.scrapeBilyonerLiveBulletin().getOrThrow()
        assertEquals(15, bilyonerBulletin.matches.size)
        assertTrue(bilyonerBulletin.bulletinTitle.contains("Bilyoner"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewModelBilyonerLiveScoresAndBulletinUpdatesState() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val bilyoner15 = (1..15).map { idx ->
                BilyonerScrapedMatch(
                    externalId = "${8800 + idx}",
                    homeTeam = "BilyonerEv$idx",
                    awayTeam = "BilyonerKonuk$idx",
                    homeScore = if (idx <= 4) 2 else null,
                    awayScore = if (idx <= 4) 1 else null,
                    status = if (idx <= 4) MatchStatus.LIVE else MatchStatus.UPCOMING,
                    minuteText = if (idx <= 4) "${40 + idx}'" else "20:45",
                    league = "Bilyoner Canlı Skor"
                )
            }
            val bilyonerScraper = BilyonerLiveScraper(
                httpFetcher = { Result.failure(IllegalStateException("SPA")) },
                webViewFetcher = { Result.success(bilyoner15) }
            )
            val repo = SporTotoBulletinRepositoryImpl(
                liveScoreApiKey = "",
                bilyonerScraper = bilyonerScraper,
                initialSource = LiveScoreSource.NESINE_RESULTS,
                allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
                allowOfflineBulletinFallback = false
            )
            val vm = TotoViewModel(bulletinRepository = repo, nesineOnlyUi = true)

            assertTrue(vm.matches.value.isEmpty())
            assertFalse(vm.hasPlayableBulletin.value)

            vm.scrapeBilyonerLiveBulletinNow()
            dispatcher.scheduler.advanceUntilIdle()
            delay(100)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(15, vm.matches.value.size)
            assertTrue(vm.hasPlayableBulletin.value)
            assertEquals(LiveScoreSource.BILYONER_LIVE, vm.activeLiveScoreSource.value)
            assertTrue(vm.coupons.value.isNotEmpty())
            assertEquals(15, vm.liveScores.value.size)

            vm.scrapeBilyonerLiveScoresNow()
            dispatcher.scheduler.advanceUntilIdle()
            delay(100)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(15, vm.liveScores.value.size)
            assertEquals(MatchStatus.LIVE, vm.liveScores.value.first { it.matchNumber == 1 }.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun couponPoolEvaluatorMatchesCorporateTurkishNamesFromNesineAndBilyoner() {
        val bulletin = listOf(
            TotoMatch(
                id = 1,
                matchNumber = 1,
                homeTeam = "GALATASARAY A.Ş.",
                awayTeam = "SAMSUNSPOR A.Ş.",
                matchDate = "",
                probability = MatchProbability(listOf(70), listOf(20), listOf(10))
            ),
            TotoMatch(
                id = 2,
                matchNumber = 2,
                homeTeam = "GENÇLERBİRLİĞİ",
                awayTeam = "GAZİANTEP FK A.Ş.",
                matchDate = "",
                probability = MatchProbability(listOf(55), listOf(25), listOf(20))
            ),
            TotoMatch(
                id = 3,
                matchNumber = 3,
                homeTeam = "ERZURUMSPOR FK",
                awayTeam = "GÖZTEPE A.Ş.",
                matchDate = "",
                probability = MatchProbability(listOf(30), listOf(30), listOf(40))
            ),
            TotoMatch(
                id = 4,
                matchNumber = 4,
                homeTeam = "AMED SPORTİF FAALİYETLER",
                awayTeam = "ÇAYKUR RİZESPOR A.Ş.",
                matchDate = "",
                probability = MatchProbability(listOf(60), listOf(22), listOf(18))
            )
        )
        val liveScores = listOf(
            MatchLiveScore(9901, "Galatasaray", "Samsunspor", 2, 0, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(9902, "Gençlerbirliği", "Gaziantep FK", 1, 1, MatchStatus.LIVE, "63'"),
            MatchLiveScore(9903, "Büyükşehir Belediye Erzurumspor", "Göztepe", 0, 1, MatchStatus.HALF_TIME, "İY"),
            MatchLiveScore(9904, "Amedspor", "Rizespor", 3, 2, MatchStatus.LIVE, "88'")
        )

        val aligned = CouponPoolEvaluator.filterAndAlignToBulletin(bulletin, liveScores)
        assertEquals(4, aligned.size)
        assertEquals(setOf(1, 2, 3, 4), aligned.map { it.matchNumber }.toSet())
        assertEquals(PredictionOutcome.HOME, aligned.first { it.matchNumber == 1 }.winningOutcome)
        assertEquals(PredictionOutcome.DRAW, aligned.first { it.matchNumber == 2 }.winningOutcome)
        assertEquals(PredictionOutcome.AWAY, aligned.first { it.matchNumber == 3 }.winningOutcome)
        assertEquals(PredictionOutcome.HOME, aligned.first { it.matchNumber == 4 }.winningOutcome)
    }

    @Test
    fun scraperRejectsUnrenderedTemplatesAndPrefersCombinedDomOverEmptyXhr() = runBlocking {
        val templateShellHtml = """
            <html><body>
              <script type="text/template">
                <a class="event-content__row">
                  <div class="event-content__cell--status">CANLI</div>
                  <div class="event-content__cell--home-team">{% if (event.banked) {%}</div>
                  <div class="event-content__cell--score">-</div>
                  <div class="event-content__cell--away-team">{{event.name}}</div>
                </a>
              </script>
              <template>
                <div class="bet-row">Toplam Oran : {{model.totalOdd}}</div>
                <div class="bet-row">Misli : {{model.multiplier}}</div>
              </template>
            </body></html>
        """.trimIndent()

        assertTrue(BilyonerLiveScraper.parseHtmlDocument(templateShellHtml).isEmpty())

        val combinedPayload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": ["{\"config\":{\"home\":\"default\",\"events\":[]}}"],
              "rows": [
                {
                  "league": "Trendyol Süper Lig",
                  "href": "/mac-karti/futbol/777001?islivescore=true",
                  "title": "Fenerbahçe - Beşiktaş",
                  "status": "61'",
                  "home": "Fenerbahçe",
                  "score": "2 - 1",
                  "away": "Beşiktaş",
                  "info": "İY: 1-0",
                  "hasStream": true
                }
              ],
              "bodyText": ""
            }
        """.trimIndent()
        val combined = BilyonerLiveScraper.parseWebViewExtractorPayload(combinedPayload)
        assertEquals(1, combined.size)
        assertEquals("Fenerbahçe", combined[0].homeTeam)
        assertEquals("Beşiktaş", combined[0].awayTeam)
        assertEquals(MatchStatus.LIVE, combined[0].status)

        var webCalled = 0
        val scraper = BilyonerLiveScraper(
            httpFetcher = { Result.success(templateShellHtml) },
            webViewFetcher = {
                webCalled++
                Result.success(combined)
            }
        )
        val live = scraper.scrapeLiveScoreboard().getOrThrow()
        assertEquals(1, webCalled)
        assertEquals("Fenerbahçe", live.matches[0].homeTeam)
    }

    @Test
    fun scraperExcludesNonFootballMatchCardsAndMigratesDefaultSourceToBilyoner() = runBlocking {
        val mixedSportHtml = """
            <html><body>
              <div class="sticky-box">
                <div class="event-content__header"><h2 class="event-content__title">EuroLeague</h2></div>
                <a href="/mac-karti/basketbol/99001?islivescore=true" class="event-content__row" title="Anadolu Efes - Fenerbahçe Beko">
                  <div class="event-content__cell event-content__cell--status">3.Ç</div>
                  <div class="event-content__cell event-content__cell--home-team">Anadolu Efes</div>
                  <div class="event-content__cell event-content__cell--score">64 - 60</div>
                  <div class="event-content__cell event-content__cell--away-team">Fenerbahçe Beko</div>
                </a>
                <a href="/mac-karti/futbol/99002?islivescore=true" class="event-content__row" title="Fenerbahçe - Galatasaray">
                  <div class="event-content__cell event-content__cell--status">45'</div>
                  <div class="event-content__cell event-content__cell--home-team">Fenerbahçe</div>
                  <div class="event-content__cell event-content__cell--score">1 - 1</div>
                  <div class="event-content__cell event-content__cell--away-team">Galatasaray</div>
                </a>
              </div>
            </body></html>
        """.trimIndent()

        val htmlMatches = BilyonerLiveScraper.parseHtmlDocument(mixedSportHtml)
        assertEquals(1, htmlMatches.size)
        assertEquals("99002", htmlMatches[0].externalId)
        assertEquals("Fenerbahçe", htmlMatches[0].homeTeam)

        val mixedJsonPayload = """
            {
              "kind": "captured_json",
              "raw": "{\"events\":[{\"id\":\"111\",\"sportId\":2,\"homeTeam\":\"Chicago Bulls\",\"awayTeam\":\"Miami Heat\",\"status\":\"LIVE\"},{\"id\":\"222\",\"sportId\":1,\"leagueName\":\"Süper Lig\",\"homeTeamName\":\"Beşiktaş\",\"awayTeamName\":\"Trabzonspor\",\"homeScore\":2,\"awayScore\":0,\"clock\":\"78'\"}]}"
            }
        """.trimIndent()
        val jsonMatches = BilyonerLiveScraper.parseWebViewExtractorPayload(mixedJsonPayload)
        assertEquals(1, jsonMatches.size)
        assertEquals("222", jsonMatches[0].externalId)
        assertEquals("Beşiktaş", jsonMatches[0].homeTeam)
        assertEquals("Trabzonspor", jsonMatches[0].awayTeam)
        assertEquals("78'", jsonMatches[0].minuteText)

        // Verify SharedPreferences migration resets stale NESINE_RESULTS on v2 upgrade, then honors manual choice
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = appContext.getSharedPreferences("api_football_free", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString("live_score_source", LiveScoreSource.NESINE_RESULTS.name)
            .commit()

        val upgradedRepo = SporTotoBulletinRepositoryImpl(
            applicationContext = appContext,
            initialSource = LiveScoreSource.BILYONER_LIVE,
            enforceNesineOnly = false,
            allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
            allowOfflineBulletinFallback = false
        )
        assertEquals(LiveScoreSource.BILYONER_LIVE, upgradedRepo.activeLiveScoreSource.value)

        upgradedRepo.setLiveScoreSource(LiveScoreSource.NESINE_RESULTS)
        val reopenedRepo = SporTotoBulletinRepositoryImpl(
            applicationContext = appContext,
            initialSource = LiveScoreSource.BILYONER_LIVE,
            enforceNesineOnly = false,
            allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
            allowOfflineBulletinFallback = false
        )
        assertEquals(LiveScoreSource.NESINE_RESULTS, reopenedRepo.activeLiveScoreSource.value)
    }
}
