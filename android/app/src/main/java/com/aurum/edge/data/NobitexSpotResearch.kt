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

/** One shared read-only scan for the screen and opt-in locked-screen research service. */
sealed interface NobitexScanState {
    data object Idle : NobitexScanState
    data object Loading : NobitexScanState
    data class Done(val snapshot: SpotScan) : NobitexScanState
    data class Failed(val message: String) : NobitexScanState
}

class NobitexSpotResearch(private val scanner: NobitexSpotScanner, private val settings: SettingsStore,
                          private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private val _state = MutableStateFlow<NobitexScanState>(NobitexScanState.Idle)
    val state: StateFlow<NobitexScanState> = _state.asStateFlow()

    fun clear() { _state.value = NobitexScanState.Idle }
    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        if (settings.read().workspaceId != "nobitex") return@withLock
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
        if (settings.read().workspaceId == "nobitex") _state.value = next
    }
}
