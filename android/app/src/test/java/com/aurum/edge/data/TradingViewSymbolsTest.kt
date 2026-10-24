package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewSymbolsTest {

    @Test fun `every chartable symbol maps to its own TradingView ticker`() {
        val seen = mutableSetOf<String>()
        WatchCatalog.chartSymbols.forEach { id ->
            val tv = TradingViewSymbols.find(id)
            assertNotNull("no TradingView mapping for $id", tv)
            assertTrue("bad TradingView ticker for $id: $tv", ":" in tv!!)
            assertTrue("duplicate TradingView ticker $tv", seen.add(tv))
            assertFalse("$id fell back to XAUUSD", id != "XAU/USD" && tv.endsWith("XAUUSD"))
        }
        assertEquals(15, seen.size)
        CryptoCatalog.symbols.forEach { coin ->
            assertEquals("BINANCE:${coin.binance}", TradingViewSymbols.find(coin.id))
        }
    }

    @Test fun `known major pairs and Dow Jones resolve to their real venues`() {
        assertEquals("OANDA:XAUUSD", TradingViewSymbols.find("XAU/USD"))
        assertEquals("OANDA:XAGUSD", TradingViewSymbols.find("XAG/USD"))
        assertEquals("BLACKBULL:US30", TradingViewSymbols.find("DOW"))
        assertEquals("BLACKBULL:US30", TradingViewSymbols.find("US30"))
        assertEquals("OANDA:EURUSD", TradingViewSymbols.find("EUR/USD"))
        assertEquals("OANDA:USDJPY", TradingViewSymbols.find("USD/JPY"))
        assertEquals("OANDA:GBPJPY", TradingViewSymbols.find("GBP/JPY"))
        assertEquals("BINANCE:BTCUSDT", TradingViewSymbols.find("BTC/USDT"))
        assertEquals("BINANCE:ETHUSDT", TradingViewSymbols.find("eth/usdt"))
        assertNull(TradingViewSymbols.find("IR_GOLD18"))
        assertNull(TradingViewSymbols.find("NOT/REAL"))
    }

    @Test fun `every symbol in WatchCatalog uses its own single dedicated source`() {
        val sources = WatchCatalog.symbols.map { it.defaultSources.single() }
        assertEquals(15, sources.distinct().size)
        assertTrue(WatchCatalog.symbols.none { "tradingview_scanner" in it.providerCodes })
    }
}
