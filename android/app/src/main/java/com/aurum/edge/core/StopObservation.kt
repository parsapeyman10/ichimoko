package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlinx.serialization.Serializable

/** Audit and shadow levels only. Never used to manage or close a position. */
@Serializable
data class StopObservation(
    val kind: String,
    val recordedAt: Long,
    val quoteAt: Long? = null,
    val quotePrice: Double? = null,
    val quoteProvider: String? = null,
    val barTime: Long? = null,
    val previousStop: Double? = null,
    val actualStop: Double? = null,
    val suggestedStop: Double? = null,
    val pnlAtStopUsd: Double? = null,
    val actualPnlUsd: Double? = null,
    val status: String = "",
)

object StopObservationPolicy {
    const val MAX_EVENTS = 64
    private const val MIN_CANDLES = 80
    // Exploratory only; matches the existing production structural buffer, not an optimized value.
    const val BUFFER_ATR = 0.35

    fun append(trade: PaperTrade, event: StopObservation): List<StopObservation> =
        (trade.stopObservations + event).takeLast(MAX_EVENTS)

    /** One suggestion for the most recent complete candle. Does not simulate an alternate exit. */
    fun shadow(trade: PaperTrade, quote: PriceTick, candles: List<Candle>, now: Long): StopObservation? {
        if (!trade.isOpen || trade.isBacktest || trade.action !in listOf(SignalAction.BUY, SignalAction.SELL) ||
            !TradeQuotePolicy.accepts(quote, now) || quote.at > now || quote.at <= trade.openedAt) return null
        val duration = trade.interval.millis
        val ordered = candles.filter { it.closed && it.time > 0 && it.time <= now - duration }
            .sortedBy { it.time }.takeLast(200)
        val lastGap = ordered.zipWithNext().indexOfLast { (a, b) -> b.time - a.time != duration }
        val bars = if (lastGap >= 0) ordered.drop(lastGap + 1) else ordered
        if (bars.size < MIN_CANDLES) return null
        val last = bars.last()
        if (last.time + duration <= trade.openedAt || now - (last.time + duration) > 2 * duration) return null
        if (trade.stopObservations.any { it.kind == "SHADOW" && it.barTime == last.time }) return null
        if (bars.any { !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
                it.low <= 0 || it.high < it.low || it.open !in it.low..it.high || it.close !in it.low..it.high }) return null
        val s = IchimokuStrategyEngine.computeSnapshot(bars, keepBars = 200, includeAnalogue = false) ?: return null
        if (s.time != last.time || !s.atr14.isFinite() || s.atr14 <= 0 ||
            listOf(s.close, s.tenkan, s.kijun, s.currentSpanA, s.currentSpanB).any { !it.isFinite() || it <= 0 }) return null
        val buy = trade.action == SignalAction.BUY
        val initial = trade.initialStopLoss ?: return null
        val protected = trade.netBreakEvenObservedAt != null &&
            (if (buy) trade.stopLoss > initial else trade.stopLoss < initial) &&
            RiskAndCalendarEngine.computeExitPnlUsd(trade, trade.stopLoss).second >= 0.01
        val aligned = if (buy) s.close > maxOf(s.currentSpanA, s.currentSpanB) && s.tenkan > s.kijun && s.close > s.tenkan
            else s.close < minOf(s.currentSpanA, s.currentSpanB) && s.tenkan < s.kijun && s.close < s.tenkan
        val buffer = s.atr14 * BUFFER_ATR
        val candidate = if (buy) s.kijun - buffer else s.kijun + buffer
        val valid = candidate.isFinite() && candidate > 0 &&
            (if (buy) candidate > trade.stopLoss && candidate <= quote.price - buffer
             else candidate < trade.stopLoss && candidate >= quote.price + buffer) &&
            RiskAndCalendarEngine.computeExitPnlUsd(trade, candidate).second >= 0.01
        val suggestion = candidate.takeIf { protected && aligned && valid }
        return StopObservation(kind = "SHADOW", recordedAt = now, quoteAt = quote.at, quotePrice = quote.price,
            quoteProvider = quote.provider?.take(80), barTime = last.time, actualStop = trade.stopLoss,
            suggestedStop = suggestion, pnlAtStopUsd = RiskAndCalendarEngine.computeExitPnlUsd(trade, trade.stopLoss).second,
            status = when { !protected -> "WAIT_PROTECTION"; !aligned -> "TREND_UNCONFIRMED"; suggestion == null -> "HOLD"; else -> "CANDIDATE" })
    }
}
