package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twelve Data coverage is a contract about what may be requested, never a promise of data.
 * A different instrument id (USD instead of USDT, a Dow future instead of the index) must be
 * reported as unsupported rather than silently substituted.
 */
class TwelveDataCoverageTest {
    @Test fun `forex uses the same id and is the only free-plan request`() {
        assertEquals("EUR/USD", TwelveDataCoverage.requestSymbol("eur/usd"))
        assertEquals("USD/CHF", TwelveDataCoverage.requestSymbol("USD/CHF"))
        assertEquals(TwelveDataCoverage.Status.FREE_SAME_ID, TwelveDataCoverage.statusOf("GBP/JPY"))
    }

    @Test fun `metals are a paid market and never requested with a free key`() {
        assertEquals(TwelveDataCoverage.Status.PAID_PLAN, TwelveDataCoverage.statusOf("XAU/USD"))
        assertEquals(TwelveDataCoverage.Status.PAID_PLAN, TwelveDataCoverage.statusOf("XAG/USD"))
        assertNull(TwelveDataCoverage.requestSymbol("XAU/USD"))
        assertTrue(TwelveDataCoverage.note("XAG/USD").isNotBlank())
    }

    @Test fun `dow and usdt crypto are never relabelled`() {
        assertEquals(TwelveDataCoverage.Status.ID_MISMATCH, TwelveDataCoverage.statusOf("DOW"))
        assertNull(TwelveDataCoverage.requestSymbol("DOW"))
        listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT").forEach { symbol ->
            assertEquals(TwelveDataCoverage.Status.VERIFY_WITH_KEY,
                TwelveDataCoverage.statusOf(symbol))
            assertNull(TwelveDataCoverage.requestSymbol(symbol))
        }
    }

    @Test fun `every catalog symbol has one entry and seven same-id slots`() {
        val symbols = TwelveDataCoverage.entries.map { it.symbol }
        assertEquals(com.aurum.edge.core.V1Universe.allSymbols, symbols)
        assertEquals(symbols.size, symbols.distinct().size)
        assertEquals(com.aurum.edge.core.V1Universe.forexSymbols.toSet(),
            TwelveDataCoverage.sameIdSymbols.toSet())
    }
}
