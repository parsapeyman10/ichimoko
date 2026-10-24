package com.aurum.edge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
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
                    }
                }
                val delayMs = if (openSymbols.isNotEmpty()) 4_000L else 8_000L
                delay(delayMs)
            }
        }
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
    }

    fun refreshSymbolLivePrice(symbolInput: String = _activeRiskSymbol.value, onLoaded: ((PriceTick?) -> Unit)? = null) {
        val normalized = V1Universe.normalize(symbolInput)
        viewModelScope.launch {
            val tick = LiveQuoteFetcher.fetchLiveTick(normalized)
            if (tick != null) {
                applyIncomingTick(normalized, tick)
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
        // If we already have a recent tick for this symbol, record its initial display mark
        _liveTicks.value[trade.symbol]?.let { tick ->
            _lastDisplayMarks.value = OpenPositionDisplay.record(_lastDisplayMarks.value, trade, tick, now)
        }
        refreshSymbolLivePrice(trade.symbol)
        _toast.value = "⚡ معامله ${trade.symbol} ثبت شد؛ رصد خودکار قیمت لحظه‌ای برای TP و SL فعال است"
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
