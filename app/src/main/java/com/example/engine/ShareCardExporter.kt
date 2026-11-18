package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.model.Coupon
import com.example.model.PredictionOutcome
import com.example.model.TotoColumn
import com.example.model.TotoMatch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShareCardTheme(
    val id: String,
    val label: String,
    val backgroundHex: Int,
    val cardHex: Int,
    val surfaceHex: Int,
    val borderHex: Int,
    val accentHex: Int,
    val titleHex: Int,
    val bodyHex: Int,
    val mutedHex: Int
) {
    DARK_GOLD(
        id = "dark_gold",
        label = "Gece Altın",
        backgroundHex = 0xFF0A0F1C.toInt(),
        cardHex = 0xFF131B2E.toInt(),
        surfaceHex = 0xFF1C2740.toInt(),
        borderHex = 0xFFF59E0B.toInt(),
        accentHex = 0xFFF59E0B.toInt(),
        titleHex = 0xFFF8FAFC.toInt(),
        bodyHex = 0xFFE2E8F0.toInt(),
        mutedHex = 0xFF94A3B8.toInt()
    ),
    NEON_PITCH(
        id = "neon_pitch",
        label = "Yeşil Saha",
        backgroundHex = 0xFF051B14.toInt(),
        cardHex = 0xFF0B2920.toInt(),
        surfaceHex = 0xFF12392D.toInt(),
        borderHex = 0xFF10B981.toInt(),
        accentHex = 0xFF10B981.toInt(),
        titleHex = 0xFFECFDF5.toInt(),
        bodyHex = 0xFFD1FAE5.toInt(),
        mutedHex = 0xFF6EE7B7.toInt()
    ),
    CLASSIC_SLATE(
        id = "classic_slate",
        label = "Klasik Fiş",
        backgroundHex = 0xFFF1F5F9.toInt(),
        cardHex = 0xFFFFFFFF.toInt(),
        surfaceHex = 0xFFE2E8F0.toInt(),
        borderHex = 0xFF334155.toInt(),
        accentHex = 0xFF0284C7.toInt(),
        titleHex = 0xFF0F172A.toInt(),
        bodyHex = 0xFF1E293B.toInt(),
        mutedHex = 0xFF64748B.toInt()
    )
}

enum class ShareColumnScope(val id: String, val label: String) {
    FEATURED_COLUMNS("featured_columns", "Kolon Listesi"),
    DISTRIBUTION_ONLY("distribution_only", "1-X-2 Dağılım"),
    SINGLE_COLUMN("single_column", "Tek Kolon")
}

data class ShareCardConfig(
    val coupon: Coupon,
    val matches: List<TotoMatch>,
    val authorName: String,
    val weekLabel: String = "",
    val theme: ShareCardTheme = ShareCardTheme.DARK_GOLD,
    val scope: ShareColumnScope = ShareColumnScope.FEATURED_COLUMNS,
    val selectedColumnIndex: Int = 0,
    val showTeamNames: Boolean = true,
    val customNote: String = ""
)

object ShareCardExporter {

    private const val CARD_WIDTH = 1080
    private const val HORIZONTAL_PADDING = 56f

    fun resolveVisibleColumns(config: ShareCardConfig): List<TotoColumn> {
        val cols = config.coupon.columns
        if (cols.isEmpty()) return emptyList()
        return when (config.scope) {
            ShareColumnScope.SINGLE_COLUMN -> {
                val safeIdx = config.selectedColumnIndex.coerceIn(0, cols.lastIndex)
                listOf(cols[safeIdx])
            }
            ShareColumnScope.FEATURED_COLUMNS -> cols.take(8)
            ShareColumnScope.DISTRIBUTION_ONLY -> emptyList()
        }
    }

