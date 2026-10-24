package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the per-symbol source map that docs/SYMBOL_SOURCES_FA.md documents, so the table cannot
 * silently drift from the code: candle candidate order (keyless and keyed) and the tick route
 * of all 15 symbols.
 */
class SymbolSourceMapTest {
    private val d = SourceRouting.HistorySource.DUKASCOPY
    private val y = SourceRouting.HistorySource.YAHOO
    private val t = SourceRouting.HistorySource.TWELVE_DATA

    @Test fun `candle order per non-crypto symbol matches the documented map`() {
        val map = mapOf(
            "XAU/USD" to (listOf(SourceRouting.HistorySource.DUKASCOPY_XAU) to
                listOf(t, SourceRouting.HistorySource.DUKASCOPY_XAU)),
            "XAG/USD" to (listOf(SourceRouting.HistorySource.DUKASCOPY_XAG) to
                listOf(SourceRouting.HistorySource.DUKASCOPY_XAG, t)),
            "DOW" to (listOf(SourceRouting.HistorySource.YAHOO_DOW, d) to
                listOf(SourceRouting.HistorySource.YAHOO_DOW, d, t)),
            "EUR/USD" to (listOf(SourceRouting.HistorySource.YAHOO_EURUSD, d) to
                listOf(SourceRouting.HistorySource.YAHOO_EURUSD, d, t)),
            "GBP/USD" to (listOf(SourceRouting.HistorySource.DUKASCOPY_GBPUSD, y) to
                listOf(SourceRouting.HistorySource.DUKASCOPY_GBPUSD, y, t)),
            "USD/JPY" to (listOf(SourceRouting.HistorySource.YAHOO_USDJPY, d) to
                listOf(SourceRouting.HistorySource.YAHOO_USDJPY, d, t)),
            "EUR/JPY" to (listOf(SourceRouting.HistorySource.DUKASCOPY_EURJPY, y) to
                listOf(SourceRouting.HistorySource.DUKASCOPY_EURJPY, y, t)),
            "GBP/JPY" to (listOf(SourceRouting.HistorySource.YAHOO_GBPJPY, d) to
                listOf(SourceRouting.HistorySource.YAHOO_GBPJPY, d, t)),
            "AUD/USD" to (listOf(SourceRouting.HistorySource.DUKASCOPY_AUDUSD, y) to
                listOf(SourceRouting.HistorySource.DUKASCOPY_AUDUSD, y, t)),
            "USD/CHF" to (listOf(SourceRouting.HistorySource.YAHOO_USDCHF, d) to
                listOf(SourceRouting.HistorySource.YAHOO_USDCHF, d, t)),
        )
        assertEquals(10, map.size)
        map.forEach { (symbol, plan) ->
            assertEquals("$symbol keyless", plan.first, SourceRouting.historyCandidates(symbol))
            assertEquals("$symbol keyed", plan.second, SourceRouting.historyCandidates(symbol, keyed = true))
        }
    }

    @Test fun `crypto never asks a yahoo or dukascopy publisher for candles`() {
        V1Universe.cryptoSymbols.forEach { symbol ->
            assertTrue("$symbol must use the Nobitex reference",
                SourceRouting.usesNobitexReference(symbol))
            // Crypto history is the Nobitex bundle plus the per-symbol read-only venue, never
            // the non-crypto candidate list (a BTC/USD proxy is not BTC/USDT).
            assertTrue(SourceRouting.historyCandidates(symbol).isEmpty())
        }
    }

    @Test fun `tick route per symbol matches the documented map`() {
        assertEquals(TickRoute.METAL, tickPlanFor("XAU/USD").route)
        assertEquals(TickRoute.METAL, tickPlanFor("XAG/USD").route)
        assertEquals(TickPlan(TickRoute.YAHOO_INDEX, "^DJI"), tickPlanFor("DOW"))
        // EUR/USD is quoted by Swissquote: EUR-ARES needs a real bid/ask before it can price its
        // cost gate, and the Yahoo quote carries a single number.
        assertEquals(TickRoute.SWISSQUOTE, tickPlanFor("EUR/USD").route)
        assertEquals(TickPlan(TickRoute.YAHOO_INDEX, "JPY=X"), tickPlanFor("USD/JPY"))
        assertEquals(TickPlan(TickRoute.YAHOO_INDEX, "GBPJPY=X"), tickPlanFor("GBP/JPY"))
        listOf("EUR/USD", "GBP/USD", "EUR/JPY", "AUD/USD", "USD/CHF").forEach { symbol ->
            assertEquals(symbol, TickRoute.SWISSQUOTE, tickPlanFor(symbol).route)
        }
        // Only Yahoo routes carry a symbol; a metal/Swissquote route must not invent one.
        assertNull(tickPlanFor("XAU/USD").yahooSymbol)
        assertNull(tickPlanFor("GBP/USD").yahooSymbol)
    }
}
