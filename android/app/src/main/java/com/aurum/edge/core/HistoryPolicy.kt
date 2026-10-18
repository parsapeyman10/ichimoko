package com.aurum.edge.core

/**
 * Candle-count policy (the old flat "1500 M5 candles for everything" rule is retired):
 *
 * Every timeframe used for alignment is fetched INDEPENDENTLY from the source —
 * higher timeframes are never built by resampling the M5 series. Counts are sized
 * by the deepest indicator lookback in the engines (EMA-200 + Ichimoku 52/26),
 * with headroom, instead of one oversized number for all frames:
 *  - M5  fetch 400 · healthy minimum 280
 *  - M15 fetch 300 · healthy minimum 240
 *  - H1  fetch 250 · healthy minimum 200
 */
object HistoryPolicy {
    /** Healthy minimum closed M5 bars before any live signal work. */
    const val LIVE_MIN_CANDLES: Int = 280
    /** Default M5 request/retention size for live paths. */
    const val LIVE_REQUEST_CANDLES: Int = 400
    const val LIVE_FETCH_CANDLES: Int = 400

    /** Display depth for trade/chart history downloads (not an entry requirement). */
    const val TARGET_CANDLES: Int = 500
    const val DEEP_CHART_CANDLES: Int = 500

    const val CHART_BOOTSTRAP_CANDLES: Int = 500
    const val CHART_BOOTSTRAP_MINIMUM: Int = 280

    const val MAX_CACHED_CANDLES: Int = 600
    const val MAX_TWELVE_CANDLES: Int = 600
    const val MAX_PROVIDER_CANDLES: Int = 600

    /** Independent per-frame request size. Frames are fetched, never resampled from M5. */
    fun fetchSize(interval: Interval): Int = when (interval) {
        Interval.M5 -> 400
        Interval.M15 -> 300
        Interval.H1 -> 250
        else -> LIVE_FETCH_CANDLES
    }

    /** Minimum healthy closed bars for a frame to be usable as alignment evidence. */
    fun minSize(interval: Interval): Int = when (interval) {
        Interval.M5 -> LIVE_MIN_CANDLES
        Interval.M15 -> 240
        Interval.H1 -> 200
        else -> LIVE_MIN_CANDLES
    }

    fun chartTargetCandles(symbol: String, interval: Interval): Int =
        fetchSize(interval)

    fun providerRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_TWELVE_CANDLES)

    fun deepProviderRequestSize(requested: Int, minimum: Int = TARGET_CANDLES): Int =
        requested.coerceAtLeast(minimum).coerceAtMost(MAX_PROVIDER_CANDLES)

    fun trimForCache(candles: List<Candle>): List<Candle> =
        candles.sortedBy { it.time }.takeLast(MAX_CACHED_CANDLES)
}
