package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
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
        val observedClose: Double,
        val linesMovingUp: Boolean,
        val linesMovingDown: Boolean,
        val line513: Double? = null,
        val line1024: Double? = null,
    ) {
        fun higherTimeframeAligned(side: SignalAction, price: Double): Boolean {
            val l513 = line513 ?: return true
            val l1024 = line1024 ?: return when (side) {
                SignalAction.BUY -> price > l513
                SignalAction.SELL -> price < l513
                else -> false
            }
            return when (side) {
                SignalAction.BUY -> price > l513 && price > l1024 && l513 >= l1024
                SignalAction.SELL -> price < l513 && price < l1024 && l513 <= l1024
                else -> false
            }
        }

        fun trendAligned(side: SignalAction, price: Double): Boolean {
            if (price != observedClose) return false // no mixing with a different quote
            return when (side) {
                SignalAction.BUY -> price > max(visibleCloud.first, visibleCloud.second) &&
                    tenkan > kijun && projectedCloud.first > projectedCloud.second && chikouVsHigh &&
                    tenkanKijunAtr <= 3.0 && priceKijunAtr <= 4.0 && linesMovingUp &&
                    higherTimeframeAligned(SignalAction.BUY, price)
                SignalAction.SELL -> price < min(visibleCloud.first, visibleCloud.second) &&
                    tenkan < kijun && projectedCloud.first < projectedCloud.second && chikouVsLow &&
                    tenkanKijunAtr <= 3.0 && priceKijunAtr <= 4.0 && linesMovingDown &&
                    higherTimeframeAligned(SignalAction.SELL, price)
                else -> false
            }
        }

        /** Range mode measures compression instead of demanding a directional cloud. */
        fun rangeAligned(price: Double): Boolean =
            price == observedClose &&
                price in min(visibleCloud.first, visibleCloud.second)..max(visibleCloud.first, visibleCloud.second) &&
                tenkanKijunAtr <= 2.0

        fun detail(side: SignalAction): String {
            val htfNote = if (line513 != null && line1024 != null) {
                " · خطوط ۵۱۳/۱۰۲۴=${"%.2f".format(java.util.Locale.US, line513)}/${"%.2f".format(java.util.Locale.US, line1024)}"
            } else if (line513 != null) {
                " · خط ۵۱۳=${"%.2f".format(java.util.Locale.US, line513)}"
            } else ""
            return "ابر قابل‌مشاهده=${visibleCloud.first}/${visibleCloud.second} · ابرِ پیش‌نمایش=${projectedCloud.first}/${projectedCloud.second} " +
                "· تنکان=$tenkan کیجون=$kijun · چیکو نسبت به کندل ۲۴ گام قبل=" +
                when (side) {
                    SignalAction.BUY -> if (chikouVsHigh) "بالای سقف" else "تأیید نشد"
                    SignalAction.SELL -> if (chikouVsLow) "پایین کف" else "تأیید نشد"
                    else -> "بدون جهت"
                } + " · فاصلهٔ تنکان/کیجون=${"%.2f".format(java.util.Locale.US, tenkanKijunAtr)} ATR" +
                " · قیمت/کیجون=${"%.2f".format(java.util.Locale.US, priceKijunAtr)} ATR" +
                " · حرکت خطوط=${if (when (side) { SignalAction.BUY -> linesMovingUp; SignalAction.SELL -> linesMovingDown; else -> false }) "همسو/تخت" else "واگرا"}" +
                htfNote
        }
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
        return Read(
            visible, projectedA to projectedB, tenkan, kijun,
            close > historical.high, close < historical.low,
            abs(tenkan - kijun) / atr, abs(close - kijun) / atr, close, movesUp, movesDown,
            line513 = ichi.line513.getOrNull(i),
            line1024 = ichi.line1024.getOrNull(i),
        )
    }
}
