package com.aurum.edge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.Candle
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

/** A successful REST download is not proof that the market has a current bar. */
internal fun hasCurrentRestBar(bars: List<Candle>, interval: Interval, now: Long): Boolean {
    val last = bars.maxByOrNull { it.time } ?: return false
    return now - last.time in 0L..(interval.millis + 90_000L)
}

/** Never place a delayed provider tick into a bar that had not opened at event time. */
internal fun isCurrentIntervalTick(at: Long, interval: Interval, now: Long): Boolean =
    at in (now - now % interval.millis)..now && now - at <= 90_000L

data class MarketState(
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val candles: List<Candle> = emptyList(),
    val lastPrice: Double? = null,
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
        if (current.workspaceId != "forex") return
        // Entering the screen while the user-started service is already running must NOT
        // tear down a healthy socket, roll back its last tick, or duplicate REST requests.
        if (started && activeKey == current.apiKey && _state.value.symbol == current.symbol &&
            _state.value.interval == current.interval &&
            (!current.hasKey || (MarketHours.forexWeekendClosed() &&
                _state.value.feed.mode == FeedMode.MARKET_CLOSED) ||
                pollJob?.isActive == true && streamJob?.isActive == true)) return
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
            feed = FeedStatus(when { closed -> FeedMode.MARKET_CLOSED; current.hasKey -> FeedMode.CONNECTING
                else -> FeedMode.NO_KEY },
                when { closed -> "تعطیلی معمول پایان هفته؛ قیمت تازه دریافت نمی‌شود"
                    current.hasKey -> "در حال دریافت…" else -> "کلید Twelve Data وارد نشده است" }),
            signal = null,
            showingCachedData = closed && _state.value.candles.isNotEmpty(),
        )
        loadCacheThenRefresh(session) // cached real bars are read-only even without the key
        if (current.hasKey) {
            if (!closed) startStream()
            startPolling() // timer checks local hours and does NOT request REST while closed
            registerNetworkCallback()
        }
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
            if (!started || generation != session || settings.read().workspaceId != "forex" ||
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
        if (!started || current.workspaceId != "forex") return@withLock
        if (MarketHours.forexWeekendClosed()) {
            publishClosed()
            return@withLock // no REST requests on the scheduled weekend
        }
        if (!current.hasKey) {
            _state.value = _state.value.copy(feed = FeedStatus(FeedMode.NO_KEY, "کلید Twelve Data وارد نشده است"))
            return@withLock
        }
        if (!hasInternet()) {
            publishDelayed("شبکهٔ پیش‌فرض موقتاً تأیید نشد؛ اتصال دوباره بررسی می‌شود. تا دریافت جدید، قیمت قابل معامله نیست")
            return@withLock
        }
        if (!hasRecentStream() && !(_state.value.feed.mode == FeedMode.POLLING &&
                FeedLiveness.hasRecentReceipt(_state.value.feed))) _state.value = _state.value.copy(
            feed = _state.value.feed.copy(mode = FeedMode.CONNECTING, detail = "دریافت کندل‌های واقعی…"))
        try {
            val fetched = client.fetchCandles(current.apiKey, current.symbol, current.interval, outputSize = 1500)
            if (!started || generation != session || settings.read().workspaceId != "forex" ||
                settings.read().symbol != current.symbol || settings.read().interval != current.interval ||
                settings.read().apiKey != current.apiKey || _state.value.symbol != current.symbol) return@withLock
            if (MarketHours.forexWeekendClosed()) { publishClosed(); return@withLock }
            val receivedAt = System.currentTimeMillis()
            val streamRecent = _state.value.feed.mode == FeedMode.LIVE &&
                _state.value.feed.lastSuccessAt?.let { receivedAt - it in 0L..90_000L } == true
            val restCurrent = hasCurrentRestBar(fetched, current.interval, receivedAt)
            if (!restCurrent && streamRecent) return@withLock // stale REST must not roll back a recent WS tick
            val periodStart = currentPeriodStart(current.interval)
            fetched.forEach { bar ->
                // The bar whose period is still open is kept as "forming" and excluded from the engine.
                // A recent WebSocket tick can be newer than this REST response; do not overwrite it.
                if (streamRecent && bar.time == periodStart) return@forEach
                cachedBars[bar.time] = bar.copy(closed = bar.time < periodStart)
            }
            settings.setLastSync(current.symbol, current.interval, receivedAt)
            persistCache()
            evaluateAndPublish(showingCache = !restCurrent)
            if (!restCurrent) {
                publishDelayed("اتصال پاسخ داد اما آخرین کندل Twelve Data قدیمی است؛ شاید بازار بسته باشد. دریافت دوبارهٔ تاریخچه قیمت زنده/مجوز معامله نیست")
            } else {
                _state.value = _state.value.copy(
                    feed = FeedStatus(
                        mode = if (streamRecent) FeedMode.LIVE else FeedMode.POLLING,
                        detail = if (streamRecent) "" else "REST: زمان دریافت تازه است؛ زمان آخرین معاملهٔ درون کندل جداگانه منتشر نشده",
                        lastSuccessAt = if (streamRecent) _state.value.feed.lastSuccessAt else receivedAt,
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
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    @Synchronized
    private fun startStream() {
        if (!started || settings.read().workspaceId != "forex") return
        if (MarketHours.forexWeekendClosed()) { publishClosed(); return }
        val epoch = ++streamEpoch
        streamJob?.cancel()
        val session = generation
        streamJob = scope?.launch {
            var backoff = 2_000L
            while (isActive && started && generation == session && streamEpoch == epoch) {
                val current = settings.read()
                if (current.workspaceId != "forex") break
                if (MarketHours.forexWeekendClosed()) { publishClosed(); delay(60_000L); continue }
                if (!current.hasKey) {
                    _state.value = _state.value.copy(feed = FeedStatus(FeedMode.NO_KEY, "کلید Twelve Data وارد نشده است"))
                    delay(10_000); continue
                }
                if (!hasInternet()) {
                    publishDelayed("شبکه موقتاً در دسترس نیست؛ WebSocket بعد از بازگشت شبکه دوباره وصل می‌شود")
                    delay(RECONNECT_WHEN_OFFLINE_MS); continue
                }
                try {
                    client.streamPrice(current.apiKey, current.symbol).collect { tick ->
                        // Ignore an already queued tick when the user changed markets/intervals.
                        val active = settings.read()
                        if (!started || generation != session || streamEpoch != epoch ||
                            active.workspaceId != "forex" || active.apiKey != current.apiKey || active.symbol != current.symbol ||
                            active.interval != current.interval) return@collect
                        backoff = 2_000L
                        onTick(tick.price, tick.at)
                    }
                    if (started && generation == session && streamEpoch == epoch)
                        publishStreamUnavailable("جریان WebSocket قطع شد — تلاش مجدد")
                } catch (e: CancellationException) {
                    throw e // cancelling an old market job must not relabel the new market offline
                } catch (e: DataFeedException) {
                    if (started && generation == session && streamEpoch == epoch)
                        publishStreamUnavailable(e.message ?: "قطع جریان WebSocket")
                } catch (_: Exception) {
                    if (started && generation == session && streamEpoch == epoch)
                        publishStreamUnavailable("اتصال WebSocket قطع شد؛ اینترنت/دسترسی فید را بررسی کنید")
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
                if (!started || generation != session || settings.read().workspaceId != "forex") break
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
                        if (settings.read().hasKey) FeedMode.CONNECTING else FeedMode.NO_KEY,
                        "برنامهٔ معمول بازگشایی شد؛ منتظر تیک/کندل معتبر هستیم"), signal = null)
                    if (settings.read().hasKey) { startStream(); refreshNow() }
                    continue
                }
                if (!settings.read().hasKey) continue
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
                    if (!started || generation != session || settings.read().workspaceId != "forex" ||
                        MarketHours.forexWeekendClosed()) return@launch
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

    private suspend fun onTick(price: Double, at: Long) {
        val current = settings.read()
        val now = System.currentTimeMillis()
        if (MarketHours.forexWeekendClosed(now)) { publishClosed(); return }
        if (!isCurrentIntervalTick(at, current.interval, now)) return
        val periodStart = now - now % current.interval.millis
        val existing = cachedBars[periodStart]
        val bar = existing?.copy(
            high = maxOf(existing.high, price),
            low = minOf(existing.low, price),
            close = price,
            closed = false,
        ) ?: Candle(
            time = periodStart,
            open = price,
            high = price,
            low = price,
            close = price,
            volume = 0.0,
            closed = false,
        )
        cachedBars[periodStart] = bar

        // Any older bar still flagged as forming is now finished → keep it as a closed, real bar.
        cachedBars.keys.filter { it < periodStart }.forEach { key ->
            val value = cachedBars[key]
            if (value != null && !value.closed) cachedBars[key] = value.copy(closed = true)
        }

        lastQuietReconnect = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            lastPrice = price,
            feed = FeedStatus(FeedMode.LIVE, "", System.currentTimeMillis()),
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
            current.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true &&
            hasCurrentRestBar(current.candles, current.interval, now)) {
            _state.value = current.copy(feed = current.feed.copy(detail =
                "$detail؛ کندل REST دوره‌ای است، نه تیک زنده"))
        } else publishDelayed(detail)
    }

    private fun publishClosed() {
        val current = _state.value
        if (current.feed.mode == FeedMode.MARKET_CLOSED) return
        _state.value = current.copy(
            feed = FeedStatus(FeedMode.MARKET_CLOSED,
                "تعطیلی معمول پایان هفته به وقت نیویورک؛ روزهای تعطیل دیگر/ساعت بروکر جداگانه تأیید نشده‌اند",
                current.feed.lastSuccessAt),
            showingCachedData = current.candles.isNotEmpty(), signal = null,
        )
    }

    private fun publishDelayed(detail: String) {
        _state.value = _state.value.copy(
            feed = _state.value.feed.copy(mode = FeedMode.DELAYED, detail = detail),
            showingCachedData = _state.value.candles.isNotEmpty(),
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
            _state.value = _state.value.copy(candles = emptyList(), lastPrice = null, showingCachedData = false)
            return
        }
        val signal = if (showingCache) null else withContext(Dispatchers.Default) {
            runCatching {
                SignalEngine.evaluate(bars, current.interval, current.minConfidence, current.spreadPrice)
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
        private const val RECONNECT_WHEN_OFFLINE_MS = 15_000L
        private const val QUIET_RECONNECT_MS = 5 * 60_000L
    }
}
