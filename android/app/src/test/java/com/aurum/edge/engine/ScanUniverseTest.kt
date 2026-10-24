package com.aurum.edge.engine

import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.CryptoCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUniverseTest {
    @Test fun `background radar covers the 15 symbols in the curated universe`() {
        val symbols = WatchCatalog.scannerSymbols
        assertEquals(15, symbols.size)
        assertTrue(listOf("XAU/USD", "XAG/USD", "DOW", "EUR/USD", "GBP/USD", "USD/JPY",
            "EUR/JPY", "GBP/JPY", "AUD/USD", "USD/CHF",
            "BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT").all { it in symbols })
        assertTrue(CryptoCatalog.ids.containsAll(symbols.filter { it.endsWith("/USDT") }))
        assertFalse("IR_GOLD18" in symbols)
        assertFalse("USD/IRR" in symbols)
        assertFalse("USOIL" in symbols)
    }

    @Test fun `user watchlist is scanned first and invalid symbols are dropped`() {
        val active = listOf("XAU/USD", "EUR/USD", "AAPL")
        val universe = WatchCatalog.scanUniverse(active)
        assertEquals(listOf("XAU/USD", "EUR/USD"), universe.take(2))
        assertTrue(universe.containsAll(WatchCatalog.scannerSymbols))
        assertFalse("AAPL" in universe)
        assertEquals(WatchCatalog.scannerSymbols.size, universe.size)
    }
}
