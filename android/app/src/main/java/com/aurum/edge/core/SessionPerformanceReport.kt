package com.aurum.edge.core

import kotlinx.serialization.Serializable

/** Descriptive statistics from closed, non-backtest paper trades; no trading decisions. */
object SessionPerformanceReport {
    const val MIN_R_SAMPLES = 30

    @Serializable
    data class Group(
        val key: String,
        val labelFa: String,
        val count: Int,
        val wins: Int,
        val losses: Int,
        val breakeven: Int,
        val netUsd: Double,
        val meanR: Double?,
        val rSamples: Int,
        val profitFactor: Double?,
    ) {
        val winRatePct: Double? get() = if (wins + losses > 0) wins * 100.0 / (wins + losses) else null
    }

    @Serializable
    data class Report(val byStrategy: List<Group>, val bySymbol: List<Group>, val bySession: List<Group>, val bestSession: Group?)

    fun sessionLabel(code: String): String = when (code) {
        "ASIA" -> "آسیا ۰۰–۰۷ UTC"
        "LONDON" -> "لندن ۰۷–۱۲ UTC"
        "LONDON_NY" -> "هم‌پوشانی لندن/نیویورک ۱۲–۱۶ UTC"
        "NEW_YORK" -> "نیویورک ۱۶–۲۱ UTC"
        "LATE_NY" -> "پایان نیویورک ۲۱–۲۴ UTC"
        else -> "زمان نامعلوم"
    }

    fun build(trades: List<PaperTrade>): Report {
        val rows = trades.filter { !it.isOpen && !it.isBacktest && it.pnlUsd?.isFinite() == true }
        fun group(key: (PaperTrade) -> String, label: (String) -> String): List<Group> =
            rows.groupBy(key).map { (k, ts) ->
                val pnl = ts.mapNotNull { it.pnlUsd }
                val r = ts.mapNotNull { t -> t.fixedRiskUsd.takeIf { it.isFinite() && it > 0.0 }?.let { t.pnlUsd!! / it } }
                    .filter { it.isFinite() }
                val grossWin = pnl.filter { it > 0 }.sum()
                val grossLoss = -pnl.filter { it < 0 }.sum()
                Group(k, label(k), pnl.size, pnl.count { it > 0 }, pnl.count { it < 0 }, pnl.count { it == 0.0 },
                    pnl.sum(), r.takeIf { it.isNotEmpty() }?.average(), r.size,
                    if (grossLoss > 0.0) grossWin / grossLoss else null)
            }.sortedBy { it.labelFa }
        val byStrategy = group({ it.strategyId.ifBlank { "نامعلوم" } }, { k ->
            rows.firstOrNull { it.strategyId == k }?.strategyTitleFa?.takeIf { it.isNotBlank() } ?: k
        })
        val bySymbol = group({ V1Universe.normalize(it.symbol).ifBlank { "نامعلوم" } }, { it })
        val bySession = group({ if (it.openedAt > 0) NeuralStateFeatures.sessionCodeOf(it.openedAt) else "" }, ::sessionLabel)
        val best = bySession.filter { it.key.isNotEmpty() && it.rSamples >= MIN_R_SAMPLES && (it.meanR ?: 0.0) > 0.0 }
            .maxWithOrNull(compareBy<Group> { it.meanR ?: Double.NEGATIVE_INFINITY }.thenBy { it.rSamples })
        return Report(byStrategy, bySymbol, bySession, best)
    }
}
