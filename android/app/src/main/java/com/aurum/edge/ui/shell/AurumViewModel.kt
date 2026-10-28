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
import com.aurum.edge.core.DualTimeframeSymbolReport
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.NeuralSelfLearningEngine
import com.aurum.edge.core.NeuralSignalEvaluation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.SymbolPollTier
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.AiTradeVerdict
import com.aurum.edge.data.AvalAiAdvisor
import com.aurum.edge.data.BandwidthSyncStats
import com.aurum.edge.data.DisplayMark
import com.aurum.edge.data.JournalStats
import com.aurum.edge.data.LiveQuoteFetcher
import com.aurum.edge.data.OpenPositionDisplay
import com.aurum.edge.data.PairScanState
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.service.BackgroundTradingService
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val _allSymbolStrategyReports = MutableStateFlow<Map<String, StrategyEvaluationReport>>(emptyMap())
    val allSymbolStrategyReports: StateFlow<Map<String, StrategyEvaluationReport>> = _allSymbolStrategyReports.asStateFlow()

    private val _dualSymbolStrategyReports = MutableStateFlow<Map<String, DualTimeframeSymbolReport>>(emptyMap())
    val dualSymbolStrategyReports: StateFlow<Map<String, DualTimeframeSymbolReport>> = _dualSymbolStrategyReports.asStateFlow()

    private val _focusedStrategySymbol = MutableStateFlow(IchimokuStrategyEngine.BTC_SYMBOL)
    val focusedStrategySymbol: StateFlow<String> = _focusedStrategySymbol.asStateFlow()

    private val _focusedStrategyInterval = MutableStateFlow(Interval.M15)
    val focusedStrategyInterval: StateFlow<Interval> = _focusedStrategyInterval.asStateFlow()

    val bandwidthStats: StateFlow<BandwidthSyncStats> = LiveQuoteFetcher.syncStats

    private val _isScanningBtcStrategy = MutableStateFlow(false)
    val isScanningBtcStrategy: StateFlow<Boolean> = _isScanningBtcStrategy.asStateFlow()

    private val _activeRiskSymbol = MutableStateFlow(container.settingsStore.read().symbol)
    val activeRiskSymbol: StateFlow<String> = _activeRiskSymbol.asStateFlow()

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    val neuralState: StateFlow<NeuralLearningStateSummary> = container.neuralEngine.state
    val neuralLearningState: StateFlow<NeuralLearningStateSummary> = container.neuralEngine.state

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var updateCheckStarted = false
    private var liveMonitorJob: Job? = null
    private var lastScanned15mBucket: Long = -1L
    private var lastScanned5mBucket: Long = -1L
    private var lastMidBarCheckedBucket: Long = -1L
    private var lastAutoFiredBarTimeByKey: Map<String, Long> = emptyMap()

    init {
        container.settingsStore.ensureBalance100kMigrated()
        container.appScope.launch {
            container.journalStore.trades.collect { currentTrades ->
                _stats.value = container.journalStore.stats()
                _lastDisplayMarks.value = OpenPositionDisplay.onlyOpen(_lastDisplayMarks.value, currentTrades)
                container.neuralEngine.syncWithClosedJournalTrades(currentTrades)
                syncBackgroundServiceState()
            }
        }
        syncBackgroundServiceState()
        startLiveMonitor()
    }

    fun startApp(@Suppress("UNUSED_PARAMETER") context: Context) {
        container.settingsStore.ensureBalance100kMigrated()
        com.aurum.edge.data.TradeSnapshotArchiveManager.init(container.appContext)
        com.aurum.edge.core.MasterIchimokuTradingAssistant.init(container.appContext)
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.aurum.edge.data.TradeSnapshotArchiveManager.scanAndRestoreFromPhoneStorage(
                    context = container.appContext,
                    journalStore = container.journalStore,
                )
                container.neuralEngine.restoreBrainBackupFromPhoneStorage(container.appContext)
                // ساخت تصویر آرشیو برای معاملاتی که از قبل باز یا بسته شده‌اند ولی هنوز تصویر ندارند
                val existingIds = com.aurum.edge.data.TradeSnapshotArchiveManager.records.value.map { it.tradeId }.toSet()
                trades.value.take(30).filterNot { it.id in existingIds }.forEach { tr ->
                    val sym = V1Universe.normalize(tr.symbol)
                    val rep = _dualSymbolStrategyReports.value[sym]?.reportFor(tr.interval, tr.strategyId)
                    val candles = LiveQuoteFetcher.peekCachedCandles(sym, tr.interval)
                    com.aurum.edge.data.TradeSnapshotArchiveManager.captureAndArchiveTradeOpen(
                        context = container.appContext,
                        trade = tr,
                        report = rep,
                        triggerCandles = candles,
                    )
                }
            }
        }
        if (!updateCheckStarted) {
            updateCheckStarted = true
            checkForAppUpdate(settings.value.autoDownloadUpdates, force = false)
        }
        syncBackgroundServiceState()
        syncAllOpenAndActiveSymbolsGap(notifyOnSuccess = false)
        startLiveMonitor()
    }

    fun setBackgroundMonitor(enabled: Boolean) {
        container.settingsStore.update { it.copy(backgroundMonitor = enabled) }
        syncBackgroundServiceState()
        _toast.value = if (enabled) {
            "پایش ۲۴/۷ در بک‌گراند فعال شد"
        } else {
            "پایش بک‌گراند غیرفعال شد"
        }
    }

    private fun syncBackgroundServiceState() {
        val cfg = settings.value
        val hasOpenTrades = trades.value.any { it.isOpen }
        val shouldRunBg = cfg.backgroundMonitor || cfg.autoPaperTrading || hasOpenTrades
        BackgroundTradingService.syncServiceState(
            context = container.appContext,
            enabled = shouldRunBg,
            title = "Aurum Edge · رادار ۱۵ نماد (15m + 5m)",
            text = "${cfg.activeBitcoinStrategyTitleFa()} · پوزیشن باز: ${trades.value.count { it.isOpen }}",
        )
    }

    private fun startLiveMonitor() {
        if (liveMonitorJob?.isActive == true) return
        liveMonitorJob = container.appScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val openSymbolSet = trades.value
                    .filter { it.isOpen }
                    .map { V1Universe.normalize(it.symbol) }
                    .toSet()
                val focused = V1Universe.normalize(_focusedStrategySymbol.value)
                val riskWatched = V1Universe.normalize(_activeRiskSymbol.value)
                val currentReports = _allSymbolStrategyReports.value
                val currentTicks = _liveTicks.value

                // ۱. دسته‌بندی هوشمند ۱۵ نماد بر اساس ۴ سطح اولویت (بدون هدررفت اینترنت)
                val dueCrypto = mutableListOf<String>()
                val dueTradFi = mutableListOf<String>()
                var skippedClosed = 0

                for (symbol in V1Universe.allSymbols) {
                    val hasOpen = symbol in openSymbolSet
                    val isFocused = (symbol == focused || symbol == riskWatched)
                    val readiness = currentReports[symbol]?.readiness
                    val tier = Smart15mSyncPlanner.classifySymbolTier(
                        symbol = symbol,
                        hasOpenTrade = hasOpen,
                        isFocusedSymbol = isFocused,
                        readiness = readiness,
                        nowMillis = now,
                    )
                    val lastTickAt = currentTicks[symbol]?.at
                    if (tier == SymbolPollTier.MARKET_CLOSED_PAUSED && lastTickAt != null) {
                        skippedClosed++
                        continue
                    }
                    if (Smart15mSyncPlanner.isTickPollDue(lastTickAt, tier, now)) {
                        if (symbol in V1Universe.cryptoSymbols) {
                            dueCrypto += symbol
                        } else {
                            dueTradFi += symbol
                        }
                    }
                }
                if (skippedClosed > 0) {
                    LiveQuoteFetcher.recordSkippedClosedMarketCheck(skippedClosed)
                }

                // ۲. دریافت یکپارچه ۵ رمزارز در ۱ درخواست واحد (۲۰۰ بایت) در صورت سررسید
                if (dueCrypto.isNotEmpty()) {
                    val batchTicks = LiveQuoteFetcher.fetchCryptoBatchTicks(dueCrypto, now)
                    batchTicks.forEach { (sym, tick) ->
                        applyIncomingTick(sym, tick)
                        container.settingsStore.setLastSync(sym, at = tick.at)
                    }
                }

                // ۳. دریافت موازی تیک نمادهای فارکس/طلا/شاخص که سررسید آن‌ها رسیده و بازارشان باز است
                if (dueTradFi.isNotEmpty()) {
                    coroutineScope {
                        dueTradFi.chunked(5).forEach { batch ->
                            batch.map { symbol ->
                                async {
                                    symbol to LiveQuoteFetcher.fetchLiveTick(symbol)
                                }
                            }.awaitAll().forEach { (symbol, tick) ->
                                if (tick != null) {
                                    LiveQuoteFetcher.applyLiveTickToCachedCandles(symbol, tick)
                                    applyIncomingTick(symbol, tick)
                                    container.settingsStore.setLastSync(symbol, at = tick.at)
                                }
                            }
                        }
                    }
                }

                // ۴. رصد هم‌زمان تایم‌فریم‌های ورود 15m و 5m هماهنگ با بسته شدن کندل ۵ دقیقه و ۱۵ دقیقه (Delta Sync بدون مصرف اضافه دیتا)
                val current15mBucket = Smart15mSyncPlanner.currentCandleBucket(now, Interval.M15)
                val current5mBucket = Smart15mSyncPlanner.currentCandleBucket(now, Interval.M5)
                val currentMidBucket = now / 120_000L // هر ۲ دقیقه چک میان‌کندلی نمادهای آماده ماشه

                if (current15mBucket > lastScanned15mBucket ||
                    current5mBucket > lastScanned5mBucket ||
                    _allSymbolStrategyReports.value.isEmpty()
                ) {
                    evaluateAll15SymbolsStrategyInternal(
                        userInitiated = false,
                        fullSweep = true,
                    )
                    lastScanned15mBucket = current15mBucket
                    lastScanned5mBucket = current5mBucket
                    lastMidBarCheckedBucket = currentMidBucket
                    LiveQuoteFetcher.recordScheduled15mSweep(
                        lastSweepAt = System.currentTimeMillis(),
                        nextSweepAt = Smart15mSyncPlanner.nextTriggerBoundaryCheckMillis(
                            nowMillis = System.currentTimeMillis(),
                            triggerInterval = Interval.M5,
                        ),
                    )
                } else if (currentMidBucket > lastMidBarCheckedBucket) {
                    // چک سریع میان‌کندلی برای شکار اول روند روی نمادهای آماده ماشه و پوزیشن‌های باز
                    lastMidBarCheckedBucket = currentMidBucket
                    evaluateAll15SymbolsStrategyInternal(
                        userInitiated = false,
                        fullSweep = false,
                    )
                }

                delay(4_000L)
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
            val msg = when {
                first.kind == AutoExitKind.TAKE_PROFIT -> "🎯 معامله ${first.symbol} با برخورد به حد سود (TP) بسته شد ($pnlStr)"
                first.netPnlUsd > 0.0 -> "🛡️ معامله ${first.symbol} با قفل سود هوشمند در روند بسته شد ($pnlStr)"
                else -> "🛑 معامله ${first.symbol} با برخورد به حد ضرر (SL) بسته شد ($pnlStr)"
            }
            _toast.value = msg
            BackgroundTradingService.postTradeEventNotification(
                context = container.appContext,
                title = "بسته شدن خودکار ${first.symbol}",
                body = msg,
            )
        }

        // شکار لحظه‌ای نقطه‌زنی ورود (Real-Time Sniper Pocket Fill) روی هر دو تایم‌فریم 15m و 5m و همه استراتژی‌های فعال (با الزام قطعی R:R >= 1:1.5)
        val cfg = settings.value
        val dualReport = _dualSymbolStrategyReports.value[symbol]
        val armedCandidates = dualReport?.sniperArmedReports
            ?.sortedByDescending { it.coreTrendScore }
            ?: listOfNotNull(_allSymbolStrategyReports.value[symbol]).filter { it.isActionable || it.isSniperArmedPendingRetest }
        if (cfg.autoPaperTrading && !com.aurum.edge.core.MarketHours.closedFor(symbol, tick.at)) {
            for (armedReport in armedCandidates.filter { it.isPriceInSniperWindow(tick.price) }) {
                val openAll = trades.value.filter { it.isOpen }
                val hasOpenSameStrategyTf = openAll.any {
                    it.strategyId == armedReport.strategyId &&
                        V1Universe.normalize(it.symbol) == symbol &&
                        it.interval == armedReport.triggerInterval
                }
                val fireKey = "${armedReport.strategyId}|$symbol|${armedReport.triggerInterval.label}"
                val lastFiredBar = lastAutoFiredBarTimeByKey[fireKey] ?: 0L
                if (!hasOpenSameStrategyTf &&
                    armedReport.triggerBarTime > 0L && armedReport.triggerBarTime != lastFiredBar
                ) {
                    val sl = armedReport.suggestedStopLoss
                    val rawTp = armedReport.primaryTakeProfit
                    if (sl != null && rawTp != null) {
                        val enforcedTp = RiskAndCalendarEngine.enforceMinimumRiskReward(
                            symbolInput = symbol,
                            entryPrice = tick.price,
                            stopLoss = sl,
                            takeProfit = rawTp,
                            action = armedReport.action,
                            minRiskReward = RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD,
                        )
                        val calc = RiskAndCalendarEngine.calculatePosition(
                            symbolInput = symbol,
                            accountBalance = cfg.accountBalance,
                            riskPercent = cfg.riskPercent,
                            entry = tick.price,
                            stopLoss = sl,
                            takeProfit = enforcedTp,
                            preferredAction = armedReport.action,
                            fixedRiskUsdOverride = if (cfg.useFixedDollarRisk) cfg.fixedRiskUsd else null,
                        )
                        if (calc != null && calc.riskReward >= RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD) {
                            lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to armedReport.triggerBarTime)
                            val tfLabel = armedReport.triggerInterval.label
                            val sniperNote = "🎯 نقطه‌زنی [تایم $tfLabel] · [${armedReport.strategyTitleFa}] · (R:R 1:${calc.riskReward} · ${armedReport.trendPhase.badgeFa})"
                            openAutoTrackedTrade(
                                calc = calc,
                                note = sniperNote,
                                strategyContext = armedReport.toAiStrategyContext(),
                                entryInterval = armedReport.triggerInterval,
                                strategyId = armedReport.strategyId,
                                strategyTitleFa = armedReport.strategyTitleFa,
                            )
                        }
                    }
                }
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
        targetCapital: Double = settings.value.targetCapital,
        useFixedDollarRisk: Boolean = settings.value.useFixedDollarRisk,
        fixedRiskUsd: Double = settings.value.fixedRiskUsd,
    ) {
        if (!accountBalance.isFinite() || accountBalance < 10.0) {
            _toast.value = "موجودی حساب باید حداقل ۱۰ دلار باشد"
            return
        }
        val safeInitial = if (initialCapital.isFinite() && initialCapital >= 10.0) initialCapital else accountBalance
        val safeTarget = if (targetCapital.isFinite() && targetCapital >= 100.0) targetCapital else 100_000.0
        val safeFixedRisk = if (fixedRiskUsd.isFinite() && fixedRiskUsd >= 0.5) fixedRiskUsd else 5.0
        val safeRisk = riskPercent.coerceIn(0.1, 10.0)
        val safeDaily = dailyLossLimitPercent.coerceIn(0.5, 100.0)
        val safeMaxDd = maxDrawdownLimitPercent.coerceIn(1.0, 100.0)
        container.settingsStore.update {
            it.copy(
                accountBalance = accountBalance,
                initialCapital = safeInitial,
                targetCapital = safeTarget,
                useFixedDollarRisk = useFixedDollarRisk,
                fixedRiskUsd = safeFixedRisk,
                riskPercent = safeRisk,
                dailyLossLimitPercent = safeDaily,
                maxDrawdownLimitPercent = safeMaxDd,
            )
        }
        _toast.value = if (useFixedDollarRisk) {
            "✅ موجودی حساب ($${String.format(Locale.US, "%,.0f", accountBalance)}) با ریسک ثابت $${safeFixedRisk} در هر معامله ذخیره شد"
        } else {
            "تنظیمات مدیریت سرمایه و ریسک ذخیره شد"
        }
    }

    fun setAvalAiEnabled(enabled: Boolean) {
        val current = settings.value
        val effectiveKey = current.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY }
        container.settingsStore.update {
            it.copy(
                avalAiEnabled = enabled,
                newsAiApiKey = effectiveKey,
            )
        }
        _toast.value = if (!enabled) {
            "پرسش بله/نه هوش مصنوعی قبل از معامله غیرفعال شد"
        } else {
            "🤖 هوش مصنوعی AvalAI فعال شد؛ قبل از هر معامله بر اساس شرایط مدل استراتژی پاسخ «بله» یا «نه» می‌دهد"
        }
    }

    fun saveAvalAiConfig(
        apiKey: String,
        model: String = AvalAiAdvisor.DEFAULT_MODEL,
        baseUrl: String = AvalAiAdvisor.DEFAULT_BASE_URL,
        enableAfterSave: Boolean = true,
    ) {
        val cleanKey = apiKey.trim().ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY }
        val (valid, msg) = AvalAiAdvisor.validateApiKeyFormat(cleanKey)
        if (!valid) {
            _toast.value = "⚠️ $msg"
            return
        }
        val cleanModel = model.trim().ifBlank { AvalAiAdvisor.DEFAULT_MODEL }
        val cleanUrl = baseUrl.trim().ifBlank { AvalAiAdvisor.DEFAULT_BASE_URL }
        container.settingsStore.update {
            it.copy(
                newsAiApiKey = cleanKey,
                newsAiModel = cleanModel,
                newsAiBaseUrl = cleanUrl,
                avalAiEnabled = if (enableAfterSave) true else it.avalAiEnabled,
            )
        }
        _toast.value = "✅ $msg · مدل $cleanModel ذخیره شد"
    }

    fun testAvalAiYesNoQuestion() {
        val cfg = settings.value
        val sym = V1Universe.normalize(_focusedStrategySymbol.value)
        val report = _dualSymbolStrategyReports.value[sym]?.primaryReport
            ?: _allSymbolStrategyReports.value[sym]
        val liveP = _liveTicks.value[sym]?.price ?: report?.livePrice ?: 2650.0
        val action = if (report?.action == SignalAction.SELL) SignalAction.SELL else SignalAction.BUY
        val entry = report?.suggestedEntry ?: liveP
        val sl = report?.suggestedStopLoss ?: if (action == SignalAction.BUY) entry * 0.995 else entry * 1.005
        val tp = report?.primaryTakeProfit ?: if (action == SignalAction.BUY) entry * 1.01 else entry * 0.99
        val rr = (kotlin.math.abs(tp - entry) / kotlin.math.abs(entry - sl).coerceAtLeast(1e-8)).coerceIn(0.5, 20.0)
        val stratContext = report?.toAiStrategyContext()
            ?: "مدل استراتژی: ${cfg.activeBitcoinStrategyTitleFa()} · نماد $sym"

        viewModelScope.launch {
            _aiChecking.value = true
            try {
                val res = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = cfg.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY },
                    baseUrl = cfg.newsAiBaseUrl,
                    model = cfg.newsAiModel,
                    symbolInput = sym,
                    action = action,
                    entry = entry,
                    stopLoss = sl,
                    takeProfit = tp,
                    riskReward = rr,
                    strategyContext = stratContext,
                )
                res.onSuccess { verdict ->
                    _lastAiVerdict.value = verdict
                    _toast.value = "🤖 پاسخ هوش مصنوعی ($sym): ${verdict.yesNoLabelFa} — ${verdict.reasonFa}"
                }.onFailure { err ->
                    _toast.value = "⚠️ خطا در تست اتصال کلید AvalAI: ${err.message}"
                }
            } finally {
                _aiChecking.value = false
            }
        }
    }

    fun checkTradeTimingWithAi(
        calc: RiskCalculationResult,
        strategyContext: String = "",
    ) {
        val cfg = settings.value
        val normSym = V1Universe.normalize(calc.symbol)
        val effectiveContext = strategyContext.ifBlank {
            _dualSymbolStrategyReports.value[normSym]?.primaryReport?.toAiStrategyContext()
                ?: _allSymbolStrategyReports.value[normSym]?.toAiStrategyContext()
                ?: "مدل استراتژی: ${cfg.activeBitcoinStrategyTitleFa()}"
        }
        viewModelScope.launch {
            _aiChecking.value = true
            try {
                val now = System.currentTimeMillis()
                val recentCandles = LiveQuoteFetcher.fetchGapCandles(calc.symbol, now - 2 * 3600_000L, now)?.first.orEmpty()
                val res = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = cfg.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY },
                    baseUrl = cfg.newsAiBaseUrl,
                    model = cfg.newsAiModel,
                    symbolInput = calc.symbol,
                    action = calc.action,
                    entry = calc.entry,
                    stopLoss = calc.stopLoss,
                    takeProfit = calc.takeProfit,
                    riskReward = calc.riskReward,
                    recentCandles = recentCandles,
                    strategyContext = effectiveContext,
                )
                res.onSuccess { verdict ->
                    _lastAiVerdict.value = verdict
                    _toast.value = "🤖 پاسخ هوش مصنوعی: ${verdict.yesNoLabelFa} — ${verdict.reasonFa}"
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
        entryInterval: Interval = Interval.M15,
        strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        strategyTitleFa: String = BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
    ) {
        val cfg = settings.value
        if (!cfg.avalAiEnabled) {
            executeOpenTradeNow(calc, note, entryInterval, strategyId, strategyTitleFa)
            return
        }

        val normSym = V1Universe.normalize(calc.symbol)
        val effectiveContext = strategyContext.ifBlank {
            _dualSymbolStrategyReports.value[normSym]?.reportFor(entryInterval, strategyId)?.toAiStrategyContext()
                ?: "مدل استراتژی: $strategyTitleFa · تایم‌فریم ${entryInterval.label}"
        }

        container.appScope.launch {
            _aiChecking.value = true
            try {
                val now = System.currentTimeMillis()
                val recentCandles = LiveQuoteFetcher.fetchGapCandles(calc.symbol, now - 2 * 3600_000L, now)?.first.orEmpty()
                val res = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = cfg.newsAiApiKey.ifBlank { AvalAiAdvisor.PRECONFIGURED_USER_KEY },
                    baseUrl = cfg.newsAiBaseUrl,
                    model = cfg.newsAiModel,
                    symbolInput = calc.symbol,
                    action = calc.action,
                    entry = calc.entry,
                    stopLoss = calc.stopLoss,
                    takeProfit = calc.takeProfit,
                    riskReward = calc.riskReward,
                    recentCandles = recentCandles,
                    strategyContext = effectiveContext,
                )
                res.onSuccess { verdict ->
                    _lastAiVerdict.value = verdict
                    if (verdict.approved) {
                        val combinedNote = buildString {
                            if (note.isNotBlank()) append(note.trim()).append(" · ")
                            append("🤖 AI: بله (${verdict.confidence}%)")
                        }
                        executeOpenTradeNow(calc, combinedNote, entryInterval, strategyId, strategyTitleFa)
                    } else {
                        _toast.value = "🛑 پاسخ هوش مصنوعی «نه» بود — معامله باز نشد: ${verdict.reasonFa}"
                    }
                }.onFailure { err ->
                    _toast.value = "⚠️ خطا در استعلام هوش مصنوعی AvalAI: ${err.message}"
                }
            } finally {
                _aiChecking.value = false
            }
        }
    }

    val tradeSnapshotArchiveRecords: StateFlow<List<com.aurum.edge.data.ArchivedTradeSnapshotRecord>> =
        com.aurum.edge.data.TradeSnapshotArchiveManager.records

    private fun executeOpenTradeNow(
        calc: RiskCalculationResult,
        note: String,
        entryInterval: Interval = Interval.M15,
        strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        strategyTitleFa: String = BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
    ) {
        val now = System.currentTimeMillis()
        val normSym = V1Universe.normalize(calc.symbol)
        val matchedReport = _dualSymbolStrategyReports.value[normSym]
            ?.reportFor(entryInterval, strategyId)
        val matchedBreakdown = matchedReport?.rrBreakdown
        val openedRaw = container.journalStore.openTrade(
            calc = calc,
            note = note,
            now = now,
            entryInterval = entryInterval,
            strategyId = strategyId,
            strategyTitleFa = strategyTitleFa,
            optimalRiskReward = matchedBreakdown?.optimalRiskReward ?: calc.riskReward,
            rrBreakdownFa = matchedBreakdown?.optimalRrReasonFa ?: "",
        )
        val condRecords = matchedReport?.conditions?.map { c ->
            com.aurum.edge.core.PaperConditionRecord(
                name = c.titleFa,
                status = if (c.passed) "CONFIRMED" else "BLOCKED",
                detail = c.detailFa,
            )
        }.orEmpty()
        val trade = if (condRecords.isNotEmpty()) {
            val enriched = openedRaw.copy(entryConditions = condRecords)
            container.journalStore.addTrade(enriched)
            enriched
        } else {
            openedRaw
        }
        _liveTicks.value[trade.symbol]?.let { tick ->
            _lastDisplayMarks.value = OpenPositionDisplay.record(_lastDisplayMarks.value, trade, tick, now)
        }
        // ثبت فوری اسکرین‌شات ایچیموکو (کندل + کومو + آینده ۲۶ کندلی + باکس SL/TP + شروط ۱۶گانه) در آرشیو اپ و حافظه گوشی
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cachedCandles = LiveQuoteFetcher.peekCachedCandles(normSym, entryInterval)
            com.aurum.edge.data.TradeSnapshotArchiveManager.captureAndArchiveTradeOpen(
                context = container.appContext,
                trade = trade,
                report = matchedReport,
                triggerCandles = cachedCandles,
            )
        }
        refreshSymbolLivePrice(trade.symbol)
        syncBackgroundServiceState()
        val msg = "⚡ معامله ${trade.symbol} [تایم ${trade.interval.label}] · [${trade.strategyTitleFa}] (${if (trade.action == com.aurum.edge.domain.SignalAction.BUY) "خرید" else "فروش"}) باز شد · 📸 اسکرین‌شات ایچیموکو در آرشیو و گوشی ذخیره شد"
        _toast.value = msg
        BackgroundTradingService.postTradeEventNotification(
            context = container.appContext,
            title = "باز شدن معامله ${trade.symbol} (${trade.strategyTitleFa})",
            body = msg,
        )
    }

    fun captureOrRefreshTradeSnapshot(trade: PaperTrade) {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sym = V1Universe.normalize(trade.symbol)
            val rep = _dualSymbolStrategyReports.value[sym]?.reportFor(trade.interval, trade.strategyId)
            val candles = LiveQuoteFetcher.peekCachedCandles(sym, trade.interval)
            val rec = com.aurum.edge.data.TradeSnapshotArchiveManager.captureAndArchiveTradeOpen(
                context = container.appContext,
                trade = trade,
                report = rep,
                triggerCandles = candles,
            )
            if (rec != null) {
                val kb = (rec.imageSizeBytes / 1024L).coerceAtLeast(1L)
                _toast.value = "📸 تصویر ایچیموکو معامله ${trade.symbol} (${kb}KB WebP) در آرشیو و حافظه گوشی ذخیره شد"
            }
        }
    }

    fun restoreAllDataAndSnapshotsFromPhone() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val restoredSnapshots = com.aurum.edge.data.TradeSnapshotArchiveManager.scanAndRestoreFromPhoneStorage(
                context = container.appContext,
                journalStore = container.journalStore,
            )
            val restoredBrain = container.neuralEngine.restoreBrainBackupFromPhoneStorage(container.appContext)
            container.neuralEngine.syncWithClosedJournalTrades(trades.value)
            _toast.value = buildString {
                append("🔄 بازخوانی از حافظه گوشی انجام شد: ")
                append("${com.aurum.edge.data.TradeSnapshotArchiveManager.records.value.size} تصویر آرشیو")
                if (restoredSnapshots > 0) append(" ($restoredSnapshots جدید)")
                if (restoredBrain) append(" + حافظه شبکه عصبی بازیابی شد")
            }
        }
    }

    fun importSnapshotFromPhoneUri(uri: android.net.Uri) {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rec = com.aurum.edge.data.TradeSnapshotArchiveManager.importSnapshotFromUri(
                context = container.appContext,
                uri = uri,
                journalStore = container.journalStore,
            )
            if (rec != null) {
                container.neuralEngine.syncWithClosedJournalTrades(trades.value)
                _toast.value = "✅ تصویر و دیتای کامل معامله ${rec.symbol} (${rec.strategyTitleFa}) از گوشی بازیابی شد"
            } else {
                _toast.value = "⚠️ فایل انتخابی حاوی متادیتای آرشیو Aurum Edge نبود"
            }
        }
    }

    fun backupNeuralBrainToPhoneNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = container.neuralEngine.saveBrainBackupToPhoneStorage(container.appContext)
            _toast.value = if (ok) {
                "💾 حافظه کامل شبکه عصبی در Download/AurumEdge/Archive/AurumNeuralBrain_Backup.json ذخیره شد"
            } else {
                "💾 حافظه شبکه عصبی در حافظه داخلی اپ همگام‌سازی شد"
            }
        }
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
        container.settingsStore.update {
            it.copy(
                autoPaperTrading = enabled,
                backgroundMonitor = if (enabled) true else it.backgroundMonitor,
            )
        }
        syncBackgroundServiceState()
        val activeTitle = settings.value.activeBitcoinStrategyTitleFa()
        _toast.value = if (enabled) {
            "✅ معامله خودکار و پایش بک‌گراند «$activeTitle» فعال شد"
        } else {
            "معامله خودکار خاموش شد"
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
        container.settingsStore.update {
            val nextEnabled = it.enabledStrategyIds + strategyId
            it.copy(
                selectedBitcoinStrategyId = strategyId,
                enabledStrategyIds = nextEnabled,
                autoPaperTrading = if (enableExecution) true else it.autoPaperTrading,
            )
        }
        val focused = V1Universe.normalize(_focusedStrategySymbol.value)
        val focusedTf = _focusedStrategyInterval.value
        _dualSymbolStrategyReports.value[focused]?.reportFor(focusedTf, strategyId)?.let {
            _btcStrategyReport.value = it
        }
        _toast.value = "✅ استراتژی منتخب برای نمایش چک‌لیست تغییر کرد (اجرای هم‌زمان استراتژی‌ها فعال است)"
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
    }

    fun setRunAllStrategiesSimultaneously(enabled: Boolean) {
        container.settingsStore.update { current ->
            val allIds = (BitcoinStrategyModel.entries.map { it.id } + current.customStrategies.map { it.id }).toSet()
            current.copy(
                runAllStrategiesSimultaneously = enabled,
                enabledStrategyIds = if (enabled && current.enabledStrategyIds.isEmpty()) allIds else current.enabledStrategyIds,
            )
        }
        _toast.value = if (enabled) {
            "✅ اجرای هم‌زمان همه استراتژی‌ها فعال شد؛ معاملات در ژورنال به تفکیک استراتژی دسته‌بندی می‌شوند"
        } else {
            "حالت تک‌استراتژی فعال شد"
        }
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
    }

    fun toggleStrategyEnabledInMultiMode(strategyId: String, enabled: Boolean) {
        container.settingsStore.update { current ->
            val updated = if (enabled) {
                current.enabledStrategyIds + strategyId
            } else {
                val candidate = current.enabledStrategyIds - strategyId
                if (candidate.isEmpty()) setOf(strategyId) else candidate
            }
            current.copy(
                enabledStrategyIds = updated,
                selectedBitcoinStrategyId = if (enabled) strategyId else current.selectedBitcoinStrategyId,
            )
        }
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
    }

    fun updateBaseStrategyConfig(newConfig: BaseStrategyConfig) {
        val safeConfig = newConfig.copy(
            minRiskReward = kotlin.math.max(newConfig.minRiskReward, RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD),
        )
        container.settingsStore.update { current ->
            val customSlot = current.activeCustomStrategySlot()
            if (customSlot != null) {
                current.copy(
                    customStrategies = current.customStrategies.map {
                        if (it.id == customSlot.id) it.copy(config = safeConfig) else it
                    }
                )
            } else {
                current.copy(baseStrategyConfig = safeConfig)
            }
        }
        _toast.value = "✅ تنظیمات استراتژی ذخیره شد (حداقل R:R = 1:${safeConfig.minRiskReward})؛ در حال بازسنجی..."
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

    fun selectFocusedStrategySymbol(
        symbolInput: String,
        intervalOverride: Interval? = null,
    ) {
        val normalized = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(normalized)) return
        _focusedStrategySymbol.value = normalized
        if (intervalOverride != null) {
            _focusedStrategyInterval.value = intervalOverride
        }
        val targetInterval = _focusedStrategyInterval.value
        val cachedDual = _dualSymbolStrategyReports.value[normalized]
        if (cachedDual != null) {
            _btcStrategyReport.value = cachedDual.reportFor(targetInterval)
        } else {
            val cachedReport = _allSymbolStrategyReports.value[normalized]
            if (cachedReport != null) {
                _btcStrategyReport.value = cachedReport
            } else {
                viewModelScope.launch {
                    evaluateSingleSymbolStrategy(normalized, forceNetwork = false)?.let { dual ->
                        _btcStrategyReport.value = dual.reportFor(targetInterval)
                    }
                }
            }
        }
    }

    fun selectFocusedStrategyInterval(interval: Interval) {
        val safeInterval = if (interval == Interval.M5) Interval.M5 else Interval.M15
        _focusedStrategyInterval.value = safeInterval
        val focused = V1Universe.normalize(_focusedStrategySymbol.value)
        _dualSymbolStrategyReports.value[focused]?.let { dual ->
            _btcStrategyReport.value = dual.reportFor(safeInterval)
        }
    }

    fun scanAndEvaluateBitcoinStrategyNow(userInitiated: Boolean = true) {
        viewModelScope.launch {
            evaluateAll15SymbolsStrategyInternal(
                userInitiated = userInitiated,
                fullSweep = true,
            )
        }
    }

    private suspend fun evaluateSingleSymbolStrategy(
        symbol: String,
        forceNetwork: Boolean = false,
    ): DualTimeframeSymbolReport? {
        val cfg = settings.value
        val activeModel = cfg.activeBitcoinStrategyModel()
        val activeCustom = cfg.activeCustomStrategySlot()
        val stratConfig = cfg.activeBitcoinStrategyConfig()
        val now = System.currentTimeMillis()

        // ۱. ابتدا 1H را می‌گیریم تا در نمادهای Yahoo کش 4H هم به‌صورت خودکار و بدون درخواست اضافه ساخته شود
        val bars1h = LiveQuoteFetcher.fetchTimeframeCandles(
            symbolInput = symbol,
            interval = Interval.H1,
            limit = 110,
            nowMillis = now,
            forceNetwork = forceNetwork,
        )?.first.orEmpty()

        val bars4h = LiveQuoteFetcher.fetchTimeframeCandles(
            symbolInput = symbol,
            interval = Interval.H4,
            limit = 110,
            nowMillis = now,
            forceNetwork = forceNetwork,
        )?.first.orEmpty()

        // ۲. دریافت دلتای کندل‌های 15m و 5m (با کش هوشمند فقط ۳ کندل جدید دانلود می‌شود و بین هر دو تایم‌فریم به اشتراک گذاشته می‌شود)
        val bars15m = LiveQuoteFetcher.fetchTimeframeCandles(
            symbolInput = symbol,
            interval = Interval.M15,
            limit = 110,
            nowMillis = now,
            forceNetwork = forceNetwork,
        )?.first.orEmpty()

        val bars5m = LiveQuoteFetcher.fetchTimeframeCandles(
            symbolInput = symbol,
            interval = Interval.M5,
            limit = 110,
            nowMillis = now,
            forceNetwork = forceNetwork,
        )?.first.orEmpty()

        val existingTick = _liveTicks.value[symbol]
        val liveTick = if (existingTick != null && (now - existingTick.at) <= 20_000L) {
            existingTick
        } else {
            LiveQuoteFetcher.fetchLiveTick(symbol)?.also { tick ->
                LiveQuoteFetcher.applyLiveTickToCachedCandles(symbol, tick)
                applyIncomingTick(symbol, tick)
            } ?: existingTick
        }

        val dualReport = IchimokuStrategyEngine.evaluateDualTimeframeStrategy(
            activeModel = activeModel,
            config = stratConfig,
            candles4h = bars4h,
            candles1h = bars1h,
            candles15m = bars15m,
            candles5m = bars5m,
            livePriceOverride = liveTick?.price,
            customTitleOverride = activeCustom?.titleFa,
            customIdOverride = activeCustom?.id,
            nowMillis = now,
            symbolInput = symbol,
            runAllSimultaneously = cfg.runAllStrategiesSimultaneously,
            enabledStrategyIds = cfg.enabledStrategyIds,
            customStrategies = cfg.customStrategies,
        )

        val updatedDualMap = _dualSymbolStrategyReports.value.toMutableMap()
        updatedDualMap[symbol] = dualReport
        _dualSymbolStrategyReports.value = updatedDualMap

        val updatedMap = _allSymbolStrategyReports.value.toMutableMap()
        updatedMap[symbol] = dualReport.primaryReport
        _allSymbolStrategyReports.value = updatedMap
        return dualReport
    }

    /**
     * بررسی هوشمند هر ۱۵ نماد داخل اپ (`V1Universe.allSymbols`) هم‌زمان در دو تایم‌فریم ورود `15m` و `5m`:
     * - با اشتراک‌گذاری کندل‌های `4H`/`1H`/`15m` و Delta Sync، هیچ دیتای تکراری دانلود نمی‌شود.
     * - در هر سیگنال، اعلان و معامله ثبت‌شده در ژورنال، تایم‌فریم ورود (`15m` یا `5m`) صراحتاً درج می‌شود.
     */
    private suspend fun evaluateAll15SymbolsStrategyInternal(
        userInitiated: Boolean,
        fullSweep: Boolean = true,
    ) {
        if (_isScanningBtcStrategy.value) return
        _isScanningBtcStrategy.value = true
        try {
            val cfg = settings.value
            val stratConfig = cfg.activeBitcoinStrategyConfig()
            val now = System.currentTimeMillis()
            val focused = V1Universe.normalize(_focusedStrategySymbol.value)
            val focusedInterval = _focusedStrategyInterval.value
            val openTradeSymbols = trades.value.filter { it.isOpen }.map { V1Universe.normalize(it.symbol) }.toSet()

            // ابتدا ۵ رمزارز را در ۱ درخواست دسته‌ای آپدیت کن تا قیمت لحظه‌ای همه تازه باشد
            val batchCrypto = LiveQuoteFetcher.fetchCryptoBatchTicks(V1Universe.cryptoSymbols, now)
            batchCrypto.forEach { (sym, tick) ->
                applyIncomingTick(sym, tick)
                container.settingsStore.setLastSync(sym, at = tick.at)
            }

            val symbolsToEvaluate = if (fullSweep) {
                V1Universe.allSymbols
            } else {
                V1Universe.allSymbols.filter { sym ->
                    val dual = _dualSymbolStrategyReports.value[sym]
                    sym == focused ||
                        sym in openTradeSymbols ||
                        dual?.report15m?.readiness == StrategyReadinessState.WATCH_SETUP ||
                        dual?.report5m?.readiness == StrategyReadinessState.WATCH_SETUP ||
                        dual?.actionableReports?.isNotEmpty() == true
                }
            }

            val dualMap = _dualSymbolStrategyReports.value.toMutableMap()
            val reportsMap = _allSymbolStrategyReports.value.toMutableMap()
            val newlyEvaluatedDuals = mutableListOf<DualTimeframeSymbolReport>()

            // اسکن موازی (Parallel Fan-Out در دسته‌های ۵ تایی) برای تصمیم‌گیری ۴ برابر سریع‌تر
            val activeSymbolsToFetch = symbolsToEvaluate.filter { symbol ->
                val marketClosed = com.aurum.edge.core.MarketHours.closedFor(symbol, now)
                if (marketClosed && dualMap[symbol] != null && !userInitiated) {
                    LiveQuoteFetcher.recordSkippedClosedMarketCheck(1)
                    false
                } else {
                    true
                }
            }

            coroutineScope {
                activeSymbolsToFetch.chunked(5).forEach { batch ->
                    val batchResults = batch.map { symbol ->
                        async {
                            evaluateSingleSymbolStrategy(symbol, forceNetwork = false)
                        }
                    }.awaitAll()
                    batchResults.filterNotNull().forEach { dual ->
                        dualMap[dual.symbol] = dual
                        reportsMap[dual.symbol] = dual.primaryReport
                        newlyEvaluatedDuals += dual
                        if (dual.symbol == focused || (_btcStrategyReport.value == null && dual.symbol == IchimokuStrategyEngine.BTC_SYMBOL)) {
                            _btcStrategyReport.value = dual.reportFor(focusedInterval)
                        }
                    }
                    updatePairScanStateFromReports(reportsMap, sweeping = true, now = now)
                }
            }

            // ۱. مدیریت پویا ۵ مرحله‌ای (شکارچی روند بالای ۱:۳ / قفل ۱:۱.۵ / Preserve Mode / کیجون / ریسک‌فری) روی معاملات باز
            for (dual in newlyEvaluatedDuals) {
                val symbol = dual.symbol
                val openSymbolTrades = trades.value.filter {
                    it.isOpen && V1Universe.normalize(it.symbol) == symbol
                }
                for (openTrade in openSymbolTrades) {
                    val matchingReport = dual.reportFor(openTrade.interval, openTrade.strategyId)
                    val preserveUpdate = IchimokuStrategyEngine.evaluateOpenTradeManagement(
                        trade = openTrade,
                        report = matchingReport,
                        config = stratConfig,
                    )
                    if (preserveUpdate != null) {
                        if (preserveUpdate.shouldCloseImmediatelyInProfit) {
                            val exitP = preserveUpdate.closePrice ?: matchingReport.livePrice
                            container.journalStore.closeTradeAtPrice(
                                tradeId = openTrade.id,
                                exitPrice = exitP,
                                reason = preserveUpdate.reasonFa,
                                now = now,
                            )
                            _toast.value = "${preserveUpdate.stageTitleFa}: معامله ${openTrade.symbol} در قیمت $exitP با سود بسته شد (${preserveUpdate.reasonFa})"
                        } else {
                            container.journalStore.updateTradeStopLoss(
                                tradeId = openTrade.id,
                                newStopLoss = preserveUpdate.newStopLoss,
                                stageNote = preserveUpdate.stageTitleFa,
                                newTakeProfit = preserveUpdate.newTakeProfit,
                            )
                            _toast.value = "${preserveUpdate.stageTitleFa} [${openTrade.interval.label}]: استاپ ${openTrade.symbol} به ${preserveUpdate.newStopLoss} منتقل شد (${preserveUpdate.reasonFa})"
                        }
                    }
                }
            }

            // ۲. رصد هم‌زمان 15m و 5m برای ورود خودکار همه استراتژی‌ها (با شرط قطعی R:R >= 1:1.5)
            if (cfg.autoPaperTrading) {
                val rankedCandidates = newlyEvaluatedDuals
                    .flatMap { it.actionableReports }
                    .filter { rep ->
                        !com.aurum.edge.core.MarketHours.closedFor(rep.symbol, now)
                    }
                    .sortedByDescending { it.coreTrendScore }

                for (report in rankedCandidates) {
                    val symbol = report.symbol
                    val openAll = trades.value.filter { it.isOpen }
                    val hasOpenSameStrategyTf = openAll.any {
                        it.strategyId == report.strategyId &&
                            V1Universe.normalize(it.symbol) == symbol &&
                            it.interval == report.triggerInterval
                    }
                    val fireKey = "${report.strategyId}|$symbol|${report.triggerInterval.label}"
                    val lastFiredBar = lastAutoFiredBarTimeByKey[fireKey] ?: 0L
                    if (!hasOpenSameStrategyTf &&
                        report.triggerBarTime > 0L && report.triggerBarTime != lastFiredBar
                    ) {
                        val neuralEval = container.neuralEngine.evaluateReport(report)
                        if (!neuralEval.shouldAllowTrade) {
                            lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to report.triggerBarTime)
                            _toast.value = "🧠 شبکه عصبی خودآموز ورود ${report.symbol} (${report.triggerInterval.label}) را مسدود کرد: ${neuralEval.noTradeWarningFa ?: neuralEval.summaryFa}"
                            continue
                        }

                        val rawEntry = report.suggestedEntry
                        val rawSl = report.suggestedStopLoss
                        val rawTp = report.primaryTakeProfit
                        if (rawEntry != null && rawSl != null && rawTp != null) {
                            // لنگر انداختن نقطهٔ ورود، حد ضرر و حد سود به قیمت لحظه‌ای اجرا (جلوگیری از استاپ فوری در ارزهای کریپتو)
                            val liveTickPrice = _liveTicks.value[symbol]?.price?.takeIf { it.isFinite() && it > 0.0 }
                            val executionEntry = liveTickPrice
                                ?: report.livePrice?.takeIf { it.isFinite() && it > 0.0 }
                                ?: rawEntry
                            val entryDelta = executionEntry - rawEntry
                            val shiftedSl = rawSl + entryDelta
                            val shiftedTp = rawTp + entryDelta
                            val desiredRr = (report.optimalRiskReward ?: RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD)
                                .coerceAtLeast(RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD)
                            val (safeSl, safeTp) = RiskAndCalendarEngine.sanitizeEntryStopAndTarget(
                                symbolInput = symbol,
                                action = report.action,
                                liveEntryPrice = executionEntry,
                                candidateStopLoss = shiftedSl,
                                candidateTakeProfit = shiftedTp,
                                desiredRiskReward = desiredRr,
                                trigAtr14 = report.triggerSnapshot?.atr14 ?: 0.0,
                                triggerInterval = report.triggerInterval,
                            )
                            val enforcedTp = RiskAndCalendarEngine.enforceMinimumRiskReward(
                                symbolInput = symbol,
                                entryPrice = executionEntry,
                                stopLoss = safeSl,
                                takeProfit = safeTp,
                                action = report.action,
                                minRiskReward = desiredRr,
                            )
                            val calc = RiskAndCalendarEngine.calculatePosition(
                                symbolInput = symbol,
                                accountBalance = cfg.accountBalance,
                                riskPercent = cfg.riskPercent,
                                entry = executionEntry,
                                stopLoss = safeSl,
                                takeProfit = enforcedTp,
                                preferredAction = report.action,
                                fixedRiskUsdOverride = if (cfg.useFixedDollarRisk) cfg.fixedRiskUsd else null,
                            )
                            if (calc != null && calc.riskReward >= RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD) {
                                lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to report.triggerBarTime)
                                val tfLabel = report.triggerInterval.label
                                val winProbPct = (neuralEval.predictedWinProb * 100.0).toInt()
                                val autoNote = "⚡ خودکار [تایم $tfLabel] · [${report.strategyTitleFa}] · (R:R 1:${calc.riskReward} · ${report.trendPhase.badgeFa}) · [NN:${neuralEval.conditionKey} · Win:${winProbPct}%]"
                                openAutoTrackedTrade(
                                    calc = calc,
                                    note = autoNote,
                                    strategyContext = report.toAiStrategyContext(),
                                    entryInterval = report.triggerInterval,
                                    strategyId = report.strategyId,
                                    strategyTitleFa = report.strategyTitleFa,
                                )
                            }
                        }
                    }
                }
            }

            _dualSymbolStrategyReports.value = dualMap
            _allSymbolStrategyReports.value = reportsMap
            dualMap[focused]?.let { _btcStrategyReport.value = it.reportFor(focusedInterval) }
            updatePairScanStateFromReports(reportsMap, sweeping = false, now = now)

            if (userInitiated) {
                val buy15 = dualMap.values.count { it.report15m.readiness == StrategyReadinessState.SIGNAL_BUY }
                val sell15 = dualMap.values.count { it.report15m.readiness == StrategyReadinessState.SIGNAL_SELL }
                val buy5 = dualMap.values.count { it.report5m.readiness == StrategyReadinessState.SIGNAL_BUY }
                val sell5 = dualMap.values.count { it.report5m.readiness == StrategyReadinessState.SIGNAL_SELL }
                _toast.value = "✅ رصد هم‌زمان ۱۵ نماد — تایم 15m (خرید: $buy15 · فروش: $sell15) | تایم 5m (خرید: $buy5 · فروش: $sell5)"
            }
        } finally {
            _isScanningBtcStrategy.value = false
        }
    }

    private fun updatePairScanStateFromReports(
        reportsMap: Map<String, StrategyEvaluationReport>,
        sweeping: Boolean,
        now: Long,
    ) {
        val dualMap = _dualSymbolStrategyReports.value
        val statuses = V1Universe.allSymbols.map { sym ->
            val rep = reportsMap[sym]
            val dual = dualMap[sym]
            val stateCode = when (rep?.readiness) {
                StrategyReadinessState.SIGNAL_BUY, StrategyReadinessState.SIGNAL_SELL -> "candidate"
                StrategyReadinessState.WATCH_SETUP -> "watch"
                StrategyReadinessState.BLOCKED -> "no_signal"
                StrategyReadinessState.INSUFFICIENT_DATA -> "partial"
                null -> "pending"
            }
            PairScanStatus(
                symbol = sym,
                state = stateCode,
                detail = dual?.combinedSummaryFa ?: rep?.summaryFa ?: "در صف بررسی هم‌زمان 15m و 5m",
                interval = rep?.triggerInterval ?: com.aurum.edge.domain.Interval.M15,
                lastScanAt = rep?.evaluatedAt ?: now,
                action = rep?.action,
            )
        }
        _pairScan.value = PairScanState(
            statuses = statuses,
            sweeping = sweeping,
            checkedCount = reportsMap.size,
            totalCount = V1Universe.allSymbols.size,
        )
    }

    fun setNotifyOnSignal(enabled: Boolean) {
        container.settingsStore.update { it.copy(notifyOnSignal = enabled) }
        _toast.value = if (enabled) "🔔 آلارم‌های سه‌گانه (ورود، حد سود، حد ضرر) فعال شدند" else "🔕 آلارم‌های صوتی معامله غیرفعال شدند"
    }

    fun setAlarmVolumePercent(percent: Int) {
        val safePct = percent.coerceIn(0, 100)
        container.settingsStore.update { it.copy(alarmVolumePercent = safePct) }
    }

    fun setAlarmTonePreset(presetId: String) {
        val preset = com.aurum.edge.notify.AlarmTonePreset.fromId(presetId)
        container.settingsStore.update { it.copy(alarmTonePreset = preset.id) }
        _toast.value = "🎼 الگوی صدای آلارم: ${preset.titleFa}"
    }

    fun setAlarmVibrationEnabled(enabled: Boolean) {
        container.settingsStore.update { it.copy(alarmVibrationEnabled = enabled) }
        _toast.value = if (enabled) "📳 ویبره اختصاصی آلارم‌ها فعال شد" else "📴 ویبره آلارم‌ها غیرفعال شد"
    }

    fun setCustomAlarmSoundForKind(
        kind: com.aurum.edge.notify.TradeAlarmKind?,
        uri: String,
        name: String,
    ) {
        val cleanUri = uri.trim()
        val cleanName = name.trim()
        container.settingsStore.update { cur ->
            when (kind) {
                com.aurum.edge.notify.TradeAlarmKind.ENTRY ->
                    cur.copy(entryAlarmSoundUri = cleanUri, entryAlarmSoundName = cleanName)
                com.aurum.edge.notify.TradeAlarmKind.TAKE_PROFIT ->
                    cur.copy(tpAlarmSoundUri = cleanUri, tpAlarmSoundName = cleanName)
                com.aurum.edge.notify.TradeAlarmKind.STOP_LOSS ->
                    cur.copy(slAlarmSoundUri = cleanUri, slAlarmSoundName = cleanName)
                null ->
                    cur.copy(
                        alertSoundUri = cleanUri,
                        alertSoundName = cleanName,
                        entryAlarmSoundUri = cleanUri,
                        entryAlarmSoundName = cleanName,
                        tpAlarmSoundUri = cleanUri,
                        tpAlarmSoundName = cleanName,
                        slAlarmSoundUri = cleanUri,
                        slAlarmSoundName = cleanName,
                    )
            }
        }
        val label = kind?.channelNameFa ?: "هر ۳ آلارم"
        _toast.value = if (cleanUri.isBlank()) {
            "🔔 صدای $label به ملودی استاندارد بازنشانی شد"
        } else {
            "🎵 صدای اختصاصی $label ذخیره شد: ${cleanName.ifBlank { "زنگ انتخابی" }}"
        }
    }

    fun evaluateReportWithNeuralEngine(report: StrategyEvaluationReport): NeuralSignalEvaluation =
        container.neuralEngine.evaluateReport(report)

    fun setNeuralForceActiveBeforeWarmup(enabled: Boolean) {
        container.neuralEngine.setForceActiveBeforeWarmup(enabled)
        _toast.value = if (enabled) {
            "🧠 فیلتر شبکه عصبی خودآموز پیش از ۱۰,۰۰۰ معامله نیز فعال شد"
        } else {
            "🧠 شبکه عصبی تا تکمیل ۱۰,۰۰۰ معامله در حالت یادگیری خالص (بدون بلاک) قرار گرفت"
        }
    }

    fun triggerTestTradeAlarm(kind: com.aurum.edge.notify.TradeAlarmKind) {
        val cfg = settings.value
        com.aurum.edge.notify.TradeEventAlarmManager.triggerTestAlarm(
            context = container.appContext,
            kind = kind,
            customSoundUri = cfg.alertSoundUri,
        )
        _toast.value = "🔊 تست ${kind.channelNameFa} پخش شد"
    }

    private val _savedJournalFiles = MutableStateFlow<List<com.aurum.edge.data.SavedJournalExportFile>>(
        com.aurum.edge.data.JournalExportEngine.listSavedExportFiles(container.appContext)
    )
    val savedJournalFiles: StateFlow<List<com.aurum.edge.data.SavedJournalExportFile>> = _savedJournalFiles.asStateFlow()

    private val _lastExportStatusFa = MutableStateFlow<String?>(null)
    val lastExportStatusFa: StateFlow<String?> = _lastExportStatusFa.asStateFlow()

    fun refreshSavedJournalFiles() {
        _savedJournalFiles.value = com.aurum.edge.data.JournalExportEngine.listSavedExportFiles(container.appContext)
    }

    fun exportAndShareJournal(
        report: com.aurum.edge.data.InstitutionalTearSheetReport,
        format: com.aurum.edge.data.JournalExportFormat,
    ) {
        viewModelScope.launch {
            runCatching {
                val file = com.aurum.edge.data.JournalExportEngine.exportToFile(
                    context = container.appContext,
                    report = report,
                    format = format,
                )
                refreshSavedJournalFiles()
                com.aurum.edge.data.JournalExportEngine.shareExportedFile(
                    context = container.appContext,
                    file = file,
                    format = format,
                ).getOrThrow()
                file
            }.onSuccess { file ->
                val msg = "📤 خروجی ${format.extension.uppercase()} آماده ارسال و ذخیره شد (${file.name})"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در تولید خروجی: ${err.message}"
            }
        }
    }

    fun exportAndOpenJournal(
        report: com.aurum.edge.data.InstitutionalTearSheetReport,
        format: com.aurum.edge.data.JournalExportFormat,
    ) {
        viewModelScope.launch {
            runCatching {
                val file = com.aurum.edge.data.JournalExportEngine.exportToFile(
                    context = container.appContext,
                    report = report,
                    format = format,
                )
                refreshSavedJournalFiles()
                com.aurum.edge.data.JournalExportEngine.openExportedFileExternally(
                    context = container.appContext,
                    file = file,
                    format = format,
                ).getOrThrow()
            }.onFailure {
                // Fallback to share sheet if no default viewer app is installed
                exportAndShareJournal(report, format)
            }
        }
    }

    fun saveJournalDirectToDownloads(
        report: com.aurum.edge.data.InstitutionalTearSheetReport,
        format: com.aurum.edge.data.JournalExportFormat,
    ) {
        viewModelScope.launch {
            com.aurum.edge.data.JournalExportEngine.saveDirectToDownloads(
                context = container.appContext,
                report = report,
                format = format,
            ).onSuccess { savedPath ->
                refreshSavedJournalFiles()
                val msg = "💾 ذخیره مستقیم در حافظه گوشی انجام شد: $savedPath"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در ذخیره مستقیم در Downloads: ${err.message}"
            }
        }
    }

    fun saveJournalToCustomUri(
        uri: android.net.Uri,
        report: com.aurum.edge.data.InstitutionalTearSheetReport,
        format: com.aurum.edge.data.JournalExportFormat,
    ) {
        viewModelScope.launch {
            com.aurum.edge.data.JournalExportEngine.writeExportToUri(
                context = container.appContext,
                uri = uri,
                report = report,
                format = format,
            ).onSuccess { fileName ->
                refreshSavedJournalFiles()
                val msg = "✅ فایل $fileName در پوشه انتخابی شما ذخیره شد"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در ذخیره فایل در مسیر انتخابی: ${err.message}"
            }
        }
    }

    fun importJournalFromUri(
        uri: android.net.Uri,
        replaceExisting: Boolean,
    ) {
        viewModelScope.launch {
            com.aurum.edge.data.JournalExportEngine.readAndParseBackupUri(
                context = container.appContext,
                uri = uri,
            ).onSuccess { importedTrades ->
                val count = container.journalStore.importTrades(
                    imported = importedTrades,
                    replaceExisting = replaceExisting,
                )
                val modeFa = if (replaceExisting) "جایگزینی کامل" else "ادغام (Merge)"
                val msg = "✅ بازگردانی بکاپ با موفقیت انجام شد: $count معامله ($modeFa)"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در بازگردانی فایل بکاپ: ${err.message}"
            }
        }
    }

    fun importJournalFromSavedFile(
        saved: com.aurum.edge.data.SavedJournalExportFile,
        replaceExisting: Boolean,
    ) {
        viewModelScope.launch {
            runCatching {
                val raw = saved.file.readText(Charsets.UTF_8)
                val parsed = com.aurum.edge.data.JournalExportEngine.parseBackupContent(raw).getOrThrow()
                container.journalStore.importTrades(parsed, replaceExisting = replaceExisting)
            }.onSuccess { count ->
                val modeFa = if (replaceExisting) "جایگزینی کامل" else "ادغام"
                val msg = "♻️ $count معامله از فایل ${saved.name} بازگردانی شد ($modeFa)"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در بازگردانی از فایل: ${err.message}"
            }
        }
    }

    fun importJournalFromClipboard(replaceExisting: Boolean) {
        viewModelScope.launch {
            runCatching {
                val cm = container.appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(container.appContext)?.toString().orEmpty()
                val parsed = com.aurum.edge.data.JournalExportEngine.parseBackupContent(clipText).getOrThrow()
                container.journalStore.importTrades(parsed, replaceExisting = replaceExisting)
            }.onSuccess { count ->
                val msg = "📋 $count معامله از کلیپ‌بورد بازگردانی شد"
                _lastExportStatusFa.value = msg
                _toast.value = msg
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در بازگردانی از کلیپ‌بورد: ${err.message}"
            }
        }
    }

    fun openSavedJournalFile(saved: com.aurum.edge.data.SavedJournalExportFile) {
        com.aurum.edge.data.JournalExportEngine.openExportedFileExternally(
            context = container.appContext,
            file = saved.file,
            format = saved.format,
        ).onFailure {
            shareSavedJournalFile(saved)
        }
    }

    fun shareSavedJournalFile(saved: com.aurum.edge.data.SavedJournalExportFile) {
        com.aurum.edge.data.JournalExportEngine.shareExportedFile(
            context = container.appContext,
            file = saved.file,
            format = saved.format,
        ).onFailure { err ->
            _toast.value = "⚠️ خطا در اشتراک‌گذاری فایل: ${err.message}"
        }
    }

    fun deleteSavedJournalFile(saved: com.aurum.edge.data.SavedJournalExportFile) {
        runCatching { saved.file.delete() }
        refreshSavedJournalFiles()
        _toast.value = "🗑️ فایل ${saved.name} حذف شد"
    }

    fun copyJournalTearSheetSummary(report: com.aurum.edge.data.InstitutionalTearSheetReport) {
        com.aurum.edge.data.JournalExportEngine.copyExecutiveSummaryToClipboard(
            context = container.appContext,
            report = report,
        )
        _toast.value = "📋 خلاصه کارنامه کوانت در کلیپ‌بورد کپی شد"
    }

    fun togglePinAssistantImprovementNote(note: com.aurum.edge.core.IchimokuImprovementNote) {
        val pinned = com.aurum.edge.core.MasterIchimokuTradingAssistant.pinOrToggleImprovementNote(
            context = container.appContext,
            note = note,
        )
        _toast.value = if (pinned) {
            "📌 نکته اصلاحی در لیست ارتقای بعدی و حافظه گوشی ذخیره شد"
        } else {
            "نکته از لیست پین‌شده‌ها برداشته شد"
        }
    }

    fun copyAssistantImprovementNotes(notes: List<com.aurum.edge.core.IchimokuImprovementNote>) {
        val text = com.aurum.edge.core.MasterIchimokuTradingAssistant.formatNotesForClipboard(notes)
        val cm = container.appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Ichimoku Fix Notes", text))
        com.aurum.edge.core.MasterIchimokuTradingAssistant.saveAllGeneratedNotesToPhone(container.appContext, notes)
        _toast.value = "📋 تمام نکات اصلاحی دستیار ایچیموکو کپی و در حافظه گوشی ذخیره شد"
    }

    fun applyAssistantNoteConfigPatch(note: com.aurum.edge.core.IchimokuImprovementNote) {
        container.settingsStore.update { cur ->
            val oldCfg = cur.baseStrategyConfig
            val newCfg = oldCfg.copy(
                slBufferAtr = note.suggestedSlBufferAtr ?: oldCfg.slBufferAtr,
                maxKijunExtensionAtr = note.suggestedMaxKijunExtAtr ?: oldCfg.maxKijunExtensionAtr,
                breakoutBufferAtr = note.suggestedBreakoutBufferAtr ?: oldCfg.breakoutBufferAtr,
                minRiskReward = note.suggestedMinRr ?: oldCfg.minRiskReward,
            )
            cur.copy(baseStrategyConfig = newCfg)
        }
        scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        _toast.value = "⚡ پیشنهاد اصلاحی دستیار ایچیموکو روی تنظیمات استراتژی اعمال شد"
    }

    fun consumeToast() {
        _toast.value = null
    }
}

class AurumViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AurumViewModel(container) as T
}
