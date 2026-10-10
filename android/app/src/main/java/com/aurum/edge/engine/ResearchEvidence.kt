package com.aurum.edge.engine

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.data.FOREX_CALENDAR_SOURCE_URL

/** Caution labels, not a statistical test, trading advice or an AI-news verdict. */
enum class EvidenceGrade { NO_DATA, LIMITED, UNFAVORABLE, COST_SENSITIVE, PRELIMINARY }

data class EvidenceAssessment(val grade: EvidenceGrade, val title: String, val detail: String)

data class PaperCostWhatIf(
    val trades: Int,
    val rawPnlUsd: Double,
    val estimatedCostUsd: Double,
    val afterAssumedCostUsd: Double,
    val afterDoubleCostUsd: Double,
)

object ResearchEvidence {
    /** Heuristic caution threshold only. Thirty is NOT statistical significance or a profit guarantee. */
    const val CAUTION_MIN_CLOSED = 30

    /** Recorded evidence, not a fresh verification of a historical publisher or AI model. */
    fun hasRecordedSignalEvidence(trade: PaperTrade): Boolean {
        val bar = trade.signalBarTime ?: return false
        val news = trade.newsEvidence
        val ict = trade.priceAction
        val technicalConditions = trade.entryConditions.filterNot { it.name == NewsConfluence.NEWS_LABEL }
        val newsEvidenceOk = news == null || (
            news.model.isNotBlank() && news.model != "deterministic-fallback" &&
                news.confidence in 80.0..100.0 &&
                news.checkedAt > 0L && trade.openedAt - news.checkedAt in 0L..180_000L &&
                news.calendarSource == FOREX_CALENDAR_SOURCE_URL &&
                news.calendarCheckedAt?.let { trade.openedAt - it in 0L..1_200_000L } == true &&
                news.evidence.isNotEmpty() && news.evidence.all { it.id.isNotBlank() &&
                    it.source.isNotBlank() && it.url.startsWith("https://") &&
                    news.checkedAt - it.publishedAt in 0L..10_800_000L })
        val common = trade.action != SignalAction.NO_TRADE && bar > 0L && newsEvidenceOk &&
            TechnicalEvidence.confirmedRecords(technicalConditions)
        if (technicalConditions.size == TechnicalEvidence.CURRENT_COUNT)
            return common && trade.confidence in 60.0..100.0 && trade.initialStopLoss != null
        // Legacy 7/8-condition records are readable, not used as authorization for V1 entry.
        return common && trade.symbol == "XAU/USD" && trade.unit == "oz" &&
            trade.mtf?.let { !it.veto && it.barTime == bar } == true &&
            ict?.let { it.symbol == trade.symbol && it.action == trade.action && it.barTime == bar &&
                trade.openedAt - it.checkedAt in 0L..180_000L } == true
    }

    /** Compatibility for callers reading eight-condition historical journal records. */
    fun hasRecordedNineWay(trade: PaperTrade): Boolean = hasRecordedSignalEvidence(trade)

    /** A hypothetical deduction from recorded paper P/L, not a broker fill or a journal edit. */
    fun paperCostWhatIf(trades: List<PaperTrade>, spread: Double, commissionPerOz: Double): PaperCostWhatIf? {
        if (!spread.isFinite() || spread < 0.0 || !commissionPerOz.isFinite() || commissionPerOz < 0.0) return null
        val settled = trades.filter { hasRecordedSignalEvidence(it) && !it.isOpen && it.pnlUsd?.isFinite() == true &&
            it.positionOz.isFinite() && it.positionOz > 0.0 }
        if (settled.isEmpty()) return null
        val gross = settled.sumOf { it.pnlUsd!! }
        val assumedCost = settled.sumOf { (spread + commissionPerOz * 2.0) * it.positionOz }
        if (!gross.isFinite() || !assumedCost.isFinite()) return null
        return PaperCostWhatIf(settled.size, gross, assumedCost, gross - assumedCost,
            gross - assumedCost * 2.0)
    }
}
