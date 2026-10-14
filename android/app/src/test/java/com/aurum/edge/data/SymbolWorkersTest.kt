package com.aurum.edge.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SymbolWorkersTest {
    @Test fun `slow engine cannot block another engine refresh and duplicates coalesce`() = runBlocking {
        var now = 100L
        val workers = SymbolWorkers(this) { now }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fastCalls = 0
        val slow = workers.submit("slow", 20) { started.complete(Unit); release.await() }!!
        started.await()
        assertSame(slow, workers.submit("slow", 0) { error("duplicate") })
        workers.submit("fast", 20) { fastCalls++ }!!.join()
        assertNull(workers.submit("fast", 20) { error("too early") })
        now += 21
        workers.submit("fast", 20) { fastCalls++ }!!.join()
        assertEquals(2, fastCalls)
        assertFalse(slow.isCompleted)
        release.complete(Unit); slow.join()
    }

    @Test fun `canceled engine can restart without retaining queued requests`() = runBlocking {
        val workers = SymbolWorkers(this) { 10L }
        val job = workers.submit("XAU/USD", 0) { awaitCancellation() }!!
        job.cancelAndJoin()
        var calls = 0
        workers.submit("XAU/USD", 0) { calls++ }!!.join()
        assertEquals(1, calls)
    }
}
