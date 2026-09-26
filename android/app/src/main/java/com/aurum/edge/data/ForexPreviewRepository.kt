package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A single XAU/USD REST SAMPLE for the chooser, not the streaming feed, signal or AI gate. */
enum class ForexPreviewStatus { IDLE, NEEDS_KEY, LOADING, OBSERVED, OUTDATED, UNAVAILABLE }

data class ForexPreviewState(
    val status: ForexPreviewStatus = ForexPreviewStatus.IDLE,
    val sample: Double? = null,
    /** Beginning of the last M5 bar reported by the provider; not a live trade timestamp. */
    val barAt: Long? = null,
    val receivedAt: Long? = null,
    val error: String? = null,
) {
    fun recent(now: Long = System.currentTimeMillis()): Boolean =
        status == ForexPreviewStatus.OBSERVED && sample?.let { it.isFinite() && it > 0.0 } == true &&
            receivedAt?.let { now - it in 0L..180_000L } == true &&
            barAt?.let { now - it in 0L..900_000L } == true && !MarketHours.forexWeekendClosed(now)
}

class ForexPreviewRepository(private val settings: SettingsStore, private val client: TwelveDataClient,
                             private val scope: CoroutineScope,
                             private val clock: () -> Long = System::currentTimeMillis,
                             private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private var attemptedKey: String? = null
    private val _state = MutableStateFlow(ForexPreviewState())
    val state: StateFlow<ForexPreviewState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    internal suspend fun refresh() = mutex.withLock {
        if (MarketHours.forexWeekendClosed(clock())) return@withLock // never request a closed-session quote
        val key = settings.read().apiKey
        if (key.isBlank()) {
            _state.value = ForexPreviewState(ForexPreviewStatus.NEEDS_KEY)
            return@withLock
        }
        val tick = elapsed()
        if (attemptedKey == key && attemptedAt != 0L && tick - attemptedAt in 0L until 180_000L) {
            if (!_state.value.recent(clock())) _state.value = ForexPreviewState(ForexPreviewStatus.OUTDATED,
                error = "نمونهٔ معتبر نداریم؛ پس از سه دقیقه دوباره بررسی کنید")
            return@withLock
        }
        attemptedAt = tick
        attemptedKey = key
        _state.value = ForexPreviewState(ForexPreviewStatus.LOADING)
        try {
            val bars = withContext(Dispatchers.IO) { client.fetchCandles(key, "XAU/USD", Interval.M5, 10) }
            if (settings.read().apiKey != key) { _state.value = ForexPreviewState(); return@withLock }
            val last = bars.lastOrNull() ?: throw DataFeedException("نمونهٔ کندل طلا موجود نیست")
            val now = clock()
            _state.value = if (now - last.time in 0L..900_000L)
                ForexPreviewState(ForexPreviewStatus.OBSERVED, last.close, last.time, now)
            else ForexPreviewState(ForexPreviewStatus.OUTDATED,
                error = "کندل گزارش‌شدهٔ طلا قدیمی است؛ قیمت جاری تأیید نشد")
        } catch (e: Exception) {
            if (settings.read().apiKey != key) _state.value = ForexPreviewState()
            else _state.value = ForexPreviewState(ForexPreviewStatus.UNAVAILABLE,
                error = if (e is DataFeedException) e.message else "دریافت نمونهٔ طلا ناموفق بود")
            // Never log a key or an OkHttp URL containing the key.
        }
    }
}
