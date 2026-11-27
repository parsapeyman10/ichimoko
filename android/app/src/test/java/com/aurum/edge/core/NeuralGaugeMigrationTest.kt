package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NeuralGaugeMigrationTest {
    @Test
    fun `gauge balances distinguish unknown from neutral and reverse for sell`() {
        val neutral = TechnicalRating(3, 2, 3)
        val buy = TechnicalRating(6, 2, 2)
        assertEquals(listOf(0.0, 1.0, 0.4, 1.0), NeuralGaugeFeatures.fromRatings(SignalAction.BUY, neutral, buy))
        assertEquals(listOf(-0.0, 1.0, -0.4, 1.0), NeuralGaugeFeatures.fromRatings(SignalAction.SELL, neutral, buy))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), NeuralGaugeFeatures.fromRatings(SignalAction.BUY, TechnicalRating.UNKNOWN, TechnicalRating(-1, 2, 3)))
        val trade = PaperTrade(id = "t", symbol = "XAU/USD", interval = Interval.M15, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0, openedAt = 1L,
            tech1hBuy = 6, tech1hNeutral = 2, tech1hSell = 2, techSnapshotRebuilt = true)
        assertEquals(List(4) { 0.0 }, NeuralGaugeFeatures.fromTrade(trade))
        assertEquals(listOf(0.4, 1.0, 0.0, 0.0), NeuralGaugeFeatures.fromTrade(trade.copy(techSnapshotRebuilt = false)))
    }

    @Test
    fun `both legacy dimensions retain weights profiles and replay without inventing gauge history`() {
        val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }
        val fresh = NeuralSelfLearningEngine(context = null, storageFile = null).stateFlow.value
        val strategy = BitcoinStrategyModel.BASE_ICHIMOKU.id
        for (oldDim in listOf(16, 24)) {
            val oldAuto = fresh.autoTunedParameters.copy(
                perStrategyProfiles = fresh.autoTunedParameters.perStrategyProfiles.mapValues { (_, v) -> v.copy(featureTunings = v.featureTunings.take(oldDim)) },
                perSymbolProfiles = fresh.autoTunedParameters.perSymbolProfiles.mapValues { (_, v) -> v.copy(featureMultipliers = v.featureMultipliers.take(oldDim), winningFingerprint = v.winningFingerprint.take(oldDim)) },
            )
            val experience = ReplayExperienceSample("legacy", "COMMODITY", "XAU/USD", strategy, "15m", List(oldDim) { it / 30.0 }, "key", "test", true, 1.0, 1000L)
            val old = fresh.copy(attentionWeights = fresh.attentionWeights.take(oldDim), gateWeights = fresh.gateWeights.take(oldDim * 16),
                upWeights = fresh.upWeights.take(oldDim * 16), mGate = fresh.mGate.take(oldDim * 16), vGate = fresh.vGate.take(oldDim * 16),
                replayBuffer = listOf(experience), autoTunedParameters = oldAuto)
            val file = File.createTempFile("neural_migration_", ".json")
            try {
                file.writeText(json.encodeToString(old))
                val engine = NeuralSelfLearningEngine(context = null, storageFile = file)
                val restored = engine.stateFlow.value
                assertEquals(old.gateWeights, restored.gateWeights.take(oldDim * 16))
                assertEquals(old.upWeights, restored.upWeights.take(oldDim * 16))
                assertEquals(List((28 - oldDim) * 16) { 0.0 }, restored.gateWeights.drop(oldDim * 16))
                assertEquals(old.attentionWeights, restored.attentionWeights.take(oldDim))
                assertEquals(oldAuto.perStrategyProfiles[strategy]!!.featureTunings, restored.autoTunedParameters.perStrategyProfiles[strategy]!!.featureTunings.take(oldDim))
                assertEquals(oldAuto.perSymbolProfiles["XAU/USD"]!!.featureMultipliers, restored.autoTunedParameters.perSymbolProfiles["XAU/USD"]!!.featureMultipliers.take(oldDim))
                assertEquals(experience.features + List(28 - oldDim) { 0.0 }, restored.replayBuffer.single().features)
                if (oldDim == 24) {
                    val x = List(24) { (it - 12) / 24.0 }
                    val expected = NeuralSelfLearningEngine(context = null, storageFile = null).evaluateFeatures("XAU/USD", x.toDoubleArray(), "key", strategy)
                    val actual = engine.evaluateFeatures("XAU/USD", (x + listOf(0.4, 1.0, -0.2, 1.0)).toDoubleArray(), "key", strategy)
                    assertEquals(expected.predictedWinProb, actual.predictedWinProb, 1e-12)
                    assertEquals(expected.expectedR, actual.expectedR, 1e-12)
                }
            } finally { file.delete() }
        }
    }

    @Test
    fun `recorded gauge features are learned and malformed observations rejected`() {
        val engine = NeuralSelfLearningEngine(context = null, storageFile = null)
        val vector = List(24) { 0.2 } + listOf(0.4, 1.0, -0.6, 1.0)
        val obs = NeuralEntryObservation(2, 30_000_000L, 29_000_000L, vector, "key", 0.5, 0.0)
        assertTrue(obs.isUsableFor(30_000_000L))
        assertFalse(obs.copy(features = vector.take(25) + listOf(0.0) + vector.drop(26)).isUsableFor(30_000_000L))
        val trade = PaperTrade(id = "new", symbol = "XAU/USD", interval = Interval.M15, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0,
            openedAt = 30_000_000L, closedAt = 31_000_000L, pnlUsd = 5.0, fixedRiskUsd = 5.0,
            signalBarTime = 29_000_000L, neuralEntryObservation = obs)
        val before = engine.stateFlow.value.gateWeights.drop(24 * 16)
        engine.recordCompletedTrade(trade)
        val state = engine.stateFlow.value
        assertEquals(vector, state.replayBuffer.first().features)
        assertTrue(state.gateWeights.drop(24 * 16).zip(before).any { (a, b) -> a != b })
    }
}
