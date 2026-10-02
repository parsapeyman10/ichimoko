package com.aurum.edge.core

/** Shared candle-history contract for live chart, scanner, replay and research downloads. */
object HistoryPolicy {
    /** Minimum real candle floor required before the engine/replay treats a feed as complete enough. */
    const val TARGET_CANDLES: Int = 3000

    /** Extra chart depth for XAU/USD so the user can scroll back beyond the shallow live window. */
    const val DEEP_CHART_CANDLES: Int = 12000

    /** Open the chart with a useful window first; background refresh expands it to the chart target. */
    const val CHART_BOOTSTRAP_CANDLES: Int = 1200
    const val CHART_BOOTSTRAP_MINIMUM: Int = 1001

    /** Keep a deep chart window plus the currently forming bar when one exists. */
    const val MAX_CACHED_CANDLES: Int = DEEP_CHART_CANDLES + 1

    /** Twelve Data's time_series endpoint tops out at 5000 rows in one response. */
    const val MAX_TWELVE_CANDLES: Int = 5000

    /** Public deep-history adapters (Yahoo/Dukascopy) may retain more than the engine floor. */
    const val MAX_PROVIDER_CANDLES: Int = DEEP_CHART_CANDLES

    fun chartTargetCandles(symbol: String, interval: Interval): Int =
        if (symbol.trim().equals("XAU/USD", ignoreCase = true) && interval.minutes >= Interval.M1.minutes) {
            DEEP_CHART_CANDLES
        } else TARGET_CANDLES

    /** Request size for Twelve Data and other single-call providers that cannot deliver deep history. */
    fun providerRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_TWELVE_CANDLES)

    /** Request size for public deep-history providers. */
    fun deepProviderRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
