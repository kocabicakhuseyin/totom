package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Coupon
import com.example.model.CouponPoolEvaluator
import com.example.model.FormulaType
import com.example.model.ForumPost
import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.example.model.PredictionOutcome
import com.example.model.TotoColumn
import com.example.model.TotoMatch
import com.example.ui.components.TeamBadgeView
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
import com.example.ui.theme.VibrantRed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

internal const val FILTER_ALL_MATCHES = -1
internal const val FILTER_GENERAL_ONLY = 0

@Composable
internal fun BulletinMatchFilterStrip(
    matches: List<TotoMatch>,
    liveScores: List<MatchLiveScore>,
    forumPosts: List<ForumPost>,
    selectedFilter: Int,
    onSelectFilter: (Int) -> Unit
) {
    val generalCount = forumPosts.count { it.matchNumber == null }
    val countByMatch = forumPosts.mapNotNull { it.matchNumber }.groupingBy { it }.eachCount()

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "filter_all") {
            ForumFilterChip(
                label = "Tümü",
                badgeCount = forumPosts.size,
                selected = selectedFilter == FILTER_ALL_MATCHES,
                accentColor = CasinoGold,
                modifier = Modifier.testTag("forum_filter_all"),
                onClick = { onSelectFilter(FILTER_ALL_MATCHES) }
            )
        }
        item(key = "filter_general") {
            ForumFilterChip(
                label = "Genel Bülten",
                badgeCount = generalCount,
                selected = selectedFilter == FILTER_GENERAL_ONLY,
                accentColor = ElectricCyan,
                modifier = Modifier.testTag("forum_filter_general"),
                onClick = { onSelectFilter(FILTER_GENERAL_ONLY) }
            )
        }
        items(matches, key = { it.matchNumber }) { match ->
            val live = liveScores.firstOrNull { it.matchNumber == match.matchNumber }
            val count = countByMatch[match.matchNumber] ?: 0
            val selected = selectedFilter == match.matchNumber
            val scoreBadge = when {
                live != null && (live.status == MatchStatus.LIVE || live.status == MatchStatus.HALF_TIME || live.status == MatchStatus.FINISHED) ->
                    " ${live.scoreDisplay}"
                else -> ""
            }
            ForumFilterChip(
                label = "#${match.matchNumber} ${abbreviateTeam(match.homeTeam)}-${abbreviateTeam(match.awayTeam)}$scoreBadge",
                badgeCount = count,
                selected = selected,
                accentColor = if (live?.isLive == true) VibrantRed else ElectricGreen,
                isLive = live?.isLive == true,
                modifier = Modifier.testTag("forum_filter_match_${match.matchNumber}"),
                onClick = { onSelectFilter(match.matchNumber) }
            )
        }
    }
}

@Composable
private fun ForumFilterChip(
    label: String,
    badgeCount: Int,
    selected: Boolean,
    accentColor: Color,
    isLive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = if (selected) accentColor.copy(alpha = 0.2f) else DarkCard
    val borderColor = if (selected) accentColor else DarkCardBorder
    val textColor = if (selected) accentColor else TextPrimary

    Row(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isLive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(VibrantRed)
            )
        }
        Text(
            text = label,
            color = textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 11.5.sp,
            maxLines = 1
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) accentColor else DarkBackground)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "$badgeCount",
                    color = if (selected) Color.Black else TextSecondary,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun SelectedMatchInsightsCard(
    match: TotoMatch,
    liveScore: MatchLiveScore?,
    postsForMatch: List<ForumPost>,
    onClearFilter: () -> Unit
) {
    val homeProb = match.probability.home.firstOrNull() ?: 0
    val drawProb = match.probability.draw.firstOrNull() ?: 0
    val awayProb = match.probability.away.firstOrNull() ?: 0

    val votePosts = postsForMatch.filter { it.predictedOutcome != null }
    val vote1 = votePosts.count { it.predictedOutcome == PredictionOutcome.HOME }
    val voteX = votePosts.count { it.predictedOutcome == PredictionOutcome.DRAW }
    val vote2 = votePosts.count { it.predictedOutcome == PredictionOutcome.AWAY }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102033)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CasinoGold)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "#${match.matchNumber}",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${match.homeTeam} - ${match.awayTeam}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "Tüm Bülten",
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearFilter)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.matchDate,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                if (liveScore != null) {
                    val statusColor = Color(liveScore.status.badgeColorHex)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.2f))
                            .border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        val scorePart = if (liveScore.homeScore != null && liveScore.awayScore != null) {
                            " • ${liveScore.scoreDisplay}"
                        } else ""
                        Text(
                            text = "${liveScore.status.label}$scorePart",
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProbabilityBadge(label = "1 (%$homeProb)", count = vote1, modifier = Modifier.weight(1f))
                ProbabilityBadge(label = "X (%$drawProb)", count = voteX, modifier = Modifier.weight(1f))
                ProbabilityBadge(label = "2 (%$awayProb)", count = vote2, modifier = Modifier.weight(1f))
            }

            if (match.selections.isNotEmpty()) {
                val picks = match.selections.sortedBy { it.ordinal }.joinToString("-") { it.code }
                Text(
                    text = "Kupon seçiminiz: $picks • Yorumlarınız otomatik bu maça etiketlenir.",
                    color = ElectricGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "${postsForMatch.size} yorum • Bu ekrandan yazdığınız yorumlar doğrudan bu maça eklenir.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ProbabilityBadge(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Text(text = "${count} oy", color = CasinoGold, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
internal fun BulletinMatchPickerDialog(
    matches: List<TotoMatch>,
    selectedMatchNumber: Int?,
    commentCounts: Map<Int, Int>,
    onSelectMatch: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SportsSoccer,
                        contentDescription = null,
                        tint = CasinoGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bülten Maçı Seç", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Kapat", tint = TextMuted)
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "picker_general") {
                    val isSelected = selectedMatchNumber == null
                    Surface(
                        color = if (isSelected) ElectricCyan.copy(alpha = 0.16f) else DarkBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isSelected) ElectricCyan else DarkCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onSelectMatch(null)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Genel Bülten Stratejisi (Maçsız)",
                                color = if (isSelected) ElectricCyan else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                            if (isSelected) {
                                Text("Seçili", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(matches, key = { it.matchNumber }) { match ->
                    val isSelected = selectedMatchNumber == match.matchNumber
                    val count = commentCounts[match.matchNumber] ?: 0
                    val h = match.probability.home.firstOrNull() ?: 0
                    val d = match.probability.draw.firstOrNull() ?: 0
                    val a = match.probability.away.firstOrNull() ?: 0

                    Surface(
                        color = if (isSelected) CasinoGold.copy(alpha = 0.16f) else DarkBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isSelected) CasinoGold else DarkCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onSelectMatch(match.matchNumber)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) CasinoGold else DarkCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${match.matchNumber}",
                                    color = if (isSelected) Color.Black else CasinoGold,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${match.homeTeam} - ${match.awayTeam}",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "1: %$h  •  X: %$d  •  2: %$a  •  $count yorum",
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat", color = TextSecondary)
            }
        }
    )
}

