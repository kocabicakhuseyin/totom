package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CouponLiveSurvivalSummary
import com.example.engine.LiveBulletinMatchState
import com.example.engine.LiveBulletinSnapshot
import com.example.engine.SurvivalTier
import com.example.model.Coupon
import com.example.model.MatchStatus
import com.example.ui.theme.CasinoGold
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricGreen
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibrantRed

internal fun tierColor(tier: SurvivalTier): Color = when (tier) {
    SurvivalTier.FIFTEEN -> CasinoGold
    SurvivalTier.FOURTEEN -> ElectricCyan
    SurvivalTier.THIRTEEN -> ElectricGreen
    SurvivalTier.TWELVE -> NeonAmber
    SurvivalTier.ELIMINATED -> VibrantRed
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveBulletinProgressCard(
    snapshot: LiveBulletinSnapshot,
    hasBulletinMatches: Boolean,
    modifier: Modifier = Modifier
) {
    var showFullBulletin by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_bulletin_progress_card"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (snapshot.liveCount > 0) VibrantRed else ElectricGreen)
                    )
                    Text(
                        text = "Canlı Bülten Durumu",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
                Text(
                    text = "${snapshot.resolvedCount} / 15 Maç",
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            LinearProgressIndicator(
                progress = { (snapshot.resolvedCount / 15f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (snapshot.liveCount > 0) VibrantRed else ElectricGreen,
                trackColor = DarkSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Biten: ${snapshot.finishedCount}",
                    color = ElectricGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Canlı: ${snapshot.liveCount}",
                    color = if (snapshot.liveCount > 0) VibrantRed else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Bekleyen: ${snapshot.upcomingCount}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (hasBulletinMatches) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    snapshot.bulletinMatches.forEach { m ->
                        val chipColor = when {
                            m.isLive -> VibrantRed
                            m.isFinished -> ElectricGreen
                            else -> DarkCardBorder
                        }
                        val bg = when {
                            m.isLive -> VibrantRed.copy(alpha = 0.14f)
                            m.isFinished -> ElectricGreen.copy(alpha = 0.12f)
                            else -> DarkSurface
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .border(1.dp, chipColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            val resultText = m.outcome?.code ?: "-"
                            val scoreHint = m.liveScore?.takeIf { m.isResolved }
                                ?.let { " (${it.homeScore}-${it.awayScore})" } ?: ""
                            Text(
                                text = "M${m.matchNumber}: $resultText$scoreHint",
                                color = if (m.isResolved) TextPrimary else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showFullBulletin = !showFullBulletin }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showFullBulletin) "15 Maçlık Bülten Listesini Gizle" else "15 Maçlık Bülteni Aç",
                        color = ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showFullBulletin) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = showFullBulletin) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HorizontalDivider(color = DarkCardBorder)
                        Spacer(Modifier.height(2.dp))
                        snapshot.bulletinMatches.forEach { m ->
                            LiveBulletinFixtureRow(m)
                        }
                    }
                }
            } else {
                Text(
                    text = "Haftalık 15 maçlık bülten yüklendiğinde canlı maçlar otomatik eşleştirilir.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun LiveBulletinFixtureRow(match: LiveBulletinMatchState) {
    val badgeColor = when {
        match.isLive -> VibrantRed
        match.isFinished -> ElectricGreen
        else -> TextMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${match.matchNumber}.",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = "${match.homeTeam} - ${match.awayTeam}",
            color = TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val scoreText = match.liveScore?.scoreDisplay ?: "- : -"
        Text(
            text = scoreText,
            color = badgeColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = match.outcome?.code ?: match.status.label.take(3),
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSurvivalTierSummaryCard(
    snapshot: LiveBulletinSnapshot,
    selectedTier: SurvivalTier?,
    onSelectTier: (SurvivalTier?) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_survival_summary_card"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.2.dp, CasinoGold.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "12'den 15'e Anlık Kupon Takibi",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp
            )
            Text(
                text = "Bülten skorlarına göre ikramiye potasında kalan kupon ve kolonlar",
                color = TextSecondary,
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TierSummaryTile(
                    tier = SurvivalTier.FIFTEEN,
                    couponCount = snapshot.couponsAliveFor15,
                    columnCount = snapshot.totalColumnsAliveFor15,
                    isSelected = selectedTier == SurvivalTier.FIFTEEN,
                    onClick = {
                        onSelectTier(if (selectedTier == SurvivalTier.FIFTEEN) null else SurvivalTier.FIFTEEN)
                    },
                    modifier = Modifier.weight(1f)
                )
                TierSummaryTile(
                    tier = SurvivalTier.FOURTEEN,
                    couponCount = snapshot.couponsAliveFor14,
                    columnCount = snapshot.totalColumnsAliveFor14,
                    isSelected = selectedTier == SurvivalTier.FOURTEEN,
                    onClick = {
                        onSelectTier(if (selectedTier == SurvivalTier.FOURTEEN) null else SurvivalTier.FOURTEEN)
                    },
                    modifier = Modifier.weight(1f)
                )
                TierSummaryTile(
                    tier = SurvivalTier.THIRTEEN,
                    couponCount = snapshot.couponsAliveFor13,
                    columnCount = snapshot.totalColumnsAliveFor13,
                    isSelected = selectedTier == SurvivalTier.THIRTEEN,
                    onClick = {
                        onSelectTier(if (selectedTier == SurvivalTier.THIRTEEN) null else SurvivalTier.THIRTEEN)
                    },
                    modifier = Modifier.weight(1f)
                )
                TierSummaryTile(
                    tier = SurvivalTier.TWELVE,
                    couponCount = snapshot.couponsAliveFor12,
                    columnCount = snapshot.totalColumnsAliveFor12,
                    isSelected = selectedTier == SurvivalTier.TWELVE,
                    onClick = {
                        onSelectTier(if (selectedTier == SurvivalTier.TWELVE) null else SurvivalTier.TWELVE)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TierFilterPill(
                    label = "Tümü (${snapshot.coupons.size})",
                    selected = selectedTier == null,
                    accentColor = ElectricCyan,
                    onClick = { onSelectTier(null) },
                    modifier = Modifier.testTag("tier_filter_all")
                )
                TierFilterPill(
                    label = "15 (${snapshot.couponsAliveFor15})",
                    selected = selectedTier == SurvivalTier.FIFTEEN,
                    accentColor = CasinoGold,
                    onClick = { onSelectTier(SurvivalTier.FIFTEEN) },
                    modifier = Modifier.testTag("tier_filter_15")
                )
                TierFilterPill(
                    label = "14 (${snapshot.couponsAliveFor14})",
                    selected = selectedTier == SurvivalTier.FOURTEEN,
                    accentColor = ElectricCyan,
                    onClick = { onSelectTier(SurvivalTier.FOURTEEN) },
                    modifier = Modifier.testTag("tier_filter_14")
                )
                TierFilterPill(
                    label = "13 (${snapshot.couponsAliveFor13})",
                    selected = selectedTier == SurvivalTier.THIRTEEN,
                    accentColor = ElectricGreen,
                    onClick = { onSelectTier(SurvivalTier.THIRTEEN) },
                    modifier = Modifier.testTag("tier_filter_13")
                )
                TierFilterPill(
                    label = "12 (${snapshot.couponsAliveFor12})",
                    selected = selectedTier == SurvivalTier.TWELVE,
                    accentColor = NeonAmber,
                    onClick = { onSelectTier(SurvivalTier.TWELVE) },
                    modifier = Modifier.testTag("tier_filter_12")
                )
            }
        }
    }
}

@Composable
private fun TierSummaryTile(
    tier: SurvivalTier,
    couponCount: Int,
    columnCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = tierColor(tier)
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("tier_tile_${tier.targetHits}"),
        color = if (isSelected) accent.copy(alpha = 0.22f) else DarkSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected || couponCount > 0) accent.copy(alpha = 0.7f) else DarkCardBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${tier.targetHits} Yolunda",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                text = "$couponCount Kupon",
                color = if (couponCount > 0) TextPrimary else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Text(
                text = "$columnCount kol.",
                color = TextSecondary,
                fontSize = 9.5.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TierFilterPill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = if (selected) accentColor.copy(alpha = 0.2f) else DarkSurface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) accentColor else DarkCardBorder)
    ) {
        Text(
            text = label,
            color = if (selected) accentColor else TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun LiveCouponSurvivalCard(
    summary: CouponLiveSurvivalSummary,
    bulletinMatches: List<LiveBulletinMatchState>,
    onOpenCoupon: (Coupon) -> Unit,
    modifier: Modifier = Modifier
) {
    val bestAccent = tierColor(summary.bestTier)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_coupon_survival_${summary.coupon.id}"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, bestAccent.copy(alpha = 0.55f)),
        onClick = { onOpenCoupon(summary.coupon) }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.coupon.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${summary.coupon.formulaType.badge} • ${summary.totalColumns} Kolon • En İyi: ${summary.bestMaxPossible}/15",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(bestAccent.copy(alpha = 0.18f))
                        .border(1.dp, bestAccent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = summary.bestTier.badgeText,
                        color = bestAccent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // 15 -> 14 -> 13 -> 12 -> Elenen column breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                MiniColumnTierChip("15", summary.aliveFor15, CasinoGold, Modifier.weight(1f))
                MiniColumnTierChip("14", summary.aliveFor14, ElectricCyan, Modifier.weight(1f))
                MiniColumnTierChip("13", summary.aliveFor13, ElectricGreen, Modifier.weight(1f))
                MiniColumnTierChip("12", summary.aliveFor12, NeonAmber, Modifier.weight(1f))
                MiniColumnTierChip("Yanan", summary.eliminatedColumns, VibrantRed, Modifier.weight(1f))
            }

            if (summary.topColumns.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "En İyi Durumdaki Kolonlar",
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    summary.topColumns.take(3).forEach { col ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurface)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "K${col.columnIndex}",
                                color = tierColor(col.bestLiveTier),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.width(34.dp)
                            )
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                col.predictions.take(15).forEachIndexed { idx, pred ->
                                    val matchState = bulletinMatches.getOrNull(idx)
                                    val outcome = matchState?.outcome
                                    val cellColor = when {
                                        outcome == null || matchState.status == MatchStatus.UPCOMING -> TextSecondary
                                        pred == outcome -> ElectricGreen
                                        else -> VibrantRed
                                    }
                                    Text(
                                        text = pred.code,
                                        color = cellColor,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (outcome != null) FontWeight.Black else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Text(
                                text = "${col.totalCorrect}✓/${col.totalWrong}✗",
                                color = if (col.isAliveFor12OrBetter) ElectricGreen else VibrantRed,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kupon Matrisini İncele",
                    color = ElectricCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Kupon detayını aç",
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniColumnTierChip(
    label: String,
    count: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .border(0.8.dp, if (count > 0) accent.copy(alpha = 0.55f) else DarkCardBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "$count",
            color = if (count > 0) TextPrimary else TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
    }
}
