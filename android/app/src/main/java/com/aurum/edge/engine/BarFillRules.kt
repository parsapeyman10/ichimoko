package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import kotlin.math.max
import kotlin.math.min

/** OHLC execution *assumptions*, not a claim that a broker would fill at these prices. */
internal object BarFillRules {
    data class Entry(val fill: Double, val time: Long)
    data class Exit(val fill: Double, val reason: String)

    fun nextOpen(signal: Candle, next: Candle?, interval: Interval, side: SignalAction,
                 stop: Double, target: Double, spread: Double): Entry? {
        if (!signal.closed || next?.closed != true || next.time - signal.time != interval.millis ||
            side == SignalAction.NO_TRADE || !spread.isFinite() || spread < 0.0 ||
            !next.open.isFinite() || next.open <= 0.0 ||
            !stop.isFinite() || !target.isFinite()) return null
        val dir = if (side == SignalAction.BUY) 1.0 else -1.0
        val fill = next.open + dir * spread / 2.0
        // A gap through a fixed stop/target invalidates the signal's original risk/reward.
        if (!fill.isFinite() || fill <= 0.0 || (fill - stop) * dir <= 0.0 ||
            (target - fill) * dir <= 0.0) return null
        return Entry(fill, next.time)
    }

    /** Apply stop before target when both are in a bar. A gap through stop fills at the worse open. */
    fun protectiveExit(bar: Candle, side: SignalAction, stop: Double, target: Double,
                       spread: Double): Exit? {
        if (side == SignalAction.NO_TRADE) return null
        val dir = if (side == SignalAction.BUY) 1.0 else -1.0
        val half = spread / 2.0
        // Stops/targets are executable bid (long) or ask (short), not midpoint OHLC.
        val exitOpen = bar.open - dir * half
        val hitStop = if (dir > 0) bar.low - half <= stop else bar.high + half >= stop
        val hitTarget = if (dir > 0) bar.high - half >= target else bar.low + half <= target
        return when {
            hitStop -> Exit(if (dir > 0) min(stop, exitOpen) else max(stop, exitOpen), "حد ضرر")
            hitTarget -> Exit(target, "حد سود") // do not assume a better fill on a favorable gap
            else -> null
        }
    }

    fun nextOpenExit(bar: Candle, side: SignalAction, stop: Double, target: Double,
                     spread: Double, reason: String): Exit {
        val dir = if (side == SignalAction.BUY) 1.0 else -1.0
        val executable = bar.open - dir * spread / 2.0
        // A protective order was also standing overnight. Never grant a better-than-target
        // price on a favorable opening gap; adverse gaps through stop fill at the worse open.
        return when {
            (executable - stop) * dir <= 0.0 -> Exit(executable, "حد ضرر (گپ در open)")
            (target - executable) * dir <= 0.0 -> Exit(target, "حد سود (گپ در open)")
            else -> Exit(executable, reason)
        }
    }
}
