package com.aurum.edge.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
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
    private var cachedBars: MutableMap<Long, Candle> = mutableMapOf()
    private var lastCacheWrite = 0L

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    fun start() {
        stop()
        val current = settings.read()
        if (_state.value.symbol != current.symbol || _state.value.interval != current.interval) {
            cachedBars.clear() // never reuse a different instrument's bars or signal
            _state.value = MarketState(symbol = current.symbol, interval = current.interval)
        }
        _state.value = _state.value.copy(
            feed = FeedStatus(if (current.hasKey) FeedMode.CONNECTING else FeedMode.NO_KEY,
                if (current.hasKey) "در حال دریافت…" else "کلید Twelve Data وارد نشده است"),
        )
        loadCacheThenRefresh() // cached real bars are read-only even without the key
        if (current.hasKey) {
            startStream()
            startPolling()
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        streamJob?.cancel(); streamJob = null
        bootstrapJob?.cancel(); bootstrapJob = null
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

    private fun loadCacheThenRefresh() {
        val s = scope ?: return
        bootstrapJob = s.launch {
            val current = settings.read()
            val cached = cache.load(current.symbol, current.interval)
            if (settings.read().symbol != current.symbol || settings.read().interval != current.interval) return@launch
            if (cached.isNotEmpty()) {
                cached.forEach { bar -> if (bar.time !in cachedBars) cachedBars[bar.time] = bar }
                evaluateAndPublish(showingCache = true)
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val current = settings.read()
        if (!current.hasKey) {
            _state.value = _state.value.copy(feed = FeedStatus(FeedMode.NO_KEY, "کلید Twelve Data وارد نشده است"))
            return
        }
        if (!hasInternet()) {
            publishOffline("اینترنت دستگاه قطع است — آخرین داده واقعیِ ذخیره‌شده نمایش داده می‌شود")
            return
        }
        if (_state.value.feed.mode != FeedMode.LIVE) _state.value = _state.value.copy(
            feed = _state.value.feed.copy(mode = FeedMode.CONNECTING, detail = "دریافت کندل‌های واقعی…"))
        try {
            val fetched = client.fetchCandles(current.apiKey, current.symbol, current.interval, outputSize = 1500)
            if (settings.read().symbol != current.symbol || settings.read().interval != current.interval ||
                _state.value.symbol != current.symbol) return
            val receivedAt = System.currentTimeMillis()
            val streamRecent = _state.value.feed.mode == FeedMode.LIVE &&
                _state.value.feed.lastSuccessAt?.let { receivedAt - it in 0L..90_000L } == true
            val restCurrent = hasCurrentRestBar(fetched, current.interval, receivedAt)
            if (!restCurrent && streamRecent) return // stale REST must not roll back a recent WS tick
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
                publishOffline("آخرین کندل Twelve Data قدیمی است؛ دریافت دوبارهٔ تاریخچه قیمت زنده/مجوز معامله نیست")
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
            if (!hasRecentStream()) publishOffline(e.message ?: "خطای دریافت داده واقعی")
        } catch (_: Exception) {
            if (!hasRecentStream()) publishOffline("دریافت یا اعتبارسنجی داده واقعی با خطا روبه‌رو شد")
        }
    }

    private fun hasRecentStream(now: Long = System.currentTimeMillis()): Boolean =
        _state.value.feed.mode == FeedMode.LIVE &&
            _state.value.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    private fun startStream() {
        streamJob?.cancel()
        streamJob = scope?.launch {
            var backoff = 2_000L
            while (isActive) {
                val current = settings.read()
                if (!current.hasKey) {
                    _state.value = _state.value.copy(feed = FeedStatus(FeedMode.NO_KEY, "کلید Twelve Data وارد نشده است"))
                    delay(10_000); continue
                }
                if (!hasInternet()) {
                    publishOffline("آفلاین — اتصال زنده برقرار نشد")
                    delay(RECONNECT_WHEN_OFFLINE_MS); continue
                }
                try {
                    client.streamPrice(current.apiKey, current.symbol).collect { tick ->
                        // Ignore an already queued tick when the user changed markets/intervals.
                        val active = settings.read()
                        if (active.symbol != current.symbol || active.interval != current.interval) return@collect
                        backoff = 2_000L
                        onTick(tick.price, tick.at)
                    }
                    publishStreamUnavailable("جریان WebSocket قطع شد — تلاش مجدد")
                } catch (e: CancellationException) {
                    throw e // cancelling an old market job must not relabel the new market offline
                } catch (e: DataFeedException) {
                    publishStreamUnavailable(e.message ?: "قطع جریان WebSocket")
                } catch (_: Exception) {
                    publishStreamUnavailable("اتصال WebSocket قطع شد؛ اینترنت/دسترسی فید را بررسی کنید")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun onTick(price: Double, at: Long) {
        val current = settings.read()
        val now = System.currentTimeMillis()
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
        val current = _state.value
        val now = System.currentTimeMillis()
        if (current.feed.mode == FeedMode.POLLING &&
            current.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true &&
            hasCurrentRestBar(current.candles, current.interval, now)) {
            _state.value = current.copy(feed = current.feed.copy(detail =
                "$detail؛ کندل REST دوره‌ای است، نه تیک زنده"))
        } else publishOffline(detail)
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
    }
}
