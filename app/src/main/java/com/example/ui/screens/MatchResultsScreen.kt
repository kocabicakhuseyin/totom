package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.LiveBulletinSnapshot
import com.example.engine.LiveBulletinTracker
import com.example.engine.SurvivalTier
import com.example.model.Coupon
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.repository.LiveScoreSource
import com.example.ui.theme.CasinoGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibrantRed

internal enum class ScoreboardStatusFilter(val id: String, val label: String) {
    ALL("all", "Tümü"),
    LIVE("live", "Canlı"),
    FINISHED("finished", "Biten"),
    UPCOMING("upcoming", "Başlamadı")
}

internal enum class ScoreboardViewMode(val id: String, val label: String) {
    TABLE("table", "Tablo"),
    CARDS("cards", "Kartlar")
}

internal data class ScoreboardRow(
    val matchNumber: Int,
    val homeTeam: String,
    val awayTeam: String,
    val scoreDisplay: String,
    val status: MatchStatus,
    val statusDetail: String,
    val league: String,
    val winningOutcome: PredictionOutcome?,
    val liveScoreModel: MatchLiveScore
)

internal fun buildScoreboardRows(
    matches: List<TotoMatch>,
    bulletinSnapshot: LiveBulletinSnapshot,
    liveScores: List<MatchLiveScore>,
    preferAllLiveScores: Boolean = false
): List<ScoreboardRow> {
    if (matches.isNotEmpty() && !preferAllLiveScores) {
        return bulletinSnapshot.bulletinMatches.map { state ->
            val ls = state.liveScore
            val detail = when (state.status) {
                MatchStatus.LIVE -> ls?.minuteText?.takeIf { it.isNotBlank() && it != "—" } ?: "CANLI"
                MatchStatus.HALF_TIME -> "İY"
                MatchStatus.FINISHED -> "MS"
                MatchStatus.UPCOMING -> formatKickoffShort(state.matchDate)
            }
            val model = ls?.copy(matchNumber = state.matchNumber) ?: MatchLiveScore(
                matchNumber = state.matchNumber,
                homeTeam = state.homeTeam,
                awayTeam = state.awayTeam,
                homeScore = null,
                awayScore = null,
                status = state.status,
                minuteText = detail,
                matchDate = state.matchDate,
                league = "Spor Toto Bülteni"
            )
            ScoreboardRow(
                matchNumber = state.matchNumber,
                homeTeam = state.homeTeam,
                awayTeam = state.awayTeam,
                scoreDisplay = ls?.scoreDisplay ?: "- : -",
                status = state.status,
                statusDetail = detail,
                league = ls?.league?.takeIf { it.isNotBlank() } ?: "Bülten #${state.matchNumber}",
                winningOutcome = state.outcome,
                liveScoreModel = model
            )
        }
    }
    return liveScores.mapIndexed { idx, score ->
        val detail = when (score.status) {
            MatchStatus.LIVE -> score.minuteText.takeIf { it.isNotBlank() && it != "—" } ?: "CANLI"
            MatchStatus.HALF_TIME -> "İY"
            MatchStatus.FINISHED -> "MS"
            MatchStatus.UPCOMING -> formatKickoffShort(score.matchDate)
        }
        val displayNo = if (score.matchNumber in 1..15) score.matchNumber else (idx + 1)
        ScoreboardRow(
            matchNumber = displayNo,
            homeTeam = score.homeTeam,
            awayTeam = score.awayTeam,
            scoreDisplay = score.scoreDisplay,
            status = score.status,
            statusDetail = detail,
            league = score.league,
            winningOutcome = score.winningOutcome,
            liveScoreModel = score
        )
    }
}

