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
import com.aurum.edge.core.PaperTicket
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TradeReplay
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.data.FreeHistoryCatalog
import com.aurum.edge.data.FreeHistoryState
import com.aurum.edge.data.JournalStats
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsRepository
import com.aurum.edge.data.Quote
import com.aurum.edge.data.WatchSelection
import com.aurum.edge.data.WatchState
import com.aurum.edge.engine.Backtester
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.engine.NewsConfluence
import com.aurum.edge.notify.AlertSoundPlayer
import com.aurum.edge.service.SignalMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

sealed interface LearnState {
    data object Idle : LearnState
    data class Loading(val step: String) : LearnState
    data class Done(val result: Backtester.Result, val interval: Interval) : LearnState
    data class Failed(val message: String) : LearnState
}

sealed interface WalkForwardState {
    data object Idle : WalkForwardState
    data class Loading(val step: String) : WalkForwardState
    data class Done(val result: Backtester.WalkForward, val interval: Interval, val saved: Boolean) : WalkForwardState
    data class Failed(val message: String) : WalkForwardState
}

data class WatchHistory(
    val symbolId: String = "",
    val sourceId: String = "",
    val entries: List<Quote> = emptyList(),
    val total: Long = 0L,
    val loading: Boolean = false,
)

/**
 * Real candles around ONE recorded paper trade, for the journal chart.
 *
 * [window] only ever holds bars that passed the same verification as the live chart; when the
 * device has no history for that period the state stays empty and says so instead of drawing
 * anything invented.
 */
data class TradeChartState(
    val tradeId: String = "",
    val loading: Boolean = false,
    val downloading: Boolean = false,
    val window: TradeReplay.Window = TradeReplay.Window(),
    /** Where the drawn bars came from — cache of this device or a fresh provider download. */
    val source: String = "",
    val error: String? = null,
)

class AurumViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
    val watchSettings: StateFlow<Map<String, WatchSelection>> = container.watchSettings.selections
    val watch: StateFlow<WatchState> = container.watch.state
    val news = container.news.state
    val publicWebNews = container.publicWebNews.state // Forex publisher snippets, not ninth-confluence evidence
    val forexCalendar = container.forexCalendar.state
    /** All-pairs radar: periodic online candle sweep status of every catalog pair. */
    val pairScan = container.pairScanner.state
    /** The AI trading companion's latest strictly-validated opinion (analysis, never a signal). */
    val traderOpinion = container.traderAdvisor.state
    val market = container.verifiedMarket
    val trades: StateFlow<List<PaperTrade>> = container.journalStore.trades
    val opportunities: StateFlow<List<PaperOpportunity>> = container.opportunityStore.items
    val opportunityError: StateFlow<String?> = container.opportunityStore.loadError
    val journalError: StateFlow<String?> = container.journalStore.loadError
    val autoPaperStatus: StateFlow<String> = container.autoPaperTrader.status
    val updateState: StateFlow<AppUpdateRepository.State> = container.updater.state

    /** Walk-forward runs made on this device, kept so the numbers can be re-checked later. */
    val reports: StateFlow<List<WalkForwardRecord>> = container.journalStore.reports
    val reportError: StateFlow<String?> = container.journalStore.reportError

    private val _stats = MutableStateFlow(container.journalStore.stats())
    val stats: StateFlow<JournalStats> = _stats.asStateFlow()

    private val _learn = MutableStateFlow<LearnState>(LearnState.Idle)
    val learn: StateFlow<LearnState> = _learn.asStateFlow()

    private val _freeHistory = MutableStateFlow<FreeHistoryState>(FreeHistoryState.Idle)
    val freeHistory: StateFlow<FreeHistoryState> = _freeHistory.asStateFlow()

    private val _walkForward = MutableStateFlow<WalkForwardState>(WalkForwardState.Idle)
    val walkForward: StateFlow<WalkForwardState> = _walkForward.asStateFlow()

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

    /** Result of the one-tap AI connectivity probe from Settings (never carries the key). */
    data class AiProbeState(val running: Boolean = false, val message: String? = null)
    private val _aiProbe = MutableStateFlow(AiProbeState())
    val aiProbe: StateFlow<AiProbeState> = _aiProbe.asStateFlow()

    /** Allowed model IDs for the user's key, fetched live from the service catalogue. */
    data class AiModelsState(val loading: Boolean = false, val models: List<String> = emptyList(),
                             val error: String? = null)
    private val _aiModels = MutableStateFlow(AiModelsState())
    val aiModels: StateFlow<AiModelsState> = _aiModels.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.journalStore.load() }.onFailure {
                _toast.value = "ژورنال خوانده نشد؛ فایل قبلی برای بازیابی نگه داشته شد"
            }
            runCatching { container.opportunityStore.load() }.onFailure {
                _toast.value = "تاریخچهٔ فرصت‌ها خوانده نشد؛ فایل قبلی نگه داشته شد و هشدار تکراری متوقف است"
            }
            runCatching { container.journalStore.loadReports() }.onFailure {
                _toast.value = "گزارش پژوهش خوانده نشد؛ فایل قبلی نگه داشته شد"
            }
            _stats.value = container.journalStore.stats()
        }
        viewModelScope.launch {
            // Automatic SL/TP settlement happens in MarketRepository, not in this ViewModel.
            // Keep totals in sync with the journal flow even while a screen is not open.
            trades.collect { _stats.value = container.journalStore.stats() }
        }
        viewModelScope.launch {
            while (isActive) {
                if (!MarketHours.forexWeekendClosed() &&
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
    }

    /** Forex is the app's only workspace: start the real feed as soon as the UI is visible. */
    fun startApp(context: Context) {
        visibleOnlineLoopEnabled = true
        container.market.start()
        container.watch.loadCached()
        ensureVisibleOnlineLoop()
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
                if ((visibleOnlineLoopEnabled || SignalMonitorService.running.value) && !MarketHours.forexWeekendClosed()) {
                    if (turns % 3 == 0) container.watch.refreshNow()
                    container.forexCalendar.refreshNow()
                    if (turns % 5 == 0) container.publicWebNews.refreshNow()
                    if (settings.value.pauseOnNews &&
                        (settings.value.newsBaseUrl.isNotBlank() || settings.value.hasClientNewsAi)) {
                        container.news.refreshNow()
                    }
                    container.traderAdvisor.refreshNow()
                }
                turns++
                delay(60_000L)
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

    /** Manual all-pairs sweep. Candidates are recorded (journal/radar) without playing a sound:
     * the user is looking at the screen; background alerts come from the monitor service. */
    fun scanPairs() {
        if (container.pairScanner.state.value.sweeping) {
            _toast.value = "اسکن همگانی در حال اجراست"
            return
        }
        viewModelScope.launch {
            _toast.value = "اسکن همگانی ۸ جفت‌ارز آغاز شد (حدود یک دقیقه؛ سهمیهٔ منابع رعایت می‌شود)"
            try {
                container.pairScanner.sweepOnce(minIntervalMs = 3 * 60_000L) { }
            } catch (_: Exception) {
                _toast.value = "اسکن همگانی ناتمام ماند؛ وضعیت هر نماد در رادار مشخص است"
            }
        }
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
    fun selectChartSymbol(symbol: String) {
        if (symbol == settings.value.symbol) return
        if (symbol !in WatchCatalog.chartSymbols) {
            _toast.value = "این نسخه فقط طلا و جفت‌ارزهای اصلی دیده‌بان را چارت می‌کند"
            return
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { container.settingsStore.saveChartSymbol(symbol) }
                if (!saved) {
                    _toast.value = "ذخیرهٔ نماد روی دستگاه تأیید نشد؛ دوباره تلاش کنید"
                    return@launch
                }
                container.market.restart()
                _toast.value = "نماد چارت و سیگنال: $symbol"
            } catch (_: Exception) {
                _toast.value = "تغییر نماد ناموفق بود؛ دوباره تلاش کنید"
            }
        }
    }

    /** One verified write, one feed restart; never echo a credential into a toast or log. */
    fun saveMarketCredentials(key: String, symbol: String) {
        if (marketSaveInFlight) return
        if (key.isBlank() && !settings.value.hasKey) {
            _toast.value = "ابتدا کلید تازهٔ Twelve Data را روی همین گوشی وارد کنید"
            return
        }
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
                _toast.value = "کلید و نماد روی همین نصب ذخیره و بازخوانی شدند؛ برای اعتبار کلید، وضعیت اتصال بازار را بررسی کنید"
            } catch (_: Exception) {
                _toast.value = "ذخیره/اتصال مجدد ناموفق بود؛ وضعیت دادهٔ بازار را بررسی کنید"
            } finally {
                marketSaveInFlight = false
            }
        }
    }

    private var marketSaveInFlight = false

    fun saveRiskPercent(value: Double) = container.settingsStore.update { it.copy(riskPercent = value.coerceIn(0.1, 5.0)) }

    fun saveBalance(value: Double) = container.settingsStore.update { it.copy(accountBalance = value.coerceAtLeast(10.0)) }

    fun saveMinConfidence(value: Double) = container.settingsStore.update { it.copy(minConfidence = value.coerceIn(72.0, 95.0)) }

    fun setSignalMomentumVolume(enabled: Boolean) = updateSignalProfile("فیلتر مومنتوم/حجم", enabled) {
        it.copy(momentumVolume = enabled)
    }

    fun setSignalFlatSpanB(enabled: Boolean) = updateSignalProfile("سناریوی تختی SpanB52", enabled) {
        it.copy(flatSpanB = enabled)
    }

    fun setSignalRangeChop(enabled: Boolean) = updateSignalProfile("فیلتر بازار رنج", enabled) {
        it.copy(rangeChopFilter = enabled)
    }

    fun setSignalHigherTimeframe(enabled: Boolean) = updateSignalProfile("تأیید تایم‌فریم بالاتر", enabled) {
        it.copy(higherTimeframeFilter = enabled)
    }

    fun setSignalFakeBreakout(enabled: Boolean) = updateSignalProfile("فیلتر فیک‌بریک‌اوت", enabled) {
        it.copy(fakeBreakoutFilter = enabled)
    }

    fun setSignalDynamicSpread(enabled: Boolean) = updateSignalProfile("فیلتر اسپرد پویا", enabled) {
        it.copy(dynamicSpreadFilter = enabled)
    }

    fun setSignalRiskyTiming(enabled: Boolean) = updateSignalProfile("فیلتر زمان‌های خطرناک", enabled) {
        it.copy(riskyTimingFilter = enabled)
    }

    fun setSignalStructureRisk(enabled: Boolean) = updateSignalProfile("فیلتر ریسک ساختار", enabled) {
        it.copy(structureRiskFilter = enabled)
    }

    fun setSignalCooldown(enabled: Boolean) = updateSignalProfile("کول‌داون بعد از شکست", enabled) {
        it.copy(cooldownFilter = enabled)
    }

    private fun updateSignalProfile(label: String, enabled: Boolean, transform: (SignalProfile) -> SignalProfile) {
        container.settingsStore.update { it.copy(signalProfile = transform(it.signalProfile)) }
        container.market.restart()
        _toast.value = "$label ${if (enabled) "به موتور پایه اضافه شد" else "از افزونه‌های موتور برداشته شد"}"
    }

    /** Cost assumptions are the user's responsibility; they are echoed in every report. */
    fun saveSpread(value: Double) = container.settingsStore.update { it.copy(spreadPrice = value.coerceIn(0.0, 5.0)) }

    fun saveCommission(value: Double) = container.settingsStore.update { it.copy(commissionPerOz = value.coerceIn(0.0, 5.0)) }

    fun setNotifyOnSignal(enabled: Boolean) = container.settingsStore.update { it.copy(notifyOnSignal = enabled) }

    fun selectAlertSound(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { AlertSoundPlayer.select(context, uri) }
                container.settingsStore.update { it.copy(alertSoundUri = uri.toString(), alertSoundName = name) }
                _toast.value = "صدای هشدار آموزشی: $name؛ برای بررسی «پخش آزمون» را بزنید"
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
        container.settingsStore.update { it.copy(backgroundMonitor = enabled,
            autoPaperTrading = if (enabled) it.autoPaperTrading else false) }
        if (!enabled) _toast.value = "پایش پس‌زمینه خاموش/ناموفق است؛ ورود خودکار کاغذی غیرفعال ماند"
    }

    fun setAutoPaperTrading(enabled: Boolean) {
        if (enabled && !settings.value.backgroundMonitor) {
            _toast.value = "برای خودکار کاغذی، پایش پس‌زمینه را فعال کنید"
            return
        }
        container.settingsStore.update { it.copy(autoPaperTrading = enabled) }
        if (enabled) container.news.refreshNow()
        _toast.value = if (enabled) "خودکار کاغذی روشن است؛ ۸ شرط فنی، آپشن‌های فعال، قیمت زنده و ICT/MTF لازم است؛ خبر فقط در ژورنال داده‌کاوی می‌شود"
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
        freshPaperQuote(current)?.let { return it }
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
        if (trades.value.any { it.isOpen && it.symbol == current.symbol }) return "برای این نماد یک پوزیشن کاغذی باز است"
        return null
    }

    fun previewManualTicket(side: SignalAction, stop: Double?, target: Double?): Result<PaperTicket> = runCatching {
        val current = market.value
        entryBlocker(current)?.let { throw IllegalArgumentException(it) }
        PaperOrderRules.preview(side, current.symbol, current.lastPrice!!,
            stop ?: throw IllegalArgumentException("حد ضرر را وارد کنید"),
            target ?: throw IllegalArgumentException("حد سود را وارد کنید"),
            settings.value.accountBalance, settings.value.riskPercent)
    }

    fun openPaperTrade(signal: Signal) = submitPaper(signal, manual = false)

    fun openManualPaperTrade(side: SignalAction, stop: Double, target: Double,
                             expectedPrice: Double, expectedSymbol: String) {
        val signal = Signal(action = side, confidence = 0.0, stopLoss = stop,
            takeProfit = target, interval = market.value.interval)
        submitPaper(signal, manual = true, expectedPrice = expectedPrice, expectedSymbol = expectedSymbol)
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
                require(expectedSymbol == current.symbol && expectedPrice != null && expectedPrice.isFinite() &&
                    expectedPrice > 0.0 && abs(price / expectedPrice - 1.0) <= 0.001) {
                    "قیمت/نماد نسبت به پیش‌نمایش تغییر کرده است؛ دوباره بررسی کنید"
                }
            } else {
                require(signal.isActionable && current.signal == signal && signal.interval == current.interval &&
                    signal.entry != null && signal.entry.isFinite() && signal.entry > 0.0 &&
                    abs(price / signal.entry - 1.0) <= 0.005 && _mtf.value?.veto != true) {
                    "سیگنال قدیمی، وتوشده یا دور از قیمت تازه است"
                }
                val technicalConditions = signal.confluence.filterNot { it.name == NewsConfluence.NEWS_LABEL }
                require(technicalConditions.take(8).size == 8 &&
                    technicalConditions.take(8).all { it.ok && it.status == com.aurum.edge.core.ConfluenceStatus.CONFIRMED }) {
                    "۸ شرط فنی دیگر معتبر نیست؛ ورود سیگنالی متوقف شد"
                }
                IctEntryRules.assess(current).reason?.let { throw IllegalArgumentException(it) }
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
                val ict = if (manual) null else (IctEntryRules.approvedEvidence(current)
                    ?: error("شواهد رنج/ICT همین کندل پیش از ثبت معتبر نیست"))
                val trade = container.journalStore.open(
                    signal = signal, symbol = current.symbol, price = price,
                    balance = s.accountBalance, riskPercent = s.riskPercent,
                    mtf = if (manual) null else _mtf.value?.let { MtfSnapshotRecord.from(it) },
                    manual = manual,
                    newsEvidence = newsRecord, priceAction = ict,
                )
                _stats.value = container.journalStore.stats()
                // Linking is metadata only; a damaged opportunity file must not erase a saved trade.
                runCatching { container.opportunityStore.linkTrade(trade) }
                val reviewedTrade = runCatching {
                    val review = container.traderAdvisor.reviewPaperEntry(trade, current)
                    container.journalStore.attachAiReview(trade.id, review)
                }.getOrNull()
                reviewedTrade?.let { _stats.value = container.journalStore.stats() }
                val aiLine = reviewedTrade?.aiReview?.let { review ->
                    " · نظر AI: ${review.verdictFa()}؛ ${review.summary}"
                } ?: if (s.hasClientNewsAi) " · نظر AI فعلاً ذخیره نشد" else " · برای نظر AI، کلید/مدل را در تنظیمات AI وارد کن"
                _toast.value = "فقط کاغذی: ${if (trade.action == SignalAction.BUY) "لانگ" else "شورت"} ${trade.symbol} · ${String.format("%.6f", trade.positionOz)} ${trade.unit}$aiLine"
            } catch (e: Exception) {
                _toast.value = "ورود کاغذی انجام نشد: ${e.message ?: "ذخیره ممکن نیست"}"
            } finally {
                paperOpening = false
            }
        }
    }

    fun closePaperTrade(trade: PaperTrade) {
        val current = market.value
        val blocked = freshPaperQuote(current)
        if (trade.symbol != current.symbol || blocked != null) {
            _toast.value = "بستن کاغذی متوقف: ${blocked ?: "نماد قیمت با پوزیشن فرق دارد"}"
            return
        }
        viewModelScope.launch {
            try {
                val latest = market.value
                require(trade.symbol == latest.symbol && freshPaperQuote(latest) == null) { "قیمت خروج تازهٔ همان نماد نیست" }
                val closed = container.journalStore.close(trade.id, latest.lastPrice!!, "بستن دستی روی قیمت دریافتی")
                _stats.value = container.journalStore.stats()
                _toast.value = "پوزیشن کاغذی ${closed.id.take(8)} با ${closed.pnlUsd} دلار بسته و در ژورنال ذخیره شد"
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
                _toast.value = "فقط تاریخچهٔ کاندیداهای آموزشی پاک شد؛ آمار معامله تغییر نکرد"
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

    fun runLearn(
        interval: Interval,
        bars: Int,
        balance: Double,
        risk: Double,
        spread: Double,
        commission: Double,
        threshold: Double,
    ) {
        viewModelScope.launch {
            val requestedBars = HistoryPolicy.providerRequestSize(bars)
            val displayBars = bars.coerceAtLeast(HistoryPolicy.TARGET_CANDLES)
            _learn.value = LearnState.Loading("دانلود حداقل $displayBars کندل واقعی ${interval.label} از منبع آنلاین فعال…")
            try {
                val result = container.runBacktest(interval, requestedBars, balance, risk, spread, commission, threshold)
                _learn.value = LearnState.Done(result, interval)
            } catch (e: Exception) {
                _learn.value = LearnState.Failed(e.message ?: "خطا در دریافت داده واقعی")
            }
        }
    }

    /** Automatically fetch fixed-source, read-only history without asking for a CSV URL. */
    fun downloadFreeHistory(id: String) {
        val choice = FreeHistoryCatalog.find(id) ?: run {
            _freeHistory.value = FreeHistoryState.Failed("نمادِ قابل دریافت پیدا نشد")
            return
        }
        if (_freeHistory.value is FreeHistoryState.Loading) return
        _freeHistory.value = FreeHistoryState.Loading(choice.title)
        viewModelScope.launch {
            try {
                _freeHistory.value = FreeHistoryState.Done(
                    container.freeHistory.download(id, container.settingsStore.read().apiKey))
            } catch (e: Exception) {
                _freeHistory.value = FreeHistoryState.Failed((e.message ?: "دادهٔ منبع دریافت نشد").take(160))
            }
        }
    }

    fun saveFreeHistoryCsv(uri: Uri) {
        val done = _freeHistory.value as? FreeHistoryState.Done ?: run {
            _toast.value = "ابتدا دادهٔ واقعی را دریافت کنید"
            return
        }
        viewModelScope.launch {
            try {
                container.exportFreeHistory(uri, done.result)
                _toast.value = "CSV ${done.result.choice.code} از دادهٔ دریافتی در فایل انتخابی ذخیره شد"
            } catch (e: Exception) {
                _toast.value = "ذخیرهٔ CSV انجام نشد: ${e.message ?: "فایل مقصد نامعتبر است"}"
            }
        }
    }

    /**
     * Offline historical HistData M1 archives (monthly CSV/ZIP or the site's yearly ZIP,
     * one or MANY files), merged and aggregated to the chosen research timeframe (M1..H1).
     * NEVER a market-feed or order source; gaps stay gaps, nothing is synthesised.
     */
    fun importHistData(uris: List<Uri>, interval: Interval, balance: Double, risk: Double,
                       spread: Double, commission: Double, threshold: Double) {
        if (uris.isEmpty()) { _learn.value = LearnState.Failed("ابتدا فایل(های) ZIP/CSV ماهانه یا سالانهٔ HistData را انتخاب کنید"); return }
        viewModelScope.launch {
            _learn.value = LearnState.Loading("خواندن ${uris.size} فایل HistData و تجمیع به تایم‌فریم ${interval.label}؛ قیمت BID تاریخی با EST ثابت…")
            try {
                val files = container.metaTraderImporter.fromHistDataFiles(uris)
                val result = container.runHistDataBacktest(files, interval, balance,
                    risk.coerceIn(0.1, 5.0), spread, commission, threshold)
                _learn.value = LearnState.Done(result, interval)
            } catch (e: Exception) {
                _learn.value = LearnState.Failed((e.message ?: "فایل‌های HistData قابل تحلیل نیست").take(160))
            }
        }
    }

    /** Imported MT/educational OHLC history is research-only: never written to the live chart/candle cache. */
    fun importMetaTrader(
        uri: Uri?, link: String?, symbol: String, interval: Interval, timezone: String,
        balance: Double, risk: Double, spread: Double, commission: Double, threshold: Double,
    ) {
        if (!symbol.matches(Regex("[A-Za-z0-9/_-]{3,30}"))) {
            _learn.value = LearnState.Failed("نام نماد وارداتی معتبر نیست")
            return
        }
        viewModelScope.launch {
            _learn.value = LearnState.Loading("خواندن CSV آموزشی/متاتریدر برای پژوهش؛ منشأ فایل تأیید نشده است…")
            try {
                val csv = when {
                    uri != null -> container.metaTraderImporter.fromFile(uri)
                    !link.isNullOrBlank() -> container.metaTraderImporter.fromHttps(link)
                    else -> throw IllegalArgumentException("فایل یا لینک CSV آموزشی/متاتریدر را انتخاب کنید")
                }
                val result = container.runImportedBacktest(csv, symbol, interval, timezone,
                    balance, risk.coerceIn(0.1, 5.0), spread, commission, threshold)
                _learn.value = LearnState.Done(result, interval)
            } catch (e: Exception) {
                _learn.value = LearnState.Failed(e.message ?: "فایل/لینک CSV آموزشی/متاتریدر قابل تحلیل نیست")
            }
        }
    }

    fun runWalkForward(
        interval: Interval,
        bars: Int,
        balance: Double,
        risk: Double,
        spread: Double,
        commission: Double,
        threshold: Double,
    ) {
        viewModelScope.launch {
            val requestedBars = HistoryPolicy.providerRequestSize(bars)
            val displayBars = bars.coerceAtLeast(HistoryPolicy.TARGET_CANDLES)
            _walkForward.value = WalkForwardState.Loading("دانلود حداقل $displayBars کندل واقعی ${interval.label} و تقسیم به داخل/خارج نمونه…")
            try {
                val result = container.runWalkForward(interval, requestedBars, balance, risk, spread, commission, threshold)
                val saved = try {
                    container.journalStore.saveReport(WalkForwardRecord.from(result))
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _toast.value = "تست انجام شد ولی گزارش در گوشی ذخیره نشد؛ فایل قبلی دست‌نخورده ماند"
                    false
                }
                _walkForward.value = WalkForwardState.Done(result, interval, saved)
            } catch (e: Exception) {
                _walkForward.value = WalkForwardState.Failed(e.message ?: "خطا در دریافت داده واقعی")
            }
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

private fun com.aurum.edge.core.PaperAiReview.verdictFa(): String = when (verdict) {
    "WORTHY" -> "شرایط مناسب بوده"
    "RISKY" -> "پرریسک/مرزی بوده"
    "NOT_WORTHY" -> "شرایط کافی نبوده"
    else -> verdict
}
