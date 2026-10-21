package com.aurum.edge.data

import com.aurum.edge.core.MonitorCadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One driver for the continuous universe sweep.
 *
 * Before this the same [PairScanner.refreshNow] was scheduled from two places:
 *   * [AurumViewModel.ensureVisibleOnlineLoop]  (foreground UI)
 *   * [SignalMonitorService.sweepJob]           (background service)
 *
 * Both called the same singleton scanner, which already throttles per symbol, so the second
 * scheduler was pure duplicate work. This coordinator owns the single adaptive loop.
 *
 * The loop runs only while [enabled] is true (switched on by the UI when it is visible) or
 * the background monitor service is running [isServiceRunning]. When neither is true the
 * scanner idles — no continuous polling of a closed app.
 */
class SweepCoordinator(
    private val scanner: PairScanner,
    private val isServiceRunning: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweepJob: Job? = null

    /** Switched on by the UI layer while a data tab is visible. Starts false. */
    val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun start() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (isActive) {
                if (enabled.value || isServiceRunning()) {
                    scanner.refreshNow()
                }
                delay(MonitorCadence.sweepDelayMs(
                    scanner.state.value.statuses.map { it.state to it.confidence }))
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
    }
}
