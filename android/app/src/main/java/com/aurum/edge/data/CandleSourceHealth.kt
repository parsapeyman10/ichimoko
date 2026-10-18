package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval

/**
 * Validates that a source returned enough fresh, closed, ordered candles for
 * the frame (per-frame minima from [HistoryPolicy.minSize]).
 */
internal object CandleSourceHealth {
    fun requiredSize(interval: Interval): Int = HistoryPolicy.minSize(interval)

    fun ready(rows: List<Candle>, interval: Interval, now: Long,
              requireContiguous: Boolean = false, requireVolume: Boolean = false): Boolean {
        val minBars = requiredSize(interval)
        val bars = rows.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }.takeLast(HistoryPolicy.fetchSize(interval))
        if (bars.size < minBars ||
            now - (bars.last().time + interval.millis) !in 0L..(interval.millis + 90_000L) ||
            bars.zipWithNext().any { (a,b) -> a.time >= b.time ||
                (requireContiguous && b.time - a.time != interval.millis) }) return false
        val stepBasis = if (interval == Interval.M15 || interval == Interval.H1) Interval.M5.millis else interval.millis
        return bars.all { bar -> bar.time > 0 && bar.time % stepBasis == 0L &&
            listOf(bar.open,bar.high,bar.low,bar.close,bar.volume).all { it.isFinite() } &&
            bar.low > 0 && bar.low <= minOf(bar.open,bar.close) &&
            bar.high >= maxOf(bar.open,bar.close) &&
            bar.volume >= 0 && (!requireVolume || bar.volume > 0) }
    }
}
