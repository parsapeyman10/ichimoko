package com.aurum.edge.core

import kotlin.math.round

/** The increment is based on realized live journal P&L, never open P&L or backtests. */
object ProfitRiskPolicy {
    const val BASE_CAPITAL_USD = 100_000.0
    const val BASE_RISK_USD = 5.0
    const val PROFIT_FRACTION = 0.005

    fun realizedProfit(trades: List<PaperTrade>): Double = trades.asSequence()
        .filter { !it.isOpen && !it.isBacktest }
        .mapNotNull { it.pnlUsd?.takeIf { pnl -> pnl.isFinite() } }
        .sum()

    fun riskUsd(trades: List<PaperTrade>, accountBalance: Double): Double {
        val availableAboveBase = if (accountBalance.isFinite()) (accountBalance - BASE_CAPITAL_USD).coerceAtLeast(0.0) else 0.0
        val eligibleProfit = realizedProfit(trades).coerceAtLeast(0.0).coerceAtMost(availableAboveBase)
        return round((BASE_RISK_USD + eligibleProfit * PROFIT_FRACTION) * 100.0) / 100.0
    }
}
