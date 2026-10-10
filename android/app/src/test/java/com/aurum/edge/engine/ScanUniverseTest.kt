package com.aurum.edge.engine

import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.CryptoFamilies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUniverseTest {
    @Test fun cryptoFamiliesExpandReadOnlyRadarWithoutUnknownPairIds() {
        val symbols = WatchCatalog.scannerSymbols
        assertTrue(symbols.size > 60)
        assertEquals(symbols.size, symbols.distinct().size)
        assertTrue(CryptoCatalog.ids.containsAll(symbols.filter { it.endsWith("/USDT") }))
        listOf("SHIB/USDT", "ARB/USDT", "UNI/USDT", "RENDER/USDT", "SAND/USDT")
            .forEach { assertTrue(it, it in symbols) }
        assertEquals("میم‌کوین", CryptoFamilies.label("SHIB/USDT"))
        assertEquals("مقیاس‌پذیری / لایهٔ دو", CryptoFamilies.label("ARB/USDT"))
    }

    @Test fun checksEveryCatalogSymbolAndCustomWatchlistExactlyOnce() {
        val active = listOf("BTC/USDT", "XAU/USD", "EUR/USD", "CUSTOM/USD", "IRR/USD")
        val universe = WatchCatalog.scanUniverse(active)
        assertEquals(active.take(4), universe.take(4))
        assertTrue(universe.containsAll(WatchCatalog.scannerSymbols))
        assertEquals(universe.size, universe.distinct().size)
        assertFalse(universe.contains("IRR/USD"))
        assertEquals(WatchCatalog.scannerSymbols.size + 1, universe.size)
    }
}
