package com.example.engine

import com.example.model.Coupon
import com.example.model.CouponPoolEvaluator
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch

enum class SurvivalTier(
    val targetHits: Int,
    val label: String,
    val badgeText: String
) {
    FIFTEEN(15, "15 Yolunda", "15 YOLUNDA"),
    FOURTEEN(14, "14 Yolunda", "14 YOLUNDA"),
    THIRTEEN(13, "13 Yolunda", "13 YOLUNDA"),
    TWELVE(12, "12 Yolunda", "12 YOLUNDA"),
    ELIMINATED(0, "Elendi", "ELENDİ")
}

data class LiveBulletinMatchState(
    val matchIndex: Int,
    val matchNumber: Int,
    val homeTeam: String,
    val awayTeam: String,
    val matchDate: String,
    val liveScore: MatchLiveScore? = null,
    val outcome: PredictionOutcome? = null,
    val status: MatchStatus = MatchStatus.UPCOMING,
    val isManualOverride: Boolean = false
) {
    val isResolved: Boolean get() = outcome != null && status != MatchStatus.UPCOMING
    val isLive: Boolean get() = status == MatchStatus.LIVE || status == MatchStatus.HALF_TIME
    val isFinished: Boolean get() = status == MatchStatus.FINISHED
}

data class LiveColumnSurvival(
    val columnIndex: Int, // 1-based
    val predictions: List<PredictionOutcome>,
    val finishedCorrect: Int,
    val finishedWrong: Int,
    val liveCorrect: Int,
    val liveWrong: Int
) {
    val totalCorrect: Int get() = finishedCorrect + liveCorrect
    val totalWrong: Int get() = finishedWrong + liveWrong
    val resolvedMatchesCount: Int get() = totalCorrect + totalWrong
    val currentMaxPossible: Int get() = (15 - totalWrong).coerceIn(0, 15)
    val guaranteedMaxPossible: Int get() = (15 - finishedWrong).coerceIn(0, 15)

    val bestLiveTier: SurvivalTier
        get() = when (totalWrong) {
            0 -> SurvivalTier.FIFTEEN
            1 -> SurvivalTier.FOURTEEN
            2 -> SurvivalTier.THIRTEEN
            3 -> SurvivalTier.TWELVE
            else -> SurvivalTier.ELIMINATED
        }

    val isAliveFor12OrBetter: Boolean get() = totalWrong <= 3
}

data class CouponLiveSurvivalSummary(
    val coupon: Coupon,
    val totalColumns: Int,
    // Cumulative surviving column counts (e.g. a 0-wrong column counts for 15, 14, 13, 12)
    val aliveFor15: Int,
    val aliveFor14: Int,
    val aliveFor13: Int,
    val aliveFor12: Int,
    // Exact peak-tier buckets (disjoint; sum == totalColumns)
    val exact15Columns: Int,
    val exact14Columns: Int,
    val exact13Columns: Int,
    val exact12Columns: Int,
    val eliminatedColumns: Int,
    val bestMaxPossible: Int,
    val bestGuaranteedPossible: Int,
    val bestFinishedHits: Int,
    val bestLiveHits: Int,
    val topColumns: List<LiveColumnSurvival>
) {
    val bestTier: SurvivalTier
        get() = when {
            aliveFor15 > 0 -> SurvivalTier.FIFTEEN
            aliveFor14 > 0 -> SurvivalTier.FOURTEEN
            aliveFor13 > 0 -> SurvivalTier.THIRTEEN
            aliveFor12 > 0 -> SurvivalTier.TWELVE
            else -> SurvivalTier.ELIMINATED
        }

    val isAlive12To15: Boolean get() = aliveFor12 > 0

    fun isAliveForTier(tier: SurvivalTier): Boolean = when (tier) {
        SurvivalTier.FIFTEEN -> aliveFor15 > 0
        SurvivalTier.FOURTEEN -> aliveFor14 > 0
        SurvivalTier.THIRTEEN -> aliveFor13 > 0
        SurvivalTier.TWELVE -> aliveFor12 > 0
        SurvivalTier.ELIMINATED -> eliminatedColumns > 0
    }

    fun aliveCountFor(tier: SurvivalTier): Int = when (tier) {
        SurvivalTier.FIFTEEN -> aliveFor15
        SurvivalTier.FOURTEEN -> aliveFor14
        SurvivalTier.THIRTEEN -> aliveFor13
        SurvivalTier.TWELVE -> aliveFor12
        SurvivalTier.ELIMINATED -> eliminatedColumns
    }

    fun exactCountFor(tier: SurvivalTier): Int = when (tier) {
        SurvivalTier.FIFTEEN -> exact15Columns
        SurvivalTier.FOURTEEN -> exact14Columns
        SurvivalTier.THIRTEEN -> exact13Columns
        SurvivalTier.TWELVE -> exact12Columns
        SurvivalTier.ELIMINATED -> eliminatedColumns
    }
}

