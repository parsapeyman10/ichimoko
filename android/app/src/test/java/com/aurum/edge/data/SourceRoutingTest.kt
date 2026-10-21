package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRoutingTest {
    @Test fun `each of the 15 symbols maps to a unique dedicated source with no duplicates`() {
        val dedicated = V1Universe.allSymbols.map { symbol ->
            val src = SourceRouting.dedicatedFor(symbol)
            assertNotNull("missing dedicated source for $symbol", src)
            src!!
        }
        assertEquals(15, dedicated.size)
        assertEquals("no two symbols may share a source", 15, dedicated.distinct().size)
    }

    @Test fun `tick-only publishers are never offered as candle candidates`() {
        // Swissquote and Gold-API publish a live price and no history at all. They stay in the
        // app as labelled ticks, but a candle bundle must never be requested from them.
        V1Universe.allSymbols.forEach { symbol ->
            listOf(false, true).forEach { keyed ->
                SourceRouting.historyCandidates(symbol, keyed).forEach { source ->
                    assertFalse("$symbol/$keyed: Swissquote is not a candle publisher",
                        source.providerLabel.contains("Swissquote", ignoreCase = true))
                    assertFalse("$symbol/$keyed: Gold-API is not a candle publisher",
                        source.providerLabel.contains("Gold-API", ignoreCase = true))
                }
            }
        }
    }

    @Test fun `non-crypto symbols each map to their own dedicated HistorySource`() {
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAU),
            SourceRouting.historySources("XAU/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAG),
            SourceRouting.historySources("XAG/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_DOW),
            SourceRouting.historySources("DOW"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_EURUSD),
            SourceRouting.historySources("EUR/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_GBPUSD),
            SourceRouting.historySources("GBP/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_USDJPY),
            SourceRouting.historySources("USD/JPY"))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_EURJPY),
            SourceRouting.historySources("EUR/JPY"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_GBPJPY),
            SourceRouting.historySources("GBP/JPY"))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_AUDUSD),
            SourceRouting.historySources("AUD/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_USDCHF),
            SourceRouting.historySources("USD/CHF"))
        assertTrue(SourceRouting.historySources("BTC/USDT").isEmpty())
    }

    @Test fun `crypto symbols each map to their own distinct exchange venue`() {
        assertEquals(CryptoRadarCandleClient.Venue.BINANCE, SourceRouting.cryptoVenueFor("BTC/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.OKX, SourceRouting.cryptoVenueFor("ETH/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.GATE, SourceRouting.cryptoVenueFor("SOL/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.MEXC, SourceRouting.cryptoVenueFor("BNB/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.KRAKEN, SourceRouting.cryptoVenueFor("XRP/USDT"))
    }

    @Test fun `history candidates only include publishers that can serve the instrument`() {
        // Yahoo has no spot XAU/USD chart: a Yahoo attempt for gold is a guaranteed failure.
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAU),
            SourceRouting.historyCandidates("XAU/USD"))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAG),
            SourceRouting.historyCandidates("XAG/USD"))
        // With a key, Twelve Data is the reference publisher for gold; Dukascopy is the
        // keyless fallback (the order is intentional, not alphabetical).
        assertEquals(
            listOf(SourceRouting.HistorySource.TWELVE_DATA, SourceRouting.HistorySource.DUKASCOPY_XAU),
            SourceRouting.historyCandidates("XAU/USD", keyed = true),
        )
        assertEquals(
            listOf(SourceRouting.HistorySource.DUKASCOPY_XAG, SourceRouting.HistorySource.TWELVE_DATA),
            SourceRouting.historyCandidates("XAG/USD", keyed = true),
        )
        // Forex keeps the real cross-publisher fallback.
        assertEquals(
            listOf(SourceRouting.HistorySource.YAHOO_EURUSD, SourceRouting.HistorySource.DUKASCOPY),
            SourceRouting.historyCandidates("EUR/USD"),
        )
        assertEquals(
            listOf(SourceRouting.HistorySource.DUKASCOPY_GBPUSD, SourceRouting.HistorySource.YAHOO),
            SourceRouting.historyCandidates("GBP/USD"),
        )
    }

    @Test fun `crypto reference is nobitex and only gold prefers twelve data`() {
        assertTrue(SourceRouting.usesNobitexReference("BTC/USDT"))
        assertTrue(SourceRouting.usesNobitexReference("ETH/USDT"))
        assertTrue(SourceRouting.twelveDataFirst("XAU/USD"))
        assertFalse(SourceRouting.twelveDataFirst("XAG/USD"))
        assertFalse(SourceRouting.twelveDataFirst("BTC/USDT"))
        val plan = SourceRouting.historyPlan("BTC/USDT", keyed = true)
        assertTrue(plan, plan.contains("نوبیتکس"))
        assertTrue("gold plan must announce the Twelve Data reference", 
            SourceRouting.historyPlan("XAU/USD", keyed = true).contains("Twelve Data"))
    }

    @Test fun `unknown symbols have no dedicated source or history fallback`() {
        assertNull(SourceRouting.dedicatedFor("AAPL"))
        assertNull(SourceRouting.cryptoVenueFor("AAPL"))
        assertTrue(SourceRouting.historySources("AAPL").isEmpty())
    }
}