internal fun filterScoreboardRows(
    rows: List<ScoreboardRow>,
    filter: ScoreboardStatusFilter
): List<ScoreboardRow> = when (filter) {
    ScoreboardStatusFilter.ALL -> rows
    ScoreboardStatusFilter.LIVE -> rows.filter { it.status == MatchStatus.LIVE || it.status == MatchStatus.HALF_TIME }
    ScoreboardStatusFilter.FINISHED -> rows.filter { it.status == MatchStatus.FINISHED }
    ScoreboardStatusFilter.UPCOMING -> rows.filter { it.status == MatchStatus.UPCOMING }
}

private fun formatKickoffShort(rawDate: String): String {
    if (rawDate.isBlank()) return "Bekliyor"
    val timeMatch = Regex("(\\d{2}:\\d{2})").find(rawDate)?.groupValues?.getOrNull(1)
    val dateMatch = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(rawDate)
    return when {
        dateMatch != null && timeMatch != null -> "${dateMatch.groupValues[3]}.${dateMatch.groupValues[2]} $timeMatch"
        timeMatch != null -> timeMatch
        else -> rawDate.take(11)
    }
}

/**
 * Verified live scoreboard table and real-time 12-to-15 bulletin coupon survival tracker.
 * Only verified provider responses populate live results; no fake minutes or mocked fixtures.
 */