@Composable
internal fun ModernForumCard(
    post: ForumPost,
    onToggleLike: () -> Unit,
    onReply: () -> Unit,
    onMatchBadgeClick: (Int) -> Unit,
    onInspectCoupon: () -> Unit = {},
    onReport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("modern_post_${post.id}"),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TeamBadgeView(team = post.authorTeam, size = 36.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = post.authorName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ElectricCyan.copy(alpha = 0.16f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = post.authorTeam.shortName,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                            if (!post.providerLabel.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = post.providerLabel,
                                    tint = ElectricGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Text(
                            text = post.dateText,
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(NeonAmber.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = post.authorTitle,
                        color = NeonAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            if (!post.matchReference.isNullOrBlank() || post.predictedOutcome != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBackground)
                        .border(1.dp, CasinoGold.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable(enabled = post.matchNumber != null) {
                            post.matchNumber?.let(onMatchBadgeClick)
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsSoccer,
                            contentDescription = "Bülten Maçı",
                            tint = CasinoGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = post.matchReference ?: "Bülten Maçı #${post.matchNumber ?: ""}",
                            color = CasinoGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    post.predictedOutcome?.let { pick ->
                        val pickColor = when (pick) {
                            PredictionOutcome.HOME -> ElectricGreen
                            PredictionOutcome.DRAW -> NeonAmber
                            PredictionOutcome.AWAY -> ElectricCyan
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(pickColor.copy(alpha = 0.2f))
                                .border(1.dp, pickColor.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Tahmin: ${pick.code}",
                                color = pickColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }
            }

            if (!post.replyToAuthor.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ElectricCyan.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "@${post.replyToAuthor} yorumuna yanıt",
                        color = ElectricCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = post.content,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )

            if (post.attachedCouponId != null || post.attachedCouponColumns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ElectricCyan.copy(alpha = 0.14f))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onInspectCoupon)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("btn_inspect_forum_coupon_${post.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = "İliştirilen kuponu incele",
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = post.attachedCouponSummary ?: "Paylaşılan Kupon",
                                color = ElectricCyan,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "15 maç tahminini ve kolonları incelemek için dokun",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ElectricCyan)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "İncele ▸",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleLike)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (post.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Beğen",
                            tint = if (post.isLiked) VibrantRed else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${post.likesCount}",
                            color = if (post.isLiked) VibrantRed else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onReply)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ModeComment,
                            contentDescription = "Yanıtla",
                            tint = ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Yanıtla", color = ElectricCyan, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                IconButton(
                    onClick = onReport,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = "Bildir",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

internal fun resolveForumPostCoupon(post: ForumPost, coupons: List<Coupon>): Coupon? {
    post.attachedCouponId?.let { id ->
        coupons.firstOrNull { it.id == id }?.let { return it }
    }
    if (post.attachedCouponColumns.isEmpty()) return null
    val parsedColumns = post.attachedCouponColumns.mapIndexedNotNull { idx, raw ->
        val clean = raw.trim().uppercase()
        if (clean.length != 15) return@mapIndexedNotNull null
        val predictions = clean.mapNotNull { ch ->
            PredictionOutcome.values().firstOrNull { it.code == ch.toString() }
        }
        if (predictions.size != 15) null else TotoColumn(index = idx + 1, predictions = predictions)
    }
    if (parsedColumns.isEmpty()) return null
    val formula = FormulaType.values().firstOrNull {
        it.title.equals(post.attachedCouponFormulaTitle, ignoreCase = true)
    } ?: FormulaType.GUARANTEE_14
    return Coupon(
        id = post.attachedCouponId ?: "forum_${post.id}",
        title = post.attachedCouponSummary ?: "${post.authorName} Forum Kuponu",
        formulaType = formula,
        columns = parsedColumns
    )
}

@Composable
internal fun ForumCouponInspectorDialog(
    post: ForumPost,
    resolvedCoupon: Coupon?,
    matches: List<TotoMatch>,
    liveScores: List<MatchLiveScore>,
    onOpenInCouponsTab: (Coupon) -> Unit,
    onDismiss: () -> Unit
) {
    var showMatchSummary by remember { mutableStateOf(false) }
    val resolvedMatchScores = remember(matches, liveScores) {
        if (matches.isNotEmpty() && liveScores.isNotEmpty()) {
            CouponPoolEvaluator.resolveBulletinScores(matches, liveScores)
        } else {
            emptyList()
        }
    }

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
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = post.attachedCouponSummary ?: "Paylaşılan Kupon",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${post.authorName} tarafından paylaşıldı",
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
            if (resolvedCoupon == null || resolvedCoupon.columns.isEmpty()) {
                Text(
                    text = "Bu paylaşıma ait kolon detayları şu anda cihazda bulunamadı. Yalnızca özet bilgisi mevcut: ${post.attachedCouponSummary ?: "Kupon"}.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground)
                            .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = resolvedCoupon.formulaType.title,
                            color = CasinoGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = "${resolvedCoupon.totalColumns} Kolon • ${String.format("%.2f ₺", resolvedCoupon.columnFeeTotalTL)}",
                            color = ElectricGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showMatchSummary,
                            onClick = { showMatchSummary = false },
                            label = {
                                Text(
                                    "Kolonlar (${resolvedCoupon.totalColumns})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = DarkBackground,
                                labelColor = TextSecondary,
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = showMatchSummary,
                            onClick = { showMatchSummary = true },
                            label = {
                                Text(
                                    "15 Maç Dağılımı",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = DarkBackground,
                                labelColor = TextSecondary,
                                selectedContainerColor = CasinoGold.copy(alpha = 0.2f),
                                selectedLabelColor = CasinoGold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (!showMatchSummary) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(resolvedCoupon.columns, key = { it.index }) { col ->
                                val liveHits = if (resolvedMatchScores.size == 15) {
                                    var hits = 0
                                    var settled = 0
                                    col.predictions.forEachIndexed { idx, pred ->
                                        val winning = resolvedMatchScores[idx]?.winningOutcome
                                        if (winning != null) {
                                            settled++
                                            if (winning == pred) hits++
                                        }
                                    }
                                    if (settled > 0) "$hits/$settled" else null
                                } else null

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkBackground)
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "#${col.index.toString().padStart(2, '0')}",
                                        color = CasinoGold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp
                                    )
                                    Text(
                                        text = col.predictions.chunked(5).joinToString("  ") { group ->
                                            group.joinToString("") { it.code }
                                        },
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    if (liveHits != null) {
                                        Text(
                                            text = "✓ $liveHits",
                                            color = ElectricGreen,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val orderedMatches = matches.sortedBy { it.matchNumber }.take(15)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items((0 until 15).toList(), key = { it }) { idx ->
                                val match = orderedMatches.getOrNull(idx)
                                val totalCols = resolvedCoupon.totalColumns.coerceAtLeast(1)
                                val c1 = resolvedCoupon.columns.count { it.predictions.getOrNull(idx) == PredictionOutcome.HOME }
                                val cx = resolvedCoupon.columns.count { it.predictions.getOrNull(idx) == PredictionOutcome.DRAW }
                                val c2 = resolvedCoupon.columns.count { it.predictions.getOrNull(idx) == PredictionOutcome.AWAY }
                                val matchTitle = match?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" }
                                    ?: "Maç #${idx + 1}"
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkBackground)
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = matchTitle,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "1: %${c1 * 100 / totalCols} ($c1)  •  X: %${cx * 100 / totalCols} ($cx)  •  2: %${c2 * 100 / totalCols} ($c2)",
                                        color = ElectricCyan,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (resolvedCoupon != null && resolvedCoupon.columns.isNotEmpty()) {
                Button(
                    onClick = {
                        onOpenInCouponsTab(resolvedCoupon)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("btn_open_forum_coupon_in_coupons")
                ) {
                    Text(
                        text = "Kuponlarım'da Aç",
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

private fun abbreviateTeam(name: String): String {
    val clean = name.trim()
    return if (clean.length <= 5) clean else clean.take(4) + "."
}
