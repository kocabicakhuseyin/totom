package com.example

import com.example.engine.LiveBulletinTracker
import com.example.model.MatchProbability
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.network.NESINE_SPORTOTO_RESULTS_URL
import com.example.network.NESINE_SPORTOTO_URL
import com.example.network.NesineSporTotoScrapedMatch
import com.example.network.NesineSporTotoScraper
import com.example.repository.LiveScoreSource
import com.example.repository.NoNewBulletinException
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
class NesineSporTotoScraperTest {

    @Test
    fun parseHtmlDocumentExtractsSporTotoJsonAndWeekMetadata() {
        val html = """
            <!doctype html>
            <html>
            <body>
            <script id="__NEXT_DATA__" type="application/json">
            {
              "props": {
                "pageProps": {
                  "WeekNo": 42,
                  "WeekName": "42. Hafta Spor Toto",
                  "Matches": [
                    {
                      "MatchNumber": 1,
                      "HomeTeam": "Galatasaray",
                      "AwayTeam": "Fenerbahçe",
                      "HomeScore": 2,
                      "AwayScore": 1,
                      "Status": "LIVE",
                      "Minute": "68'"
                    },
                    {
                      "MatchNumber": 2,
                      "HomeTeam": "Beşiktaş",
                      "AwayTeam": "Trabzonspor",
                      "HomeScore": 1,
                      "AwayScore": 1,
                      "Status": "HT",
                      "Minute": "İY"
                    },
                    {
                      "MatchNumber": 3,
                      "HomeTeam": "Samsunspor",
                      "AwayTeam": "Göztepe",
                      "HomeScore": 3,
                      "AwayScore": 0,
                      "Status": "FT",
                      "Minute": "MS"
                    },
                    {
                      "MatchNumber": 4,
                      "MatchName": "Başakşehir - Konyaspor",
                      "Status": "UPCOMING",
                      "Minute": "20:00"
                    }
                  ]
                }
              }
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        val parsed = NesineSporTotoScraper.parseHtmlDocument(html)
        assertEquals(42, parsed.weekNumber)
        assertEquals("42. Hafta Spor Toto", parsed.weekTitle)
        assertEquals(4, parsed.matches.size)

        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Galatasaray", parsed.matches[0].homeTeam)
        assertEquals("Fenerbahçe", parsed.matches[0].awayTeam)
        assertEquals(2, parsed.matches[0].homeScore)
        assertEquals(1, parsed.matches[0].awayScore)
        assertEquals(MatchStatus.LIVE, parsed.matches[0].status)
        assertEquals("68'", parsed.matches[0].minuteText)

        assertEquals(MatchStatus.HALF_TIME, parsed.matches[1].status)
        assertEquals("İY", parsed.matches[1].minuteText)

        assertEquals(MatchStatus.FINISHED, parsed.matches[2].status)
        assertEquals("MS", parsed.matches[2].minuteText)

        assertEquals(4, parsed.matches[3].matchNumber)
        assertEquals("Başakşehir", parsed.matches[3].homeTeam)
        assertEquals("Konyaspor", parsed.matches[3].awayTeam)
        assertEquals(MatchStatus.UPCOMING, parsed.matches[3].status)
    }

    @Test
    fun parseHtmlDocumentHandlesTableRowsWithoutConfusingClockTimesForScores() {
        val tableHtml = """
            <html><body>
              <h1>43. Hafta Spor Toto Sonuçları</h1>
              <table>
                <tbody>
                  <tr class="sportoto-row" data-match-no="1">
                    <td>1</td>
                    <td>Kasımpaşa - Eyüpspor</td>
                    <td>2 - 1</td>
                    <td>MS</td>
                  </tr>
                  <tr class="sportoto-row" data-match-no="2">
                    <td>2</td>
                    <td>Antalyaspor</td>
                    <td>Alanyaspor</td>
                    <td>20:00</td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val parsed = NesineSporTotoScraper.parseHtmlDocument(tableHtml)
        assertEquals(43, parsed.weekNumber)
        assertEquals(2, parsed.matches.size)

        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Kasımpaşa", parsed.matches[0].homeTeam)
        assertEquals("Eyüpspor", parsed.matches[0].awayTeam)
        assertEquals(2, parsed.matches[0].homeScore)
        assertEquals(1, parsed.matches[0].awayScore)
        assertEquals(MatchStatus.FINISHED, parsed.matches[0].status)

        assertEquals(2, parsed.matches[1].matchNumber)
        assertEquals("Antalyaspor", parsed.matches[1].homeTeam)
        assertEquals("Alanyaspor", parsed.matches[1].awayTeam)
        assertEquals(null, parsed.matches[1].homeScore)
        assertEquals(null, parsed.matches[1].awayScore)
        assertEquals(MatchStatus.UPCOMING, parsed.matches[1].status)
    }

