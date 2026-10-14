package com.aurum.edge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One independently throttled engine per symbol. Refreshes coalesce, never queue behind
 * a universe-wide sweep. A slow symbol does not prevent completed symbols from refreshing.
 * HTTP publisher quotas and the device's finite networking resources still apply.
 */
internal class SymbolWorkers(private val scope: CoroutineScope, private val clock: () -> Long) {
    private class Slot(var job: Job? = null, var completed: Long? = null)
    private val slots = mutableMapOf<String, Slot>()

    @Synchronized fun submit(symbol: String, intervalMs: Long, work: suspend () -> Unit): Job? {
        val slot = slots.getOrPut(symbol) { Slot() }
        slot.job?.takeUnless { it.isCompleted }?.let { return it }
        if (slot.completed?.let { clock() - it in 0 until intervalMs } == true) return null
        val job = scope.launch(start = CoroutineStart.LAZY) { work() }
        slot.job = job
        job.invokeOnCompletion { synchronized(this) {
            if (slot.job === job) slot.completed = clock()
        } }
        job.start()
        return job
    }
}
