package com.aurum.edge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.core.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.core.TradeQuotePolicy
import com.aurum.edge.core.PaperTicket
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TradeReplay
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.JournalStats
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.SpotFallbackClient
import com.aurum.edge.data.NewsRepository
import com.aurum.edge.data.WatchSelection
import com.aurum.edge.data.WatchState
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.notify.AlertSoundPlayer
import com.aurum.edge.notify.Notifier
import com.aurum.edge.service.SignalMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

class AurumViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings


    val watchSettings: StateFlow<Map<String, WatchSelection>> = container.watchSettings.selections
    val watch: StateFlow<WatchState> = container.watch.state
    val news = container.news.state
    val publicWebNews = container.publicWebNews.state // Forex publisher snippets, not ninth-confluence evidence
    val forexCalendar = container.forexCalendar.state
    /** All-pairs radar: periodic online candle sweep status of every catalog pair. */
    val pairScan = container.pairScanner.state
    val decisionLog = container.decisionLog.records
    /** The AI trading companion's latest strictly-validated opinion (analysis, never a signal). */
    val traderOpinion = container.traderAdvisor.state
    /** Reachability of the model, checked before any AI judgement of a trade. */
    val aiConnection = container.traderAdvisor.connection
    /** «ادامه بده یا نه» — the companion AI's advisory pass over the OPEN paper positions. */
    val holdReview = container.traderAdvisor.holdReviews

    val market = container.verifiedMarket
    val trades: StateFlow<List<PaperTrade>> = container.journalStore.trades
    val opportunities: StateFlow<List<PaperOpportunity>> = container.opportunityStore.items
    val opportunityError: StateFlow<String?> = container.opportunityStore.loadError
    val journalError: StateFlow<String?> = container.journalStore.loadError
    val autoPaperStatus: StateFlow<String> = container.autoPaperTrader.status

    /**
     * «کدام روش برای کدام بازار» — per-market method router for the SELECTED symbol, recomputed
     * on every market emission from real closed candles + the real session clocks. Read-only: it
     * never creates a signal, it says which method is legal right now and why not when it isn't.
     */
    val playbook: StateFlow<com.aurum.edge.core.PlaybookDecision?> = container.verifiedMarket
        .map { state ->
            withContext(Dispatchers.Default) {
                runCatching {
                    com.aurum.edge.core.MarketPlaybook.assess(state.symbol, state.candles, state.interval)
                }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * «روند کلی بازار» — خوانشِ عرض بازار از همان پویشِ پیوستهٔ ۵۰+ نماد: چند نماد صعودی/نزولی/
     * خنثی اندازه گرفته شد، جهت دلار از شش جفت اصلی، و جوّ ریسک‌پذیری از دارایی‌های ریسکی در
     * برابر طلا و دلار. null تا وقتی یک پویش چیزی اندازه نگرفته باشد (و هرگز حدس زده نمی‌شود).
     */
    val marketTrend: StateFlow<com.aurum.edge.core.MarketTrendRead?> = pairScan
        .map { it.marketTrend }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * لایهٔ اولِ همان روند: جهتِ اندازه‌گیری‌شدهٔ **خودِ نمادِ انتخابی** روی تایم‌فریم پایه و
     * تایم‌فریم مرجع (تجمیع‌شده از همان کندل‌های واقعی)، با ER و شیب EMA50 بر حسب ATR.
     */
    val symbolTrend: StateFlow<com.aurum.edge.core.SymbolTrend?> = container.verifiedMarket
        .map { state ->
            withContext(Dispatchers.Default) {
                runCatching {
                    com.aurum.edge.core.MarketTrend.symbolTrend(state.symbol, state.candles, state.interval)
                }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** جایگاه آخرین سیگنالِ همین نماد نسبت به روندِ بازار — برای نمایش و ثبت، نه برای ساخت سیگنال. */
    val trendContext: StateFlow<com.aurum.edge.core.TrendContext?> = combine(
        symbolTrend, marketTrend, container.verifiedMarket, playbook,
    ) { trend, overall, state, decision ->
        val side = state.signal?.action ?: com.aurum.edge.core.SignalAction.NO_TRADE
        if (trend != null && trend.symbol != state.symbol) return@combine null
        runCatching {
            com.aurum.edge.core.MarketTrend.contextOf(side, trend, overall, decision?.method)
        }.getOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * «چرا معامله/هشدار نداریم؟» — the honest prerequisites (feed, history, monitor, notification
     * channels, storage, news/AI, the current seven-condition technical gate and the CONTINUOUS SYMBOL SCAN itself),
     * computed from live app state.
     * Green checks are prerequisites, never a forecast.
     */
    val entryDiagnostics: StateFlow<List<com.aurum.edge.core.AlertCheck>> = combine(
        container.verifiedMarket, settings, trades, news, pairScan,
    ) { market, config, openTrades, headlines, scan ->
        withContext(Dispatchers.Default) {
            val mtf = runCatching { MtfAnalyzer.analyze(market.candles, market.interval) }.getOrNull()
            runCatching {
                com.aurum.edge.core.AlertDiagnostics.checks(
                    market = market, settings = config, news = headlines,
                    monitorRunning = SignalMonitorService.running.value,
                    androidNotificationsReady = Notifier.canNotifyVerified(
                        container.appContext, config.alertSoundUri),
                    trades = openTrades, mtf = mtf,
                    opportunityError = opportunityError.value, journalError = journalError.value,
                    scan = scan,
                )
            }.getOrElse { emptyList() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val updateState: StateFlow<AppUpdateRepository.State> = container.updater.state

    private val _livePrices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val livePrices: StateFlow<Map<String, Double>> = _livePrices.asStateFlow()
    private var liveTickerLoopStarted = false

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    /** Multi-timeframe confluence, recomputed on the phone whenever a new real bar closes. */
    private val _mtf = MutableStateFlow<MtfAnalyzer.Snapshot?>(null)
    val mtf: StateFlow<MtfAnalyzer.Snapshot?> = _mtf.asStateFlow()
    private var mtfBarTime: Long = -1L

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _watchHistory = MutableStateFlow(WatchHistory())
    val watchHistory: StateFlow<WatchHistory> = _watchHistory.asStateFlow()

    /** Candles behind ONE journal row. Only one trade is expanded at a time. */
    private val _tradeChart = MutableStateFlow(TradeChartState())
    val tradeChart: StateFlow<TradeChartState> = _tradeChart.asStateFlow()

    /** Result of the one-tap AI connectivity probe from the API settings tab. */
    private val _aiProbe = MutableStateFlow(AiProbeState())
    val aiProbe: StateFlow<AiProbeState> = _aiProbe.asStateFlow()

    /** Allowed model IDs for the user's key, fetched live from the service catalogue. */
    private val _aiModels = MutableStateFlow(AiModelsState())
    val aiModels: StateFlow<AiModelsState> = _aiModels.asStateFlow()

    init {
        // The market and background service can settle trades without going through a UI
        // button. Keep Home/Journal statistics attached to the same persisted ledger.
        viewModelScope.launch {
            container.journalStore.trades.collect { _stats.value = container.journalStore.stats() }
        }
        viewModelScope.launch {
            runCatching { container.journalStore.load() }.onFailure {
                _toast.value = "ژورنال خوانده نشد؛ فایل قبلی برای بازیابی نگه داشته شد"
            }
            runCatching { container.opportunityStore.load() }.onFailure {
                _toast.value = "تاریخچهٔ فرصت‌ها خوانده نشد؛ فایل قبلی نگه داشته شد و هشدار تکراری متوقف است"
            }
            _stats.value = container.journalStore.stats()
        }
        viewModelScope.launch {
            // «وضعیت اتصال هوش مصنوعی را چک کن و اگر قطع بود اعلام کن»: announced in the app
            // itself, once per real change of state — never on every tick, never as a guess.
            var announced: Boolean? = null
            container.traderAdvisor.connection.collect { connection ->
                val reachable = connection.reachable
                if (connection.configured && reachable != null && reachable != announced) {
                    announced = reachable
                    _toast.value = if (reachable) {
                        "🤖 اتصال AI برقرار شد — ارزنده‌بودن ورودها و بازبینی پوزیشن‌های باز دوباره از مدل پرسیده می‌شود"
                    } else {
                        "🤖 اتصال AI قطع است (${connection.detail.ifBlank { "پاسخی از مدل نگرفتیم" }}) — " +
                            "معاملات کاغذی بدون AI و فقط با شرط‌های فنی ادامه پیدا می‌کنند"
                    }
                }
            }
        }
        viewModelScope.launch {
            // Automatic SL/TP settlement happens in MarketRepository, not in this ViewModel.
            // Keep totals in sync with the journal flow even while a screen is not open.
            trades.collect { _stats.value = container.journalStore.stats() }
        }
        viewModelScope.launch {
            while (isActive) {
                if (!MarketHours.weekendClosedFor(settings.value.symbol) &&
                    settings.value.pauseOnNews && settings.value.newsBaseUrl.isNotBlank()) container.news.refreshNow()
                delay(120_000L)
            }
        }
        viewModelScope.launch {
            container.market.state.collect { state ->
                val lastClosed = state.candles.lastOrNull { it.closed }?.time ?: -1L
                if (lastClosed == mtfBarTime) return@collect
                mtfBarTime = lastClosed
                val snapshot = withContext(Dispatchers.Default) {
                    runCatching { MtfAnalyzer.analyze(state.candles, state.interval) }.getOrNull()
                }
                _mtf.value = snapshot
            }
        }
        ensureLiveTickerLoop()
    }

    /** Open on gold on each cold launch; in-session symbol selections remain available. */
    fun startApp(context: Context) {
        if (container.settingsStore.read().symbol != "XAU/USD") {
            // Save before starting the market feed, so it cannot start on the previously
            // viewed symbol. This changes only the chart selection, never open positions.
            if (!container.settingsStore.saveChartSymbol("XAU/USD")) {
                _toast.value = "شروع با طلا ذخیره نشد؛ نماد قبلی حفظ شد"
            }
        }
        visibleOnlineLoopEnabled = true
        container.market.start()
        container.watch.loadCached()
        ensureVisibleOnlineLoop()
        ensureLiveTickerLoop()
        // A saved opt-in can outlive a killed service. Re-arm only when the UI is in the
        // foreground again; it stays off otherwise.
        if (settings.value.backgroundMonitor && !SignalMonitorService.running.value) {
            val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!permitted || !SignalMonitorService.start(context)) {
                setMonitorFlag(false)
                _toast.value = "سرویس پایش شروع نشد؛ مجوز اعلان یا محدودیت باتری گوشی را بررسی کنید"
            }
        }
        // Do this once per process, not on every tab change. If the user enabled automatic
        // download, a verified public APK is downloaded and Android's installer is opened.
        if (!updateCheckStarted) {
            updateCheckStarted = true
            checkForAppUpdate(settings.value.autoDownloadUpdates)
        }
    }

    /** UI resume must not restart a healthy service socket; start() is idempotent. */
    fun resumeVisibleForexFeed() {
        visibleOnlineLoopEnabled = true
        container.market.start()
    }

    /** When no user-enabled foreground service remains, do not keep a headless feed alive. */
    fun pauseInvisibleForexFeed() {
        visibleOnlineLoopEnabled = false
        if (!SignalMonitorService.running.value) container.market.stop()
    }

    private var visibleOnlineLoopStarted = false
    private var visibleOnlineLoopEnabled = false
    private var updateCheckStarted = false

    /**
     * While the app UI is open (or the user-enabled foreground monitor is running), keep every
     * data section warm automatically. Individual repositories still enforce their own rate limits.
     */
    private fun ensureVisibleOnlineLoop() {
        if (visibleOnlineLoopStarted) return
        visibleOnlineLoopStarted = true
        viewModelScope.launch {
            var turns = 0
            while (isActive) {
                if ((visibleOnlineLoopEnabled || SignalMonitorService.running.value) && !MarketHours.weekendClosedFor(settings.value.symbol)) {
                    if (turns % 3 == 0) container.watch.refreshNow()
                    container.forexCalendar.refreshNow()
                    if (turns % 5 == 0) container.publicWebNews.refreshNow()
                    if (settings.value.pauseOnNews &&
                        (settings.value.newsBaseUrl.isNotBlank() || settings.value.hasClientNewsAi)) {
                        container.news.refreshNow()
                    }
                    container.traderAdvisor.refreshNow()
                    // وضعیت اتصال AI در پیش‌زمینه هم تازه می‌ماند (TTL پنج دقیقه + cooldown روی
                    // پروب ناموفق)، تا اگر قطع شد هم در کارت «چرا معامله نداریم؟» و هم با پیام
                    // روی صفحه اعلام شود.
                    if (settings.value.hasClientNewsAi) {
                        runCatching { container.traderAdvisor.ensureConnection() }.getOrNull()
                    }
                    // Advisory review of the open positions while the app is visible (the monitor
                    // service does the same when it runs). Never closes or re-prices a trade.
                    runCatching { container.traderAdvisor.reviewOpenPositions() }.getOrNull()
                }
                // Scan each market on its own calendar. A closed selected FX chart must not
                // suppress crypto (which also trades on weekends) or the other catalog rows.
                if (visibleOnlineLoopEnabled || SignalMonitorService.running.value) {
                    container.pairScanner.refreshNow()
                }
                turns++
                delay(60_000L)
            }
        }
    }

    /**
     * Continuous multi-symbol parallel price ticker:
     * Fetches real-time market prices concurrently for ALL open trades and active symbols,
     * updates live PnL and gauge animations on Home Screen, and automatically
     * settles Take Profit (TP) and Stop Loss (SL) in real-time as prices move.
     */
    private fun ensureLiveTickerLoop() {
        if (liveTickerLoopStarted) return
        liveTickerLoopStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            val spot = SpotFallbackClient()
            while (isActive) {
                if (!visibleOnlineLoopEnabled && !SignalMonitorService.running.value) {
                    _livePrices.value = emptyMap()
                    delay(1_000L)
                    continue
                }
                try {
                    val currentMarket = container.verifiedMarket.value
                    val openTradesList = container.journalStore.trades.value.filter { it.isOpen }
                    // Rebuild on every pass: a missed provider response must never leave an
                    // indefinitely "live" cached price in Home/Chart/Journal.
                    val currentMap = mutableMapOf<String, Double>()

                    if (currentMarket.lastPrice != null && currentMarket.lastPrice!! > 0.0 &&
                        !currentMarket.showingCachedData && currentMarket.feed.mode == FeedMode.LIVE &&
                        FeedLiveness.hasRecentReceipt(currentMarket.feed) &&
                        !CryptoCatalog.isCrypto(currentMarket.symbol) &&
                        !MarketHours.closedFor(currentMarket.symbol)) {
                        currentMap[currentMarket.symbol] = currentMarket.lastPrice!!
                        // MarketRepository already settles the original timestamped tick.
                        // Replaying the same lastPrice on every UI frame could falsely close
                        // a position opened after that quote.
                    }

                    // Parallel multi-processing quote fetching across all open trade symbols
                    val symbolsToFetch = openTradesList.map { it.symbol }.distinct().filter {
                        it != currentMarket.symbol && !CryptoCatalog.isCrypto(it)
                    }
                    if (symbolsToFetch.isNotEmpty()) {
                        kotlinx.coroutines.coroutineScope {
                            val deferreds = symbolsToFetch.map { sym ->
                                async(Dispatchers.IO) {
                                    val quote = runCatching { spot.fetchQuote(sym) }.getOrNull()
                                    sym to quote?.takeIf { TradeQuotePolicy.accepts(it) }
                                }
                            }
                            deferreds.awaitAll().forEach { (sym, quote) ->
                                if (quote != null && quote.price.isFinite() && quote.price > 0.0 &&
                                    !MarketHours.closedFor(sym)) {
                                    currentMap[sym] = quote.price
                                    val closedList = container.journalStore.settleTick(sym, quote.price, quote.at)
                                    if (closedList.isNotEmpty()) {
                                        _stats.value = container.journalStore.stats()
                                        closedList.forEach { closed ->
                                            _toast.value = "معاملهٔ ${closed.symbol} (${closed.exitReason}) بسته و تسویه شد: ${closed.pnlUsd}$"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Scanner prices are closed historical bars. They must never enter the
                    // live PnL map or settle an open position as if they were new ticks.

                    _livePrices.value = currentMap
                } catch (_: Exception) {}
                delay(800L)
            }
        }
    }

    fun refreshNow() = container.market.refreshNow()

    fun checkForAppUpdate(autoDownload: Boolean = settings.value.autoDownloadUpdates) {
        viewModelScope.launch {
            container.updater.checkForUpdate(autoDownload)
            if (autoDownload && container.updater.state.value.downloadedApkPath != null) {
                container.updater.installDownloaded()
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
        if (enabled) checkForAppUpdate(autoDownload = true)
        _toast.value = if (enabled)
            "بررسی و دانلود خودکار فعال شد؛ نصب نهایی با تأیید Android انجام می‌شود"
        else "دانلود خودکار بروزرسانی خاموش شد"
    }

    fun installDownloadedUpdate() = container.updater.installDownloaded()

    fun resumeUpdateInstall() = container.updater.resumePendingInstall()

    fun openUpdateInstallPermission() = container.updater.openInstallPermissionSettings()

    fun refreshWatch() = container.watch.refreshNow()

    /** Ask the companion AI for a fresh opinion; throttled inside the advisor (10 minutes). */
    fun refreshTraderOpinion(force: Boolean = false) = container.traderAdvisor.refreshNow(force)

    /**
     * Runs the «continue or not» pass on demand (the loops also run it on their own throttle).
     * Connection is probed first inside; an unreachable model leaves every position untouched.
     */
    fun reviewOpenPositionsNow() {
        viewModelScope.launch {
            runCatching { container.traderAdvisor.reviewOpenPositions() }.getOrNull()
            // Alerts are NOT consumed here: the monitor service owns the notification channel, and
            // the journal keeps showing the verdict either way.
            container.traderAdvisor.holdReviews.value.alerts.firstOrNull()?.let { alert ->
                _toast.value = "نظر AI دربارهٔ ${alert.symbol}: ادامه توصیه نمی‌شود — ${alert.summary}"
            }
        }
    }

    /** Manual all-pairs sweep across 50+ instruments. */
    fun scanPairs() {
        if (container.pairScanner.state.value.sweeping) {
            _toast.value = "اسکن همگانی ۵۰+ نماد در حال اجراست"
            return
        }
        viewModelScope.launch {
            _toast.value = "اسکن ۵۰+ سهم و نماد آغاز شد؛ بهترین فرصت‌ها شناسایی می‌شوند"
            try {
                container.pairScanner.sweepOnce(minIntervalMs = 1 * 60_000L) { }
            } catch (_: Exception) {
                _toast.value = "اسکن همگانی ناتمام ماند؛ وضعیت هر نماد در رادار مشخص است"
            }
        }
    }

    /** Selects the #1 Best Pick symbol, loads its chart and opens the paper trade. */
    fun selectAndTradeBestPick() {
        val best = container.pairScanner.state.value.bestPick ?: return
        selectChartSymbol(best.symbol)
        _toast.value = "بهترین فرصت انتخاب شد: ${best.symbol} (${best.action?.name ?: "سیگنال"})"
    }

    fun refreshNews() = container.news.refreshNow()

    fun refreshPublicWebNews() = container.publicWebNews.refreshNow()

    fun refreshForexCalendar() = container.forexCalendar.refreshNow()

    fun saveApiKey(key: String) = saveMarketCredentials(key, settings.value.symbol)

    /**
     * Switch the chart/signal/paper symbol to one of the catalog pairs. Needs no API key:
     * the keyless Swissquote feed serves ticks for every pair; REST history still needs a key.
     * One verified write, one feed restart.
     */
    private val symbolSelectionMutex = Mutex()

    fun selectChartSymbol(symbol: String) {
        if (symbol == settings.value.symbol) return
        val valid = com.aurum.edge.core.V1Universe.valid(symbol)
        if (!valid) {
            _toast.value = "نماد $symbol در کاتالوگ نمادها پیدا نشد"
            return
        }
        viewModelScope.launch {
            symbolSelectionMutex.withLock {
                try {
                    val saved = withContext(Dispatchers.IO) { container.settingsStore.saveChartSymbol(symbol) }
                    if (!saved) {
                        _toast.value = "ذخیرهٔ نماد روی دستگاه تأیید نشد؛ دوباره تلاش کنید"
                        return@withLock
                    }
                    container.market.restart()
                    _toast.value = "نماد چارت و سیگنال: $symbol"
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    _toast.value = "تغییر نماد ناموفق بود؛ دوباره تلاش کنید"
                }
            }
        }
    }

    /** One verified write, one feed restart; never echo a credential into a toast or log. */
    fun saveMarketCredentials(key: String, symbol: String) {
        if (marketSaveInFlight) return
        if (key.trim().any { it.isWhitespace() }) {
            _toast.value = "کلید نباید فاصله یا خط جدید داشته باشد؛ چیزی ذخیره نشد"
            return
        }
        marketSaveInFlight = true
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { container.settingsStore.saveMarketCredentials(key, symbol) }
                if (!saved) {
                    _toast.value = "ذخیرهٔ کلید روی دستگاه تأیید نشد؛ کلید قبلی را حذف نکنید و دوباره تلاش کنید"
                    return@launch
                }
                container.market.restart()
                container.watch.refreshNow()
                _toast.value = if (key.isBlank() && !settings.value.hasKey)
                    "نماد ذخیره شد؛ اتصال رایگان Yahoo/TradingView/Swissquote بازخوانی شد"
                else "کلید و نماد روی همین نصب ذخیره و بازخوانی شدند؛ وضعیت اتصال بازار را بررسی کنید"
            } catch (_: Exception) {
                _toast.value = "ذخیره/اتصال مجدد ناموفق بود؛ وضعیت دادهٔ بازار را بررسی کنید"
            } finally {
                marketSaveInFlight = false
            }
        }
    }

    private var marketSaveInFlight = false

    fun setWatchlist(symbols: List<String>) {
        container.settingsStore.update {
            it.copy(activeWatchlist = com.aurum.edge.core.V1Universe.watchlist(symbols))
        }
        container.pairScanner.refreshNow(minIntervalMs = 0L)
    }

    fun setCategoryStrategy(category: com.aurum.edge.core.AssetClass,
                            mode: com.aurum.edge.core.CategoryStrategy) = container.settingsStore.update {
        it.copy(categoryStrategies = it.categoryStrategies + (category to mode))
    }

    fun saveRiskPercent(value: Double) = container.settingsStore.update { it.copy(riskPercent = value.coerceIn(0.1, 0.5)) }

    fun saveBalance(value: Double) = container.settingsStore.update { it.copy(accountBalance = value.coerceAtLeast(10.0)) }

    fun saveMinConfidence(value: Double) = container.settingsStore.update { it.copy(minConfidence = value.coerceIn(60.0, 95.0)) }

    /** Cost assumptions are the user's responsibility; they are echoed in every report. */
    fun saveSpread(value: Double) = container.settingsStore.update { it.copy(spreadPrice = value.coerceIn(0.0, 5.0)) }

    fun saveCommission(value: Double) = container.settingsStore.update { it.copy(commissionPerOz = value.coerceIn(0.0, 5.0)) }

    fun setNotifyOnSignal(enabled: Boolean) = container.settingsStore.update { it.copy(notifyOnSignal = enabled) }

    fun selectAlertSound(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { AlertSoundPlayer.select(context, uri) }
                container.settingsStore.update { it.copy(alertSoundUri = uri.toString(), alertSoundName = name) }
                _toast.value = "صدای هشدار: $name؛ برای بررسی «پخش آزمون» را بزنید"
            } catch (error: Exception) {
                _toast.value = "صدای فایل انتخاب نشد: ${error.message ?: "دسترسی به فایل برقرار نیست"}"
            }
        }
    }

    fun resetAlertSound() {
        AlertSoundPlayer.stop()
        container.settingsStore.update { it.copy(alertSoundUri = "", alertSoundName = "") }
        _toast.value = "صدای پیش‌فرض اعلان گوشی انتخاب شد"
    }

    fun reportNotificationTest(posted: Boolean) {
        _toast.value = if (posted) "اعلان آزمایشی تحویل سیستم اندروید شد؛ نمایش/صدا را روی گوشی بررسی کنید"
            else "اعلان ارسال نشد: مجوز اعلان یا کانال آن بسته است؛ تنظیمات اعلان‌های اندروید را باز کنید"
    }

    fun testAlertSound(context: Context) {
        val uri = settings.value.alertSoundUri
        if (uri.isBlank()) {
            _toast.value = "برای شنیدن صدای سیستم، تنظیمات اعلان‌های اندروید را بررسی کنید"
            return
        }
        viewModelScope.launch {
            val queued = withContext(Dispatchers.IO) { AlertSoundPlayer.play(context, uri) }
            _toast.value = if (queued) "آزمون پخش فایل تا ۱۰ ثانیه؛ بلندی صدا/مزاحم‌نشدن دستگاه را بررسی کنید"
                else "فایل صوتی دیگر قابل خواندن نیست؛ دوباره انتخاب کنید"
        }
    }

    fun setMonitorFlag(enabled: Boolean) {
        container.settingsStore.update { it.copy(backgroundMonitor = enabled) }
        if (!enabled) _toast.value = "پایش پس‌زمینه خاموش/ناموفق است؛ ورود کاغذی خودکار در زمان باز بودن برنامه همچنان با قواعد موتور بررسی می‌شود"
    }

    fun setAutoPaperTrading(enabled: Boolean) {
        container.settingsStore.update { it.copy(autoPaperTrading = enabled) }
        if (enabled) container.news.refreshNow()
        _toast.value = if (enabled) "خودکار کاغذی روشن است؛ فقط با موتور چهارلایه، هفت بازهٔ واقعی و قفل‌های ریسک/AI ثبت می‌شود"
            else "معاملهٔ خودکار کاغذی خاموش شد"
    }

    private var paperOpening = false

    /** A price received recently is still not an exchange fill; only a paper ticket can use it. */
    private fun freshPaperQuote(current: MarketState): String? {
        val now = System.currentTimeMillis()
        val lastBar = current.candles.lastOrNull()?.time
        return when {
            current.symbol != settings.value.symbol || current.interval != settings.value.interval ->
                "نماد یا بازهٔ قیمت با تنظیمات فعلی فرق دارد"
            current.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING) || current.showingCachedData ->
                "قیمت زنده نیست؛ ورود/خروج روی کش ممنوع است"
            current.lastPrice?.let { it.isFinite() && it > 0.0 } != true -> "قیمت معتبر موجود نیست"
            !FeedLiveness.hasRecentReceipt(current.feed, now) -> "آخرین دریافت قیمت قدیمی است"
            lastBar == null || now - lastBar !in 0L..min(180_000L, current.interval.millis * 2) ->
                "کندل این نماد برای ورود خیلی قدیمی است"
            else -> null
        }
    }

    private fun entryBlocker(current: MarketState): String? {
        com.aurum.edge.core.OilEntrySafety.blocker(current.symbol)?.let { return it }
        freshPaperQuote(current)?.let { return it }
        if (com.aurum.edge.core.MarketHours.closedFor(current.symbol)) return "بازار بسته است"
        val candidate = current.signal
        if (candidate?.isActionable != true || !TechnicalEvidence.confirmed(candidate))
            return candidate?.blockers?.joinToString("، ") ?: "سیگنال چهارلایهٔ معتبر وجود ندارد"
        if (settings.value.pauseOnNews) {
            val checked = news.value.lastCheckedAt
            if (news.value.gate != NewsGate.CLEAR || checked == null ||
                System.currentTimeMillis() - checked !in 0L..180_000L) {
                return "خبر پراثر، نامعتبر یا وضعیت خبری نامشخص/قدیمی؛ توقف ورود جدید"
            }
        }
        WatchCatalog.find(current.symbol)?.let { watched ->
            val selected = watchSettings.value[current.symbol]
            if (selected != null && SourceComparison.verify(watched, selected.enabledSources,
                    watch.value.quotes[current.symbol].orEmpty()).status == VerificationStatus.CONFLICT) {
                return "قیمت منابع مستقل با هم تعارض دارد"
            }
        }
        PaperPortfolioPolicy.blocker(trades.value, current.symbol, settings.value.accountBalance, 0.0)?.let { return it }
        return null
    }

    fun previewManualTicket(side: SignalAction, stop: Double?, target: Double?): Result<PaperTicket> = runCatching {
        val current = market.value
        entryBlocker(current)?.let { throw IllegalArgumentException(it) }
        require(current.signal?.action == side) { "جهت انتخابی باید با سیگنال چهارلایه برابر باشد" }
        PaperOrderRules.preview(side, current.symbol, current.lastPrice!!,
            stop ?: throw IllegalArgumentException("حد ضرر را وارد کنید"),
            target ?: throw IllegalArgumentException("حد سود را وارد کنید"),
            settings.value.accountBalance, settings.value.riskPercent)
    }

    fun openPaperTrade(signal: Signal) = submitPaper(signal, manual = false)

    fun openManualPaperTrade(side: SignalAction, stop: Double, target: Double,
                             expectedPrice: Double, expectedSymbol: String) {
        val verified = market.value.signal
        if (verified?.isActionable != true || verified.action != side ||
            !TechnicalEvidence.confirmed(verified)) {
            _toast.value = "ورود دستی بدون سیگنال چهارلایهٔ هم‌جهت ممنوع است"
            return
        }
        submitPaper(verified.copy(stopLoss = stop, takeProfit = target),
            manual = true, expectedPrice = expectedPrice, expectedSymbol = expectedSymbol)
    }

    private fun submitPaper(signal: Signal, manual: Boolean, expectedPrice: Double? = null,
                            expectedSymbol: String? = null) {
        if (paperOpening) {
            _toast.value = "درخواست کاغذی قبلی هنوز ذخیره نشده است"
            return
        }
        fun verified(): Pair<MarketState, Double> {
            val current = market.value
            entryBlocker(current)?.let { throw IllegalArgumentException(it) }
            val price = current.lastPrice!!
            if (manual) {
                require(current.signal?.isActionable == true && current.signal?.action == signal.action &&
                    current.signal?.barTime == signal.barTime && TechnicalEvidence.confirmed(signal)) {
                    "سیگنال چهارلایه یا وتوی AI تغییر کرده است"
                }
                require(expectedSymbol == current.symbol && expectedPrice != null && expectedPrice.isFinite() &&
                    expectedPrice > 0.0 && abs(price / expectedPrice - 1.0) <= 0.001) {
                    "قیمت/نماد نسبت به پیش‌نمایش تغییر کرده است؛ دوباره بررسی کنید"
                }
            } else {
                require(signal.isActionable && current.signal == signal && signal.interval == current.interval &&
                    signal.entry != null && signal.entry.isFinite() && signal.entry > 0.0 &&
                    abs(price / signal.entry - 1.0) <= 0.005) {
                    "سیگنال قدیمی، وتوشده یا دور از قیمت تازه است"
                }
                require(TechnicalEvidence.confirmed(signal)) {
                    "شروط فنی دیگر معتبر نیست؛ ورود سیگنالی متوقف شد"
                }
            }
            PaperOrderRules.preview(signal.action, current.symbol, price,
                signal.stopLoss ?: throw IllegalArgumentException("حد ضرر لازم است"),
                signal.takeProfit ?: throw IllegalArgumentException("حد سود لازم است"),
                settings.value.accountBalance, settings.value.riskPercent)
            return current to price
        }
        try {
            verified()
        } catch (error: Exception) {
            _toast.value = "ورود کاغذی متوقف: ${error.message ?: "شرایط ورود معتبر نیست"}"
            return
        }
        paperOpening = true
        viewModelScope.launch {
            try {
                // Repeat all checks after scheduling; never persist a stale or switched symbol.
                val (current, price) = verified()
                val s = container.settingsStore.read()
                val newsRecord = if (manual) null else NewsConfluence.record(news.value, current.symbol)
                val ict = if (manual) null else IctEntryRules.approvedEvidence(current) // optional historical detail
                // «روند کلی بازار» در همان لحظهٔ ورود عکس گرفته و در ژورنال ثبت می‌شود تا برای
                // هر معامله معلوم باشد با روند بوده یا خلافش. فقط ثبت است؛ گیتِ ورود همان‌جاست
                // که تصمیم گرفته می‌شود (پویشگر و ورود خودکار)، نه بعد از ذخیره.
                val trendRecord = runCatching {
                    val trend = symbolTrend.value?.takeIf { it.symbol == current.symbol }
                    com.aurum.edge.core.MarketTrendRecord.from(
                        com.aurum.edge.core.MarketTrend.contextOf(
                            signal.action, trend, marketTrend.value, playbook.value?.method,
                        ),
                    )
                }.getOrNull()
                val trade = container.journalStore.open(
                    signal = signal, symbol = current.symbol, price = price,
                    balance = s.accountBalance, riskPercent = s.riskPercent,
                    mtf = if (manual) null else _mtf.value?.let { MtfSnapshotRecord.from(it) },
                    manual = manual,
                    newsEvidence = newsRecord, priceAction = ict,
                    marketTrend = trendRecord,
                )
                _stats.value = container.journalStore.stats()
                // Linking is metadata only; a damaged opportunity file must not erase a saved trade.
                runCatching { container.opportunityStore.linkTrade(trade) }
                val reviewedTrade = runCatching {
                    val review = container.traderAdvisor.reviewPaperEntry(trade, current)
                    container.journalStore.attachAiReview(trade.id, review)
                }.getOrNull()
                reviewedTrade?.let { _stats.value = container.journalStore.stats() }
                val conditions = trade.entryConditions.joinToString("، ") {
                    it.name.substringAfter('·').trim()
                }
                _toast.value = "کاغذی: ${if (trade.action == SignalAction.BUY) "لانگ" else "شورت"} ${trade.symbol} · شروع: $conditions"
                Notifier.notifyTradeOpened(container.appContext, reviewedTrade ?: trade, s.alertSoundUri)
            } catch (e: Exception) {
                _toast.value = "ورود کاغذی انجام نشد: ${e.message ?: "ذخیره ممکن نیست"}"
            } finally {
                paperOpening = false
            }
        }
    }

    fun closePaperTrade(trade: PaperTrade) {
        val currentPrice = _livePrices.value[trade.symbol] ?: (if (trade.symbol == market.value.symbol) market.value.lastPrice else null)
        viewModelScope.launch {
            try {
                val exitPrice = currentPrice ?: runCatching { SpotFallbackClient().fetchQuote(trade.symbol).price }.getOrNull()
                require(exitPrice != null && exitPrice.isFinite() && exitPrice > 0.0) { "قیمت خروج تازه برای ${trade.symbol} در دسترس نیست" }
                val closed = container.journalStore.close(trade.id, exitPrice, "بستن دستی روی قیمت دریافتی")
                _stats.value = container.journalStore.stats()
                _toast.value = "پوزیشن کاغذی ${trade.symbol} با سود/ضرر ${String.format(java.util.Locale.US, "%.2f", closed.pnlUsd ?: 0.0)}$ بسته شد"
                Notifier.notifyClosedTrade(container.appContext, closed)
            } catch (error: Exception) {
                _toast.value = "بستن انجام نشد: ${error.message ?: "خطا در ذخیره"}"
            }
        }
    }

    fun clearJournal() {
        viewModelScope.launch {
            try {
                container.journalStore.clear()
                _stats.value = container.journalStore.stats()
                _toast.value = "ژورنال کاغذی روی دستگاه پاک شد"
            } catch (e: Exception) {
                _toast.value = "پاک‌کردن انجام نشد؛ فایل حفظ شد: ${e.message ?: "خطای ذخیره"}"
            }
        }
    }

    fun clearOpportunityHistory() {
        viewModelScope.launch {
            try {
                container.opportunityStore.clear()
                _toast.value = "فقط تاریخچهٔ فرصت‌های اسکن‌شده پاک شد؛ آمار معامله تغییر نکرد"
            } catch (error: Exception) {
                _toast.value = "حذف کاندیدا انجام نشد؛ فایل قبلی حفظ شد: ${error.message ?: "خطای ذخیره"}"
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.candleCache.clear()
            _toast.value = "کش دیتای واقعی پاک شد"
            container.market.restart()
        }
    }

    fun selectWatchSource(symbolId: String, sourceId: String, enabled: Boolean) {
        container.watchSettings.selectSource(symbolId, sourceId, enabled)
        if (enabled) container.watch.refreshNow()
    }

    fun setWatchPreferred(symbolId: String, sourceId: String) = container.watchSettings.setPreferred(symbolId, sourceId)

    fun watchKeyOverride(symbolId: String): String = container.watchSettings.keyOverride(symbolId)

    fun setWatchKeyOverride(symbolId: String, key: String) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { container.watchSettings.setKeyOverride(symbolId, key) }
            if (!saved) {
                _toast.value = "کلید اختصاصی ذخیره نشد؛ فاصله/خط جدید یا حافظهٔ دستگاه را بررسی کنید"
                return@launch
            }
            container.watch.refreshNow()
            _toast.value = if (key.isBlank()) "کلید اختصاصی این نماد حذف شد؛ کلید چارت در صورت وجود استفاده می‌شود"
                else "کلید خواندنی این نماد روی همین نصب ذخیره و بازخوانی شد"
        }
    }

    fun showWatchHistory(symbolId: String, sourceId: String) {
        if (_watchHistory.value.symbolId == symbolId && _watchHistory.value.sourceId == sourceId) {
            _watchHistory.value = WatchHistory()
            return
        }
        _watchHistory.value = WatchHistory(symbolId, sourceId, loading = true)
        viewModelScope.launch {
            val page = container.watch.page(symbolId, sourceId)
            val total = container.watch.count(symbolId, sourceId)
            if (_watchHistory.value.symbolId == symbolId && _watchHistory.value.sourceId == sourceId) {
                _watchHistory.value = WatchHistory(symbolId, sourceId, page, total)
            }
        }
    }

    fun moreWatchHistory() {
        val current = _watchHistory.value
        if (current.loading || current.total <= current.entries.size || current.entries.isEmpty()) return
        _watchHistory.value = current.copy(loading = true)
        viewModelScope.launch {
            val next = container.watch.page(current.symbolId, current.sourceId, current.entries.last().ts)
            if (_watchHistory.value.symbolId == current.symbolId && _watchHistory.value.sourceId == current.sourceId) {
                _watchHistory.value = current.copy(entries = current.entries + next)
            }
        }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            container.watch.clearHistory()
            _watchHistory.value = WatchHistory()
            _toast.value = "تاریخچهٔ دریافت‌شدهٔ دیده‌بان پاک شد"
        }
    }

    /**
     * Draw a *recorded* paper trade on the real candles this device already verified.
     * Tapping the same trade again closes the chart. Nothing here re-evaluates the trade: the
     * entry/SL/TP levels are read from the stored record, never recomputed from today's engine.
     */
    fun showTradeChart(trade: PaperTrade) {
        if (_tradeChart.value.tradeId == trade.id) {
            _tradeChart.value = TradeChartState()
            return
        }
        _tradeChart.value = TradeChartState(tradeId = trade.id, loading = true)
        viewModelScope.launch {
            val cached = runCatching { container.cachedCandles(trade.symbol, trade.interval) }
            if (_tradeChart.value.tradeId != trade.id) return@launch
            val bars = cached.getOrNull()
            if (bars == null) {
                _tradeChart.value = TradeChartState(tradeId = trade.id,
                    error = "کش کندل این نماد خوانده نشد؛ برای جلوگیری از نمایش دادهٔ نامعتبر، نموداری رسم نمی‌شود")
                return@launch
            }
            val window = withContext(Dispatchers.Default) { TradeReplay.window(trade, bars) }
            _tradeChart.value = TradeChartState(
                tradeId = trade.id,
                window = window,
                source = if (window.bars.isEmpty()) "" else "کش کندل‌های تأییدشدهٔ همین گوشی",
            )
        }
    }

    /**
     * The local cache does not reach back far enough: ask the provider for the real bars of this
     * pair/timeframe with the user's own read-only key. Display only — no order, no signal.
     */
    fun downloadTradeChart(trade: PaperTrade) {
        val current = _tradeChart.value
        if (current.tradeId != trade.id || current.downloading) return
        _tradeChart.value = current.copy(downloading = true, error = null)
        viewModelScope.launch {
            try {
                val (bars, source) = container.fetchTradeCandles(trade.symbol, trade.interval)
                if (_tradeChart.value.tradeId != trade.id) return@launch
                val window = withContext(Dispatchers.Default) { TradeReplay.window(trade, bars) }
                _tradeChart.value = TradeChartState(
                    tradeId = trade.id,
                    window = window,
                    source = if (window.bars.isEmpty()) "" else "دریافت تازه از $source",
                )
                if (window.bars.isEmpty()) {
                    _toast.value = "ناشر برای این بازه کندلی برنگرداند؛ تاریخچهٔ این معامله در دسترس نیست"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (_tradeChart.value.tradeId != trade.id) return@launch
                _tradeChart.value = _tradeChart.value.copy(
                    downloading = false,
                    error = error.message ?: "دریافت کندل‌های این بازه انجام نشد",
                )
            }
        }
    }

    fun setInterval(interval: Interval) = container.market.setInterval(interval)

    fun saveNewsAiConfig(apiKey: String, baseUrl: String, model: String, format: String = "AUTO") {
        val url = baseUrl.trim().trimEnd('/')
        if (apiKey.isNotBlank() && (!url.startsWith("https://") || model.isBlank())) {
            _toast.value = "برای کلید مستقیم، نشانی HTTPS و نام مدل هم لازم است"
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { container.settingsStore.saveNewsAiConfig(apiKey, url, model, format) }
            if (!saved) {
                _toast.value = "ذخیرهٔ کلید مستقیم ناموفق بود"
                return@launch
            }
            container.news.resetAndRefresh()
            // The companion card reacts immediately to a newly saved/cleared key.
            container.traderAdvisor.refreshNow(force = apiKey.isNotBlank())
            _toast.value = if (apiKey.isBlank()) "کلید مستقیم حذف شد"
                else "کلید مستقیم ذخیره شد؛ همراه تریدر AI هم فعال شد — نظر اول چند لحظهٔ دیگر می‌آید"
        }
    }

    fun clearNewsAiConfig() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.settingsStore.clearNewsAiConfig() }
            container.news.resetAndRefresh()
            container.traderAdvisor.refreshNow() // flips the companion card to "not configured"
            _toast.value = "کلید مستقیم حذف شد"
        }
    }

    /**
     * One-tap proof of life for the user's own model endpoint. Empty fields fall back to the
     * SAVED values (the key field is intentionally blanked after saving) so the test always
     * checks what the app would actually use. The key itself is never echoed.
     */
    /** Fetch the model IDs this key may use, so Settings never guesses a model name. */
    fun loadNewsAiModels(keyInput: String, baseUrl: String, format: String = "AUTO") {
        val saved = container.settingsStore.read()
        val key = keyInput.trim().ifBlank { saved.newsAiApiKey }
        val url = baseUrl.trim().trimEnd('/').ifBlank { saved.newsAiBaseUrl }
        if (key.isBlank() || !url.startsWith("https://")) {
            _aiModels.value = AiModelsState(error = "کلید و نشانی HTTPS هر دو لازم است")
            return
        }
        _aiModels.value = AiModelsState(loading = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { container.traderAdvisor.listModels(key, url, format) }
            }
            _aiModels.value = result.fold(
                onSuccess = { AiModelsState(models = it) },
                onFailure = { AiModelsState(error = (it.message ?: "خطای نامشخص").take(120)) })
        }
    }

    fun testNewsAiConnection(keyInput: String, baseUrl: String, model: String, format: String = "AUTO") {
        val saved = container.settingsStore.read()
        val key = keyInput.trim().ifBlank { saved.newsAiApiKey }
        val url = baseUrl.trim().trimEnd('/').ifBlank { saved.newsAiBaseUrl }
        val modelName = model.trim().ifBlank { saved.newsAiModel }
        if (key.isBlank() || !url.startsWith("https://") || modelName.isBlank()) {
            _aiProbe.value = AiProbeState(message = "کلید، نشانی HTTPS و نام مدل هر سه لازم است")
            return
        }
        _aiProbe.value = AiProbeState(running = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.traderAdvisor.probe(key, url, modelName, format)
                }
            }
            _aiProbe.value = AiProbeState(message = result.fold(
                onSuccess = { "اتصال تأیید شد؛ پاسخ مدل: «$it»" },
                onFailure = { "اتصال ناموفق: ${(it.message ?: "خطای نامشخص").take(120)}" }))
        }
    }

    fun saveNewsBaseUrl(value: String) {
        val url = value.trim().trimEnd('/')
        if (url.isNotBlank() && NewsRepository.newsUrl(url) == null) {
            _toast.value = "آدرس HTTPS سرور خبر بدون مسیر و کلید وارد کنید"
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { container.settingsStore.saveNewsBaseUrl(url) }
            if (!saved) {
                _toast.value = "ذخیرهٔ آدرس سرور روی دستگاه ناموفق بود؛ خبر AI هنوز تأیید نشده است"
                return@launch
            }
            container.news.resetAndRefresh()
            _toast.value = if (url.isBlank()) "سرور خبر جدا شد؛ تیترهای وب در تب خبر بدون سرور قابل دریافت‌اند؛ خبر فقط ریسک زرد/وتو می‌دهد"
                else "آدرس سرور ذخیره شد؛ پاسخ فید، مدل AI و ریسک خبر را در تب خبر جداگانه بررسی کنید"
        }
    }

    fun setPauseOnNews(enabled: Boolean) {
        container.settingsStore.update { it.copy(pauseOnNews = enabled) }
        if (enabled) container.news.refreshNow()
    }

    /** PDF export of REAL journal trades already on disk. */
    fun exportJournalPdf(uri: Uri, title: String, tradesForPdf: List<PaperTrade>) {
        viewModelScope.launch {
            try {
                container.exportJournalPdf(uri, title, tradesForPdf, settings.value.accountBalance)
                _toast.value = "PDF ژورنال ذخیره شد (${tradesForPdf.count { !it.isOpen }} معاملهٔ بسته)"
            } catch (error: Exception) {
                _toast.value = "ذخیرهٔ PDF انجام نشد: ${error.message ?: "خطای فایل"}"
            }
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
