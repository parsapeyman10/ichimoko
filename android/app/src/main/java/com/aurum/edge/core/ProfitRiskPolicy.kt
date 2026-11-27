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

    /** Validate the actual paper settlement, not the approximate unrounded planning ratio. */
    fun planErrorFa(calc: RiskCalculationResult, trades: List<PaperTrade>, accountBalance: Double): String? {
        val expectedRisk = riskUsd(trades, accountBalance)
        if (!calc.riskBudgetUsd.isFinite() || kotlin.math.abs(calc.riskBudgetUsd - expectedRisk) > 0.005) {
            return "ریسک تغییر کرده است؛ ماشین‌حساب را با ریسک جدید به‌روز کنید."
        }
        val trade = calc.toPaperTrade()
        val stopPnl = RiskAndCalendarEngine.computeExitPnlUsd(trade, calc.stopLoss).second
        val targetPnl = RiskAndCalendarEngine.computeExitPnlUsd(trade, calc.takeProfit).second
        if (!stopPnl.isFinite() || kotlin.math.abs(stopPnl + expectedRisk) > 0.005) {
            return "زیان خالص در حد ضرر با بودجهٔ ریسک برابر نیست؛ معامله باز نشد."
        }
        val minimum = round(expectedRisk * 2.0 * 100.0) / 100.0
        val maximum = round(expectedRisk * 3.0 * 100.0) / 100.0
        if (!targetPnl.isFinite() || targetPnl < minimum - 1e-8 || targetPnl > maximum + 1e-8) {
            return "سود خالص حد هدف باید بین ۲ تا ۳ برابر ریسک باشد؛ معامله باز نشد."
        }
        return null
    }
}