    fun formatShareText(config: ShareCardConfig): String = buildString {
        val coupon = config.coupon
        val author = config.authorName.ifBlank { "Totom Üyesi" }
        appendLine("⚽ ${coupon.title} (${coupon.formulaType.badge})")
        if (config.weekLabel.isNotBlank()) {
            appendLine("📅 ${config.weekLabel}")
        }
        appendLine("👤 Hazırlayan: $author • Toplam ${coupon.totalColumns} Kolon")
        if (config.customNote.isNotBlank()) {
            appendLine("💬 Not: ${config.customNote.trim()}")
        }
        appendLine()

        when (config.scope) {
            ShareColumnScope.SINGLE_COLUMN -> {
                val col = resolveVisibleColumns(config).firstOrNull()
                if (col != null) {
                    appendLine("🎯 Kolon #${col.index}: ${col.predictions.joinToString("-") { it.code }}")
                    if (config.showTeamNames && config.matches.isNotEmpty()) {
                        appendLine()
                        config.matches.take(15).forEachIndexed { idx, match ->
                            val pick = col.predictions.getOrNull(idx)?.code ?: "-"
                            appendLine("${(idx + 1).toString().padStart(2, '0')}. ${match.homeTeam} - ${match.awayTeam} -> [$pick]")
                        }
                    }
                }
            }
            ShareColumnScope.FEATURED_COLUMNS -> {
                val cols = config.coupon.columns.take(10)
                cols.forEach { col ->
                    appendLine("Kolon #${col.index.toString().padStart(2, '0')}: ${col.predictions.joinToString("-") { it.code }}")
                }
                if (config.coupon.totalColumns > cols.size) {
                    appendLine("... ve +${config.coupon.totalColumns - cols.size} kolon daha")
                }
                if (config.showTeamNames && config.matches.isNotEmpty()) {
                    appendLine()
                    appendLine("📊 Maç Dağılım Özeti:")
                    config.coupon.matchDistributions.forEachIndexed { idx, (h, d, a) ->
                        val match = config.matches.getOrNull(idx)
                        val title = if (match != null) "${match.homeTeam} - ${match.awayTeam}" else "Maç ${idx + 1}"
                        val picks = buildList {
                            if (h > 0) add("1($h)")
                            if (d > 0) add("X($d)")
                            if (a > 0) add("2($a)")
                        }.joinToString(" ")
                        appendLine("${(idx + 1).toString().padStart(2, '0')}. $title: $picks")
                    }
                }
            }
            ShareColumnScope.DISTRIBUTION_ONLY -> {
                appendLine("📊 15 Maç Tahmin Dağılımı:")
                config.coupon.matchDistributions.forEachIndexed { idx, (h, d, a) ->
                    val match = config.matches.getOrNull(idx)
                    val title = if (config.showTeamNames && match != null) {
                        "${match.homeTeam} - ${match.awayTeam}"
                    } else {
                        "Maç ${idx + 1}"
                    }
                    val picks = buildList {
                        if (h > 0) add("1:$h")
                        if (d > 0) add("X:$d")
                        if (a > 0) add("2:$a")
                    }.joinToString(" | ")
                    appendLine("${(idx + 1).toString().padStart(2, '0')}. $title -> $picks")
                }
            }
        }
    }.trim()

    fun renderBitmap(config: ShareCardConfig): Bitmap {
        val theme = config.theme
        val visibleColumns = resolveVisibleColumns(config)
        val hasMatches = config.showTeamNames && config.matches.isNotEmpty()
        val showMatchTable = config.scope == ShareColumnScope.DISTRIBUTION_ONLY ||
            (config.scope == ShareColumnScope.SINGLE_COLUMN && hasMatches)
        val matchRowsCount = if (showMatchTable) 15 else 0
        val columnRowsCount = if (config.scope == ShareColumnScope.FEATURED_COLUMNS) visibleColumns.size else 0

        val noteLinesHeight = if (config.customNote.isNotBlank()) 88 else 0
        val matchSectionHeight = if (matchRowsCount > 0) 80 + matchRowsCount * 58 else 0
        val columnsSectionHeight = when (config.scope) {
            ShareColumnScope.FEATURED_COLUMNS -> 110 + columnRowsCount * 68 + if (config.coupon.totalColumns > visibleColumns.size) 50 else 0
            ShareColumnScope.SINGLE_COLUMN -> 150
            ShareColumnScope.DISTRIBUTION_ONLY -> 0
        }
        val totalHeight = (360 + noteLinesHeight + columnsSectionHeight + matchSectionHeight + 110)
            .coerceIn(720, 2400)

        val bitmap = Bitmap.createBitmap(CARD_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme.backgroundHex)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardHex
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.borderHex
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.surfaceHex
            style = Paint.Style.FILL
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentHex
            style = Paint.Style.FILL
        }

