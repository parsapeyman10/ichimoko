package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart must draw the instrument that was selected.
 *
 * The mapping used to be inline with `else -> OANDA:XAUUSD`, so anything missing from the
 * list rendered GOLD while the rest of the screen said otherwise — a wrong chart with no
 * error anywhere. These tests exist so a symbol can never silently become gold again.
 */
class TradingViewSymbolsTest {

    @Test fun `every chartable catalog symbol resolves to a real ticker`() {
        WatchCatalog.chartSymbols.forEach { id ->
            val ticker = TradingViewSymbols.find(id)
            assertTrue("no TradingView ticker for $id", ticker != null)
            assertTrue("malformed ticker for $id: $ticker", ticker!!.contains(':'))
        }
    }

    @Test fun `every seeded coin resolves to its exchange ticker`() {
        CryptoCatalog.symbols.forEach { coin ->
            assertEquals("BINANCE:${coin.binance}", TradingViewSymbols.find(coin.id))
        }
    }

    @Test fun `gold and the majors map to OANDA`() {
        assertEquals("OANDA:XAUUSD", TradingViewSymbols.find("XAU/USD"))
        assertEquals("OANDA:EURUSD", TradingViewSymbols.find("EUR/USD"))
        assertEquals("OANDA:USDJPY", TradingViewSymbols.find("USD/JPY"))
        assertEquals("OANDA:XAUUSD", TradingViewSymbols.find("xau/usd"))   // case-insensitive
    }

    @Test fun `an unknown symbol is reported, not quietly turned into gold`() {
        assertNull(TradingViewSymbols.find("NOT/REAL"))
        assertNull(TradingViewSymbols.find(""))
        assertTrue(!TradingViewSymbols.isChartable("NOT/REAL"))
        // `of()` used to return OANDA:XAUUSD for anything unmapped, so a caller could draw a
        // wrong-but-plausible chart by accident. It is gone: `find()` returning null is the only
        // answer, and the UI must say "this symbol has no TradingView mapping".
    }

    @Test fun `TradingView stays visual only and exchange watch prices stay with their venue`() {
        assertTrue(SourceCatalog.all.none { it.id == "tradingview_scanner" })
        CryptoCatalog.watchlistSeed.forEach { coin ->
            val watch = WatchCatalog.find(coin.id)!!
            assertEquals(coin.id, watch.providerCodes["nobitex_public_trades"])
            assertEquals("BINANCE:${coin.binance}", TradingViewSymbols.find(coin.id))
        }
        assertTrue(WatchCatalog.symbols.none { "tradingview_scanner" in it.providerCodes })
    }
}
