package com.aurum.edge.engine

import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperOrderRulesTest {
    @Test fun longAndShortHaveOppositeStopsAndBoundedPaperRisk() {
        val long = PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", 100.0, 98.0, 104.0,
            1000.0, 1.0)
        val short = PaperOrderRules.preview(SignalAction.SELL, "BTC/USD", 100.0, 102.0, 96.0,
            1000.0, 1.0)
        assertEquals("BTC", long.unit)
        assertEquals(5.0, long.quantity, 1e-9)
        assertEquals(10.0, long.actualRiskUsd, 1e-9)
        assertEquals(2.0, long.rewardRisk, 1e-9)
        assertEquals(long.quantity, short.quantity, 1e-9)
        assertEquals(10.0, short.actualRiskUsd, 1e-9)
        assertEquals("oz", PaperOrderRules.unitFor("XAU/USD"))
    }

    @Test fun noForcedMinimumQuantityCanIncreaseRisk() {
        val ticket = PaperOrderRules.preview(SignalAction.BUY, "XAU/USD", 4000.0, 3993.0,
            4014.0, 100.0, 0.5)
        assertTrue(ticket.actualRiskUsd <= ticket.riskBudgetUsd)
        assertTrue(ticket.notionalUsd <= 300.0)
        assertEquals(0.071428, ticket.quantity, 1e-8)
    }

    @Test fun rejectsWrongSideExcessLeverageRewardAndInvalidQuotes() {
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Must fail closed") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", 100.0, 101.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.SELL, "BTC/USD", 100.0, 99.0, 96.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.NO_TRADE, "BTC/USD", 100.0, 98.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", 100.0, 98.0, 101.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", 100.0, 99.9, 100.2, 100.0, 5.0) } // >3x balance
        invalid { PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", 100.0, 98.0, 104.0, 1000.0, 5.01) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "BTC/USD", Double.NaN, 98.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "USD/IRT", 100.0, 98.0, 104.0, 1000.0, 1.0) }
    }
}
