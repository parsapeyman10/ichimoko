package com.aurum.edge.core

/** Shared candle-history contract for live chart, scanner, replay and research downloads. */
object HistoryPolicy {
    /** User-requested floor: every online provider path asks for at least this many real bars. */
    const val TARGET_CANDLES: Int = 3000

    /** Keep 3000 completed previous bars plus the currently forming bar when one exists. */
    const val MAX_CACHED_CANDLES: Int = TARGET_CANDLES + 1

    /** Twelve Data supports up to 5000 in one time_series response. */
    const val MAX_PROVIDER_CANDLES: Int = 5000

    fun providerRequestSize(requested: Int): Int =
        requested.coerceAtLeast(MAX_CACHED_CANDLES).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
