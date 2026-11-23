package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction
import kotlin.math.abs

/** In-memory, descriptive matching of past entry snapshots; never a trade veto or win guarantee. */
object EntryQualityEvidence {
    const val MIN_SAMPLES = 30
    const val MAX_PRIORITY_POINTS = 10.0
    data class Evidence(val samples: Int, val wins: Int, val meanR: Double?, val priorityAdjustment: Double) {
        fun textFa(): String = if (meanR == null) "شواهد ترکیبی کافی نیست ($samples نمونه؛ حداقل $MIN_SAMPLES)"
        else "شواهد ترکیبی $samples نمونه · $wins برد · میانگین ${"%+.2f".format(java.util.Locale.US, meanR)}R · صرفاً اولویت تجربی، نه منع یا تضمین"
    }
    private fun alignment(b: Int, n: Int, s: Int, action: SignalAction): Int? {
        if (b < 0 || n < 0 || s < 0 || b + n + s <= 0) return null
        val sign = when (action) { SignalAction.BUY -> 1; SignalAction.SELL -> -1; else -> return null }
        return ((b - s) * sign).compareTo(0)
    }
    fun evaluate(trades: List<PaperTrade>, symbol: String, strategy: String, action: SignalAction, now: Long, h1: TechnicalRating?, h4: TechnicalRating?): Evidence {
        val a = h1?.let { alignment(it.buy, it.neutral, it.sell, action) }
        val b = h4?.let { alignment(it.buy, it.neutral, it.sell, action) }
        if (now <= 0 || a == null || b == null) return Evidence(0, 0, null, 0.0)
        val session = NeuralStateFeatures.sessionCodeOf(now)
        val sym = V1Universe.normalize(symbol)
        val strat = BitcoinStrategyModel.normalizeStrategyId(strategy)
        val sample = trades.filter { t ->
            t.closedAt != null && t.closedAt < now && t.openedAt > 0 && t.openedAt <= t.closedAt && t.origin == PaperTrade.ORIGIN_LIVE && !t.techSnapshotRebuilt &&
                t.action == action && V1Universe.normalize(t.symbol) == sym && BitcoinStrategyModel.normalizeStrategyId(t.strategyId) == strat &&
                NeuralStateFeatures.sessionCodeOf(t.openedAt) == session &&
                alignment(t.tech1hBuy, t.tech1hNeutral, t.tech1hSell, action) == a &&
                alignment(t.tech4hBuy, t.tech4hNeutral, t.tech4hSell, action) == b &&
                t.pnlUsd?.isFinite() == true && t.fixedRiskUsd.isFinite() && t.fixedRiskUsd > 0 &&
                t.exitReason?.contains("بازیابی ساعات قطعی") != true
        }.distinctBy { t -> listOf(t.symbol, t.strategyId, t.interval, t.action, t.openedAt, t.closedAt, t.entry, t.exitPrice, t.pnlUsd) }
        val rs = sample.mapNotNull { t -> (t.pnlUsd!! / t.fixedRiskUsd).takeIf { it.isFinite() } }
        if (rs.size < MIN_SAMPLES) return Evidence(rs.size, sample.count { (it.pnlUsd ?: 0.0) > 0 }, null, 0.0)
        val mean = rs.average()
        val confidence = rs.size.toDouble() / (rs.size + MIN_SAMPLES)
        return Evidence(rs.size, sample.count { (it.pnlUsd ?: 0.0) > 0 }, mean, MAX_PRIORITY_POINTS * confidence * (mean / (1 + abs(mean))))
    }
}
