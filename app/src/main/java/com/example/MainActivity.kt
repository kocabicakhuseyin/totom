package com.example

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.Coupon
import com.example.model.FormulaType
import com.example.model.NesineReceiptParser
import com.example.model.PlatformCredentials
import com.example.ui.components.AppNavTab
import com.example.ui.components.ModernBottomNavBar
import com.example.ui.components.TopHeaderBar
import com.example.ui.screens.CouponsScreen
import com.example.ui.screens.CouponPoolScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ExcelImportDialog
import com.example.ui.screens.FormulaWizardDialog
import com.example.ui.screens.ForumScreen
import com.example.ui.screens.GooglePlayBillingDialog
import com.example.ui.screens.MatchAnalysisScreen
import com.example.ui.screens.MatchResultsScreen
import com.example.ui.screens.NesineWebBrowserAutomationContent
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.TotoFilterDialog
import com.example.ui.screens.TxtImportDialog
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.TotoViewModel

import androidx.compose.ui.platform.LocalContext
import com.example.data.local.AppDatabase
import com.example.data.repository.WalletRepository
import com.example.repository.LiveScoreSource
import com.example.repository.PoolRepository
import com.example.repository.SporTotoBulletinRepositoryImpl
import com.example.viewmodel.BillingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.model.SupportedTeam
import com.example.ui.screens.SupabaseAuthDialog

class MainActivity : ComponentActivity() {
    private var incomingNesineUrl by mutableStateOf<String?>(null)
    private var incomingAuthCallbackUri by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingNesineUrl = extractNesineShareLink(intent)
        incomingAuthCallbackUri = extractAuthCallbackUri(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(15, 23, 42)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(15, 23, 42))
        )
        setContent {
            MyApplicationTheme {
                MainAppContent(
                    incomingNesineUrl = incomingNesineUrl,
                    onNesineUrlHandled = { incomingNesineUrl = null },
                    incomingAuthCallbackUri = incomingAuthCallbackUri,
                    onAuthCallbackHandled = { incomingAuthCallbackUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNesineShareLink(intent)?.let { incomingNesineUrl = it }
        extractAuthCallbackUri(intent)?.let { incomingAuthCallbackUri = it }
    }

    private fun extractNesineShareLink(intent: Intent?): String? {
        val source = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }.orEmpty()
        return Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE)
            .findAll(source)
            .map { it.value.trimEnd('.', ',', ')', ']', ';') }
            .firstOrNull(NesineReceiptParser::isShareUrl)
    }

    private fun extractAuthCallbackUri(intent: Intent?): android.net.Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        return if (uri.scheme.equals("sportoto", ignoreCase = true) &&
            uri.host.equals("auth-callback", ignoreCase = true)
        ) {
            uri
        } else {
            null
        }
    }
}

