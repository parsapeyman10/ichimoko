package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import kotlin.math.abs
import kotlin.math.max

/** Wilder's +DI/-DI(14); ADX is separately computed by Indicators.adx.
 * No zero-volume fallback or synthetic history is used for entry.
 */
internal object DirectionalIndex {
    data class Read(val plus: Double, val minus: Double)
    fun at(bars: List<Candle>, index: Int, period: Int = 14): Read? {
        if (index < period + 1 || period <= 0 || index >= bars.size) return null
        var tr = 0.0
        var plus = 0.0
        var minus = 0.0
        for (i in 1..index) {
            val up = bars[i].high - bars[i-1].high
            val down = bars[i-1].low - bars[i].low
            val trueRange = max(bars[i].high - bars[i].low,
                max(abs(bars[i].high - bars[i-1].close), abs(bars[i].low - bars[i-1].close)))
            val positive = if (up > down && up > 0) up else 0.0
            val negative = if (down > up && down > 0) down else 0.0
            if (!trueRange.isFinite() || trueRange < 0) return null
            if (i <= period) { tr += trueRange; plus += positive; minus += negative }
            else {
                tr += trueRange - tr / period
                plus += positive - plus / period
                minus += negative - minus / period
            }
        }
        if (tr <= 0) return null
        return Read(100 * plus / tr, 100 * minus / tr)
    }
}
