package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class NeuralTemporalValidationTest {
    private fun sample(id: String, start: Long, end: Long, pnl: Double = 1.0, origin: String = PaperTrade.ORIGIN_LIVE, reason: String? = null): NeuralSelfLearningEngine.JournalTrainingSample {
        val t = PaperTrade(id = id, symbol = "XAU/USD", interval = Interval.M15, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0,
            openedAt = start, closedAt = end, pnlUsd = pnl, origin = origin, exitReason = reason)
        return NeuralSelfLearningEngine.JournalTrainingSample(t, List(28) { 0.0 }, "key", "", pnl > 0, "", end)
    }

    @Test
    fun `training labels must be closed before embargo and timestamp ties never straddle boundary`() {
        val h = 3_600_000L
        val samples = listOf(sample("old", h, 2*h), sample("lateClose", 2*h, 6*h),
            sample("tiedA", 5*h, 7*h), sample("tiedB", 5*h, 8*h), sample("next", 9*h, 10*h))
        val split = NeuralTemporalValidation.split(samples, 2, 5)!!
        assertEquals(listOf("old"), split.training.map { it.trade.id })
        assertEquals(listOf("tiedA", "tiedB", "next"), split.test.map { it.trade.id })
        assertTrue(split.training.all { it.trade.closedAt!! <= split.testStart - h })
        assertNull(NeuralTemporalValidation.split(samples, 2, 3)) // end boundary coincides with tied entries
    }

    @Test
    fun `evaluation excludes unknown origin invalid times zero outcomes recovery and economic duplicates`() {
        val valid = sample("a", 1L, 2L)
        val sameEconomics = sample("b", 1L, 2L)
        val candidates = listOf(valid, sameEconomics, sample("future", 2, 100), sample("flat", 1, 2, 0.0),
            sample("unknown", 1, 2, origin = "UNKNOWN"), sample("bt", 1, 2, origin = PaperTrade.ORIGIN_BACKTEST),
            sample("recovered", 1, 2, reason = "بازیابی ساعات قطعی"), sample("invalid", 5, 4))
        assertEquals(listOf("a"), NeuralTemporalValidation.eligible(candidates, 10).map { it.trade.id })
    }

    @Test
    fun `walk forward baseline uses training rate rather than test outcomes`() {
        val points = List(20) { NeuralCalibrationPoint(predictedWinProb = 0.7, won = true) }
        val report = NeuralCalibration.evaluateWalkForward(listOf(points), trainingWinRates = listOf(0.2), trainingSizes = listOf(50))
        assertEquals(0.64, report.meanBrierBaseline, 0.001)
        assertEquals(50, report.folds.single().trainSamples)
        val oos = NeuralCalibration.evaluate(points, baselineProbability = 0.2)
        assertEquals(0.64, oos.brierBaseline, 0.001)
    }
}
