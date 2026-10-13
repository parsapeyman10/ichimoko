package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.V1Universe
import org.junit.Assert.*
import org.junit.Test

class CryptoUniverse200Test {
    @Test fun `exactly 200 unique keyless crypto choices are scannable and searchable`() {
        assertEquals(200, CryptoCatalog.symbols.size)
        assertEquals(200, CryptoCatalog.ids.distinct().size)
        assertEquals(200, CryptoCatalog.symbols.map { it.binance }.distinct().size)
        CryptoCatalog.symbols.forEach { coin ->
            assertEquals(coin.id.replace("/", ""), coin.binance)
            assertTrue(coin.id, V1Universe.valid(coin.id))
            assertTrue(coin.id, PaperOrderRules.paperable(coin.id))
            assertEquals(AssetClass.CRYPTO, AssetClass.of(coin.id))
            assertTrue(coin.id in WatchCatalog.scanUniverse(emptyList()))
            assertTrue(ChartSymbolChoices.search(coin.id).any { it.id == coin.id })
            assertTrue(coin.digits in 2..8)
        }
        assertEquals(200, WatchCatalog.scannerSymbols.count(CryptoCatalog::isCrypto))
        assertEquals(200, ChartSymbolChoices.search("").count { CryptoCatalog.isCrypto(it.id) })
    }

    @Test fun `US stocks and ETFs cannot reenter through custom or old watchlists`() {
        val stocks = listOf("AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "GOOGL", "META",
            "AMD", "NFLX", "INTC", "SPY", "QQQ", "PLTR", "COIN", "BABA", "NASDAQ", "SP500")
        stocks.forEach { symbol ->
            assertFalse(symbol, V1Universe.valid(symbol))
            assertFalse(symbol in WatchCatalog.scannerSymbols)
            assertFalse(symbol in V1Universe.defaults)
            assertNull(TradingViewSymbols.find(symbol))
        }
        assertTrue(WatchCatalog.scanUniverse(stocks).none { AssetClass.of(it) == AssetClass.STOCK })
        assertEquals(listOf("BTC/USDT", "EUR/USD", "XAU/USD"),
            V1Universe.watchlist(stocks + listOf(" btc/usdt ", "EUR/USD", "XAU/USD", "BTC/USDT")))
    }
}
