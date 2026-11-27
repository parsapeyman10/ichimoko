package com.aurum.edge.core

/** Historical preference per symbol and strategy. Unknown or thin samples never block. */
object SessionEntryPolicy {
    fun preferred(trades: List<PaperTrade>, symbol: String, strategy: String, now: Long): SessionPerformanceReport.Group? {
        val rows = trades.filter {
            !it.isOpen && !it.isBacktest && it.closedAt != null && it.closedAt <= now &&
                V1Universe.normalize(it.symbol) == V1Universe.normalize(symbol) &&
                BitcoinStrategyModel.normalizeStrategyId(it.strategyId) == BitcoinStrategyModel.normalizeStrategyId(strategy)
        }
        val groups = SessionPerformanceReport.build(rows).bySession.filter {
            it.key.isNotEmpty() && it.rSamples >= SessionPerformanceReport.MIN_R_SAMPLES && it.meanR != null
        }
        if (groups.size < 2) return null
        return groups.filter { (it.meanR ?: 0.0) > 0.0 }.maxByOrNull { it.meanR ?: Double.NEGATIVE_INFINITY }
    }
}
