package com.aurum.edge.core

/** Shared candle-history contract for live chart, scanner, replay and research downloads. */
object HistoryPolicy {
    /** User-requested floor for the complete online history window. */
    const val TARGET_CANDLES: Int = 3000

    /** Open the chart with a useful window first; background refresh expands it to TARGET_CANDLES. */
    const val CHART_BOOTSTRAP_CANDLES: Int = 1200
    const val CHART_BOOTSTRAP_MINIMUM: Int = 1001

    /** Keep 3000 completed previous bars plus the currently forming bar when one exists. */
    const val MAX_CACHED_CANDLES: Int = TARGET_CANDLES + 1

    /** Twelve Data supports up to 5000 in one time_series response. */
    const val MAX_PROVIDER_CANDLES: Int = 5000

    /**
     * Full-history callers retain the old 3000-bar floor. The chart's first request passes its
     * smaller explicit floor so it can render more than 1000 real bars without waiting for the
     * background expansion request.
     */
    fun providerRequestSize(requested: Int, minimum: Int = MAX_CACHED_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
