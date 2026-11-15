package com.example

import com.example.engine.LiveBulletinTracker
import com.example.engine.SurvivalTier
import com.example.model.Coupon
import com.example.model.FormulaType
import com.example.model.MatchLiveScore
import com.example.model.MatchProbability
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoColumn
import com.example.model.TotoMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBulletinTrackerTest {

    private fun sampleBulletin(): List<TotoMatch> {
        val teams = listOf(
            "Türkiye" to "Fransa",
            "Fenerbahçe" to "Galatasaray",
            "İstanbul Başakşehir" to "Beşiktaş",
            "Trabzonspor" to "Konyaspor",
            "Çaykur Rizespor" to "Samsunspor",
            "Antalyaspor" to "Alanyaspor",
            "Gaziantep FK" to "Kasımpaşa",
            "Kayserispor" to "Sivasspor",
            "Real Madrid" to "Barcelona",
            "Bayern Münih" to "Dortmund",
            "Arsenal" to "Chelsea",
            "Man. City" to "Liverpool",
            "Inter Milan" to "AC Milan",
            "PSG" to "Lyon",
            "Benfica" to "Porto"
        )
        return teams.mapIndexed { idx, (home, away) ->
            TotoMatch(
                id = idx + 1,
                matchNumber = idx + 1,
                homeTeam = home,
                awayTeam = away,
                matchDate = "",
                probability = MatchProbability(
                    home = listOf(50),
                    draw = listOf(25),
                    away = listOf(25)
                )
            )
        }
    }

    private fun columnOf(vararg firstOutcomes: PredictionOutcome): TotoColumn {
        val preds = (0 until 15).map { idx ->
            firstOutcomes.getOrNull(idx) ?: PredictionOutcome.HOME
        }
        return TotoColumn(index = 1, predictions = preds)
    }

    @Test
    fun evaluatesCouponsAcrossAll12To15TiersAndRanksCorrectly() {
        val bulletin = sampleBulletin()
        // First 4 bulletin matches have results: HOME, HOME, DRAW, AWAY
        val liveScores = listOf(
            MatchLiveScore(101, "Turkey", "France", 2, 1, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(102, "Fenerbahce", "Galatasaray", 1, 0, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(103, "Basaksehir", "Besiktas", 1, 1, MatchStatus.LIVE, "74'"),
            MatchLiveScore(104, "Trabzonspor", "Konyaspor", 0, 2, MatchStatus.HALF_TIME, "İY")
        )

        val coupon15 = Coupon(
            id = "c15",
            title = "15 Kuponum",
            formulaType = FormulaType.GUARANTEE_15,
            columns = listOf(
                columnOf(PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.DRAW, PredictionOutcome.AWAY)
            )
        )
        val coupon14 = Coupon(
            id = "c14",
            title = "14 Kuponum",
            formulaType = FormulaType.GUARANTEE_14,
            columns = listOf(
                // 1 wrong (match 4 predicted HOME instead of AWAY)
                columnOf(PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.DRAW, PredictionOutcome.HOME)
            )
        )
        val coupon13 = Coupon(
            id = "c13",
            title = "13 Kuponum",
            formulaType = FormulaType.GUARANTEE_13,
            columns = listOf(
                // 2 wrong (match 3, 4)
                columnOf(PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.HOME)
            )
        )
        val coupon12 = Coupon(
            id = "c12",
            title = "12 Kuponum",
            formulaType = FormulaType.GUARANTEE_12,
            columns = listOf(
                // 3 wrong (match 2, 3, 4)
                columnOf(PredictionOutcome.HOME, PredictionOutcome.AWAY, PredictionOutcome.HOME, PredictionOutcome.HOME)
            )
        )
        val couponEliminated = Coupon(
            id = "c0",
            title = "Yanan Kupon",
            formulaType = FormulaType.GUARANTEE_12,
            columns = listOf(
                // 4 wrong
                columnOf(PredictionOutcome.AWAY, PredictionOutcome.AWAY, PredictionOutcome.HOME, PredictionOutcome.HOME)
            )
        )

        val snapshot = LiveBulletinTracker.evaluate(
            matches = bulletin,
            liveScores = liveScores,
            coupons = listOf(couponEliminated, coupon12, coupon15, coupon13, coupon14)
        )

        assertEquals(2, snapshot.finishedCount)
        assertEquals(2, snapshot.liveCount)
        assertEquals(11, snapshot.upcomingCount)
        assertEquals(4, snapshot.resolvedCount)

        // Sorted order: 15 -> 14 -> 13 -> 12 -> Eliminated
        assertEquals(listOf("c15", "c14", "c13", "c12", "c0"), snapshot.coupons.map { it.coupon.id })

        // Aggregate coupon survival counts (cumulative)
        assertEquals(1, snapshot.couponsAliveFor15)
        assertEquals(2, snapshot.couponsAliveFor14)
        assertEquals(3, snapshot.couponsAliveFor13)
        assertEquals(4, snapshot.couponsAliveFor12)

        assertEquals(SurvivalTier.FIFTEEN, snapshot.coupons[0].bestTier)
        assertEquals(15, snapshot.coupons[0].bestMaxPossible)
        assertEquals(SurvivalTier.FOURTEEN, snapshot.coupons[1].bestTier)
        assertEquals(14, snapshot.coupons[1].bestMaxPossible)
        // Match 4 is only HALF_TIME (live), so guaranteedMaxPossible is still 15!
        assertEquals(15, snapshot.coupons[1].bestGuaranteedPossible)

        assertEquals(SurvivalTier.THIRTEEN, snapshot.coupons[2].bestTier)
        assertEquals(SurvivalTier.TWELVE, snapshot.coupons[3].bestTier)
        assertEquals(SurvivalTier.ELIMINATED, snapshot.coupons[4].bestTier)
        assertFalse(snapshot.coupons[4].isAlive12To15)

        // Filtering checks
        assertEquals(listOf("c15"), snapshot.filterCoupons(SurvivalTier.FIFTEEN).map { it.coupon.id })
        assertEquals(listOf("c15", "c14"), snapshot.filterCoupons(SurvivalTier.FOURTEEN).map { it.coupon.id })
        assertEquals(listOf("c15", "c14", "c13"), snapshot.filterCoupons(SurvivalTier.THIRTEEN).map { it.coupon.id })
        assertEquals(listOf("c15", "c14", "c13", "c12"), snapshot.filterCoupons(SurvivalTier.TWELVE).map { it.coupon.id })
        assertTrue(snapshot.filterCoupons(SurvivalTier.ELIMINATED).single().coupon.id == "c0")
    }

    @Test
    fun multiColumnCouponAggregatesExactAndCumulativeTierCounts() {
        val bulletin = sampleBulletin()
        val liveScores = listOf(
            MatchLiveScore(201, "Turkey", "France", 1, 0, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(202, "Fenerbahçe", "Galatasaray", 0, 0, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(203, "İstanbul Başakşehir", "Beşiktaş", 0, 1, MatchStatus.FINISHED, "MS"),
            MatchLiveScore(204, "Trabzonspor", "Konyaspor", 3, 1, MatchStatus.FINISHED, "MS")
        )
        // Actual outcomes: HOME, DRAW, AWAY, HOME
        val multiCoupon = Coupon(
            id = "multi",
            title = "Sistem Kuponu",
            formulaType = FormulaType.GUARANTEE_14,
            columns = listOf(
                columnOf(PredictionOutcome.HOME, PredictionOutcome.DRAW, PredictionOutcome.AWAY, PredictionOutcome.HOME), // 0 wrong -> 15
                columnOf(PredictionOutcome.HOME, PredictionOutcome.DRAW, PredictionOutcome.AWAY, PredictionOutcome.DRAW), // 1 wrong -> 14
                columnOf(PredictionOutcome.HOME, PredictionOutcome.DRAW, PredictionOutcome.HOME, PredictionOutcome.DRAW), // 2 wrong -> 13
                columnOf(PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.DRAW), // 3 wrong -> 12
                columnOf(PredictionOutcome.AWAY, PredictionOutcome.HOME, PredictionOutcome.HOME, PredictionOutcome.DRAW)  // 4 wrong -> busted
            )
        )

        val snapshot = LiveBulletinTracker.evaluate(bulletin, liveScores, listOf(multiCoupon))
        val summary = snapshot.coupons.single()

        assertEquals(5, summary.totalColumns)
        assertEquals(1, summary.aliveFor15)
        assertEquals(2, summary.aliveFor14)
        assertEquals(3, summary.aliveFor13)
        assertEquals(4, summary.aliveFor12)
        assertEquals(1, summary.eliminatedColumns)

        assertEquals(1, summary.exact15Columns)
        assertEquals(1, summary.exact14Columns)
        assertEquals(1, summary.exact13Columns)
        assertEquals(1, summary.exact12Columns)
    }
}
