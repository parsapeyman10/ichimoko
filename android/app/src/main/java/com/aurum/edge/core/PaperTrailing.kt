package com.aurum.edge.core

import kotlin.math.max
import kotlin.math.min

/** Long-lived V1 paper positions have no fixed take-profit exit. Stop advances on observed
 * prices only. Original stop/risk stays immutable for R and portfolio budgeting. On an OHLC
 * candle, the PREVIOUS stop wins before a new trail is calculated (unknown intrabar sequence).
 */
object PaperTrailing {
    fun advance(trade: PaperTrade, observedHigh: Double, observedLow: Double): PaperTrade {
        if (!trade.isOpen || trade.initialStopLoss == null ||
            !observedHigh.isFinite() || !observedLow.isFinite() || observedLow <= 0.0 ||
            observedHigh < observedLow) return trade
        val initialRisk = kotlin.math.abs(trade.entry - trade.initialStopLoss)
        if (initialRisk <= 0.0 || !initialRisk.isFinite()) return trade
        val buy = trade.action == SignalAction.BUY
        val extreme = if (buy) max(trade.trailExtreme ?: trade.entry, observedHigh)
                      else min(trade.trailExtreme ?: trade.entry, observedLow)
        val favourable = if (buy) extreme - trade.entry else trade.entry - extreme
        val proposed = when {
            favourable >= 2.0 * initialRisk -> if (buy) extreme - initialRisk else extreme + initialRisk
            favourable >= initialRisk -> trade.entry
            else -> trade.stopLoss
        }
        val nextStop = if (buy) max(trade.stopLoss, proposed) else min(trade.stopLoss, proposed)
        return if (nextStop == trade.stopLoss && extreme == trade.trailExtreme) trade
               else trade.copy(stopLoss = nextStop, trailExtreme = extreme)
    }
}
