package com.aurum.edge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.AiTraderEngine
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CandleBackfillSummary
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.DualTimeframeSymbolReport
import com.aurum.edge.core.EntrySafetyGate
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.MarketState
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.NeuralSelfLearningEngine
import com.aurum.edge.core.NeuralSignalEvaluation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.RiskCalculationResult
import com.aurum.edge.core.RiskLimitStatus
import com.aurum.edge.core.SessionExitPlanner
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.StrategyEvaluationReport
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.SymbolPollTier
import com.aurum.edge.core.TradePlanGuard
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
import com.aurum.edge.data.SessionExitLearningStore
import com.aurum.edge.data.TraderMemoryStore
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
import kotlinx.coroutines.yield
import com.aurum.edge.core.CrashReporter

/**
 * وضعیت کارت «بکاپ اسکرین‌شات‌ها» در تب آپدیت.
 */

class AurumViewModel(internal val container: AppContainer) : ViewModel() {

    companion object {
        /** هر چند وقت یک‌بار معامله‌گر خودش را بازبینی و اصلاح کند. */
        private const val AI_TRADER_CYCLE_MS = 15L * 60L * 1000L
    }

    // ─── معامله‌گر هوشمند (نه دستیار): پیش‌بینی خبری + خودترمیمی + ماتریس برتری ───
    //
    // سه بخش پیش از این جدا و بی‌اثر بودند: دستیار فقط توضیح می‌داد، نکات قابل اصلاح فقط
    // ساخته می‌شدند و ماتریس برتری فقط نمایشی بود. این وضعیت، خروجی هر سه را به «تصمیم» تبدیل
    // می‌کند و روی خود موتور اثر می‌گذارد.
    private val _aiTraderForecasts = MutableStateFlow<List<AiTraderEngine.SymbolConditionsForecast>>(emptyList())
    val aiTraderForecasts: StateFlow<List<AiTraderEngine.SymbolConditionsForecast>> =
        _aiTraderForecasts.asStateFlow()

    private val _aiTraderHealings = MutableStateFlow<List<AiTraderEngine.SelfHealingAction>>(emptyList())
    val aiTraderHealings: StateFlow<List<AiTraderEngine.SelfHealingAction>> =
        _aiTraderHealings.asStateFlow()

    private val _aiTraderMatrix = MutableStateFlow<List<AiTraderEngine.EdgeMatrixRow>>(emptyList())
    val aiTraderMatrix: StateFlow<List<AiTraderEngine.EdgeMatrixRow>> =
        _aiTraderMatrix.asStateFlow()

    private val _aiTraderSummaryFa = MutableStateFlow<List<String>>(emptyList())
    val aiTraderSummaryFa: StateFlow<List<String>> = _aiTraderSummaryFa.asStateFlow()

    private val _aiTraderMemory = MutableStateFlow(com.aurum.edge.data.TraderMemorySnapshot())
    val aiTraderMemory: StateFlow<com.aurum.edge.data.TraderMemorySnapshot> = _aiTraderMemory.asStateFlow()

    @Volatile
    private var lastAiTraderCycleAtMs: Long = 0L

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
    /** پلن‌های کهنه/نامعتبری که یک بار به کاربر گزارش شده‌اند تا پیام تکراری ساخته نشود. */
    private val rejectedPlanKeys = mutableSetOf<String>()

    /** زمان سربارِ آموخته‌شدهٔ خروج پیش از سشن برای هر دستهٔ دارایی (پنل فارسی). */
    val sessionExitEstimates: StateFlow<List<com.aurum.edge.core.SessionExitLearning.Estimate>> =
        SessionExitLearningStore.estimates

    /** آخرین نتیجهٔ سنجش «خروج زودتر بهتر بود یا نگه‌داشتن» پس از بازگشایی بازار. */
    val sessionExitLearningNote: StateFlow<String> = SessionExitLearningStore.lastResolvedFa

    init {
        container.settingsStore.ensureBalance100kMigrated()
        runCatching { SessionExitLearningStore.init(container.appContext) }
        container.appScope.launch {
            container.journalStore.trades.collect { currentTrades ->
                _stats.value = container.journalStore.stats()
                _lastDisplayMarks.value = OpenPositionDisplay.onlyOpen(_lastDisplayMarks.value, currentTrades)
                val reconciliation = container.neuralEngine.syncWithClosedJournalTrades(currentTrades)
                // بعد از یادگیری معامله‌های تازه، شخصیت نمادها/استراتژی‌ها در پیکربندی موتور
                // تازه‌سازی می‌شود تا ارزیابی‌های بعدی از آموخته‌های جدید استفاده کنند.
                if (reconciliation.newlyLearnedThisRun > 0) {
                    runCatching {
                        val refreshed = container.neuralEngine
                            .applyAutoTunedParametersToConfig(settings.value.baseStrategyConfig)
                        container.settingsStore.update { cur -> cur.copy(baseStrategyConfig = refreshed) }
                    }
                }
                syncBackgroundServiceState()
            }
        }
        syncBackgroundServiceState()
        startLiveMonitor()
    }

    fun startApp(@Suppress("UNUSED_PARAMETER") context: Context) {
        container.settingsStore.ensureBalance100kMigrated()
        CrashReporter.breadcrumb("app:start")
        // ── سبک‌سازی راه‌اندازی ────────────────────────────────────────────────
        // همهٔ کارهای دیسکی/سنگینِ شروع، مرحله‌به‌مرحله و روی IO اجرا می‌شوند. پیش‌تر همین کارها
        // پشت‌سرهم روی رشتهٔ اصلی اجرا می‌شدند و فریم اول UI را قفل می‌کردند (لگ و «اپ پاسخ نمی‌دهد»).
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.aurum.edge.data.PhoneStorageVaultManager.attachContext(container.appContext) }
            runCatching { com.aurum.edge.data.SessionExitLearningStore.init(container.appContext) }
            yield()
            // راه‌اندازی فقط دادهٔ متنی و کندل را آماده می‌کند؛ هیچ کار تصویری در این مسیر نیست.
            runCatching { com.aurum.edge.core.MasterIchimokuTradingAssistant.init(container.appContext) }

            // فرصت دادن به اولین فریم و اولین تیک قیمت تا کارهای سنگین با نمایش اولیه رقابت نکنند.
            delay(1_500L)
            runCatching {
                val shardTrades = com.aurum.edge.data.PhoneStorageVaultManager.loadAllTradesFromPhoneShards(container.appContext)
                if (shardTrades.isNotEmpty()) {
                    container.journalStore.importTrades(shardTrades, replaceExisting = false)
                }
                yield()
                com.aurum.edge.data.PhoneStorageVaultManager.syncTradesToShardsAndDailyBackup(
                    context = container.appContext,
                    trades = trades.value,
                    forceAllShards = true,
                )
                yield()
                container.neuralEngine.restoreBrainBackupFromPhoneStorage(container.appContext)
            }

            // ── آموزش شبکهٔ عصبی روی همهٔ ۱۶ استراتژی در هر اجرا ──────────────────
            // بعد از بارگذاری ژورنال/دفتر یادگیری و با فاصله از فریم اول اجرا می‌شود تا UI سبک بماند.
            delay(1_200L)
            TraderMemoryStore.init(container.appContext)
            runCatching {
                container.neuralEngine.learnAllStrategiesNow(settings.value.baseStrategyConfig)
                val refreshed = container.neuralEngine.applyAutoTunedParametersToConfig(settings.value.baseStrategyConfig)
                container.settingsStore.update { cur -> cur.copy(baseStrategyConfig = refreshed) }
            }
            // حافظهٔ معامله‌گر از گوشی و یک چرخهٔ کامل (پیش‌بینی + خودترمیمی + ماتریس برتری).
            runCatching { _aiTraderMemory.value = TraderMemoryStore.read(container.appContext) }
            runCatching { runAiTraderCycleInternal(System.currentTimeMillis()) }
            CrashReporter.breadcrumb("app:startup-work-done")
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

    /**
     * آخرین تیک **هر** نماد، مستقل از نرخ انتشار به UI.
     *
     * منطق معاملاتی (حد ضرر/حد سود، سشن، پلن) همیشه روی تازه‌ترین قیمت کار می‌کند؛ فقط نمایش
     * با نرخ محدود به‌روز می‌شود تا بازسازی مکرر صفحه روی گوشی‌های ضعیف باعث لگ/ANR نشود.
     */
    private val latestTicks = java.util.concurrent.ConcurrentHashMap<String, PriceTick>()

    /** هم‌گراسازی انتشار وضعیت به UI (حداکثر ۴ بار در ثانیه برای هر کلید). */
    private val uiCoalescer = com.aurum.edge.core.UiUpdateCoalescer(minIntervalMs = 250L)

    /** پوزیشن‌هایی که پلن‌شان یک‌بار اصلاح شده است. */
    private val repairedPlanTradeIds = mutableSetOf<String>()

    /** پوزیشن‌هایی که در بازار بسته باز مانده‌اند و باید در اولین تیک بازگشایی بسته شوند. */
    private val carriedOverClosureIds = mutableSetOf<String>()

    /** آخرین سشنی که برای هر نماد دیده‌ایم؛ برای تشخیص «بازگشایی» بدون نگاه به ساعت. */
    private val lastClosedStateBySymbol = mutableMapOf<String, Boolean>()

    /** زمان سربار آموخته‌شده برای هر نماد (کش تا از محاسبهٔ تکراری در هر تیک جلوگیری شود). */
    private val leadMinutesCache = mutableMapOf<String, Int>()

    private fun learnedLeadMinutes(symbol: String): Int = leadMinutesCache.getOrPut(symbol) {
        runCatching { SessionExitLearningStore.leadMinutesFor(symbol) }
            .getOrElse { 0 }
    }

    /**
     * «بهداشت معاملات» — تصمیم‌های زمانی بازار، دقیقاً همان‌طور که کاربر خواست:
     *
     * ۱. **هیچ معامله‌ای به‌صرف بسته بودن بازار بسته نمی‌شود.** پوزیشن باز در بازار بسته فقط
     *    علامت‌گذاری می‌شود و تا **بازگشایی** صبر می‌شود (نسخهٔ ۳.۹.۲۸ با قیمت کهنه می‌بست که غلط بود).
     * ۲. **پیش از پایان سشن، در بازار زنده و با قیمت واقعی** بسته می‌شود: در فاصلهٔ
     *    `بسته‌شدن − زمان سربار` که از `SessionExitLearning` می‌آید (پیش‌فرض سهام ۱۲ دقیقه).
     * ۳. پوزیشنی که از سشن قبل باز مانده، در **اولین تیک معتبر پس از بازگشایی** بسته می‌شود؛
     *    قیمت همان تیک در ژورنال ثبت و پاداش واقعی به موتور یادگیری داده می‌شود.
     * ۴. پلن قدیمی و متورم (نمونهٔ BNB 5m با ۱R = ۵.۶۶٪) با `TradePlanGuard` اصلاح می‌شود.
     */
    private fun maintainTradingHygiene(now: Long) {
        val openTrades = trades.value.filter { it.isOpen }
        if (openTrades.isEmpty()) {
            repairedPlanTradeIds.clear()
            carriedOverClosureIds.clear()
            lastClosedStateBySymbol.clear()
            leadMinutesCache.clear()
            return
        }
        for (trade in openTrades) {
            val symbol = V1Universe.normalize(trade.symbol)
            val lastTick = latestTicks[symbol] ?: _liveTicks.value[symbol]
            val lastPrice = lastTick?.price?.takeIf { it.isFinite() && it > 0.0 }
            val tickIsFresh = lastTick?.let { now - it.at < 15 * 60_000L } == true
            val plan = SessionExitPlanner.plan(
                symbol = symbol,
                now = now,
                learnedLeadMinutes = learnedLeadMinutes(symbol),
                hasOpenPosition = true,
                carriedOverClosure = trade.id in carriedOverClosureIds,
            )

            // قاعدهٔ ۱: بازار بسته = هرگز بستن با قیمت کهنه؛ فقط انتظار برای بازگشایی.
            if (plan.closed) {
                if (carriedOverClosureIds.add(trade.id)) {
                    _toast.value = "🕒 بازار $symbol بسته است — پوزیشن #${trade.displayTradeNumber} " +
                        "بدون دست‌کاری تا بازگشایی می‌ماند و در اولین قیمت معتبر بسته می‌شود"
                }
                lastClosedStateBySymbol[symbol] = true
                continue
            }

            // قاعدهٔ ۳: بازار تازه باز شد و پوزیشن از سشن قبل باز مانده → خروج با قیمت واقعی.
            val reopened = lastClosedStateBySymbol[symbol] == true
            if (trade.id in carriedOverClosureIds) {
                if (lastPrice == null || !tickIsFresh) {
                    _toast.value = "⏳ بازار $symbol باز شد؛ منتظر اولین قیمت معتبر برای بستن پوزیشن #${trade.displayTradeNumber}"
                    continue
                }
                val closed = container.journalStore.closeTradeAtPrice(
                    tradeId = trade.id,
                    exitPrice = lastPrice,
                    reason = "🕒 خروج در اولین قیمت معتبر پس از بازگشایی سشن $symbol (پوزیشن از سشن قبل باز مانده بود)",
                    now = now,
                )
                if (closed != null) {
                    carriedOverClosureIds.remove(trade.id)
                    _toast.value = String.format(
                        Locale.US,
                        "🕒 معاملهٔ #%d %s پس از بازگشایی بازار با قیمت واقعی %,.4f بسته شد (P/L %+.2f$)",
                        closed.displayTradeNumber,
                        symbol,
                        lastPrice,
                        closed.pnlUsd ?: 0.0,
                    )
                }
                lastClosedStateBySymbol[symbol] = false
                continue
            }
            if (reopened) {
                // یادگیری: پاداش واقعی خروج پیش از سشن با قیمت بازگشایی سنجیده می‌شود.
                SessionExitLearningStore.resolveReopen(symbol, lastPrice ?: 0.0, now)
                    ?.let { _toast.value = it }
                lastClosedStateBySymbol[symbol] = false
            }

            // قاعدهٔ ۲: خروج خودکار پیش از پایان سشن، در بازار زنده.
            if (plan.shouldCloseNow && lastPrice != null && tickIsFresh) {
                val reason = SessionExitPlanner.exitReasonFa(plan, lastPrice)
                val closed = container.journalStore.closeTradeAtPrice(
                    tradeId = trade.id,
                    exitPrice = lastPrice,
                    reason = reason,
                    now = now,
                )
                if (closed != null) {
                    SessionExitLearningStore.recordPreCloseExit(
                        trade = closed,
                        exitPrice = lastPrice,
                        leadMinutes = plan.leadMinutes,
                        now = now,
                    )
                    _toast.value = String.format(
                        Locale.US,
                        "⏱️ معاملهٔ #%d %s %d دقیقه پیش از پایان سشن بسته شد (P/L %+.2f$)",
                        closed.displayTradeNumber,
                        symbol,
                        plan.leadMinutes,
                        closed.pnlUsd ?: 0.0,
                    )
                }
                continue
            }

            // قاعدهٔ ۴: اصلاح پلن قدیمی و متورم.
            if (trade.id in repairedPlanTradeIds) continue
            // معامله‌ای که حد ضررش در سود قفل شده (ریسک‌فری/تریل) نباید با اصلاح پلن به
            // حد ضرر اولیه برگردد؛ این همان چیزی است که Preserve Mode را بی‌اثر می‌کرد.
            val stopLockedInProfit = trade.note.contains(IchimokuStrategyEngine.BE_TAG_MARK) ||
                trade.note.contains(IchimokuStrategyEngine.TRAIL_TAG_MARK)
            if (stopLockedInProfit) continue
            val report = _allSymbolStrategyReports.value[symbol]
                ?.takeIf { it.triggerInterval == trade.interval }
            val atr = report?.triggerSnapshot?.atr14 ?: 0.0
            val review = TradePlanGuard.reviewStoredPlan(
                symbol = symbol,
                action = trade.action,
                entryPrice = trade.entry,
                stopLoss = trade.initialStopLoss ?: trade.stopLoss,
                takeProfit = trade.takeProfit,
                triggerInterval = trade.interval,
                referenceAtr14 = atr,
                strategyAtr = report?.strategySnapshot?.atr14 ?: atr,
            )
            if (!review.needsRepair) {
                repairedPlanTradeIds.add(trade.id)
                continue
            }
            val repaired = container.journalStore.repairOpenTradePlan(
                tradeId = trade.id,
                newStopLoss = review.guarded.stopLoss,
                newTakeProfit = review.guarded.takeProfit,
                reasonFa = "🛡️ ${review.reasonFa}",
            )
            if (repaired != null) {
                repairedPlanTradeIds.add(trade.id)
                _toast.value = "🛡️ پلن معاملهٔ #${repaired.displayTradeNumber} $symbol (${trade.interval.label}) اصلاح شد — ${review.reasonFa}"
            }
        }
    }

