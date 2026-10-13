package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import org.junit.Assert.*
import org.junit.Test

class SourceRoutingTest {
    @Test fun `reference frames never fetch Binance for a read-only or keyed crypto base`() {
        assertFalse(canRefreshSamePublisherFrames("BTC/USDT", "Gate Spot / USDT (radar only)"))
        assertFalse(canRefreshSamePublisherFrames("BTC/USDT", "Twelve Data · fallback هم‌نماد BTC/USDT"))
        assertTrue(canRefreshSamePublisherFrames("BTC/USDT", BinanceSpotClient.PROVIDER))
        assertTrue(canRefreshSamePublisherFrames("EUR/USD", "Yahoo Finance عمومی"))
        assertTrue(canRefreshSamePublisherFrames("XAG/USD", "Dukascopy عمومی"))
        assertFalse(canRefreshSamePublisherFrames("XAG/USD", "نامعلوم"))
    }

    @Test fun `all 230 scanner rows show honest exact-pair plans without claiming universal coverage`() {
        val universe = WatchCatalog.scannerSymbols
        assertEquals(230, universe.distinct().size)
        assertEquals(200, universe.count { AssetClass.of(it) == AssetClass.CRYPTO })
        assertEquals(25, universe.count { AssetClass.of(it) == AssetClass.FOREX })
        assertEquals(0, universe.count { AssetClass.of(it) == AssetClass.STOCK })
        assertEquals(5, universe.count { AssetClass.of(it) == AssetClass.COMMODITY })
        assertTrue(universe.filter { AssetClass.of(it) == AssetClass.CRYPTO }.all {
            SourceRouting.historyPlan(it, false).contains("Gate Spot ← OKX Spot ← Kraken Spot")
        })
        assertTrue(SourceRouting.historyPlan("EUR/JPY", false).contains("Dukascopy"))
        assertFalse(SourceRouting.historyPlan("AAPL", false).contains("Dukascopy"))
        assertTrue(SourceRouting.historyPlan("AAPL", false).contains("جایگزین مستقل ندارد"))
        assertTrue(SourceRouting.historyPlan("USOIL", false).contains("تعریف نشده"))
        assertTrue(SourceRouting.historyPlan("XAU/USD", true).contains("Twelve Data"))
        assertTrue(SourceRouting.historyPlan("XAG/USD", false).contains("Dukascopy"))
        assertFalse(SourceRouting.historyPlan("XAG/USD", false).contains("Yahoo"))
        assertEquals(listOf(SourceRouting.HistorySource.YAHOO), SourceRouting.historySources("AAPL", false))
        assertTrue(SourceRouting.historySources("USOIL", false).isEmpty())
        assertNull(PublicCandleHistoryClient().yahooSymbol("BTC/USDT"))
    }
}
