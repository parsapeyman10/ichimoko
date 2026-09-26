package com.aurum.edge.data

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared read-only scan for the chooser, screen and opt-in locked-screen research service. */
sealed interface NobitexScanState {
    data object Idle : NobitexScanState
    data object Loading : NobitexScanState
    data class Done(val snapshot: SpotScan) : NobitexScanState
    data class Failed(val message: String) : NobitexScanState
}

class NobitexSpotResearch(private val scanner: NobitexSpotScanner, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private val _state = MutableStateFlow<NobitexScanState>(NobitexScanState.Idle)
    val state: StateFlow<NobitexScanState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    /** Safe before selecting a workspace: a public GET only; no orders, alerts or paper practice. */
    internal suspend fun refresh() = mutex.withLock {
        val elapsed = SystemClock.elapsedRealtime()
        if (lastAttempt != 0L && elapsed - lastAttempt in 0L until 60_000L) {
            if (_state.value == NobitexScanState.Idle) _state.value = NobitexScanState.Failed(
                "برای رعایت سهمیهٔ نوبیتکس، تا یک دقیقه بعد از درخواست قبلی صبر کنید")
            return@withLock
        }
        lastAttempt = elapsed
        _state.value = NobitexScanState.Loading // never retain an old candidate during refresh
        val next = try { NobitexScanState.Done(scanner.scan()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { NobitexScanState.Failed((e.message ?: "آمار عمومی نوبیتکس در دسترس نیست").take(130)) }
        _state.value = next
    }
}