data class LiveBulletinSnapshot(
    val bulletinMatches: List<LiveBulletinMatchState>,
    val finishedCount: Int,
    val liveCount: Int,
    val upcomingCount: Int,
    val coupons: List<CouponLiveSurvivalSummary>,
    val couponsAliveFor15: Int,
    val couponsAliveFor14: Int,
    val couponsAliveFor13: Int,
    val couponsAliveFor12: Int,
    val totalColumnsAliveFor15: Int,
    val totalColumnsAliveFor14: Int,
    val totalColumnsAliveFor13: Int,
    val totalColumnsAliveFor12: Int
) {
    val resolvedCount: Int get() = finishedCount + liveCount

    fun filterCoupons(tierFilter: SurvivalTier?): List<CouponLiveSurvivalSummary> {
        if (tierFilter == null) return coupons
        if (tierFilter == SurvivalTier.ELIMINATED) {
            return coupons.filter { !it.isAlive12To15 }
        }
        return coupons.filter { it.isAliveForTier(tierFilter) }
    }
}

object LiveBulletinTracker {

    fun evaluate(
        matches: List<TotoMatch>,
        liveScores: List<MatchLiveScore>,
        coupons: List<Coupon>,
        manualOverrides: Map<Int, PredictionOutcome> = emptyMap()
    ): LiveBulletinSnapshot {
        val orderedMatches = matches.sortedBy { it.matchNumber }.take(15)
        val matchedScores = if (orderedMatches.isNotEmpty()) {
            CouponPoolEvaluator.resolveBulletinScores(orderedMatches, liveScores)
        } else {
            emptyList()
        }

        val bulletinStates = (0 until 15).map { idx ->
            val totoMatch = orderedMatches.getOrNull(idx)
            val matchedScore = matchedScores.getOrNull(idx)
            val manualOutcome = manualOverrides[idx]
            val effectiveOutcome = manualOutcome ?: matchedScore?.winningOutcome
            val effectiveStatus = when {
                manualOutcome != null -> MatchStatus.FINISHED
                matchedScore != null && matchedScore.winningOutcome != null -> matchedScore.status
                else -> MatchStatus.UPCOMING
            }
            LiveBulletinMatchState(
                matchIndex = idx,
                matchNumber = totoMatch?.matchNumber ?: (idx + 1),
                homeTeam = totoMatch?.homeTeam ?: matchedScore?.homeTeam ?: "Maç ${idx + 1}",
                awayTeam = totoMatch?.awayTeam ?: matchedScore?.awayTeam ?: "Deplasman",
                matchDate = totoMatch?.matchDate.orEmpty(),
                liveScore = matchedScore,
                outcome = effectiveOutcome,
                status = effectiveStatus,
                isManualOverride = manualOutcome != null
            )
        }

        val finishedCount = bulletinStates.count { it.isFinished }
        val liveCount = bulletinStates.count { it.isLive }
        val upcomingCount = (15 - finishedCount - liveCount).coerceAtLeast(0)

        val evaluatedCoupons = coupons.map { coupon ->
            evaluateCoupon(coupon, bulletinStates)
        }.sortedWith(
            compareByDescending<CouponLiveSurvivalSummary> { it.bestMaxPossible }
                .thenByDescending { it.aliveFor15 }
                .thenByDescending { it.aliveFor14 }
                .thenByDescending { it.aliveFor13 }
                .thenByDescending { it.aliveFor12 }
                .thenByDescending { it.bestLiveHits }
                .thenByDescending { it.coupon.createdAt }
        )

        return LiveBulletinSnapshot(
            bulletinMatches = bulletinStates,
            finishedCount = finishedCount,
            liveCount = liveCount,
            upcomingCount = upcomingCount,
            coupons = evaluatedCoupons,
            couponsAliveFor15 = evaluatedCoupons.count { it.aliveFor15 > 0 },
            couponsAliveFor14 = evaluatedCoupons.count { it.aliveFor14 > 0 },
            couponsAliveFor13 = evaluatedCoupons.count { it.aliveFor13 > 0 },
            couponsAliveFor12 = evaluatedCoupons.count { it.aliveFor12 > 0 },
            totalColumnsAliveFor15 = evaluatedCoupons.sumOf { it.aliveFor15 },
            totalColumnsAliveFor14 = evaluatedCoupons.sumOf { it.aliveFor14 },
            totalColumnsAliveFor13 = evaluatedCoupons.sumOf { it.aliveFor13 },
            totalColumnsAliveFor12 = evaluatedCoupons.sumOf { it.aliveFor12 }
        )
    }

