package com.aurum.edge.core

import kotlinx.serialization.Serializable

/** Paired, pre-outcome diagnostic experiment. Does not place or filter orders. */
object AssistantProspectiveExperiment {
    const val EMBARGO_MS = 3_600_000L
    const val CAPTURE_MAX_DELAY_MS = 60_000L
    const val MIN_HISTORY = 30
    const val MIN_RESOLVED = 30
    const val WINDOW = 50
    const val MAX_RECORDS = 500
    const val PROMOTION_GAIN = 0.01

    @Serializable
    data class Record(
        val tradeId: String,
        val openedAt: Long,
        val capturedAt: Long,
        val historicalCount: Int,
        val baseline: Double,
        val neural: Double,
        val closedAt: Long? = null,
        val pnl: Double? = null,
        val invalid: Boolean = false,
    )
    data class Result(val count: Int, val neuralBrier: Double?, val baselineBrier: Double?, val proposed: AssistantEvidenceReview.Source, val reasonFa: String)

    private fun eligibleClosed(t: PaperTrade, before: Long): Boolean =
        t.origin == PaperTrade.ORIGIN_LIVE && !t.isBacktest && t.openedAt > 0 &&
            t.closedAt?.let { it > t.openedAt && it <= before } == true &&
            t.pnlUsd?.let { it.isFinite() && it != 0.0 } == true &&
            t.exitReason?.contains("بازیابی ساعات قطعی") != true

    /** Only live, open trades seen immediately after entry. Never reconstruct a missing pre-entry sample. */
    fun update(records: List<Record>, trades: List<PaperTrade>, now: Long): List<Record> {
        val byId = trades.associateBy { it.id }
        val updated = records.map { record ->
            val t = byId[record.tradeId]
            when {
                record.invalid -> record
                t == null || t.openedAt != record.openedAt || t.origin != PaperTrade.ORIGIN_LIVE || t.isBacktest -> record.copy(invalid = true)
                record.closedAt != null && (t.closedAt != record.closedAt || t.pnlUsd != record.pnl ||
                    !eligibleClosed(t, now)) -> record.copy(invalid = true)
                record.closedAt != null || t.isOpen -> record
                !eligibleClosed(t, now) -> record.copy(invalid = true)
                else -> record.copy(closedAt = t.closedAt, pnl = t.pnlUsd)
            }
        }.toMutableList()
        val known = updated.mapTo(hashSetOf()) { it.tradeId }
        for (t in trades) {
            if (t.id in known || !t.isOpen || t.origin != PaperTrade.ORIGIN_LIVE || t.isBacktest ||
                t.openedAt <= 0 || now < t.openedAt || now - t.openedAt > CAPTURE_MAX_DELAY_MS) continue
            val observation = t.neuralEntryObservation?.takeIf { it.isUsableFor(t.openedAt) } ?: continue
            val past = trades.filter { eligibleClosed(it, t.openedAt - EMBARGO_MS) }
                .distinctBy { it.id }
                .distinctBy { listOf(it.symbol, it.strategyId, it.interval, it.action, it.openedAt, it.closedAt, it.entry, it.exitPrice, it.pnlUsd) }
            if (past.size < MIN_HISTORY) continue
            val baseline = past.count { (it.pnlUsd ?: 0.0) > 0 }.toDouble() / past.size
            updated += Record(t.id, t.openedAt, now, past.size, baseline, observation.predictedWinProbability)
            known.add(t.id)
        }
        return updated.takeLast(MAX_RECORDS)
    }

    /** Rolling score is exploratory sequential monitoring, not an independent trial of profitability. */
    fun evaluate(records: List<Record>, previous: AssistantEvidenceReview.Source): Result {
        val resolved = records.filter { !it.invalid && it.closedAt != null && it.pnl != null && it.pnl.isFinite() && it.pnl != 0.0 &&
            it.capturedAt >= it.openedAt && it.capturedAt - it.openedAt <= CAPTURE_MAX_DELAY_MS &&
            it.capturedAt < it.closedAt && it.baseline in 0.0..1.0 && it.neural in 0.0..1.0 }
            .sortedBy { it.closedAt }.takeLast(WINDOW)
        if (resolved.size < MIN_RESOLVED) return Result(resolved.size, null, null, AssistantEvidenceReview.Source.BASELINE,
            "در انتظار حداقل ۳۰ معاملهٔ آینده‌نگر معتبر؛ انتخاب پایه است.")
        val n = resolved.map { val d = it.neural - if (it.pnl!! > 0) 1.0 else 0.0; d * d }.average()
        val b = resolved.map { val d = it.baseline - if (it.pnl!! > 0) 1.0 else 0.0; d * d }.average()
        val gain = b - n
        val selected = when (previous) {
            AssistantEvidenceReview.Source.BASELINE -> if (gain >= PROMOTION_GAIN) AssistantEvidenceReview.Source.NEURAL else AssistantEvidenceReview.Source.BASELINE
            AssistantEvidenceReview.Source.NEURAL -> if (gain < 0.0) AssistantEvidenceReview.Source.BASELINE else AssistantEvidenceReview.Source.NEURAL
        }
        val reason = if (selected == AssistantEvidenceReview.Source.NEURAL)
            "شبکه در پنجرهٔ آینده‌نگر برای گزارش انتخاب شد؛ این آزمون پیوسته و اکتشافی است."
        else "پایه در گزارش فعال است؛ شواهد کافی برای انتخاب یا حفظ شبکه وجود ندارد."
        return Result(resolved.size, n, b, selected, reason)
    }
}