@Composable
fun MatchResultsScreen(
    liveScores: List<MatchLiveScore>,
    providerName: String,
    activeSource: LiveScoreSource = LiveScoreSource.NESINE_RESULTS,
    onSelectSource: (LiveScoreSource) -> Unit = {},
    hasApiKey: Boolean = false,
    freeRequestsRemaining: Int? = null,
    onSaveApiKey: (String) -> Unit = {},
    lastUpdateTime: String,
    isRefreshing: Boolean,
    errorMessage: String?,
    onPollNow: () -> Unit,
    matches: List<TotoMatch> = emptyList(),
    coupons: List<Coupon> = emptyList(),
    onOpenCoupon: (Coupon) -> Unit = {},
    isSyncingBulletin: Boolean = false,
    onScrapeNesineBulletin: () -> Unit = {},
    onScrapeNesineResults: () -> Unit = {},
    onScrapeBilyonerLive: () -> Unit = {},
    onScrapeBilyonerBulletin: () -> Unit = {},
    previousWeekReport: com.example.network.model.PreviousWeekBulletinReport? = null,
    isLoadingPreviousWeek: Boolean = false,
    previousWeekError: String? = null,
    onLoadPreviousWeek: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isProviderReady = true
    var selectedTierFilter by remember { mutableStateOf<SurvivalTier?>(null) }
    var selectedScoreFilter by remember { mutableStateOf(ScoreboardStatusFilter.ALL) }
    var scoreboardViewMode by remember { mutableStateOf(ScoreboardViewMode.TABLE) }
    var showAllBilyonerFixtures by remember(activeSource) {
        mutableStateOf(activeSource == LiveScoreSource.BILYONER_LIVE)
    }

    val bulletinSnapshot = remember(matches, liveScores, coupons) {
        LiveBulletinTracker.evaluate(
            matches = matches,
            liveScores = liveScores,
            coupons = coupons
        )
    }
    val filteredCoupons = remember(bulletinSnapshot, selectedTierFilter) {
        bulletinSnapshot.filterCoupons(selectedTierFilter)
    }
    val preferAllLive = activeSource == LiveScoreSource.BILYONER_LIVE &&
        showAllBilyonerFixtures &&
        liveScores.isNotEmpty()
    val allScoreboardRows = remember(matches, bulletinSnapshot, liveScores, preferAllLive) {
        buildScoreboardRows(matches, bulletinSnapshot, liveScores, preferAllLiveScores = preferAllLive)
    }
    val filteredScoreboardRows = remember(allScoreboardRows, selectedScoreFilter) {
        filterScoreboardRows(allScoreboardRows, selectedScoreFilter)
    }
    val liveRowsCount = remember(allScoreboardRows) {
        allScoreboardRows.count { it.status == MatchStatus.LIVE || it.status == MatchStatus.HALF_TIME }
    }
    val finishedRowsCount = remember(allScoreboardRows) {
        allScoreboardRows.count { it.status == MatchStatus.FINISHED }
    }
    val upcomingRowsCount = remember(allScoreboardRows) {
        allScoreboardRows.count { it.status == MatchStatus.UPCOMING }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Canlı Skor Tablosu",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 19.sp
                        )
                        Text(
                            text = if (activeSource == LiveScoreSource.BILYONER_LIVE) {
                                "$providerName • bilyoner.com/canli-skor"
                            } else {
                                "$providerName • nesine.com/sportoto/mac-sonuclari"
                            },
                            color = TextSecondary,
                            fontSize = 10.5.sp
                        )
                        Text(
                            "Son kontrol: $lastUpdateTime",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(
                        onClick = onPollNow,
                        enabled = isProviderReady && !isRefreshing,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("btn_refresh_live_scores")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp),
                                strokeWidth = 2.dp,
                                color = ElectricCyan
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Gerçek skorları yenile",
                                tint = ElectricCyan
                            )
                        }
                    }
                }
            }
        }

        // 2. Bulletin Live Progress Overview
        item {
            LiveBulletinProgressCard(
                snapshot = bulletinSnapshot,
                hasBulletinMatches = matches.isNotEmpty()
            )
        }

        // 3. Dedicated Live Scoreboard Table Section (Filters + Tablo/Kartlar Toggle)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_scoreboard_section")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Anlık Maç Durumları",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp
                            )
                            Text(
                                text = "$liveRowsCount Canlı • $finishedRowsCount Biten • $upcomingRowsCount Başlamadı",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = scoreboardViewMode == ScoreboardViewMode.TABLE,
                                onClick = { scoreboardViewMode = ScoreboardViewMode.TABLE },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.TableRows,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = ScoreboardViewMode.TABLE.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = ElectricCyan,
                                    selectedLeadingIconColor = ElectricCyan,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("btn_scoreboard_mode_table")
                            )
                            FilterChip(
                                selected = scoreboardViewMode == ScoreboardViewMode.CARDS,
                                onClick = { scoreboardViewMode = ScoreboardViewMode.CARDS },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.GridView,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = ScoreboardViewMode.CARDS.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = ElectricCyan,
                                    selectedLeadingIconColor = ElectricCyan,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("btn_scoreboard_mode_cards")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeSource == LiveScoreSource.BILYONER_LIVE,
                            onClick = { onSelectSource(LiveScoreSource.BILYONER_LIVE) },
                            label = {
                                Text(
                                    text = "Bilyoner Canlı Skor",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricGreen.copy(alpha = 0.22f),
                                selectedLabelColor = ElectricGreen,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chip_source_bilyoner")
                        )
                        FilterChip(
                            selected = activeSource == LiveScoreSource.NESINE_RESULTS,
                            onClick = { onSelectSource(LiveScoreSource.NESINE_RESULTS) },
                            label = {
                                Text(
                                    text = "Nesine Sonuçlar",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFEC4899).copy(alpha = 0.24f),
                                selectedLabelColor = Color(0xFFF472B6),
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chip_source_nesine")
                        )
                        if (activeSource == LiveScoreSource.BILYONER_LIVE &&
                            matches.isNotEmpty() &&
                            liveScores.isNotEmpty()
                        ) {
                            FilterChip(
                                selected = showAllBilyonerFixtures,
                                onClick = { showAllBilyonerFixtures = true },
                                label = {
                                    Text(
                                        text = "Bilyoner Tüm (${liveScores.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = ElectricCyan,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("chip_scope_bilyoner_all")
                            )
                            FilterChip(
                                selected = !showAllBilyonerFixtures,
                                onClick = { showAllBilyonerFixtures = false },
                                label = {
                                    Text(
                                        text = "Bülten (${matches.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CasinoGold.copy(alpha = 0.2f),
                                    selectedLabelColor = CasinoGold,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("chip_scope_bulletin_15")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScoreboardStatusFilter.values().forEach { filter ->
                            val count = when (filter) {
                                ScoreboardStatusFilter.ALL -> allScoreboardRows.size
                                ScoreboardStatusFilter.LIVE -> liveRowsCount
                                ScoreboardStatusFilter.FINISHED -> finishedRowsCount
                                ScoreboardStatusFilter.UPCOMING -> upcomingRowsCount
                            }
                            val selected = selectedScoreFilter == filter
                            val activeBg = when (filter) {
                                ScoreboardStatusFilter.LIVE -> VibrantRed.copy(alpha = 0.24f)
                                ScoreboardStatusFilter.FINISHED -> ElectricGreen.copy(alpha = 0.22f)
                                ScoreboardStatusFilter.UPCOMING -> CasinoGold.copy(alpha = 0.2f)
                                ScoreboardStatusFilter.ALL -> ElectricCyan.copy(alpha = 0.22f)
                            }
                            val activeFg = when (filter) {
                                ScoreboardStatusFilter.LIVE -> Color(0xFFFCA5A5)
                                ScoreboardStatusFilter.FINISHED -> ElectricGreen
                                ScoreboardStatusFilter.UPCOMING -> CasinoGold
                                ScoreboardStatusFilter.ALL -> ElectricCyan
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { selectedScoreFilter = filter },
                                label = {
                                    Text(
                                        text = "${filter.label} ($count)",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = activeBg,
                                    selectedLabelColor = activeFg,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("chip_scoreboard_filter_${filter.id}")
                            )
                        }
                    }

                    if (errorMessage != null && allScoreboardRows.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2B1115))
                                .border(1.dp, VibrantRed, RoundedCornerShape(10.dp))
                                .padding(14.dp)
                                .testTag("live_score_empty")
                        ) {
                            Text(
                                text = errorMessage,
                                color = Color(0xFFFFB4A9),
                                textAlign = TextAlign.Center,
                                fontSize = 12.5.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (allScoreboardRows.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurface)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                .padding(14.dp)
                                .testTag("live_score_empty")
                        ) {
                            Text(
                                text = when {
                                    activeSource == LiveScoreSource.BILYONER_LIVE ->
                                        "Şu anda bilyoner.com/canli-skor üzerinden doğrulanmış karşılaşma bulunamadı. Aşağıdan yenileyebilirsiniz."
                                    !isProviderReady ->
                                        "Canlı sonuçlar çekiliyor."
                                    else ->
                                        "Şu anda nesine.com/sportoto/mac-sonuclari üzerinden doğrulanmış karşılaşma bulunamadı."
                                },
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (filteredScoreboardRows.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurface)
                                .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                .padding(16.dp)
                                .testTag("live_scoreboard_filter_empty"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${selectedScoreFilter.label} durumunda karşılaşma bulunamadı.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = { selectedScoreFilter = ScoreboardStatusFilter.ALL },
                                border = BorderStroke(1.dp, ElectricCyan),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Tüm Maçları Göster", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (scoreboardViewMode == ScoreboardViewMode.TABLE) {
                        LiveScoreboardTable(rows = filteredScoreboardRows)
                    }
                }
            }
        }

        // Card mode expands rows natively in the LazyColumn for smooth scrolling
        if (scoreboardViewMode == ScoreboardViewMode.CARDS && filteredScoreboardRows.isNotEmpty()) {
            items(filteredScoreboardRows, key = { "card_${it.matchNumber}_${it.homeTeam}_${it.awayTeam}" }) { row ->
                RealLiveScoreCard(
                    match = row.liveScoreModel,
                    isBulletinMatch = matches.isNotEmpty() && !preferAllLive
                )
            }
        }

        if (errorMessage != null && allScoreboardRows.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_score_empty"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1115)),
                    border = BorderStroke(1.dp, VibrantRed)
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFFB4A9),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }
        }

        // 4. Coupon Survival Tier Summary & Tracked Coupons
        item {
            LiveSurvivalTierSummaryCard(
                snapshot = bulletinSnapshot,
                selectedTier = selectedTierFilter,
                onSelectTier = { selectedTierFilter = it }
            )
        }

        if (coupons.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_coupons_empty"),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Text(
                        text = "Henüz takip edilecek kupon yok. Bülten veya Formül Sihirbazı ile kupon oluşturduğunuzda 12–15 canlı takibi otomatik başlar.",
                        modifier = Modifier.padding(16.dp),
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            }
        } else if (filteredCoupons.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_coupons_filter_empty"),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Text(
                        text = "Seçili ikramiye basamağında (${selectedTierFilter?.label ?: ""}) devam eden kupon bulunamadı.",
                        modifier = Modifier.padding(16.dp),
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            items(filteredCoupons, key = { "survival_${it.coupon.id}" }) { summary ->
                LiveCouponSurvivalCard(
                    summary = summary,
                    bulletinMatches = bulletinSnapshot.bulletinMatches,
                    onOpenCoupon = onOpenCoupon
                )
            }
        }

        // 5. Nesine & Bilyoner Live Source Controls Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, DarkCardBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Veri Kaynakları: Nesine & Bilyoner",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Spor Toto bülteni bilyoner.com/spor-toto üzerinden; anlık skorlar bilyoner.com/canli-skor veya nesine.com/sportoto/mac-sonuclari üzerinden canlı çekilir.",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = onScrapeBilyonerLive,
                        enabled = !isRefreshing && !isSyncingBulletin,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("btn_scrape_bilyoner_live")
                    ) {
                        Text(
                            text = if (isRefreshing)
                                "Bilyoner Canlı Çekiliyor..."
                            else
                                "🟢 bilyoner.com/canli-skor'dan Maç Verilerini Çek",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onScrapeBilyonerBulletin,
                        enabled = !isSyncingBulletin && !isRefreshing,
                        border = BorderStroke(1.dp, ElectricGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("btn_scrape_bilyoner_bulletin")
                    ) {
                        Text(
                            text = if (isSyncingBulletin)
                                "Bilyoner Spor Toto Çekiliyor..."
                            else
                                "⚡ bilyoner.com/spor-toto'dan 15'li Bülteni Çek",
                            color = ElectricGreen,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = onScrapeNesineResults,
                        enabled = !isSyncingBulletin,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("btn_scrape_nesine_results")
                    ) {
                        Text(
                            text = if (isSyncingBulletin)
                                "Nesine Sonuçları Çekiliyor..."
                            else
                                "🎯 nesine.com/sportoto/mac-sonuclari'ndan Anlık Çek",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 6. Bilyoner Previous/Finished Results Summary
        item {
            PreviousWeekResultsCard(
                report = previousWeekReport,
                isLoading = isLoadingPreviousWeek,
                errorMessage = previousWeekError,
                hasApiKey = true,
                requiresApiKey = false,
                providerLabel = "Bilyoner",
                onLoadPreviousWeek = onLoadPreviousWeek
            )
        }
    }
}

@Composable
private fun LiveScoreboardTable(rows: List<ScoreboardRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B111E))
            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
            .testTag("live_scoreboard_table")
    ) {
        // Table Column Headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(26.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = "KARŞILAŞMA",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "SKOR",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = "DURUM",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(66.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = "1X2",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center
            )
        }

        HorizontalDivider(color = DarkCardBorder, thickness = 1.dp)

        rows.forEachIndexed { idx, row ->
            val isLiveOrHt = row.status == MatchStatus.LIVE || row.status == MatchStatus.HALF_TIME
            val rowBg = when {
                row.status == MatchStatus.LIVE -> VibrantRed.copy(alpha = 0.08f)
                row.status == MatchStatus.HALF_TIME -> CasinoGold.copy(alpha = 0.08f)
                idx % 2 == 0 -> Color(0xFF0E1626)
                else -> Color.Transparent
            }
            val scoreColor = when (row.status) {
                MatchStatus.LIVE -> VibrantRed
                MatchStatus.HALF_TIME -> CasinoGold
                MatchStatus.FINISHED -> ElectricGreen
                MatchStatus.UPCOMING -> TextMuted
            }
            val statusBg = when (row.status) {
                MatchStatus.LIVE -> VibrantRed.copy(alpha = 0.18f)
                MatchStatus.HALF_TIME -> CasinoGold.copy(alpha = 0.18f)
                MatchStatus.FINISHED -> ElectricGreen.copy(alpha = 0.16f)
                MatchStatus.UPCOMING -> DarkSurface
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag("live_fixture_${row.matchNumber}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.matchNumber.toString().padStart(2, '0'),
                    color = ElectricCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.width(26.dp),
                    textAlign = TextAlign.Center
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                ) {
                    Text(
                        text = row.homeTeam,
                        color = TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = row.awayTeam,
                        color = TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.league.isNotBlank() && row.league != "Spor Toto Listesi" && !row.league.startsWith("Bülten #")) {
                        Text(
                            text = row.league,
                            color = TextMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(DarkSurface)
                        .border(
                            0.8.dp,
                            if (isLiveOrHt) scoreColor.copy(alpha = 0.65f) else DarkCardBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = row.scoreDisplay,
                        color = scoreColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Box(
                    modifier = Modifier
                        .width(62.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusBg)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (row.status == MatchStatus.LIVE) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(VibrantRed)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = row.statusDetail,
                            color = scoreColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                val outcomeBg = when (row.winningOutcome) {
                    PredictionOutcome.HOME -> ElectricGreen
                    PredictionOutcome.DRAW -> ElectricCyan
                    PredictionOutcome.AWAY -> CasinoGold
                    null -> DarkSurface
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(outcomeBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = row.winningOutcome?.code ?: "·",
                        color = if (row.winningOutcome == null) TextMuted else Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (idx < rows.lastIndex) {
                HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun RealLiveScoreCard(
    match: MatchLiveScore,
    isBulletinMatch: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_fixture_${match.matchNumber}"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isBulletinMatch || match.league.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isBulletinMatch && match.matchNumber in 1..15) {
                        Text(
                            text = "BÜLTEN #${match.matchNumber}",
                            color = ElectricCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (match.league.isNotBlank()) {
                        Text(
                            text = match.league,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    match.homeTeam,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        match.scoreDisplay,
                        color = when (match.status) {
                            MatchStatus.LIVE -> VibrantRed
                            MatchStatus.HALF_TIME -> CasinoGold
                            MatchStatus.FINISHED -> ElectricGreen
                            MatchStatus.UPCOMING -> TextMuted
                        },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    match.awayTeam,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            val minuteLabel = when (match.status) {
                MatchStatus.HALF_TIME -> "Devre arası"
                MatchStatus.FINISHED -> "Maç sona erdi (Bitti)"
                MatchStatus.UPCOMING -> "Henüz başlamadı • ${formatKickoffShort(match.matchDate)}"
                MatchStatus.LIVE -> if (match.minuteText == "—" || match.minuteText.isBlank()) "Dakika bilgisi yok" else "${match.minuteText} dakika"
            }
            Text(
                minuteLabel,
                modifier = Modifier.fillMaxWidth(),
                color = when (match.status) {
                    MatchStatus.LIVE -> VibrantRed
                    MatchStatus.HALF_TIME -> CasinoGold
                    MatchStatus.FINISHED -> ElectricGreen
                    MatchStatus.UPCOMING -> TextMuted
                },
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
