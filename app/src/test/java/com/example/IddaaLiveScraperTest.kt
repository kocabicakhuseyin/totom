package com.example

import com.example.engine.LiveBulletinTracker
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.network.IDDAA_LIVE_FOOTBALL_URL
import com.example.network.IddaaLiveScraper
import com.example.network.IddaaScrapedMatch
import com.example.repository.LiveScoreSource
import com.example.repository.SporTotoBulletinRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IddaaLiveScraperTest {

    @Test
    fun parseHtmlDocumentExtractsNextDataSsrMatchesAndOdds() {
        val html = """
            <!doctype html>
            <html>
            <head><title>Canlı Skor Futbol | iddaa.com</title></head>
            <body>
            <div id="__next"></div>
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "events": [
                    {
                      "id": "901231",
                      "leagueName": "Trendyol Süper Lig",
                      "homeTeam": { "name": "Galatasaray" },
                      "awayTeam": { "name": "Fenerbahçe" },
                      "homeScore": 2,
                      "awayScore": 1,
                      "status": "LIVE",
                      "minute": "67'",
                      "matchDate": "2026-11-21T19:00:00+03:00",
                      "odds": { "1": 1.85, "X": 3.40, "2": 3.75 }
                    },
                    {
                      "id": "901232",
                      "leagueName": "Trendyol Süper Lig",
                      "homeTeam": { "name": "Beşiktaş" },
                      "awayTeam": { "name": "Trabzonspor" },
                      "homeScore": 0,
                      "awayScore": 0,
                      "status": "HT",
                      "minute": "İY",
                      "matchDate": "2026-11-21T16:00:00+03:00"
                    },
                    {
                      "id": "901233",
                      "leagueName": "İngiltere Premier Lig",
                      "competitors": [
                        { "side": "home", "name": "Arsenal" },
                        { "side": "away", "name": "Chelsea" }
                      ],
                      "score": { "fullTime": { "home": 3, "away": 0 } },
                      "status": "FT",
                      "minute": "MS",
                      "matchDate": "2026-11-21T15:00:00Z"
                    }
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val parsed = IddaaLiveScraper.parseHtmlDocument(html)
        assertEquals(3, parsed.size)

        val gsFb = parsed[0]
        assertEquals("Galatasaray", gsFb.homeTeam)
        assertEquals("Fenerbahçe", gsFb.awayTeam)
        assertEquals(2, gsFb.homeScore)
        assertEquals(1, gsFb.awayScore)
        assertEquals(MatchStatus.LIVE, gsFb.status)
        assertEquals("67'", gsFb.minuteText)
        assertEquals("Trendyol Süper Lig", gsFb.league)
        assertEquals(1.85, gsFb.homeOdd, 0.001)

        val dto = gsFb.toBulletinMatchDto(1)
        assertEquals("GALATASARAY", dto.homeTeam)
        assertEquals("FENERBAHÇE", dto.awayTeam)
        assertEquals("LIVE", dto.status)
        assertTrue(dto.probabilities.homeProbability > dto.probabilities.awayProbability)

        val bjkTs = parsed[1]
        assertEquals(MatchStatus.HALF_TIME, bjkTs.status)
        assertEquals("İY", bjkTs.minuteText)

        val arsChe = parsed[2]
        assertEquals("Arsenal", arsChe.homeTeam)
        assertEquals("Chelsea", arsChe.awayTeam)
        assertEquals(MatchStatus.FINISHED, arsChe.status)
        assertEquals(3, arsChe.homeScore)
        assertEquals(0, arsChe.awayScore)
    }

    @Test
    fun parseHtmlDocumentExtractsRscFlightPayloadAndHtmlRowFallback() {
        val rscHtml = """
            <html><body>
            <script>self.__next_f.push([1,"5:{\"matches\":[{\"id\":\"501\",\"league\":\"TFF 1. Lig\",\"home\":\"Sakaryaspor\",\"away\":\"Kocaelispor\",\"homeScore\":1,\"awayScore\":1,\"status\":\"LIVE\",\"minute\":\"78'\"}]}"])</script>
            </body></html>
        """.trimIndent()

        val rscMatches = IddaaLiveScraper.parseHtmlDocument(rscHtml)
        assertEquals(1, rscMatches.size)
        assertEquals("Sakaryaspor", rscMatches[0].homeTeam)
        assertEquals("Kocaelispor", rscMatches[0].awayTeam)
        assertEquals(MatchStatus.LIVE, rscMatches[0].status)
        assertEquals("78'", rscMatches[0].minuteText)

        val domHtml = """
            <html><body>
              <div class="match-row">
                <span>67'</span>
                <span>Samsunspor</span>
                <span>2 - 0</span>
                <span>Göztepe</span>
              </div>
              <div class="fixture-card">
                <span>20:00</span>
                <span>Konyaspor</span>
                <span>Başakşehir</span>
              </div>
            </body></html>
        """.trimIndent()

        val domMatches = IddaaLiveScraper.parseHtmlDocument(domHtml)
        assertEquals(2, domMatches.size)
        assertEquals("Samsunspor", domMatches[0].homeTeam)
        assertEquals("Göztepe", domMatches[0].awayTeam)
        assertEquals(2, domMatches[0].homeScore)
        assertEquals(0, domMatches[0].awayScore)
        assertEquals(MatchStatus.LIVE, domMatches[0].status)

        assertEquals("Konyaspor", domMatches[1].homeTeam)
        assertEquals("Başakşehir", domMatches[1].awayTeam)
        assertEquals(MatchStatus.UPCOMING, domMatches[1].status)
    }

    @Test
    fun parseWebViewExtractorPayloadHandlesDomAndCapturedJson() {
        val domPayload = """
            {
              "kind": "dom",
              "items": [
                { "lines": ["Trendyol Süper Lig"] },
                { "lines": ["İY", "Alanyaspor", "1 - 1", "Antalyaspor"] }
              ],
              "bodyText": ""
            }
        """.trimIndent()

        val domMatches = IddaaLiveScraper.parseWebViewExtractorPayload(domPayload)
        assertEquals(1, domMatches.size)
        assertEquals("Alanyaspor", domMatches[0].homeTeam)
        assertEquals("Antalyaspor", domMatches[0].awayTeam)
        assertEquals(MatchStatus.HALF_TIME, domMatches[0].status)
        assertEquals("Trendyol Süper Lig", domMatches[0].league)
    }

    @Test
    fun scrapeLiveFootballFallsBackToWebViewWhenHttpReturnsSpaShellOrError() = runBlocking {
        var webViewCalls = 0
        val spaShellScraper = IddaaLiveScraper(
            httpFetcher = { Result.success("<html><body><div id='__next'>Yükleniyor...</div></body></html>") },
            webViewFetcher = { url ->
                webViewCalls++
                assertEquals(IDDAA_LIVE_FOOTBALL_URL, url)
                Result.success(
                    listOf(
                        IddaaScrapedMatch(
                            externalId = "8811",
                            homeTeam = "Gaziantep FK",
                            awayTeam = "Kasımpaşa",
                            homeScore = 1,
                            awayScore = 2,
                            status = MatchStatus.FINISHED,
                            minuteText = "MS",
                            league = "Trendyol Süper Lig"
                        )
                    )
                )
            }
        )

        val result = spaShellScraper.scrapeLiveFootball().getOrThrow()
        assertEquals(1, webViewCalls)
        assertEquals("WebView JS/DOM", result.methodLabel)
        assertEquals(1, result.matches.size)
        assertEquals("Gaziantep FK", result.matches.first().homeTeam)

        // When both HTTP and WebView fail or return empty, never fabricate fake matches
        val failingScraper = IddaaLiveScraper(
            httpFetcher = { Result.failure(IllegalStateException("HTTP 403 engeli")) },
            webViewFetcher = { Result.success(emptyList()) }
        )
        val failed = failingScraper.scrapeLiveFootball()
        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()?.message.orEmpty().contains("HTTP 403"))
    }

    @Test
    fun repositoryScrapeIddaaLiveBulletinBuilds15MatchBulletinAndAlignsScores() = runBlocking {
        val scrapedFixtures = (1..16).map { idx ->
            IddaaScrapedMatch(
                externalId = "${7000 + idx}",
                homeTeam = "EvTakım$idx",
                awayTeam = "KonukTakım$idx",
                homeScore = if (idx <= 5) 2 else null,
                awayScore = if (idx <= 5) 0 else null,
                status = when {
                    idx <= 2 -> MatchStatus.LIVE
                    idx <= 5 -> MatchStatus.FINISHED
                    else -> MatchStatus.UPCOMING
                },
                minuteText = when {
                    idx <= 2 -> "${40 + idx}'"
                    idx <= 5 -> "MS"
                    else -> "20:00"
                },
                matchDate = "21.11.2026 20:00",
                league = if (idx <= 10) "Trendyol Süper Lig" else "TFF 1. Lig",
                homeOdd = 1.90,
                drawOdd = 3.40,
                awayOdd = 3.80
            )
        }

        val scraper = IddaaLiveScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = { Result.success(scrapedFixtures) }
        )
        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            iddaaScraper = scraper
        )

        val bulletin = repository.scrapeIddaaLiveBulletin().getOrThrow()
        assertEquals(15, bulletin.matches.size)
        assertEquals(LiveScoreSource.IDDAA_SCRAPE, repository.activeLiveScoreSource.value)
        assertTrue(repository.liveScoreProvider.value.startsWith("İddaa.com"))
        assertTrue(bulletin.bulletinTitle.contains("İddaa Canlı Bülten"))

        val liveScores = repository.liveScores.value
        assertFalse(liveScores.isEmpty())

        val domainMatches = bulletin.matches.map {
            com.example.model.TotoMatch(
                id = it.matchNumber,
                matchNumber = it.matchNumber,
                homeTeam = it.homeTeam,
                awayTeam = it.awayTeam,
                matchDate = it.matchDate,
                probability = com.example.model.MatchProbability(listOf(45), listOf(30), listOf(25))
            )
        }
        val trackerSnapshot = LiveBulletinTracker.evaluate(domainMatches, liveScores, emptyList())
        assertEquals(2, trackerSnapshot.liveCount)
        assertEquals(3, trackerSnapshot.finishedCount)
        assertEquals(10, trackerSnapshot.upcomingCount)
        assertEquals(
            PredictionOutcome.HOME,
            trackerSnapshot.bulletinMatches.first().outcome
        )

        // Direct pollLiveScores() under IDDAA_SCRAPE works too
        repository.setLiveScoreSource(LiveScoreSource.IDDAA_SCRAPE)
        val polled = repository.pollLiveScores().getOrThrow()
        assertFalse(polled.isEmpty())
    }
}
