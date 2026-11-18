package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.ShareCardConfig
import com.example.engine.ShareCardExporter
import com.example.engine.ShareCardTheme
import com.example.engine.ShareColumnScope
import com.example.model.Coupon
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
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

/**
 * Interactive sharing studio dialog allowing users to share prepared prediction columns
 * with community members in the Forum, copy formatted 1-X-2 text, or export and share
 * a themed high-resolution PNG card for social media.
 */
@Composable
fun CouponShareDialog(
    coupon: Coupon,
    matches: List<TotoMatch>,
    authorName: String,
    weekLabel: String = "",
    onDismiss: () -> Unit,
    onShareToForum: (String) -> Unit,
    onOpenPool: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableStateOf(ShareCardTheme.DARK_GOLD) }
    var selectedScope by remember { mutableStateOf(ShareColumnScope.FEATURED_COLUMNS) }
    var selectedColumnIndex by remember(coupon.id) { mutableIntStateOf(0) }
    var showTeamNames by remember { mutableStateOf(true) }
    var customNote by remember { mutableStateOf("") }

    val safeSelectedIdx = if (coupon.columns.isEmpty()) 0 else selectedColumnIndex.coerceIn(0, coupon.columns.lastIndex)

    val config = remember(
        coupon,
        matches,
        authorName,
        weekLabel,
        selectedTheme,
        selectedScope,
        safeSelectedIdx,
        showTeamNames,
        customNote
    ) {
        ShareCardConfig(
            coupon = coupon,
            matches = matches,
            authorName = authorName,
            weekLabel = weekLabel,
            theme = selectedTheme,
            scope = selectedScope,
            selectedColumnIndex = safeSelectedIdx,
            showTeamNames = showTeamNames,
            customNote = customNote
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("coupon_share_dialog")
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tahmin & Görsel Paylaşım Stüdyosu",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${coupon.title} • ${coupon.totalColumns} Kolon",
                        color = CasinoGold,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_close_share_dialog")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Paylaşım ekranını kapat",
                        tint = TextPrimary
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Visual Social Card Preview
                item {
                    ShareCardLivePreview(config = config)
                }

                // 2. Theme Picker
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        border = BorderStroke(1.dp, DarkCardBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Görsel Kart Teması",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ShareCardTheme.values().forEach { theme ->
                                    val selected = theme == selectedTheme
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedTheme = theme },
                                        label = {
                                            Text(
                                                text = theme.label,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(theme.accentHex).copy(alpha = 0.22f),
                                            selectedLabelColor = CasinoGold,
                                            containerColor = DarkSurface,
                                            labelColor = TextSecondary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("share_theme_${theme.id}")
                                    )
                                }
                            }

                            Text(
                                text = "İçerik Görünümü",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ShareColumnScope.values().forEach { scope ->
                                    val selected = scope == selectedScope
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedScope = scope },
                                        label = {
                                            Text(
                                                text = scope.label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                            selectedLabelColor = ElectricCyan,
                                            containerColor = DarkSurface,
                                            labelColor = TextSecondary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("share_scope_${scope.id}")
                                    )
                                }
                            }

                            if (selectedScope == ShareColumnScope.SINGLE_COLUMN && coupon.columns.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(DarkSurface)
                                        .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (safeSelectedIdx > 0) selectedColumnIndex = safeSelectedIdx - 1
                                        },
                                        enabled = safeSelectedIdx > 0,
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("btn_share_prev_col")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "Önceki kolon",
                                            tint = if (safeSelectedIdx > 0) ElectricCyan else TextMuted
                                        )
                                    }
                                    Text(
                                        text = "Kolon #${coupon.columns[safeSelectedIdx].index} / ${coupon.totalColumns}",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    IconButton(
                                        onClick = {
                                            if (safeSelectedIdx < coupon.columns.lastIndex) {
                                                selectedColumnIndex = safeSelectedIdx + 1
                                            }
                                        },
                                        enabled = safeSelectedIdx < coupon.columns.lastIndex,
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                            .testTag("btn_share_next_col")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Sonraki kolon",
                                            tint = if (safeSelectedIdx < coupon.columns.lastIndex) ElectricCyan else TextMuted
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Takım İsimlerini Göster",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Kart ve metin çıktısında 15 maçın eşleşmeleri görünsün",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = showTeamNames,
                                    onCheckedChange = { showTeamNames = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = ElectricGreen
                                    ),
                                    modifier = Modifier.testTag("toggle_share_team_names")
                                )
                            }

                            OutlinedTextField(
                                value = customNote,
                                onValueChange = { if (it.length <= 100) customNote = it },
                                label = { Text("Paylaşım Notu (İsteğe bağlı)") },
                                placeholder = { Text("Örn: Bu hafta 15 hedefi için sürprizleri ekledim") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CasinoGold,
                                    unfocusedBorderColor = DarkCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = CasinoGold,
                                    unfocusedLabelColor = TextMuted
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_share_note")
                            )
                        }
                    }
                }

                // 3. Export & Community Sharing Actions
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        border = BorderStroke(1.dp, DarkCardBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Dışa Aktar ve Paylaş",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )

                            Button(
                                onClick = {
                                    if (coupon.columns.isEmpty()) {
                                        Toast.makeText(context, "Paylaşılacak kolon bulunmuyor.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        ShareCardExporter.shareImageAndText(context, config)
                                            .onFailure { err ->
                                                Toast.makeText(
                                                    context,
                                                    "Görsel paylaşılamadı: ${err.localizedMessage ?: "Bilinmeyen hata"}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .testTag("btn_share_visual_png")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sosyal Medyada Görsel Paylaş (PNG)",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (coupon.columns.isEmpty()) {
                                            Toast.makeText(context, "Kaydedilecek kolon yok.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            ShareCardExporter.saveImageToGallery(context, config)
                                                .onSuccess { msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                }
                                                .onFailure { err ->
                                                    Toast.makeText(
                                                        context,
                                                        "PNG kaydedilemedi: ${err.localizedMessage ?: "Hata"}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                        }
                                    },
                                    border = BorderStroke(1.dp, ElectricGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("btn_save_visual_png")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        tint = ElectricGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "PNG Kaydet",
                                        color = ElectricGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val text = ShareCardExporter.formatShareText(config)
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("Totom Kupon Tahmini", text))
                                        Toast.makeText(context, "Tahmin özeti panoya kopyalandı!", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, ElectricCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("btn_copy_share_text")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Metin Kopyala",
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    onShareToForum(customNote.trim())
                                    Toast.makeText(
                                        context,
                                        "Tahmin kolonlarınız Forumda paylaşıldı!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E3A53)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("btn_share_to_forum_dialog")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forum,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Forumda Kullanıcılarla Paylaş",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            if (onOpenPool != null) {
                                OutlinedButton(
                                    onClick = {
                                        onDismiss()
                                        onOpenPool()
                                    },
                                    border = BorderStroke(1.dp, DarkCardBorder),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("btn_share_to_pool_dialog")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Canlı Kupon Havuzuna Gönder / Gör",
                                        color = TextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareCardLivePreview(config: ShareCardConfig) {
    val theme = config.theme
    val cardBg = Color(theme.cardHex)
    val surfaceBg = Color(theme.surfaceHex)
    val borderCol = Color(theme.borderHex)
    val accentCol = Color(theme.accentHex)
    val titleCol = Color(theme.titleHex)
    val bodyCol = Color(theme.bodyHex)
    val mutedCol = Color(theme.mutedHex)

    val visibleColumns = remember(config) { ShareCardExporter.resolveVisibleColumns(config) }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.5.dp, borderCol),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("share_card_preview")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TOTOM • PAYLAŞIM KARTI",
                    color = accentCol,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentCol)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = config.coupon.formulaType.badge,
                        color = if (theme == ShareCardTheme.DARK_GOLD) Color.Black else Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }

            Text(
                text = config.coupon.title,
                color = titleCol,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = buildString {
                    append("Hazırlayan: ${config.authorName.ifBlank { "Totom Üyesi" }}")
                    append(" • ${config.coupon.totalColumns} Kolon")
                    if (config.weekLabel.isNotBlank()) append(" • ${config.weekLabel}")
                },
                color = mutedCol,
                fontSize = 11.sp
            )

            if (config.customNote.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(surfaceBg)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "💬 ${config.customNote.trim()}",
                        color = bodyCol,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            when (config.scope) {
                ShareColumnScope.SINGLE_COLUMN -> {
                    val col = visibleColumns.firstOrNull()
                    if (col != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(surfaceBg)
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "SEÇİLİ KOLON #${col.index}",
                                    color = accentCol,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                PreviewOutcomePillRow(col.predictions)
                            }
                        }
                    }
                    if (config.showTeamNames && config.matches.isNotEmpty()) {
                        PreviewMatchRows(
                            matches = config.matches,
                            singleColumnPredictions = col?.predictions,
                            distributions = config.coupon.matchDistributions,
                            showTeamNames = true,
                            surfaceBg = surfaceBg,
                            bodyCol = bodyCol,
                            accentCol = accentCol
                        )
                    }
                }

                ShareColumnScope.FEATURED_COLUMNS -> {
                    visibleColumns.take(5).forEach { col ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceBg)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "K${col.index.toString().padStart(2, '0')}",
                                color = bodyCol,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.width(30.dp)
                            )
                            PreviewOutcomePillRow(
                                predictions = col.predictions,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (config.coupon.totalColumns > 5) {
                        Text(
                            text = "+${config.coupon.totalColumns - 5} kolon daha PNG kartında ve metinde yer alır",
                            color = mutedCol,
                            fontSize = 10.5.sp
                        )
                    }
                }

                ShareColumnScope.DISTRIBUTION_ONLY -> {
                    PreviewMatchRows(
                        matches = config.matches,
                        singleColumnPredictions = null,
                        distributions = config.coupon.matchDistributions,
                        showTeamNames = config.showTeamNames,
                        surfaceBg = surfaceBg,
                        bodyCol = bodyCol,
                        accentCol = accentCol
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewOutcomePillRow(
    predictions: List<PredictionOutcome>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (idx in 0 until 15) {
            val outcome = predictions.getOrNull(idx)
            val bg = when (outcome) {
                PredictionOutcome.HOME -> Color(0xFF10B981)
                PredictionOutcome.DRAW -> Color(0xFF06B6D4)
                PredictionOutcome.AWAY -> Color(0xFFF59E0B)
                null -> Color(0xFF334155)
            }
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = outcome?.code ?: "-",
                    color = if (outcome == null) Color.White else Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PreviewMatchRows(
    matches: List<TotoMatch>,
    singleColumnPredictions: List<PredictionOutcome>?,
    distributions: List<Triple<Int, Int, Int>>,
    showTeamNames: Boolean,
    surfaceBg: Color,
    bodyCol: Color,
    accentCol: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (idx in 0 until 15) {
            val match = matches.getOrNull(idx)
            val matchLabel = if (showTeamNames && match != null) {
                "${(idx + 1).toString().padStart(2, '0')}. ${match.homeTeam} - ${match.awayTeam}"
            } else {
                "${(idx + 1).toString().padStart(2, '0')}. Karşılaşma #${idx + 1}"
            }
            val rightLabel = if (singleColumnPredictions != null) {
                singleColumnPredictions.getOrNull(idx)?.code ?: "-"
            } else {
                val (h, d, a) = distributions.getOrElse(idx) { Triple(0, 0, 0) }
                buildList {
                    if (h > 0) add("1:$h")
                    if (d > 0) add("X:$d")
                    if (a > 0) add("2:$a")
                }.joinToString(" ").ifBlank { "-" }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (idx % 2 == 0) surfaceBg.copy(alpha = 0.7f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = matchLabel,
                    color = bodyCol,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rightLabel,
                    color = accentCol,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