    @Test
    fun parseWebViewExtractorPayloadParsesCapturedXhrAndDomItems() {
        val xhrPayload = """
            {
              "kind": "captured_json",
              "weekTitle": "44. Hafta Spor Toto",
              "raw": "{\"Matches\":[{\"MatchNo\":1,\"HomeTeamName\":\"Kayserispor\",\"AwayTeamName\":\"Rizespor\",\"EvSkor\":1,\"DepSkor\":0,\"Durum\":\"LIVE\",\"Dakika\":\"55'\"}]}"
            }
        """.trimIndent()

        val xhrParsed = NesineSporTotoScraper.parseWebViewExtractorPayload(xhrPayload)
        assertEquals(44, xhrParsed.weekNumber)
        assertEquals(1, xhrParsed.matches.size)
        assertEquals("Kayserispor", xhrParsed.matches[0].homeTeam)
        assertEquals(MatchStatus.LIVE, xhrParsed.matches[0].status)
        assertEquals("55'", xhrParsed.matches[0].minuteText)

        val domPayload = """
            {
              "kind": "dom",
              "weekTitle": "44. Hafta",
              "items": [
                { "lines": ["5", "Sivasspor - Hatayspor", "0 - 2", "BİTTİ"] }
              ],
              "bodyText": ""
            }
        """.trimIndent()

        val domParsed = NesineSporTotoScraper.parseWebViewExtractorPayload(domPayload)
        assertEquals(1, domParsed.matches.size)
        assertEquals(5, domParsed.matches[0].matchNumber)
        assertEquals("Sivasspor", domParsed.matches[0].homeTeam)
        assertEquals("Hatayspor", domParsed.matches[0].awayTeam)
        assertEquals(MatchStatus.FINISHED, domParsed.matches[0].status)
    }

    @Test
    fun scrapeBulletinParsesProbabilitiesFromNesineSportotoHtmlAndDom() = runBlocking {
        val bulletinHtml = """
            <html><body>
              <h1>47. Hafta Spor Toto Listesi</h1>
              <table><tbody>
                <tr class="toto-program-row">
                  <td>1</td>
                  <td>Galatasaray - Beşiktaş</td>
                  <td>%60</td>
                  <td>%22</td>
                  <td>%18</td>
                  <td>20:00</td>
                </tr>
                <tr class="toto-program-row">
                  <td>2</td>
                  <td>Fenerbahçe - Trabzonspor</td>
                  <td>58%</td>
                  <td>24%</td>
                  <td>18%</td>
                  <td>20:00</td>
                </tr>
              </tbody></table>
            </body></html>
        """.trimIndent()

        val requestedUrls = mutableListOf<String>()
        val scraper = NesineSporTotoScraper(
            httpFetcher = { url ->
                requestedUrls.add(url)
                Result.success(bulletinHtml)
            }
        )

        val result = scraper.scrapeBulletin().getOrThrow()
        assertEquals(listOf(NESINE_SPORTOTO_URL), requestedUrls)
        assertEquals(47, result.weekNumber)
        assertEquals(2, result.matches.size)

        val firstDto = result.matches[0].toBulletinMatchDto()
        assertEquals(60, firstDto.probabilities.homeProbability)
        assertEquals(22, firstDto.probabilities.drawProbability)
        assertEquals(18, firstDto.probabilities.awayProbability)
        assertTrue(firstDto.liveOdds.homeWinOdd < firstDto.liveOdds.awayWinOdd)

        val secondDto = result.matches[1].toBulletinMatchDto()
        assertEquals(58, secondDto.probabilities.homeProbability)
        assertEquals(24, secondDto.probabilities.drawProbability)
        assertEquals(18, secondDto.probabilities.awayProbability)
    }

