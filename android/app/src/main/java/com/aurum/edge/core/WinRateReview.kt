package com.aurum.edge.core

/** Observational review only; does not alter model parameters or trading policy. */
object WinRateReview {
    const val MIN_SAMPLES = 30
    const val TARGET_PCT = 65.0
    data class Result(val wins: Int, val losses: Int, val flat: Int, val netUsd: Double) {
        val samples: Int get() = wins + losses
        val ratePct: Double? get() = if (samples == 0) null else wins * 100.0 / samples
        val needsReview: Boolean get() = samples >= MIN_SAMPLES && (ratePct ?: 0.0) < TARGET_PCT
    }
    fun calculate(trades: List<PaperTrade>): Result {
        val pnl = trades.asSequence().filter { !it.isOpen && !it.isBacktest }
            .mapNotNull { it.pnlUsd?.takeIf { value -> value.isFinite() } }.toList()
        return Result(pnl.count { it > 0.0 }, pnl.count { it < 0.0 }, pnl.count { it == 0.0 }, pnl.sum())
    }
}
