package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.FormulaEngine
import com.example.model.Coupon
import com.example.model.FormulaType
import com.example.model.ForumPost
import com.example.model.INSUFFICIENT_BALANCE_ERROR
import com.example.model.MatchProbability
import com.example.model.NesineCredentials
import com.example.model.PlatformCredentials
import com.example.model.PredictionOutcome
import com.example.model.SupportedTeam
import com.example.model.TotoColumn
import com.example.model.TotoMatch
import com.example.model.UserProfile
import com.example.network.NetworkModule
import com.example.network.model.NO_NEW_BULLETIN_ERROR
import com.example.network.model.PreviousWeekBulletinReport
import com.example.network.model.WeeklyBulletinResponse
import com.example.network.model.isPlayable
import com.example.network.model.toDomainTotoMatch
import com.example.repository.LiveScoreSource
import com.example.repository.SporTotoBulletinRepository
import com.example.repository.SporTotoBulletinRepositoryImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.example.data.local.entity.PurchaseHistoryEntity
import com.example.data.repository.WalletRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.data.local.CouponJsonStorageManager
import com.example.data.local.SavedJsonCouponMeta
import com.example.model.FilterProfileType
import com.example.model.MatchLiveScore

class TotoViewModel(
    private val bulletinRepository: SporTotoBulletinRepository = SporTotoBulletinRepositoryImpl(),
    private val walletRepository: WalletRepository? = null
) : ViewModel() {

    private val _matches = MutableStateFlow<List<TotoMatch>>(createInitialMatches())
    val matches: StateFlow<List<TotoMatch>> = _matches.asStateFlow()

    val currentBulletin: StateFlow<WeeklyBulletinResponse?> = bulletinRepository.cachedBulletin

    private val _isRefreshingBulletin = MutableStateFlow(false)
    val isRefreshingBulletin: StateFlow<Boolean> = _isRefreshingBulletin.asStateFlow()

    private val _bulletinMessage = MutableStateFlow<String?>(null)
    val bulletinMessage: StateFlow<String?> = _bulletinMessage.asStateFlow()

    private val _bulletinError = MutableStateFlow<String?>(null)
    val bulletinError: StateFlow<String?> = _bulletinError.asStateFlow()

    private val _hasPlayableBulletin = MutableStateFlow(true)
    val hasPlayableBulletin: StateFlow<Boolean> = _hasPlayableBulletin.asStateFlow()

    private val _walletError = MutableStateFlow<String?>(null)
    val walletError: StateFlow<String?> = _walletError.asStateFlow()

    private val _coupons = MutableStateFlow<List<Coupon>>(emptyList())
    val coupons: StateFlow<List<Coupon>> = _coupons.asStateFlow()

    private val _currentCoupon = MutableStateFlow<Coupon?>(null)
    val currentCoupon: StateFlow<Coupon?> = _currentCoupon.asStateFlow()

    // The active coupon can be filtered again only while its original match
    // selections remain unchanged. This prevents stale starter coupons from
    // replacing the user's newly chosen 15-match matrix.
    private var currentCouponSourceMatches: List<TotoMatch>? = null

    fun currentFilterSourceColumns(): List<TotoColumn>? =
        if (currentCouponSourceMatches == _matches.value) _currentCoupon.value?.columns else null

    private val _forumPosts = MutableStateFlow<List<ForumPost>>(createInitialForumPosts())
    val forumPosts: StateFlow<List<ForumPost>> = _forumPosts.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _selectedFormula = MutableStateFlow(FormulaType.GUARANTEE_14)
    val selectedFormula: StateFlow<FormulaType> = _selectedFormula.asStateFlow()

    private val _targetColumnCap = MutableStateFlow<Int?>(null)
    val targetColumnCap: StateFlow<Int?> = _targetColumnCap.asStateFlow()

    fun setTargetColumnCap(cap: Int?) {
        _targetColumnCap.value = cap
    }

    private val _filterCriteria = MutableStateFlow(com.example.model.TotoFilterCriteria())
    val filterCriteria: StateFlow<com.example.model.TotoFilterCriteria> = _filterCriteria.asStateFlow()

    val liveScores: StateFlow<List<MatchLiveScore>> = bulletinRepository.liveScores
    val liveScoreProvider: StateFlow<String> = bulletinRepository.liveScoreProvider
    val activeLiveScoreSource: StateFlow<LiveScoreSource> = bulletinRepository.activeLiveScoreSource

    private val _hasApiFootballKey = MutableStateFlow(bulletinRepository.hasApiFootballKey())
    val hasApiFootballKey: StateFlow<Boolean> = _hasApiFootballKey.asStateFlow()

    private val _freeScoreRequestsRemaining =
        MutableStateFlow(bulletinRepository.freeScoreRequestsRemaining())
    val freeScoreRequestsRemaining: StateFlow<Int?> = _freeScoreRequestsRemaining.asStateFlow()

    fun selectLiveScoreSource(source: LiveScoreSource) {
        bulletinRepository.setLiveScoreSource(source)
        _liveScoreError.value = null
        _previousWeekError.value = null
        _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
        if (!bulletinRepository.requiresApiKeyForActiveSource() || bulletinRepository.hasApiFootballKey()) {
            pollLiveScoresNow()
        }
    }

    fun saveApiFootballKey(key: String) {
        bulletinRepository.configureApiFootballKey(key)
        _hasApiFootballKey.value = bulletinRepository.hasApiFootballKey()
        _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
        _liveScoreError.value = null
        _previousWeekError.value = null
        if (_hasApiFootballKey.value) pollLiveScoresNow()
    }

    val previousWeekReport: StateFlow<PreviousWeekBulletinReport?> =
        bulletinRepository.previousWeekReport

    private val _isLoadingPreviousWeek = MutableStateFlow(false)
    val isLoadingPreviousWeek: StateFlow<Boolean> = _isLoadingPreviousWeek.asStateFlow()

    private val _previousWeekError = MutableStateFlow<String?>(null)
    val previousWeekError: StateFlow<String?> = _previousWeekError.asStateFlow()

    fun loadPreviousWeekBulletin(forceRefresh: Boolean = false) {
        if (_isLoadingPreviousWeek.value) return
        viewModelScope.launch {
            _isLoadingPreviousWeek.value = true
            _previousWeekError.value = null
            val res = bulletinRepository.fetchPreviousWeekBulletinAndResults(forceRefresh = forceRefresh)
            res.onSuccess {
                _previousWeekError.value = null
            }.onFailure { err ->
                _previousWeekError.value = err.message ?: "Geçen haftanın sonuçları alınamadı."
            }
            _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
            _isLoadingPreviousWeek.value = false
        }
    }

    fun loadPreviousWeekWithEspn(forceRefresh: Boolean = true) {
        if (_isLoadingPreviousWeek.value) return
        viewModelScope.launch {
            _isLoadingPreviousWeek.value = true
            _previousWeekError.value = null
            val res = bulletinRepository.fetchPreviousWeekFromEspn(forceRefresh = forceRefresh)
            res.onSuccess {
                _previousWeekError.value = null
            }.onFailure { err ->
                _previousWeekError.value = err.message ?: "Geçen haftanın ESPN sonuçları alınamadı."
            }
            _isLoadingPreviousWeek.value = false
        }
    }

    private val _isLivePolling = MutableStateFlow(false)
    val isLivePolling: StateFlow<Boolean> = _isLivePolling.asStateFlow()

    private val _lastLiveUpdateTime = MutableStateFlow("Henüz güncellenmedi")
    val lastLiveUpdateTime: StateFlow<String> = _lastLiveUpdateTime.asStateFlow()

    private val _liveScoreError = MutableStateFlow<String?>(null)
    val liveScoreError: StateFlow<String?> = _liveScoreError.asStateFlow()

    private val _isRefreshingLiveScores = MutableStateFlow(false)
    val isRefreshingLiveScores: StateFlow<Boolean> = _isRefreshingLiveScores.asStateFlow()

    private val _favoritedMatchNumbers = MutableStateFlow<Set<Int>>(emptySet())
    val favoritedMatchNumbers: StateFlow<Set<Int>> = _favoritedMatchNumbers.asStateFlow()

    init {
        // Initial bulletin load & starter coupon
        refreshBulletin(forceRefresh = false)
        generateCoupon(FormulaType.GUARANTEE_14, "14 Garanti (N-1) Özel Başlangıç Kuponu")

        // Poll live scores while the tracker or bulletin is visible (45s for keyless ESPN, 3m for API-Football).
        // The repository also enforces an independent throttle.
        viewModelScope.launch {
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            while (true) {
                if (_isLivePolling.value) {
                    val result = bulletinRepository.pollLiveScores()
                    result.onSuccess {
                        _liveScoreError.value = null
                        _lastLiveUpdateTime.value = timeFormat.format(Date())
                    }.onFailure { _liveScoreError.value = it.message ?: "Canlı veri alınamadı." }
                    _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
                }
                val intervalMs = if (activeLiveScoreSource.value == LiveScoreSource.ESPN) 45_000L else 180_000L
                delay(intervalMs)
            }
        }

        // Sync with Room database if walletRepository is provided
        walletRepository?.let { repo ->
            viewModelScope.launch {
                repo.balanceFlow.collect { roomBalance ->
                    _userProfile.update { it.copy(walletBalanceTL = roomBalance) }
                }
            }
            viewModelScope.launch {
                repo.purchaseHistoryFlow.collect { history ->
                    if (history.isNotEmpty()) {
                        val mapped = history.map { entity ->
                            com.example.model.WalletTransaction(
                                id = entity.transactionId,
                                title = entity.title,
                                amountTL = entity.amountTL,
                                type = if (entity.transactionType == PurchaseHistoryEntity.TYPE_TOP_UP) {
                                    com.example.model.TransactionType.TOP_UP
                                } else {
                                    com.example.model.TransactionType.COLUMN_FEE
                                },
                                dateText = entity.formattedDate,
                                orderId = entity.orderId
                            )
                        }
                        _userProfile.update { it.copy(walletTransactions = mapped) }
                    }
                }
            }
        }
    }

    fun refreshBulletin(forceRefresh: Boolean = true) {
        // Ignore repeated taps while a bulletin request is already running.
        if (_isRefreshingBulletin.value) return
        _isRefreshingBulletin.value = true
        viewModelScope.launch {
            try {
                val result = bulletinRepository.getDomainMatches(forceRefresh)
                result.onSuccess { freshMatches ->
                    val bulletin = currentBulletin.value
                    val playable = freshMatches.size >= 15 && (bulletin == null || bulletin.isPlayable())
                    if (playable) {
                        _matches.value = freshMatches
                        _hasPlayableBulletin.value = true
                        _bulletinError.value = null
                        syncStarterForumPostsWithBulletin(freshMatches)
                        if (liveScores.value.isNotEmpty()) {
                            _liveScoreError.value = null
                            _lastLiveUpdateTime.value =
                                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        } else if (!bulletinRepository.requiresApiKeyForActiveSource() || bulletinRepository.hasApiFootballKey()) {
                            pollLiveScoresNow()
                        }
                        if (forceRefresh) {
                            _bulletinMessage.value = "Maçlar çekildi, canlı skorlar takip ediliyor!"
                        }
                    } else {
                        _matches.value = emptyList()
                        _hasPlayableBulletin.value = false
                        _bulletinError.value = NO_NEW_BULLETIN_ERROR
                        _bulletinMessage.value = NO_NEW_BULLETIN_ERROR
                    }
                }.onFailure { error ->
                    _matches.value = emptyList()
                    _hasPlayableBulletin.value = false
                    val errorMsg = error.message?.takeIf { it.isNotBlank() } ?: NO_NEW_BULLETIN_ERROR
                    _bulletinError.value = errorMsg
                    _bulletinMessage.value = errorMsg
                }
            } finally {
                _isRefreshingBulletin.value = false
            }
        }
    }

    fun syncTurkishLeagueBulletinFromApi() {
        if (_isRefreshingBulletin.value) return
        _isRefreshingBulletin.value = true
        viewModelScope.launch {
            try {
                if (bulletinRepository.requiresApiKeyForActiveSource() && !bulletinRepository.hasApiFootballKey()) {
                    val result = bulletinRepository.getDomainMatches(forceRefresh = true)
                    result.onSuccess { freshMatches ->
                        val bulletin = currentBulletin.value
                        if (freshMatches.size >= 15 && (bulletin == null || bulletin.isPlayable())) {
                            _matches.value = freshMatches
                            _hasPlayableBulletin.value = true
                            _bulletinError.value = null
                            syncStarterForumPostsWithBulletin(freshMatches)
                        }
                    }
                    _bulletinMessage.value =
                        "Türkiye Ligi bülteni hazır. Canlı API-Football takibi için Canlı sekmesinden anahtar girin veya ESPN'e geçin."
                    return@launch
                }

                val syncResult = bulletinRepository.syncTurkishLeagueBulletinFromApi()
                syncResult.onSuccess { bulletin ->
                    val freshMatches = bulletin.matches.map { it.toDomainTotoMatch() }
                    val playable = freshMatches.size >= 15 && bulletin.isPlayable()
                    if (playable) {
                        _matches.value = freshMatches
                        _hasPlayableBulletin.value = true
                        _bulletinError.value = null
                        _liveScoreError.value = null
                        _lastLiveUpdateTime.value =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        syncStarterForumPostsWithBulletin(freshMatches)
                        val badge = bulletinRepository.activeLiveScoreSource.value.badgeLabel
                        _bulletinMessage.value =
                            "Türkiye Ligi & Avrupa bülteni ($badge) ile bağlandı ve maç takibi başlatıldı!"
                    } else {
                        _matches.value = emptyList()
                        _hasPlayableBulletin.value = false
                        _bulletinError.value = NO_NEW_BULLETIN_ERROR
                        _bulletinMessage.value = NO_NEW_BULLETIN_ERROR
                    }
                }.onFailure { err ->
                    _bulletinMessage.value = err.message ?: "Türkiye Ligi senkronizasyonu başarısız."
                }
                _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
            } finally {
                _isRefreshingBulletin.value = false
            }
        }
    }

    fun ensurePlayableBulletin(): Boolean {
        val bulletin = currentBulletin.value
        val playable = _hasPlayableBulletin.value &&
            _matches.value.size >= 15 &&
            (bulletin == null || bulletin.isPlayable())
        if (!playable) {
            _hasPlayableBulletin.value = false
            _bulletinError.value = NO_NEW_BULLETIN_ERROR
            _bulletinMessage.value = NO_NEW_BULLETIN_ERROR
        }
        return playable
    }

    fun clearBulletinMessage() {
        _bulletinMessage.value = null
    }

    fun selectFormula(formulaType: FormulaType) {
        _selectedFormula.value = formulaType
    }

    fun togglePrediction(matchId: Int, outcome: PredictionOutcome) {
        _matches.update { list ->
            list.map { match ->
                if (match.id == matchId) {
                    val current = match.selections.toMutableSet()
                    if (current.contains(outcome)) {
                        current.remove(outcome)
                    } else {
                        current.add(outcome)
                    }
                    match.copy(selections = current)
                } else match
            }
        }
    }

    fun selectAllFavorites() {
        _matches.update { list ->
            list.map { match ->
                val h = match.probability.home.firstOrNull() ?: 50
                val d = match.probability.draw.firstOrNull() ?: 25
                val a = match.probability.away.firstOrNull() ?: 25
                val best = when {
                    h >= d && h >= a -> PredictionOutcome.HOME
                    d >= h && d >= a -> PredictionOutcome.DRAW
                    else -> PredictionOutcome.AWAY
                }
                match.copy(selections = setOf(best))
            }
        }
    }

    fun applyQuickDoubles() {
        _matches.update { list ->
            list.mapIndexed { idx, match ->
                val set = when (idx % 3) {
                    0 -> setOf(PredictionOutcome.HOME, PredictionOutcome.DRAW)
                    1 -> setOf(PredictionOutcome.DRAW, PredictionOutcome.AWAY)
                    else -> setOf(PredictionOutcome.HOME, PredictionOutcome.AWAY)
                }
                match.copy(selections = set)
            }
        }
        // Only update match selections; generating a matrix is a separate user action.
    }

    fun applyQuickDoubles10To15() {
        _matches.update { list ->
            list.mapIndexed { idx, match ->
                if (idx in 9..14) {
                    val set = when (idx) {
                        9 -> setOf(PredictionOutcome.HOME, PredictionOutcome.DRAW)
                        10 -> setOf(PredictionOutcome.DRAW, PredictionOutcome.AWAY)
                        11 -> setOf(PredictionOutcome.HOME, PredictionOutcome.AWAY)
                        12 -> setOf(PredictionOutcome.HOME, PredictionOutcome.DRAW)
                        13 -> setOf(PredictionOutcome.DRAW, PredictionOutcome.AWAY)
                        else -> setOf(PredictionOutcome.HOME, PredictionOutcome.DRAW)
                    }
                    match.copy(selections = set)
                } else {
                    if (match.selections.isEmpty()) {
                        val h = match.probability.home.firstOrNull() ?: 50
                        val d = match.probability.draw.firstOrNull() ?: 25
                        val a = match.probability.away.firstOrNull() ?: 25
                        val best = when {
                            h >= d && h >= a -> PredictionOutcome.HOME
                            d >= h && d >= a -> PredictionOutcome.DRAW
                            else -> PredictionOutcome.AWAY
                        }
                        match.copy(selections = setOf(best))
                    } else match
                }
            }
        }
        // Avoid expensive automatic coupon generation after a preset selection.
    }

    fun applyQuickDoubles1To9() {
        _matches.update { list ->
            list.mapIndexed { idx, match ->
                if (idx in 0..8) {
                    val h = match.probability.home.firstOrNull() ?: 50
                    val a = match.probability.away.firstOrNull() ?: 25
                    val set = if (h >= a) {
                        setOf(PredictionOutcome.HOME, PredictionOutcome.DRAW)
                    } else {
                        setOf(PredictionOutcome.DRAW, PredictionOutcome.AWAY)
                    }
                    match.copy(selections = set)
                } else {
                    if (match.selections.isEmpty()) {
                        val h = match.probability.home.firstOrNull() ?: 50
                        val d = match.probability.draw.firstOrNull() ?: 25
                        val a = match.probability.away.firstOrNull() ?: 25
                        val best = when {
                            h >= d && h >= a -> PredictionOutcome.HOME
                            d >= h && d >= a -> PredictionOutcome.DRAW
                            else -> PredictionOutcome.AWAY
                        }
                        match.copy(selections = setOf(best))
                    } else match
                }
            }
        }
        // The Formüller action generates the coupon when requested.
    }

    fun clearAllSelections() {
        _matches.update { list ->
            list.map { it.copy(selections = emptySet(), isBanko = false) }
        }
    }

    fun generateCoupon(
        formulaType: FormulaType,
        customTitle: String? = null,
        cap: Int? = null
    ): Coupon {
        if (!ensurePlayableBulletin()) {
            return Coupon(
                id = "no_bulletin",
                title = NO_NEW_BULLETIN_ERROR,
                formulaType = formulaType,
                columns = emptyList()
            )
        }
        val title = customTitle ?: "${formulaType.title} (#${_coupons.value.size + 1})"
        val effectiveCap = cap ?: _targetColumnCap.value
        val generatedCols = FormulaEngine.generateColumns(_matches.value, formulaType, maxColumnsCap = effectiveCap)
        val newCoupon = Coupon(
            id = "coupon_${System.currentTimeMillis()}",
            title = title,
            formulaType = formulaType,
            columns = generatedCols
        )
        _coupons.update { listOf(newCoupon) + it }
        _currentCoupon.value = newCoupon
        currentCouponSourceMatches = _matches.value
        return newCoupon
    }

    fun updateFilterCriteria(criteria: com.example.model.TotoFilterCriteria) {
        _filterCriteria.value = criteria
    }

    fun resetFilterCriteria() {
        _filterCriteria.value = com.example.model.TotoFilterCriteria()
    }

    /**
     * Applies a 1-click filter profile (Favorite weighted, Balanced, Surprise weighted)
     * and generates the filtered coupon.
     */
    fun applyFilterProfile(profile: FilterProfileType): Coupon {
        val criteria = com.example.engine.TotoFilterEngine
            .filterAvailability(_matches.value)
            .restrict(profile.toCriteria())
        _filterCriteria.value = criteria
        return applyFiltersAndEliminate(criteria, "${profile.displayName} Kuponu")
    }

    /**
     * Re-filters only the active coupon when its match selections are unchanged;
     * otherwise starts from the user's new matrix. Never expands a filtered
     * coupon by regenerating combinations during successive filter operations.
     */
    fun applyFiltersAndEliminate(
        criteria: com.example.model.TotoFilterCriteria,
        customTitle: String? = null,
        precomputedMatrix: com.example.engine.MatrixFilterResult? = null
    ): Coupon {
        _filterCriteria.value = criteria
        if (!ensurePlayableBulletin()) {
            return Coupon(
                id = "no_bulletin",
                title = NO_NEW_BULLETIN_ERROR,
                formulaType = _selectedFormula.value,
                columns = emptyList()
            )
        }
        val result = precomputedMatrix ?: com.example.engine.TotoFilterEngine.filterSelectedMatrix(
            matches = _matches.value,
            criteria = criteria,
            maxColumnsCap = _targetColumnCap.value,
            sourceColumns = currentFilterSourceColumns()
        )

        if (result.isTooLarge) {
            _bulletinMessage.value =
                "Matris çok büyük (${result.initialColumnCount} kolon). " +
                "Doğru eleme için seçimleri ${com.example.engine.TotoFilterEngine.MAX_EXACT_MATRIX_COMBINATIONS} kolona indirin."
            return _currentCoupon.value ?: Coupon(
                id = "empty_filter", title = "Filtreleme uygulanamadı",
                formulaType = _selectedFormula.value, columns = emptyList()
            )
        }

        val savings = if (result.initialColumnCount > 0) {
            ((result.initialColumnCount - result.matchingColumnCount) * 100) / result.initialColumnCount
        } else 0

        val modeLabel = if (
            criteria.applicationMode == com.example.model.FilterApplicationMode.PROBABILITY_STRICT
        ) "Tüm koşullar" else "Esnek (koşul başına ±1)"
        val limitInfo = if (result.isLimitedByCap) " • Kupon limiti: ${result.savedColumnCount}" else ""
        val title = customTitle ?: "Matris Eleme ($modeLabel • %$savings Eleme$limitInfo)"

        // If no column passes, keep the empty result. Rejected columns must NEVER
        // be silently reintroduced to make a nonempty coupon.
        val newCoupon = Coupon(
            id = "filtered_${System.currentTimeMillis()}",
            title = title,
            formulaType = _selectedFormula.value,
            columns = result.columns
        )

        _coupons.update { listOf(newCoupon) + it }
        _currentCoupon.value = newCoupon
        currentCouponSourceMatches = _matches.value
        return newCoupon
    }

    // --- Local JSON Storage Operations ---

    fun saveCurrentCouponToJson(context: Context): Result<File> {
        val coupon = _currentCoupon.value
            ?: return Result.failure(IllegalStateException("Kaydedilecek aktif kupon bulunamadı."))
        return saveCouponToJson(context, coupon)
    }

    /** Saves the exact coupon the user selected, including filtered columns. */
    fun saveCouponToJson(context: Context, coupon: Coupon): Result<File> {
        if (coupon.columns.isEmpty()) {
            return Result.failure(IllegalArgumentException("Kaydedilecek kolon yok. Önce kupon oluşturun."))
        }
        if (coupon.columns.any { it.predictions.size != 15 }) {
            return Result.failure(IllegalArgumentException("Kupon yalnızca 15 maçlık geçerli kolonlar içermelidir."))
        }
        return CouponJsonStorageManager.saveCouponToFile(context, coupon)
    }

    fun listSavedJsonCoupons(context: Context): List<SavedJsonCouponMeta> {
        return CouponJsonStorageManager.listSavedJsonCoupons(context)
    }

    fun loadCouponFromJsonFile(file: File): Result<Coupon> {
        return CouponJsonStorageManager.loadCouponFromFile(file).onSuccess { loaded ->
            _coupons.update { existing ->
                if (existing.any { it.id == loaded.id }) existing
                else listOf(loaded) + existing
            }
            _currentCoupon.value = loaded
            currentCouponSourceMatches = _matches.value
        }
    }

    fun deleteSavedJsonCoupon(file: File): Boolean {
        return CouponJsonStorageManager.deleteCouponFile(file)
    }

    // --- Real-time Match Results & Live Polling Operations ---

    fun toggleLivePolling() {
        _isLivePolling.update { !it }
    }

    fun setLivePolling(enabled: Boolean) {
        _isLivePolling.value = enabled
    }

    fun pollLiveScoresNow() {
        if (_isRefreshingLiveScores.value) return
        viewModelScope.launch {
            _isRefreshingLiveScores.value = true
            val result = bulletinRepository.pollLiveScores()
            result.onSuccess {
                _liveScoreError.value = null
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                _lastLiveUpdateTime.value = timeFormat.format(Date())
            }.onFailure {
                _liveScoreError.value = it.message ?: "Canlı veri alınamadı."
            }
            _freeScoreRequestsRemaining.value = bulletinRepository.freeScoreRequestsRemaining()
            delay(300)
            _isRefreshingLiveScores.value = false
        }
    }

    // Favorites are independent from notifications.
    fun toggleFavoriteMatch(matchNumber: Int): Boolean {
        var isNowFav = false
        _favoritedMatchNumbers.update { current ->
            if (current.contains(matchNumber)) {
                isNowFav = false
                current - matchNumber
            } else {
                isNowFav = true
                current + matchNumber
            }
        }
        return isNowFav
    }

    fun isMatchFavorited(matchNumber: Int): Boolean =
        _favoritedMatchNumbers.value.contains(matchNumber)

    fun importTxtColumns(content: String, formulaName: String = "Yüklenen Extra1X2 Kuponu"): Coupon? {
        val cols = FormulaEngine.parseExtra1X2Txt(content)
        if (cols.isEmpty()) return null

        val coupon = Coupon(
            id = "txt_${System.currentTimeMillis()}",
            title = formulaName,
            formulaType = FormulaType.EXTRA1X2_TXT,
            columns = cols
        )
        _coupons.update { listOf(coupon) + it }
        _currentCoupon.value = coupon
        currentCouponSourceMatches = _matches.value
        return coupon
    }

    fun loadSampleTxt() {
        val sample = buildString {
            append("1X211X212111X21\n")
            append("11111X212111X21\n")
            append("1X2111112111X21\n")
            append("1X211X211111X21\n")
            append("11211X212111X22\n")
            append("1X111X212111X21\n")
            append("1XX11X212111X21\n")
            append("1X211X212111111\n")
            append("111111111111111\n")
        }
        importTxtColumns(sample, "Örnek Extra1X2 TXT Kuponu (9 Kolon)")
    }

    fun setCurrentCoupon(coupon: Coupon) {
        _currentCoupon.value = coupon
        currentCouponSourceMatches = _matches.value
    }

    fun deleteCoupon(couponId: String) {
        _coupons.update { list -> list.filter { it.id != couponId } }
        if (_currentCoupon.value?.id == couponId) {
            _currentCoupon.value = _coupons.value.firstOrNull()
            currentCouponSourceMatches = null
        }
    }

    fun saveNesineCredentials(credentials: PlatformCredentials) {
        savePlatformCredentials(credentials)
    }

    fun savePlatformCredentials(credentials: PlatformCredentials) {
        _userProfile.update {
            it.copy(platformCredentials = it.platformCredentials + (credentials.platform.id to credentials))
        }
    }

    fun topUpWallet(pkg: com.example.model.GooglePlayPackage) {
        val tx = com.example.billing.GooglePlayBillingManager.createPurchaseTransaction(pkg)
        _walletError.value = null
        _userProfile.update { current ->
            current.copy(
                walletBalanceTL = current.walletBalanceTL + pkg.amountTL,
                walletTransactions = listOf(tx) + current.walletTransactions
            )
        }
        walletRepository?.let { repo ->
            viewModelScope.launch {
                repo.topUp(pkg, tx.orderId ?: "GPA.SIMULATED")
            }
        }
    }

    fun canAffordCoupon(coupon: Coupon): Boolean {
        val balance = _userProfile.value.walletBalanceTL
        return balance > 0.0 && balance >= coupon.columnFeeTotalTL
    }

    fun deductColumnFee(coupon: Coupon): Boolean {
        if (!ensurePlayableBulletin()) {
            return false
        }
        val required = coupon.columnFeeTotalTL
        val currentBalance = _userProfile.value.walletBalanceTL
        if (currentBalance <= 0.0 || currentBalance < required) {
            _walletError.value = INSUFFICIENT_BALANCE_ERROR
            _bulletinMessage.value = "$INSUFFICIENT_BALANCE_ERROR! Bu kupon için %.2f ₺ gereklidir.".format(required)
            return false
        }
        _walletError.value = null
        val tx = com.example.billing.GooglePlayBillingManager.createColumnPlayTransaction(coupon.totalColumns, required)
        _userProfile.update { current ->
            current.copy(
                walletBalanceTL = current.walletBalanceTL - required,
                walletTransactions = listOf(tx) + current.walletTransactions,
                totalAutoPlayedCoupons = current.totalAutoPlayedCoupons + 1
            )
        }
        walletRepository?.let { repo ->
            viewModelScope.launch {
                repo.chargeForCouponColumns(coupon.totalColumns, coupon.id)
            }
        }
        return true
    }

    fun addForumPost(
        content: String,
        attachedCouponId: String? = null,
        matchRef: String? = null,
        matchNumber: Int? = null,
        predictedOutcome: PredictionOutcome? = null,
        replyToPostId: String? = null,
        replyToAuthor: String? = null,
        attachedCouponSummary: String? = null,
        overrideAuthorName: String? = null,
        providerLabel: String? = null,
        attachedCouponColumns: List<String> = emptyList(),
        attachedCouponFormulaTitle: String? = null
    ) {
        if (content.isBlank()) return
        val profile = _userProfile.value
        val matchedBulletinGame = matchNumber?.let { num ->
            _matches.value.firstOrNull { it.matchNumber == num }
        }
        val resolvedMatchRef = matchRef?.takeIf { it.isNotBlank() }
            ?: matchedBulletinGame?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" }
        val attachedCoupon = attachedCouponId?.let { id ->
            _coupons.value.firstOrNull { it.id == id }
        }
        val resolvedCouponSummary = attachedCouponSummary?.takeIf { it.isNotBlank() }
            ?: attachedCoupon?.let { "${it.title} (${it.totalColumns} Kolon)" }
        val resolvedColumns = attachedCouponColumns.ifEmpty {
            attachedCoupon?.columns?.map { it.asString }.orEmpty()
        }
        val resolvedFormulaTitle = attachedCouponFormulaTitle?.takeIf { it.isNotBlank() }
            ?: attachedCoupon?.formulaType?.title
        val post = ForumPost(
            id = "post_${System.currentTimeMillis()}",
            authorName = overrideAuthorName?.trim()?.takeIf { it.isNotEmpty() } ?: profile.username,
            authorTeam = profile.favoriteTeam,
            authorTitle = if (!providerLabel.isNullOrBlank()) "Doğrulanmış Üye" else "Spor Toto Uzmanı",
            content = content.trim(),
            dateText = "Bugün ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
            likesCount = 0,
            attachedCouponId = attachedCouponId,
            matchReference = resolvedMatchRef,
            matchNumber = matchNumber,
            predictedOutcome = predictedOutcome,
            replyToPostId = replyToPostId,
            replyToAuthor = replyToAuthor,
            attachedCouponSummary = resolvedCouponSummary,
            providerLabel = providerLabel,
            attachedCouponColumns = resolvedColumns,
            attachedCouponFormulaTitle = resolvedFormulaTitle
        )
        _forumPosts.update { listOf(post) + it }
    }

    fun openSharedForumCoupon(coupon: Coupon) {
        _coupons.update { existing ->
            if (existing.any { it.id == coupon.id }) existing
            else listOf(coupon) + existing
        }
        _currentCoupon.value = coupon
        currentCouponSourceMatches = _matches.value
    }

    private fun syncStarterForumPostsWithBulletin(freshMatches: List<TotoMatch>) {
        if (freshMatches.isEmpty()) return
        val m1 = freshMatches.getOrNull(0)
        val m6 = freshMatches.getOrNull(5)
        val m7 = freshMatches.getOrNull(6)
        _forumPosts.update { posts ->
            posts.map { post ->
                when (post.id) {
                    "p1" -> if (m1 != null) post.copy(
                        matchNumber = m1.matchNumber,
                        matchReference = "#${m1.matchNumber} ${m1.homeTeam} - ${m1.awayTeam}"
                    ) else post
                    "p3" -> if (m6 != null) post.copy(
                        matchNumber = m6.matchNumber,
                        matchReference = "#${m6.matchNumber} ${m6.homeTeam} - ${m6.awayTeam}"
                    ) else post
                    "p4" -> if (m7 != null) post.copy(
                        matchNumber = m7.matchNumber,
                        matchReference = "#${m7.matchNumber} ${m7.homeTeam} - ${m7.awayTeam}"
                    ) else post
                    else -> post
                }
            }
        }
    }

    fun toggleLikePost(postId: String) {
        _forumPosts.update { list ->
            list.map { post ->
                if (post.id == postId) {
                    val wasLiked = post.isLiked
                    post.copy(
                        isLiked = !wasLiked,
                        likesCount = if (wasLiked) post.likesCount - 1 else post.likesCount + 1
                    )
                } else post
            }
        }
    }

    fun updateFavoriteTeam(team: SupportedTeam) {
        _userProfile.update { it.copy(favoriteTeam = team) }
    }

    fun updateUsername(name: String) {
        if (name.isNotBlank()) {
            _userProfile.update { it.copy(username = name.trim()) }
        }
    }

    private fun createInitialMatches(): List<TotoMatch> {
        return NetworkModule.createTurkishLeagueSporTotoBulletin().matches.map { it.toDomainTotoMatch() }
    }

    private fun createInitialForumPosts(): List<ForumPost> {
        val m1 = createInitialMatches().getOrNull(0)
        val m6 = createInitialMatches().getOrNull(5)
        val m7 = createInitialMatches().getOrNull(6)
        return listOf(
            ForumPost(
                id = "p1",
                authorName = "TATAR34",
                authorTeam = SupportedTeam.ALANYASPOR,
                authorTitle = "Usta ✨",
                content = "Bu maçta %${m1?.probability?.home?.firstOrNull() ?: 79} ev sahibi görünüyor ama sürpriz beraberlik (X) de kuponu ayırır.",
                dateText = "09.11.2026 23:12:47",
                likesCount = 8,
                matchNumber = m1?.matchNumber ?: 1,
                matchReference = m1?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" },
                predictedOutcome = PredictionOutcome.DRAW
            ),
            ForumPost(
                id = "p2",
                authorName = "GALATALI34",
                authorTeam = SupportedTeam.GALATASARAY,
                authorTitle = "Usta ✨",
                content = "Bu haftanın genel bülteninde devir ihtimali yüksek; en az 3-4 maçta çifte şans (1X / X2) koymak şart.",
                dateText = "09.11.2026 23:12:07",
                likesCount = 24
            ),
            ForumPost(
                id = "p3",
                authorName = "CUMHURİYET",
                authorTeam = SupportedTeam.BESIKTAS,
                authorTitle = "Kral 👑",
                content = "Yüzdeler birbirine çok yakın, tek geçilmez — 1X2 kapatmak mantıklı duruyor.",
                dateText = "09.11.2026 23:01:38",
                likesCount = 12,
                matchNumber = m6?.matchNumber ?: 6,
                matchReference = m6?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" },
                predictedOutcome = PredictionOutcome.AWAY
            ),
            ForumPost(
                id = "p4",
                authorName = "HUSEYIN_K",
                authorTeam = SupportedTeam.FENERBAHCE,
                authorTitle = "Usta",
                content = "Garantili 14 formülüyle kuponu kurduk! Bu maçta banko 1 tarafındayım.",
                dateText = "09.11.2026 22:45:10",
                likesCount = 31,
                attachedCouponId = "starter_forum_coupon_p4",
                attachedCouponSummary = "14 Garanti Örnek Kupon (6 Kolon)",
                attachedCouponFormulaTitle = FormulaType.GUARANTEE_14.title,
                attachedCouponColumns = listOf(
                    "111221211212111",
                    "1X12X12112X21X1",
                    "11122X21X21211X",
                    "X11221211212X11",
                    "11X2212X1212111",
                    "1X1221211X121X1"
                ),
                matchNumber = m7?.matchNumber ?: 7,
                matchReference = m7?.let { "#${it.matchNumber} ${it.homeTeam} - ${it.awayTeam}" },
                predictedOutcome = PredictionOutcome.HOME
            )
        )
    }
}