        val outerRect = RectF(28f, 28f, CARD_WIDTH - 28f, totalHeight - 28f)
        canvas.drawRoundRect(outerRect, 36f, 36f, cardPaint)
        canvas.drawRoundRect(outerRect, 36f, 36f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.titleHex
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val accentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentHex
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.bodyHex
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.mutedHex
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        var cursorY = 92f

        // Brand & formula badge
        canvas.drawText("TOTOM • SPOR TOTO TAHMİN KARTI", HORIZONTAL_PADDING, cursorY, accentTextPaint)
        val badgeText = config.coupon.formulaType.badge
        val badgeWidth = accentTextPaint.measureText(badgeText) + 42f
        val badgeRect = RectF(
            CARD_WIDTH - HORIZONTAL_PADDING - badgeWidth,
            cursorY - 34f,
            CARD_WIDTH - HORIZONTAL_PADDING,
            cursorY + 14f
        )
        canvas.drawRoundRect(badgeRect, 14f, 14f, accentPaint)
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (theme == ShareCardTheme.DARK_GOLD) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(badgeText, badgeRect.centerX(), badgeRect.centerY() + 8f, badgeTextPaint)

        cursorY += 58f
        val cleanTitle = ellipsizeText(config.coupon.title, titlePaint, CARD_WIDTH - HORIZONTAL_PADDING * 2)
        canvas.drawText(cleanTitle, HORIZONTAL_PADDING, cursorY, titlePaint)

        cursorY += 42f
        val dateStamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        val metaLine = buildString {
            append("Hazırlayan: ${config.authorName.ifBlank { "Totom Üyesi" }}")
            append("  •  ${config.coupon.totalColumns} Kolon")
            if (config.weekLabel.isNotBlank()) {
                append("  •  ${config.weekLabel}")
            } else {
                append("  •  $dateStamp")
            }
        }
        canvas.drawText(ellipsizeText(metaLine, mutedPaint, CARD_WIDTH - HORIZONTAL_PADDING * 2), HORIZONTAL_PADDING, cursorY, mutedPaint)

        cursorY += 32f
        if (config.customNote.isNotBlank()) {
            val noteRect = RectF(HORIZONTAL_PADDING, cursorY, CARD_WIDTH - HORIZONTAL_PADDING, cursorY + 66f)
            canvas.drawRoundRect(noteRect, 16f, 16f, surfacePaint)
            val noteText = ellipsizeText("💬 ${config.customNote.trim()}", bodyPaint, noteRect.width() - 32f)
            canvas.drawText(noteText, noteRect.left + 16f, noteRect.centerY() + 9f, bodyPaint)
            cursorY += 86f
        }

        // Single Column Spotlight
        if (config.scope == ShareColumnScope.SINGLE_COLUMN) {
            val col = visibleColumns.firstOrNull()
            if (col != null) {
                val colBox = RectF(HORIZONTAL_PADDING, cursorY, CARD_WIDTH - HORIZONTAL_PADDING, cursorY + 124f)
                canvas.drawRoundRect(colBox, 20f, 20f, surfacePaint)
                canvas.drawText("SEÇİLİ KOLON #${col.index}", colBox.left + 22f, colBox.top + 38f, accentTextPaint)
                drawOutcomePillRow(
                    canvas = canvas,
                    predictions = col.predictions,
                    startX = colBox.left + 22f,
                    centerY = colBox.top + 86f,
                    totalWidth = colBox.width() - 44f
                )
                cursorY += 144f
            }
        }

        // Multiple Featured Columns List
        if (config.scope == ShareColumnScope.FEATURED_COLUMNS && visibleColumns.isNotEmpty()) {
            canvas.drawText("TAHMİN KOLONLARI", HORIZONTAL_PADDING, cursorY + 26f, accentTextPaint)
            cursorY += 46f

            visibleColumns.forEach { col ->
                val rowRect = RectF(HORIZONTAL_PADDING, cursorY, CARD_WIDTH - HORIZONTAL_PADDING, cursorY + 56f)
                canvas.drawRoundRect(rowRect, 14f, 14f, surfacePaint)
                canvas.drawText("K${col.index.toString().padStart(2, '0')}", rowRect.left + 16f, rowRect.centerY() + 9f, bodyPaint)
                drawOutcomePillRow(
                    canvas = canvas,
                    predictions = col.predictions,
                    startX = rowRect.left + 92f,
                    centerY = rowRect.centerY(),
                    totalWidth = rowRect.width() - 108f
                )
                cursorY += 66f
            }

            if (config.coupon.totalColumns > visibleColumns.size) {
                canvas.drawText(
                    "+ ${config.coupon.totalColumns - visibleColumns.size} kolon daha kupon içinde yer alıyor",
                    HORIZONTAL_PADDING,
                    cursorY + 24f,
                    mutedPaint
                )
                cursorY += 42f
            }
        }

        // 15 Match Matrix / Distribution Section
        if (showMatchTable) {
            canvas.drawText(
                if (config.scope == ShareColumnScope.SINGLE_COLUMN) "MAÇ EŞLEŞMELERİ" else "15 MAÇ TAHMİN DAĞILIMI",
                HORIZONTAL_PADDING,
                cursorY + 28f,
                accentTextPaint
            )
            cursorY += 48f

            val singleCol = if (config.scope == ShareColumnScope.SINGLE_COLUMN) visibleColumns.firstOrNull() else null
            val distributions = config.coupon.matchDistributions

            for (idx in 0 until 15) {
                val rowRect = RectF(HORIZONTAL_PADDING, cursorY, CARD_WIDTH - HORIZONTAL_PADDING, cursorY + 48f)
                if (idx % 2 == 0) {
                    canvas.drawRoundRect(rowRect, 10f, 10f, surfacePaint)
                }
                val match = config.matches.getOrNull(idx)
                val matchLabel = if (config.showTeamNames && match != null) {
                    "${(idx + 1).toString().padStart(2, '0')}. ${match.homeTeam} - ${match.awayTeam}"
                } else {
                    "${(idx + 1).toString().padStart(2, '0')}. Karşılaşma #${idx + 1}"
                }
                canvas.drawText(
                    ellipsizeText(matchLabel, bodyPaint, 620f),
                    rowRect.left + 14f,
                    rowRect.centerY() + 9f,
                    bodyPaint
                )

                val summaryText = if (singleCol != null) {
                    singleCol.predictions.getOrNull(idx)?.code ?: "-"
                } else {
                    val (h, d, a) = distributions.getOrElse(idx) { Triple(0, 0, 0) }
                    buildList {
                        if (h > 0) add("1:$h")
                        if (d > 0) add("X:$d")
                        if (a > 0) add("2:$a")
                    }.joinToString("  ").ifBlank { "-" }
                }
                val rightPaint = Paint(accentTextPaint).apply {
                    textSize = 22f
                    textAlign = Paint.Align.RIGHT
                }
                canvas.drawText(summaryText, rowRect.right - 16f, rowRect.centerY() + 8f, rightPaint)
                cursorY += 56f
            }
        }

        // Footer
        val footerPaint = Paint(mutedPaint).apply {
            textAlign = Paint.Align.CENTER
            textSize = 20f
        }
        canvas.drawText(
            "Totom Android ile oluşturuldu • Paylaşılabilir Tahmin Kartı",
            CARD_WIDTH / 2f,
            totalHeight - 54f,
            footerPaint
        )

        return bitmap
    }

