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
        // of() still yields something drawable, but find() is how a caller detects it.
        assertEquals("OANDA:XAUUSD", TradingViewSymbols.of("NOT/REAL"))
    }

    @Test fun `the watchlist quote and the chart use the same ticker`() {
        CryptoCatalog.watchlistSeed.forEach { coin ->
            val watchCode = WatchCatalog.find(coin.id)!!.providerCodes["tradingview_scanner"]
            assertEquals(TradingViewSymbols.of(coin.id), watchCode)
        }
    }

    @Test fun `every forex watch row quotes from the same ticker the chart draws`() {
        WatchCatalog.symbols
            .filter { "tradingview_scanner" in it.providerCodes }
            .forEach { row ->
                assertEquals(
                    "watch and chart disagree for ${row.id}",
                    TradingViewSymbols.of(row.id),
                    row.providerCodes["tradingview_scanner"],
                )
                assertEquals("tradingview_scanner", row.defaultSources.single())
            }
    }
}
