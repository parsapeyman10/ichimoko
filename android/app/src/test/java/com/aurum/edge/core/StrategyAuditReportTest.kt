package com.aurum.edge.core

import org.junit.Assert.*
import org.junit.Test

class StrategyAuditReportTest {
    @Test fun separatesSavedResolvedAndProposedWithoutClaimingHistoricalBaseline() {
        val id = BitcoinStrategyModel.BASE_ICHIMOKU.id
        val saved = BaseStrategyConfig(perStrategyOverrides = mapOf(id to PerStrategyConfigOverride(strategyId = id, slBufferAtr = 0.35)))
        val profile = PerStrategyNeuralTuningProfile(strategyId = id, strategyTitleFa = "test", strategyBadgeFa = "test", slBufferAtr = 0.40, tuningGeneration = 3)
        val summary = NeuralLearningStateSummary(autoTunedParameters = NeuralAutoTunedParameters(perStrategyProfiles = mapOf(id to profile)))
        val result = StrategyAuditReport.build(AppSettings(baseStrategyConfig = saved), summary).first { it.id == id }
        val row = result.parameters.first { it.key == "slBufferAtr" }
        assertFalse(result.baselineKnown)
        assertEquals("0.25", row.reference)
        assertEquals("0.25", row.saved)
        assertEquals("0.35", row.resolved)
        assertEquals("0.4", row.neuralProfile)
        assertEquals(3, result.neuralProfile!!.tuningGeneration)
    }
    @Test fun includesBuiltInAndCustomAndNeverInventsCustomDefaults() {
        val custom = CustomStrategySlot(id = "CUSTOM", titleFa = "سفارشی", summaryFa = "test")
        val all = StrategyAuditReport.build(AppSettings(customStrategies = listOf(custom), selectedBitcoinStrategyId = custom.id), NeuralLearningStateSummary())
        assertEquals(BitcoinStrategyModel.entries.size + 1, all.size)
        val row = all.first { it.id == custom.id }
        assertTrue(row.enabled)
        assertNull(row.referenceDefaults)
        assertFalse(row.baselineKnown)
        assertNull(row.neuralProfile)
    }
}
