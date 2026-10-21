package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.util.concurrent.ConcurrentHashMap

/**
 * Radar candle cache, invalidated by the *bar*, not by the wall clock.
 *
 * The sweep used to download three independent frames (M5 400 + M15 300 + H1 250 candles) for
 * every one of the 15 symbols every 60 seconds. A closed M5 bar only appears every 5 minutes, so
 * four of those five sweeps downloaded byte-identical history — roughly 80 % of the app's traffic
 * produced no new information at all.
 *
 * This cache keeps the last complete, integrity-checked bundle per symbol and expires it when the
 * next M5 bar can exist (`newestBar + 5 min + margin`). Everything downstream still runs on every
 * sweep: the tick, the spread gate, the paper-entry review, ranking and alerts all use a fresh
 * quote; only the candle series is reused, and only while it is provably unchanged.
 *
 * It is deliberately small and honest:
 *  - only a *complete* bundle is stored (a partial/error scan leaves no entry, so it is retried);
 *  - a provider or integrity revision change drops the entry instead of mixing two sources;
 *  - [invalidate] lets the UI force a real download (pull-to-refresh, source change).
 */
internal class ScanHistoryCache(
    private val clock: () -> Long = System::currentTimeMillis,
    /** A new bar is only usable a couple of seconds after its close, never exactly on the edge. */
    private val settleMs: Long = 2_000L,
) {
    data class Bundle(
        val provider: String,
        val integrityRevision: Long,
        val frames: Map<Interval, List<Candle>>,
        /** Wall clock at which a newer M5 bar can exist; until then the data is unchanged. */
        val validUntil: Long,
    ) {
        val m5: List<Candle> get() = frames.getValue(Interval.M5)
    }

    private val bundles = ConcurrentHashMap<String, Bundle>()

    /** Store a complete bundle and derive its expiry from the newest closed M5 bar. */
    fun record(symbol: String, provider: String, integrityRevision: Long,
               frames: Map<Interval, List<Candle>>, now: Long = clock()): Bundle? {
        val m5 = frames[Interval.M5].orEmpty()
        val newest = m5.maxOfOrNull { it.time } ?: return null
        val bundle = Bundle(provider, integrityRevision, frames,
            validUntil = newest + Interval.M5.millis + settleMs)
        // Never hand out an already-expired bundle (a stale source must be re-read, not trusted).
        if (bundle.validUntil <= now) {
            bundles.remove(symbol)
            return null
        }
        bundles[symbol] = bundle
        return bundle
    }

    fun get(symbol: String, now: Long = clock()): Bundle? =
        bundles[symbol]?.takeIf { now < it.validUntil }

    fun invalidate(symbol: String? = null) {
        if (symbol == null) bundles.clear() else bundles.remove(symbol)
    }

    /** True when a stored bundle still describes the current bar for this symbol. */
    fun isFresh(symbol: String, now: Long = clock()): Boolean = get(symbol, now) != null
}
