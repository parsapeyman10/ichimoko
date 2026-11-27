package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction

/**
 * Exploratory closed-bar comparison, not a faithful live execution replay or promotion evidence.
 * Both arms see identical closed candles, use the paper settlement model and cannot move SL backwards.
 * Candles do not give the order of highs/lows or the real timing of a strategy scan.
 */
object TradingShadowComparison {
    const val CURRENT_TRAIL_ATR = 0.35
    const val CANDIDATE_TRAIL_ATR = 0.40
    private const val WARMUP = 60
    data class Comparison(val currentNetUsd: Double, val candidateNetUsd: Double, val currentExitAt: Long, val candidateExitAt: Long)
    data class Result(val comparison: Comparison? = null, val unavailableFa: String = "")
    private data class Exit(val price: Double, val time: Long, val net: Double)

    fun compare(trade: PaperTrade, source: List<Candle>, now: Long): Result {
        if (trade.isOpen || trade.isBacktest || trade.origin != PaperTrade.ORIGIN_LIVE ||
            trade.action !in setOf(SignalAction.BUY, SignalAction.SELL) || !trade.netSettlementEnabled ||
            !trade.fixedRiskUsd.isFinite() || trade.fixedRiskUsd <= 0 || trade.initialStopLoss == null ||
            !trade.initialStopLoss.isFinite() || trade.initialStopLoss <= 0 || trade.settlementFeeUsd < 0 ||
            !trade.settlementFeeUsd.isFinite() || !trade.entry.isFinite() || trade.entry <= 0 ||
            !trade.takeProfit.isFinite() || trade.takeProfit <= 0)
            return Result(unavailableFa = "معامله یا مدل تسویهٔ کامل در دسترس نیست")
        val duration = trade.interval.millis
        // A partial entry candle could already contain a stop/target before the first complete bar.
        if (trade.openedAt % duration != 0L) return Result(unavailableFa = "ورود داخل کندل است و ترتیب آن ناشناخته")
        val bars = source.filter { it.closed && it.time + duration <= now }.sortedBy { it.time }
        if (bars.map { it.time }.distinct().size != bars.size) return Result(unavailableFa = "کندل تکراری")
        val entryIndex = bars.indexOfFirst { it.time == trade.openedAt }
        if (entryIndex < WARMUP) return Result(unavailableFa = "پوشش ورود یا پیش‌زمینهٔ کافی وجود ندارد")
        if (bars.any { !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
                it.low <= 0 || it.high < it.low || it.open !in it.low..it.high || it.close !in it.low..it.high })
            return Result(unavailableFa = "کندل ناسالم")
        if (bars.zipWithNext().any { (a, b) -> b.time - a.time != duration }) return Result(unavailableFa = "شکاف زمانی کندل‌ها")
        val isBuy = trade.action == SignalAction.BUY
        if (if (isBuy) !(trade.initialStopLoss < trade.entry && trade.takeProfit > trade.entry)
            else !(trade.initialStopLoss > trade.entry && trade.takeProfit < trade.entry))
            return Result(unavailableFa = "جهت حد اولیه یا هدف نامعتبر است")
        val original = trade.copy(stopLoss = trade.initialStopLoss, closedAt = null, exitPrice = null, pnlUsd = null, stopUpdatedAt = null)
        var current = original
        var candidate = original
        var exitA: Exit? = null
        var exitB: Exit? = null
        for (i in entryIndex until bars.size) {
            val bar = bars[i]
            fun hit(t: PaperTrade): Pair<Exit?, Boolean> {
                val slGap = if (isBuy) bar.open <= t.stopLoss else bar.open >= t.stopLoss
                val tpGap = if (isBuy) bar.open >= t.takeProfit else bar.open <= t.takeProfit
                val stopHit = if (isBuy) bar.low <= t.stopLoss else bar.high >= t.stopLoss
                val targetHit = if (isBuy) bar.high >= t.takeProfit else bar.low <= t.takeProfit
                // After an opening gap the first price is known; otherwise two barriers in one candle are ambiguous.
                if (!slGap && !tpGap && stopHit && targetHit) return null to true
                val price = when { slGap -> bar.open; tpGap -> t.takeProfit; stopHit -> t.stopLoss; targetHit -> t.takeProfit; else -> return null to false }
                return Exit(price, bar.time + duration, RiskAndCalendarEngine.computeExitPnlUsd(t, price).second) to false
            }
            if (exitA == null) { val (value, ambiguous) = hit(current); if (ambiguous) return Result(unavailableFa = "ترتیب برخورد حد و هدف در یک کندل نامعلوم است"); exitA = value }
            if (exitB == null) { val (value, ambiguous) = hit(candidate); if (ambiguous) return Result(unavailableFa = "ترتیب برخورد حد و هدف در یک کندل نامعلوم است"); exitB = value }
            if (exitA != null && exitB != null) break
            val snapshot = IchimokuStrategyEngine.computeSnapshot(bars.subList(maxOf(0, i + 1 - 200), i + 1), includeAnalogue = false)
            fun advance(t: PaperTrade, multiplier: Double): PaperTrade {
                val tick = PriceTick(bar.close, bar.time + duration)
                val be = ProfitLockPolicy.breakEvenStop(t, tick)
                var next = if (be != null) t.copy(stopLoss = be, stopUpdatedAt = tick.at) else t
                val protected = if (isBuy) next.stopLoss > next.initialStopLoss!! else next.stopLoss < next.initialStopLoss!!
                if (!protected || RiskAndCalendarEngine.computeExitPnlUsd(next, next.stopLoss).second < 0 || snapshot == null || snapshot.atr14 <= 0) return next
                val buffer = snapshot.atr14 * multiplier
                val levels = listOf(snapshot.tenkan, snapshot.kijun, if (isBuy) maxOf(snapshot.currentSpanA, snapshot.currentSpanB) else minOf(snapshot.currentSpanA, snapshot.currentSpanB))
                    .filter { it.isFinite() && it > 0 }
                    .map { if (isBuy) it - buffer else it + buffer }
                    .filter { if (isBuy) it > next.stopLoss && it <= bar.close - buffer else it < next.stopLoss && it >= bar.close + buffer }
                    .filter { RiskAndCalendarEngine.computeExitPnlUsd(next, it).second >= 0.01 }
                val best = if (isBuy) levels.maxOrNull() else levels.minOrNull()
                if (best != null) next = next.copy(stopLoss = best, stopUpdatedAt = tick.at)
                return next
            }
            if (exitA == null) current = advance(current, CURRENT_TRAIL_ATR)
            if (exitB == null) candidate = advance(candidate, CANDIDATE_TRAIL_ATR)
        }
        val a = exitA ?: return Result(unavailableFa = "مسیر برای پایان هر دو نسخه کافی نیست")
        val b = exitB ?: return Result(unavailableFa = "مسیر برای پایان هر دو نسخه کافی نیست")
        return Result(Comparison(a.net, b.net, a.time, b.time))
    }
}
