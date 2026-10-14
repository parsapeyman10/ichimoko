package com.aurum.edge.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Isolate each external publisher's concurrent HTTP budget, NEVER the asset class.
 * All symbol jobs start immediately. A publisher can still throttle its own requests;
 * there is no promise of quota-free, instant or even available network data.
 */
internal class PublisherBudget<K>(capacities: Map<K, Int>) {
    private val permits = capacities.mapValues { (_, capacity) ->
        require(capacity > 0)
        Semaphore(capacity)
    }

    suspend fun <T> tryRead(publisher: K, request: suspend () -> T): T {
        val permit = requireNotNull(permits[publisher]) { "publisher not configured" }
        if (!permit.tryAcquire()) throw PublisherOccupied()
        try { return request() } finally { permit.release() }
    }

    suspend fun <T> read(publisher: K, request: suspend () -> T): T =
        requireNotNull(permits[publisher]) { "publisher not configured" }.withPermit { request() }
}

/** Local admission is not an outage or a publisher's HTTP 429. */
internal class PublisherOccupied : DataFeedException("ظرفیت محلی این ناشر پر است؛ بدون صف، تلاش بعدی موتور نماد")
