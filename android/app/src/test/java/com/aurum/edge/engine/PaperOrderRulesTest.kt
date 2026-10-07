package com.aurum.edge.engine

import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperOrderRulesTest {
    @Test fun longAndShortHaveOppositeStopsAndBoundedPaperRisk() {
        val long = PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 104.0,
            1000.0, 1.0)
        val short = PaperOrderRules.preview(SignalAction.SELL, "EUR/USD", 100.0, 102.0, 96.0,
            1000.0, 1.0)
        assertEquals("EUR", long.unit)
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
        assertEquals(0.071428, ticket.quantity, 1e-6)
    }

    @Test fun usdCrossPairsSizeRiskInDollarsViaTheirOwnPrice() {
        // USD/JPY at 150: a 2 JPY stop risks 2/150 USD per unit, so 10 USD budget needs 750 units.
        val ticket = PaperOrderRules.preview(SignalAction.BUY, "USD/JPY", 150.0, 148.0, 153.0,
            1000.0, 1.0)
        assertEquals("USD", ticket.unit)
        assertEquals(750.0, ticket.quantity, 1e-6)
        assertEquals(10.0, ticket.actualRiskUsd, 1e-8)
        assertTrue(ticket.actualRiskUsd <= ticket.riskBudgetUsd)
        // Base of a USD/XXX pair IS one dollar: notional is the unit count, not units x price.
        assertEquals(750.0, ticket.notionalUsd, 1e-6)
        assertEquals(1.5, ticket.rewardRisk, 1e-9)
        // Quote P/L converts to USD through the pair's own exit price.
        assertEquals(10.0 / 153.0, PaperOrderRules.quotePnlToUsd("USD/JPY", 10.0, 153.0), 1e-9)
        assertEquals(10.0, PaperOrderRules.quotePnlToUsd("EUR/USD", 10.0, 1.08), 1e-9)
        assertTrue(PaperOrderRules.paperable("USD/CHF"))
        assertTrue(PaperOrderRules.paperable("USD/CAD"))
        assertTrue(PaperOrderRules.paperable("XAU/USD"))
        assertTrue(PaperOrderRules.paperable("EUR/GBP"))
        assertTrue(PaperOrderRules.paperable("EUR/JPY"))
        assertTrue(PaperOrderRules.paperable("BTCUSDT"))
        assertTrue(PaperOrderRules.paperable("AAPL"))
        assertTrue(PaperOrderRules.paperable("BRENT"))
        assertFalse(PaperOrderRules.paperable("USD/IRT"))

        // Asset Class classification checks
        assertEquals(com.aurum.edge.core.AssetClass.CRYPTO, com.aurum.edge.core.AssetClass.of("BTCUSDT"))
        assertEquals(com.aurum.edge.core.AssetClass.CRYPTO, com.aurum.edge.core.AssetClass.of("ETH/USDT"))
        assertEquals(com.aurum.edge.core.AssetClass.COMMODITY, com.aurum.edge.core.AssetClass.of("XAU/USD"))
        assertEquals(com.aurum.edge.core.AssetClass.COMMODITY, com.aurum.edge.core.AssetClass.of("BRENT"))
        assertEquals(com.aurum.edge.core.AssetClass.STOCK, com.aurum.edge.core.AssetClass.of("AAPL"))
        assertEquals(com.aurum.edge.core.AssetClass.STOCK, com.aurum.edge.core.AssetClass.of("NASDAQ"))
        assertEquals(com.aurum.edge.core.AssetClass.FOREX, com.aurum.edge.core.AssetClass.of("EUR/USD"))
    }

    @Test fun rejectsWrongSideExcessLeverageRewardAndInvalidQuotes() {
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Must fail closed") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 101.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.SELL, "EUR/USD", 100.0, 99.0, 96.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.NO_TRADE, "EUR/USD", 100.0, 98.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 100.5, 1000.0, 1.0) } // RR < 1.2
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 104.0, 1000.0, 5.01) } // risk > 5%
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", Double.NaN, 98.0, 104.0, 1000.0, 1.0) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "USD/IRT", 100.0, 98.0, 104.0, 1000.0, 1.0) }
    }
}