    private fun startLiveMonitor() {
        if (liveMonitorJob?.isActive == true) return
        liveMonitorJob = container.appScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                // بهداشت پوزیشن‌ها: بستن پوزیشن بازارِ بسته + اصلاح پلن‌های متورم قدیمی
                runCatching { maintainTradingHygiene(now) }
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

                // ۵. چرخهٔ معامله‌گر: خودش را بازبینی و اصلاح می‌کند (حداکثر هر ۱۵ دقیقه).
                //    این حلقه همان چیزی است که «دستیار» را به «مدیر» تبدیل می‌کند: بدون آن، نکات
                //    و ماتریس برتری فقط وقتی باز کردن تب هوش دستی صدا زده می‌شد ساخته می‌شدند.
                runCatching { runAiTraderCycle(force = false) }

                // «سبک‌سازی»: وقتی پوزیشن بازی نیست و معاملهٔ خودکار هم خاموش است، حلقهٔ پایش
                // با فاصلهٔ بیشتری اجرا می‌شود تا پردازنده/باتری و رشتهٔ اصلی آزاد بمانند.
                val idleLight = openSymbolSet.isEmpty() && !settings.value.autoPaperTrading
                delay(if (idleLight) 12_000L else 4_000L)
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
        // بازیابی کندل‌های جامانده، بازپخش ژورنال و نوشتن JSON است؛ اجرای آن روی رشتهٔ اصلی
        // باعث توقف رابط کاربری (ANR) می‌شد.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
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
        latestTicks[symbol] = tick
        val coalesceAt = if (tick.at > 0L) tick.at else System.currentTimeMillis()
        if (uiCoalescer.shouldPublish("tick:$symbol", coalesceAt)) {
            _liveTicks.value = _liveTicks.value + (symbol to tick)
        }

        // Update display marks for open trades of this symbol
        if (uiCoalescer.shouldPublish("marks:$symbol", coalesceAt)) {
            var marks = _lastDisplayMarks.value
            trades.value.filter { it.isOpen && V1Universe.normalize(it.symbol) == symbol }.forEach { trade ->
                marks = OpenPositionDisplay.record(marks, trade, tick, tick.at)
            }
            _lastDisplayMarks.value = marks
        }

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
        }

