package com.aurum.edge.core

/**
 * Compare only predictions actually recorded at entry. Diagnostic selection only: it never gates
 * trades or changes the five Ichimoku rules, risk, stops, or execution. Retrospective, not causal.
 */
object AssistantEvidenceReview {
    const val MIN_TRAIN = 30
    const val MIN_TEST = 30
    const val FOLDS = 3
    const val EMBARGO_MS = 3_600_000L
    const val REQUIRED_GAIN = 0.01
    enum class Source { BASELINE, NEURAL }
    data class Fold(val train: Int, val test: Int, val baseline: Double, val neuralBrier: Double, val baselineBrier: Double) {
        val gain: Double get() = baselineBrier - neuralBrier
    }
    data class Report(val eligible: Int, val folds: List<Fold>, val selected: Source, val currentBaseline: Double?, val reasonFa: String)

    fun evaluate(trades: List<PaperTrade>, now: Long): Report {
        val valid = trades.filter { t ->
            val end = t.closedAt
            t.origin == PaperTrade.ORIGIN_LIVE && t.openedAt > 0 && end != null && end > t.openedAt && end <= now &&
                t.pnlUsd?.let { it.isFinite() && it != 0.0 } == true &&
                t.exitReason?.contains("بازیابی ساعات قطعی") != true &&
                t.neuralEntryObservation?.isUsableFor(t.openedAt) == true
        }.sortedWith(compareBy<PaperTrade> { it.openedAt }.thenBy { it.id })
            .distinctBy { it.id }
            .distinctBy { t -> listOf(t.symbol, t.strategyId, t.interval, t.action, t.openedAt, t.closedAt, t.entry, t.exitPrice, t.pnlUsd) }
        val current = valid.filter { (it.closedAt ?: Long.MAX_VALUE) <= now - EMBARGO_MS }
        val baseline = if (current.size >= MIN_TRAIN) current.count { (it.pnlUsd ?: 0.0) > 0 }.toDouble() / current.size else null
        val folds = mutableListOf<Fold>()
        if (valid.size >= MIN_TRAIN + FOLDS * MIN_TEST) {
            for (k in 1..FOLDS) {
                val startIndex = valid.size * k / (FOLDS + 1)
                val endIndex = valid.size * (k + 1) / (FOLDS + 1)
                val start = valid[startIndex].openedAt
                val end = if (endIndex < valid.size) valid[endIndex].openedAt else Long.MAX_VALUE
                if (end <= start) continue
                val train = valid.filter { (it.closedAt ?: Long.MAX_VALUE) <= start - EMBARGO_MS }
                val test = valid.filter { it.openedAt >= start && it.openedAt < end }
                if (train.size < MIN_TRAIN || test.size < MIN_TEST) continue
                val base = train.count { (it.pnlUsd ?: 0.0) > 0 }.toDouble() / train.size
                val brierN = test.map { t ->
                    val d = t.neuralEntryObservation!!.predictedWinProbability - if ((t.pnlUsd ?: 0.0) > 0) 1.0 else 0.0
                    d * d
                }.average()
                val brierB = test.map { t -> val d = base - if ((t.pnlUsd ?: 0.0) > 0) 1.0 else 0.0; d * d }.average()
                folds += Fold(train.size, test.size, base, brierN, brierB)
            }
        }
        val selected = if (folds.size == FOLDS && folds.all { it.gain >= REQUIRED_GAIN }) Source.NEURAL else Source.BASELINE
        val reason = when {
            folds.size < FOLDS -> "نمونه یا پوشش زمانی کافی نیست؛ برتری شبکه ثابت نشده است."
            selected == Source.NEURAL -> "شبکه در هر سه بخش زمانی حداقل ۰٫۰۱ Brier بهتر از پایه بود؛ ارزیابی پس‌نگر است."
            else -> "شبکه در هر سه بخش حداقل بهبود ۰٫۰۱ Brier نداشت؛ پایه برای گزارش انتخاب شد."
        }
        return Report(valid.size, folds, selected, baseline, reason)
    }
}
