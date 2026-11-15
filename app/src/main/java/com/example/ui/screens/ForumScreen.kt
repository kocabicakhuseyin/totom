package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Coupon
import com.example.model.ForumPost
import com.example.model.MatchLiveScore
import com.example.model.NewForumPostRequest
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.model.UserProfile
import com.example.repository.MemberSessionState
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

@Composable
fun ForumScreen(
    forumPosts: List<ForumPost>,
    userProfile: UserProfile,
    onAddPost: (String, String?) -> Unit = { _, _ -> },
    onToggleLike: (String) -> Unit,
    matches: List<TotoMatch> = emptyList(),
    liveScores: List<MatchLiveScore> = emptyList(),
    bulletinWeekLabel: String? = null,
    currentCoupon: Coupon? = null,
    coupons: List<Coupon> = emptyList(),
    memberSession: MemberSessionState = MemberSessionState(),
    hasPlayableBulletin: Boolean = true,
    onRefreshBulletin: () -> Unit = {},
    onOpenCouponInCoupons: (Coupon) -> Unit = {},
    onAddBulletinPost: ((NewForumPostRequest) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Filter state: FILTER_ALL_MATCHES (-1), FILTER_GENERAL_ONLY (0), or matchNumber (1..15)
    var selectedFilter by remember { mutableIntStateOf(FILTER_ALL_MATCHES) }
    var composerMatchNumber by remember { mutableStateOf<Int?>(null) }
    var selectedPick by remember { mutableStateOf<PredictionOutcome?>(null) }
    var replyTarget by remember { mutableStateOf<ForumPost?>(null) }
    var showMatchPicker by remember { mutableStateOf(false) }
    var inspectedPost by remember { mutableStateOf<ForumPost?>(null) }

    var commentText by remember { mutableStateOf("") }
    var attachCoupon by remember { mutableStateOf(false) }

    // Synchronize composer target when user taps a match filter chip
    LaunchedEffect(selectedFilter) {
        when {
            selectedFilter in 1..15 -> composerMatchNumber = selectedFilter
            selectedFilter == FILTER_GENERAL_ONLY -> {
                composerMatchNumber = null
                selectedPick = null
            }
        }
    }

    val selectedBulletinMatch = remember(selectedFilter, matches) {
        if (selectedFilter in 1..15) matches.firstOrNull { it.matchNumber == selectedFilter } else null
    }
    val composerMatch = remember(composerMatchNumber, matches) {
        composerMatchNumber?.let { num -> matches.firstOrNull { it.matchNumber == num } }
    }
    val commentCountsByMatch = remember(forumPosts) {
        forumPosts.mapNotNull { it.matchNumber }.groupingBy { it }.eachCount()
    }
    val visiblePosts = remember(forumPosts, selectedFilter) {
        when (selectedFilter) {
            FILTER_ALL_MATCHES -> forumPosts
            FILTER_GENERAL_ONLY -> forumPosts.filter { it.matchNumber == null }
            else -> forumPosts.filter { it.matchNumber == selectedFilter }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Community Title Bar
        Surface(
            color = DarkSurface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bülten Forumu & Maç Yorumları",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        text = bulletinWeekLabel?.takeIf { it.isNotBlank() }
                            ?: "Aktif 15 Maçlık Bülten • ${forumPosts.size} Yorum",
                        color = CasinoGold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElectricGreen.copy(alpha = 0.16f))
                        .border(1.dp, ElectricGreen.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (matches.size >= 15) "15 MAÇ AKTİF" else "CANLI FORUM",
                        color = ElectricGreen,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }
        }

        if (!hasPlayableBulletin || matches.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, NeonAmber.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Aktif maç bülteni bekleniyor",
                            color = NeonAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Maç bazlı yorum için haftalık bülteni yenileyin.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onRefreshBulletin,
                        colors = ButtonDefaults.buttonColors(containerColor = CasinoGold, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Bülteni Yenile",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Yenile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        } else {
            BulletinMatchFilterStrip(
                matches = matches,
                liveScores = liveScores,
                forumPosts = forumPosts,
                selectedFilter = selectedFilter,
                onSelectFilter = { selectedFilter = it }
            )
        }

        selectedBulletinMatch?.let { activeMatch ->
            SelectedMatchInsightsCard(
                match = activeMatch,
                liveScore = liveScores.firstOrNull { it.matchNumber == activeMatch.matchNumber },
                postsForMatch = visiblePosts,
                onClearFilter = { selectedFilter = FILTER_ALL_MATCHES }
            )
        }

        // Forum Feed
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (visiblePosts.isEmpty()) {
                item(key = "empty_thread") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        border = BorderStroke(1.dp, DarkCardBorder),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsSoccer,
                                contentDescription = null,
                                tint = CasinoGold,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = if (selectedBulletinMatch != null) {
                                    "#${selectedBulletinMatch.matchNumber} ${selectedBulletinMatch.homeTeam} - ${selectedBulletinMatch.awayTeam} için henüz yorum yok"
                                } else {
                                    "Bu filtrede henüz yorum bulunmuyor"
                                },
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "Aşağıdaki kutudan maç tahmini seçerek ilk yorumu paylaşabilirsiniz.",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            items(visiblePosts, key = { it.id }) { post ->
                ModernForumCard(
                    post = post,
                    onToggleLike = { onToggleLike(post.id) },
                    onReply = {
                        replyTarget = post
                        if (post.matchNumber != null) {
                            composerMatchNumber = post.matchNumber
                        }
                    },
                    onMatchBadgeClick = { matchNum ->
                        selectedFilter = matchNum
                    },
                    onInspectCoupon = {
                        inspectedPost = post
                    },
                    onReport = {
                        Toast.makeText(context, "Yorum incelenmek üzere bildirildi.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // Dark Bulletin-Aware Comment Composer
        Surface(
            color = DarkSurface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Target Match + Outcome Pick + Reply Context strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (composerMatch != null) CasinoGold.copy(alpha = 0.18f) else DarkCard)
                            .border(
                                1.dp,
                                if (composerMatch != null) CasinoGold else DarkCardBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = matches.isNotEmpty()) { showMatchPicker = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("btn_forum_pick_match"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsSoccer,
                            contentDescription = "Bülten maçı seç",
                            tint = if (composerMatch != null) CasinoGold else ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = composerMatch?.let {
                                "#${it.matchNumber} ${it.homeTeam.take(6)}-${it.awayTeam.take(6)} ▾"
                            } ?: "Maç Seç (Genel) ▾",
                            color = if (composerMatch != null) CasinoGold else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        )
                    }

                    if (composerMatch != null) {
                        PredictionOutcome.values().forEach { outcome ->
                            val picked = selectedPick == outcome
                            val pickColor = when (outcome) {
                                PredictionOutcome.HOME -> ElectricGreen
                                PredictionOutcome.DRAW -> NeonAmber
                                PredictionOutcome.AWAY -> ElectricCyan
                            }
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (picked) pickColor else DarkCard)
                                    .border(1.dp, if (picked) pickColor else DarkCardBorder, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedPick = if (picked) null else outcome
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("btn_forum_pick_${outcome.code}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = outcome.code,
                                    color = if (picked) Color.Black else TextSecondary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    replyTarget?.let { target ->
                        Row(
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElectricCyan.copy(alpha = 0.16f))
                                .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .clickable { replyTarget = null }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "@${target.authorName}",
                                color = ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Yanıtı kaldır",
                                tint = TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    if (attachCoupon && currentCoupon != null) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElectricGreen.copy(alpha = 0.16f))
                                .border(1.dp, ElectricGreen.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .clickable { attachCoupon = false }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = ElectricGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${currentCoupon.totalColumns} Kolon ✕",
                                color = ElectricGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TeamBadgeView(team = userProfile.favoriteTeam, size = 36.dp)

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_modern_comment"),
                        placeholder = {
                            val hint = composerMatch?.let {
                                "#${it.matchNumber} ${it.homeTeam}-${it.awayTeam} yorumu..."
                            } ?: "Bülten tahmininizi veya yorumunuzu yazın..."
                            Text(
                                text = hint,
                                fontSize = 12.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = CasinoGold,
                            unfocusedIndicatorColor = DarkCardBorder
                        ),
                        maxLines = 3,
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (currentCoupon != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { attachCoupon = !attachCoupon },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = "Kupon Ekle",
                                tint = if (attachCoupon) ElectricGreen else TextMuted
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = {
                            if (commentText.isNotBlank()) {
                                val couponIdToAttach = if (attachCoupon) currentCoupon?.id else null
                                val couponSummary = if (attachCoupon && currentCoupon != null) {
                                    "${currentCoupon.title} (${currentCoupon.totalColumns} Kolon)"
                                } else null
                                val couponCols = if (attachCoupon && currentCoupon != null) {
                                    currentCoupon.columns.map { it.asString }
                                } else emptyList()
                                val couponFormulaTitle = if (attachCoupon && currentCoupon != null) {
                                    currentCoupon.formulaType.title
                                } else null
                                val request = NewForumPostRequest(
                                    content = commentText.trim(),
                                    attachedCouponId = couponIdToAttach,
                                    attachedCouponSummary = couponSummary,
                                    matchNumber = composerMatch?.matchNumber,
                                    matchReference = composerMatch?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" },
                                    predictedOutcome = if (composerMatch != null) selectedPick else null,
                                    replyToPostId = replyTarget?.id,
                                    replyToAuthor = replyTarget?.authorName,
                                    attachedCouponColumns = couponCols,
                                    attachedCouponFormulaTitle = couponFormulaTitle
                                )
                                if (onAddBulletinPost != null) {
                                    onAddBulletinPost(request)
                                } else {
                                    onAddPost(request.content, request.attachedCouponId)
                                }
                                commentText = ""
                                attachCoupon = false
                                selectedPick = null
                                replyTarget = null
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (commentText.isNotBlank()) CasinoGold else DarkCardBorder)
                            .testTag("btn_send_comment")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Gönder",
                            tint = if (commentText.isNotBlank()) Color.Black else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    if (showMatchPicker) {
        BulletinMatchPickerDialog(
            matches = matches,
            selectedMatchNumber = composerMatchNumber,
            commentCounts = commentCountsByMatch,
            onSelectMatch = { pickedNum ->
                composerMatchNumber = pickedNum
                if (pickedNum == null) selectedPick = null
            },
            onDismiss = { showMatchPicker = false }
        )
    }

    inspectedPost?.let { post ->
        val resolvedCoupon = remember(post, coupons) {
            resolveForumPostCoupon(post, coupons)
        }
        ForumCouponInspectorDialog(
            post = post,
            resolvedCoupon = resolvedCoupon,
            matches = matches,
            liveScores = liveScores,
            onOpenInCouponsTab = onOpenCouponInCoupons,
            onDismiss = { inspectedPost = null }
        )
    }
}
