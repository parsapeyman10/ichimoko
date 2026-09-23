package com.aurum.edge.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurum.edge.core.AppContainer
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.FeedMode
import com.aurum.edge.data.SourceComparison
import com.aurum.edge.data.VerificationStatus
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.PaperTicket
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
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
    data class Done(val result: Backtester.WalkForward, val interval: Interval) : WalkForwardState
    data class Failed(val message: String) : WalkForwardState
}

data class WatchHistory(
    val symbolId: String = "",
    val sourceId: String = "",
    val entries: List<Quote> = emptyList(),
    val total: Long = 0L,
    val loading: Boolean = false,
)

class AurumViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
    val watchSettings: StateFlow<Map<String, WatchSelection>> = container.watchSettings.selections
    val watch: StateFlow<WatchState> = container.watch.state
    val news = container.news.state
    val crypto = container.crypto.state
    private val _watchHistory = MutableStateFlow(WatchHistory())
    val watchHistory: StateFlow<WatchHistory> = _watchHistory.asStateFlow()
    val market = container.verifiedMarket
    val trades: StateFlow<List<PaperTrade>> = container.journalStore.trades
    val journalError: StateFlow<String?> = container.journalStore.loadError
    val autoPaperStatus: StateFlow<String> = container.autoPaperTrader.status

    /** Walk-forward runs made on this device, kept so the numbers can be re-checked later. */
    val reports: StateFlow<List<WalkForwardRecord>> = container.journalStore.reports

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

    init {
        viewModelScope.launch {
            runCatching { container.journalStore.load() }.onFailure {
                _toast.value = "ژورنال خوانده نشد؛ فایل قبلی برای بازیابی نگه داشته شد"
            }
            container.journalStore.loadReports()
            _stats.value = container.journalStore.stats()
            container.market.start()
            container.watch.loadCached()
        }
        viewModelScope.launch {
            // Automatic SL/TP settlement happens in MarketRepository, not in this ViewModel.
            // Keep totals in sync with the journal flow even while a screen is not open.
            trades.collect { _stats.value = container.journalStore.stats() }
        }
        viewModelScope.launch {
            while (isActive) {
                if (settings.value.pauseOnNews && settings.value.newsBaseUrl.isNotBlank()) container.news.refreshNow()
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

    fun refreshNow() = container.market.refreshNow()

    fun refreshWatch() = container.watch.refreshNow()

    fun refreshNews() = container.news.refreshNow()

    fun refreshCrypto() = container.crypto.refreshNow()

    fun saveNewsBaseUrl(value: String) {
        val url = value.trim().trimEnd('/')
        if (url.isNotBlank() && NewsRepository.newsUrl(url) == null) {
            _toast.value = "آدرس HTTPS سرور خبر بدون مسیر و کلید وارد کنید"
            return
        }
        container.settingsStore.update { it.copy(newsBaseUrl = url) }
        container.news.resetAndRefresh()
        container.crypto.resetAndRefresh()
        _toast.value = "آدرس سرور خبر و غربالگر ذخیره شد"
    }

    fun setPauseOnNews(enabled: Boolean) {
        container.settingsStore.update { it.copy(pauseOnNews = enabled) }
        if (enabled) container.news.refreshNow()
    }

    fun selectWatchSource(symbolId: String, sourceId: String, enabled: Boolean) {
        container.watchSettings.selectSource(symbolId, sourceId, enabled)
        if (enabled) container.watch.refreshNow()
    }

    fun setWatchPreferred(symbolId: String, sourceId: String) = container.watchSettings.setPreferred(symbolId, sourceId)

    fun watchKeyOverride(symbolId: String): String = container.watchSettings.keyOverride(symbolId)

    fun setWatchKeyOverride(symbolId: String, key: String) {
        container.watchSettings.setKeyOverride(symbolId, key)
        container.watch.refreshNow()
        _toast.value = "کلید خواندنی این نماد ذخیره شد"
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

    fun setInterval(interval: Interval) = container.market.setInterval(interval)

    fun saveApiKey(key: String) {
        container.settingsStore.update { it.copy(apiKey = key.trim()) }
        container.market.restart()
    }

    fun saveSymbol(symbol: String) {
        container.settingsStore.update { it.copy(symbol = symbol.trim().ifBlank { "XAU/USD" }) }
        container.market.restart()
    }

    fun saveRiskPercent(value: Double) = container.settingsStore.update { it.copy(riskPercent = value.coerceIn(0.1, 5.0)) }

    fun saveBalance(value: Double) = container.settingsStore.update { it.copy(accountBalance = value.coerceAtLeast(10.0)) }

    fun saveMinConfidence(value: Double) = container.settingsStore.update { it.copy(minConfidence = value.coerceIn(72.0, 95.0)) }

    /** Cost assumptions are the user's responsibility; they are echoed in every report. */
    fun saveSpread(value: Double) = container.settingsStore.update { it.copy(spreadPrice = value.coerceIn(0.0, 5.0)) }

    fun saveCommission(value: Double) = container.settingsStore.update { it.copy(commissionPerOz = value.coerceIn(0.0, 5.0)) }

    fun setNotifyOnSignal(enabled: Boolean) = container.settingsStore.update { it.copy(notifyOnSignal = enabled) }

    fun setMonitorFlag(enabled: Boolean) {
        container.settingsStore.update { it.copy(backgroundMonitor = enabled,
            autoPaperTrading = if (enabled) it.autoPaperTrading else false) }
        if (!enabled) _toast.value = "پایش پس‌زمینه روشن نشد؛ معاملهٔ خودکار کاغذی خاموش ماند"
    }

    fun setAutoPaperTrading(enabled: Boolean) {
        if (enabled && (!settings.value.backgroundMonitor || settings.value.newsBaseUrl.isBlank())) {
            _toast.value = "برای خودکار کاغذی، پایش و آدرس HTTPS سرور خبر را فعال کنید"
            return
        }
        container.settingsStore.update { it.copy(autoPaperTrading = enabled) }
        if (enabled) container.news.refreshNow()
        _toast.value = if (enabled) "خودکار کاغذی روشن است؛ بدون مدل AI و ۹/۹ معتبر هیچ ورودی ثبت نمی‌شود"
            else "معاملهٔ خودکار کاغذی خاموش شد"
    }

    private var paperOpening = false

    /** A price received recently is still not an exchange fill; only a paper ticket can use it. */
    private fun freshPaperQuote(current: MarketState): String? {
        val now = System.currentTimeMillis()
        val received = current.feed.lastSuccessAt
        val lastBar = current.candles.lastOrNull()?.time
        return when {
            current.symbol != settings.value.symbol || current.interval != settings.value.interval ->
                "نماد یا بازهٔ قیمت با تنظیمات فعلی فرق دارد"
            current.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING) || current.showingCachedData ->
                "قیمت زنده نیست؛ ورود/خروج روی کش ممنوع است"
            current.lastPrice?.let { it.isFinite() && it > 0.0 } != true -> "قیمت معتبر موجود نیست"
            received == null || now - received !in 0L..90_000L -> "آخرین دریافت قیمت قدیمی است"
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
                require(NewsConfluence.alignment(current.symbol, signal.action, news.value).status ==
                    com.aurum.edge.core.ConfluenceStatus.CONFIRMED) {
                    "شرط نهم خبر AI دیگر معتبر نیست؛ ورود سیگنالی متوقف شد"
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
                val newsRecord = if (manual) null else {
                    val latestNews = news.value
                    require(NewsConfluence.alignment(current.symbol, signal.action, latestNews).status ==
                        com.aurum.edge.core.ConfluenceStatus.CONFIRMED) { "خبر AI در لحظهٔ ثبت قدیمی شد" }
                    NewsConfluence.record(latestNews) ?: error("شواهد خبر قابل ذخیره نیست")
                }
                val trade = container.journalStore.open(
                    signal = signal, symbol = current.symbol, price = price,
                    balance = s.accountBalance, riskPercent = s.riskPercent,
                    mtf = if (manual) null else _mtf.value?.let { MtfSnapshotRecord.from(it) },
                    manual = manual,
                    newsEvidence = newsRecord,
                )
                _stats.value = container.journalStore.stats()
                _toast.value = "فقط کاغذی: ${if (trade.action == SignalAction.BUY) "لانگ" else "شورت"} ${trade.symbol} · ${String.format("%.6f", trade.positionOz)} ${trade.unit}"
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
            _learn.value = LearnState.Loading("دانلود $bars کندل واقعی ${interval.label} از Twelve Data…")
            try {
                val result = container.runBacktest(interval, bars, balance, risk, spread, commission, threshold)
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

    /** Imported MT history is research-only: never written to the live chart/candle cache. */
    fun importMetaTrader(
        uri: Uri?, link: String?, symbol: String, interval: Interval, timezone: String,
        balance: Double, risk: Double, spread: Double, commission: Double, threshold: Double,
    ) {
        if (!symbol.matches(Regex("[A-Za-z0-9/_-]{3,30}"))) {
            _learn.value = LearnState.Failed("نام نماد وارداتی معتبر نیست")
            return
        }
        viewModelScope.launch {
            _learn.value = LearnState.Loading("خواندن CSV متاتریدر برای پژوهش؛ منشأ فایل تأیید نشده است…")
            try {
                val csv = when {
                    uri != null -> container.metaTraderImporter.fromFile(uri)
                    !link.isNullOrBlank() -> container.metaTraderImporter.fromHttps(link)
                    else -> throw IllegalArgumentException("فایل یا لینک CSV را انتخاب کنید")
                }
                val result = container.runImportedBacktest(csv, symbol, interval, timezone,
                    balance, risk.coerceIn(0.1, 5.0), spread, commission, threshold)
                _learn.value = LearnState.Done(result, interval)
            } catch (e: Exception) {
                _learn.value = LearnState.Failed(e.message ?: "فایل/لینک متاتریدر قابل تحلیل نیست")
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
            _walkForward.value = WalkForwardState.Loading("دانلود $bars کندل واقعی ${interval.label} و تقسیم به داخل/خارج نمونه…")
            try {
                val result = container.runWalkForward(interval, bars, balance, risk, spread, commission, threshold)
                container.journalStore.saveReport(WalkForwardRecord.from(result))
                _walkForward.value = WalkForwardState.Done(result, interval)
            } catch (e: Exception) {
                _walkForward.value = WalkForwardState.Failed(e.message ?: "خطا در دریافت داده واقعی")
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
