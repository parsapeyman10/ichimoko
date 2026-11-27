package com.aurum.edge.core

/** Conservative retrospective validation only; never deletes or changes journal trades. */
object NeuralTemporalValidation {
    const val EMBARGO_MS = 3_600_000L
    data class Split(
        val training: List<NeuralSelfLearningEngine.JournalTrainingSample>,
        val test: List<NeuralSelfLearningEngine.JournalTrainingSample>,
        val testStart: Long,
    )

    fun eligible(samples: List<NeuralSelfLearningEngine.JournalTrainingSample>, now: Long): List<NeuralSelfLearningEngine.JournalTrainingSample> =
        samples.filter { s ->
            val t = s.trade
            val end = t.closedAt
            t.origin == PaperTrade.ORIGIN_LIVE && t.openedAt > 0 && end != null && end > t.openedAt && end <= now &&
                t.pnlUsd?.let { it.isFinite() && it != 0.0 } == true &&
                t.exitReason?.contains("بازیابی ساعات قطعی") != true
        }.sortedWith(compareBy({ it.trade.openedAt }, { it.trade.id }))
            // Identical economics can be a duplicate; exclude only from this sensitivity evaluation.
            .distinctBy { s -> s.trade.let { t -> listOf(t.symbol, t.strategyId, t.interval, t.action, t.openedAt, t.closedAt, t.entry, t.exitPrice, t.pnlUsd) } }

    /** Splits by entry timestamps. All labels in training are known at least one hour before the first test entry. */
    fun split(samples: List<NeuralSelfLearningEngine.JournalTrainingSample>, startIndex: Int, endIndex: Int): Split? {
        if (startIndex !in 1 until samples.size || endIndex <= startIndex || endIndex > samples.size) return null
        val start = samples[startIndex].trade.openedAt
        val end = if (endIndex < samples.size) samples[endIndex].trade.openedAt else Long.MAX_VALUE
        if (end <= start) return null // Do not split trades with the same entry timestamp across folds.
        val test = samples.filter { it.trade.openedAt >= start && it.trade.openedAt < end }
        val train = samples.filter { (it.trade.closedAt ?: Long.MAX_VALUE) <= start - EMBARGO_MS }
            .sortedBy { it.trade.closedAt }
        if (train.isEmpty() || test.isEmpty()) return null
        return Split(train, test, start)
    }
}