    fun evaluateCoupon(
        coupon: Coupon,
        bulletinStates: List<LiveBulletinMatchState>
    ): CouponLiveSurvivalSummary {
        val columnResults = coupon.columns.mapIndexed { colIdx, col ->
            var finishedCorrect = 0
            var finishedWrong = 0
            var liveCorrect = 0
            var liveWrong = 0

            for (matchIdx in 0 until 15) {
                val state = bulletinStates.getOrNull(matchIdx) ?: continue
                val actual = state.outcome ?: continue
                val predicted = col.predictions.getOrNull(matchIdx) ?: continue
                val isCorrect = predicted == actual
                when (state.status) {
                    MatchStatus.FINISHED -> if (isCorrect) finishedCorrect++ else finishedWrong++
                    MatchStatus.LIVE, MatchStatus.HALF_TIME -> if (isCorrect) liveCorrect++ else liveWrong++
                    MatchStatus.UPCOMING -> Unit
                }
            }

            LiveColumnSurvival(
                columnIndex = colIdx + 1,
                predictions = col.predictions,
                finishedCorrect = finishedCorrect,
                finishedWrong = finishedWrong,
                liveCorrect = liveCorrect,
                liveWrong = liveWrong
            )
        }

        val aliveFor15 = columnResults.count { it.totalWrong == 0 }
        val aliveFor14 = columnResults.count { it.totalWrong <= 1 }
        val aliveFor13 = columnResults.count { it.totalWrong <= 2 }
        val aliveFor12 = columnResults.count { it.totalWrong <= 3 }

        val exact15 = columnResults.count { it.totalWrong == 0 }
        val exact14 = columnResults.count { it.totalWrong == 1 }
        val exact13 = columnResults.count { it.totalWrong == 2 }
        val exact12 = columnResults.count { it.totalWrong == 3 }
        val eliminated = columnResults.count { it.totalWrong >= 4 }

        val topColumns = columnResults
            .sortedWith(
                compareBy<LiveColumnSurvival> { it.totalWrong }
                    .thenBy { it.finishedWrong }
                    .thenByDescending { it.totalCorrect }
                    .thenBy { it.columnIndex }
            )
            .take(5)

        return CouponLiveSurvivalSummary(
            coupon = coupon,
            totalColumns = coupon.totalColumns,
            aliveFor15 = aliveFor15,
            aliveFor14 = aliveFor14,
            aliveFor13 = aliveFor13,
            aliveFor12 = aliveFor12,
            exact15Columns = exact15,
            exact14Columns = exact14,
            exact13Columns = exact13,
            exact12Columns = exact12,
            eliminatedColumns = eliminated,
            bestMaxPossible = columnResults.maxOfOrNull { it.currentMaxPossible } ?: 0,
            bestGuaranteedPossible = columnResults.maxOfOrNull { it.guaranteedMaxPossible } ?: 0,
            bestFinishedHits = columnResults.maxOfOrNull { it.finishedCorrect } ?: 0,
            bestLiveHits = columnResults.maxOfOrNull { it.totalCorrect } ?: 0,
            topColumns = topColumns
        )
    }
}
