package com.aurum.edge.engine

import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperOrderRulesTest {
    @Test fun longAndShortHaveOppositeStopsAndBoundedPaperRisk() {
        val long = PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 104.0,
            1000.0, 0.5)
        val short = PaperOrderRules.preview(SignalAction.SELL, "EUR/USD", 100.0, 102.0, 96.0,
            1000.0, 0.5)
        assertEquals("EUR", long.unit)
        assertTrue(long.quantity in 0.000001..2.5)
        assertTrue(long.actualRiskUsd + long.commissionUsd + long.spreadCostUsd <= 5.001)
        assertEquals(2.0, long.rewardRisk, 1e-9)
        assertEquals(long.quantity, short.quantity, 1e-9)
        assertTrue(short.actualRiskUsd + short.commissionUsd + short.spreadCostUsd <= 5.001)
        assertEquals("oz", PaperOrderRules.unitFor("XAU/USD"))
    }

    @Test fun noForcedMinimumQuantityCanIncreaseRisk() {
        val ticket = PaperOrderRules.preview(SignalAction.BUY, "XAU/USD", 4000.0, 3993.0,
            4014.0, 100.0, 0.5)
        assertTrue(ticket.actualRiskUsd <= ticket.riskBudgetUsd)
        assertTrue(ticket.quantity > 0 && ticket.quantity < 0.071428)
    }

    @Test fun usdCrossPairsSizeRiskInDollarsViaTheirOwnPrice() {
        // USD/JPY at 150: a 2 JPY stop risks 2/150 USD per unit, so a $5 all-in risk budget must size below 375 units.
        val ticket = PaperOrderRules.preview(SignalAction.BUY, "USD/JPY", 150.0, 148.0, 153.0,
            1000.0, 0.5)
        assertEquals("USD", ticket.unit)
        assertTrue(ticket.quantity > 0.0 && ticket.quantity < 375.0)
        assertTrue(ticket.actualRiskUsd + ticket.commissionUsd + ticket.spreadCostUsd <= 5.001)
        assertTrue(ticket.actualRiskUsd <= ticket.riskBudgetUsd)
        // Base of a USD/XXX pair IS one dollar: notional is the unit count, not units x price.
        assertEquals(ticket.quantity, ticket.notionalUsd, 1e-6)
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

        // Financial realism: leverage, margin, commission, and spread cost checks
        val goldTicket = PaperOrderRules.preview(SignalAction.BUY, "XAU/USD", 2500.0, 2490.0, 2530.0, 1000.0, 0.5)
        assertEquals(20, goldTicket.leverage)
        assertTrue(goldTicket.marginUsd > 0.0)
        assertTrue(goldTicket.commissionUsd > 0.0)
        assertTrue(goldTicket.spreadCostUsd > 0.0)

        val cryptoTicket = PaperOrderRules.preview(SignalAction.BUY, "BTCUSDT", 60000.0, 59000.0, 63000.0, 1000.0, 0.5)
        // Real reference: Binance Spot is 1:1, and the ESMA cap for retail crypto CFDs is 2:1.
        assertEquals(2, cryptoTicket.leverage)
        // Binance Spot VIP0 taker fee is 0.10% per side, so a round trip costs ~21 bps of notional.
        assertTrue(cryptoTicket.costBps > 20.0)
        assertTrue(cryptoTicket.commissionUsd > cryptoTicket.spreadCostUsd)

        // ESMA tiering is per underlying, not per "commodity": gold 20:1, oil/other commodities 10:1,
        // major FX 30:1, non-major FX (AUD/USD is not an ESMA "major") 20:1.
        assertEquals(10, PaperOrderRules.defaultLeverageFor("USOIL"))
        assertEquals(30, PaperOrderRules.defaultLeverageFor("EUR/USD"))
        assertEquals(20, PaperOrderRules.defaultLeverageFor("AUD/USD"))
        assertEquals(5, PaperOrderRules.defaultLeverageFor("AAPL"))
        // The ticket names the venue the numbers came from and its cost in bps of notional.
        assertTrue(goldTicket.venue.startsWith("LMAX/IC Markets"))
        assertTrue(goldTicket.costBps > 0.0)
        assertEquals("oz", PaperOrderRules.unitFor("XAU/USD"))
    }

    @Test fun aTargetSmallerThanTheRealCostFloorOfItsMarketIsRefused() {
        // BTCUSDT costs ~10 bps per side plus a 1 bp spread, so a round trip is ~21 bps. Crypto
        // therefore needs at least 5x that (and the 60 bps family floor) before a ticket may even
        // exist: a 25 bps target is a fee donation, not a trade.
        val refused = try {
            PaperOrderRules.preview(SignalAction.BUY, "BTCUSDT", 60000.0, 59900.0, 60150.0, 1000.0, 0.1)
            null
        } catch (error: IllegalArgumentException) {
            error.message
        }
        assertNotNull(refused)
        assertTrue(refused!!.contains("کفِ"))
        assertTrue(refused.contains("بازار «"))

        // The same shape with a target that actually pays for the fee is accepted unchanged.
        val ticket = PaperOrderRules.preview(SignalAction.BUY, "BTCUSDT", 60000.0, 59900.0, 60900.0, 1000.0, 0.1)
        assertTrue(ticket.rewardRisk >= 1.5)
        assertTrue(ticket.costBps > 0.0)
        assertEquals("coins", ticket.unit)
    }

    @Test fun rejectsWrongSideExcessLeverageRewardAndInvalidQuotes() {
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Must fail closed") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 101.0, 104.0, 1000.0, 0.5) }
        invalid { PaperOrderRules.preview(SignalAction.SELL, "EUR/USD", 100.0, 99.0, 96.0, 1000.0, 0.5) }
        invalid { PaperOrderRules.preview(SignalAction.NO_TRADE, "EUR/USD", 100.0, 98.0, 104.0, 1000.0, 0.5) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 100.5, 1000.0, 0.5) } // RR < 1.5
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", 100.0, 98.0, 104.0, 1000.0, 5.01) } // risk > 0.5%
        invalid { PaperOrderRules.preview(SignalAction.BUY, "EUR/USD", Double.NaN, 98.0, 104.0, 1000.0, 0.5) }
        invalid { PaperOrderRules.preview(SignalAction.BUY, "USD/IRT", 100.0, 98.0, 104.0, 1000.0, 0.5) }
        // Real margin rule: with gold at 20:1 a 2.5 oz position needs $500 of margin, which a
        // $100 account cannot post — the ticket must be refused instead of silently oversized.
        invalid { PaperOrderRules.preview(SignalAction.BUY, "XAU/USD", 4000.0, 3998.0, 4008.0, 100.0, 5.0) }
    }
}
