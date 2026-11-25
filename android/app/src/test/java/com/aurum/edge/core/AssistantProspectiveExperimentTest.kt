package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class AssistantProspectiveExperimentTest {
    private val h = 3_600_000L
    private fun trade(id: String, start: Long, closed: Long?, pnl: Double?, prob: Double = 0.8) = PaperTrade(
        id = id, symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0,
        openedAt = start, closedAt = closed, pnlUsd = pnl, origin = PaperTrade.ORIGIN_LIVE,
        neuralEntryObservation = NeuralEntryObservation(1, start - 1, start - 2, List(24) { 0.0 }, "key", prob, 0.0))
    private fun history() = List(40) { i -> trade("past$i", (i + 1L)*h, (i + 1L)*h + 100, if (i % 2 == 0) 1.0 else -1.0) }

    @Test fun capturesOnlyOpenRecentValidBeforeOutcomeAndFreezesBaseline() {
        val start = 100*h
        val opened = trade("new", start, null, null)
        val first = AssistantProspectiveExperiment.update(emptyList(), history() + opened, start + 1000)
        assertEquals(1, first.size)
        assertEquals(0.5, first.single().baseline, 0.00001)
        val closed = opened.copy(closedAt = start + 5000, pnlUsd = 3.0)
        val after = AssistantProspectiveExperiment.update(first, history() + closed, start + 6000)
        assertEquals(3.0, after.single().pnl!!, 0.0)
        assertEquals(0.5, after.single().baseline, 0.0)
        assertTrue(AssistantProspectiveExperiment.update(emptyList(), history() + closed, start + 6000).isEmpty())
        assertTrue(AssistantProspectiveExperiment.update(emptyList(), history() + opened, start + 61_000).isEmpty())
        assertTrue(AssistantProspectiveExperiment.update(emptyList(), history().take(29) + opened, start + 1000).isEmpty())
    }

    @Test fun temporalEmbargoAndExcludedOutcomesDoNotInventObservations() {
        val start = 100*h
        val open = trade("new", start, null, null)
        val tooRecent = List(35) { i -> trade("p$i", start - h, start - h + 100, 1.0) }
        assertTrue(AssistantProspectiveExperiment.update(emptyList(), tooRecent + open, start + 1000).isEmpty())
        val first = AssistantProspectiveExperiment.update(emptyList(), history() + open, start + 1000)
        val recovered = open.copy(closedAt = start + 5000, pnlUsd = -1.0, exitReason = "بازیابی ساعات قطعی")
        assertTrue(AssistantProspectiveExperiment.update(first, history() + recovered, start + 6000).single().invalid)
    }

    @Test fun promotesOnlyWithThirtyAndRollsBackOnNegativeGain() {
        fun records(neural: Double, size: Int) = List(size) { i ->
            AssistantProspectiveExperiment.Record("$i", i*10L + 1, i*10L + 2, 40, 0.5, neural,
                i*10L + 3, 1.0)
        }
        assertEquals(AssistantEvidenceReview.Source.BASELINE, AssistantProspectiveExperiment.evaluate(records(0.9, 29), AssistantEvidenceReview.Source.BASELINE).proposed)
        val success = AssistantProspectiveExperiment.evaluate(records(0.9, 30), AssistantEvidenceReview.Source.BASELINE)
        assertEquals(AssistantEvidenceReview.Source.NEURAL, success.proposed)
        assertEquals(0.01, success.neuralBrier!!, 0.000001)
        val failure = AssistantProspectiveExperiment.evaluate(records(0.1, 30), AssistantEvidenceReview.Source.NEURAL)
        assertEquals(AssistantEvidenceReview.Source.BASELINE, failure.proposed)
    }

    @Test fun changedOrRemovedOutcomeInvalidatesRecord() {
        val start = 100*h
        val open = trade("new", start, null, null)
        val first = AssistantProspectiveExperiment.update(emptyList(), history() + open, start + 1000)
        val closed = open.copy(closedAt = start + 5000, pnlUsd = 1.0)
        val second = AssistantProspectiveExperiment.update(first, history() + closed, start + 6000)
        assertTrue(AssistantProspectiveExperiment.update(second, history() + closed.copy(pnlUsd = -1.0), start + 7000).single().invalid)
        assertTrue(AssistantProspectiveExperiment.update(second, history(), start + 7000).single().invalid)
    }
}
