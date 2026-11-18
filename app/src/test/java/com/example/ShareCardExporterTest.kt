package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.LiveBulletinTracker
import com.example.engine.ShareCardConfig
import com.example.engine.ShareCardExporter
import com.example.engine.ShareCardTheme
import com.example.engine.ShareColumnScope
import com.example.model.Coupon
import com.example.model.FormulaType
import com.example.model.MatchLiveScore
import com.example.model.MatchProbability
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoColumn
import com.example.model.TotoMatch
import com.example.ui.screens.ScoreboardStatusFilter
import com.example.ui.screens.buildScoreboardRows
import com.example.ui.screens.filterScoreboardRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareCardExporterTest {

    private fun sampleMatches(): List<TotoMatch> {
        return (1..15).map { idx ->
            TotoMatch(
                id = idx,
                matchNumber = idx,
                homeTeam = "EvSahibi$idx",
                awayTeam = "Konuk$idx",
                matchDate = "2026-11-21T16:00:00Z",
                probability = MatchProbability(listOf(50), listOf(25), listOf(25))
            )
        }
    }

    private fun sampleCoupon(columnCount: Int = 10): Coupon {
        val cols = (1..columnCount).map { colIdx ->
            val preds = (0 until 15).map { matchIdx ->
                when ((colIdx + matchIdx) % 3) {
                    0 -> PredictionOutcome.HOME
                    1 -> PredictionOutcome.DRAW
                    else -> PredictionOutcome.AWAY
                }
            }
            TotoColumn(index = colIdx, predictions = preds)
        }
        return Coupon(
            id = "coupon_share_test",
            title = "14 Garanti Sistemim",
            formulaType = FormulaType.GUARANTEE_14,
            columns = cols
        )
    }

    @Test
    fun resolveVisibleColumnsHandlesAllScopesAndIndexBounds() {
        val coupon = sampleCoupon(10)
        val matches = sampleMatches()

        val featured = ShareCardExporter.resolveVisibleColumns(
            ShareCardConfig(coupon, matches, "Ahmet", scope = ShareColumnScope.FEATURED_COLUMNS)
        )
        assertEquals(8, featured.size)

        val distributionOnly = ShareCardExporter.resolveVisibleColumns(
            ShareCardConfig(coupon, matches, "Ahmet", scope = ShareColumnScope.DISTRIBUTION_ONLY)
        )
        assertTrue(distributionOnly.isEmpty())

        val clampedHigh = ShareCardExporter.resolveVisibleColumns(
            ShareCardConfig(coupon, matches, "Ahmet", scope = ShareColumnScope.SINGLE_COLUMN, selectedColumnIndex = 99)
        )
        assertEquals(1, clampedHigh.size)
        assertEquals(10, clampedHigh.first().index)

        val clampedLow = ShareCardExporter.resolveVisibleColumns(
            ShareCardConfig(coupon, matches, "Ahmet", scope = ShareColumnScope.SINGLE_COLUMN, selectedColumnIndex = -4)
        )
        assertEquals(1, clampedLow.first().index)
    }

    @Test
    fun formatShareTextIncludesNoteColumnsAndTeamToggle() {
        val coupon = sampleCoupon(3)
        val matches = sampleMatches()

        val singleWithTeams = ShareCardExporter.formatShareText(
            ShareCardConfig(
                coupon = coupon,
                matches = matches,
                authorName = "KralTahminci",
                weekLabel = "16. Hafta",
                scope = ShareColumnScope.SINGLE_COLUMN,
                selectedColumnIndex = 1,
                showTeamNames = true,
                customNote = "Sürpriz beraberliklere dikkat"
            )
        )
        assertTrue(singleWithTeams.contains("14 Garanti Sistemim"))
        assertTrue(singleWithTeams.contains("16. Hafta"))
        assertTrue(singleWithTeams.contains("KralTahminci"))
        assertTrue(singleWithTeams.contains("Sürpriz beraberliklere dikkat"))
        assertTrue(singleWithTeams.contains("Kolon #2:"))
        assertTrue(singleWithTeams.contains("EvSahibi1 - Konuk1"))

        val distWithoutTeams = ShareCardExporter.formatShareText(
            ShareCardConfig(
                coupon = coupon,
                matches = matches,
                authorName = "",
                scope = ShareColumnScope.DISTRIBUTION_ONLY,
                showTeamNames = false
            )
        )
        assertTrue(distWithoutTeams.contains("Totom Üyesi"))
        assertTrue(distWithoutTeams.contains("Maç 15"))
        assertFalse(distWithoutTeams.contains("EvSahibi15"))
    }

    @Test
    fun renderBitmapAndWritePngToCacheProducesValidImageForAllThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coupon = sampleCoupon(4)
        val matches = sampleMatches()

        ShareCardTheme.values().forEach { theme ->
            ShareColumnScope.values().forEach { scope ->
                val config = ShareCardConfig(
                    coupon = coupon,
                    matches = matches,
                    authorName = "TotomUsta",
                    weekLabel = "Hafta 12",
                    theme = theme,
                    scope = scope,
                    showTeamNames = true,
                    customNote = "Test PNG çıktısı"
                )
                val bitmap = ShareCardExporter.renderBitmap(config)
                assertEquals(1080, bitmap.width)
                assertTrue(bitmap.height in 720..2400)
                bitmap.recycle()
            }
        }

        val bitmap = ShareCardExporter.renderBitmap(
            ShareCardConfig(coupon = coupon, matches = matches, authorName = "TotomUsta")
        )
        val file = ShareCardExporter.writePngToCacheFile(context, bitmap, coupon.id)
        assertTrue(file.exists())
        assertTrue(file.length() > 1000L)
        bitmap.recycle()
        file.delete()
    }

    @Test
    fun liveScoreboardBuildsAndFilters15BulletinRowsAccurately() {
        val matches = sampleMatches()
        val liveScores = listOf(
            MatchLiveScore(
                matchNumber = 1,
                homeTeam = "EvSahibi1",
                awayTeam = "Konuk1",
                homeScore = 2,
                awayScore = 1,
                status = MatchStatus.LIVE,
                minuteText = "64'",
                league = "Süper Lig"
            ),
            MatchLiveScore(
                matchNumber = 2,
                homeTeam = "EvSahibi2",
                awayTeam = "Konuk2",
                homeScore = 0,
                awayScore = 0,
                status = MatchStatus.HALF_TIME,
                minuteText = "İY",
                league = "Süper Lig"
            ),
            MatchLiveScore(
                matchNumber = 3,
                homeTeam = "EvSahibi3",
                awayTeam = "Konuk3",
                homeScore = 1,
                awayScore = 3,
                status = MatchStatus.FINISHED,
                minuteText = "MS",
                league = "Süper Lig"
            )
        )
        val snapshot = LiveBulletinTracker.evaluate(matches, liveScores, emptyList())
        val rows = buildScoreboardRows(matches, snapshot, liveScores)

        assertEquals(15, rows.size)
        assertEquals("2 - 1", rows[0].scoreDisplay)
        assertEquals("64'", rows[0].statusDetail)
        assertEquals(PredictionOutcome.HOME, rows[0].winningOutcome)
        assertEquals("İY", rows[1].statusDetail)
        assertEquals("MS", rows[2].statusDetail)
        assertEquals(PredictionOutcome.AWAY, rows[2].winningOutcome)

        assertEquals(15, filterScoreboardRows(rows, ScoreboardStatusFilter.ALL).size)
        assertEquals(2, filterScoreboardRows(rows, ScoreboardStatusFilter.LIVE).size)
        assertEquals(1, filterScoreboardRows(rows, ScoreboardStatusFilter.FINISHED).size)
        assertEquals(12, filterScoreboardRows(rows, ScoreboardStatusFilter.UPCOMING).size)
    }
}
