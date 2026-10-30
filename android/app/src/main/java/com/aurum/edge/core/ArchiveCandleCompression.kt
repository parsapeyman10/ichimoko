package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import kotlin.math.ceil

/** OHLC aggregation preserves each bucket's first open, last close and extremes. */
fun compressArchiveCandles(candles: List<Candle>, maxBars: Int): List<Candle> {
    require(maxBars > 0)
    val sorted = candles.filter {
        it.open.isFinite() && it.close.isFinite() && it.high.isFinite() && it.low.isFinite() &&
            it.open > 0 && it.close > 0 && it.high >= it.low
    }.sortedBy { it.time }.distinctBy { it.time }
    if (sorted.size <= maxBars) return sorted
    val bucketSize = ceil(sorted.size.toDouble() / maxBars).toInt()
    return sorted.chunked(bucketSize).map { group ->
        Candle(time = group.first().time, open = group.first().open,
            high = group.maxOf { it.high }, low = group.minOf { it.low },
            close = group.last().close, volume = group.sumOf { it.volume },
            closed = group.all { it.closed })
    }
}
