package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import kotlin.math.abs

/** Paper-account protection on observed ticks; gaps and outages can still cause loss. */
object ProfitLockPolicy {
    const val ACTIVATION_NET_R = 1.5
    const val MIN_NET_USD = 0.01

    fun breakEvenStop(trade: PaperTrade, tick: PriceTick): Double? {
        if (!trade.isOpen || !tick.isTradeTick || tick.at <= trade.openedAt || !tick.price.isFinite() || tick.price <= 0.0) return null
        val initial = trade.initialStopLoss ?: trade.stopLoss
        if (trade.stopLoss != initial && RiskAndCalendarEngine.computeExitPnlUsd(trade, trade.stopLoss).second >= MIN_NET_USD) return null
        if (!initial.isFinite() || initial <= 0 || !trade.entry.isFinite() || trade.entry <= 0 ||
            !trade.fixedRiskUsd.isFinite() || trade.fixedRiskUsd <= 0 || abs(trade.entry - initial) <= 1e-12) return null
        val sign = when (trade.action) { SignalAction.BUY -> 1.0; SignalAction.SELL -> -1.0; else -> return null }
        val favorable = (tick.price - trade.entry) * sign
        if (favorable <= 0 || RiskAndCalendarEngine.computeExitPnlUsd(trade, tick.price).second / trade.fixedRiskUsd < ACTIVATION_NET_R) return null
        // Use exactly the same settlement conversion and rounding as closing the paper trade.
        var low = 0.0
        var high = favorable
        repeat(48) {
            val mid = (low + high) / 2.0
            val pnl = RiskAndCalendarEngine.computeExitPnlUsd(trade, trade.entry + sign * mid).second
            if (pnl >= MIN_NET_USD) high = mid else low = mid
        }
        // Add a tiny price cushion beyond the rounding boundary, not an invented spread.
        val candidate = trade.entry + sign * (high + maxOf(trade.entry * 1e-9, 1e-9))
        if (!candidate.isFinite() || candidate <= 0 || (tick.price - candidate) * sign <= 0) return null
        if ((candidate - trade.stopLoss) * sign <= 0) return null
        if (RiskAndCalendarEngine.computeExitPnlUsd(trade, candidate).second < MIN_NET_USD) return null
        return candidate
    }
}