        // شکار لحظه‌ای نقطه‌زنی ورود (Real-Time Sniper Pocket Fill) روی هر دو تایم‌فریم 15m و 5m و همه استراتژی‌های فعال (با الزام قطعی R:R >= 1:1.5)
        val cfg = settings.value
        val dualReport = _dualSymbolStrategyReports.value[symbol]
        val armedCandidates = dualReport?.sniperArmedReports
            ?.sortedByDescending { it.coreTrendScore }
            ?: listOfNotNull(_allSymbolStrategyReports.value[symbol]).filter { it.isActionable || it.isSniperArmedPendingRetest }
        if (cfg.autoPaperTrading && !SessionExitPlanner.plan(symbol, tick.at, learnedLeadMinutes(symbol)).shouldBlockEntry) {
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
                        // لنگر انداختن پلن گزارش روی قیمت لحظه‌ای + کهنگی‌سنجی + دالان نوسان تایم‌فریم
                        val guarded = TradePlanGuard.rebaseToLive(
                            symbol = symbol,
                            action = armedReport.action,
                            liveEntryPrice = tick.price,
                            planAnchorEntry = armedReport.suggestedEntry ?: armedReport.livePrice,
                            planStopLoss = sl,
                            planTakeProfit = rawTp,
                            referenceAtr = armedReport.triggerSnapshot?.atr14 ?: 0.0,
                            triggerInterval = armedReport.triggerInterval,
                            strategyAtr = armedReport.strategySnapshot?.atr14 ?: 0.0,
                            desiredRiskReward = (armedReport.optimalRiskReward ?: RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD)
                                .coerceAtLeast(RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD),
                        )
                        if (!guarded.isUsable) {
                            lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to armedReport.triggerBarTime)
                            continue
                        }
                        val calc = RiskAndCalendarEngine.calculatePosition(
                            symbolInput = symbol,
                            accountBalance = cfg.accountBalance,
                            riskPercent = cfg.riskPercent,
                            entry = tick.price,
                            stopLoss = guarded.stopLoss,
                            takeProfit = guarded.takeProfit,
                            preferredAction = armedReport.action,
                            fixedRiskUsdOverride = if (cfg.useFixedDollarRisk) cfg.fixedRiskUsd else null,
                            triggerInterval = armedReport.triggerInterval,
                            referenceAtr14 = armedReport.triggerSnapshot?.atr14 ?: 0.0,
                        )
                        if (calc != null && calc.riskReward >= RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD) {
                            val safety = EntrySafetyGate.evaluate(
                                symbol = symbol,
                                interval = armedReport.triggerInterval,
                                openTrades = trades.value,
                                nowMillis = tick.at,
                                signalBarTime = armedReport.triggerBarTime,
                                proposedRiskUsd = calc.riskBudgetUsd,
                                roundTripFeeUsd = calc.commissionUsd + calc.spreadCostUsd,
                                riskStatus = currentRiskLimitStatus(tick.at),
                                limits = safetyLimits(cfg),
                                enforceSession = true,
                                strategyId = armedReport.strategyId,
                            )
                            if (!safety.allowed) {
                                lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to armedReport.triggerBarTime)
                                val blockKey = "$fireKey|${armedReport.triggerBarTime}|safety"
                                if (rejectedPlanKeys.add(blockKey)) _toast.value = safety.summaryFa
                                continue
                            }
                            lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to armedReport.triggerBarTime)
                            // 🎩 لایهٔ رفتاری «عادت‌های معاملاتی» (۳۹ قاعدهٔ کتاب): ریسک ۱٪، حرارت ۳٪،
                            // R:R ≥ ۲، حد ضرر بیرون از نویز، ممنوعیت میانگین‌گیری روی ضرر و کوچک‌کردن
                            // سایز در سری باخت/افت از قله. اگر رد شود، معامله باز نمی‌شود.
                            val habits = applyTradingHabitsGuard(
                                calc = calc,
                                cfg = cfg,
                                atr = armedReport.triggerSnapshot?.atr14 ?: 0.0,
                                setupScorePct = armedReport.bookGateScorePct,
                                now = tick.at,
                                livePrice = tick.price,
                            )
                            if (habits == null) continue
                            val gatedCalc = habits.calc
                            val tfLabel = armedReport.triggerInterval.label
                            val sniperNote = "🎯 نقطه‌زنی [تایم $tfLabel] · [${armedReport.strategyTitleFa}] · (R:R 1:${gatedCalc.riskReward} · ${armedReport.trendPhase.badgeFa})" +
                                traderBudgetNote(armedReport.strategyId, gatedCalc) + habits.noteFa
                            openAutoTrackedTrade(
                                calc = gatedCalc,
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
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                backfillSingleSymbolInternal(normalized, hoursOverride = 6)
            }
        }
    }

    fun refreshSymbolLivePrice(symbolInput: String = _activeRiskSymbol.value, onLoaded: ((PriceTick?) -> Unit)? = null) {
        val normalized = V1Universe.normalize(symbolInput)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        maxOpenTrades: Int = settings.value.maxOpenTrades,
        maxOpenTradesPerSymbol: Int = settings.value.maxOpenTradesPerSymbol,
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
                // ۰ = بدون سقف (درخواست کاربر)؛ عدد دستی کاربر تا ۱۲/۴ همچنان اعمال می‌شود.
                maxOpenTrades = maxOpenTrades.coerceIn(0, 12),
                maxOpenTradesPerSymbol = maxOpenTradesPerSymbol.coerceIn(0, 4),
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


    val phoneVaultStatus: StateFlow<com.aurum.edge.data.PhoneVaultStatusSummary> =
        com.aurum.edge.data.PhoneStorageVaultManager.vaultStatus

    val phoneDailyBackups: StateFlow<List<com.aurum.edge.data.PhoneDailyBackupMeta>> =
        com.aurum.edge.data.PhoneStorageVaultManager.dailyBackups

    /**
     * بودجهٔ ریسکِ «معامله‌گر» برای یک استراتژی، به‌صورت یادداشت خوانا روی معامله.
     *
     * ماتریس برتری می‌گوید هر استراتژی چه سهمی از بودجهٔ ریسک را باید بگیرد؛ اگر سهم استراتژی
     * از سهم مساوی همه کمتر باشد (لبهٔ ضعیف‌تر اندازه‌گیری‌شده)، همین‌جا در یادداشت معامله گفته
     * می‌شود تا معلوم باشد تصمیم از کجا آمده است. هیچ معامله‌ای به‌خاطر این متن رد نمی‌شود —
     * ردکردن با [EntrySafetyGate] است که سقف‌ها را می‌سنجد.
     */
    private fun traderBudgetNote(
        strategyId: String,
        calc: RiskCalculationResult,
    ): String {
        val matrix = _aiTraderMatrix.value
        if (matrix.isEmpty()) return ""
        val row = matrix.firstOrNull { it.strategyId == strategyId } ?: return ""
        val evenShare = 1.0 / matrix.size.toDouble()
        val verdict = when {
            row.tradesCount == 0 -> "بدون نمونه — سهم آزمایشی"
            row.allocationShare <= 0.0 -> "لبهٔ منفی — سهم صفر"
            row.allocationShare > evenShare -> "لبهٔ بالاتر از میانگین — سهم کامل"
            else -> "لبهٔ زیر میانگین — سهم محدود"
        }
        val sharePct = String.format(Locale.US, "%.1f%%", row.allocationShare * 100.0)
        return "\n🧮 بودجهٔ معامله‌گر: $sharePct از بودجهٔ کل · $verdict · ${row.tradesCount} نمونه · ریسک این معامله " +
            "${String.format(Locale.US, "%.2f$", calc.riskBudgetUsd)}"
    }

    private fun executeOpenTradeNow(
        calc: RiskCalculationResult,
        note: String,
        entryInterval: Interval = Interval.M15,
        strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        strategyTitleFa: String = BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
    ) {
        val now = System.currentTimeMillis()
        val normSym = V1Universe.normalize(calc.symbol)
        // ورود روی نمادی که بازارش بسته است ممنوع می‌شود: قیمت‌ها کهنه‌اند و گپ بازگشایی سشن
        // می‌تواند حد ضرر را بدون امکان خروج رد کند (گزارش کاربر: معاملهٔ DOW در بازار بسته).
        // ورود در بازار بسته و همچنین در «پنجرهٔ خروج پیش از سشن» ممنوع است: معامله‌ای که تا پایان
        // سشن فرصت کارکردن ندارد، فقط ریسک گپ شبانه می‌خرد. پنجره با زمان سربارِ آموخته‌شده تعیین می‌شود.
        val sessionPlan = SessionExitPlanner.plan(
            symbol = normSym,
            now = now,
            learnedLeadMinutes = learnedLeadMinutes(normSym),
            hasOpenPosition = false,
        )
        if (sessionPlan.shouldBlockEntry) {
            _toast.value = "⛔ ورود به ${calc.symbol} در این لحظه مجاز نیست — " + sessionPlan.badgeFa +
                " · معامله باز نشد"
            return
        }
        val sessionWarningFa = if ((sessionPlan.minutesToExit ?: Long.MAX_VALUE) <= 45L) {
            "⏳ " + sessionPlan.badgeFa
        } else ""
        val effectiveNote = if (sessionWarningFa.isBlank()) note else "$note · $sessionWarningFa".trim(' ', '·')
        val matchedReport = _dualSymbolStrategyReports.value[normSym]
            ?.reportFor(entryInterval, strategyId)
        val matchedBreakdown = matchedReport?.rrBreakdown
        val entryCandles = LiveQuoteFetcher.peekCachedCandles(normSym, entryInterval)
        val openedRaw = container.journalStore.openTrade(
            calc = calc,
            note = effectiveNote,
            now = now,
            entryInterval = entryInterval,
            strategyId = strategyId,
            strategyTitleFa = strategyTitleFa,
            optimalRiskReward = matchedBreakdown?.optimalRiskReward ?: calc.riskReward,
            rrBreakdownFa = matchedBreakdown?.optimalRrReasonFa ?: "",
            preEntryCandles = entryCandles,
        )
        val condRecords = matchedReport?.conditions?.map { c ->
            com.aurum.edge.core.PaperConditionRecord(
                name = c.titleFa,
                status = if (c.passed) "CONFIRMED" else "BLOCKED",
                detail = c.detailFa,
            )
        }.orEmpty()
        // فیلدهای نئوویو در `PaperTrade` برای سازگاری با ژورنال قدیمی باقی مانده‌اند، اما
        // لایهٔ نئوویو حذف شده است؛ پس هیچ شمارشی روی معاملهٔ تازه ثبت نمی‌شود.
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
        refreshSymbolLivePrice(trade.symbol)
        syncBackgroundServiceState()
        val msg = "⚡ معامله ${trade.symbol} [تایم ${trade.interval.label}] · [${trade.strategyTitleFa}] (${if (trade.action == com.aurum.edge.domain.SignalAction.BUY) "خرید" else "فروش"}) باز شد · ${trade.preEntry3CandleCategoryFa}"
        _toast.value = msg
    }

    fun triggerManualDailyBackupNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.aurum.edge.data.PhoneStorageVaultManager.syncTradesToShardsAndDailyBackup(
                context = container.appContext,
                trades = trades.value,
                forceAllShards = true,
            )
            val status = com.aurum.edge.data.PhoneStorageVaultManager.refreshVaultStatus(
                context = container.appContext,
                allTrades = trades.value,
            )
            _toast.value = "💾 بک‌آپ روزانه (${status.latestBackupDateKey}) و خزانه ۱ ماهه کندل‌ها (${status.totalStoredCandlesCount} کندل) در حافظه گوشی ذخیره شد"
        }
    }

    fun restoreAllDataAndSnapshotsFromPhone() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val shardTrades = com.aurum.edge.data.PhoneStorageVaultManager.loadAllTradesFromPhoneShards(container.appContext)
            val restoredShards = if (shardTrades.isNotEmpty()) {
                container.journalStore.importTrades(shardTrades, replaceExisting = false)
            } else 0
            val restoredBrain = container.neuralEngine.restoreBrainBackupFromPhoneStorage(container.appContext)
            val vaultStat = com.aurum.edge.data.PhoneStorageVaultManager.refreshVaultStatus(
                context = container.appContext,
                allTrades = trades.value,
            )
            container.neuralEngine.syncWithClosedJournalTrades(trades.value)
            _toast.value = buildString {
                append("🔄 بازخوانی از حافظه گوشی: ")
                append("$restoredShards معامله از شاردهای روزانه · ")
                append("${vaultStat.totalStoredCandlesCount} کندل ۱ ماه اخیر")
                if (restoredBrain) append(" + شبکه عصبی")
            }
        }
    }

    /**
     * کلید «پاک‌سازی دادهٔ هوش مصنوعی استراتژی‌های حذف‌شده» (تنظیمات → داده و نگهداری).
     *
     * فقط ردیف‌های یتیم مغز (پروفایل تیونینگ، برچسب بهترین استراتژی دسته و نمونه‌های بافر
     * تکرار) پاک می‌شوند؛ هیچ وزن عصبی، ژورنال یا معامله‌ای دست نمی‌خورد.
     */
    fun purgeOrphanAiStrategyData() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val report = container.neuralEngine.purgeOrphanStrategyData()
            _toast.value = "🧹 " + report.summaryFa
        }
    }

    /** ذخیرهٔ حافظهٔ شبکهٔ عصبی روی گوشی (بکاپ). مستقل از آرشیو تصویری است و باقی می‌ماند. */
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

    /** بستن دستی یک معاملهٔ باز با قیمت لحظه‌ای. */
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

    /** حذف یک معامله از ژورنال. */
    fun deleteJournalTrade(tradeId: String) {
        container.journalStore.deleteTrade(tradeId)
        _toast.value = "معامله از ژورنال حذف شد"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // عیب‌یابی: گزارش کرش‌های ثبت‌شده داخل اپ
    // ─────────────────────────────────────────────────────────────────────────────

    private val _crashReports = MutableStateFlow<List<com.aurum.edge.core.CrashReporter.CrashEntry>>(emptyList())
    val crashReports: StateFlow<List<com.aurum.edge.core.CrashReporter.CrashEntry>> = _crashReports.asStateFlow()

    /** خواندن فهرست گزارش‌های کرش از حافظهٔ اپ (روی IO). */
    fun refreshCrashReports() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val entries = runCatching {
                com.aurum.edge.core.CrashReporter.list(container.appContext)
            }.getOrDefault(emptyList())
            _crashReports.value = entries
        }
    }

    /** کپی متن کامل جدیدترین گزارش کرش در کلیپ‌بورد (با پیام فارسی). */
    fun copyLatestCrashReport() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val latest = runCatching {
                com.aurum.edge.core.CrashReporter.latest(container.appContext)
            }.getOrNull()
            if (latest == null) {
                _toast.value = "⚠️ هیچ گزارش کرشی ثبت نشده است"
                return@launch
            }
            val text = com.aurum.edge.core.CrashReporter.readFull(container.appContext, latest)
            if (text.isBlank()) {
                _toast.value = "⚠️ فایل گزارش خالی بود"
                return@launch
            }
            copyTextToClipboard(
                labelFa = "گزارش کرش",
                text = text,
                successToastFa = "📋 گزارش کرش کپی شد — برای بررسی بفرستید",
            )
        }
    }

    /** پاک کردن گزارش‌های کرش. */
    fun clearCrashReports() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val removed = runCatching {
                com.aurum.edge.core.CrashReporter.clear(container.appContext)
            }.getOrDefault(0)
            _crashReports.value = emptyList()
            _toast.value = if (removed > 0) "🗑️ $removed گزارش کرش پاک شد" else "ℹ️ گزارشی برای پاک کردن نبود"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // بکاپ اسکرین‌شات: حذف کامل (گرفتن، ذخیره، اشتراک، بازگردانی و آمار تعداد/حجم)
    // ─────────────────────────────────────────────────────────────────────────────

    fun clearAllJournalTrades() {
        container.journalStore.clear()
        _toast.value = "تمام سوابق ژورنال پاک شد"
    }

    /**
     * سقف‌های دروازهٔ ایمنی از تنظیمات کاربر.
     *
     * تا پیش از این، سقف ۳ پوزیشن و ۱ پوزیشن هر نماد داخل `EntrySafetyGate` سخت‌کد بود؛
     * کاربر می‌توانست فقط در صفحهٔ ژورنال «۱۵۰ پوزیشن باز» ببیند بدون اینکه چیزی قابل تنظیم باشد.
     */
    /**
     * سقف‌های لایهٔ رفتاری «عادت‌های معاملاتی» از تنظیمات کاربر.
     *
     * منبع اعداد: کتاب *Trading Habits* (Steve Burns / Holly Burns) — قواعد ۱ تا ۳۹.
     * پیش‌فرض‌ها همان اعداد کتاب‌اند (۱٪ ریسک هر معامله، ۳٪ ریسک باز، R:R ≥ ۲، حد ضرر ≥ ۱×ATR).
     */
    private fun habitsLimits(cfg: AppSettings): TradingHabitsPolicy.Limits = TradingHabitsPolicy.Limits(
        maxRiskPerTradePct = cfg.habitsMaxRiskPerTradePct.coerceIn(0.05, 5.0),
        maxOpenRiskPct = cfg.habitsMaxOpenRiskPct.coerceIn(0.25, 20.0),
        minRiskReward = cfg.habitsMinRiskReward.coerceIn(1.0, 5.0),
        minStopAtrMultiple = cfg.habitsStopAtrMultiple.coerceIn(0.0, 5.0),
        hardStopDrawdownPct = cfg.habitsHaltDrawdownPct.coerceIn(2.0, 60.0),
        enabled = cfg.habitsGuardEnabled,
    )

    /**
     * خروجی داوری لایهٔ رفتاری: معاملهٔ مجاز (شاید با ریسک کوچک‌شده) + یادداشتی که روی
     * ژورنال معامله نوشته می‌شود.
     */
    private data class HabitsOutcome(
        val calc: RiskCalculationResult,
        val noteFa: String,
        val decision: TradingHabitsPolicy.Decision,
    )

    /** آخرین داوری لایهٔ رفتاری — برای نمایش در UI بدون محاسبهٔ دوباره. */
    private val _lastHabitsDecision = kotlinx.coroutines.flow.MutableStateFlow<TradingHabitsPolicy.Decision?>(null)
    val lastHabitsDecision: kotlinx.coroutines.flow.StateFlow<TradingHabitsPolicy.Decision?> = _lastHabitsDecision

    /**
     * # داوری ورود خودکار با ۳۹ قاعدهٔ کتاب «Trading Habits»
     *
     * این تابع بین `EntrySafetyGate` (سقف‌های سخت: سشن، کهنگی ماشه، هزینه، سقف ریسک روزانه) و
     * باز کردن واقعی معامله می‌نشیند و لایهٔ **رفتاری** را اضافه می‌کند:
     *
     * | قاعده | اثر در این تابع |
     * |---|---|
     * | R02/R22 | ورود بدون سیگنال کمّی رد می‌شود |
     * | R03 | R:R کمتر از ۲ رد می‌شود |
     * | R39 | حد ضرر نزدیک‌تر از ۱×ATR رد می‌شود |
     * | R32 | ریسک هر معامله به ۱٪ حساب محدود می‌شود |
     * | R34 | مجموع ریسک باز از ۳٪ نمی‌گذرد |
     * | R36 | افزودن به پوزیشن در ضرر رد می‌شود |
     * | R23 | افت از **قله** سایز را نصف/ربع می‌کند و از حد تحمل می‌گذرد → قفل |
     * | R35 | پس از ۳ باخت پیاپی سایز نصف، پس از ۵ باخت ربع |
     * | R31 | کیفیت ستاپ (A/B/C) سایز را پله‌ای می‌کند |
     * | R38 | جهش ۵۰٪ نوسان با کوچک‌کردن ریسک مؤثر پوشش داده می‌شود |
     *
     * @return `null` = رد شد (پیام در توست می‌آید). مقدار غیر null = همان پلن، شاید کوچک‌تر.
     */
    private fun applyTradingHabitsGuard(
        calc: RiskCalculationResult,
        cfg: AppSettings,
        atr: Double,
        setupScorePct: Double,
        now: Long,
        livePrice: Double,
    ): HabitsOutcome? {
        if (!cfg.habitsGuardEnabled) {
            val off = dummyHabitsDecision(calc)
            _lastHabitsDecision.value = off
            return HabitsOutcome(calc, "", off)
        }

        val allTrades = trades.value
        val equity = calc.accountBalance.takeIf { it.isFinite() && it > 0.0 } ?: cfg.accountBalance
        // قله فقط وقتی معنا دارد که معاملهٔ بسته‌شده‌ای وجود داشته باشد؛ وگرنه «افت سرمایه»
        // صرفاً فاصلهٔ موجودی از سرمایهٔ اولیه است و نباید ورود خودکار را قفل کند (R23).
        val hasClosed = allTrades.any { !it.isOpen }
        val peakSeed = if (hasClosed) cfg.initialCapital.coerceAtLeast(equity) else equity
        val peak = maxOf(equity, TradingHabitsPolicy.peakEquityUsd(allTrades, peakSeed))
        val streak = TradingHabitsPolicy.lossStreak(allTrades)
        val requestedRiskPct = if (equity > 0.0) calc.riskBudgetUsd / equity * 100.0 else cfg.riskPercent

        val request = TradingHabitsPolicy.EntryRequest(
            symbol = calc.symbol,
            action = calc.action,
            entry = calc.entry,
            stopLoss = calc.stopLoss,
            takeProfit = calc.takeProfit,
            atr = atr,
            equityUsd = equity,
            peakEquityUsd = peak,
            requestedRiskPct = requestedRiskPct,
            openRiskPct = TradingHabitsPolicy.openRiskPct(allTrades, equity),
            lossStreak = streak,
            setupScorePct = setupScorePct,
            livePrice = livePrice,
            openTrades = allTrades,
            hasQuantifiedSignal = true,
            limits = habitsLimits(cfg),
        )
        val decision = TradingHabitsPolicy.evaluate(request)
        _lastHabitsDecision.value = decision

        if (!decision.allowed) {
            _toast.value = decision.summaryFa
            return null
        }

        val scaledRiskUsd = calc.riskBudgetUsd * decision.sizeMultiplier
        val resized = if (scaledRiskUsd > 0.0 && scaledRiskUsd < calc.riskBudgetUsd - 0.005) {
            RiskAndCalendarEngine.calculatePosition(
                symbolInput = calc.symbol,
                accountBalance = equity,
                riskPercent = cfg.riskPercent,
                entry = calc.entry,
                stopLoss = calc.stopLoss,
                takeProfit = calc.takeProfit,
                preferredAction = calc.action,
                fixedRiskUsdOverride = scaledRiskUsd,
            ) ?: calc
        } else {
            calc
        }
        val note = buildString {
            append("\n")
            append(decision.summaryFa)
            decision.notesFa.forEach { append("\n· ").append(it) }
        }
        return HabitsOutcome(resized, note, decision)
    }

    /** روشن/خاموش‌کردن لایهٔ رفتاری «عادت‌های معاملاتی». */
    fun setHabitsGuardEnabled(enabled: Boolean) {
        container.settingsStore.update { it.copy(habitsGuardEnabled = enabled) }
        _toast.value = if (enabled) {
            "🎩 لایهٔ عادت‌های معاملاتی روشن شد: ریسک ۱٪ · حرارت ۳٪ · R:R ≥ ۲ · حد ضرر بیرون از نویز"
        } else {
            "🎩 لایهٔ عادت‌های معاملاتی خاموش شد (ورود خودکار بدون داوری رفتاری)"
        }
    }

    /**
     * ذخیرهٔ اعداد لایهٔ رفتاری از کارت تنظیمات.
     *
     * سقف‌ها با بازهٔ ایمن محدود می‌شوند تا یک عدد اشتباه (مثلاً ۵۰٪ ریسک) کل حساب را به باد
     * ندهد؛ خودِ کتاب هم ۱٪ و ۳٪ را سقف می‌داند و بالاتر را «مشکل حجم حساب» می‌نامد.
     */
    fun saveHabitsLimits(
        maxRiskPerTradePct: Double,
        maxOpenRiskPct: Double,
        minRiskReward: Double,
        stopAtrMultiple: Double,
        haltDrawdownPct: Double,
    ) {
        container.settingsStore.update {
            it.copy(
                habitsMaxRiskPerTradePct = maxRiskPerTradePct.coerceIn(0.05, 5.0),
                habitsMaxOpenRiskPct = maxOpenRiskPct.coerceIn(0.25, 20.0),
                habitsMinRiskReward = minRiskReward.coerceIn(1.0, 5.0),
                habitsStopAtrMultiple = stopAtrMultiple.coerceIn(0.0, 5.0),
                habitsHaltDrawdownPct = haltDrawdownPct.coerceIn(2.0, 60.0),
            )
        }
        _toast.value = "🎩 اعداد لایهٔ عادت‌های معاملاتی ذخیره شد"
    }

    /** بازگرداندن اعداد لایه به همان اعداد صریح کتاب (۱٪ · ۳٪ · ۲R · ۱×ATR · ۱۵٪). */
    fun resetHabitsToBookDefaults() {
        container.settingsStore.update {
            it.copy(
                habitsGuardEnabled = true,
                habitsMaxRiskPerTradePct = TradingHabitsPolicy.MAX_RISK_PER_TRADE_PCT,
                habitsMaxOpenRiskPct = TradingHabitsPolicy.MAX_OPEN_RISK_PCT,
                habitsMinRiskReward = TradingHabitsPolicy.MIN_RISK_REWARD,
                habitsStopAtrMultiple = TradingHabitsPolicy.MIN_STOP_ATR_MULTIPLE,
                habitsHaltDrawdownPct = TradingHabitsPolicy.DEFAULT_HARD_STOP_DRAWDOWN_PCT,
            )
        }
        _toast.value = "🎩 اعداد لایهٔ عادت‌های معاملاتی روی مقادیر کتاب بازنشانی شد"
    }

    /** یک داوری خالی برای وقتی لایه خاموش است و هیچ داوری قبلی هم نیست. */
    private fun dummyHabitsDecision(calc: RiskCalculationResult): TradingHabitsPolicy.Decision =
        TradingHabitsPolicy.Decision(
            allowed = true,
            reasonsFa = emptyList(),
            sizeMultiplier = 1.0,
            effectiveRiskPct = calc.riskPercent,
            riskUsd = calc.riskBudgetUsd,
            grade = TradingHabitsPolicy.SetupGrade.B,
            riskReward = calc.riskReward,
            neededWinRatePct = TradingHabitsPolicy.neededWinRatePct(calc.riskReward),
            drawdownFromPeakPct = 0.0,
            heatPct = 0.0,
            stopAtrRatio = 0.0,
            atrPctOfPrice = 0.0,
            summaryFa = "🎩 عادت‌های معاملاتی: خاموش",
        )

    private fun safetyLimits(cfg: AppSettings): EntrySafetyGate.Limits = EntrySafetyGate.Limits(
        // ۰ = بدون سقف. سقف پوزیشن یک «دلیل اضافهٔ رد معامله» بود و برداشته شد.
        maxConcurrentPositions = cfg.maxOpenTrades.coerceIn(0, 12),
        maxPositionsPerSymbol = cfg.maxOpenTradesPerSymbol.coerceIn(0, 4),
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // معامله‌گر هوشمند: چرخهٔ کامل تصمیم‌گیری
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * یک چرخهٔ کامل معامله‌گر.
     *
     * سه کار انجام می‌دهد و هر سه را **ذخیره** می‌کند:
     *
     * ۱. **پیش‌بینی خبری** روی همهٔ نمادهای رصدشده: شرایط نوظهور هر نماد را پیش از رسیدن به
     *    سیگنال بیرون می‌کشد (نه واکنش، پیش‌دستی).
     * ۲. **خودترمیمی**: از گلوگاه‌های واقعی (دلیل‌های باخت) و نکات دستیار، اقدام‌های مشخص با
     *    نمونه می‌سازد و روی کانفیگ اعمال می‌کند — نکته به تغییر تبدیل می‌شود.
     * ۳. **ماتریس برتری**: بودجهٔ ریسک هر استراتژی را با لبهٔ تعدیل‌شده با نمونه توزیع می‌کند.
     *
     * @param force اگر false باشد، حداکثر هر ۱۵ دقیقه یک‌بار اجرا می‌شود (هزینهٔ محاسباتی).
     */
    fun runAiTraderCycle(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAiTraderCycleAtMs < AI_TRADER_CYCLE_MS) return
        lastAiTraderCycleAtMs = now
        container.appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { runAiTraderCycleInternal(now) }.onFailure { err ->
                CrashReporter.recordNonFatal(container.appContext, "aiTraderCycle", err)
            }
        }
    }

    private suspend fun runAiTraderCycleInternal(now: Long) {
        val cfg = settings.value
        val journal = trades.value

        // ── ۱. پیش‌بینی خبری روی همهٔ نمادهای رصدشده ──
        val reportsBySymbol: Map<String, List<StrategyEvaluationReport>> =
            _dualSymbolStrategyReports.value.mapValues { (_, dual) ->
                dual.reports15mByStrategy.values.toList() + dual.reports5mByStrategy.values.toList()
            }
        // وضعیت خبری/ماکرو و انتساب علیّ از همان موتور عصبی می‌آید؛ پیش‌بینی نمادها باید از خبر
        // هم اثر بگیرد، نه فقط از تکنیکال. اگر خبری نباشد، این ورودی‌ها خالی‌اند و رفتار خنثی است.
        val neuralSummaryNow = container.neuralEngine.state.value
        val forecasts = AiTraderEngine.forecastEmergingConditions(
            reportsBySymbol = reportsBySymbol,
            nowMillis = now,
            newsSnapshot = neuralSummaryNow.latestNewsSnapshot,
            attributions = neuralSummaryNow.liveMarketAttributions,
        )
        _aiTraderForecasts.value = forecasts

        // ── ۲. خودترمیمی: گلوگاه‌ها را از معاملات واقعی بردار ──
        val blockedReasons = buildLossReasonDistribution(journal)
        val notes = runCatching {
            com.aurum.edge.core.MasterIchimokuTradingAssistant.generateActionableImprovementNotes(
                trades = journal,
                dualReports = _dualSymbolStrategyReports.value,
                currentConfig = cfg.baseStrategyConfig,
            )
        }.getOrDefault(emptyList())

        val activeModel = cfg.activeBitcoinStrategyModel()
        val healings = AiTraderEngine.healSelf(
            strategyId = activeModel.id,
            strategyTitleFa = activeModel.titleFa,
            config = cfg.baseStrategyConfig,
            blockedReasons = blockedReasons,
            notes = notes,
            nowMillis = now,
        )
        _aiTraderHealings.value = healings

        // اصلاح‌ها فقط اگر کاربر «مدیریت خودکار» را روشن کرده باشد روی موتور اعمال می‌شوند.
        if (healings.isNotEmpty() && cfg.autoPaperTrading) {
            val (nextConfig, applied) = AiTraderEngine.applyHealing(cfg.baseStrategyConfig, healings)
            if (applied.isNotEmpty()) {
                container.settingsStore.update { current -> current.copy(baseStrategyConfig = nextConfig) }
                TraderMemoryStore.recordActions(applied, container.appContext)
            }
        }

        // ── ۳. ماتریس برتری و توزیع بودجهٔ ریسک ──
        val summary = neuralSummaryNow
        val matrix = AiTraderEngine.buildEdgeMatrix(summary.strategyEdgeMatrix)
        _aiTraderMatrix.value = matrix

        // ── ۴. ذخیرهٔ همیشگی درس‌ها و برداشت‌ها روی گوشی ──
        val lines = AiTraderEngine.buildTraderSummaryFa(
            forecasts = forecasts,
            healings = healings,
            matrix = matrix,
        ) + summary.learnedInsightsFeedFa.take(20)
        val headLine = "چرخهٔ معامله‌گر: ${forecasts.count { it.isActionable }} نماد آماده · " +
            "${healings.size} اصلاح · ${matrix.count { it.allocationShare > 0.0 }} استراتژی با بودجه"
        _aiTraderSummaryFa.value = lines
        val memory = TraderMemoryStore.recordLessons(
            linesFa = lines,
            summaryFa = headLine,
            context = container.appContext,
        )
        _aiTraderMemory.value = memory
    }

    /**
     * توزیع دلیل‌های باخت از ژورنال.
     *
     * منبع، متن دلیل بستن هر معاملهٔ باخته است؛ همان چیزی که کاربر در ژورنال می‌بیند. اگر دلیل
     * ثبت نشده باشد، معامله با برچسب «دلیل نامشخص» شمرده می‌شود تا مجموع همیشه با تعداد
     * باخت‌ها بخواند و درصدها ساختگی نشوند.
     */
    private fun buildLossReasonDistribution(journal: List<PaperTrade>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        journal.filter { !it.isOpen }.forEach { trade ->
            val pnl = trade.pnlUsd ?: 0.0
            if (pnl >= 0.0) return@forEach
            val reason = trade.exitReason?.takeIf { it.isNotBlank() }
                ?: trade.note.takeIf { it.isNotBlank() }
                ?: "دلیل نامشخص"
            // خیلی طولانی نکن؛ ۶۰ کاراکتر اول برای الگوشناسی کافی است.
            val key = reason.take(60)
            out[key] = (out[key] ?: 0) + 1
        }
        return out
    }

    /**
     * برگرداندن یک اصلاح خودترمیم به مقدار قبلی.
     *
     * خودترمیمی بی‌بازگشت، همان چیزی است که کاربر از آن می‌ترسد. هر اقدام مقدار `previousValue`
     * را با خود دارد و [AiTraderEngine.revertActions] می‌تواند پارامتر را دقیقاً به همان عدد
     * برگرداند؛ این تابع همان مسیر را از UI صدا می‌زند و نتیجه را در حافظهٔ گوشی هم ثبت می‌کند
     * تا بعد از بستن اپ، «این اصلاح برگشت داده شد» فراموش نشود.
     */
    fun revertSelfHealing(action: AiTraderEngine.SelfHealingAction) {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val cfg = settings.value
                val reverted = AiTraderEngine.revertActions(cfg.baseStrategyConfig, listOf(action))
                container.settingsStore.update { current -> current.copy(baseStrategyConfig = reverted) }
                TraderMemoryStore.write { snap ->
                    snap.copy(
                        appliedActions = snap.appliedActions.filterNot { it.id == action.id },
                        activeSignatures = snap.activeSignatures - action.kind.name,
                        totalHealCount = (snap.totalHealCount - 1).coerceAtLeast(0),
                        lessonLinesFa = (snap.lessonLinesFa + "↩️ اصلاح ${action.kind.labelFa} برگشت داده شد " +
                            "(${String.format(Locale.US, "%.2f", action.newValue)} → " +
                            "${String.format(Locale.US, "%.2f", action.previousValue)})").takeLast(60),
                    )
                }
                _aiTraderHealings.value = _aiTraderHealings.value.filterNot { it.id == action.id }
                _aiTraderMemory.value = TraderMemoryStore.read(container.appContext)
                _toast.value = "↩️ ${action.kind.labelFa} به مقدار قبلی برگشت " +
                    "(${String.format(Locale.US, "%.2f", action.previousValue)})"
            }.onFailure { err ->
                CrashReporter.recordNonFatal(container.appContext, "revertSelfHealing", err)
                _toast.value = "⚠️ برگرداندن اصلاح ناموفق بود: ${err.message}"
            }
        }
    }

    /** خروجی دستی: اجرای فوری چرخه (دکمهٔ «همین حالا تحلیل کن»). */
    fun runAiTraderCycleNow() = runAiTraderCycle(force = true)

    /** بازخوانی حافظهٔ معامله‌گر از گوشی برای نمایش. */
    fun refreshAiTraderMemory() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _aiTraderMemory.value = TraderMemoryStore.read(container.appContext)
        }
    }

    /** پاک‌کردن حافظهٔ معامله‌گر (فقط با تصمیم صریح کاربر). */
    fun clearAiTraderMemory() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _aiTraderMemory.value = TraderMemoryStore.clear(container.appContext)
            _toast.value = "🗑️ حافظهٔ معامله‌گر پاک شد"
        }
    }

    /** مسیر فایل حافظهٔ معامله‌گر (برای نمایش در UI). */
    fun aiTraderMemoryPath(): String =
        TraderMemoryStore.storagePathFa(container.appContext)

    /** کپی خلاصهٔ درس‌های ذخیره‌شده در کلیپ‌بورد. */
    fun copyAiTraderLessons() {
        val lines = _aiTraderSummaryFa.value.ifEmpty {
            TraderMemoryStore.read(container.appContext).lessonLinesFa
        }
        if (lines.isEmpty()) {
            _toast.value = "⚠️ هنوز درسی ذخیره نشده است"
            return
        }
        copyTextToClipboard(
            labelFa = "خلاصهٔ معامله‌گر",
            text = lines.joinToString("\n"),
            successToastFa = "📋 خلاصهٔ معامله‌گر کپی شد",
        )
    }

    /**
     * وضعیت سقف ضرر روزانه/افت سرمایه با تنظیمات فعلی. مسیر معاملهٔ خودکار پیش از این هیچ‌جا
     * آن را نمی‌سنجید و تابع `evaluateRiskLimits` فقط برای نمایش در صفحهٔ ژورنال صدا زده می‌شد.
     */
    fun currentRiskLimitStatus(now: Long = System.currentTimeMillis()): RiskLimitStatus? = runCatching {
        val cfg = settings.value
        RiskAndCalendarEngine.evaluateRiskLimits(
            accountBalance = cfg.accountBalance,
            initialCapital = cfg.initialCapital,
            dayOpeningEquity = dayOpeningEquity(now),
            dailyLossLimitPercent = cfg.dailyLossLimitPercent,
            maxDrawdownLimitPercent = cfg.maxDrawdownLimitPercent,
            trades = trades.value,
            now = now,
        )
    }.getOrNull()

    fun dayOpeningEquity(now: Long = System.currentTimeMillis()): Double =
        runCatching { container.settingsStore.dayOpeningEquity(now) }
            .getOrDefault(settings.value.accountBalance)

    fun resumeVisibleForexFeed() {
        syncAllOpenAndActiveSymbolsGap(notifyOnSuccess = false)
        startLiveMonitor()
    }

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
        if (strategyId in BitcoinStrategyModel.temporarilyDisabledIds) {
            // این شاخه برای استراتژی‌های خاموش آینده است؛ پیمان دیگر در آن فهرست نیست.
            _toast.value = "⏸ این استراتژی موقتاً غیرفعال است و فعلاً قابل فعال‌سازی نیست"
            return
        }
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
            val allIds = (BitcoinStrategyModel.activeStrategyIds + current.customStrategies.map { it.id }).toSet()
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
        if (strategyId in BitcoinStrategyModel.temporarilyDisabledIds) {
            // این شاخه برای استراتژی‌های خاموش آینده است؛ پیمان دیگر در آن فهرست نیست.
            _toast.value = "⏸ این استراتژی موقتاً غیرفعال است و فعلاً قابل فعال‌سازی نیست"
            return
        }
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
        // اسکن ۱۵ نماد، محاسبهٔ سنگین موتور است؛ روی رشتهٔ اصلی اجرا نمی‌شود (جلوگیری از ANR).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
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
        // «درک زندهٔ بازار» فقط برای بیت‌کوین ساخته می‌شود.
        if (V1Universe.normalize(symbol) == NeuralSelfLearningEngine.LIVE_ATTRIBUTION_SYMBOL) {
            runCatching {
                container.neuralEngine.updateLiveMarketCausalAttribution(dualReport.primaryReport)
            }
        }
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

            // ۱. مدیریت معاملات باز با حد ضرر کاملاً ثابت (غیرپله‌ای) و حفظ R:R اولیه (بستن فقط در هدف اولیه یا برگشت قطعی روند در سود)
            for (dual in newlyEvaluatedDuals) {
                val symbol = dual.symbol
                val openSymbolTrades = trades.value.filter {
                    it.isOpen && V1Universe.normalize(it.symbol) == symbol
                }
                for (openTrade in openSymbolTrades) {
                    // ⛔ خروج زمانی حذف شد (۳.۹.۷۶): بستن معاملهٔ روندی به‌خاطر «۲۰ کندل گذشت»
                    // تز را باطل نمی‌کند. خروج فقط با یکی از سه دلیل ساختاری انجام می‌شود:
                    // ۱) برخورد حد ضرر ثابت، ۲) رسیدن به هدف، ۳) برگشت قطعی روند **در سود**
                    // (Preserve Mode / trend-health در ادامهٔ همین حلقه).
                    val matchingReport = dual.reportFor(openTrade.interval, openTrade.strategyId)
                    val preserveUpdate = IchimokuStrategyEngine.evaluateOpenTradeManagement(
                        trade = openTrade,
                        report = matchingReport,
                        config = stratConfig,
                    )
                    if (preserveUpdate != null && preserveUpdate.updatedStopLoss != null) {
                        val stageNote = when (preserveUpdate.stopStageTag) {
                            "BE" -> "${IchimokuStrategyEngine.BE_TAG_MARK} ریسک‌فری در ${preserveUpdate.lockedRMultiple}R"
                            "TRAIL" -> "${IchimokuStrategyEngine.TRAIL_TAG_MARK} قفل سود روی تنکان در ${preserveUpdate.lockedRMultiple}R"
                            else -> preserveUpdate.stageTitleFa
                        }
                        val ratcheted = container.journalStore.updateTradeStopLoss(
                            tradeId = openTrade.id,
                            newStopLoss = preserveUpdate.updatedStopLoss,
                            stageNote = stageNote,
                            newTakeProfit = preserveUpdate.updatedTakeProfit,
                            allowRatchet = true,
                        )
                        if (ratcheted != null) {
                            _toast.value = "${preserveUpdate.stageTitleFa}: ${openTrade.symbol} (${openTrade.interval.label})"
                        }
                    }
                    if (preserveUpdate != null && preserveUpdate.shouldCloseImmediatelyInProfit) {
                        val exitP = preserveUpdate.closePrice ?: matchingReport.livePrice
                        container.journalStore.closeTradeAtPrice(
                            tradeId = openTrade.id,
                            exitPrice = exitP,
                            reason = preserveUpdate.reasonFa,
                            now = now,
                        )
                        _toast.value = "${preserveUpdate.stageTitleFa}: معامله ${openTrade.symbol} در قیمت $exitP با سود بسته شد (${preserveUpdate.reasonFa})"
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
                            // لنگر انداختن نقطهٔ ورود، حد ضرر و حد سود به قیمت لحظه‌ای اجرا + دالان نوسان تایم‌فریم
                            val liveTickPrice = _liveTicks.value[symbol]?.price?.takeIf { it.isFinite() && it > 0.0 }
                            val executionEntry = liveTickPrice
                                ?: report.livePrice?.takeIf { it.isFinite() && it > 0.0 }
                                ?: rawEntry
                            val desiredRr = (report.optimalRiskReward ?: RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD)
                                .coerceAtLeast(RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD)
                            val guarded = TradePlanGuard.rebaseToLive(
                                symbol = symbol,
                                action = report.action,
                                liveEntryPrice = executionEntry,
                                planAnchorEntry = rawEntry,
                                planStopLoss = rawSl,
                                planTakeProfit = rawTp,
                                referenceAtr = report.triggerSnapshot?.atr14 ?: 0.0,
                                triggerInterval = report.triggerInterval,
                                strategyAtr = report.strategySnapshot?.atr14 ?: 0.0,
                                desiredRiskReward = desiredRr,
                            )
                            if (!guarded.isUsable) {
                                // پلن کهنه/غیرمعتبر است: با اعداد قدیمی معامله باز نمی‌شود و پیام تکراری هم ساخته نمی‌شود
                                val rejectKey = "$fireKey|${report.triggerBarTime}"
                                if (rejectedPlanKeys.add(rejectKey)) {
                                    _toast.value = "🛡️ ورود ${report.symbol} (${report.triggerInterval.label}) انجام نشد: " +
                                        (guarded.reasonsFa.firstOrNull() ?: "پلن نامعتبر")
                                }
                                continue
                            }
                            val calc = RiskAndCalendarEngine.calculatePosition(
                                symbolInput = symbol,
                                accountBalance = cfg.accountBalance,
                                riskPercent = cfg.riskPercent,
                                entry = executionEntry,
                                stopLoss = guarded.stopLoss,
                                takeProfit = guarded.takeProfit,
                                preferredAction = report.action,
                                fixedRiskUsdOverride = if (cfg.useFixedDollarRisk) cfg.fixedRiskUsd else null,
                                triggerInterval = report.triggerInterval,
                                referenceAtr14 = report.triggerSnapshot?.atr14 ?: 0.0,
                            )
                            if (calc != null && calc.riskReward >= RiskAndCalendarEngine.MIN_REQUIRED_RISK_REWARD) {
                                // 🚧 دروازهٔ ایمنی ورود: سقف پوزیشن، سقف ضرر روزانه، سشن، انقضای ماشه و
                                // نسبت هزینه به ریسک. پیش از این هیچ‌کدام در مسیر معامله بررسی نمی‌شد.
                                val safety = EntrySafetyGate.evaluate(
                                    symbol = symbol,
                                    interval = report.triggerInterval,
                                    openTrades = trades.value,
                                    nowMillis = now,
                                    signalBarTime = report.triggerBarTime,
                                    proposedRiskUsd = calc.riskBudgetUsd,
                                    roundTripFeeUsd = calc.commissionUsd + calc.spreadCostUsd,
                                    riskStatus = currentRiskLimitStatus(now),
                                    limits = safetyLimits(cfg),
                                    enforceSession = cfg.autoPaperTrading,
                                    strategyId = report.strategyId,
                                )
                                if (!safety.allowed) {
                                    lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to report.triggerBarTime)
                                    val blockKey = "$fireKey|${report.triggerBarTime}|safety"
                                    if (rejectedPlanKeys.add(blockKey)) {
                                        _toast.value = safety.summaryFa
                                    }
                                    continue
                                }
                                lastAutoFiredBarTimeByKey = lastAutoFiredBarTimeByKey + (fireKey to report.triggerBarTime)
                                // 🎩 لایهٔ رفتاری «عادت‌های معاملاتی» (کتاب Trading Habits · قواعد ۱ تا ۳۹)
                                val habits = applyTradingHabitsGuard(
                                    calc = calc,
                                    cfg = cfg,
                                    atr = report.triggerSnapshot?.atr14 ?: 0.0,
                                    setupScorePct = report.bookGateScorePct,
                                    now = now,
                                    livePrice = executionEntry,
                                )
                                if (habits == null) continue
                                val gatedCalc = habits.calc
                                val tfLabel = report.triggerInterval.label
                                val winProbPct = (neuralEval.predictedWinProb * 100.0).toInt()
                                val autoNote = "⚡ خودکار [تایم $tfLabel] · [${report.strategyTitleFa}] · (R:R 1:${gatedCalc.riskReward} · ${report.trendPhase.badgeFa}) · [NN:${neuralEval.conditionKey} · Win:${winProbPct}%]" + habits.noteFa
                                openAutoTrackedTrade(
                                    calc = gatedCalc,
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

    fun setAlarmVibrationEnabled(enabled: Boolean) {
        container.settingsStore.update { it.copy(alarmVibrationEnabled = enabled) }
        _toast.value = if (enabled) "📳 ویبره اختصاصی آلارم‌ها فعال شد" else "📴 ویبره آلارم‌ها غیرفعال شد"
    }

    fun setAlarmStageEnabled(
        kind: com.aurum.edge.notify.TradeAlarmKind,
        enabled: Boolean,
    ) {
        container.settingsStore.update { cur ->
            when (kind) {
                com.aurum.edge.notify.TradeAlarmKind.ENTRY -> cur.copy(entryAlarmEnabled = enabled)
                com.aurum.edge.notify.TradeAlarmKind.TAKE_PROFIT -> cur.copy(tpAlarmEnabled = enabled)
                com.aurum.edge.notify.TradeAlarmKind.STOP_LOSS -> cur.copy(slAlarmEnabled = enabled)
            }
        }
        val stateFa = if (enabled) "روشن" else "خاموش"
        _toast.value = "⏰ آلارم ${kind.channelNameFa}: $stateFa (۱ اعلان + ۱ صدا)"
    }

    fun setShowSettingsDescriptions(show: Boolean) {
        container.settingsStore.update { it.copy(showSettingsDescriptions = show) }
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

    /**
     * شرط دوم فعال‌سازی گیت شبکهٔ عصبی: تصمیم صریح کاربر.
     * شرط اول همیشه «۱۰,۰۰۰ معاملهٔ یادگیری‌شده» است و با این کلید دور زده نمی‌شود.
     */
    fun setNeuralGateUserActivation(enabled: Boolean) {
        container.neuralEngine.setGateUserActivation(enabled)
        val st = container.neuralEngine.state.value
        _toast.value = when {
            st.isGateActive ->
                "🧠🟢 گیت شبکه عصبی فعال شد (هر دو شرط کامل): ورود در شرایط پرخطر مسدود می‌شود"
            enabled ->
                "🧠 شرط ۲ ثبت شد · گیت پس از رسیدن به ${st.warmupTargetTrades} معاملهٔ یادگیری‌شده " +
                    "خودکار وارد مدار می‌شود (اکنون ${st.totalLearnedTrades}/${st.warmupTargetTrades})"
            else ->
                "🧠 گیت شبکه عصبی غیرفعال شد؛ شبکه فقط یاد می‌گیرد و هشدار می‌دهد"
        }
    }

    /** بازبینی دستی دفتر یادگیری و تطبیق عدد یادگیری با معامله‌های واقعی ژورنال. */
    fun auditNeuralLearningLedgerNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val reconciliation = container.neuralEngine.auditLearningLedger(trades.value)
            _toast.value = reconciliation.summaryFa
        }
    }

    /** ذخیرهٔ بستهٔ دانش شبکه (JSON) + منیفست فارسی در Download/AurumEdge/Archive. */
    fun exportNeuralKnowledgeBundleNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val files = container.neuralEngine.exportKnowledgeBundleToPhoneStorage(
                context = container.appContext,
                appVersionName = com.aurum.edge.BuildConfig.VERSION_NAME,
                appVersionCode = com.aurum.edge.BuildConfig.VERSION_CODE,
            )
            _toast.value = if (files != null) {
                "🧾 بستهٔ دانش ذخیره شد: $files (پوشهٔ Download/AurumEdge/Archive)"
            } else {
                "🧾 بستهٔ دانش در حافظهٔ داخلی اپ آماده شد؛ برای ذخیره در پوشهٔ دانلود دسترسی لازم است"
            }
        }
    }

    /** بازخوانی جدیدترین بستهٔ دانش از Download/AurumEdge/Archive با ادغام ایمن. */
    fun restoreNeuralKnowledgeBundleFromPhoneNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = container.neuralEngine.restoreKnowledgeBundleFromPhoneStorage(container.appContext)
            _toast.value = result?.summaryFa
                ?: "📥 هیچ بستهٔ دانش (AurumNeuralKnowledge) در Download/AurumEdge/Archive پیدا نشد"
        }
    }

    fun runNeuralParameterAutoTuningNow() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val baseCfg = settings.value.baseStrategyConfig
            val tuned = container.neuralEngine.runPostTrainingParameterAutoTuning(baseCfg)
            val updatedCfg = container.neuralEngine.applyAutoTunedParametersToConfig(baseCfg)
            container.settingsStore.update { cur ->
                cur.copy(baseStrategyConfig = updatedCfg)
            }
            _toast.value = "🎛️ تیونینگ خودکار پارامترها و ۱۶ ویژگی هر ۱۶ استراتژی (نسل #${tuned.tuningGeneration} · CEM + Bayesian) اعمال شد"
            scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        }
    }

    /** متن وضعیت «آموزش روی همهٔ ۱۶ استراتژی» (یادگیری انتقالی). */
    private val _allStrategyLearningFa = MutableStateFlow("")
    val allStrategyLearningFa: StateFlow<String> = _allStrategyLearningFa.asStateFlow()

    /**
     * آموزش یک‌جای شبکهٔ عصبی روی **همهٔ ۱۶ استراتژی و همهٔ نمادها**.
     *
     * هر استراتژی پروفایل ۱۶ ویژگی و پارامترهای خودش را از دادهٔ اختصاصی‌اش می‌گیرد و در صورت
     * کمبود داده، از تجربهٔ بقیهٔ استراتژی‌ها (یادگیری انتقالی) پر می‌شود. هم‌زمان «شخصیت» هر
     * نماد (روندسواری، نویز شدوی، اعتبار شکست، بهترین استراتژی و حد ضرر مناسبش) ساخته می‌شود و
     * در پیکربندی موتور اعمال می‌گردد.
     */
    fun trainNeuralOnAllStrategies() {
        _toast.value = "🧠 آموزش روی همهٔ استراتژی‌ها و نمادها شروع شد…"
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val baseCfg = settings.value.baseStrategyConfig
            val report = runCatching {
                container.neuralEngine.learnAllStrategiesNow(baseCfg)
            }.getOrNull()
            // پیکربندی موتور با شخصیت آموخته‌شدهٔ نمادها به‌روز می‌شود تا تصمیم‌های بعدی
            // از همین حالا بر پایهٔ آموخته‌های جدید گرفته شوند.
            val updatedCfg = runCatching {
                container.neuralEngine.applyAutoTunedParametersToConfig(baseCfg)
            }.getOrNull()
            if (updatedCfg != null) {
                container.settingsStore.update { cur -> cur.copy(baseStrategyConfig = updatedCfg) }
            }
            val text = report?.summaryFa ?: "⚠️ آموزش روی همهٔ استراتژی‌ها و نمادها انجام نشد"
            _allStrategyLearningFa.value = text
            _toast.value = text
            runCatching { scanAndEvaluateBitcoinStrategyNow(userInitiated = false) }
        }
    }

    fun runSingleStrategyAutoTuningNow(strategyId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val baseCfg = settings.value.baseStrategyConfig
            val prof = container.neuralEngine.runAutoTuningForSingleStrategy(strategyId, baseCfg)
            val updatedCfg = container.neuralEngine.applyAutoTunedParametersToConfig(baseCfg)
            container.settingsStore.update { cur ->
                cur.copy(baseStrategyConfig = updatedCfg)
            }
            _toast.value = "🎯 تیونینگ هوشمند اختصاصی «${prof.strategyBadgeFa}» (نسل #${prof.tuningGeneration}) روی تمام ۱۶ ویژگی و پارامترها اعمال شد"
            scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        }
    }

    fun tuneStrategyFeatureNow(
        strategyId: String,
        featureIndex: Int,
        tuningMultiplier: Double? = null,
        learnedWeight: Double? = null,
        minThresholdNorm: Double? = null,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val prof = container.neuralEngine.tuneStrategyFeature(
                strategyId = strategyId,
                featureIndex = featureIndex,
                tuningMultiplier = tuningMultiplier,
                learnedWeight = learnedWeight,
                minThresholdNorm = minThresholdNorm,
            )
            val updatedCfg = container.neuralEngine.applyAutoTunedParametersToConfig(settings.value.baseStrategyConfig)
            container.settingsStore.update { cur ->
                cur.copy(baseStrategyConfig = updatedCfg)
            }
            val featTitle = prof.featureTunings.getOrNull(featureIndex)?.featureTitleFa ?: "ویژگی #${featureIndex + 1}"
            _toast.value = "⚙️ ویژگی «$featTitle» در استراتژی «${prof.strategyBadgeFa}» تیون و ذخیره شد"
            scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        }
    }

    fun tuneStrategyParametersNow(
        strategyId: String,
        minRiskReward: Double? = null,
        maxKijunExtensionAtr: Double? = null,
        slBufferAtr: Double? = null,
        minEfficiencyRatio: Double? = null,
        maxAdverseWickRatio: Double? = null,
        minFutureKumoThicknessAtr: Double? = null,
        minSniperDistanceAtr: Double? = null,
        minDriveBooksConsensus: Int? = null,
        neuralWinProbGatePct: Double? = null,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val prof = container.neuralEngine.tuneStrategyParameters(
                strategyId = strategyId,
                minRiskReward = minRiskReward,
                maxKijunExtensionAtr = maxKijunExtensionAtr,
                slBufferAtr = slBufferAtr,
                minEfficiencyRatio = minEfficiencyRatio,
                maxAdverseWickRatio = maxAdverseWickRatio,
                minFutureKumoThicknessAtr = minFutureKumoThicknessAtr,
                minSniperDistanceAtr = minSniperDistanceAtr,
                minDriveBooksConsensus = minDriveBooksConsensus,
                neuralWinProbGatePct = neuralWinProbGatePct,
            )
            val updatedCfg = container.neuralEngine.applyAutoTunedParametersToConfig(settings.value.baseStrategyConfig)
            container.settingsStore.update { cur ->
                cur.copy(baseStrategyConfig = updatedCfg)
            }
            _toast.value = "🎛️ پارامترهای اختصاصی استراتژی «${prof.strategyBadgeFa}» (نسل #${prof.tuningGeneration}) ذخیره و اعمال شد"
            scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
        }
    }

    fun refreshNeuralMacroNewsNow() {
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                container.neuralEngine.ingestExternalHeadlinesAndRefreshMacroNews()
            }.onSuccess { snap ->
                _toast.value = "📰 هوش خبری و علیت بازار شبکه عصبی به‌روزرسانی شد (${snap.headlines.size} رویداد کلان فعال)"
            }
        }
    }

    fun runNeuralParameterRegressionNow(
        strategyId: String = "ALL",
        ridgeLambda: Double = 0.25,
        huberDelta: Double = 1.35,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val baseCfg = settings.value.baseStrategyConfig
                val report = container.neuralEngine.runParameterRegression(
                    strategyId = strategyId,
                    ridgeLambda = ridgeLambda,
                    huberDelta = huberDelta,
                    baseConfig = baseCfg,
                )
                val updatedCfg = container.neuralEngine.applyAutoTunedParametersToConfig(baseCfg)
                container.settingsStore.update { cur ->
                    cur.copy(baseStrategyConfig = updatedCfg)
                }
                report
            }.onSuccess { report ->
                _toast.value = "📐 رگرسیون ماتریسی پارامترها (${report.strategyTitleFa} · R²=${String.format(Locale.US, "%.2f", report.rSquared)}) با موفقیت کالیبره و اعمال شد"
                scanAndEvaluateBitcoinStrategyNow(userInitiated = false)
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در اجرای رگرسیون پارامترها: ${err.message}"
            }
        }
    }

    fun exportFullAiAndNeuralDataPdf(shareAfterExport: Boolean = true) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.aurum.edge.data.JournalExportEngine.exportFullAiAndNeuralReportPdf(
                    context = container.appContext,
                    neuralSummary = neuralState.value,
                    lastAiVerdict = _lastAiVerdict.value,
                    trades = trades.value,
                ).getOrThrow()
            }.onSuccess { (pdfFile, savedPath) ->
                refreshSavedJournalFiles()
                val msg = "🧠 فایل PDF کامل تمام دیتاهای هوش مصنوعی و رگرسیون ساخته و در $savedPath ذخیره شد"
                _lastExportStatusFa.value = msg
                _toast.value = msg
                if (shareAfterExport) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        com.aurum.edge.data.JournalExportEngine.shareExportedFile(
                            context = container.appContext,
                            file = pdfFile,
                            format = com.aurum.edge.data.JournalExportFormat.PDF_REPORT,
                        )
                    }
                }
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در تولید PDF هوش مصنوعی: ${err.message}"
            }
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

    /**
     * خروجی PDF از کل ژورنال.
     *
     * فقط PDF ساخته و اشتراک گذاشته می‌شود (نام تابع تاریخی است و فراخوانی‌اش در ژورنال است).
     */
    fun recordAllTradesWithArchiveSnapshotsAndExportPdf() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val allTrades = trades.value
                val cfg = settings.value
                val report = com.aurum.edge.data.JournalExportEngine.buildInstitutionalTearSheet(
                    trades = allTrades,
                    initialCapital = cfg.initialCapital,
                    currentBalance = cfg.accountBalance,
                    customStrategies = cfg.customStrategies,
                )
                val pdfFile = com.aurum.edge.data.JournalExportEngine.exportToFile(
                    context = container.appContext,
                    report = report,
                    format = com.aurum.edge.data.JournalExportFormat.PDF_REPORT,
                )
                val downloadsPath = com.aurum.edge.data.JournalExportEngine.saveDirectToDownloads(
                    context = container.appContext,
                    report = report,
                    format = com.aurum.edge.data.JournalExportFormat.PDF_REPORT,
                ).getOrNull()
                pdfFile to downloadsPath
            }.onSuccess { (pdfFile, downloadsPath) ->
                refreshSavedJournalFiles()
                val dlNote = downloadsPath?.let { " و در $it" }.orEmpty()
                val msg = "✅ فایل PDF ژورنال (${pdfFile.name}) ساخته$dlNote ذخیره شد"
                _lastExportStatusFa.value = msg
                _toast.value = msg
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching {
                        com.aurum.edge.data.JournalExportEngine.shareExportedFile(
                            context = container.appContext,
                            file = pdfFile,
                            format = com.aurum.edge.data.JournalExportFormat.PDF_REPORT,
                        )
                    }
                }
            }.onFailure { err ->
                _toast.value = "⚠️ خطا در ساخت PDF: ${err.message}"
            }
        }
    }

    fun exportAndShareJournal(
        report: com.aurum.edge.data.InstitutionalTearSheetReport,
        format: com.aurum.edge.data.JournalExportFormat,
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val file = com.aurum.edge.data.JournalExportEngine.exportToFile(
                    context = container.appContext,
                    report = report,
                    format = format,
                )
                com.aurum.edge.data.JournalExportEngine.saveBytesToPhoneStorage(
                    context = container.appContext,
                    fileName = file.name,
                    mimeType = format.mimeType,
                    bytes = file.readBytes(),
                )
                refreshSavedJournalFiles()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.aurum.edge.data.JournalExportEngine.shareExportedFile(
                        context = container.appContext,
                        file = file,
                        format = format,
                    ).getOrThrow()
                }
                file
            }.onSuccess { file ->
                val msg = "📤 خروجی ${format.extension.uppercase()} در Download/AurumEdge ذخیره و آماده ارسال شد (${file.name})"
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val file = com.aurum.edge.data.JournalExportEngine.exportToFile(
                    context = container.appContext,
                    report = report,
                    format = format,
                )
                com.aurum.edge.data.JournalExportEngine.saveBytesToPhoneStorage(
                    context = container.appContext,
                    fileName = file.name,
                    mimeType = format.mimeType,
                    bytes = file.readBytes(),
                )
                refreshSavedJournalFiles()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.aurum.edge.data.JournalExportEngine.openExportedFileExternally(
                        context = container.appContext,
                        file = file,
                        format = format,
                    ).getOrThrow()
                }
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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

    // ── کپی توضیحات هوش عصبی ──────────────────────────────────────────────────
    /**
     * کپی متن در کلیپ‌بورد با پیام فارسی.
     * همهٔ دکمه‌های «کپی» هوش عصبی و دستیار ایچیموکو از همین مسیر می‌گذرند.
     */
    /**
     * «هم‌ترازسازی» موجودی حساب با دفتر ژورنال:
     * موجودی = سرمایهٔ پایه + مجموع سود/زیان معاملات بسته‌شده.
     * فقط با زدن دکمه توسط کاربر اجرا می‌شود؛ هیچ عددی خودکار تغییر نمی‌کند.
     */
    fun alignAccountBalanceWithJournal() {
        val realized = container.journalStore.stats().netPnl
        val current = container.settingsStore.read()
        val target = (current.initialCapital + realized).coerceAtLeast(10.0)
        if (kotlin.math.abs(current.accountBalance - target) < 0.01) {
            _toast.value = "✅ موجودی حساب از قبل با ژورنال هم‌تراز است"
            return
        }
        container.settingsStore.update { it.copy(accountBalance = kotlin.math.round(target * 100.0) / 100.0) }
        _toast.value = "🔄 موجودی حساب با ژورنال هم‌تراز شد: " +
            com.aurum.edge.core.AccountLedgerSnapshot.money(current.initialCapital) + " + تحقق‌یافته " +
            com.aurum.edge.core.AccountLedgerSnapshot.signed(realized) + " = " +
            com.aurum.edge.core.AccountLedgerSnapshot.money(target)
    }

    fun copyTextToClipboard(
        labelFa: String,
        text: String,
        successToastFa: String? = null,
    ) {
        val safeText = text.trim()
        if (safeText.isBlank()) {
            _toast.value = "⚠️ متنی برای کپی پیدا نشد"
            return
        }
        val clipboard = container.appContext
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard == null) {
            _toast.value = "⚠️ کلیپ‌بورد در این گوشی در دسترس نیست"
            return
        }
        runCatching {
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(labelFa.ifBlank { "Aurum Edge" }, safeText),
            )
        }.onFailure { err ->
            _toast.value = "⚠️ کپی نشد: ${err.message}"
        }.onSuccess {
            _toast.value = successToastFa
                ?: "📋 «$labelFa» کپی شد (${safeText.length} کاراکتر)"
        }
    }

    /** کپی کامل تمام توضیحات هوش عصبی (همهٔ بخش‌ها در یک متن). */
    fun copyNeuralBrainFullReport() {
        val sections = neuralCopySections().map {
            com.aurum.edge.core.NeuralReportSection(titleFa = it.titleFa, lines = it.lines)
        }
        val text = com.aurum.edge.core.NeuralReportText.render(
            headerFa = "🧠 گزارش کامل توضیحات هوش عصبی Aurum Edge",
            generatedAtFa = com.aurum.edge.core.NeuralReportText.timestamp(),
            sections = sections,
            footerFa = neuralReportFooter(),
        )
        copyTextToClipboard("گزارش کامل هوش عصبی", text, "📋 تمام توضیحات هوش عصبی کپی شد (${text.length} کاراکتر)")
    }

    /** کپی خلاصهٔ کوتاه وضعیت (برای اشتراک سریع). */
    fun copyNeuralBrainQuickSummary() {
        val s = neuralState.value
        val lines = ArrayList<String>()
        lines += com.aurum.edge.core.NeuralReportText.label(
            "معاملات یادگیری‌شده",
            com.aurum.edge.core.NeuralReportText.number(s.totalLearnedTrades.toDouble(), 0) +
                " از " + com.aurum.edge.core.NeuralReportText.number(s.warmupTargetTrades.toDouble(), 0),
        )
        lines += com.aurum.edge.core.NeuralReportText.label(
            "پیشرفت گرم‌کردن",
            com.aurum.edge.core.NeuralReportText.pct(s.warmupProgressPercent),
        )
        lines += com.aurum.edge.core.NeuralReportText.label("وضعیت گیت", if (s.isGateActive) "فعال" else "غیرفعال")
        lines += com.aurum.edge.core.NeuralReportText.label(
            "دقت جاری",
            com.aurum.edge.core.NeuralReportText.pct(s.runningAccuracyPct),
        )
        lines += com.aurum.edge.core.NeuralReportText.label(
            "خطای BCE",
            com.aurum.edge.core.NeuralReportText.number(s.runningBceLoss, 4),
        )
        lines += com.aurum.edge.core.NeuralReportText.label(
            "خوشه‌های طلایی / ممنوعه",
            "${s.winningClusters.size} / ${s.noTradeClusters.size}",
        )
        if (s.gatePolicySummaryFa.isNotBlank()) lines += s.gatePolicySummaryFa
        val text = com.aurum.edge.core.NeuralReportText.renderSummary(
            headerFa = "🧠 خلاصهٔ وضعیت هوش عصبی Aurum Edge",
            generatedAtFa = com.aurum.edge.core.NeuralReportText.timestamp(),
            lines = lines,
            footerFa = neuralReportFooter(),
        )
        copyTextToClipboard("خلاصهٔ هوش عصبی", text, "📋 خلاصهٔ وضعیت هوش عصبی کپی شد")
    }

    /** کپی توضیحات یک بخش مشخص (دکمهٔ کپی کنار عنوان همان کارت). */
    fun copyNeuralBrainSection(sectionKey: String) {
        val key = sectionKey.trim().lowercase(java.util.Locale.ROOT)
        val section = neuralCopySections().firstOrNull { it.key == key }
        if (section == null) {
            _toast.value = "⚠️ بخش «$sectionKey» برای کپی پیدا نشد"
            return
        }
        val text = com.aurum.edge.core.NeuralReportText.renderSection(
            titleFa = section.titleFa,
            lines = section.lines,
            versionFa = com.aurum.edge.BuildConfig.VERSION_NAME,
        )
        copyTextToClipboard(section.titleFa, text, "📋 توضیحات «${section.titleFa}» کپی شد")
    }

    private fun neuralReportFooter(): String =
        "Aurum Edge ${com.aurum.edge.BuildConfig.VERSION_NAME} · تولید‌شده توسط موتور هوش عصبی ایچیموکو"

    private fun neuralCopySections(): List<NeuralCopySection> {
        val s = neuralState.value
        // کمینهٔ نمونه برای اینکه معیارهای برازش رگرسیون «قابل تفسیر» باشند.
        val MIN_REGRESSION_SAMPLES = 30
        val R = com.aurum.edge.core.NeuralReportText
        val sections = ArrayList<NeuralCopySection>()

        sections += NeuralCopySection(
            key = "sessionexit",
            titleFa = "زمان‌بندی سشن و یادگیری زمان خروج",
            lines = buildList {
                val note = sessionExitLearningNote.value
                if (note.isNotBlank()) add("آخرین یادگیری: $note")
                val estimates = sessionExitEstimates.value
                if (estimates.isEmpty()) {
                    add("هنوز تخمینی برای خروج پیش از بسته‌شدن بازار ثبت نشده است.")
                } else {
                    estimates.forEach { e ->
                        add(
                            "• ${e.labelFa}: خروج ${e.leadMinutes} دقیقه پیش از بستهٔ بازار | نمونه ${e.samples} | " +
                                "نرخ موفقیت ${R.pct(e.hitRatePct)} | دلار نجات/زیان ${R.number(e.savedUsd)} | " +
                                "اطمینان ${e.confidencePct}% | ${e.noteFa}",
                        )
                    }
                }
            },
        )

        sections += NeuralCopySection(
            key = "regression",
            titleFa = "پنل رگرسیون ماتریسی پارامترها",
            lines = buildList {
                val reg = s.autoTunedParameters.latestRegressionReport
                add(R.label("استراتژی هدف", reg.strategyTitleFa))
                add(R.label("متد", reg.regressionMethodFa))
                add(R.label("تعداد نمونه", R.number(reg.samplesCount.toDouble(), 0)))
                add(
                    R.inline(
                        // R² با نمونهٔ کمتر از ۳۰ قابل تفسیر نیست؛ در گزارش «اندازه‌گیری‌نشده» می‌آید.
                        "R²" to R.number(reg.rSquared, 3, samples = if (reg.samplesCount >= MIN_REGRESSION_SAMPLES) reg.samplesCount else 0),
                        "R² تعدیل‌شده" to R.number(reg.adjustedRSquared, 3, samples = if (reg.samplesCount > 17) reg.samplesCount else 0),
                        "RMSE" to R.number(reg.walkForwardRmse, 3, samples = if (reg.samplesCount >= MIN_REGRESSION_SAMPLES) reg.samplesCount else 0),
                        "MAE" to R.number(reg.walkForwardMae, 3, samples = if (reg.samplesCount >= MIN_REGRESSION_SAMPLES) reg.samplesCount else 0),
                    ),
                )
                // لیفت با نمونهٔ کم یا هدف صفر معنایی ندارد؛ خط در آن حالت اصلاً چاپ نمی‌شود.
                val liftWin = R.pct(reg.expectedWinRateLiftPct)
                val liftR = R.rMultiple(reg.expectedRLiftPerTrade)
                if (!R.isUnmeasured(liftWin) || !R.isUnmeasured(liftR)) {
                    add(R.inline("لیفت نرخ برد" to liftWin, "لیفت R هر معامله" to liftR))
                }
                add(R.label("دالان حد ضرر", reg.optimalStopLossCorridorFa))
                add(R.label("قفل R:R", reg.optimalRrCorridorFa))
                add(
                    "ℹ️ سه عدد R:R در این گزارش با هم فرق دارند و هر سه درست‌اند: کف مطلق قانون ۱.۵۰ (هرگز پایین‌تر نمی‌رود)، " +
                        "کف پیشنهادی رگرسیون (ردیف ۲ پایین) و کف تیونینگ خودکار (بخش «تیونینگ خودکار پارامترها»). " +
                        "عددی که موتور ورود واقعاً اعمال می‌کند همان کف تیونینگ است و همیشه داخل بازهٔ قفل‌شدهٔ ۱.۵ تا ۳.۰ می‌ماند.",
                )
                if (reg.parameterRows.isNotEmpty()) {
                    add("")
                    add("پارامترهای رگرسیون‌شده:")
                    reg.parameterRows.forEach { row ->
                        add(
                            "• ${row.paramTitleFa}: ${R.number(row.priorValue, 3)} → ${R.number(row.regressedValue, 3)} ${row.unitLabel} | " +
                                "β=${R.number(row.slopeBeta, 3)} | t=${R.number(row.tStat, 2)} | CI ${row.confidenceIntervalLabel} | ${row.impactVerdictFa}",
                        )
                    }
                }
                if (reg.featureSlopes.isNotEmpty()) {
                    add("")
                    add("شیب ۱۶ ویژگی (بر اساس اهمیت):")
                    reg.featureSlopes.sortedByDescending { it.importancePct }.take(16).forEach { f ->
                        add(
                            "• ${f.featureTitleFa} — اهمیت ${R.pct(f.importancePct)} | β برد ${R.number(f.ridgeBetaWin, 3)} | " +
                                "Huber β ${R.number(f.huberBetaR, 3)} | t=${R.number(f.tStat, 2)}",
                        )
                    }
                }
                if (reg.regressionDiagnosticNotesFa.isNotEmpty()) {
                    add("")
                    reg.regressionDiagnosticNotesFa.forEach { add("• $it") }
                }
            },
        )

        sections += NeuralCopySection(
            key = "brain",
            titleFa = "مغز شبکه عصبی و وضعیت یادگیری",
            lines = buildList {
                add(R.label("معماری", s.architectureNameFa))
                add(R.label("فاز جاری", s.currentTrainingPhaseBadgeFa))
                add(R.label("معاملات یادگیری‌شده", R.number(s.totalLearnedTrades.toDouble(), 0)))
                add(R.label("هدف گرم‌کردن", R.number(s.warmupTargetTrades.toDouble(), 0)))
                add(R.label("پیشرفت گرم‌کردن", R.pct(s.warmupProgressPercent)))
                add(R.label("باقی‌مانده تا هدف", R.number(s.remainingWarmupTrades.toDouble(), 0)))
                add(R.label("گرم‌کردن کامل شده", if (s.isWarmupCompleted) "بله" else "نه"))
                add(R.label("گیت فعال", if (s.isGateActive) "بله" else "نه"))
                add(R.label("شرط حجم (۱۰٬۰۰۰ معامله)", if (s.volumeConditionMet) "برقرار" else "برقرار نشده"))
                add(R.label("فعال‌سازی دستی کاربر", if (s.gateUserActivationEnabled) "روشن" else "خاموش"))
                add(R.label("اجبار فعال‌سازی پیش از گرم‌کردن", if (s.forceActiveBeforeWarmup) "بله" else "نه"))
                add(R.label("دقت جاری", R.pct(s.runningAccuracyPct)))
                add(R.label("خطای BCE", R.number(s.runningBceLoss, 4)))
                add(R.label("گام‌های AdamW", R.number(s.adamStepCount.toDouble(), 0)))
                add(R.label("خوشه‌های طلایی کشف‌شده", R.number(s.winningClusters.size.toDouble(), 0)))
                add(R.label("تله‌های ممنوعه کشف‌شده", R.number(s.noTradeClusters.size.toDouble(), 0)))
                if (s.gatePolicySummaryFa.isNotBlank()) {
                    add("")
                    add(s.gatePolicySummaryFa)
                }
            },
        )

        sections += NeuralCopySection(
            key = "ledger",
            titleFa = "دفتر یادگیری و انتقال دانش بین نسخه‌ها",
            lines = buildList {
                add(R.label("ردیف‌های دفتر", R.number(s.learnedLedgerRows.toDouble(), 0)))
                add(R.label("ردیف‌های حذف‌شده (پیرایش)", R.number(s.learnedLedgerPrunedCount.toDouble(), 0)))
                add(R.label("نسخهٔ ساختار دفتر", R.number(s.learnedSchemaVersion.toDouble(), 0)))
                add(R.label("معاملات بسته‌شدهٔ دیده‌شده در ژورنال", R.number(s.journalClosedTradesSeen.toDouble(), 0)))
                add(R.label("اصلاح ردیف‌های تکراری", R.number(s.duplicatesRepairedCount.toDouble(), 0)))
                add(R.label("نسخهٔ دانش", R.number(s.knowledgeRevision.toDouble(), 0)))
                if (s.ledgerReconciliationSummaryFa.isNotBlank()) add(s.ledgerReconciliationSummaryFa)
                if (s.knowledgeChangeLog.isNotEmpty()) {
                    add("")
                    add("تغییرهای ثبت‌شدهٔ دانش:")
                    s.knowledgeChangeLog.takeLast(12).reversed().forEach { entry ->
                        add("• ${entry.titleFa} — ${entry.detailFa}")
                    }
                }
            },
        )

        sections += NeuralCopySection(
            key = "market",
            titleFa = "درک زندهٔ وضعیت بیت‌کوین و علت حرکت",
            lines = buildList {
                add("این بخش فقط برای بیت‌کوین ساخته می‌شود و هنگام بررسی/معاملهٔ بیت‌کوین پر می‌شود.")
                val bitcoinAttributions = s.liveMarketAttributions.filter {
                    it.symbol == NeuralSelfLearningEngine.LIVE_ATTRIBUTION_SYMBOL
                }
                if (bitcoinAttributions.isEmpty()) {
                    add("در حال حاضر بررسی معاملهٔ بیت‌کوین فعال نیست؛ به‌محض اسکن بیت‌کوین این بخش پر می‌شود.")
                }
                bitcoinAttributions.forEach { a ->
                    add("• ${a.symbol} [${a.timeframeLabel}] — ${a.categoryTitleFa} | رژیم: ${a.marketRegimeStateFa} | علت اصلی: ${a.primaryCauseBadgeFa} (${a.causalConfidencePct}%)")
                    add("   چه اتفاقی افتاده: ${a.whatIsHappeningFa}")
                    add("   چرا: ${a.whyItIsHappeningFa}")
                    if (a.newsImpactSummaryFa.isNotBlank()) add("   خبر: ${a.newsImpactSummaryFa}")
                    if (a.unified9BookSummaryFa.isNotBlank()) add("   ۹ لایهٔ ساختاری: ${a.unified9BookSummaryFa}")
                    if (a.recommendedTacticalActionFa.isNotBlank()) add("   تاکتیک پیشنهادی: ${a.recommendedTacticalActionFa}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "news",
            titleFa = "هوش خبری کلان و تقویم اقتصادی",
            lines = buildList {
                val n = s.latestNewsSnapshot
                add(R.label("سنتیمنت کلان", R.number(n.overallMacroSentiment, 2)))
                add(R.label("شدت شوک", R.number(n.shockIntensity, 2)))
                add(R.label("پنجرهٔ خبری پرتأثیر", if (n.isHighImpactNewsWindow) "بله" else "نه"))
                add(R.label("کاتالیزور فعال", n.activeMacroCatalystFa))
                n.categoryNewsSummaryFa.forEach { (code, summary) ->
                    if (summary.isNotBlank()) add("• $code: $summary")
                }
                n.headlines.take(12).forEach { h ->
                    add("• [${h.source}] ${h.title} — ${h.causeTagFa} | سنتیمنت ${R.number(h.sentimentScore, 2)} | اثر ${h.impactLevel}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "autotune",
            titleFa = "تیونینگ خودکار پارامترها (CEM + Bayesian)",
            lines = buildList {
                val a = s.autoTunedParameters
                add(R.label("نسل تیونینگ", R.number(a.tuningGeneration.toDouble(), 0)))
                add(R.label("متد", a.methodBadgeFa))
                add(R.label("نمونه‌های تحلیل‌شده", R.number(a.samplesAnalyzed.toDouble(), 0)))
                add(R.label("حداقل R:R", R.number(a.minRiskReward, 2)))
                add(R.label("سقف کشش کیجون (ATR)", R.number(a.maxKijunExtensionAtr, 2)))
                add(R.label("بافر حد ضرر (ATR)", R.number(a.slBufferAtr, 2)))
                add(R.label("بافر شکست (ATR)", R.number(a.breakoutBufferAtr, 2)))
                add(R.label("حداقل کارایی روند", R.number(a.minEfficiencyRatio, 2)))
                add(R.label("حداقل نسبت حجم ۲۰", R.number(a.minVolumeRatio20, 2)))
                add(R.label("توافق حداقلی ۹ لایه", R.number(a.minDriveBooksConsensus.toDouble(), 0)))
                add(R.label("گیت احتمال برد شبکه", R.pct(a.neuralWinProbGatePct)))
                // عدد بدون نمونه «اندازه‌گیری‌نشده» است، نه ۰٪ — تفاوت این دو در گزارش
                // ۱۶۰ معامله‌ای به‌سادگی گم می‌شد.
                val globalSamples = s.totalLearnedTrades
                add(R.label("نرخ برد اندازه‌گیری‌شده", R.pct(a.expectedWinRatePct, samples = globalSamples)))
                add(R.label("پروفیت فاکتور اندازه‌گیری‌شده", R.number(a.expectedProfitFactor, 2, samples = globalSamples)))
                add(R.label("سهم نمونه‌های داخل منطقهٔ ممنوعه", R.pct(a.falsePositiveReductionPct, samples = globalSamples)))
                if (a.tuningSummaryFa.isNotBlank()) add(a.tuningSummaryFa)
                a.bestStrategyByCategory.forEach { (code, title) -> add("• بهترین استراتژی $code: $title") }
                if (a.parameterChangeLogFa.isNotEmpty()) {
                    add("")
                    add("تغییرهای اعمال‌شده:")
                    a.parameterChangeLogFa.takeLast(12).reversed().forEach { add("• $it") }
                }
            },
        )

        sections += NeuralCopySection(
            key = "perstrategy",
            titleFa = "تیونینگ اختصاصی هر استراتژی",
            lines = buildList {
                val profiles = s.autoTunedParameters.perStrategyProfiles.values
                if (profiles.isEmpty()) add("هنوز پروفایل اختصاصی استراتژی‌ها ساخته نشده است.")
                profiles.sortedByDescending { it.samplesAnalyzed }.take(16).forEach { p ->
                    add("• ${p.strategyTitleFa} (${p.strategyBadgeFa}) — نمونه ${p.samplesAnalyzed} | نرخ برد ${R.pct(p.winRatePct, samples = p.samplesAnalyzed)} | PF ${R.number(p.expectedProfitFactor, 2, samples = p.samplesAnalyzed)} | R:R≥${R.number(p.minRiskReward, 2)} | بافر SL ${R.number(p.slBufferAtr, 2)}ATR | گیت ${R.pct(p.neuralWinProbGatePct)}")
                    if (p.tuningSummaryFa.isNotBlank()) add("   ${p.tuningSummaryFa}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "persymbol",
            titleFa = "شخصیت آموخته‌شدهٔ هر نماد بازار",
            lines = buildList {
                val profiles = s.autoTunedParameters.perSymbolProfiles.values
                if (profiles.isEmpty()) {
                    add("هنوز شخصیت نمادها ساخته نشده است؛ با یک‌بار آموزش (یا اولین معاملهٔ بسته‌شده) ساخته می‌شود.")
                }
                profiles.sortedWith(compareByDescending<com.aurum.edge.core.PerSymbolNeuralTuningProfile> { it.ownSamples }
                    .thenByDescending { it.samplesAnalyzed })
                    .forEach { p ->
                        add("• ${p.symbolTitleFa} (${p.symbol}) — سبک: ${p.decisionStyleFa} | معاملهٔ خودش ${p.ownSamples} | نمونهٔ آموزشی ${p.samplesAnalyzed}")
                        val symbolSamples = if (p.ownSamples > 0) p.ownSamples else p.samplesAnalyzed
                        add("   نرخ برد ${R.pct(p.winRatePct, samples = symbolSamples)} | PF ${R.number(p.expectedProfitFactor, 2, samples = symbolSamples)} | میانگین ${R.rMultiple(p.avgRMultiple, samples = symbolSamples)} | احتمال برد بیزین ${R.pct(p.posteriorWinProbPct, samples = symbolSamples)} | اعتبار شکست ${R.pct(p.breakoutReliabilityPct, samples = symbolSamples)}")
                        add("   پارامترها: بافر SL ${R.number(p.slBufferAtr, 2)}ATR | سقف کیجون ${R.number(p.maxKijunExtensionAtr, 2)}ATR | بافر شکست ${R.number(p.breakoutBufferAtr, 2)}ATR | حداقل کارایی ${R.number(p.minEfficiencyRatio, 2)} | حجم ${R.number(p.minVolumeRatio20, 2)} | اجماع ${p.minDriveBooksConsensus}/9 | گیت ${R.pct(p.neuralWinProbGatePct)}")
                        if (p.bestStrategyTitleFa.isNotBlank()) add("   سازگارترین استراتژی: ${p.bestStrategyTitleFa}")
                        if (p.personalityFa.isNotBlank()) add("   ${p.personalityFa}")
                    }
            },
        )

        sections += NeuralCopySection(
            key = "forensics",
            titleFa = "کالبدشکافی تریدهای تارگت و استاپ",
            lines = buildList {
                val f = s.tradeForensics
                add(R.label("معاملات برندهٔ تحلیل‌شده", R.number(f.totalWinsAnalyzed.toDouble(), 0)))
                add(R.label("معاملات بازندهٔ تحلیل‌شده", R.number(f.totalLossesAnalyzed.toDouble(), 0)))
                add(R.label("میانگین برد", R.rMultiple(f.avgWinR)))
                add(R.label("میانگین باخت", R.rMultiple(-kotlin.math.abs(f.avgLossR))))
                add(R.label("پروفیت فاکتور", R.number(f.profitFactor, 2)))
                add(R.label("امید ریاضی هر معامله", R.rMultiple(f.expectancyPerTradeR)))
                add(R.label("بهترین تایم‌فریم", f.bestTimeframeSummaryFa))
                if (f.topWinDriversFa.isNotEmpty()) {
                    add("")
                    add("محرک‌های اصلی برد:")
                    f.topWinDriversFa.forEach { add("• $it") }
                }
                if (f.topLossCausesFa.isNotEmpty()) {
                    add("")
                    add("علت‌های اصلی استاپ:")
                    f.topLossCausesFa.forEach { add("• $it") }
                }
                if (f.actionableTradingRulesFa.isNotEmpty()) {
                    add("")
                    add("قوانین طلایی استخراج‌شده:")
                    f.actionableTradingRulesFa.forEach { add("• $it") }
                }
            },
        )

        sections += NeuralCopySection(
            key = "matrix",
            titleFa = "ماتریس برتری استراتژی‌ها",
            lines = buildList {
                if (s.strategyEdgeMatrix.isEmpty()) add("هنوز معاملهٔ بسته‌شده‌ای برای رتبه‌بندی استراتژی‌ها ثبت نشده است.")
                s.strategyEdgeMatrix.sortedByDescending { it.edgeScore }.forEach { e ->
                    add("• ${e.strategyTitleFa} (${e.strategyBadgeFa}) — امتیاز لبه ${e.edgeScore} | معامله ${e.tradesCount} | نرخ برد ${R.pct(e.winRatePercent)} | PF ${R.number(e.profitFactor, 2)} | E[R] ${R.rMultiple(e.expectancyR)} | بهترین TF ${e.bestTimeframeLabel} | بهترین نماد ${e.bestSymbol} | ${e.tradingVerdictFa}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "insights",
            titleFa = "خلاصهٔ آموخته‌ها به زبان ساده",
            lines = buildList {
                if (s.learnedInsightsFeedFa.isEmpty()) add("هنوز درسی از معاملات بسته‌شده استخراج نشده است.")
                s.learnedInsightsFeedFa.forEach { add("• $it") }
            },
        )

        sections += NeuralCopySection(
            key = "categories",
            titleFa = "دانش شبکه به تفکیک دستهٔ بازار",
            lines = buildList {
                if (s.categoryStats.isEmpty()) add("آمار دسته‌های بازار هنوز ساخته نشده است.")
                s.categoryStats.forEach { c ->
                    add("• ${c.categoryTitleFa} — یادگرفته ${c.learnedTrades} | نرخ برد ${R.pct(c.winRatePercent)} | تله‌های ثبت‌شده ${c.noTradeConditionCount}")
                    if (c.bestConditionSummaryFa.isNotBlank()) add("   بهترین رژیم سودده: ${c.bestConditionSummaryFa}")
                    if (c.worstNoTradeConditionSummaryFa.isNotBlank()) add("   بدترین تله: ${c.worstNoTradeConditionSummaryFa}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "clusters",
            titleFa = "تله‌های ممنوعه و الگوهای طلایی",
            lines = buildList {
                add(
                    "ℹ️ «مشاهده» یعنی هر باری که یک معاملهٔ بسته‌شده در این خوشه ثبت شده است (تجمعی). " +
                        "این عدد با شمارندهٔ معاملات یکتا یکی نیست؛ شمارندهٔ یکتا و تطبیق‌شده با ژورنال در بخش «دفتر یادگیری» است " +
                        "(${R.number(s.totalLearnedTrades.toDouble(), 0)} معاملهٔ یکتا).",
                )
                add("")
                add("⛔ تله‌های ممنوعه (${s.noTradeClusters.size}):")
                if (s.noTradeClusters.isEmpty()) add("• هنوز خوشهٔ ضرردهٔ پرتکراری ثبت نشده است.")
                s.noTradeClusters.take(20).forEach { c ->
                    add("• ${c.conditionDescriptionFa} — مشاهده ${c.totalCount} | برد ${c.winCount} | باخت ${c.lossCount} | احتمال برد ${R.pct(c.posteriorWinProb * 100.0)} | میانگین ${R.rMultiple(c.avgR)}")
                }
                add("")
                add("🎯 الگوهای طلایی (${s.winningClusters.size}):")
                if (s.winningClusters.isEmpty()) add("• هنوز الگوی پرتکرار سوددهی ثبت نشده است.")
                s.winningClusters.take(20).forEach { c ->
                    add("• ${c.conditionDescriptionFa} — مشاهده ${c.totalCount} | برد ${c.winCount} | احتمال برد ${R.pct(c.posteriorWinProb * 100.0)} | میانگین ${R.rMultiple(c.avgR)}")
                }
            },
        )

        sections += NeuralCopySection(
            key = "features",
            titleFa = "وزن ۱۶ ویژگی ایچیموکو در مغز شبکه",
            lines = buildList {
                if (s.featureInsights.isEmpty()) add("وزن ویژگی‌ها هنوز محاسبه نشده است.")
                val attnSpread = (s.featureInsights.maxOfOrNull { it.attentionPercent } ?: 0.0) -
                    (s.featureInsights.minOfOrNull { it.attentionPercent } ?: 0.0)
                val uniformAttention = s.featureInsights.isNotEmpty() && attnSpread < 1.0
                if (uniformAttention) {
                    add(
                        "ℹ️ سهم توجه (Self-Attention) در حال حاضر یکنواخت است: هر یک از ۱۶ ویژگی " +
                            "${R.pct(s.featureInsights.first().attentionPercent)} — یعنی شبکه هنوز هیچ ویژگی را بر دیگری ترجیح نداده است. " +
                            "برای دیدن تفکیک واقعی، ستون «اثر خالص وزن» را بخوانید؛ این فهرست بر اساس همان اثر مرتب شده است.",
                    )
                    add("")
                }
                s.featureInsights
                    .sortedByDescending { if (uniformAttention) kotlin.math.abs(it.netWeightImpact) else it.attentionPercent }
                    .forEach { f ->
                        val attnPart = if (uniformAttention) "" else "سهم توجه ${R.pct(f.attentionPercent)} | "
                        add("• #${f.index} ${f.titleFa} — ${attnPart}اثر خالص وزن ${R.number(f.netWeightImpact, 3)} | ${f.impactLabelFa}")
                        if (f.explanationFa.isNotBlank()) add("   ${f.explanationFa}")
                    }
            },
        )

        sections += NeuralCopySection(
            key = "live15",
            titleFa = "ارزیابی لحظه‌ای نمادها",
            lines = buildList {
                val dual = dualSymbolStrategyReports.value
                if (dual.isEmpty()) add("گزارش زندهٔ نمادها هنوز آماده نیست.")
                dual.keys.sorted().forEach { sym ->
                    val d = dual[sym] ?: return@forEach
                    listOfNotNull(d.report15m, d.report5m).forEach { rep ->
                        val ev = runCatching { evaluateReportWithNeuralEngine(rep) }.getOrNull() ?: return@forEach
                        val decisionFa = when {
                            ev.isBlockingTradeNow -> "⛔ ممنوع (گیت عصبی فعال است)"
                            ev.isNoTradeCondition -> "⚠️ شرایط پرخطر — گیت خاموش، تصمیم نهایی با موتور قواعد"
                            else -> "✅ اجازهٔ ورود"
                        }
                        add("• ${ev.symbol} [${rep.triggerInterval.label}] — احتمال برد ${R.pct(ev.predictedWinProb * 100.0)} | امید ریاضی ${R.rMultiple(ev.expectedR)} | $decisionFa")
                        if (ev.summaryFa.isNotBlank()) add("   ${ev.summaryFa}")
                        if (!ev.noTradeWarningFa.isNullOrBlank()) add("   ⛔ ${ev.noTradeWarningFa}")
                        if (ev.causalAttributionFa.isNotBlank()) add("   علت: ${ev.causalAttributionFa}")
                        if (ev.newsCatalystFa.isNotBlank()) add("   خبر: ${ev.newsCatalystFa}")
                    }
                }
            },
        )

        sections += NeuralCopySection(
            key = "lessons",
            titleFa = "درس‌های استخراج‌شده از معاملات",
            lines = buildList {
                if (s.recentLessons.isEmpty()) add("هنوز معاملهٔ بسته‌شده‌ای در حافظهٔ تجربه ثبت نشده است.")
                s.recentLessons.forEach { l ->
                    add("• ${l.symbol} [${l.timeframeLabel}] ${if (l.won) "برد ✅" else "باخت ❌"} (${R.rMultiple(l.achievedR)}) — ${l.strategyId}")
                    if (l.conditionDescriptionFa.isNotBlank()) add("   شرایط: ${l.conditionDescriptionFa}")
                    if (l.lessonLearnedFa.isNotBlank()) add("   درس: ${l.lessonLearnedFa}")
                }
            },
        )

        return sections
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


/** یک بخش از توضیحات هوش عصبی که دکمهٔ کپی دارد. */
private data class NeuralCopySection(
    val key: String,
    val titleFa: String,
    val lines: List<String>,
)
