package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.IchimokuEntryEvidence
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs

/** Mandatory entry gate; only CLOSED, contiguous, authentic bars can establish a setup.
 * The cross may be on one of the last two completed bars; compression precedes the cross.
 * CloudAt is the *visible* cloud, not the future-shifted raw spans.
 */
internal object IchimokuEntrySetup {
    fun assess(bars: List<Candle>, ichi: Ichimoku, atr: List<Double?>,
               interval: Interval, side: SignalAction): IchimokuEntryEvidence? {
        if (side == SignalAction.NO_TRADE || bars.size < 100 ||
            bars.any { !it.closed || it.time <= 0 || !it.close.isFinite() } ||
            bars.zipWithNext().any { (a, b) -> b.time - a.time != interval.millis }) return null
        val last = bars.lastIndex
        fun value(series: List<Double?>, i: Int): Double? =
            series.getOrNull(i)?.takeIf { it.isFinite() }
        fun thin(i: Int): Boolean {
            val width = ichi.cloudAt(i)?.let { (a, b) -> abs(a - b) } ?: return false
            val unit = value(atr, i) ?: return false
            return unit > 0 && width.isFinite() && width <= unit * 0.8
        }
        if (!thin(last)) return null
        for (cross in last downTo last - 1) {
            val previousT = value(ichi.tenkan, cross - 1) ?: continue
            val previousK = value(ichi.kijun, cross - 1) ?: continue
            val currentT = value(ichi.tenkan, cross) ?: continue
            val currentK = value(ichi.kijun, cross) ?: continue
            val crossed = if (side == SignalAction.BUY) previousT <= previousK && currentT > currentK
                          else previousT >= previousK && currentT < currentK
            val finalT = value(ichi.tenkan, last) ?: continue
            val finalK = value(ichi.kijun, last) ?: continue
            if (!crossed || (if (side == SignalAction.BUY) finalT <= finalK else finalT >= finalK) ||
                !thin(cross)) continue
            // Three of the five completed bars BEFORE the cross must have converged
            // Tenkan/Kijun AND a visibly thin cloud, measured against each bar's own ATR.
            val compressed = (cross - 5 until cross).count { i ->
                val t = value(ichi.tenkan, i)
                val k = value(ichi.kijun, i)
                val unit = value(atr, i)
                t != null && k != null && unit != null && unit > 0 &&
                    abs(t - k) <= unit * 0.55 && thin(i)
            }
            if (compressed >= 3) return IchimokuEntryEvidence(
                side = side, barTime = bars[last].time, crossTime = bars[cross].time,
                interval = interval, compressedBars = compressed)
        }
        return null
    }
}
