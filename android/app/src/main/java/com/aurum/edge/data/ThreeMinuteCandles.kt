package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval

/** M3 from three *completed, consecutive* one-minute OHLC buckets on the same venue/pair.
 * No tick or candle is fabricated for minutes without trades; such a gap invalidates the
 * latest contiguous history. The seven independent V1 frames are fetched separately.
 */
internal object ThreeMinuteCandles {
    fun fromMinutes(input: List<Candle>, now: Long): List<Candle> {
        val minute = Interval.M1.millis
        val three = Interval.M3.millis
        val sorted = input.sortedBy { it.time }
        if (sorted.zipWithNext().any { (a, b) -> a.time == b.time && a != b }) return emptyList()
        val valid = sorted.distinctBy { it.time }.filter {
            it.closed && it.time > 0L && it.time % minute == 0L &&
                it.time + minute <= now && it.open.isFinite() && it.open > 0.0 &&
                it.high.isFinite() && it.high >= maxOf(it.open, it.close) &&
                it.low.isFinite() && it.low > 0.0 && it.low <= minOf(it.open, it.close) &&
                it.close.isFinite() && it.close > 0.0 && it.volume.isFinite() && it.volume > 0.0
        }
        val complete = valid.groupBy { it.time / three * three }.mapNotNull { (start, parts) ->
            val bars = parts.sortedBy { it.time }
            if (start + three > now || bars.size != 3 ||
                bars.indices.any { bars[it].time != start + it * minute }) null
            else Candle(start, bars.first().open, bars.maxOf { it.high },
                bars.minOf { it.low }, bars.last().close, bars.sumOf { it.volume }, closed = true)
        }.sortedBy { it.time }
        // A recent missing minute must never be covered by older M3 history.
        return complete.takeLastWhileIndexed(three)
    }

    private fun List<Candle>.takeLastWhileIndexed(step: Long): List<Candle> {
        if (isEmpty()) return emptyList()
        var index = lastIndex
        while (index > 0 && this[index].time - this[index - 1].time == step) index--
        return subList(index, size)
    }
}
