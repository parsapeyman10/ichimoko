package com.aurum.edge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.engine.SignalEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A successful REST download is not proof that the market has a current bar. */
internal fun hasCurrentRestBar(bars: List<Candle>, interval: Interval, now: Long): Boolean {
    val last = bars.maxByOrNull { it.time } ?: return false
    return now - last.time in 0L..(interval.millis + 90_000L)
}

/** A live tick may arrive a few seconds after its source timestamp, even across a candle boundary. */
internal fun isCurrentIntervalTick(at: Long, interval: Interval, now: Long): Boolean =
    at in (now - 90_000L)..(now + 10_000L)

data class MarketState(
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val candles: List<Candle> = emptyList(),
    val lastPrice: Double? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    val feed: FeedStatus = FeedStatus(FeedMode.NO_KEY),
    val signal: Signal? = null,
    val showingCachedData: Boolean = false,
) {
    val closedCount: Int get() = candles.count { it.closed }
    val hasRealData: Boolean get() = candles.isNotEmpty()
}

/**
 * Owns the real-data pipeline: REST bootstrap + WebSocket ticks + disk cache + signal evaluation.
 *
 * Invariants:
 *  - every [Candle] in [MarketState.candles] came from the provider (or the provider cache on disk);
 *  - a failure never triggers generated data — it flips [FeedStatus] to OFFLINE;
 *  - the signal engine only ever sees closed bars.
 */
