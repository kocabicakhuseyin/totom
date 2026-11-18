package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.engine.NesineAutoPlayJsInjector
import com.example.engine.PlatformAutomationManager
import com.example.model.BettingPlatform
import com.example.model.Coupon
import com.example.model.PlatformCredentials
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

@Composable
fun NesineWebBrowserAutomationDialog(
    coupon: Coupon,
    credentials: PlatformCredentials,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        val density = LocalDensity.current
        var safeInsets by remember { mutableStateOf(PaddingValues(0.dp)) }

        DisposableEffect(view) {
            val dialogWindow = (view as? DialogWindowProvider)?.window
                ?: (view.parent as? DialogWindowProvider)?.window
            dialogWindow?.let { win ->
                win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                WindowCompat.setDecorFitsSystemWindows(win, false)
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    win.attributes = win.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }

            val listener = OnApplyWindowInsetsListener { _, windowInsets ->
                val bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                safeInsets = with(density) {
                    PaddingValues(
                        start = bars.left.toDp(),
                        top = bars.top.toDp(),
                        end = bars.right.toDp(),
                        bottom = bars.bottom.toDp()
                    )
                }
                windowInsets
            }
            ViewCompat.setOnApplyWindowInsetsListener(view, listener)
            ViewCompat.requestApplyInsets(view)
            onDispose {
                ViewCompat.setOnApplyWindowInsetsListener(view, null)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(safeInsets),
            color = DarkBackground
        ) {
            NesineWebBrowserAutomationContent(
                coupon = coupon,
                credentials = credentials,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NesineWebBrowserAutomationContent(
    coupon: Coupon,
    credentials: PlatformCredentials,
    onDismiss: () -> Unit,
    onPlatformSelected: ((BettingPlatform) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activePlatform by remember(credentials.platform) { mutableStateOf(credentials.platform) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember(credentials.platform) { mutableStateOf(credentials.platform.webUrl) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var activeColumnIdx by remember { mutableIntStateOf(0) }
    var isFillAllRunning by remember { mutableStateOf(false) }
    var isPlayAllRunning by remember { mutableStateOf(false) }
    var isHudExpanded by remember { mutableStateOf(true) }
    var webViewRecreationKey by remember { mutableIntStateOf(0) }
    var isNesineLoggedIn by remember { mutableStateOf<Boolean?>(null) }
    var automationFeedback by remember(credentials.platform) {
        mutableStateOf(
            if (credentials.platform == BettingPlatform.NESINE)
                "Nesine WebView hazır. Oturum açtıktan sonra 'Tümünü Doldur' veya 'Tümünü Oyna' kullanın."
            else
                "${credentials.platform.displayName} WebView açıldı. Kupon kolonlarını kopyalayıp oynayabilirsiniz."
        )
    }

    val columns = coupon.columns
    val currentColumn = columns.getOrNull(activeColumnIdx)

    val checkSessionNow: (WebView?) -> Unit = { view ->
        view?.evaluateJavascript(NesineAutoPlayJsInjector.buildSessionCheckScript()) { raw ->
            val cleaned = raw?.trim('"')?.replace("\\\"", "\"")
            val json = runCatching { JSONObject(cleaned ?: "{}") }.getOrNull()
            if (json != null) {
                val loggedIn = json.optBoolean("loggedIn", false)
                val needs2fa = json.optBoolean("requires2fa", false)
                isNesineLoggedIn = loggedIn
                if (needs2fa) {
                    automationFeedback = "📲 Nesine SMS doğrulama (2FA) kodu bekleniyor."
                }
            }
        }
    }

    val triggerNesineLogin: () -> Unit = {
        val script = NesineAutoPlayJsInjector.buildLoginFillScript(
            credentials.username,
            credentials.password,
            autoSubmit = true
        )
        webViewRef?.evaluateJavascript(script) { raw ->
            val cleaned = raw?.trim('"')?.replace("\\\"", "\"")
            val json = runCatching { JSONObject(cleaned ?: "{}") }.getOrNull()
            val reason = json?.optString("reason")
            val openedModal = json?.optBoolean("openedModal", false) == true
            automationFeedback = reason?.ifBlank { null }
                ?: "Giriş formu doldurma komutu gönderildi."
            if (openedModal && credentials.username.isNotBlank() && credentials.password.isNotBlank()) {
                scope.launch {
                    delay(850L)
                    val fillJs = NesineAutoPlayJsInjector.buildLoginFillScript(
                        credentials.username,
                        credentials.password,
                        autoSubmit = true
                    )
                    webViewRef?.evaluateJavascript(fillJs, null)
                }
            }
            scope.launch {
                delay(1600L)
                checkSessionNow(webViewRef)
            }
        }
    }

    val fitPageScript = remember {
        """
        (function() {
          try {
            var docEl = document.documentElement;
            var sw = docEl ? docEl.scrollWidth : 0;
            var iw = window.innerWidth || (docEl ? docEl.clientWidth : 360) || 360;
            if (sw > iw + 16 && iw > 0) {
              var scale = Math.max(0.30, Math.min(1.0, (iw / sw))).toFixed(2);
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                if (document.head) document.head.appendChild(meta);
              }
              if (meta) {
                meta.setAttribute(
                  'content',
                  'width=' + sw + ', initial-scale=' + scale + ', minimum-scale=0.25, maximum-scale=3.0, user-scalable=yes'
                );
              }
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }

    val evaluateJsJson: suspend (WebView?, String) -> JSONObject? = { view, script ->
        if (view == null) {
            null
        } else {
            withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine { cont ->
                    view.evaluateJavascript(script) { raw ->
                        val cleaned = raw?.trim('"')?.replace("\\\"", "\"")
                        val parsed = runCatching { JSONObject(cleaned ?: "{}") }.getOrNull()
                        if (cont.isActive) cont.resume(parsed)
                    }
                }
            }
        }
    }

    BackHandler {
        isFillAllRunning = false
        isPlayAllRunning = false
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
            com.example.network.HeadlessWebViewSupport.destroySafely(webViewRef, rendererAlreadyGone = false)
            webViewRef = null
        }
    }

    // Periodic session detection so login badge updates right after user completes 2FA/login
    LaunchedEffect(webViewRef) {
        while (true) {
            delay(2500L)
            checkSessionNow(webViewRef)
        }
    }

    val ensureBulletinReady: suspend (WebView, Boolean) -> Boolean = { view, requireLoggedIn ->
        var ready = false
        var abortedByState = false
        for (attempt in 1..10) {
            if ((requireLoggedIn && !isPlayAllRunning) || (!requireLoggedIn && !isFillAllRunning && !isPlayAllRunning)) {
                abortedByState = true
                break
            }
            val sessionJson = evaluateJsJson(view, NesineAutoPlayJsInjector.buildSessionCheckScript())
            val loggedIn = sessionJson?.optBoolean("loggedIn", false) == true
            val needs2fa = sessionJson?.optBoolean("requires2fa", false) == true
            val hasBulletin = sessionJson?.optBoolean("hasBulletin", false) == true
            val insufficientBalance = sessionJson?.optBoolean("insufficientBalance", false) == true
            isNesineLoggedIn = loggedIn

            if (insufficientBalance) {
                automationFeedback = "❌ Nesine hesabınızda bakiye yetersiz görünüyor."
                abortedByState = true
                break
            }
            if (needs2fa) {
                automationFeedback = "📲 Nesine SMS doğrulama (2FA) kodu bekleniyor. Kodu girip tekrar deneyin."
                abortedByState = true
                break
            }
            if (requireLoggedIn && !loggedIn) {
                automationFeedback = "⚠️ Nesine oturumu kapalı! Giriş penceresi açılıyor..."
                triggerNesineLogin()
                abortedByState = true
                break
            }
            if (hasBulletin) {
                ready = true
                break
            }

            automationFeedback = "🔄 Nesine 15 maçlık bülten ekranına geçiliyor... ($attempt/10)"
            evaluateJsJson(view, NesineAutoPlayJsInjector.buildReturnToBulletinScript())
            if (attempt == 5) {
                view.loadUrl(BettingPlatform.NESINE.webUrl)
            }
            delay(800L)
        }
        if (!ready && !abortedByState) {
            automationFeedback = "❌ Nesine'de aktif 15 maçlık Spor Toto bülteni bulunamadı."
        }
        ready
    }

    // Full Play-All automation loop: verifies session, returns to bulletin if needed, fills 4-column batches, clicks Hemen Oyna & Onayla
    LaunchedEffect(isPlayAllRunning) {
        if (!isPlayAllRunning) return@LaunchedEffect
        val view = webViewRef
        if (view == null || columns.isEmpty()) {
            automationFeedback = "❌ Tarayıcı veya kupon hazır değil."
            isPlayAllRunning = false
            return@LaunchedEffect
        }

        if (!ensureBulletinReady(view, true)) {
            isPlayAllRunning = false
            return@LaunchedEffect
        }

        val totalBatches = (columns.size + 3) / 4
        var batchIdx = 0
        var stoppedWithError = false

        var start = 0
        while (start < columns.size && isPlayAllRunning) {
            batchIdx++
            val end = minOf(columns.size, start + 4)

            if (batchIdx > 1 && !ensureBulletinReady(view, true)) {
                stoppedWithError = true
                break
            }

            automationFeedback = "🧹 Grup $batchIdx/$totalBatches temizleniyor..."
            evaluateJsJson(view, NesineAutoPlayJsInjector.buildClearGridScript())
            delay(300L)
            if (!isPlayAllRunning) break

            for (idx in start until end) {
                if (!isPlayAllRunning) break
                activeColumnIdx = idx
                val col = columns[idx]
                val slot = idx - start
                automationFeedback = "🎯 Tümünü Oyna ($batchIdx/$totalBatches): Kolon #${col.index} (${idx + 1}/${columns.size}) işaretleniyor..."
                val fillRes = evaluateJsJson(
                    view,
                    NesineAutoPlayJsInjector.buildColumnFillScript(col, slotIndex = slot)
                )
                val ok = fillRes?.optBoolean("success", false) == true
                if (!ok) {
                    val errMsg = fillRes?.optString("message").orEmpty().ifBlank { "Kolon #${col.index} işaretlenemedi." }
                    automationFeedback = "❌ $errMsg"
                    stoppedWithError = true
                    break
                }
                delay(420L)
            }
            if (stoppedWithError || !isPlayAllRunning) break

            delay(400L)
            automationFeedback = "🚀 Grup $batchIdx/$totalBatches için 'Hemen Oyna' tetikleniyor..."
            var playRes = evaluateJsJson(view, NesineAutoPlayJsInjector.buildPlayAndConfirmScript())
            var status = playRes?.optString("status").orEmpty()
            var msg = playRes?.optString("message").orEmpty()

            if (status == "PLAY_CLICKED" || status == "NO_PLAY_BUTTON") {
                for (attempt in 1..6) {
                    if (!isPlayAllRunning) break
                    automationFeedback = "⏳ Grup $batchIdx/$totalBatches onay ekranı bekleniyor... ($attempt/6)"
                    delay(850L)
                    playRes = evaluateJsJson(view, NesineAutoPlayJsInjector.buildPlayAndConfirmScript())
                    status = playRes?.optString("status").orEmpty()
                    msg = playRes?.optString("message").orEmpty()
                    if (status != "PLAY_CLICKED" && status != "NO_PLAY_BUTTON") break
                }
            }

            when (status) {
                "CONFIRMED" -> {
                    automationFeedback = "✅ Grup $batchIdx/$totalBatches onaylandı! Sonuç kontrol ediliyor..."
                    delay(1200L)
                    var postCheck = evaluateJsJson(view, NesineAutoPlayJsInjector.buildSessionCheckScript())
                    if (postCheck?.optBoolean("hasConfirmView", false) == true &&
                        postCheck.optBoolean("insufficientBalance", false) != true &&
                        postCheck.optBoolean("requires2fa", false) != true
                    ) {
                        evaluateJsJson(view, NesineAutoPlayJsInjector.buildPlayAndConfirmScript())
                        delay(1000L)
                        postCheck = evaluateJsJson(view, NesineAutoPlayJsInjector.buildSessionCheckScript())
                    }
                    when {
                        postCheck?.optBoolean("insufficientBalance", false) == true -> {
                            automationFeedback = "❌ Nesine hesabınızda bakiye yetersiz görünüyor."
                            stoppedWithError = true
                        }
                        postCheck?.optBoolean("requires2fa", false) == true -> {
                            automationFeedback = "📲 Nesine SMS doğrulama (2FA) kodu bekleniyor."
                            stoppedWithError = true
                        }
                        else -> {
                            automationFeedback = "✅ Grup $batchIdx/$totalBatches onaylandı! ($msg)"
                        }
                    }
                }
                "NOT_LOGGED_IN" -> {
                    isNesineLoggedIn = false
                    automationFeedback = "⚠️ ${msg.ifBlank { "Oturum kapandı! Tekrar giriş yapın." }}"
                    stoppedWithError = true
                }
                "REQUIRES_2FA" -> {
                    automationFeedback = "📲 ${msg.ifBlank { "SMS doğrulama (2FA) kodu gerekli." }}"
                    stoppedWithError = true
                }
                "INSUFFICIENT_BALANCE" -> {
                    automationFeedback = "❌ ${msg.ifBlank { "Nesine hesabınızda bakiye yetersiz." }}"
                    stoppedWithError = true
                }
                else -> {
                    if (end < columns.size) {
                        automationFeedback = "⚠️ Grup $batchIdx/$totalBatches için oynama istendi ancak onay butonu doğrulanamadı — ekrandan kontrol edip devam edin."
                        stoppedWithError = true
                    } else {
                        automationFeedback = "🚀 ${msg.ifBlank { "Son grup için oynama talebi gönderildi — Nesine ekranını kontrol edin." }}"
                    }
                }
            }
            if (stoppedWithError) break
            start += 4
        }

        if (!stoppedWithError && isPlayAllRunning) {
            runCatching { CookieManager.getInstance().flush() }
            checkSessionNow(view)
            automationFeedback = "✅ Tümünü Oyna tamamlandı: ${columns.size} kolon ($totalBatches grup) işlendi!"
        }
        isPlayAllRunning = false
    }

    // Full Fill-All automation loop: fills all columns (e.g. 48 columns) automatically across extended DOM slots, cart buttons, or post-confirm slip resets
    LaunchedEffect(isFillAllRunning) {
        if (!isFillAllRunning) return@LaunchedEffect
        val view = webViewRef
        if (view == null || columns.isEmpty()) {
            automationFeedback = "❌ Tarayıcı veya kupon hazır değil."
            isFillAllRunning = false
            return@LaunchedEffect
        }

        if (!ensureBulletinReady(view, false)) {
            isFillAllRunning = false
            return@LaunchedEffect
        }

        val totalBatches = (columns.size + 3) / 4
        var batchIdx = 0
        var start = 0
        var stoppedWithError = false
        var usedExtendedSlots = false

        while (start < columns.size && isFillAllRunning) {
            batchIdx++
            val end = minOf(columns.size, start + 4)

            if (!usedExtendedSlots) {
                automationFeedback = "🧹 Tümünü Doldur ($batchIdx/$totalBatches): Sütunlar hazırlanıyor..."
                evaluateJsJson(view, NesineAutoPlayJsInjector.buildClearGridScript())
                delay(280L)
                if (!isFillAllRunning) break
            }

            for (idx in start until end) {
                if (!isFillAllRunning) break
                activeColumnIdx = idx
                val col = columns[idx]
                automationFeedback = "✍️ Tümünü Doldur ($batchIdx/$totalBatches): Kolon #${col.index} (${idx + 1}/${columns.size}) dolduruluyor..."
                val fillRes = evaluateJsJson(
                    view,
                    NesineAutoPlayJsInjector.buildColumnFillAutoSlotScript(col, rawIndex = idx)
                )
                val ok = fillRes?.optBoolean("success", false) == true
                if (fillRes?.optBoolean("usedExtendedSlot", false) == true) {
                    usedExtendedSlots = true
                }
                if (!ok) {
                    val errMsg = fillRes?.optString("message").orEmpty().ifBlank { "Kolon #${col.index} işaretlenemedi." }
                    automationFeedback = "❌ $errMsg"
                    stoppedWithError = true
                    break
                }
                delay(320L)
            }
            if (stoppedWithError || !isFillAllRunning) break

            if (end >= columns.size) {
                break
            }

            if (usedExtendedSlots) {
                start += 4
                continue
            }

            // Standard 4-column A-D slip: advance via Cart/Add-Slip if available, or watch for slip reset after Onayla
            val advanceRes = evaluateJsJson(
                view,
                NesineAutoPlayJsInjector.buildSlipAdvanceOrStateScript(clickCartOrNewSlip = true)
            )
            if (advanceRes?.optBoolean("advancedToCart", false) == true) {
                automationFeedback = "✅ Grup $batchIdx/$totalBatches eklendi, sonraki 4 kolon dolduruluyor..."
                delay(850L)
                start += 4
                continue
            }

            val nextEnd = minOf(columns.size, end + 4)
            automationFeedback = "✅ Kolon ${start + 1}–$end/${columns.size} (A–D) doldu! 'Onayla' yapınca Kolon ${end + 1}–$nextEnd otomatik dolacak."
            while (isFillAllRunning) {
                delay(750L)
                val watchRes = evaluateJsJson(
                    view,
                    NesineAutoPlayJsInjector.buildSlipAdvanceOrStateScript(clickCartOrNewSlip = false)
                )
                val checkedCount = watchRes?.optInt("checkedCount", 60) ?: 60
                val hasSuccess = watchRes?.optBoolean("hasSuccessNotice", false) == true
                if ((checkedCount in 0..9) || hasSuccess) {
                    automationFeedback = "⏳ Kupon onaylandı/temizlendi! Sonraki 4 kolon (${end + 1}–$nextEnd) dolduruluyor..."
                    delay(650L)
                    break
                }
            }
            start += 4
        }

        if (!stoppedWithError && isFillAllRunning) {
            activeColumnIdx = columns.lastIndex
            automationFeedback = "✅ Tüm kolonlar (${columns.size}/${columns.size}) otomatik dolduruldu!"
        }
        isFillAllRunning = false
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                // 1. Top Browser Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkCard)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = {
                                isFillAllRunning = false
                                isPlayAllRunning = false
                                onDismiss()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_close_nesine_browser")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Kapat", tint = TextPrimary)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (activePlatform == BettingPlatform.NESINE) "Nesine Robotu" else "${activePlatform.shortName} WebView",
                                    color = CasinoGold,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sessionBadgeText = when (isNesineLoggedIn) {
                                    true -> "Oturum Açık"
                                    false -> "Giriş Yok"
                                    null -> "Kontrol..."
                                }
                                val sessionBadgeColor = when (isNesineLoggedIn) {
                                    true -> ElectricGreen
                                    false -> NeonAmber
                                    null -> TextMuted
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(sessionBadgeColor.copy(alpha = 0.18f))
                                        .border(0.8.dp, sessionBadgeColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = sessionBadgeText,
                                        color = sessionBadgeColor,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = pageTitle,
                                color = TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                webViewRef?.evaluateJavascript(fitPageScript, null)
                                webViewRef?.zoomOut()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_fit_nesine_browser")
                        ) {
                            Icon(
                                Icons.Default.FitScreen,
                                contentDescription = "Sayfayı Ekrana Sığdır",
                                tint = ElectricGreen
                            )
                        }

                        IconButton(
                            onClick = {
                                if (webViewRef == null) {
                                    webViewRecreationKey++
                                } else {
                                    webViewRef?.reload()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_reload_nesine_browser")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = ElectricCyan)
                        }

                        IconButton(
                            onClick = { isHudExpanded = !isHudExpanded },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_toggle_webview_hud")
                        ) {
                            Icon(
                                imageVector = if (isHudExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (isHudExpanded) "Alt paneli gizle" else "Alt paneli göster",
                                tint = TextSecondary
                            )
                        }
                    }
                }

                // Quick Platform Switcher Bar inside WebView
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BettingPlatform.values().forEach { platform ->
                        val selected = activePlatform == platform
                        FilterChip(
                            selected = selected,
                            onClick = {
                                isFillAllRunning = false
                                isPlayAllRunning = false
                                activePlatform = platform
                                pageTitle = platform.webUrl
                                onPlatformSelected?.invoke(platform)
                                webViewRef?.loadUrl(platform.webUrl)
                                automationFeedback = if (platform == BettingPlatform.NESINE) {
                                    "Nesine açıldı • 'Tümünü Doldur' / 'Tümünü Oyna' hazır."
                                } else {
                                    "${platform.displayName} açıldı • Kolonları kopyalayıp yatırabilirsiniz."
                                }
                            },
                            label = {
                                Text(
                                    text = "${platform.iconEmoji} ${platform.shortName}",
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = DarkCard,
                                labelColor = TextSecondary,
                                selectedContainerColor = Color(platform.brandColorHex),
                                selectedLabelColor = Color(platform.textColorHex)
                            ),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("webview_platform_${platform.id}")
                        )
                    }
                }

                // 2. Always-Visible Top Primary Coupon Action Bar (never hidden by nav bar or keyboard!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkCard)
                        .border(1.dp, DarkCardBorder)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activePlatform == BettingPlatform.NESINE) {
                        Button(
                            onClick = { isFillAllRunning = !isFillAllRunning },
                            enabled = !isPlayAllRunning && columns.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFillAllRunning) Color(0xFFDC2626) else CasinoGold
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_webview_fill_all")
                        ) {
                            Icon(
                                imageVector = if (isFillAllRunning) Icons.Default.Close else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (isFillAllRunning) Color.White else Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isFillAllRunning) {
                                    "Durdur (Doldur)"
                                } else {
                                    "Tümünü Doldur (${columns.size})"
                                },
                                color = if (isFillAllRunning) Color.White else Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { isPlayAllRunning = !isPlayAllRunning },
                            enabled = !isFillAllRunning && columns.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlayAllRunning) Color(0xFFDC2626) else ElectricGreen
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_webview_play_all")
                        ) {
                            Icon(
                                imageVector = if (isPlayAllRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isPlayAllRunning) Color.White else Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPlayAllRunning) {
                                    "Durdur (Oyna)"
                                } else {
                                    "Tümünü Oyna (${columns.size})"
                                },
                                color = if (isPlayAllRunning) Color.White else Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                val formatted = PlatformAutomationManager.formatCouponForPlatform(
                                    coupon,
                                    activePlatform,
                                    credentials
                                )
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("${activePlatform.shortName} Spor Toto", formatted))
                                automationFeedback = "✅ ${columns.size} kolon ${activePlatform.shortName} için panoya kopyalandı."
                                Toast.makeText(context, "${activePlatform.shortName} kuponu kopyalandı", Toast.LENGTH_SHORT).show()
                            },
                            enabled = columns.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_webview_copy_platform_coupon")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Kolonları Kopyala (${columns.size})",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = { webViewRef?.loadUrl(activePlatform.webUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(activePlatform.brandColorHex)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("btn_webview_open_platform_home")
                        ) {
                            Text(
                                text = "${activePlatform.iconEmoji} ${activePlatform.shortName} Spor Toto",
                                color = Color(activePlatform.textColorHex),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (activePlatform == BettingPlatform.NESINE && isNesineLoggedIn == false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeonAmber.copy(alpha = 0.14f))
                            .border(0.8.dp, NeonAmber.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Nesine oturumu kapalı. Oynamak için giriş yapın:",
                            color = NeonAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = triggerNesineLogin,
                            enabled = !isFillAllRunning && !isPlayAllRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("btn_webview_banner_login")
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Oturum Aç",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = CasinoGold,
                        trackColor = DarkSurface
                    )
                }

                // 3. Embedded Real WebView loading nesine.com/spor-toto
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    androidx.compose.runtime.key(webViewRecreationKey) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                                    setInitialScale(0)
                                    isHorizontalScrollBarEnabled = true
                                    isVerticalScrollBarEnabled = true
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                            isLoading = newProgress < 100
                                        }

                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            if (!title.isNullOrBlank()) pageTitle = title
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            isLoading = true
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                            runCatching { CookieManager.getInstance().flush() }
                                            view?.evaluateJavascript(NesineAutoPlayJsInjector.buildCookieBannerDismissScript(), null)
                                            view?.evaluateJavascript(fitPageScript, null)
                                            view?.requestLayout()
                                            if (activePlatform == BettingPlatform.NESINE) {
                                                checkSessionNow(view)
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: RenderProcessGoneDetail?
                                        ): Boolean {
                                            isLoading = false
                                            isFillAllRunning = false
                                            isPlayAllRunning = false
                                            if (webViewRef === view) {
                                                webViewRef = null
                                            }
                                            (view?.parent as? ViewGroup)?.removeView(view)
                                            runCatching { view?.destroy() }
                                            automationFeedback = "Tarayıcı motoru yenileniyor..."
                                            webViewRecreationKey++
                                            return true
                                        }
                                    }
                                    loadUrl(activePlatform.webUrl)
                                    webViewRef = this
                                }
                            }
                        )
                    }
                }

                // 4. Collapsible Bottom Column Selector, Login Fill & Status Bar
                AnimatedVisibility(visible = isHudExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkCard)
                            .border(1.dp, DarkCardBorder)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBackground)
                                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = automationFeedback,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (currentColumn != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = { if (activeColumnIdx > 0) activeColumnIdx-- },
                                    enabled = activeColumnIdx > 0 && !isFillAllRunning && !isPlayAllRunning,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Önceki Kolon",
                                        tint = if (activeColumnIdx > 0) CasinoGold else TextMuted
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "Kolon ${activeColumnIdx + 1} / ${columns.size}",
                                        color = CasinoGold,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = currentColumn.asString,
                                        color = ElectricGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = { if (activeColumnIdx < columns.lastIndex) activeColumnIdx++ },
                                    enabled = activeColumnIdx < columns.lastIndex && !isFillAllRunning && !isPlayAllRunning,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Sonraki Kolon",
                                        tint = if (activeColumnIdx < columns.lastIndex) CasinoGold else TextMuted
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val text = PlatformAutomationManager.formatCouponForPlatform(
                                            coupon,
                                            activePlatform,
                                            credentials
                                        )
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("${activePlatform.shortName} Spor Toto", text))
                                        Toast.makeText(context, "Kupon panoya kopyalandı", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("btn_copy_nesine_coupon")
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Kuponu Kopyala",
                                        tint = CasinoGold
                                    )
                                }

                                if (activePlatform == BettingPlatform.NESINE) {
                                    OutlinedButton(
                                        onClick = triggerNesineLogin,
                                        enabled = !isFillAllRunning && !isPlayAllRunning,
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .testTag("btn_webview_fill_login")
                                    ) {
                                        Icon(
                                            Icons.Default.Key,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Giriş",
                                            color = ElectricCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
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
