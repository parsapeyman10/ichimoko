package com.aurum.edge.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NeuralAutomaticEntryGateTest {
    private fun evaluation(block: Boolean) = NeuralSignalEvaluation("XAU/USD", "x", 0.2, -0.3,
        isNoTradeCondition = true, isBlockingTradeNow = block, shouldAllowTrade = !block,
        summaryFa = "test", noTradeWarningFa = "دلیل")
    @Test fun `veto only affects enabled eligible automatic entries`() {
        assertEquals("دلیل", NeuralAutomaticEntryGate.blockReason(true, true, true, true, true, evaluation(true)))
        assertNull(NeuralAutomaticEntryGate.blockReason(true, true, true, false, true, evaluation(true)))
        assertNull(NeuralAutomaticEntryGate.blockReason(false, true, true, true, true, evaluation(true)))
        assertNull(NeuralAutomaticEntryGate.blockReason(true, false, true, true, true, evaluation(true)))
        assertNull(NeuralAutomaticEntryGate.blockReason(true, true, true, true, true, evaluation(false)))
        assertNotNull(NeuralAutomaticEntryGate.blockReason(true, true, true, true, false, null))
        assertNotNull(NeuralAutomaticEntryGate.blockReason(true, true, false, false, false, null))
    }
    @Test fun `initial activation only once and later switch choice survives reload`() {
        val dir = createTempDir(prefix = "aurum-gate-")
        try {
            val file = File(dir, "brain.json")
            val engine = NeuralSelfLearningEngine(null, file)
            engine.initializeGateControlOnce()
            assertTrue(engine.stateFlow.value.gateUserActivationEnabled)
            engine.setGateUserActivation(false)
            val reopened = NeuralSelfLearningEngine(null, file)
            reopened.initializeGateControlOnce()
            assertTrue(reopened.stateFlow.value.gateControlInitialized)
            assertFalse(reopened.stateFlow.value.gateUserActivationEnabled)
        } finally { dir.deleteRecursively() }
    }
}
