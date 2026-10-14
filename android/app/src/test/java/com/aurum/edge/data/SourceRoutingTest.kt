package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import org.junit.Assert.assertEquals
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

    @Test fun `unknown symbols have no dedicated source or history fallback`() {
        assertNull(SourceRouting.dedicatedFor("AAPL"))
        assertNull(SourceRouting.cryptoVenueFor("AAPL"))
        assertTrue(SourceRouting.historySources("AAPL").isEmpty())
    }
}