    private fun drawOutcomePillRow(
        canvas: Canvas,
        predictions: List<PredictionOutcome>,
        startX: Float,
        centerY: Float,
        totalWidth: Float
    ) {
        val count = 15
        val gap = 6f
        val pillWidth = ((totalWidth - gap * (count - 1)) / count).coerceAtLeast(30f)
        val pillHeight = 36f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until count) {
            val outcome = predictions.getOrNull(i)
            val left = startX + i * (pillWidth + gap)
            val rect = RectF(left, centerY - pillHeight / 2f, left + pillWidth, centerY + pillHeight / 2f)
            when (outcome) {
                PredictionOutcome.HOME -> {
                    bgPaint.color = 0xFF10B981.toInt()
                    textPaint.color = 0xFF000000.toInt()
                }
                PredictionOutcome.DRAW -> {
                    bgPaint.color = 0xFF06B6D4.toInt()
                    textPaint.color = 0xFF000000.toInt()
                }
                PredictionOutcome.AWAY -> {
                    bgPaint.color = 0xFFF59E0B.toInt()
                    textPaint.color = 0xFF000000.toInt()
                }
                null -> {
                    bgPaint.color = 0xFF334155.toInt()
                    textPaint.color = 0xFFFFFFFF.toInt()
                }
            }
            canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
            canvas.drawText(outcome?.code ?: "-", rect.centerX(), rect.centerY() + 7f, textPaint)
        }
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end.coerceAtLeast(1)) + "…"
    }

    fun writePngToCacheFile(context: Context, bitmap: Bitmap, fileNamePrefix: String = "totom_kupon"): File {
        val dir = File(context.cacheDir, "shared_coupons").apply { mkdirs() }
        // Prune stale temp exports so cache stays compact
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
        val safePrefix = fileNamePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(24)
        val outFile = File(dir, "${safePrefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return outFile
    }

    fun shareImageAndText(context: Context, config: ShareCardConfig): Result<Uri> {
        return runCatching {
            val bitmap = renderBitmap(config)
            val file = writePngToCacheFile(context, bitmap, config.coupon.id)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val summaryText = formatShareText(config)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, summaryText)
                putExtra(Intent.EXTRA_SUBJECT, "${config.coupon.title} • Spor Toto Tahmini")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Tahmin Görselini Paylaş").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            uri
        }
    }

    fun saveImageToGallery(context: Context, config: ShareCardConfig): Result<String> {
        return runCatching {
            val bitmap = renderBitmap(config)
            val fileName = "Totom_${config.coupon.id}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Totom")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Medya deposu URI oluşturulamadı")
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("Görsel yazılamadı")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Görsel galeriye kaydedildi (Pictures/Totom)"
            } else {
                val picDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(context.cacheDir, "shared_coupons")
                picDir.mkdirs()
                val outFile = File(picDir, fileName)
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                "Görsel kaydedildi: ${outFile.name}"
            }
        }
    }
}
