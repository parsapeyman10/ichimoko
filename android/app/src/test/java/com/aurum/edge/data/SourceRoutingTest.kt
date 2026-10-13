package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import org.junit.Assert.*
import org.junit.Test

class SourceRoutingTest {
    @Test fun `all 81 scanner rows show honest exact-pair plans without claiming universal coverage`() {
        val universe = WatchCatalog.scannerSymbols
        assertEquals(81, universe.distinct().size)
        assertEquals(36, universe.count { AssetClass.of(it) == AssetClass.CRYPTO })
        assertEquals(25, universe.count { AssetClass.of(it) == AssetClass.FOREX })
        assertEquals(15, universe.count { AssetClass.of(it) == AssetClass.STOCK })
        assertEquals(5, universe.count { AssetClass.of(it) == AssetClass.COMMODITY })
        assertTrue(universe.filter { AssetClass.of(it) == AssetClass.CRYPTO }.all {
            SourceRouting.historyPlan(it, false).contains("Gate Spot ← OKX Spot ← Kraken Spot")
        })
        assertTrue(SourceRouting.historyPlan("EUR/JPY", false).contains("Dukascopy"))
        assertFalse(SourceRouting.historyPlan("AAPL", false).contains("Dukascopy"))
        assertTrue(SourceRouting.historyPlan("AAPL", false).contains("جایگزین مستقل ندارد"))
        assertTrue(SourceRouting.historyPlan("USOIL", false).contains("تعریف نشده"))
        assertTrue(SourceRouting.historyPlan("XAU/USD", true).contains("Twelve Data"))
        assertNull(PublicCandleHistoryClient.yahooSymbol("BTC/USDT"))
    }
}