@Composable
fun MainAppContent(
    incomingNesineUrl: String? = null,
    onNesineUrlHandled: () -> Unit = {},
    incomingAuthCallbackUri: android.net.Uri? = null,
    onAuthCallbackHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val walletRepository = remember {
        WalletRepository(AppDatabase.getInstance(context).walletDao())
    }
    val poolRepository = remember { PoolRepository(context) }
    val memberSession by poolRepository.sessionState.collectAsStateWithLifecycle()
    val saveScope = rememberCoroutineScope()
    val viewModel: TotoViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TotoViewModel(
                    bulletinRepository = SporTotoBulletinRepositoryImpl(
                        applicationContext = context.applicationContext,
                        preferTurkishLeagueBulletin = true,
                        initialSource = LiveScoreSource.ESPN
                    ),
                    walletRepository = walletRepository
                ) as T
            }
        }
    )
    val application = context.applicationContext as android.app.Application
    val billingViewModel: BillingViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BillingViewModel(
                    application = application,
                    walletRepository = walletRepository
                ) as T
            }
        }
    )
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val coupons by viewModel.coupons.collectAsStateWithLifecycle()
    val currentCoupon by viewModel.currentCoupon.collectAsStateWithLifecycle()
    val forumPosts by viewModel.forumPosts.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val isRefreshingBulletin by viewModel.isRefreshingBulletin.collectAsStateWithLifecycle()
    val filterCriteria by viewModel.filterCriteria.collectAsStateWithLifecycle()
    val targetColumnCap by viewModel.targetColumnCap.collectAsStateWithLifecycle()

    val liveScores by viewModel.liveScores.collectAsStateWithLifecycle()
    val currentBulletin by viewModel.currentBulletin.collectAsStateWithLifecycle()
    val liveScoreProvider by viewModel.liveScoreProvider.collectAsStateWithLifecycle()
    val activeLiveScoreSource by viewModel.activeLiveScoreSource.collectAsStateWithLifecycle()
    val hasApiFootballKey by viewModel.hasApiFootballKey.collectAsStateWithLifecycle()
    val freeScoreRequestsRemaining by viewModel.freeScoreRequestsRemaining.collectAsStateWithLifecycle()
    val lastLiveUpdateTime by viewModel.lastLiveUpdateTime.collectAsStateWithLifecycle()
    val isRefreshingLiveScores by viewModel.isRefreshingLiveScores.collectAsStateWithLifecycle()
    val previousWeekReport by viewModel.previousWeekReport.collectAsStateWithLifecycle()
    val isLoadingPreviousWeek by viewModel.isLoadingPreviousWeek.collectAsStateWithLifecycle()
    val previousWeekError by viewModel.previousWeekError.collectAsStateWithLifecycle()

    val favoritedMatchNumbers by viewModel.favoritedMatchNumbers.collectAsStateWithLifecycle()
    val liveScoreError by viewModel.liveScoreError.collectAsStateWithLifecycle()
    val bulletinMessage by viewModel.bulletinMessage.collectAsStateWithLifecycle()
    val bulletinError by viewModel.bulletinError.collectAsStateWithLifecycle()
    val hasPlayableBulletin by viewModel.hasPlayableBulletin.collectAsStateWithLifecycle()

    LaunchedEffect(bulletinMessage) {
        bulletinMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearBulletinMessage()
        }
    }

    val saveCouponToDevice: (Coupon) -> Unit = { coupon ->
        if (coupon.totalColumns == 0) {
            Toast.makeText(context, "Kaydedilecek kolon bulunmuyor.", Toast.LENGTH_SHORT).show()
        } else {
            saveScope.launch {
                val result = withContext(Dispatchers.IO) {
                    viewModel.saveCouponToJson(context, coupon)
                }
                result.onSuccess { file ->
                    Toast.makeText(
                        context,
                        "Kupon kaydedildi: ${file.name} • Kayıtlı JSON Aç bölümünden erişebilirsiniz.",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        "Kupon kaydedilemedi: ${error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val liveMatchesCount = remember(liveScores) { liveScores.count { it.isLive } }

    var currentTab by remember { mutableStateOf(AppNavTab.HOME) }
    var showPool by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    LaunchedEffect(incomingNesineUrl) {
        if (incomingNesineUrl != null) showPool = true
    }
    LaunchedEffect(incomingAuthCallbackUri) {
        val uri = incomingAuthCallbackUri ?: return@LaunchedEffect
        onAuthCallbackHandled()
        saveScope.launch {
            poolRepository.handleOAuthCallbackUri(uri)
                .onSuccess { session ->
                    if (session.displayName.isNotBlank()) {
                        viewModel.updateUsername(session.displayName)
                    }
                    SupportedTeam.values().firstOrNull { it.name == session.favoriteTeamId }?.let { team ->
                        viewModel.updateFavoriteTeam(team)
                    }
                    showAuthDialog = false
                    Toast.makeText(
                        context,
                        "✅ ${session.providerDisplayLabel} ile giriş başarılı (${session.displayName.ifBlank { session.email }})",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "Giriş işlemi tamamlanamadı.",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
    LaunchedEffect(memberSession.isSignedIn, memberSession.displayName, memberSession.favoriteTeamId) {
        if (memberSession.isSignedIn) {
            if (memberSession.displayName.isNotBlank()) {
                viewModel.updateUsername(memberSession.displayName)
            }
            SupportedTeam.values().firstOrNull { it.name == memberSession.favoriteTeamId }?.let { team ->
                viewModel.updateFavoriteTeam(team)
            }
        }
    }
    LaunchedEffect(currentTab, showPool, activeLiveScoreSource) {
        val showLiveScores = currentTab == AppNavTab.RESULTS ||
            showPool ||
            (activeLiveScoreSource == LiveScoreSource.ESPN &&
                currentTab in listOf(AppNavTab.HOME, AppNavTab.ANALYSIS, AppNavTab.COUPONS))
        viewModel.setLivePolling(showLiveScores)
        if (showLiveScores) {
            viewModel.pollLiveScoresNow()
            if (currentTab == AppNavTab.RESULTS && previousWeekReport == null) {
                viewModel.loadPreviousWeekWithEspn(forceRefresh = false)
            }
        }
    }
    var showTxtDialog by remember { mutableStateOf(false) }
    var showExcelDialog by remember { mutableStateOf(false) }
    var showFormulaWizard by remember { mutableStateOf(false) }
    var showGooglePlayDialog by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var webViewAutomationTarget by remember { mutableStateOf<Pair<Coupon, PlatformCredentials>?>(null) }

    // Back button returns to Home tab if inside a subscreen
    BackHandler(enabled = showPool || currentTab != AppNavTab.HOME) {
        if (showPool) showPool = false else currentTab = AppNavTab.HOME
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopHeaderBar(
                userProfile = userProfile,
                onProfileClick = { currentTab = AppNavTab.PROFILE },
                onOpenWallet = { showGooglePlayDialog = true },
                onLiveClick = { currentTab = AppNavTab.RESULTS },
                liveMatchesCount = liveMatchesCount,
                memberSession = memberSession,
                onOpenAuth = { showAuthDialog = true }
            )
        },
        bottomBar = {
            ModernBottomNavBar(
                currentTab = currentTab,
                couponsCount = coupons.size,
                liveMatchesCount = liveMatchesCount,
                onTabSelected = { currentTab = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showPool) {
                CouponPoolScreen(
                    weekKey = currentBulletin?.let { "${it.season}-${it.weekNumber}" }.orEmpty(),
                    matches = matches,
                    coupons = coupons,
                    liveScores = liveScores,
                    repository = poolRepository,
                    incomingNesineUrl = incomingNesineUrl,
                    displayUsername = userProfile.username,
                    onNesineUrlHandled = onNesineUrlHandled,
                    onBack = { showPool = false }
                )
            } else when (currentTab) {
                AppNavTab.HOME -> {
                    DashboardScreen(
                        userProfile = userProfile,
                        onFormulaSelected = { formula ->
                            if (viewModel.ensurePlayableBulletin()) {
                                viewModel.selectFormula(formula)
                                viewModel.generateCoupon(formula)
                                currentTab = AppNavTab.COUPONS
                            }
                        },
                        onNavigateTab = { tab -> currentTab = tab },
                        onOpenTxtImportDialog = { showTxtDialog = true },
                        onOpenExcelImportDialog = { showExcelDialog = true },
                        onOpenWizardDialog = {
                            if (viewModel.ensurePlayableBulletin()) {
                                showFormulaWizard = true
                            }
                        },
                        onOpenWallet = { showGooglePlayDialog = true },
                        jackpotEstimateTL = currentBulletin?.jackpotEstimateTL ?: 0.0,
                        weekNumber = currentBulletin?.weekNumber
                    )
                }

                AppNavTab.ANALYSIS -> {
                    MatchAnalysisScreen(
                        matches = matches,
                        liveScores = liveScores,
                        onTogglePrediction = { matchId, outcome ->
                            viewModel.togglePrediction(matchId, outcome)
                        },
                        onSelectAllFavorites = { viewModel.selectAllFavorites() },
                        onApplyQuickDoubles = { viewModel.applyQuickDoubles() },
                        onApplyQuickDoubles10To15 = { viewModel.applyQuickDoubles10To15() },
                        onApplyQuickDoubles1To9 = { viewModel.applyQuickDoubles1To9() },
                        onClearAll = { viewModel.clearAllSelections() },
                        onGenerateCoupon = {
                            if (viewModel.ensurePlayableBulletin()) {
                                viewModel.generateCoupon(FormulaType.GUARANTEE_14)
                                currentTab = AppNavTab.COUPONS
                            }
                        },
                        onOpenFormulaWizard = {
                            if (viewModel.ensurePlayableBulletin()) {
                                showFormulaWizard = true
                            }
                        },
                        onOpenFilterDialog = {
                            if (viewModel.ensurePlayableBulletin()) {
                                showFilterDialog = true
                            }
                        },
                        targetColumnCap = targetColumnCap,
                        onSelectTargetColumnCap = { viewModel.setTargetColumnCap(it) },
                        onApplyFilterProfile = { profile ->
                            if (viewModel.ensurePlayableBulletin()) {
                                viewModel.applyFilterProfile(profile)
                                currentTab = AppNavTab.COUPONS
                            }
                        },
                        isRefreshing = isRefreshingBulletin,
                        onRefreshBulletin = { viewModel.refreshBulletin(forceRefresh = true) },
                        onSyncTurkishLeagueBulletin = { viewModel.syncTurkishLeagueBulletinFromApi() },
                        previousWeekReport = previousWeekReport,
                        isLoadingPreviousWeek = isLoadingPreviousWeek,
                        previousWeekError = previousWeekError,
                        hasApiKey = hasApiFootballKey,
                        requiresApiKey = false,
                        providerLabel = "ESPN",
                        onLoadPreviousWeek = { force -> viewModel.loadPreviousWeekWithEspn(force) },
                        onNavigateToLiveTab = { currentTab = AppNavTab.RESULTS },
                        favoritedMatchNumbers = favoritedMatchNumbers,
                        onToggleFavorite = { viewModel.toggleFavoriteMatch(it) },
                        hasPlayableBulletin = hasPlayableBulletin,
                        bulletinError = bulletinError
                    )
                }

                AppNavTab.RESULTS -> {
                    MatchResultsScreen(
                        liveScores = liveScores,
                        providerName = liveScoreProvider,
                        activeSource = activeLiveScoreSource,
                        onSelectSource = { viewModel.selectLiveScoreSource(it) },
                        hasApiKey = hasApiFootballKey,
                        freeRequestsRemaining = freeScoreRequestsRemaining,
                        onSaveApiKey = { viewModel.saveApiFootballKey(it) },
                        lastUpdateTime = lastLiveUpdateTime,
                        isRefreshing = isRefreshingLiveScores,
                        errorMessage = liveScoreError,
                        onPollNow = { viewModel.pollLiveScoresNow() },
                        matches = matches,
                        coupons = coupons,
                        onOpenCoupon = { coupon ->
                            viewModel.setCurrentCoupon(coupon)
                            currentTab = AppNavTab.COUPONS
                        },
                        isSyncingBulletin = isRefreshingBulletin,
                        onSyncTurkishLeagueBulletin = { viewModel.syncTurkishLeagueBulletinFromApi() },
                        previousWeekReport = previousWeekReport,
                        isLoadingPreviousWeek = isLoadingPreviousWeek,
                        previousWeekError = previousWeekError,
                        onLoadPreviousWeek = { force -> viewModel.loadPreviousWeekWithEspn(force) }
                    )
                }

                AppNavTab.COUPONS -> {
                    CouponsScreen(
                        coupons = coupons,
                        currentCoupon = currentCoupon,
                        matches = matches,
                        liveScores = liveScores,
                        savedCredentials = userProfile.nesineCredentials,
                        walletBalanceTL = userProfile.walletBalanceTL,
                        onSelectCoupon = { viewModel.setCurrentCoupon(it) },
                        onDeleteCoupon = { viewModel.deleteCoupon(it) },
                        onSaveCredentials = { viewModel.saveNesineCredentials(it) },
                        onNavigateToAnalysis = { currentTab = AppNavTab.ANALYSIS },
                        onOpenWallet = { showGooglePlayDialog = true },
                        onDeductFeeAndPlay = { coupon -> viewModel.deductColumnFee(coupon) },
                        onShareToForum = { coupon ->
                            viewModel.addForumPost("Garantili ${coupon.formulaType.badge} kuponumu inceleyebilirsiniz (${coupon.totalColumns} kolon)!", coupon.id)
                        },
                        onOpenWizard = { showFormulaWizard = true },
                        onOpenFilterDialog = { showFilterDialog = true },
                        onSaveCouponToJson = saveCouponToDevice,
                        onLoadSavedJsonCoupons = {
                            viewModel.listSavedJsonCoupons(context)
                        },
                        onLoadCouponFromFile = { file ->
                            val res = viewModel.loadCouponFromJsonFile(file)
                            res.onSuccess { loaded ->
                                Toast.makeText(context, "'${loaded.title}' başarıyla açıldı!", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, "Dosya okuma hatası: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteSavedJsonFile = { file ->
                            val deleted = viewModel.deleteSavedJsonCoupon(file)
                            if (deleted) {
                                Toast.makeText(context, "Kayıtlı dosya silindi.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onNavigateToLiveResults = { currentTab = AppNavTab.RESULTS },
                        onOpenPool = { showPool = true },
                        hasPlayableBulletin = hasPlayableBulletin,
                        bulletinError = bulletinError,
                        onRefreshBulletin = { viewModel.refreshBulletin(forceRefresh = true) },
                        onOpenInAppBrowser = { targetCoupon, targetCreds ->
                            webViewAutomationTarget = targetCoupon to targetCreds
                        }
                    )
                }

                AppNavTab.FORUM -> {
                    ForumScreen(
                        forumPosts = forumPosts,
                        userProfile = userProfile,
                        matches = matches,
                        liveScores = liveScores,
                        bulletinWeekLabel = currentBulletin?.let { "${it.bulletinTitle} (${it.season})" },
                        currentCoupon = currentCoupon,
                        coupons = coupons,
                        memberSession = memberSession,
                        hasPlayableBulletin = hasPlayableBulletin,
                        onRefreshBulletin = { viewModel.refreshBulletin(forceRefresh = true) },
                        onOpenCouponInCoupons = { selectedCoupon ->
                            viewModel.openSharedForumCoupon(selectedCoupon)
                            currentTab = AppNavTab.COUPONS
                        },
                        onAddBulletinPost = { request ->
                            val memberName = memberSession.displayName.takeIf { memberSession.isSignedIn && it.isNotBlank() }
                            val providerBadge = if (memberSession.isSignedIn) memberSession.providerDisplayLabel else null
                            viewModel.addForumPost(
                                content = request.content,
                                attachedCouponId = request.attachedCouponId,
                                matchRef = request.matchReference,
                                matchNumber = request.matchNumber,
                                predictedOutcome = request.predictedOutcome,
                                replyToPostId = request.replyToPostId,
                                replyToAuthor = request.replyToAuthor,
                                attachedCouponSummary = request.attachedCouponSummary,
                                overrideAuthorName = memberName,
                                providerLabel = providerBadge,
                                attachedCouponColumns = request.attachedCouponColumns,
                                attachedCouponFormulaTitle = request.attachedCouponFormulaTitle
                            )
                        },
                        onToggleLike = { postId ->
                            viewModel.toggleLikePost(postId)
                        }
                    )
                }

                AppNavTab.PROFILE -> {
                    ProfileScreen(
                        userProfile = userProfile,
                        onUpdateUsername = { newName ->
                            viewModel.updateUsername(newName)
                            if (memberSession.isSignedIn) {
                                saveScope.launch {
                                    poolRepository.updateMemberProfile(username = newName)
                                }
                            }
                        },
                        onSelectTeam = { team ->
                            viewModel.updateFavoriteTeam(team)
                            if (memberSession.isSignedIn) {
                                saveScope.launch {
                                    poolRepository.updateMemberProfile(favoriteTeamId = team.name)
                                }
                            }
                        },
                        onOpenWallet = { showGooglePlayDialog = true },
                        memberSession = memberSession,
                        poolRepository = poolRepository,
                        onOpenAuth = { showAuthDialog = true },
                        onRefreshMemberProfile = {
                            saveScope.launch {
                                poolRepository.refreshProfileFromServer()
                                    .onSuccess {
                                        Toast.makeText(context, "Üye profili buluttan güncellendi.", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure {
                                        Toast.makeText(context, it.message ?: "Senkronizasyon başarısız.", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                        onSignOut = {
                            saveScope.launch {
                                poolRepository.signOutAsync()
                                Toast.makeText(context, "Üye oturumu kapatıldı.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

        }
    }

    if (showFormulaWizard) {
        FormulaWizardDialog(
            initialFormula = FormulaType.GUARANTEE_14,
            matches = matches,
            onDismiss = { showFormulaWizard = false },
            onConfirmFormula = { formula ->
                if (viewModel.ensurePlayableBulletin()) {
                    viewModel.selectFormula(formula)
                    viewModel.generateCoupon(formula)
                    currentTab = AppNavTab.COUPONS
                }
            }
        )
    }

    if (showTxtDialog) {
        TxtImportDialog(
            onDismiss = { showTxtDialog = false },
            onImportTxt = { content ->
                val coupon = viewModel.importTxtColumns(content)
                if (coupon != null) {
                    currentTab = AppNavTab.COUPONS
                }
            },
            onLoadSample = {
                viewModel.loadSampleTxt()
                currentTab = AppNavTab.COUPONS
            }
        )
    }

    if (showExcelDialog) {
        ExcelImportDialog(
            onDismiss = { showExcelDialog = false },
            onImportContent = { content ->
                val coupon = viewModel.importTxtColumns(content, "Excel'den Yüklenen Kupon")
                if (coupon != null) {
                    currentTab = AppNavTab.COUPONS
                }
            }
        )
    }

    if (showGooglePlayDialog) {
        GooglePlayBillingDialog(
            currentBalanceTL = userProfile.walletBalanceTL,
            requiredFeeTL = currentCoupon?.columnFeeTotalTL,
            onDismiss = { showGooglePlayDialog = false },
            onPurchaseSuccess = { pkg ->
                viewModel.topUpWallet(pkg)
            }
        )
    }

    if (showFilterDialog) {
        TotoFilterDialog(
            initialCriteria = filterCriteria,
            matches = matches,
            targetColumnCap = targetColumnCap,
            sourceColumns = viewModel.currentFilterSourceColumns(),
            onDismiss = { showFilterDialog = false },
            onApplyFilters = { criteria, matrixResult, saveToDevice ->
                if (viewModel.ensurePlayableBulletin()) {
                    val coupon = viewModel.applyFiltersAndEliminate(
                        criteria = criteria,
                        precomputedMatrix = matrixResult
                    )
                    if (saveToDevice) saveCouponToDevice(coupon)
                    currentTab = AppNavTab.COUPONS
                }
            }
        )
    }

    webViewAutomationTarget?.let { (targetCoupon, targetCreds) ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            NesineWebBrowserAutomationContent(
                coupon = targetCoupon,
                credentials = targetCreds,
                onDismiss = { webViewAutomationTarget = null },
                onPlatformSelected = { platform ->
                    val updatedCreds = targetCreds.copy(platform = platform)
                    viewModel.saveNesineCredentials(updatedCreds)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showAuthDialog) {
        SupabaseAuthDialog(
            repository = poolRepository,
            onDismiss = { showAuthDialog = false },
            onAuthenticated = { displayName ->
                if (displayName.isNotBlank()) {
                    viewModel.updateUsername(displayName)
                }
                Toast.makeText(
                    context,
                    "Üye oturumu açıldı.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}
