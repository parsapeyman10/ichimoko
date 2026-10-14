package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.V1Universe
import org.junit.Assert.*
import org.junit.Test

class CryptoUniverse200Test {
    @Test fun `exactly 5 top high-volume crypto pairs and 15 total symbols with 1-to-1 sources`() {
        assertEquals(5, CryptoCatalog.symbols.size)
        assertEquals(listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT"), CryptoCatalog.ids)
        assertEquals(5, CryptoCatalog.symbols.map { it.binance }.distinct().size)
        CryptoCatalog.symbols.forEach { coin ->
            assertEquals(coin, CryptoCatalog.find(coin.id))
            assertTrue(CryptoCatalog.isCrypto(coin.id))
            assertEquals(AssetClass.CRYPTO, AssetClass.of(coin.id))
            assertTrue(V1Universe.valid(coin.id))
            assertTrue(coin.id in WatchCatalog.scannerSymbols)
            assertTrue(coin.id in WatchCatalog.scanUniverse(emptyList()))
            assertEquals("BINANCE:${coin.binance}", TradingViewSymbols.find(coin.id))
            assertEquals(coin.id, ChartSymbolChoices.search(coin.id).single { it.id == coin.id }.id)
        }
        assertEquals(5, WatchCatalog.scannerSymbols.count(CryptoCatalog::isCrypto))
        assertEquals(15, WatchCatalog.scannerSymbols.size)
        assertEquals(5, ChartSymbolChoices.search("").count { CryptoCatalog.isCrypto(it.id) })
        // 1-to-1 unique source mapping per symbol:
        val watchSources = WatchCatalog.symbols.map { it.defaultSources.single() }
        assertEquals(15, watchSources.distinct().size)
        val historySources = WatchCatalog.scannerSymbols.map { SourceRouting.dedicatedSource(it)!! }
        assertEquals(15, historySources.distinct().size)
    }

    @Test fun `removed symbols are excluded from active universe and scanner`() {
        val removed = listOf("AAPL", "MSFT", "NVDA", "SPY", "QQQ", "USOIL", "UKOIL", "COPPER", "DOGE/USDT", "PEPE/USDT")
        removed.forEach { symbol ->
            assertFalse("expected $symbol to be excluded", V1Universe.valid(symbol))
            assertFalse(symbol in WatchCatalog.scannerSymbols)
            assertTrue(ChartSymbolChoices.search(symbol).none { it.id == symbol })
        }
        assertTrue(WatchCatalog.scanUniverse(removed).none { it in removed })
        assertTrue(V1Universe.valid("DOW"))
        assertEquals("DOW", V1Universe.normalize("US30"))
        assertEquals("DOW", V1Universe.normalize("DJI"))
    }
}