    @Test
    fun scrapeResultsFallsBackToWebViewOnHttpBlockAndFailsHonestlyIfEmpty() = runBlocking {
        var webCalls = 0
        val scraper = NesineSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("nesine.com HTTP 403 engeli")) },
            webViewFetcher = { url ->
                webCalls++
                assertEquals(NESINE_SPORTOTO_RESULTS_URL, url)
                Result.success(
                    NesineSporTotoScraper.NesineWebViewPayload(
                        weekTitle = "45. Hafta",
                        weekNumber = 45,
                        matches = listOf(
                            NesineSporTotoScrapedMatch(
                                matchNumber = 1,
                                homeTeam = "Gaziantep FK",
                                awayTeam = "Bodrum FK",
                                homeScore = 2,
                                awayScore = 2,
                                status = MatchStatus.LIVE,
                                minuteText = "81'"
                            )
                        )
                    )
                )
            }
        )

        val res = scraper.scrapeResults().getOrThrow()
        assertEquals(1, webCalls)
        assertEquals("WebView JS/DOM", res.methodLabel)
        assertEquals(45, res.weekNumber)
        assertEquals("Gaziantep FK", res.matches.single().homeTeam)

        val emptyScraper = NesineSporTotoScraper(
            httpFetcher = { Result.success("<html><body><div id='app'></div></body></html>") },
            webViewFetcher = { Result.success(NesineSporTotoScraper.NesineWebViewPayload(emptyList())) }
        )
        val failed = emptyScraper.scrapeResults()
        assertTrue(failed.isFailure)
    }

    @Test
    fun repositoryScrapeNesineResultsUpdates15MatchBulletinAndLiveScores() = runBlocking {
        val scraped15 = (1..15).map { idx ->
            NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "NesineEv$idx",
                awayTeam = "NesineKonuk$idx",
                homeScore = when {
                    idx <= 3 -> 2
                    idx <= 7 -> 1
                    else -> null
                },
                awayScore = when {
                    idx <= 3 -> 0
                    idx <= 7 -> 1
                    else -> null
                },
                status = when {
                    idx <= 3 -> MatchStatus.LIVE
                    idx <= 7 -> MatchStatus.FINISHED
                    else -> MatchStatus.UPCOMING
                },
                minuteText = when {
                    idx <= 3 -> "${35 + idx}'"
                    idx <= 7 -> "MS"
                    else -> "19:00"
                },
                matchDate = "21.11.2026 19:00"
            )
        }

        val scraper = NesineSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = {
                Result.success(
                    NesineSporTotoScraper.NesineWebViewPayload(
                        matches = scraped15,
                        weekTitle = "46. Hafta",
                        weekNumber = 46
                    )
                )
            }
        )
        val repository = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            nesineScraper = scraper
        )

        val bulletin = repository.scrapeNesineResultsAndBulletin(updateBulletin = true).getOrThrow()
        assertEquals(15, bulletin.matches.size)
        assertEquals(46, bulletin.weekNumber)
        assertTrue(bulletin.bulletinTitle.contains("Nesine Spor Toto"))
        assertEquals(LiveScoreSource.NESINE_RESULTS, repository.activeLiveScoreSource.value)
        assertTrue(repository.liveScoreProvider.value.startsWith("Nesine.com"))

        val liveScores = repository.liveScores.value
        assertEquals(7, liveScores.size)

        val domainMatches = bulletin.matches.map {
            TotoMatch(
                id = it.matchNumber,
                matchNumber = it.matchNumber,
                homeTeam = it.homeTeam,
                awayTeam = it.awayTeam,
                matchDate = it.matchDate,
                probability = MatchProbability(listOf(45), listOf(30), listOf(25))
            )
        }
        val snapshot = LiveBulletinTracker.evaluate(domainMatches, liveScores, emptyList())
        assertEquals(3, snapshot.liveCount)
        assertEquals(4, snapshot.finishedCount)
        assertEquals(8, snapshot.upcomingCount)
        assertEquals(PredictionOutcome.HOME, snapshot.bulletinMatches[0].outcome)
        assertEquals(PredictionOutcome.DRAW, snapshot.bulletinMatches[3].outcome)

        val polled = repository.pollLiveScores().getOrThrow()
        assertFalse(polled.isEmpty())
    }

    @Test
    fun enforceNesineOnlyLocksSourceAndNeverFallsBackToOfflineMatches() = runBlocking {
        val partialScraper = NesineSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("nesine.com geçici hata")) },
            webViewFetcher = {
                Result.success(
                    NesineSporTotoScraper.NesineWebViewPayload(
                        matches = listOf(
                            NesineSporTotoScrapedMatch(
                                matchNumber = 1,
                                homeTeam = "EksikEv",
                                awayTeam = "EksikDep"
                            )
                        ),
                        weekTitle = "48. Hafta",
                        weekNumber = 48
                    )
                )
            }
        )
        val strictRepo = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "unused",
            nesineScraper = partialScraper,
            initialSource = LiveScoreSource.NESINE_RESULTS,
            enforceNesineOnly = true
        )

        strictRepo.setLiveScoreSource(LiveScoreSource.ESPN)
        assertEquals(LiveScoreSource.NESINE_RESULTS, strictRepo.activeLiveScoreSource.value)
        strictRepo.setLiveScoreSource(LiveScoreSource.API_FOOTBALL)
        assertEquals(LiveScoreSource.NESINE_RESULTS, strictRepo.activeLiveScoreSource.value)

        val incompleteResult = strictRepo.getWeeklyBulletin(forceRefresh = true)
        assertTrue(incompleteResult.isFailure)
        assertTrue(incompleteResult.exceptionOrNull() is NoNewBulletinException)

        val calledUrls = mutableListOf<String>()
        val full15Bulletin = (1..15).map { idx ->
            NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "BultenEv$idx",
                awayTeam = "BultenDep$idx",
                status = MatchStatus.UPCOMING,
                minuteText = "20:00",
                homeProb = 50,
                drawProb = 28,
                awayProb = 22
            )
        }
        val full15Results = (1..15).map { idx ->
            NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "BultenEv$idx",
                awayTeam = "BultenDep$idx",
                homeScore = 1,
                awayScore = if (idx % 2 == 0) 1 else 0,
                status = MatchStatus.FINISHED,
                minuteText = "MS"
            )
        }
        val fullScraper = NesineSporTotoScraper(
            httpFetcher = { url ->
                calledUrls.add(url)
                Result.failure(IllegalStateException("SPA"))
            },
            webViewFetcher = { url ->
                Result.success(
                    NesineSporTotoScraper.NesineWebViewPayload(
                        matches = if (url == NESINE_SPORTOTO_URL) full15Bulletin else full15Results,
                        weekTitle = "49. Hafta Spor Toto",
                        weekNumber = 49
                    )
                )
            }
        )
        val workingRepo = SporTotoBulletinRepositoryImpl(
            nesineScraper = fullScraper,
            initialSource = LiveScoreSource.NESINE_RESULTS,
            enforceNesineOnly = true
        )

        val weekly = workingRepo.getWeeklyBulletin(forceRefresh = true).getOrThrow()
        assertEquals(15, weekly.matches.size)
        assertEquals(49, weekly.weekNumber)
        assertEquals(50, weekly.matches[0].probabilities.homeProbability)
        assertTrue(calledUrls.contains(NESINE_SPORTOTO_URL))

        val prevReport = workingRepo.fetchPreviousWeekBulletinAndResults(forceRefresh = true).getOrThrow()
        assertEquals(15, prevReport.matches.size)
        assertTrue(prevReport.sourceLabel.startsWith("Nesine.com"))
        assertTrue(calledUrls.contains(NESINE_SPORTOTO_RESULTS_URL))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nesineOnlyViewModelStartsEmptyAndLoadsFromNesineBulletinAndResults() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val full15Bulletin = (1..15).map { idx ->
                NesineSporTotoScrapedMatch(
                    matchNumber = idx,
                    homeTeam = "NesineEv$idx",
                    awayTeam = "NesineDep$idx",
                    status = MatchStatus.UPCOMING,
                    minuteText = "19:00",
                    homeProb = 55,
                    drawProb = 25,
                    awayProb = 20
                )
            }
            val full15Results = (1..15).map { idx ->
                NesineSporTotoScrapedMatch(
                    matchNumber = idx,
                    homeTeam = "NesineEv$idx",
                    awayTeam = "NesineDep$idx",
                    homeScore = 2,
                    awayScore = 1,
                    status = if (idx <= 5) MatchStatus.LIVE else MatchStatus.FINISHED,
                    minuteText = if (idx <= 5) "74'" else "MS"
                )
            }
            val scraper = NesineSporTotoScraper(
                httpFetcher = { Result.failure(IllegalStateException("SPA")) },
                webViewFetcher = { url ->
                    Result.success(
                        NesineSporTotoScraper.NesineWebViewPayload(
                            matches = if (url == NESINE_SPORTOTO_URL) full15Bulletin else full15Results,
                            weekTitle = "50. Hafta",
                            weekNumber = 50
                        )
                    )
                }
            )
            val repo = SporTotoBulletinRepositoryImpl(
                nesineScraper = scraper,
                initialSource = LiveScoreSource.NESINE_RESULTS,
                enforceNesineOnly = true
            )
            val vm = TotoViewModel(bulletinRepository = repo, nesineOnlyUi = true)

            assertTrue(vm.matches.value.isEmpty())
            assertFalse(vm.hasPlayableBulletin.value)

            vm.scrapeNesineBulletinNow()
            dispatcher.scheduler.advanceUntilIdle()
            delay(100)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(15, vm.matches.value.size)
            assertTrue(vm.hasPlayableBulletin.value)
            assertEquals("NESİNEEV1", vm.matches.value[0].homeTeam)
            assertTrue(vm.coupons.value.isNotEmpty())

            vm.scrapeNesineResultsNow(syncBulletin = true)
            dispatcher.scheduler.advanceUntilIdle()
            delay(100)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(15, vm.liveScores.value.size)
            assertEquals(MatchStatus.LIVE, vm.liveScores.value[0].status)
            assertEquals(MatchStatus.FINISHED, vm.liveScores.value[14].status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun parseHtmlAndWebViewExtractorHandleExactLiveNesineSnapshotDom() {
        val exactSnapshotHtml = """
            <div id="sportoto-snapshot"><div class="ebf622cd4751510b79d4"><div class="a6d87c645e336289419a"><div class="a244cffc167b228ba707">Program Seçimi</div><select id="filter-week-select"><option value="367">13.11.2026 - 17.11.2026</option><option value="366">06.11.2026 - 09.11.2026</option></select></div><div class="f6354322637f20e1f835"><table class="table bf466c863f7ec0d4b8d6 efa57aec2a18442274c2"><thead class="thead dccf8d8b9dbda6d4fa69"><tr class="tr ae43250260ce03a34913"><th class="th a15625a5651dc1e97933 a149a5644f81a6a862eb"><b>No</b></th><th class="th a15625a5651dc1e97933 a149a5644f81a6a862eb"><b>Tarih</b></th><th class="th a15625a5651dc1e97933 a149a5644f81a6a862eb"><b>Karşılaşma</b></th><th class="th a15625a5651dc1e97933 a149a5644f81a6a862eb"><b>Durum</b></th><th class="th a15625a5651dc1e97933 a149a5644f81a6a862eb"><b>Sonuç</b></th></tr></thead><tbody class="tbody ab52aa08138eb69cbc2a">
            <tr class="tr ae43250260ce03a34913 dfcf01680c6b648455d5"><td class="td" data-test-id="programResult-number">1</td><td class="td" data-test-id="programResult-date">15.11.2026 22:45</td><td class="td" data-test-id="programResult-name"><b>Fransa-Türkiye</b></td><td class="td" data-test-id="programResult-status">Bitti / 2-1</td><td class="td" data-test-id="programResult-result">1</td></tr>
            <tr class="tr ae43250260ce03a34913 dfcf01680c6b648455d5"><td class="td" data-test-id="programResult-number">2</td><td class="td" data-test-id="programResult-date">13.11.2026 22:45</td><td class="td" data-test-id="programResult-name"><b>Bulgaristan-İzlanda</b></td><td class="td" data-test-id="programResult-status">Bitti / 0-5</td><td class="td" data-test-id="programResult-result">2</td></tr>
            <tr class="tr ae43250260ce03a34913 dfcf01680c6b648455d5"><td class="td" data-test-id="programResult-number">5</td><td class="td" data-test-id="programResult-date">13.11.2026 22:45</td><td class="td" data-test-id="programResult-name"><b>Slovakya-Faroe Adaları</b></td><td class="td" data-test-id="programResult-status">Bitti / 2-2</td><td class="td" data-test-id="programResult-result">X</td></tr>
            <tr class="tr ae43250260ce03a34913 dfcf01680c6b648455d5"><td class="td" data-test-id="programResult-number">15</td><td class="td" data-test-id="programResult-date">16.11.2026 22:45</td><td class="td" data-test-id="programResult-name"><b>İsviçre-İskoçya</b></td><td class="td" data-test-id="programResult-status">Bitti / 4-0</td><td class="td" data-test-id="programResult-result">1</td></tr>
            </tbody></table></div></div></div>
        """.trimIndent()

        val parsed = NesineSporTotoScraper.parseHtmlDocument(exactSnapshotHtml)
        assertEquals(367, parsed.weekNumber)
        assertTrue(parsed.weekTitle.contains("367"))
        assertTrue(parsed.weekTitle.contains("13.11.2026 - 17.11.2026"))
        assertEquals(4, parsed.matches.size)

        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Fransa", parsed.matches[0].homeTeam)
        assertEquals("Türkiye", parsed.matches[0].awayTeam)
        assertEquals(2, parsed.matches[0].homeScore)
        assertEquals(1, parsed.matches[0].awayScore)
        assertEquals(MatchStatus.FINISHED, parsed.matches[0].status)
        assertEquals("MS", parsed.matches[0].minuteText)
        assertEquals("15.11.2026 22:45", parsed.matches[0].matchDate)
        assertEquals(PredictionOutcome.HOME, parsed.matches[0].explicitOutcome)

        assertEquals(5, parsed.matches[2].matchNumber)
        assertEquals("Slovakya", parsed.matches[2].homeTeam)
        assertEquals("Faroe Adaları", parsed.matches[2].awayTeam)
        assertEquals(2, parsed.matches[2].homeScore)
        assertEquals(2, parsed.matches[2].awayScore)
        assertEquals(PredictionOutcome.DRAW, parsed.matches[2].explicitOutcome)

        assertEquals(15, parsed.matches[3].matchNumber)
        assertEquals("İsviçre", parsed.matches[3].homeTeam)
        assertEquals("İskoçya", parsed.matches[3].awayTeam)
        assertEquals(4, parsed.matches[3].homeScore)
        assertEquals(0, parsed.matches[3].awayScore)

        val domPayload = """
            {
              "kind": "dom",
              "weekTitle": "367. Hafta (13.11.2026 - 17.11.2026)",
              "weekNumber": "367",
              "items": [
                { "lines": ["1", "15.11.2026 22:45", "Fransa-Türkiye", "Bitti / 2-1", "1"] },
                { "lines": ["5", "13.11.2026 22:45", "Slovakya-Faroe Adaları", "Bitti / 2-2", "X"] },
                { "lines": ["13", "16.11.2026 22:45", "Kuzey Makedonya-Slovenya", "68' / 0-3", "2"] }
              ],
              "bodyText": ""
            }
        """.trimIndent()
        val domParsed = NesineSporTotoScraper.parseWebViewExtractorPayload(domPayload)
        assertEquals(367, domParsed.weekNumber)
        assertEquals(3, domParsed.matches.size)
        assertEquals("Fransa", domParsed.matches[0].homeTeam)
        assertEquals("Türkiye", domParsed.matches[0].awayTeam)
        assertEquals(MatchStatus.FINISHED, domParsed.matches[0].status)
        assertEquals(PredictionOutcome.HOME, domParsed.matches[0].explicitOutcome)

        assertEquals(MatchStatus.LIVE, domParsed.matches[2].status)
        assertEquals("68'", domParsed.matches[2].minuteText)
        assertEquals(0, domParsed.matches[2].homeScore)
        assertEquals(3, domParsed.matches[2].awayScore)
    }

    @Test
    fun parseHtmlAndWebViewExtractorHandleExactLiveNesineBulletinDom() {
        val rowData = listOf(
            listOf("1", "21.11.2026 13:30", "Gençlerbirliği-Gaziantep FK A.Ş.", "3313538", "59", "24", "17"),
            listOf("2", "21.11.2026 16:00", "Amed Sportif Faaliyetler-Çaykur Rizespor A.Ş.", "3313999", "74", "14", "12"),
            listOf("3", "21.11.2026 16:00", "Galatasaray A.Ş.-Samsunspor A.Ş.", "3314007", "86", "9", "5"),
            listOf("4", "21.11.2026 19:00", "Kocaelispor-Fenerbahçe A.Ş.", "3313522", "9", "16", "75"),
            listOf("5", "22.11.2026 13:30", "Erzurumspor FK-Göztepe A.Ş.", "3318456", "23", "28", "49"),
            listOf("6", "22.11.2026 16:00", "Kasımpaşa A.Ş.-Alanyaspor", "3319960", "66", "18", "16"),
            listOf("7", "22.11.2026 16:00", "Konyaspor-Beşiktaş A.Ş.", "3318595", "10", "17", "73"),
            listOf("8", "22.11.2026 19:00", "Trabzonspor A.Ş.-Eyüpspor", "3319509", "92", "5", "3"),
            listOf("9", "23.11.2026 20:00", "Başakşehir FK-Çorum FK", "3319387", "59", "22", "19"),
            listOf("10", "21.11.2026 20:30", "Schalke 04-Stuttgart", "3314496", "16", "19", "65"),
            listOf("11", "21.11.2026 22:45", "Lille-Lyon", "3314607", "53", "27", "20"),
            listOf("12", "21.11.2026 20:30", "Newcastle United-Arsenal", "3313135", "12", "14", "74"),
            listOf("13", "22.11.2026 19:30", "Liverpool-Manchester United", "3318613", "67", "18", "15"),
            listOf("14", "21.11.2026 20:30", "Sevilla-Real Betis", "3318820", "47", "27", "26"),
            listOf("15", "22.11.2026 22:45", "Atalanta-Inter", "3319987", "12", "16", "72")
        )
        val rowsHtml = rowData.joinToString("\n") { r ->
            val p1 = r[4]; val px = r[5]; val p2 = r[6]
            val cells = (0..3).joinToString("") { g ->
                """<td class="a42f8e1a7edb7d0a3690"><label><input type="checkbox"><span>%$p1</span></label><label><input type="checkbox"><span>%$px</span></label><label><input type="checkbox"><span>%$p2</span></label></td>"""
            }
            """<tr><td>${r[0]}</td><td class="a94f48c1e6a9ae560d95">${r[1]}</td><td class="ab93a215fb2fea9565fc" data-test-id="name"><a href="https://istatistik.nesine.com/${r[3]}" rel="noreferrer" target="_blank">${r[2]} <br></a></td>$cells</tr>"""
        }
        val bulletinSnapshotHtml = """
            <div id="sportoto-snapshot"><div class="ebf622cd4751510b79d4"><div class="ad9a3a0d35d6233b9982"><div data-test-id="programLastPlayDate"><div>Spor Toto oyunu için</div><div><strong>son tarih 21.11.2026 13:25</strong></div></div></div>
            <div class="f5354c56d67194de87bf"><table><thead class="ae999fb4bf2ae42876e3"><tr><th colspan="3" class="a315151e2a5bf5deff0f">21.11.2026 - 23.11.2026 Programı</th><th>A</th><th>B</th><th>C</th><th>D</th></tr></thead>
            <tbody class="a85921604e5091f057c4">$rowsHtml</tbody>
            <tfoot><tr><td colspan="3"><span>Hızlı Seçim</span></td><td data-test-id="button-popular"><button type="button">Popüler</button></td><td><button type="button">İdeal</button></td><td><button type="button">Sürpriz</button></td><td><button type="button">Rastgele</button></td></tr></tfoot>
            </table></div></div></div>
        """.trimIndent()

        val parsedHtml = NesineSporTotoScraper.parseHtmlDocument(bulletinSnapshotHtml)
        assertEquals("21.11.2026 - 23.11.2026 Programı", parsedHtml.weekTitle)
        assertEquals(15, parsedHtml.matches.size)

        assertEquals(1, parsedHtml.matches[0].matchNumber)
        assertEquals("Gençlerbirliği", parsedHtml.matches[0].homeTeam)
        assertEquals("Gaziantep FK A.Ş.", parsedHtml.matches[0].awayTeam)
        assertEquals("21.11.2026 13:30", parsedHtml.matches[0].matchDate)
        assertEquals(59, parsedHtml.matches[0].homeProb)
        assertEquals(24, parsedHtml.matches[0].drawProb)
        assertEquals(17, parsedHtml.matches[0].awayProb)

        assertEquals(10, parsedHtml.matches[9].matchNumber)
        assertEquals("Schalke 04", parsedHtml.matches[9].homeTeam)
        assertEquals("Stuttgart", parsedHtml.matches[9].awayTeam)
        assertEquals(16, parsedHtml.matches[9].homeProb)
        assertEquals(19, parsedHtml.matches[9].drawProb)
        assertEquals(65, parsedHtml.matches[9].awayProb)

        assertEquals(15, parsedHtml.matches[14].matchNumber)
        assertEquals("Atalanta", parsedHtml.matches[14].homeTeam)
        assertEquals("Inter", parsedHtml.matches[14].awayTeam)
        assertEquals(12, parsedHtml.matches[14].homeProb)
        assertEquals(16, parsedHtml.matches[14].drawProb)
        assertEquals(72, parsedHtml.matches[14].awayProb)

        // Also test WebView DOM table row cell concatenation ("%59%24%17" per A/B/C/D cell)
        val itemsJson = rowData.joinToString(",") { r ->
            val pctCell = "%${r[4]}%${r[5]}%${r[6]}"
            """{"lines":["${r[0]}","${r[1]}","${r[2]}","$pctCell","$pctCell","$pctCell","$pctCell"]}"""
        }
        val domPayload = """{"kind":"dom","weekTitle":"21.11.2026 - 23.11.2026 Programı","weekNumber":"","items":[$itemsJson],"bodyText":""}"""
        val domParsed = NesineSporTotoScraper.parseWebViewExtractorPayload(domPayload)
        assertEquals(15, domParsed.matches.size)
        assertEquals(86, domParsed.matches[2].homeProb)
        assertEquals(9, domParsed.matches[2].drawProb)
        assertEquals(5, domParsed.matches[2].awayProb)
        assertEquals("Atalanta", domParsed.matches[14].homeTeam)
        assertEquals("Inter", domParsed.matches[14].awayTeam)
    }

    @Test
    fun scrapeBulletinRejectsUnrenderedJsTemplatesAndFallsBackToWebViewCombinedDom() = runBlocking {
        val spaHtmlWithBetslipTemplates = """
            <!doctype html>
            <html>
            <body>
              <div id="app"></div>
              <script type="text/template" id="kupon-template">
                <div class="bet-row">
                  <span>{% if (event.banked) {%}</span>
                  <span>{{event.name}}</span>
                </div>
                <div class="bet-row">
                  <span>{% if (event.sportId > 0 || event.isLive) {%}</span>
                  <span>{{event.marketName}} -</span>
                </div>
                <table><tbody>
                  <tr><td>Toplam Oran</td><td>{{model.totalOdd}}</td></tr>
                  <tr><td>Misli</td><td>{{model.multiplier}}</td></tr>
                </tbody></table>
              </script>
              <template>
                <tr><td>{{item.homeTeam}} - {{item.awayTeam}}</td><td>1 - 0</td></tr>
              </template>
            </body>
            </html>
        """.trimIndent()

        // Direct HTML parse must extract ZERO fake template rows
        val parsedDirect = NesineSporTotoScraper.parseHtmlDocument(spaHtmlWithBetslipTemplates)
        assertTrue(parsedDirect.matches.isEmpty())

        // Combined WebView payload with a decoy captured XHR + real DOM rows must pick real DOM rows
        val realRows = (1..15).joinToString(",") { idx ->
            """{"lines":["$idx","21.11.2026 16:00","EvSahibi$idx - Deplasman$idx","%55%25%20"]}"""
        }
        val combinedPayload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": ["{\"Matches\":[],\"config\":{\"home\":\"tr\"}}"],
              "items": [$realRows],
              "bodyText": "",
              "weekTitle": "21.11.2026 - 23.11.2026 Programı",
              "weekNumber": "368"
            }
        """.trimIndent()
        val combinedParsed = NesineSporTotoScraper.parseWebViewExtractorPayload(combinedPayload)
        assertEquals(15, combinedParsed.matches.size)
        assertEquals("EvSahibi1", combinedParsed.matches[0].homeTeam)
        assertEquals("Deplasman15", combinedParsed.matches[14].awayTeam)

        // End-to-end scrapeBulletin ignores template HTML and uses Stage 2 WebView
        val web15 = (1..15).map { idx ->
            NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "GerçekEv$idx",
                awayTeam = "GerçekDep$idx",
                homeProb = 50,
                drawProb = 28,
                awayProb = 22
            )
        }
        var webCalled = 0
        val scraper = NesineSporTotoScraper(
            httpFetcher = { Result.success(spaHtmlWithBetslipTemplates) },
            webViewFetcher = { url ->
                webCalled++
                assertEquals(NESINE_SPORTOTO_URL, url)
                Result.success(
                    NesineSporTotoScraper.NesineWebViewPayload(
                        matches = web15,
                        weekTitle = "368. Hafta",
                        weekNumber = 368
                    )
                )
            }
        )
        val result = scraper.scrapeBulletin().getOrThrow()
        assertEquals(1, webCalled)
        assertEquals(15, result.matches.size)
        assertEquals("GerçekEv1", result.matches[0].homeTeam)
    }
}
