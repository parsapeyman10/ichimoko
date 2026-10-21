package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import kotlin.math.max
import kotlin.math.min

/** Pure closed-bar exit intent. Candles MUST be from the original position venue;
 * the journal executes an intent only on a later, ordered same-venue trade print.
 * This never substitutes a candle close for a fill. Old strategy records are untouched.
 */
internal object CryptoCompressionExit {
    data class Decision(val barClosedAt: Long, val stop: Double?, val exitReason: String?)

    fun assess(trade: PaperTrade, m5: List<Candle>, m15: List<Candle>,
               now: Long): Decision? {
        val atr = trade.cryptoEntryAtr ?: return null
        if (!trade.isOpen || atr <= 0 || !atr.isFinite() || m5.size < HistoryPolicy.minSize(Interval.M5) ||
            m5.any { !it.closed || it.time <= 0 || it.low <= 0 || it.high < it.low } ||
            m5.zipWithNext().any { (a,b) -> b.time-a.time != Interval.M5.millis }) return null
        val last = m5.last()
        val closeAt = last.time + Interval.M5.millis
        if (closeAt <= trade.openedAt || closeAt > now ||
            now - closeAt > 2*Interval.M5.millis ||
            closeAt <= (trade.cryptoLastClosedBarAt ?: 0L)) return null
        val i = m5.lastIndex
        val ichi = Ichimoku.compute(m5, 9, 26, 52, 26)
        val tenkan = ichi.tenkan[i] ?: return null
        val kijun = ichi.kijun[i] ?: return null
        val visible = ichi.cloudAt(i) ?: return null
        val spanB = ichi.senkouB[i] ?: return null
        val custom103 = Indicators.donchianMid(m5, 103)[i] ?: return null
        val projectedA = ichi.senkouA[i] ?: return null
        val buy = trade.action == SignalAction.BUY
        val favourable = if (buy) last.close - trade.entry else trade.entry - last.close
        val stop = when {
            favourable >= 3*atr -> if (buy) kijun - 0.1*atr else kijun + 0.1*atr
            favourable >= 2*atr -> if (buy) tenkan - 0.1*atr else tenkan + 0.1*atr
            favourable >= atr -> trade.entry
            else -> null
        }?.takeIf { if (buy) it < last.close else it > last.close }
        val macroFlip = (if (buy) custom103 - spanB >= 0.5*atr
            else spanB - custom103 >= 0.5*atr) ||
            (ichi.senkouB[i-3]?.let { prior ->
                if (buy) spanB - prior < -0.75*atr else spanB - prior > 0.75*atr
            } == true)
        val mainExit = if (buy) last.close < kijun || last.close <= max(visible.first, visible.second)
            else last.close > kijun || last.close >= min(visible.first, visible.second)
        val oppositeM15 = m15.size >= HistoryPolicy.minSize(Interval.M15) && m15.all { it.closed && it.time > 0 } &&
            m15.zipWithNext().all { (a,b) -> b.time-a.time == Interval.M15.millis } &&
            m15.last().time + Interval.M15.millis <= closeAt &&
            closeAt - (m15.last().time + Interval.M15.millis) <= 2*Interval.M15.millis &&
            run {
                val h = Ichimoku.compute(m15.takeLast(HistoryPolicy.fetchSize(Interval.M15)), 9, 26, 52, 26)
                val j = m15.lastIndex
                val t = h.tenkan[j] ?: return@run false
                val k = h.kijun[j] ?: return@run false
                if (buy) m15[j].close < k && t < k else m15[j].close > k && t > k
            }
        val prevT = ichi.tenkan[i-1]
        val prevK = ichi.kijun[i-1]
        val emergency = oppositeM15 && prevT != null && prevK != null && if (buy)
            prevT >= prevK && tenkan < kijun && projectedA < spanB
        else prevT <= prevK && tenkan > kijun && projectedA > spanB
        val exitReason = when {
            macroFlip -> "تغییر جهت ۵۲/۱۰۳ یا شیب ۵۲"
            emergency -> "کراس معکوس/ابر آینده/M15"
            mainExit -> "بسته‌شدن کندل آن‌سوی کیجون یا داخل ابر"
            favourable >= atr && (if (buy) last.close < tenkan else last.close > tenkan) -> null
            else -> null
        }
        return Decision(closeAt, stop, exitReason)
    }
}
