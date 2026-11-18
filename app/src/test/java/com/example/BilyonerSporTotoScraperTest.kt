package com.example

import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.network.BILYONER_SPORTOTO_RESULTS_URL
import com.example.network.BILYONER_SPORTOTO_URL
import com.example.network.BilyonerLiveScraper
import com.example.network.BilyonerSporTotoScraper
import com.example.repository.LiveScoreSource
import com.example.repository.SporTotoBulletinRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BilyonerSporTotoScraperTest {

    @Test
    fun parseHtmlDocumentExtracts15SporTotoMatchesWithPercentagesAndIgnoresTemplates() {
        val rows = (1..15).joinToString("\n") { idx ->
            """
            <tr class="sportoto-row">
              <td>$idx</td>
              <td>21.11.2026 19:00</td>
              <td>EvTakim$idx - KonukTakim$idx</td>
              <td>%55</td>
              <td>%25</td>
              <td>%20</td>
            </tr>
            """.trimIndent()
        }
        val html = """
            <!doctype html>
            <html>
            <body>
              <h1>Spor Toto 368. Hafta</h1>
              <script type="text/template">
                <tr>
                  <td>1</td>
                  <td>{% if (event.banked) {%} - {{event.name}}</td>
                  <td>TOPLAM ORAN - {{model.totalOdd}}</td>
                </tr>
              </script>
              <template>
                <div class="sportoto-row">MİSLİ - {{model.multiplier}}</div>
              </template>
              <table class="sportoto-table">
                <thead><tr><th>No</th><th>Tarih</th><th>Karşılaşma</th></tr></thead>
                <tbody>
                  $rows
                </tbody>
              </table>
            </body>
            </html>
        """.trimIndent()

        val payload = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(368, payload.weekNumber)
        assertEquals(15, payload.matches.size)
        assertEquals("EvTakim1", payload.matches[0].homeTeam)
        assertEquals("KonukTakim1", payload.matches[0].awayTeam)
        assertEquals("21.11.2026 19:00", payload.matches[0].matchDate)
        assertEquals(55, payload.matches[0].homeProb)
        assertEquals(25, payload.matches[0].drawProb)
        assertEquals(20, payload.matches[0].awayProb)
        assertEquals("EvTakim15", payload.matches[14].homeTeam)
        assertTrue(payload.matches.none { it.homeTeam.contains("event", true) || it.homeTeam.contains("TOPLAM", true) })
    }

    @Test
    fun repositoryFetchesWeeklyBulletinFromBilyonerSporTotoUrl() = runBlocking {
        val sample15 = (1..15).map { idx ->
            com.example.network.NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "BilyonerTotoEv$idx",
                awayTeam = "BilyonerTotoDep$idx",
                matchDate = "22.11.2026 20:00",
                league = "Bilyoner Spor Toto",
                homeProb = 50,
                drawProb = 26,
                awayProb = 24
            )
        }
        var requestedUrl = ""
        val totoScraper = BilyonerSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = { url ->
                requestedUrl = url
                Result.success(
                    BilyonerSporTotoScraper.BilyonerSporTotoPayload(
                        matches = sample15,
                        weekTitle = "368. Hafta",
                        weekNumber = 368
                    )
                )
            }
        )
        val liveScraper = BilyonerLiveScraper(
            httpFetcher = { Result.failure(IllegalStateException("no live")) },
            webViewFetcher = { Result.failure(IllegalStateException("no live")) }
        )

        val repo = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            bilyonerScraper = liveScraper,
            initialSource = LiveScoreSource.BILYONER_LIVE,
            enforceNesineOnly = false,
            allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
            allowOfflineBulletinFallback = false,
            bilyonerSporTotoScraper = totoScraper
        )

        val bulletin = repo.getWeeklyBulletin(forceRefresh = true).getOrThrow()
        assertEquals(BILYONER_SPORTOTO_URL, requestedUrl)
        assertEquals(15, bulletin.matches.size)
        assertEquals("BİLYONERTOTOEV1", bulletin.matches[0].homeTeam)
        assertTrue(bulletin.bulletinTitle.contains("Bilyoner Spor Toto"))
        assertEquals(50, bulletin.matches[0].probabilities.homeProbability)
        assertEquals(26, bulletin.matches[0].probabilities.drawProbability)
        assertEquals(24, bulletin.matches[0].probabilities.awayProbability)
    }

    @Test
    fun webViewCombinedPayloadSelectsBestSporTotoCandidateFromJsonOrDom() {
        val eventsJson = (1..15).joinToString(",") { idx ->
            """{"order":$idx,"homeTeamName":"TotoEv$idx","awayTeamName":"TotoDep$idx","date":"22.11.2026 19:00","rate1":48,"rate0":27,"rate2":25}"""
        }
        val capturedProgramJson = org.json.JSONObject.quote(
            """{"programNo":369,"matches":[$eventsJson]}"""
        )
        val combinedPayload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": ["{\"menu\":{\"home\":\"Ana Sayfa\"}}", $capturedProgramJson],
              "structuredRows": [],
              "items": [],
              "bodyText": "",
              "weekTitle": "369. Hafta",
              "weekNumber": "369"
            }
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseWebViewExtractorPayload(combinedPayload)
        assertEquals(369, parsed.weekNumber)
        assertEquals(15, parsed.matches.size)
        assertEquals("TotoEv1", parsed.matches[0].homeTeam)
        assertEquals("TotoDep15", parsed.matches[14].awayTeam)
        assertEquals(48, parsed.matches[0].homeProb)
        assertEquals(27, parsed.matches[0].drawProb)
        assertEquals(25, parsed.matches[0].awayProb)
    }

    @Test
    fun scraperRejectsTurkishShortDatesLike21KasFromTeamSlotsAndExtractsRealTeams() {
        val combinedDomPayload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": [],
              "structuredRows": [],
              "items": [
                {"lines": ["02", "21 Kas", "17:00", "Gençlerbirliği", "Kocaelispor", "52", "26", "22"]},
                {"lines": ["03", "21 Kasım Cmt", "19:00", "Amed Sportif Faaliyetler", "Çaykur Rizespor", "%44", "%28", "%28"]},
                {"lines": ["04", "Cmt 21 Kas\n20:00", "Galatasaray A.Ş.\nSamsunspor A.Ş.", "65", "20", "15"]}
              ],
              "bodyText": "",
              "weekTitle": "368. Hafta",
              "weekNumber": "368"
            }
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseWebViewExtractorPayload(combinedDomPayload)
        assertEquals(3, parsed.matches.size)

        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Gençlerbirliği", parsed.matches[0].homeTeam)
        assertEquals("Kocaelispor", parsed.matches[0].awayTeam)
        assertTrue(parsed.matches[0].matchDate.startsWith("21.11."))
        assertTrue(parsed.matches[0].matchDate.endsWith("17:00"))
        assertEquals(52, parsed.matches[0].homeProb)
        assertEquals(26, parsed.matches[0].drawProb)
        assertEquals(22, parsed.matches[0].awayProb)

        assertEquals("Amed Sportif Faaliyetler", parsed.matches[1].homeTeam)
        assertEquals("Çaykur Rizespor", parsed.matches[1].awayTeam)
        assertTrue(parsed.matches[1].matchDate.startsWith("21.11."))
        assertEquals(44, parsed.matches[1].homeProb)

        assertEquals("Galatasaray A.Ş.", parsed.matches[2].homeTeam)
        assertEquals("Samsunspor A.Ş.", parsed.matches[2].awayTeam)
        assertTrue(parsed.matches[2].matchDate.startsWith("21.11."))
        assertTrue(parsed.matches[2].matchDate.endsWith("20:00"))
        assertEquals(65, parsed.matches[2].homeProb)
    }

    @Test
    fun htmlBodyTextFallbackSkipsTurkishDateAndColumnHeaders() {
        val html = """
            <html><body>
              <div>Ev Sahibi</div><div>Deplasman</div><div>Tarih</div>
              <div>02</div><div>21 KAS</div><div>Gençlerbirliği</div><div>Kocaelispor</div><div>51</div><div>27</div><div>22</div>
              <div>03</div><div>22 Kas 16:00</div><div>Fenerbahçe</div><div>Beşiktaş</div><div>60</div><div>22</div><div>18</div>
            </body></html>
        """.trimIndent()
        val parsed = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(2, parsed.matches.size)
        assertEquals("Gençlerbirliği", parsed.matches[0].homeTeam)
        assertEquals("Kocaelispor", parsed.matches[0].awayTeam)
        assertTrue(parsed.matches[0].matchDate.startsWith("21.11."))
        assertEquals(51, parsed.matches[0].homeProb)
        assertEquals("Fenerbahçe", parsed.matches[1].homeTeam)
        assertEquals("Beşiktaş", parsed.matches[1].awayTeam)
        assertTrue(parsed.matches[1].matchDate.startsWith("22.11."))
        assertTrue(parsed.matches[1].matchDate.endsWith("16:00"))
    }

    @Test
    fun parsePreservesFirstMatchWhenColumnHeadersAndNestedDivsPrecedeMatchOne() {
        val html = """
            <html><body>
              <div class="sportoto-program">
                <div>No</div><div>Tarih</div><div>Karşılaşma</div><div>1</div><div>X</div><div>2</div>
                <div class="sportoto-row"><div class="no">#01</div><div class="date">21 Kas 17:00</div><div>Kasımpaşa</div><div>Antalyaspor</div><div>45</div><div>28</div><div>27</div></div>
                <div class="sportoto-row"><div class="no">02</div><div class="date">21 Kas 19:00</div><div>Trabzonspor</div><div>Başakşehir</div><div>52</div><div>26</div><div>22</div></div>
                <div class="sportoto-row"><div class="no">03</div><div class="date">22 Kas 16:00</div><div>Fenerbahçe</div><div>Göztepe</div><div>70</div><div>18</div><div>12</div></div>
              </div>
            </body></html>
        """.trimIndent()
        val parsed = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(3, parsed.matches.size)
        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Kasımpaşa", parsed.matches[0].homeTeam)
        assertEquals("Antalyaspor", parsed.matches[0].awayTeam)
        assertEquals("Trabzonspor", parsed.matches[1].homeTeam)
        assertEquals("Fenerbahçe", parsed.matches[2].homeTeam)
    }

    @Test
    fun parseExactBilyonerSporTotoItemHtmlWithDecimalPercentagesAndNavTabs() {
        val html = """
            <html><body>
              <div class="spor-toto-tabs">
                <div class="spor-toto-tabs__row"><span>OYNA</span><span>KUPONLARIM</span></div>
              </div>
              <div class="spor-toto-item"><div class="spor-toto-item__main-row"><div class="spor-toto-item__index">1</div><div class="spor-toto-item__match-info"><div class="spor-toto-item__date"><span class="spor-toto-item__date-main">21 Kas</span><br><span class="spor-toto-item__date-time">13:30</span></div><div class="spor-toto-item__separator"></div><div class="spor-toto-item__teams"><div class="spor-toto-item__team">Gençlerbirliği
Gaziantep FK A.Ş.</div></div></div><div class="spor-toto-item__betting"><div class="spor-toto-item__pills"><div class="spor-toto-pill"><span class="spor-toto-pill__label">1</span><span class="spor-toto-pill__percentage spor-toto-pill__percentage--highlighted">%51.4</span></div><div class="spor-toto-pill"><span class="spor-toto-pill__label">X</span><span class="spor-toto-pill__percentage">%27.2</span></div><div class="spor-toto-pill"><span class="spor-toto-pill__label">2</span><span class="spor-toto-pill__percentage">%21.4</span></div></div></div></div></div>
              <div class="spor-toto-item"><div class="spor-toto-item__main-row"><div class="spor-toto-item__index">2</div><div class="spor-toto-item__match-info"><div class="spor-toto-item__date"><span class="spor-toto-item__date-main">21 Kas</span><br><span class="spor-toto-item__date-time">16:00</span></div><div class="spor-toto-item__separator"></div><div class="spor-toto-item__teams"><div class="spor-toto-item__team">Amed Sportif Faaliyetler
Çaykur Rizespor A.Ş.</div></div></div><div class="spor-toto-item__betting"><div class="spor-toto-item__pills"><div class="spor-toto-pill"><span class="spor-toto-pill__label">1</span><span class="spor-toto-pill__percentage spor-toto-pill__percentage--highlighted">%72.2</span></div><div class="spor-toto-pill"><span class="spor-toto-pill__label">X</span><span class="spor-toto-pill__percentage">%15.7</span></div><div class="spor-toto-pill"><span class="spor-toto-pill__label">2</span><span class="spor-toto-pill__percentage">%12.1</span></div></div></div></div></div>
            </body></html>
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(2, parsed.matches.size)
        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Gençlerbirliği", parsed.matches[0].homeTeam)
        assertEquals("Gaziantep FK A.Ş.", parsed.matches[0].awayTeam)
        assertTrue(parsed.matches[0].matchDate.startsWith("21.11."))
        assertTrue(parsed.matches[0].matchDate.endsWith("13:30"))

        assertEquals(2, parsed.matches[1].matchNumber)
        assertEquals("Amed Sportif Faaliyetler", parsed.matches[1].homeTeam)
        assertEquals("Çaykur Rizespor A.Ş.", parsed.matches[1].awayTeam)
        assertTrue(parsed.matches[1].matchDate.endsWith("16:00"))
        assertEquals(72, parsed.matches[1].homeProb)
        assertEquals(16, parsed.matches[1].drawProb)
        assertEquals(12, parsed.matches[1].awayProb)
        assertTrue(parsed.matches.none { it.homeTeam.equals("OYNA", true) || it.awayTeam.equals("KUPONLARIM", true) })
    }

    @Test
    fun parseCombinedPayloadPrefersSporTotoStructuredRowsAndRejectsNavTabs() {
        val payload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": [],
              "structuredRows": [
                {"matchNo": "1", "home": "Gençlerbirliği", "away": "Gaziantep FK A.Ş.", "status": "21 Kas 13:30", "info": "%51.4 %27.2 %21.4"},
                {"matchNo": "2", "home": "Amed Sportif Faaliyetler", "away": "Çaykur Rizespor A.Ş.", "status": "21 Kas 16:00", "info": "%72.2 %15.7 %12.1"}
              ],
              "items": [
                {"lines": ["OYNA", "KUPONLARIM"]}
              ],
              "bodyText": "",
              "weekTitle": "369. Hafta",
              "weekNumber": "369"
            }
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseWebViewExtractorPayload(payload)
        assertEquals(2, parsed.matches.size)
        assertEquals(1, parsed.matches[0].matchNumber)
        assertEquals("Gençlerbirliği", parsed.matches[0].homeTeam)
        assertEquals(2, parsed.matches[1].matchNumber)
        assertEquals("Amed Sportif Faaliyetler", parsed.matches[1].homeTeam)
        assertEquals(72, parsed.matches[1].homeProb)
        assertEquals(16, parsed.matches[1].drawProb)
        assertEquals(12, parsed.matches[1].awayProb)
    }

    @Test
    fun lopsidedPercentagesAbove92ConvertToBulletinDtoWithoutCoerceCrash() {
        val html = """
            <html><body>
              <div class="spor-toto-item">
                <div class="spor-toto-item__main-row">
                  <div class="spor-toto-item__index">1</div>
                  <div class="spor-toto-item__match-info">
                    <div class="spor-toto-item__date"><span class="spor-toto-item__date-main">21 Kas</span><br><span class="spor-toto-item__date-time">19:00</span></div>
                    <div class="spor-toto-item__teams"><div class="spor-toto-item__team">Galatasaray A.Ş.
Eyüpspor</div></div>
                  </div>
                  <div class="spor-toto-item__betting">
                    <div class="spor-toto-item__pills">
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">1</span><span class="spor-toto-pill__percentage">%95.4</span></div>
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">X</span><span class="spor-toto-pill__percentage">%2.8</span></div>
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">2</span><span class="spor-toto-pill__percentage">%1.8</span></div>
                    </div>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(1, parsed.matches.size)
        val dto = parsed.matches[0].toBulletinMatchDto()
        assertEquals(94, dto.probabilities.homeProbability)
        assertEquals(3, dto.probabilities.drawProbability)
        assertEquals(3, dto.probabilities.awayProbability)
        assertEquals(100, dto.probabilities.homeProbability + dto.probabilities.drawProbability + dto.probabilities.awayProbability)

        val extremeMatch = com.example.network.NesineSporTotoScrapedMatch(
            matchNumber = 2,
            homeTeam = "Fenerbahçe",
            awayTeam = "Konuk",
            homeProb = 92,
            drawProb = 4,
            awayProb = 3
        )
        val extremeDto = extremeMatch.toBulletinMatchDto()
        assertEquals(100, extremeDto.probabilities.homeProbability + extremeDto.probabilities.drawProbability + extremeDto.probabilities.awayProbability)
        assertTrue(extremeDto.probabilities.drawProbability >= 3)
        assertTrue(extremeDto.probabilities.awayProbability >= 3)
    }

    @Test
    fun parseBilyonerSonuclarHtmlAndCombinedPayloadExtractsScoresAndWinningOutcomes() {
        val html = """
            <html><body>
              <h1>Spor Toto 368. Hafta Sonuçları</h1>
              <div class="spor-toto-item">
                <div class="spor-toto-item__main-row">
                  <div class="spor-toto-item__index">1</div>
                  <div class="spor-toto-item__match-info">
                    <div class="spor-toto-item__date"><span class="spor-toto-item__date-main">14 Kas</span><br><span class="spor-toto-item__date-time">16:00</span></div>
                    <div class="spor-toto-item__teams"><div class="spor-toto-item__team">Fenerbahçe
Sivasspor</div></div>
                    <div class="spor-toto-item__score">3 - 1</div>
                  </div>
                  <div class="spor-toto-item__betting">
                    <div class="spor-toto-item__pills">
                      <div class="spor-toto-pill spor-toto-pill--winner"><span class="spor-toto-pill__label">1</span></div>
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">X</span></div>
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">2</span></div>
                    </div>
                  </div>
                </div>
              </div>
              <div class="spor-toto-item">
                <div class="spor-toto-item__main-row">
                  <div class="spor-toto-item__index">2</div>
                  <div class="spor-toto-item__match-info">
                    <div class="spor-toto-item__date"><span class="spor-toto-item__date-main">15 Kas</span><br><span class="spor-toto-item__date-time">19:00</span></div>
                    <div class="spor-toto-item__teams"><div class="spor-toto-item__team">Beşiktaş
Kasımpaşa</div></div>
                  </div>
                  <div class="spor-toto-item__betting">
                    <div class="spor-toto-item__pills">
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">1</span></div>
                      <div class="spor-toto-pill spor-toto-pill--active"><span class="spor-toto-pill__label">X</span></div>
                      <div class="spor-toto-pill"><span class="spor-toto-pill__label">2</span></div>
                    </div>
                  </div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = BilyonerSporTotoScraper.parseHtmlDocument(html)
        assertEquals(368, parsed.weekNumber)
        assertEquals(2, parsed.matches.size)
        assertEquals(3, parsed.matches[0].homeScore)
        assertEquals(1, parsed.matches[0].awayScore)
        assertEquals(MatchStatus.FINISHED, parsed.matches[0].status)
        assertEquals(PredictionOutcome.HOME, parsed.matches[0].explicitOutcome)

        assertEquals(MatchStatus.FINISHED, parsed.matches[1].status)
        assertEquals(PredictionOutcome.DRAW, parsed.matches[1].explicitOutcome)

        val combinedPayload = """
            {
              "kind": "combined",
              "nextData": "",
              "captured": [],
              "structuredRows": [
                {"matchNo": "1", "home": "Trabzonspor", "away": "Alanyaspor", "status": "14 Kas 20:00", "score": "1 - 2", "outcome": "2", "info": ""}
              ],
              "items": [],
              "bodyText": "",
              "weekTitle": "368. Hafta",
              "weekNumber": "368"
            }
        """.trimIndent()
        val combined = BilyonerSporTotoScraper.parseWebViewExtractorPayload(combinedPayload)
        assertEquals(1, combined.matches.size)
        assertEquals(1, combined.matches[0].homeScore)
        assertEquals(2, combined.matches[0].awayScore)
        assertEquals(MatchStatus.FINISHED, combined.matches[0].status)
        assertEquals(PredictionOutcome.AWAY, combined.matches[0].explicitOutcome)
    }

    @Test
    fun repositoryFetchesPreviousWeekResultsFromBilyonerSonuclarUrl() = runBlocking {
        val finished15 = (1..15).map { idx ->
            com.example.network.NesineSporTotoScrapedMatch(
                matchNumber = idx,
                homeTeam = "EvSonuc$idx",
                awayTeam = "DepSonuc$idx",
                homeScore = if (idx % 2 == 0) 2 else 1,
                awayScore = 1,
                status = MatchStatus.FINISHED,
                matchDate = "14.11.2026 19:00",
                league = "Bilyoner Spor Toto",
                explicitOutcome = if (idx % 2 == 0) PredictionOutcome.HOME else PredictionOutcome.DRAW
            )
        }
        var requestedUrl = ""
        val totoScraper = BilyonerSporTotoScraper(
            httpFetcher = { Result.failure(IllegalStateException("SPA")) },
            webViewFetcher = { url ->
                requestedUrl = url
                Result.success(
                    BilyonerSporTotoScraper.BilyonerSporTotoPayload(
                        matches = finished15,
                        weekTitle = "368. Hafta Sonuçları",
                        weekNumber = 368
                    )
                )
            }
        )
        val liveScraper = BilyonerLiveScraper(
            httpFetcher = { Result.failure(IllegalStateException("no live")) },
            webViewFetcher = { Result.failure(IllegalStateException("no live")) }
        )
        val repo = SporTotoBulletinRepositoryImpl(
            liveScoreApiKey = "",
            bilyonerScraper = liveScraper,
            initialSource = LiveScoreSource.BILYONER_LIVE,
            enforceNesineOnly = false,
            allowedSources = setOf(LiveScoreSource.NESINE_RESULTS, LiveScoreSource.BILYONER_LIVE),
            allowOfflineBulletinFallback = false,
            bilyonerSporTotoScraper = totoScraper
        )

        val report = repo.fetchPreviousWeekBulletinAndResults(forceRefresh = true).getOrThrow()
        assertEquals(BILYONER_SPORTOTO_RESULTS_URL, requestedUrl)
        assertEquals(368, report.weekNumber)
        assertEquals(15, report.matches.size)
        assertEquals(2, report.matches[1].homeScore)
        assertEquals(1, report.matches[1].awayScore)
        assertEquals(PredictionOutcome.HOME, report.matches[1].winningOutcome)
        assertTrue(report.sourceLabel.contains("bilyoner.com/spor-toto/sonuclar"))
    }
}
