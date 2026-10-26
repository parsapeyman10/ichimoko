package com.aurum.edge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CandleBackfillSummary
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.AiTradeVerdict
import com.aurum.edge.data.AvalAiAdvisor
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.JournalStats
import com.aurum.edge.data.LiveQuoteFetcher
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.PairScanStatus
import java.util.Locale
import java.util.UUID
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
    private val _pairScan = MutableStateFlow(PairScanState())
    val pairScan: StateFlow<PairScanState> = _pairScan.asStateFlow()
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

    private val _aiChecking = MutableStateFlow(false)
    val aiChecking: StateFlow<Boolean> = _aiChecking.asStateFlow()

    private val _lastAiVerdict = MutableStateFlow<AiTradeVerdict?>(null)
    val lastAiVerdict: StateFlow<AiTradeVerdict?> = _lastAiVerdict.asStateFlow()

    private val _btcStrategyReport = MutableStateFlow<StrategyEvaluationReport?>(null)
    val btcStrategyReport: StateFlow<StrategyEvaluationReport?> = _btcStrategyReport.asStateFlow()

    private val _isScanningBtcStrategy = MutableStateFlow(false)
    val isScanningBtcStrategy: StateFlow<Boolean> = _isScanningBtcStrategy.asStateFlow()

    private val _activeRiskSymbol = MutableStateFlow(container.settingsStore.read().symbol)
    val activeRiskSymbol: StateFlow<String> = _activeRiskSymbol.asStateFlow()

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var updateCheckStarted = false
    private var liveMonitorJob: Job? = null
    private var lastBtcStrategyEvalMillis: Long = 0L
    private var lastAutoFiredBarTimeByStrategy: Map<String, Long> = emptyMap()

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
                val symbolsToPoll = (openSymbols + watchedSymbol + IchimokuStrategyEngine.BTC_SYMBOL)
                    .filter(V1Universe::valid).distinct()
                for (symbol in symbolsToPoll) {
                    val tick = LiveQuoteFetcher.fetchLiveTick(symbol)
                    if (tick != null) {
                        applyIncomingTick(symbol, tick)
                        container.settingsStore.setLastSync(symbol, at = tick.at)
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastBtcStrategyEvalMillis >= 25_000L || _btcStrategyReport.value == null) {
                    evaluateActiveBitcoinStrategyInternal(userInitiated = false)
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

    fun setAvalAiEnabled(enabled: Boolean) {
        val current = settings.value
        if (enabled && current.newsAiApiKey.isBlank()) {
            _toast.value = "ابتدا کلید API سایت AvalAI را وارد و ذخیره کنید، سپس نظر هوش مصنوعی را فعال نمایید"
        }
        container.settingsStore.update { it.copy(avalAiEnabled = enabled) }
        if (!enabled) {
            _toast.value = "بررسی هوش مصنوعی قبل از معامله غیرفعال شد"
        } else if (current.newsAiApiKey.isNotBlank()) {
            _toast.value = "🤖 نظر هوش مصنوعی AvalAI فعال شد؛ قبل از هر خرید یا فروش بررسی می‌شود آیا وقت معامله هست یا خیر"
        }
    }

    fun saveAvalAiConfig(
        apiKey: String,
        model: String = AvalAiAdvisor.DEFAULT_MODEL,
        baseUrl: String = AvalAiAdvisor.DEFAULT_BASE_URL,
        enableAfterSave: Boolean = true,
    ) {
        val cleanKey = apiKey.trim()
        if (cleanKey.any { it.isWhitespace() }) {
            _toast.value = "کلید API نباید شامل فاصله (Space) باشد"
            return
        }
        val cleanModel = model.trim().ifBlank { AvalAiAdvisor.DEFAULT_MODEL }
        val cleanUrl = baseUrl.trim().ifBlank { AvalAiAdvisor.DEFAULT_BASE_URL }
        container.settingsStore.update {
            it.copy(
                newsAiApiKey = cleanKey,
                newsAiModel = cleanModel,
                newsAiBaseUrl = cleanUrl,
                avalAiEnabled = if (cleanKey.isNotBlank() && enableAfterSave) true else if (cleanKey.isBlank()) false else it.avalAiEnabled,
            )
        }
        _toast.value = if (cleanKey.isNotBlank()) {
            "✅ کلید AvalAI و مدل $cleanModel ذخیره و فعال شد"
        } else {
            "کلید هوش مصنوعی پاک شد و فیلتر غیرفعال گردید"
        }
    }

    fun checkTradeTimingWithAi(
        calc: RiskCalculationResult,
        strategyContext: String = "",
    ) {
        val cfg = settings.value
        if (cfg.newsAiApiKey.isBlank()) {
            _toast.value = "لطفاً ابتدا کلید API سایت chat.avalai.ir را در بخش هوش مصنوعی وارد کنید"
            return
        }
        viewModelScope.launch {
            _aiChecking.value = true
            try {
                val now = System.currentTimeMillis()
                val recentCandles = LiveQuoteFetcher.fetchGapCandles(calc.symbol, now - 2 * 3600_000L, now)?.first.orEmpty()
                val res = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = cfg.newsAiApiKey,
                    baseUrl = cfg.newsAiBaseUrl,
                    model = cfg.newsAiModel,
                    symbolInput = calc.symbol,
                    action = calc.action,
                    entry = calc.entry,
                    stopLoss = calc.stopLoss,
                    takeProfit = calc.takeProfit,
                    riskReward = calc.riskReward,
                    recentCandles = recentCandles,
                    strategyContext = strategyContext,
                )
                res.onSuccess { verdict ->
                    _lastAiVerdict.value = verdict
                    _toast.value = if (verdict.approved) {
                        "🤖 هوش مصنوعی (${verdict.confidence}%): الان زمان مناسبی برای ورود است — ${verdict.summaryFa}"
                    } else {
                        "🛑 هوش مصنوعی (${verdict.confidence}%): الان وقت معامله نیست — ${verdict.summaryFa}"
                    }
                }.onFailure { err ->
                    _toast.value = "⚠️ خطا در ارتباط با هوش مصنوعی AvalAI: ${err.message}"
                }
            } finally {
                _aiChecking.value = false
            }
        }
    }

    fun openAutoTrackedTrade(
        calc: RiskCalculationResult,
        note: String = "",
        strategyContext: String = "",
    ) {
        val cfg = settings.value
        if (!cfg.avalAiEnabled) {
            executeOpenTradeNow(calc, note)
            return
        }

        if (cfg.newsAiApiKey.isBlank()) {
            _toast.value = "⚠️ نظر هوش مصنوعی فعال است اما کلید API (AvalAI) وارد نشده؛ لطفاً کلید را وارد کنید یا کلید هوش مصنوعی را خاموش کنید."
            return
        }

        viewModelScope.launch {
            _aiChecking.value = true
            try {
                val now = System.currentTimeMillis()
                val recentCandles = LiveQuoteFetcher.fetchGapCandles(calc.symbol, now - 2 * 3600_000L, now)?.first.orEmpty()
                val res = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = cfg.newsAiApiKey,
                    baseUrl = cfg.newsAiBaseUrl,
                    model = cfg.newsAiModel,
                    symbolInput = calc.symbol,
                    action = calc.action,
                    entry = calc.entry,
                    stopLoss = calc.stopLoss,
                    takeProfit = calc.takeProfit,
                    riskReward = calc.riskReward,
                    recentCandles = recentCandles,
                    strategyContext = strategyContext,
                )
                res.onSuccess { verdict ->
                    _lastAiVerdict.value = verdict
                    if (verdict.approved) {
                        val combinedNote = buildString {
                            if (note.isNotBlank()) append(note.trim()).append(" · ")
                            append("🤖 تایید AI (${verdict.confidence}%): ${verdict.summaryFa}")
                        }
                        executeOpenTradeNow(calc, combinedNote)
                    } else {
                        _toast.value = "🛑 هوش مصنوعی مانع ورود شد (${verdict.confidence}%): الان وقت معامله نیست — ${verdict.summaryFa}"
                    }
                }.onFailure { err ->
                    _toast.value = "⚠️ خطا در استعلام هوش مصنوعی AvalAI: ${err.message}"
                }
            } finally {
                _aiChecking.value = false
            }
        }
    }

    private fun executeOpenTradeNow(calc: RiskCalculationResult, note: String) {
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
        val activeTitle = settings.value.activeBitcoinStrategyTitleFa()
        _toast.value = if (enabled) {
            "✅ اجرای خودکار «$activeTitle» روی BTC/USDT روشن شد (فقط این متود فعال است)"
        } else {
            "اجرای خودکار استراتژی بیت‌کوین خاموش شد"
        }
        if (enabled) {
            scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        }
    }

    /**
     * انتخاب و فعال‌سازی انحصاری یک استراتژی:
     * طبق قانون سیستم، فقط یک متود در هر لحظه قابل اجراست و با فعال‌سازی استراتژی جدید،
     * استراتژی قبلی بلافاصله غیرفعال می‌شود.
     */
    fun activateSingleBitcoinStrategy(strategyId: String, enableExecution: Boolean = true) {
        val prevTitle = settings.value.activeBitcoinStrategyTitleFa()
        val prevId = settings.value.selectedBitcoinStrategyId
        container.settingsStore.update {
            it.copy(
                selectedBitcoinStrategyId = strategyId,
                autoPaperTrading = if (enableExecution) true else it.autoPaperTrading,
            )
        }
        val newTitle = settings.value.activeBitcoinStrategyTitleFa()
        _toast.value = if (prevId != strategyId) {
            "✅ «$newTitle» فعال شد و استراتژی قبلی («$prevTitle») غیرفعال گردید"
        } else {
            "✅ «$newTitle» به‌عنوان تنها استراتژی فعال روی BTC/USDT تنظیم شد"
        }
        scanAndEvaluateBitcoinStrategyNow(userInitiated = true)
    }

    fun updateBaseStrategyConfig(newConfig: BaseStrategyConfig) {
        container.settingsStore.update { current ->
            val customSlot = current.activeCustomStrategySlot()
            if (customSlot != null) {
                current.copy(
                    customStrategies = current.customStrategies.map {
                        if (it.id == customSlot.id) it.copy(config = newConfig) else it
                    }
                )
            } else {
                current.copy(baseStrategyConfig = newConfig)
            }
        }
        _toast.value = "✅ تنظیمات استراتژی ذخیره شد؛ در حال بازسنجی چارت BTC/USDT..."
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
    }

    fun addCustomStrategyModel(
        titleFa: String,
        summaryFa: String,
        baseModel: BitcoinStrategyModel = BitcoinStrategyModel.BASE_ICHIMOKU,
        activateImmediately: Boolean = true,
    ) {
        val cleanTitle = titleFa.trim()
        if (cleanTitle.isEmpty()) {
            _toast.value = "لطفاً نام استراتژی جدید را وارد کنید"
            return
        }
        val slot = CustomStrategySlot(
            id = "CUSTOM_${UUID.randomUUID().toString().take(8).uppercase(Locale.ROOT)}",
            titleFa = cleanTitle,
            badgeFa = "مدل استراتژی سفارشی",
            summaryFa = summaryFa.trim().ifBlank { "مبتنی بر ${baseModel.titleFa} روی بیت‌کوین (BTC/USDT)" },
            baseModelId = baseModel.id,
            config = settings.value.baseStrategyConfig,
        )
        container.settingsStore.update { current ->
            current.copy(
                customStrategies = current.customStrategies + slot,
                selectedBitcoinStrategyId = if (activateImmediately) slot.id else current.selectedBitcoinStrategyId,
                autoPaperTrading = if (activateImmediately) true else current.autoPaperTrading,
            )
        }
        _toast.value = if (activateImmediately) {
            "✅ استراتژی «$cleanTitle» اضافه و فعال شد (استراتژی قبلی غیرفعال گردید)"
        } else {
            "✅ استراتژی «$cleanTitle» به لیست استراتژی‌ها اضافه شد"
        }
        if (activateImmediately) {
            scanAndEvaluateBitcoinStrategyNow(userInitiated = true)
        }
    }

    fun deleteCustomStrategyModel(strategyId: String) {
        container.settingsStore.update { current ->
            val remaining = current.customStrategies.filterNot { it.id == strategyId }
            val nextActiveId = if (current.selectedBitcoinStrategyId == strategyId) {
                BitcoinStrategyModel.BASE_ICHIMOKU.id
            } else {
                current.selectedBitcoinStrategyId
            }
            current.copy(
                customStrategies = remaining,
                selectedBitcoinStrategyId = nextActiveId,
            )
        }
        _toast.value = "استراتژی سفارشی حذف شد"
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
    }

    fun scanAndEvaluateBitcoinStrategyNow(userInitiated: Boolean = true) {
        viewModelScope.launch {
            evaluateActiveBitcoinStrategyInternal(userInitiated = userInitiated)
        }
    }

    private suspend fun evaluateActiveBitcoinStrategyInternal(userInitiated: Boolean) {
        if (_isScanningBtcStrategy.value) return
        _isScanningBtcStrategy.value = true
        try {
            val cfg = settings.value
            val activeModel = cfg.activeBitcoinStrategyModel()
            val activeCustom = cfg.activeCustomStrategySlot()
            val stratConfig = cfg.activeBitcoinStrategyConfig()
            val now = System.currentTimeMillis()

            val macroBars = LiveQuoteFetcher.fetchTimeframeCandles(
                symbolInput = IchimokuStrategyEngine.BTC_SYMBOL,
                interval = stratConfig.macroInterval,
                limit = 110,
                nowMillis = now,
            )?.first.orEmpty()

            val strategyBars = LiveQuoteFetcher.fetchTimeframeCandles(
                symbolInput = IchimokuStrategyEngine.BTC_SYMBOL,
                interval = stratConfig.strategyInterval,
                limit = 110,
                nowMillis = now,
            )?.first.orEmpty()

            val triggerBars = LiveQuoteFetcher.fetchTimeframeCandles(
                symbolInput = IchimokuStrategyEngine.BTC_SYMBOL,
                interval = stratConfig.triggerInterval,
                limit = 110,
                nowMillis = now,
            )?.first.orEmpty()

            val liveTick = LiveQuoteFetcher.fetchLiveTick(IchimokuStrategyEngine.BTC_SYMBOL)
                ?: _liveTicks.value[IchimokuStrategyEngine.BTC_SYMBOL]
            if (liveTick != null) {
                applyIncomingTick(IchimokuStrategyEngine.BTC_SYMBOL, liveTick)
            }

            val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
                activeModel = activeModel,
                config = stratConfig,
                macroCandles = macroBars,
                strategyCandles = strategyBars,
                triggerCandles = triggerBars,
                livePriceOverride = liveTick?.price,
                customTitleOverride = activeCustom?.titleFa,
                customIdOverride = activeCustom?.id,
                nowMillis = now,
            )

            _btcStrategyReport.value = report
            lastBtcStrategyEvalMillis = now

            // Update PairScanState for Home Screen AssetClass4SlotsBanner
            val scanStateCode = when (report.readiness) {
                StrategyReadinessState.SIGNAL_BUY, StrategyReadinessState.SIGNAL_SELL -> "candidate"
                StrategyReadinessState.WATCH_SETUP -> "watch"
                StrategyReadinessState.BLOCKED -> "no_signal"
                StrategyReadinessState.INSUFFICIENT_DATA -> "partial"
            }
            _pairScan.value = PairScanState(
                statuses = listOf(
                    PairScanStatus(
                        symbol = IchimokuStrategyEngine.BTC_SYMBOL,
                        state = scanStateCode,
                        detail = report.summaryFa,
                        interval = report.triggerInterval,
                        lastScanAt = now,
                        action = report.action,
                    )
                ),
                sweeping = false,
                checkedCount = 1,
                totalCount = 1,
            )

            // 1. Dynamic trade management (Preserve Mode / Kijun Trail / Break-Even) on open BTC trades
            val openBtcTrades = trades.value.filter {
                it.isOpen && V1Universe.normalize(it.symbol) == IchimokuStrategyEngine.BTC_SYMBOL
            }
            for (openTrade in openBtcTrades) {
                val preserveUpdate = IchimokuStrategyEngine.evaluateOpenTradeManagement(
                    trade = openTrade,
                    report = report,
                    config = stratConfig,
                )
                if (preserveUpdate != null) {
                    container.journalStore.updateTradeStopLoss(
                        tradeId = openTrade.id,
                        newStopLoss = preserveUpdate.newStopLoss,
                        stageNote = preserveUpdate.stageTitleFa,
                    )
                    _toast.value = "${preserveUpdate.stageTitleFa}: استاپ ${openTrade.symbol} به ${preserveUpdate.newStopLoss} منتقل شد (${preserveUpdate.reasonFa})"
                }
            }

            // 2. If user clicked manual scan, show feedback
            if (userInitiated) {
                _toast.value = report.summaryFa
            }

            // 3. Automated trade firing if strategy execution is enabled & signal is actionable
            if (cfg.autoPaperTrading && report.isActionable && openBtcTrades.isEmpty()) {
                val lastFiredBar = lastAutoFiredBarTimeByStrategy[report.strategyId] ?: 0L
                if (report.triggerBarTime > 0L && report.triggerBarTime != lastFiredBar) {
                    val entry = report.suggestedEntry ?: return
                    val sl = report.suggestedStopLoss ?: return
                    val tp = report.primaryTakeProfit ?: return
                    val calc = RiskAndCalendarEngine.calculatePosition(
                        symbolInput = IchimokuStrategyEngine.BTC_SYMBOL,
                        accountBalance = cfg.accountBalance,
                        riskPercent = cfg.riskPercent,
                        entry = entry,
                        stopLoss = sl,
                        takeProfit = tp,
                        preferredAction = report.action,
                    ) ?: return

                    val limits = RiskAndCalendarEngine.evaluateRiskLimits(
                        accountBalance = cfg.accountBalance,
                        initialCapital = cfg.initialCapital,
                        dayOpeningEquity = dayOpeningEquity(now),
                        dailyLossLimitPercent = cfg.dailyLossLimitPercent,
                        maxDrawdownLimitPercent = cfg.maxDrawdownLimitPercent,
                        trades = trades.value,
                        now = now,
                    )
                    if (!limits.canOpenTrade(calc.riskBudgetUsd)) {
                        return
                    }

                    lastAutoFiredBarTimeByStrategy = lastAutoFiredBarTimeByStrategy + (report.strategyId to report.triggerBarTime)
                    val autoNote = "⚡ خودکار (${report.strategyTitleFa}) · ${report.triggerType.titleFa}"
                    openAutoTrackedTrade(
                        calc = calc,
                        note = autoNote,
                        strategyContext = report.toAiStrategyContext(),
                    )
                }
            }
        } finally {
            _isScanningBtcStrategy.value = false
        }
    }

    fun consumeToast() {
        _toast.value = null
    }
}

class AurumViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AurumViewModel(container) as T
}
