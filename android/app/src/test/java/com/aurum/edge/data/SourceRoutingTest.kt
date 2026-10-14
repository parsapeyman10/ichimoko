package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRoutingTest {
    @Test fun `each of the 15 symbols maps to a unique dedicated source with no duplicates`() {
        val dedicated = V1Universe.allSymbols.map { symbol ->
            val src = SourceRouting.dedicatedSource(symbol)
            assertNotNull("missing dedicated source for $symbol", src)
            src!!
        }
        assertEquals(15, dedicated.size)
        assertEquals("no two symbols may share a source", 15, dedicated.distinct().size)
    }

    @Test fun `non-crypto symbols each map to their own dedicated HistorySource`() {
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAU),
            SourceRouting.historySources("XAU/USD", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAG),
            SourceRouting.historySources("XAG/USD", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_DOW),
            SourceRouting.historySources("DOW", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_EURUSD),
            SourceRouting.historySources("EUR/USD", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_GBPUSD),
            SourceRouting.historySources("GBP/USD", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_USDJPY),
            SourceRouting.historySources("USD/JPY", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_EURJPY),
            SourceRouting.historySources("EUR/JPY", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_GBPJPY),
            SourceRouting.historySources("GBP/JPY", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_AUDUSD),
            SourceRouting.historySources("AUD/USD", hasTwelveKey = false))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO_USDCHF),
            SourceRouting.historySources("USD/CHF", hasTwelveKey = false))
        assertTrue(SourceRouting.historySources("BTC/USDT", hasTwelveKey = true).isEmpty())
    }

    @Test fun `crypto symbols each map to their own distinct exchange venue`() {
        assertEquals(CryptoRadarCandleClient.Venue.BINANCE, SourceRouting.cryptoVenueFor("BTC/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.OKX, SourceRouting.cryptoVenueFor("ETH/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.GATE, SourceRouting.cryptoVenueFor("SOL/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.MEXC, SourceRouting.cryptoVenueFor("BNB/USDT"))
        assertEquals(CryptoRadarCandleClient.Venue.KRAKEN, SourceRouting.cryptoVenueFor("XRP/USDT"))
    }

    @Test fun `live stream policy matches dedicated source`() {
        assertTrue(SourceRouting.preferSpotLive("XAU/USD"))
        assertTrue(SourceRouting.preferSpotLive("EUR/USD"))
        assertTrue(SourceRouting.preferSpotLive("DOW"))
        assertFalse(SourceRouting.preferSpotLive("BTC/USDT"))
        assertFalse(SourceRouting.preferSpotLive("AAPL"))
    }
}
