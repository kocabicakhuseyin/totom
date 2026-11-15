package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.model.PredictionOutcome
import com.example.network.model.PastBulletinMatchResult
import com.example.network.model.PreviousWeekBulletinReport
import com.example.ui.theme.CasinoGold
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricGreen
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
internal fun PreviousWeekResultsCard(
    report: PreviousWeekBulletinReport?,
    isLoading: Boolean,
    errorMessage: String?,
    hasApiKey: Boolean,
    requiresApiKey: Boolean = false,
    providerLabel: String = "ESPN",
    onLoadPreviousWeek: (forceRefresh: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isProviderReady = !requiresApiKey || hasApiKey
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CasinoGold.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_previous_week_bulletin")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = CasinoGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = report?.weekLabel ?: "Geçen Haftanın Bülteni & Sonuçları",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                        Text(
                            text = report?.let { "${it.dateRangeLabel} • ${it.sourceLabel}" }
                                ?: "Doğrulanmış tamamlanan maçlar ve 1-X-2 sonuçları",
                            color = TextSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (report != null) {
                    IconButton(
                        onClick = { onLoadPreviousWeek(true) },
                        enabled = !isLoading,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("btn_refresh_previous_week")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CasinoGold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Geçen hafta sonuçlarını yenile",
                                tint = CasinoGold
                            )
                        }
                    }
                }
            }

            if (isLoading && report == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = CasinoGold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Geçen haftanın doğrulanmış sonuçları getiriliyor...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            } else if (report != null && report.matches.isNotEmpty()) {
                PreviousWeekDistributionStrip(report.matches)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.matches.forEach { item ->
                        PastBulletinMatchRow(item = item)
                    }
                }
            } else {
                Text(
                    text = errorMessage ?: if (isProviderReady) {
                        "Geçen haftanın Spor Toto bülten maçlarını ve kesinleşmiş maç sonuçlarını $providerLabel üzerinden çekebilirsiniz."
                    } else {
                        "Geçen haftanın bültenini ve gerçek maç sonuçlarını görmek için yukarıdaki alana API-Football anahtarınızı girin."
                    },
                    color = if (errorMessage != null) Color(0xFFFFB4A9) else TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Button(
                    onClick = { onLoadPreviousWeek(false) },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("btn_load_previous_week")
                ) {
                    Text(
                        text = "📅 Geçen Haftanın Bültenini & Sonuçlarını Göster",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun PreviousWeekBulletinDialog(
    report: PreviousWeekBulletinReport?,
    isLoading: Boolean,
    errorMessage: String?,
    hasApiKey: Boolean,
    requiresApiKey: Boolean = false,
    providerLabel: String = "ESPN",
    onRefresh: () -> Unit,
    onNavigateToLiveTab: () -> Unit,
    onDismiss: () -> Unit
) {
    val isProviderReady = !requiresApiKey || hasApiKey
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        titleContentColor = TextPrimary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = CasinoGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = report?.weekLabel ?: "Geçen Haftanın Bülteni & Sonuçları",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = report?.dateRangeLabel ?: "Doğrulanmış $providerLabel Sonuçları",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kapat",
                        tint = TextMuted
                    )
                }
            }
        },
        text = {
            if (isLoading && report == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = CasinoGold)
                    Text(
                        text = "Geçen haftanın bülteni ve kesinleşmiş skorları çekiliyor...",
                        color = TextSecondary,
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (report != null && report.matches.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PreviousWeekDistributionStrip(report.matches)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(report.matches, key = { it.matchNumber }) { item ->
                            PastBulletinMatchRow(item = item)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = errorMessage ?: if (isProviderReady) {
                            "Geçen haftanın sonuçlarını yüklemek için Yenile butonuna dokunun."
                        } else {
                            "Geçen haftanın bültenini ve gerçek maç sonuçlarını API-Football üzerinden çekmek için Canlı sekmesinden ücretsiz API anahtarınızı girin."
                        },
                        color = if (errorMessage != null) Color(0xFFFFB4A9) else TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    if (!isProviderReady) {
                        Button(
                            onClick = {
                                onDismiss()
                                onNavigateToLiveTab()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = "Canlı Sekmesinde Sağlayıcı Ayarla",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isProviderReady) {
                Button(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (isLoading) "Çekiliyor..." else "↻ Yenile",
                        color = Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Kapat", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun PreviousWeekDistributionStrip(matches: List<PastBulletinMatchResult>) {
    val homeWins = matches.count { it.winningOutcome == PredictionOutcome.HOME }
    val draws = matches.count { it.winningOutcome == PredictionOutcome.DRAW }
    val awayWins = matches.count { it.winningOutcome == PredictionOutcome.AWAY }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutcomeCountPill(
            label = "1 (Ev)",
            count = homeWins,
            color = ElectricGreen,
            modifier = Modifier.weight(1f)
        )
        OutcomeCountPill(
            label = "X (Ber.)",
            count = draws,
            color = NeonAmber,
            modifier = Modifier.weight(1f)
        )
        OutcomeCountPill(
            label = "2 (Dep.)",
            count = awayWins,
            color = ElectricCyan,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OutcomeCountPill(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "$count",
            color = TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun PastBulletinMatchRow(item: PastBulletinMatchResult) {
    val outcomeColor = when (item.winningOutcome) {
        PredictionOutcome.HOME -> ElectricGreen
        PredictionOutcome.DRAW -> NeonAmber
        PredictionOutcome.AWAY -> ElectricCyan
        null -> TextMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkBackground)
            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("past_match_row_${item.matchNumber}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#${item.matchNumber.toString().padStart(2, '0')} • ${item.matchDate}",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.league,
                color = CasinoGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.homeTeam,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.scoreDisplay,
                    color = ElectricGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Text(
                text = item.awayTeam,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(outcomeColor.copy(alpha = 0.2f))
                    .border(1.dp, outcomeColor, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.winningOutcome?.code ?: "-",
                    color = outcomeColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
