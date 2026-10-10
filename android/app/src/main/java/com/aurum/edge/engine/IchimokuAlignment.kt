package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Compare each Ichimoku component at its OWN time index. Span values at the current
 * candle are projected forward for display, not future observed prices; the visible cloud
 * comes from 24 bars ago. Chikou at the current cursor is today's close drawn 24 bars back.
 */
internal object IchimokuAlignment {
    data class Read(
        val visibleCloud: Pair<Double, Double>,
        val projectedCloud: Pair<Double, Double>,
        val tenkan: Double,
        val kijun: Double,
        val chikouVsHigh: Boolean,
        val chikouVsLow: Boolean,
        val tenkanKijunAtr: Double,
        val priceKijunAtr: Double,
        val linesMovingUp: Boolean,
        val linesMovingDown: Boolean,
    ) {
        fun trendAligned(side: SignalAction, price: Double): Boolean = when (side) {
            SignalAction.BUY -> price > max(visibleCloud.first, visibleCloud.second) &&
                tenkan > kijun && projectedCloud.first > projectedCloud.second && chikouVsHigh &&
                tenkanKijunAtr <= 3.0 && priceKijunAtr <= 4.0 && linesMovingUp
            SignalAction.SELL -> price < min(visibleCloud.first, visibleCloud.second) &&
                tenkan < kijun && projectedCloud.first < projectedCloud.second && chikouVsLow &&
                tenkanKijunAtr <= 3.0 && priceKijunAtr <= 4.0 && linesMovingDown
            else -> false
        }

        /** Range mode measures compression instead of demanding a directional cloud. */
        fun rangeAligned(price: Double): Boolean =
            price in min(visibleCloud.first, visibleCloud.second)..max(visibleCloud.first, visibleCloud.second) &&
                tenkanKijunAtr <= 2.0

        fun detail(side: SignalAction): String =
            "ابر قابل‌مشاهده=${visibleCloud.first}/${visibleCloud.second} · ابرِ پیش‌نمایش=${projectedCloud.first}/${projectedCloud.second} " +
                "· تنکان=$tenkan کیجون=$kijun · چیکو نسبت به کندل ۲۴ گام قبل=" +
                when (side) {
                    SignalAction.BUY -> if (chikouVsHigh) "بالای سقف" else "تأیید نشد"
                    SignalAction.SELL -> if (chikouVsLow) "پایین کف" else "تأیید نشد"
                    else -> "بدون جهت"
                } + " · فاصلهٔ تنکان/کیجون=${"%.2f".format(java.util.Locale.US, tenkanKijunAtr)} ATR" +
                " · قیمت/کیجون=${"%.2f".format(java.util.Locale.US, priceKijunAtr)} ATR" +
                " · حرکت خطوط=${if (when (side) { SignalAction.BUY -> linesMovingUp; SignalAction.SELL -> linesMovingDown; else -> false }) "همسو/تخت" else "واگرا"}"
    }

    fun read(bars: List<Candle>, ichi: Ichimoku, atr: Double): Read? {
        if (bars.isEmpty() || !atr.isFinite() || atr <= 0.0) return null
        val i = bars.lastIndex
        val lag = i - ichi.displacement
        val historical = bars.getOrNull(lag) ?: return null
        val visible = ichi.cloudAt(i) ?: return null
        val projectedA = ichi.senkouA.getOrNull(i) ?: return null
        val projectedB = ichi.senkouB.getOrNull(i) ?: return null
        val tenkan = ichi.tenkan.getOrNull(i) ?: return null
        val kijun = ichi.kijun.getOrNull(i) ?: return null
        val close = bars[i].close
        // Compare the four computed lines across recent CLOSED bars. Flat long-period
        // lines are normal; only a meaningful counter-move (>0.25 ATR) is divergence.
        val before = i - 3
        val prior = listOf(ichi.tenkan, ichi.kijun, ichi.senkouA, ichi.senkouB)
            .map { it.getOrNull(before) ?: return null }
        val current = listOf(tenkan, kijun, projectedA, projectedB)
        val movesUp = current.zip(prior).all { (value, previous) -> value - previous >= -0.25 * atr }
        val movesDown = current.zip(prior).all { (value, previous) -> value - previous <= 0.25 * atr }
        return Read(visible, projectedA to projectedB, tenkan, kijun,
            close > historical.high, close < historical.low,
            abs(tenkan - kijun) / atr, abs(close - kijun) / atr, movesUp, movesDown)
    }
}
