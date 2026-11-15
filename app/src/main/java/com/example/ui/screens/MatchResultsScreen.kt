package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.repository.LiveScoreSource
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.LiveBulletinTracker
import com.example.engine.SurvivalTier
import com.example.model.Coupon
import com.example.model.CouponPoolEvaluator
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.TotoMatch
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

/**
 * Verified live scoreboard and real-time 12-to-15 bulletin coupon survival tracker.
 * Only verified provider responses populate live results; no fake minutes or mocked fixtures.
 */
@Composable
fun MatchResultsScreen(
    liveScores: List<MatchLiveScore>,
    providerName: String,
    activeSource: LiveScoreSource = LiveScoreSource.ESPN,
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
    onSyncTurkishLeagueBulletin: () -> Unit = {},
    previousWeekReport: com.example.network.model.PreviousWeekBulletinReport? = null,
    isLoadingPreviousWeek: Boolean = false,
    previousWeekError: String? = null,
    onLoadPreviousWeek: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val requiresApiKey = activeSource == LiveScoreSource.API_FOOTBALL
    val isProviderReady = !requiresApiKey || hasApiKey
    var showApiKeyEntry by remember(activeSource, hasApiKey) {
        mutableStateOf(requiresApiKey && !hasApiKey)
    }
    var apiKeyDraft by remember { mutableStateOf("") }
    var selectedTierFilter by remember { mutableStateOf<SurvivalTier?>(null) }

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
    val displayedBulletinScores = remember(matches, liveScores) {
        if (matches.isNotEmpty()) {
            CouponPoolEvaluator.filterAndAlignToBulletin(matches, liveScores)
        } else {
            liveScores
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(DarkBackground),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Canlı Bülten & Skorlar",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 19.sp
                        )
                        Text(
                            text = if (activeSource == LiveScoreSource.ESPN)
                                "$providerName • Anahtarsız otomatik takip"
                            else
                                "$providerName • Ekran açıkken en sık 3 dk yenileme",
                            color = TextSecondary,
                            fontSize = 10.sp
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
                        modifier = Modifier.testTag("btn_refresh_live_scores")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp),
                                strokeWidth = 2.dp,
                                color = ElectricCyan
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Gerçek skorları yenile", tint = ElectricCyan)
                        }
                    }
                }
            }
        }

        item {
            LiveBulletinProgressCard(
                snapshot = bulletinSnapshot,
                hasBulletinMatches = matches.isNotEmpty()
            )
        }

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
                    modifier = Modifier.fillMaxWidth().testTag("live_coupons_empty"),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
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
                    modifier = Modifier.fillMaxWidth().testTag("live_coupons_filter_empty"),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
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

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Maç Sonuçları Kaynağı (${activeSource.badgeLabel})",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeSource == LiveScoreSource.ESPN,
                            onClick = { onSelectSource(LiveScoreSource.ESPN) },
                            label = {
                                Text(
                                    "🌐 ESPN (Anahtarsız)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF064E3B),
                                selectedLabelColor = ElectricGreen,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("chip_source_espn")
                        )
                        FilterChip(
                            selected = activeSource == LiveScoreSource.API_FOOTBALL,
                            onClick = { onSelectSource(LiveScoreSource.API_FOOTBALL) },
                            label = {
                                Text(
                                    "🔑 API-Football",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ElectricCyan,
                                containerColor = DarkSurface,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("chip_source_api_football")
                        )
                    }

                    if (activeSource == LiveScoreSource.ESPN) {
                        Text(
                            "ESPN Site API aktif • Ücretsiz & API anahtarı gerektirmez • Süper Lig ve Avrupa ligleri doğrudan çekilir.",
                            color = ElectricGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = onSyncTurkishLeagueBulletin,
                            enabled = !isSyncingBulletin,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("btn_sync_turkish_league_bulletin")
                        ) {
                            Text(
                                text = if (isSyncingBulletin)
                                    "Türkiye Ligi Bülteni Çekiliyor..."
                                else
                                    "🇹🇷 Türkiye Ligi Bültenini ESPN'den Çek & Takip Et",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            "100 istek/gün • Uygulama en fazla 80 istek kullanır • Maç dakikaları API verisidir.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        if (hasApiKey) {
                            Text(
                                "Bu cihazın kalan sorgu hakkı: ${freeRequestsRemaining ?: "—"}",
                                color = ElectricGreen,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = onSyncTurkishLeagueBulletin,
                                enabled = !isSyncingBulletin,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("btn_sync_turkish_league_bulletin")
                            ) {
                                Text(
                                    text = if (isSyncingBulletin)
                                        "Türkiye Ligi Bülteni Çekiliyor..."
                                    else
                                        "🇹🇷 Türkiye Ligi Bültenini API'den Çek & Takip Et",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            TextButton(onClick = { showApiKeyEntry = !showApiKeyEntry }) {
                                Text(if (showApiKeyEntry) "Anahtar Alanını Kapat" else "API Anahtarını Değiştir")
                            }
                        }
                        if (!hasApiKey || showApiKeyEntry) {
                            OutlinedTextField(
                                value = apiKeyDraft,
                                onValueChange = { apiKeyDraft = it.trim() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("api_football_free_key"),
                                label = { Text("API-Football Anahtarı") },
                                placeholder = { Text("Ücretsiz plan API anahtarınızı girin") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    onSaveApiKey(apiKeyDraft)
                                    apiKeyDraft = ""
                                    showApiKeyEntry = false
                                },
                                enabled = apiKeyDraft.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricGreen),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_save_api_football_key")
                            ) {
                                Text("Anahtarı Kaydet ve Canlı Skorları Aç", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        if (errorMessage != null || displayedBulletinScores.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("live_score_empty"),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Text(
                        text = errorMessage ?: if (!isProviderReady)
                            "Canlı ve biten bülten sonuçlarını görmek için yukarıdaki ücretsiz API-Football anahtarını girin veya ESPN'e geçin."
                        else if (matches.isNotEmpty())
                            "Bültendeki 15 maç için henüz başlayan veya biten doğrulanmış ${activeSource.badgeLabel} sonucu bulunamadı."
                        else "Şu anda ${activeSource.badgeLabel} üzerinden doğrulanmış canlı karşılaşma bulunamadı.",
                        modifier = Modifier.padding(20.dp),
                        color = if (errorMessage != null) Color(0xFFFFB4A9) else TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            item {
                Text(
                    text = if (matches.isNotEmpty())
                        "${activeSource.badgeLabel} • Bülten Maçları (Canlı & Biten: ${displayedBulletinScores.size})"
                    else "${activeSource.badgeLabel} Doğrulanmış Skorlar (${displayedBulletinScores.size})",
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            items(displayedBulletinScores, key = { it.matchNumber }) { match ->
                RealLiveScoreCard(match, isBulletinMatch = matches.isNotEmpty())
            }
        }

        item {
            PreviousWeekResultsCard(
                report = previousWeekReport,
                isLoading = isLoadingPreviousWeek,
                errorMessage = previousWeekError,
                hasApiKey = hasApiKey,
                requiresApiKey = requiresApiKey,
                providerLabel = activeSource.badgeLabel,
                onLoadPreviousWeek = onLoadPreviousWeek
            )
        }
    }
}

@Composable
private fun RealLiveScoreCard(
    match: MatchLiveScore,
    isBulletinMatch: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("live_fixture_${match.matchNumber}"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
                        color = if (match.isLive) VibrantRed else ElectricGreen,
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
                MatchStatus.UPCOMING -> "Henüz başlamadı"
                MatchStatus.LIVE -> if (match.minuteText == "—") "Dakika bilgisi yok" else "${match.minuteText} dakika"
            }
            Text(
                minuteLabel,
                modifier = Modifier.fillMaxWidth(),
                color = when (match.status) {
                    MatchStatus.LIVE, MatchStatus.HALF_TIME -> VibrantRed
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
