package com.aurum.edge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.CandleBackfillSummary
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.JournalStats
import com.aurum.edge.data.LiveQuoteFetcher
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.data.PairScanState
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AurumViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
    val market: StateFlow<MarketState> = container.verifiedMarket
    val trades: StateFlow<List<PaperTrade>> = container.journalStore.trades
    val pairScan: StateFlow<PairScanState> = MutableStateFlow(PairScanState()).asStateFlow()
    val clock: StateFlow<Long> = container.clock
    val updateState: StateFlow<AppUpdateRepository.State> = container.updater.state
    val updateSigningDiagnostic: String by lazy { container.updater.installedSigningDiagnostic() }
    val updateSigningSummary: AppUpdateRepository.SigningSummary by lazy { container.updater.signingSummary() }

    private val _liveTicks = MutableStateFlow<Map<String, PriceTick>>(emptyMap())
    val liveTicks: StateFlow<Map<String, PriceTick>> = _liveTicks.asStateFlow()

    private val _lastDisplayMarks = MutableStateFlow<Map<String, DisplayMark>>(emptyMap())
    val lastDisplayMarks: StateFlow<Map<String, DisplayMark>> = _lastDisplayMarks.asStateFlow()

    private val _backfillSummaries = MutableStateFlow<Map<String, CandleBackfillSummary>>(emptyMap())
    val backfillSummaries: StateFlow<Map<String, CandleBackfillSummary>> = _backfillSummaries.asStateFlow()

    private val _isBackfilling = MutableStateFlow(false)
    val isBackfilling: StateFlow<Boolean> = _isBackfilling.asStateFlow()

    private val _activeRiskSymbol = MutableStateFlow(container.settingsStore.read().symbol)
    val activeRiskSymbol: StateFlow<String> = _activeRiskSymbol.asStateFlow()

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var updateCheckStarted = false
    private var liveMonitorJob: Job? = null

    init {
        viewModelScope.launch {
            container.journalStore.trades.collect { currentTrades ->
                _stats.value = container.journalStore.stats()
                _lastDisplayMarks.value = OpenPositionDisplay.onlyOpen(_lastDisplayMarks.value, currentTrades)
            }
        }
        startLiveMonitor()
    }

    fun startApp(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (!updateCheckStarted) {
            updateCheckStarted = true
            checkForAppUpdate(settings.value.autoDownloadUpdates, force = false)
        }
        syncAllOpenAndActiveSymbolsGap(notifyOnSuccess = false)
        startLiveMonitor()
    }

    private fun startLiveMonitor() {
        if (liveMonitorJob?.isActive == true) return
        liveMonitorJob = viewModelScope.launch {
            while (isActive) {
                val openSymbols = trades.value.filter { it.isOpen }.map { V1Universe.normalize(it.symbol) }
                val watchedSymbol = V1Universe.normalize(_activeRiskSymbol.value)
                val symbolsToPoll = (openSymbols + watchedSymbol).filter(V1Universe::valid).distinct()
                for (symbol in symbolsToPoll) {
                    val tick = LiveQuoteFetcher.fetchLiveTick(symbol)
                    if (tick != null) {
                        applyIncomingTick(symbol, tick)
                        container.settingsStore.setLastSync(symbol, at = tick.at)
                    }
                }
                val delayMs = if (openSymbols.isNotEmpty()) 4_000L else 8_000L
                delay(delayMs)
            }
        }
    }

    /**
     * Automatically backfills any offline gap for all open positions + the active symbol,
     * replaying historical 1m/5m candles chronologically to catch any TP/SL crossed while offline.
     */
    fun syncAllOpenAndActiveSymbolsGap(
        hoursOverride: Int? = null,
        notifyOnSuccess: Boolean = true,
    ) {
        viewModelScope.launch {
            _isBackfilling.value = true
            try {
                val openSymbols = trades.value.filter { it.isOpen }.map { V1Universe.normalize(it.symbol) }
                val active = V1Universe.normalize(_activeRiskSymbol.value)
                val targets = (openSymbols + active).filter(V1Universe::valid).distinct()
                var totalTriggered = 0
                var lastSummary: CandleBackfillSummary? = null

                for (symbol in targets) {
                    val summaryAndHits = backfillSingleSymbolInternal(symbol, hoursOverride)
                    if (summaryAndHits != null) {
                        if (symbol == active || lastSummary == null) {
                            lastSummary = summaryAndHits.first
                        }
                        totalTriggered += summaryAndHits.second
                    }
                }

                if (notifyOnSuccess && totalTriggered == 0 && lastSummary != null) {
                    _toast.value = "✅ ${lastSummary.candlesCount} کندل ساعات قطعی (${lastSummary.symbol}) بازیابی و تا این لحظه همگام شد"
                }
            } finally {
                _isBackfilling.value = false
            }
        }
    }

    private suspend fun backfillSingleSymbolInternal(
        symbolInput: String,
        hoursOverride: Int? = null,
    ): Pair<CandleBackfillSummary, Int>? {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return null
        val now = System.currentTimeMillis()
        val earliestOpenTradeAt = trades.value
            .filter { it.isOpen && V1Universe.normalize(it.symbol) == symbol }
            .minOfOrNull { it.openedAt }
        val lastSynced = container.settingsStore.getLastSync(symbol)
        val defaultSixHoursAgo = now - 6L * 3600_000L

        val sinceMillis = when {
            hoursOverride != null && hoursOverride > 0 -> now - hoursOverride * 3600_000L
            earliestOpenTradeAt != null && lastSynced != null -> minOf(earliestOpenTradeAt, lastSynced)
            earliestOpenTradeAt != null -> earliestOpenTradeAt
            lastSynced != null && (now - lastSynced) in (2 * 60_000L)..(5L * 24L * 3600_000L) -> lastSynced
            else -> defaultSixHoursAgo
        }

        val fetched = LiveQuoteFetcher.fetchGapCandles(symbol, sinceMillis, now) ?: return null
        val (candles, provider) = fetched
        val summary = RiskAndCalendarEngine.summarizeBackfill(
            symbolInput = symbol,
            requestedFromMillis = sinceMillis,
            candles = candles,
            provider = provider,
            syncedAt = now,
        ) ?: return null

        _backfillSummaries.value = _backfillSummaries.value + (symbol to summary)

        // Chronologically replay the gap candles against open trades for this symbol
        val autoClosed = container.journalStore.onBackfillCandles(symbol, candles)
        if (autoClosed.isNotEmpty()) {
            val first = autoClosed.first()
            val pnlStr = String.format(Locale.US, "%s$%.2f", if (first.netPnlUsd >= 0) "+" else "", first.netPnlUsd)
            _toast.value = when (first.kind) {
                AutoExitKind.TAKE_PROFIT ->
                    "🎯 در ساعات قطعی، ${first.symbol} به حد سود (TP) رسیده و خودکار در ژورنال ثبت شد ($pnlStr)"
                AutoExitKind.STOP_LOSS ->
                    "🛑 در ساعات قطعی، ${first.symbol} به حد ضرر (SL) رسیده و خودکار در ژورنال ثبت شد ($pnlStr)"
            }
        }

        // Also update the latest price tick from the newest candle close + live quote
        val liveTick = LiveQuoteFetcher.fetchLiveTick(symbol) ?: PriceTick(
            price = summary.lastClose,
            at = now,
            isTradeTick = true,
            provider = provider,
        )
        applyIncomingTick(symbol, liveTick)
        container.settingsStore.setLastSync(symbol, at = now)

        return summary to autoClosed.size
    }

    private fun applyIncomingTick(symbolInput: String, tick: PriceTick) {
        val symbol = V1Universe.normalize(symbolInput)
        _liveTicks.value = _liveTicks.value + (symbol to tick)

        // Update display marks for open trades of this symbol
        var marks = _lastDisplayMarks.value
        trades.value.filter { it.isOpen && V1Universe.normalize(it.symbol) == symbol }.forEach { trade ->
            marks = OpenPositionDisplay.record(marks, trade, tick, tick.at)
        }
        _lastDisplayMarks.value = marks

        // Automatically evaluate TP / SL hits and close triggered trades
        val autoClosed = container.journalStore.onLiveTick(symbol, tick)
        if (autoClosed.isNotEmpty()) {
            val first = autoClosed.first()
            val pnlStr = String.format(Locale.US, "%s$%.2f", if (first.netPnlUsd >= 0) "+" else "", first.netPnlUsd)
            _toast.value = when (first.kind) {
                AutoExitKind.TAKE_PROFIT -> "🎯 معامله ${first.symbol} با برخورد خودکار به حد سود (TP) بسته شد ($pnlStr)"
                AutoExitKind.STOP_LOSS -> "🛑 معامله ${first.symbol} با برخورد خودکار به حد ضرر (SL) بسته شد ($pnlStr)"
            }
        }
    }

    fun selectRiskSymbol(symbolInput: String) {
        val normalized = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(normalized)) return
        _activeRiskSymbol.value = normalized
        refreshSymbolLivePrice(normalized)
        if (_backfillSummaries.value[normalized] == null) {
            viewModelScope.launch {
                backfillSingleSymbolInternal(normalized, hoursOverride = 6)
            }
        }
    }

    fun refreshSymbolLivePrice(symbolInput: String = _activeRiskSymbol.value, onLoaded: ((PriceTick?) -> Unit)? = null) {
        val normalized = V1Universe.normalize(symbolInput)
        viewModelScope.launch {
            val tick = LiveQuoteFetcher.fetchLiveTick(normalized)
            if (tick != null) {
                applyIncomingTick(normalized, tick)
                container.settingsStore.setLastSync(normalized, at = tick.at)
            }
            onLoaded?.invoke(tick)
        }
    }

    fun saveRiskAndCapitalSettings(
        accountBalance: Double,
        initialCapital: Double,
        riskPercent: Double,
        dailyLossLimitPercent: Double,
        maxDrawdownLimitPercent: Double,
    ) {
        if (!accountBalance.isFinite() || accountBalance < 10.0) {
            _toast.value = "موجودی حساب باید حداقل ۱۰ دلار باشد"
            return
        }
        val safeInitial = if (initialCapital.isFinite() && initialCapital >= 10.0) initialCapital else accountBalance
        val safeRisk = riskPercent.coerceIn(0.1, 10.0)
        val safeDaily = dailyLossLimitPercent.coerceIn(0.5, 25.0)
        val safeMaxDd = maxDrawdownLimitPercent.coerceIn(1.0, 50.0)
        container.settingsStore.update {
            it.copy(
                accountBalance = accountBalance,
                initialCapital = safeInitial,
                riskPercent = safeRisk,
                dailyLossLimitPercent = safeDaily,
                maxDrawdownLimitPercent = safeMaxDd,
            )
        }
        _toast.value = "تنظیمات مدیریت سرمایه و ریسک ذخیره شد"
    }

    fun openAutoTrackedTrade(calc: RiskCalculationResult, note: String = "") {
        val now = System.currentTimeMillis()
        val trade = container.journalStore.openTrade(calc, note = note, now = now)
        _liveTicks.value[trade.symbol]?.let { tick ->
            _lastDisplayMarks.value = OpenPositionDisplay.record(_lastDisplayMarks.value, trade, tick, now)
        }
        refreshSymbolLivePrice(trade.symbol)
        _toast.value = "⚡ معامله ${trade.symbol} ثبت شد؛ رصد خودکار قیمت لحظه‌ای و کندل‌های قطعی فعال است"
    }

    fun closeTradeManually(
        tradeId: String,
        exitPrice: Double,
        reason: String = "بستن با قیمت لحظه‌ای",
    ) {
        val closed = container.journalStore.closeTradeAtPrice(
            tradeId = tradeId,
            exitPrice = exitPrice,
            reason = reason,
            now = System.currentTimeMillis(),
        )
        if (closed != null) {
            val pnl = closed.pnlUsd ?: 0.0
            val pnlStr = String.format(Locale.US, "%s$%.2f", if (pnl >= 0) "+" else "", pnl)
            _toast.value = "معامله ${closed.symbol} بسته و در تقویم ژورنال ثبت شد ($pnlStr)"
        }
    }

    fun deleteJournalTrade(tradeId: String) {
        container.journalStore.deleteTrade(tradeId)
        _toast.value = "معامله از ژورنال حذف شد"
    }

    fun clearAllJournalTrades() {
        container.journalStore.clear()
        _toast.value = "تمام سوابق ژورنال پاک شد"
    }

    fun dayOpeningEquity(now: Long = System.currentTimeMillis()): Double =
        runCatching { container.settingsStore.dayOpeningEquity(now) }
            .getOrDefault(settings.value.accountBalance)

    fun resumeVisibleForexFeed() {
        syncAllOpenAndActiveSymbolsGap(notifyOnSuccess = false)
        startLiveMonitor()
    }

    fun pauseInvisibleForexFeed() = Unit

    fun checkForAppUpdate(autoDownload: Boolean = settings.value.autoDownloadUpdates, force: Boolean = true) {
        viewModelScope.launch {
            container.updater.checkForUpdate(autoDownload, force)
            if (autoDownload && container.updater.state.value.downloadedApkPath != null) {
                container.updater.installDownloaded(openPermissionSettings = false)
            }
        }
    }

    fun downloadAppUpdate() {
        viewModelScope.launch {
            container.updater.downloadAvailable()
            if (container.updater.state.value.downloadedApkPath != null) container.updater.installDownloaded()
        }
    }

    fun setAutoDownloadUpdates(enabled: Boolean) {
        container.settingsStore.update { it.copy(autoDownloadUpdates = enabled) }
        if (enabled) checkForAppUpdate(autoDownload = true, force = false)
        _toast.value = if (enabled)
            "بررسی و دانلود خودکار فعال شد؛ نصب نهایی با تأیید Android انجام می‌شود"
        else "دانلود خودکار بروزرسانی خاموش شد"
    }

    fun installDownloadedUpdate() = container.updater.installDownloaded()

    fun resumeUpdateInstall() = container.updater.resumePendingInstall()

    fun openUpdateInstallPermission() = container.updater.openInstallPermissionSettings()

    fun setAutoPaperTrading(enabled: Boolean) {
        container.settingsStore.update { it.copy(autoPaperTrading = enabled) }
        _toast.value = if (enabled) "معاملهٔ خودکار کاغذی روشن شد" else "معاملهٔ خودکار کاغذی خاموش شد"
    }

    fun consumeToast() {
        _toast.value = null
    }
}

class AurumViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AurumViewModel(container) as T
}
