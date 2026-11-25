package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class AssistantEvidenceReviewTest {
    private val h = 3_600_000L
    private fun trade(i: Int, correct: Boolean = true): PaperTrade {
        val opened = (i + 2L) * h
        val won = i % 2 == 0
        val p = if (won == correct) 0.9 else 0.1
        return PaperTrade(id = "t$i", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0,
            openedAt = opened, closedAt = opened + 1000, pnlUsd = if (won) 1.0 else -1.0,
            origin = PaperTrade.ORIGIN_LIVE,
            neuralEntryObservation = NeuralEntryObservation(1, opened - 1, opened - 2,
                List(24) { 0.0 }, "actual-key", p, 0.0))
    }
    @Test fun insufficientDataKeepsBaseline() {
        val report = AssistantEvidenceReview.evaluate(List(20) { trade(it) }, 1000*h)
        assertEquals(AssistantEvidenceReview.Source.BASELINE, report.selected)
        assertEquals(0, report.folds.size)
        assertNull(report.currentBaseline)
    }
    @Test fun onlyConsistentlyBetterRegisteredPredictionsSelectNeural() {
        val samples = List(200) { trade(it) }
        val report = AssistantEvidenceReview.evaluate(samples, 1000*h)
        assertEquals(3, report.folds.size)
        assertEquals(AssistantEvidenceReview.Source.NEURAL, report.selected)
        assertTrue(report.folds.all { it.train >= 30 && it.test >= 30 && it.gain >= 0.01 })
        val poor = AssistantEvidenceReview.evaluate(List(200) { trade(it, false) }, 1000*h)
        assertEquals(AssistantEvidenceReview.Source.BASELINE, poor.selected)
    }
    @Test fun filtersRecoveryFutureBacktestsMissingObservationsAndDuplicates() {
        val a = trade(0)
        val report = AssistantEvidenceReview.evaluate(listOf(a, a.copy(id = "duplicate"),
            trade(1).copy(origin = PaperTrade.ORIGIN_BACKTEST),
            trade(2).copy(exitReason = "بازیابی ساعات قطعی"),
            trade(3).copy(neuralEntryObservation = null),
            trade(4).copy(closedAt = 1001*h)), 1000*h)
        assertEquals(1, report.eligible)
    }
    @Test fun labelsStillOpenAtEmbargoAreNeverTrainingLabels() {
        val trades = List(200) { i -> trade(i).let { if (i < 50) it.copy(closedAt = 900*h) else it } }
        val report = AssistantEvidenceReview.evaluate(trades, 1000*h)
        assertTrue(report.folds.size < 3)
        assertEquals(AssistantEvidenceReview.Source.BASELINE, report.selected)
    }
}
