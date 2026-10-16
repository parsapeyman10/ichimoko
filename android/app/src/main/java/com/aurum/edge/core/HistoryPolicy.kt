package com.aurum.edge.core

/**
 * Unified 5-minute (M5) candle history policy:
 * Always fetch and retain the latest 1500 closed M5 candles so that:
 * - Higher-timeframe Ichimoku lines (513 and 1024) are computed directly on M5
 * - M15 Ichimoku is computed from 500 resampled candles (every 3 M5 candles = 1 M15 candle)
 * - H1 Ichimoku is computed from 125 resampled candles (every 12 M5 candles = 1 H1 candle)
 */
object HistoryPolicy {
    const val LIVE_MIN_CANDLES: Int = 1500
    const val LIVE_REQUEST_CANDLES: Int = 1500
    const val LIVE_FETCH_CANDLES: Int = 1500

    const val TARGET_CANDLES: Int = 1500
    const val DEEP_CHART_CANDLES: Int = 1500

    const val CHART_BOOTSTRAP_CANDLES: Int = 1500
    const val CHART_BOOTSTRAP_MINIMUM: Int = 1500

    const val MAX_CACHED_CANDLES: Int = 1500
    const val MAX_TWELVE_CANDLES: Int = 1500
    const val MAX_PROVIDER_CANDLES: Int = 1500

    const val ICHIMOKU_HTF_513: Int = 513
    const val ICHIMOKU_HTF_1024: Int = 1024
    const val RESAMPLED_M15_CANDLES: Int = LIVE_REQUEST_CANDLES / 3
    const val RESAMPLED_H1_CANDLES: Int = LIVE_REQUEST_CANDLES / 12

    fun chartTargetCandles(symbol: String, interval: Interval): Int =
        LIVE_REQUEST_CANDLES

    fun providerRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_TWELVE_CANDLES)

    fun deepProviderRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
