package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval

/** The source has to be healthy *before* fallback stops. A successful HTTP response
 * with old/partial/corrupt bars must not hide a healthier second provider.
 */
internal object CandleSourceHealth {
    fun ready(rows: List<Candle>, interval: Interval, now: Long,
              requireContiguous: Boolean = false, requireVolume: Boolean = false): Boolean {
        val bars = rows.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        if (bars.size < HistoryPolicy.LIVE_MIN_CANDLES ||
            now - (bars.last().time + interval.millis) !in 0L..(interval.millis + 90_000L) ||
            bars.zipWithNext().any { (a,b) -> a.time >= b.time ||
                (requireContiguous && b.time - a.time != interval.millis) }) return false
        return bars.all { bar -> bar.time > 0 && bar.time % interval.millis == 0L &&
            listOf(bar.open,bar.high,bar.low,bar.close,bar.volume).all { it.isFinite() } &&
            bar.low > 0 && bar.low <= minOf(bar.open,bar.close) &&
            bar.high >= maxOf(bar.open,bar.close) &&
            bar.volume >= 0 && (!requireVolume || bar.volume > 0) }
    }
}
