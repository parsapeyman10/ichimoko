package com.aurum.edge.core

/** Separate online evaluation windows from optional archived research-history limits. */
object HistoryPolicy {
    /** The live engine's EMA200 / Ichimoku floor is 210 CLOSED real bars. Request a small
     * cushion for still-forming bars and gaps; never relax freshness, identity or MTF gates.
     */
    const val LIVE_MIN_CANDLES: Int = 210
    const val LIVE_REQUEST_CANDLES: Int = 320

    /** Retained only for archived offline research; not a prerequisite for online checking. */
    const val TARGET_CANDLES: Int = 3000

    /** Archived research ceiling; never automatically requested by the live chart/scanner. */
    const val DEEP_CHART_CANDLES: Int = 12000

    /** Archived research/history bootstrap sizes (no automatic live-chart expansion). */
    const val CHART_BOOTSTRAP_CANDLES: Int = 1200
    const val CHART_BOOTSTRAP_MINIMUM: Int = 1001

    /** Legacy on-disk cache ceiling: do not discard saved real bars during an app update. */
    const val MAX_CACHED_CANDLES: Int = DEEP_CHART_CANDLES + 1

    /** Twelve Data's time_series endpoint tops out at 5000 rows in one response. */
    const val MAX_TWELVE_CANDLES: Int = 5000

    /** Public deep-history adapters (Yahoo/Dukascopy) may retain more than the engine floor. */
    const val MAX_PROVIDER_CANDLES: Int = DEEP_CHART_CANDLES

    fun chartTargetCandles(symbol: String, interval: Interval): Int =
        LIVE_REQUEST_CANDLES

    /** Request size for Twelve Data and other single-call providers that cannot deliver deep history. */
    fun providerRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_TWELVE_CANDLES)

    /** Request size for public deep-history providers. */
    fun deepProviderRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
