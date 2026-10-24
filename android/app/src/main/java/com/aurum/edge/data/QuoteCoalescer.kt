package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One in-flight fetch per symbol, shared by every caller for a very short window.
 *
 * Why it exists: the same quote is asked for by independent components — the selected-market
 * stream, the open-position display loop, the background service, the scanner's "partial" price
 * and the entry gate. They used to each hold their own HTTP client, so one open EUR/USD position
 * produced **two** full requests per second and the selected gold market produced one more.
 * Nothing was shared, so the phone's modem never left the high-power state.
 *
 * What it deliberately does NOT do:
 *  - it never extends a quote's own timestamp, so [com.aurum.edge.core.TradeQuotePolicy.accepts]
 *    still rejects an old tick exactly as before;
 *  - it caches only *successful* reads (an exception propagates and leaves nothing behind);
 *  - the window is [ttlMs] (1.2 s by default), far below the 45 s freshness contract, so a cost
 *    or spread gate cannot pass on a price from an earlier decision;
 *  - callers that arrive while a fetch is running wait for that one fetch instead of starting a
 *    second (single-flight), which is what removes the duplicate requests.
 */
internal class QuoteCoalescer(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fetchedAt: Long, val tick: PriceTick)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun quote(symbol: String, load: suspend () -> PriceTick): PriceTick {
        fresh(symbol)?.let { return it }
        val lock = locks.computeIfAbsent(symbol) { Mutex() }
        return lock.withLock {
            // A caller that was queued behind another fetch reuses its result instead of
            // paying for the same request twice.
            fresh(symbol) ?: load().also { tick ->
                entries[symbol] = Entry(clock(), tick)
            }
        }
    }

    private fun fresh(symbol: String): PriceTick? = entries[symbol]?.let { entry ->
        val age = clock() - entry.fetchedAt
        entry.tick.takeIf { age in 0L..ttlMs }
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        /** Short enough that no decision can rely on it, long enough to merge duplicate loops. */
        const val DEFAULT_TTL_MS = 1_200L
    }
}