class MarketRepository(
    private val context: Context,
    private val client: TwelveDataClient,
    private val cache: CandleCache,
    private val settings: SettingsStore,
    private val journal: JournalStore,
    // Automatic, keyless real-price fallback (Swissquote/Gold-API). It is used when no Twelve
    // Data key is configured, and as a labelled live-tick fallback when the user's Twelve Data
    // plan/key does not provide a working WebSocket; Twelve REST candles still use the key.
    private val spotFallback: SpotFallbackClient = SpotFallbackClient(),
    private val publicHistory: PublicCandleHistoryClient = PublicCandleHistoryClient(),
) {
    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var streamJob: Job? = null
    private var bootstrapJob: Job? = null
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private val refreshMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var generation = 0L
    @Volatile private var streamEpoch = 0L
    private var activeKey: String? = null // never logged; compare to prevent needless service/UI restarts
    private var lastQuietReconnect = 0L
    private var cachedBars: MutableMap<Long, Candle> = mutableMapOf()
    private var lastCacheWrite = 0L

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    @Synchronized
    fun start() {
        val current = settings.read()
        // Entering the screen while the user-started service is already running must NOT
        // tear down a healthy socket, roll back its last tick, or duplicate REST requests.
        if (started && activeKey == current.apiKey && _state.value.symbol == current.symbol &&
            _state.value.interval == current.interval &&
            ((MarketHours.forexWeekendClosed() && _state.value.feed.mode == FeedMode.MARKET_CLOSED) ||
                // Both keyed and keyless modes keep a history-refresh poll plus the live/fallback
                // tick stream alive, so every tab sees the same continuously updated 3000-bar state.
                (pollJob?.isActive == true && streamJob?.isActive == true))) return
        stop()
        started = true
        activeKey = current.apiKey
        lastQuietReconnect = SystemClock.elapsedRealtime()
        val session = generation
        if (_state.value.symbol != current.symbol || _state.value.interval != current.interval) {
            cachedBars.clear() // never reuse a different instrument's bars or signal
            _state.value = MarketState(symbol = current.symbol, interval = current.interval)
        }
        val closed = MarketHours.forexWeekendClosed()
        _state.value = _state.value.copy(
            feed = FeedStatus(
                mode = if (closed) FeedMode.MARKET_CLOSED else FeedMode.CONNECTING,
                detail = if (closed) "تعطیلی معمول پایان هفته؛ قیمت تازه دریافت نمی‌شود" else "در حال دریافت…",
                // Without a key the free keyless mode uses public Yahoo history plus the same
                // Swissquote/Gold-API real-tick pipeline — it is not blocked on Twelve Data.
                provider = if (current.hasKey) "Twelve Data" else "Yahoo Finance عمومی + Swissquote/Gold-API",
            ),
            signal = null,
            showingCachedData = closed && _state.value.candles.isNotEmpty(),
        )
        loadCacheThenRefresh(session) // cached real bars are read-only even without the key
        if (!closed) startStream() // Twelve Data WS with a key; labelled fallback ticks if WS is unavailable
        if (!closed) startPolling() // Twelve REST or keyless public-history refresh keeps the 3000-bar window current
        registerNetworkCallback()
        startWatchdog(session) // local clock re-arms the feed at the next scheduled opening
    }

    @Synchronized
    fun stop() {
        started = false
        generation++ // delayed HTTP/old socket responses cannot publish after a restart
        streamEpoch++
        pollJob?.cancel(); pollJob = null
        streamJob?.cancel(); streamJob = null
        bootstrapJob?.cancel(); bootstrapJob = null
        watchdogJob?.cancel(); watchdogJob = null
        recoveryJob?.cancel(); recoveryJob = null
        networkCallback?.let { callback ->
            runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        lastNetwork = null
        activeKey = null
    }

    fun restart() {
        stop()
        cachedBars.clear()
        val current = settings.read()
        _state.value = MarketState(symbol = current.symbol, interval = current.interval)
        start()
    }

    fun setInterval(interval: Interval) {
        if (_state.value.interval == interval) return
        settings.update { it.copy(interval = interval) }
        restart()
    }

    fun refreshNow() {
        val s = scope ?: return
        s.launch { refresh() }
    }

    private fun loadCacheThenRefresh(session: Long) {
        val s = scope ?: return
        bootstrapJob = s.launch {
            val current = settings.read()
            val cached = cache.load(current.symbol, current.interval)
            if (!started || generation != session ||
                settings.read().symbol != current.symbol || settings.read().interval != current.interval) return@launch
            if (cached.isNotEmpty()) {
                cached.forEach { bar -> if (bar.time !in cachedBars) cachedBars[bar.time] = bar }
                evaluateAndPublish(showingCache = true)
            }
            refresh()
        }
    }

    private suspend fun refresh() = refreshMutex.withLock {
        val current = settings.read()
        val session = generation
        if (!started) return@withLock
        if (MarketHours.forexWeekendClosed()) {
            publishClosed()
            return@withLock // no REST requests on the scheduled weekend
        }
        if (!hasInternet()) {
            publishDelayed("شبکهٔ پیش‌فرض موقتاً تأیید نشد؛ اتصال دوباره بررسی می‌شود. تا دریافت جدید، قیمت قابل معامله نیست")
            return@withLock
        }
        if (!hasRecentStream() && !(_state.value.feed.mode == FeedMode.POLLING &&
                FeedLiveness.hasRecentReceipt(_state.value.feed))) _state.value = _state.value.copy(
            feed = _state.value.feed.copy(
                mode = FeedMode.CONNECTING,
                detail = if (current.hasKey) "دریافت حداقل ${HistoryPolicy.TARGET_CANDLES} کندل واقعی از Twelve Data…"
                else "دریافت حداقل ${HistoryPolicy.TARGET_CANDLES} کندل تاریخچهٔ رایگان…",
            ))
        try {
            var fetched: List<Candle>
            var historyProvider: String
            var staleDetail: String
            if (current.hasKey) {
                try {
                    fetched = client.fetchCandles(current.apiKey, current.symbol, current.interval,
                        outputSize = HistoryPolicy.TARGET_CANDLES)
                    if (fetched.size < HistoryPolicy.TARGET_CANDLES) {
                        throw DataFeedException("Twelve Data فقط ${fetched.size} کندل داد؛ حداقل ${HistoryPolicy.TARGET_CANDLES} کندل واقعی لازم است")
                    }
                    historyProvider = "Twelve Data"
                    staleDetail = "اتصال پاسخ داد اما آخرین کندل Twelve Data قدیمی است؛ شاید بازار بسته باشد. دریافت دوبارهٔ تاریخچه قیمت زنده/مجوز معامله نیست"
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (primary: Exception) {
                    val public = publicHistory.fetchCandles(current.symbol, current.interval,
                        minimumSize = HistoryPolicy.TARGET_CANDLES)
                    fetched = public.candles
                    historyProvider = "${public.provider} · پشتیبان تاریخچه"
                    staleDetail = "Twelve Data REST در دسترس نبود (${(primary.message ?: "خطای نامشخص").take(80)})؛ تاریخچهٔ عمومی پشتیبان پاسخ داد اما آخرین کندل آن باید با تیک زنده تأیید شود"
                }
            } else {
                val public = publicHistory.fetchCandles(current.symbol, current.interval,
                    minimumSize = HistoryPolicy.TARGET_CANDLES)
                fetched = public.candles
                historyProvider = public.provider
                staleDetail = "تاریخچهٔ رایگان پاسخ داد اما آخرین کندل آن قدیمی است؛ تیک زندهٔ جداگانه باید قیمت فعلی را تأیید کند"
            }
            if (!started || generation != session ||
                settings.read().symbol != current.symbol || settings.read().interval != current.interval ||
                (current.hasKey && settings.read().apiKey != current.apiKey) ||
                settings.read().hasKey != current.hasKey || _state.value.symbol != current.symbol) return@withLock
            if (MarketHours.forexWeekendClosed()) { publishClosed(); return@withLock }
            val receivedAt = System.currentTimeMillis()
            val streamRecent = _state.value.feed.mode == FeedMode.LIVE &&
                FeedLiveness.hasRecentReceipt(_state.value.feed, receivedAt)
            val historyCurrent = hasCurrentRestBar(fetched, current.interval, receivedAt)
            val periodStart = currentPeriodStart(current.interval)
            fetched.forEach { bar ->
                // The bar whose period is still open is kept as "forming" and excluded from the engine.
                // A recent WebSocket/fallback tick can be newer than this REST response; do not overwrite it.
                if (streamRecent && bar.time == periodStart) return@forEach
                cachedBars[bar.time] = bar.copy(closed = bar.time < periodStart)
            }
            trimCachedBars()
            settings.setLastSync(current.symbol, current.interval, receivedAt)
            persistCache()
            val enoughHistory = cachedBars.values.count { it.closed } >= HistoryPolicy.TARGET_CANDLES
            evaluateAndPublish(showingCache = (!historyCurrent && !streamRecent) || !enoughHistory)
            if (!enoughHistory) {
                publishDelayed("فقط ${cachedBars.values.count { it.closed }} کندل قبلی/بستهٔ واقعی ذخیره شد؛ حداقل ${HistoryPolicy.TARGET_CANDLES} لازم است و هیچ کندلی ساخته نمی‌شود")
            } else if (!historyCurrent && streamRecent) {
                _state.value = _state.value.copy(
                    feed = _state.value.feed.copy(detail = "$staleDetail؛ آخرین تیک زنده هنوز تازه است"),
                    showingCachedData = false,
                )
            } else if (!historyCurrent) {
                publishDelayed(staleDetail)
            } else {
                _state.value = _state.value.copy(
                    feed = FeedStatus(
                        mode = if (streamRecent) FeedMode.LIVE else FeedMode.POLLING,
                        detail = if (streamRecent) "" else "تاریخچه/کندل آنلاین تازه است؛ برای تیک لحظه‌ای، کانال زنده جداگانه بررسی می‌شود",
                        lastSuccessAt = if (streamRecent) _state.value.feed.lastSuccessAt else receivedAt,
                        provider = if (streamRecent) _state.value.feed.provider else historyProvider,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataFeedException) {
            if (started && generation == session) reportRestFailure(e.message ?: "دادهٔ بازار از منبع دریافت نشد")
        } catch (_: Exception) {
            if (started && generation == session) reportRestFailure("دریافت یا اعتبارسنجی دادهٔ واقعی با خطا روبه‌رو شد")
        }
    }

    private fun hasRecentStream(now: Long = System.currentTimeMillis()): Boolean =
        _state.value.feed.mode == FeedMode.LIVE && FeedLiveness.hasRecentReceipt(_state.value.feed, now)

    private fun reportRestFailure(detail: String) {
        if (MarketHours.forexWeekendClosed()) { publishClosed(); return }
        val current = _state.value
        if (current.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            !current.showingCachedData && FeedLiveness.hasRecentReceipt(current.feed)) {
            _state.value = current.copy(feed = current.feed.copy(detail =
                "$detail؛ آخرین دریافت هنوز زمان‌دار است، نه شاهد درخواست جدید"))
        } else publishDelayed(detail)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch {
            while (isActive) {
                delay(if (settings.read().hasKey) POLL_INTERVAL_MS else FREE_HISTORY_POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    @Synchronized
    private fun startStream() {
        if (!started) return
        if (MarketHours.forexWeekendClosed()) { publishClosed(); return }
        val epoch = ++streamEpoch
        streamJob?.cancel()
        val session = generation
        streamJob = scope?.launch {
            var backoff = 2_000L
            while (isActive && started && generation == session && streamEpoch == epoch) {
                val current = settings.read()
                if (MarketHours.forexWeekendClosed()) { publishClosed(); delay(60_000L); continue }
                if (!hasInternet()) {
                    publishDelayed(if (current.hasKey)
                        "شبکه موقتاً در دسترس نیست؛ WebSocket بعد از بازگشت شبکه دوباره وصل می‌شود"
                    else "شبکه موقتاً در دسترس نیست؛ فید رایگان بعد از بازگشت شبکه دوباره وصل می‌شود")
                    delay(RECONNECT_WHEN_OFFLINE_MS); continue
                }
                fun stillCurrent(active: com.aurum.edge.core.AppSettings): Boolean =
                    started && generation == session && streamEpoch == epoch &&
                        active.symbol == current.symbol && active.interval == current.interval
                suspend fun collectSpotFallback(provider: String, maxMillis: Long? = null) {
                    val collectBlock: suspend () -> Unit = {
                        spotFallback.streamQuotes(current.symbol).collect { tick ->
                            val active = settings.read()
                            if (!stillCurrent(active) || active.hasKey != current.hasKey ||
                                (current.hasKey && active.apiKey != current.apiKey)) return@collect
                            backoff = 2_000L
                            onTick(tick, provider)
                        }
                    }
                    if (maxMillis == null) collectBlock()
                    else withTimeoutOrNull(maxMillis) { collectBlock() }
                }
                try {
                    if (current.hasKey) {
                        var fallbackStarted = false
                        try {
                            client.streamPrice(current.apiKey, current.symbol).collect { tick ->
                                // Ignore an already queued tick when the user changed markets/intervals.
                                val active = settings.read()
                                if (!stillCurrent(active) || active.apiKey != current.apiKey) return@collect
                                backoff = 2_000L
                                onTick(tick, "Twelve Data WebSocket")
                            }
                        } catch (e: DataFeedException) {
                            if (started && generation == session && streamEpoch == epoch) {
                                publishStreamUnavailable((e.message ?: "WebSocket Twelve Data در دسترس نیست") +
                                    "؛ تا تلاش بعدی از فید زندهٔ جایگزین استفاده می‌شود")
                                fallbackStarted = true
                                collectSpotFallback("فید زندهٔ جایگزین (Swissquote/Gold-API؛ WebSocket Twelve در دسترس نیست)",
                                    TWELVE_WS_FALLBACK_WINDOW_MS)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (started && generation == session && streamEpoch == epoch) {
                                publishStreamUnavailable("اتصال WebSocket Twelve Data قطع شد؛ تا تلاش بعدی از فید زندهٔ جایگزین استفاده می‌شود")
                                fallbackStarted = true
                                collectSpotFallback("فید زندهٔ جایگزین (Swissquote/Gold-API؛ WebSocket Twelve در دسترس نیست)",
                                    TWELVE_WS_FALLBACK_WINDOW_MS)
                            }
                        }
                        if (!fallbackStarted && started && generation == session && streamEpoch == epoch)
                            publishStreamUnavailable("جریان WebSocket Twelve Data قطع شد — تلاش مجدد")
                    } else {
                        collectSpotFallback("فید رایگان خودکار (Swissquote/Gold-API)")
                        if (started && generation == session && streamEpoch == epoch)
                            publishStreamUnavailable("جریان فید رایگان قطع شد — تلاش مجدد")
                    }
                } catch (e: CancellationException) {
                    throw e // cancelling an old market job must not relabel the new market offline
                } catch (e: DataFeedException) {
                    if (started && generation == session && streamEpoch == epoch)
                        publishStreamUnavailable(e.message
                            ?: (if (current.hasKey) "قطع جریان WebSocket" else "قطع جریان فید رایگان"))
                } catch (_: Exception) {
                    if (started && generation == session && streamEpoch == epoch)
                        publishStreamUnavailable(if (current.hasKey)
                            "اتصال WebSocket قطع شد؛ اینترنت/دسترسی فید را بررسی کنید"
                        else "اتصال فید رایگان قطع شد؛ اینترنت را بررسی کنید")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** A locked screen can leave a TCP socket open without a new price. Silence is not LIVE.
     * The watchdog downgrades the quote immediately, then retries with a bounded cadence.
     * It never wakes the CPU permanently or bypasses Android's foreground-service time limit.
     */
    private fun startWatchdog(session: Long) {
        val startedAt = System.currentTimeMillis()
        watchdogJob = scope?.launch {
            while (isActive && started && generation == session) {
                delay(20_000L)
                if (!started || generation != session) break
                val now = System.currentTimeMillis()
                if (MarketHours.forexWeekendClosed(now)) {
                    if (_state.value.feed.mode != FeedMode.MARKET_CLOSED || streamJob?.isActive == true) {
                        synchronized(this@MarketRepository) { ++streamEpoch; streamJob?.cancel(); streamJob = null }
                        recoveryJob?.cancel()
                        publishClosed()
                    }
                    continue // no network check, HTTP request or reconnect on a known closed weekend
                }
                if (_state.value.feed.mode == FeedMode.MARKET_CLOSED) {
                    _state.value = _state.value.copy(feed = FeedStatus(
                        FeedMode.CONNECTING, "برنامهٔ معمول بازگشایی شد؛ منتظر تیک/کندل معتبر هستیم",
                        provider = if (settings.read().hasKey) "Twelve Data" else "Yahoo Finance عمومی + Swissquote/Gold-API"),
                        signal = null)
                    startStream()
                    refreshNow() // keyed Twelve or keyless public-history backfill
                    continue
                }
                val feed = _state.value.feed
                if (feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
                    !FeedLiveness.hasRecentReceipt(feed, now)) {
                    publishDelayed("تیک/کندل تازه نرسیده؛ شبکه، سهمیه یا ساعت بازار را بررسی کنید. اتصال دوباره بررسی می‌شود")
                }
                if (!hasInternet()) {
                    if (now - (feed.lastSuccessAt ?: startedAt) > 120_000L &&
                        _state.value.feed.mode != FeedMode.OFFLINE) {
                        publishOffline("شبکهٔ پیش‌فرض بیش از دو دقیقه در دسترس نیست؛ فقط دادهٔ قبلی نمایش داده می‌شود")
                    }
                    continue
                }
                if (_state.value.feed.mode in setOf(FeedMode.DELAYED, FeedMode.OFFLINE, FeedMode.CONNECTING) &&
                    SystemClock.elapsedRealtime() - lastQuietReconnect >= QUIET_RECONNECT_MS) {
                    lastQuietReconnect = SystemClock.elapsedRealtime()
                    startStream() // a silent open socket is replaced, not counted as a price tick
                    refreshNow() // REST may recover independently; its timestamp is checked
                }
            }
        }
    }

    /** Retry promptly on Wi-Fi/cellular handoff rather than waiting for the next REST timer. */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lastNetwork = cm.activeNetwork
        val session = generation
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network == lastNetwork) return // initial callback, no needless restart
                recoveryJob?.cancel()
                recoveryJob = scope?.launch {
                    delay(750L) // a default network may be announced before it can actually route
                    if (!started || generation != session || MarketHours.forexWeekendClosed()) return@launch
                    if (cm.activeNetwork != network || !hasInternet()) return@launch
                    lastNetwork = network
                    publishDelayed("شبکه تغییر کرد؛ تیک قدیمی قابل معامله نیست. اتصال WebSocket/REST بازیابی می‌شود")
                    lastQuietReconnect = SystemClock.elapsedRealtime()
                    startStream()
                    refreshNow()
                }
            }

            override fun onLost(network: Network) {
                if (network != lastNetwork || cm.activeNetwork != null) return
                recoveryJob?.cancel()
                recoveryJob = scope?.launch {
                    delay(1_500L) // allow Android to switch to a new default network first
                    if (started && generation == session && !MarketHours.forexWeekendClosed() && !hasInternet()) {
                        publishDelayed("شبکه در حال تغییر/قطع است؛ تا رسیدن دادهٔ تازه آنلاین نیست")
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private suspend fun onTick(tick: com.aurum.edge.core.PriceTick, provider: String = "Twelve Data") {
        val current = settings.read()
        val now = System.currentTimeMillis()
        val price = tick.price
        val at = tick.at
        if (MarketHours.forexWeekendClosed(now)) { publishClosed(); return }
        if (!isCurrentIntervalTick(at, current.interval, now)) return
        val wallPeriodStart = now - now % current.interval.millis
        val periodStart = at - at % current.interval.millis
        val existing = cachedBars[periodStart]
        val bar = existing?.copy(
            high = maxOf(existing.high, price),
            low = minOf(existing.low, price),
            close = price,
            closed = periodStart < wallPeriodStart,
        ) ?: Candle(
            time = periodStart,
            open = price,
            high = price,
            low = price,
            close = price,
            volume = 0.0,
            closed = periodStart < wallPeriodStart,
        )
        cachedBars[periodStart] = bar
        trimCachedBars()

        // Any older bar still flagged as forming is now finished → keep it as a closed, real bar.
        cachedBars.keys.filter { it < periodStart }.forEach { key ->
            val value = cachedBars[key]
            if (value != null && !value.closed) cachedBars[key] = value.copy(closed = true)
        }

        lastQuietReconnect = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            lastPrice = price,
            bid = tick.bid,
            ask = tick.ask,
            feed = FeedStatus(FeedMode.LIVE, "", System.currentTimeMillis(), provider = provider),
            showingCachedData = false,
        )
        publishCandles()

        val writeAt = System.currentTimeMillis()
        if (writeAt - lastCacheWrite > 60_000L) {
            lastCacheWrite = writeAt
            persistCache()
        }
        // settle paper trades against real prices as they arrive
        journal.settle(Candle(time = at, open = price, high = price, low = price, close = price), current.symbol, at)
    }

    private fun publishStreamUnavailable(detail: String) {
        if (MarketHours.forexWeekendClosed()) { publishClosed(); return }
        val current = _state.value
        val now = System.currentTimeMillis()
        if (current.feed.mode == FeedMode.POLLING &&
            FeedLiveness.hasRecentReceipt(current.feed, now) &&
            hasCurrentRestBar(current.candles, current.interval, now)) {
            _state.value = current.copy(feed = current.feed.copy(detail =
                "$detail؛ کندل/تاریخچهٔ دوره‌ای است، نه تیک زنده"))
        } else publishDelayed(detail)
    }

    private fun publishClosed() {
        val current = _state.value
        if (current.feed.mode == FeedMode.MARKET_CLOSED) return
        _state.value = current.copy(
            feed = current.feed.copy(mode = FeedMode.MARKET_CLOSED,
                detail = "تعطیلی معمول پایان هفته به وقت نیویورک؛ روزهای تعطیل دیگر/ساعت بروکر جداگانه تأیید نشده‌اند"),
            showingCachedData = current.candles.isNotEmpty(), signal = null,
        )
    }

    private fun publishDelayed(detail: String) {
        val current = _state.value
        val now = System.currentTimeMillis()
        val displayFeed = FeedLiveness.display(current.feed, now)
        val recentReceipt = !current.showingCachedData && current.lastPrice != null &&
            displayFeed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
            FeedLiveness.hasRecentReceipt(displayFeed, now)
        if (recentReceipt) {
            // Do not relabel a one-second Swissquote/Gold-API fallback tick (or a fresh Twelve
            // tick/candle) as "delayed" merely because another channel just failed/reconnected.
            // Even if an older path had already set DELAYED with a fresh receipt, normalize it.
            _state.value = current.copy(
                feed = displayFeed.copy(detail = "$detail؛ آخرین تیک/کندل دریافتی هنوز تازه است"),
                showingCachedData = false,
            )
            return
        }
        _state.value = current.copy(
            feed = current.feed.copy(mode = FeedMode.DELAYED, detail = detail),
            showingCachedData = current.candles.isNotEmpty(),
            signal = null, // a disconnected stream never authorizes a new paper entry
        )
    }

    private fun publishOffline(detail: String) {
        _state.value = _state.value.copy(
            feed = _state.value.feed.copy(mode = FeedMode.OFFLINE, detail = detail),
            showingCachedData = _state.value.candles.isNotEmpty(),
            signal = null, // never advertise an old signal as live during an outage
        )
    }

    private fun trimCachedBars() {
        if (cachedBars.size <= HistoryPolicy.MAX_CACHED_CANDLES) return
        val keep = cachedBars.values.sortedBy { it.time }.takeLast(HistoryPolicy.MAX_CACHED_CANDLES)
        cachedBars = keep.associateBy { it.time }.toMutableMap()
    }

    private suspend fun persistCache() {
        val current = settings.read()
        if (_state.value.symbol != current.symbol || _state.value.interval != current.interval) return
        // Do not turn an unfinished live bar into a closed historical candle on restart.
        val bars = cachedBars.values.sortedBy { it.time }
        if (bars.isNotEmpty()) cache.save(current.symbol, current.interval, bars)
    }

    private suspend fun evaluateAndPublish(showingCache: Boolean) {
        publishCandles(showingCache)
    }

    private suspend fun publishCandles(showingCache: Boolean = _state.value.showingCachedData) {
        val current = settings.read()
        val bars = cachedBars.values.sortedBy { it.time }
        val lastPrice = bars.lastOrNull()?.close ?: _state.value.lastPrice
        if (bars.isEmpty()) {
            _state.value = _state.value.copy(candles = emptyList(), lastPrice = null, bid = null, ask = null, showingCachedData = false)
            return
        }
        val signal = if (showingCache) null else withContext(Dispatchers.Default) {
            runCatching {
                SignalEngine.evaluate(bars, current.interval, current.minConfidence, current.spreadPrice, current.signalProfile)
            }.getOrNull()
        }
        _state.value = _state.value.copy(
            candles = bars,
            lastPrice = lastPrice,
            signal = signal,
            showingCachedData = showingCache,
        )
        if (!showingCache) bars.filter { it.closed }.lastOrNull()?.let { bar ->
            journal.settle(bar, current.symbol, bar.time + current.interval.millis)
        }
    }

    private fun currentPeriodStart(interval: Interval): Long {
        val now = System.currentTimeMillis()
        return now - (now % interval.millis)
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun lastSyncAt(): Long? {
        val current = settings.read()
        return settings.lastSync(current.symbol, current.interval)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val FREE_HISTORY_POLL_INTERVAL_MS = 5 * 60_000L
        private const val RECONNECT_WHEN_OFFLINE_MS = 15_000L
        private const val QUIET_RECONNECT_MS = 5 * 60_000L
        private const val TWELVE_WS_FALLBACK_WINDOW_MS = 2 * 60_000L
    }
}
