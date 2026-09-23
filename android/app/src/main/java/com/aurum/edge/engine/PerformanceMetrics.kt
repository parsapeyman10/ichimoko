package com.aurum.edge.engine

import com.aurum.edge.core.BacktestRecord
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import kotlin.math.sqrt

data class ClosedOutcome(
    val side: SignalAction,
    val entryAt: Long,
    val exitAt: Long,
    val pnlUsd: Double,
    val rMultiple: Double?,
)

data class PerformanceReport(
    val total: Int,
    val wins: Int,
    val losses: Int,
    val longCount: Int,
    val shortCount: Int,
    val winRatePct: Double?,
    val netPnlUsd: Double,
    val grossProfitUsd: Double,
    val grossLossUsd: Double,
    val averageWinUsd: Double?,
    val averageLossUsd: Double?,
    val profitFactor: Double?,
    /** Per-closed-trade Sharpe, risk-free rate zero, NOT annualized. Not daily Sharpe. */
    val tradeSharpe: Double?,
    val expectancyR: Double?,
    val averageDurationMillis: Long?,
    val longestWinningStreak: Int,
    val longestLosingStreak: Int,
    val maxDrawdownPct: Double?,
)

/** Descriptive statistics from settled outcomes only; no future return or synthetic observation. */
object PerformanceMetrics {
    fun fromBacktest(result: Backtester.Result): PerformanceReport = from(
        result.trades.map { ClosedOutcome(it.side, it.entryTime, it.exitTime, it.pnlUsd, it.rMultiple) },
        result.initialBalance,
    )

    fun fromStoredReport(record: BacktestRecord): PerformanceReport? {
        // Older versions stored only the last 80 trades; do not present partial metrics as full.
        if (record.trades.size != record.wins + record.losses) return null
        return from(record.trades.map { ClosedOutcome(
            if (it.side == "BUY") SignalAction.BUY else SignalAction.SELL,
            it.entryTime, it.exitTime, it.pnlUsd, it.rMultiple,
        ) }, record.initialBalance)
    }

    fun fromPaper(trades: List<PaperTrade>, startingBalance: Double): PerformanceReport = from(
        trades.filter { !it.isOpen && it.pnlUsd != null && it.closedAt != null }.map {
            ClosedOutcome(it.action, it.openedAt, it.closedAt!!, it.pnlUsd!!, it.rMultiple)
        }, startingBalance,
    )

    fun from(outcomes: List<ClosedOutcome>, startingBalance: Double): PerformanceReport {
        require(startingBalance.isFinite() && startingBalance > 0) { "موجودی اولیه باید مثبت باشد" }
        val closed = outcomes.filter { it.pnlUsd.isFinite() }.sortedBy { it.exitAt }
        val wins = closed.filter { it.pnlUsd > 0 }
        val losses = closed.filter { it.pnlUsd <= 0 } // breakeven counts as non-win
        val grossWin = wins.sumOf { it.pnlUsd }
        val grossLoss = -losses.sumOf { it.pnlUsd }
        val durations = closed.mapNotNull { (it.exitAt - it.entryAt).takeIf { ms -> ms > 0 } }
        val rValues = closed.mapNotNull { it.rMultiple?.takeIf { value -> value.isFinite() } }
        var bestWin = 0
        var bestLoss = 0
        var consecutiveWins = 0
        var consecutiveLosses = 0
        var equity = startingBalance
        var peak = equity
        var drawdown = 0.0
        val returns = mutableListOf<Double>()
        for (outcome in closed) {
            // Zero/negative equity makes a percentage return undefined, not zero.
            if (equity > 0) returns += outcome.pnlUsd / equity
            equity += outcome.pnlUsd
            peak = maxOf(peak, equity)
            drawdown = maxOf(drawdown, (peak - equity) / peak * 100)
            if (outcome.pnlUsd > 0) {
                consecutiveWins++
                consecutiveLosses = 0
            } else {
                consecutiveLosses++
                consecutiveWins = 0
            }
            bestWin = maxOf(bestWin, consecutiveWins)
            bestLoss = maxOf(bestLoss, consecutiveLosses)
        }
        val mean = returns.takeIf { it.size >= 2 }?.average()
        val variance = mean?.let { avg -> returns.sumOf { (it - avg) * (it - avg) } / (returns.size - 1) }
        val sharpe = if (returns.size == closed.size && mean != null && variance != null && variance > 0) mean / sqrt(variance) else null
        return PerformanceReport(
            total = closed.size,
            wins = wins.size, losses = losses.size,
            longCount = closed.count { it.side == SignalAction.BUY },
            shortCount = closed.count { it.side == SignalAction.SELL },
            winRatePct = if (closed.isEmpty()) null else wins.size * 100.0 / closed.size,
            netPnlUsd = closed.sumOf { it.pnlUsd },
            grossProfitUsd = grossWin, grossLossUsd = grossLoss,
            averageWinUsd = wins.takeIf { it.isNotEmpty() }?.map { it.pnlUsd }?.average(),
            averageLossUsd = losses.takeIf { it.isNotEmpty() }?.map { it.pnlUsd }?.average(),
            profitFactor = if (grossLoss > 0) grossWin / grossLoss else null,
            tradeSharpe = sharpe,
            expectancyR = rValues.takeIf { it.isNotEmpty() }?.average(),
            averageDurationMillis = durations.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            longestWinningStreak = bestWin, longestLosingStreak = bestLoss,
            maxDrawdownPct = if (closed.isEmpty()) null else drawdown,
        )
    }
}
