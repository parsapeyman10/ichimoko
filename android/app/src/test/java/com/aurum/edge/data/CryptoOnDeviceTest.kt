package com.aurum.edge.data

import com.aurum.edge.core.MarketHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class CryptoOnDeviceTest {

    @Test fun `crypto symbols are chartable and recognised`() {
        assertTrue(CryptoCatalog.isCrypto("BTC/USDT"))
        assertTrue(CryptoCatalog.isCrypto("btc/usdt"))
        assertFalse(CryptoCatalog.isCrypto("XAU/USD"))
        assertFalse(CryptoCatalog.isCrypto("EUR/USD"))
        assertFalse(CryptoCatalog.isCrypto("DOW"))
        assertNotNull(CryptoCatalog.find("SOL/USDT"))
        assertEquals("BTCUSDT", CryptoCatalog.find("BTC/USDT")!!.binance)
    }

    @Test fun `all 5 high-volume crypto pairs sit in the watchlist and chart choices`() {
        assertEquals(5, CryptoCatalog.watchlistSeed.size)
        CryptoCatalog.watchlistSeed.forEach { coin ->
            assertNotNull("missing from watch catalog: ${coin.id}", WatchCatalog.find(coin.id))
            assertTrue("not chartable: ${coin.id}", coin.id in WatchCatalog.chartSymbols)
        }
        assertTrue("BTC/USDT" in CryptoCatalog.watchlistSeed.map { it.id })
        assertTrue("XAU/USD" in WatchCatalog.chartSymbols)
        assertTrue("XAG/USD" in WatchCatalog.chartSymbols)
        assertTrue("DOW" in WatchCatalog.chartSymbols)
        assertTrue("EUR/USD" in WatchCatalog.chartSymbols)
    }

    @Test fun `display decimals follow the instrument, not a fixed two`() {
        assertEquals(2, CryptoCatalog.digitsFor("BTC/USDT"))
        assertEquals(3, CryptoCatalog.digitsFor("SOL/USDT"))
        assertEquals(4, CryptoCatalog.digitsFor("XRP/USDT"))
        assertEquals(2, CryptoCatalog.digitsFor("XAU/USD"))
        assertEquals(3, CryptoCatalog.digitsFor("XAG/USD"))
        assertEquals(2, CryptoCatalog.digitsFor("DOW"))
        assertEquals(3, CryptoCatalog.digitsFor("USD/JPY"))
        assertEquals(5, CryptoCatalog.digitsFor("EUR/USD"))
    }

    @Test fun `crypto is never weekend-closed but forex still is`() {
        val saturday = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val millis = saturday.toInstant().toEpochMilli()

        assertTrue(MarketHours.forexWeekendClosed(millis))
        assertTrue(MarketHours.weekendClosedFor("XAU/USD", millis))
        assertTrue(MarketHours.weekendClosedFor("EUR/USD", millis))
        assertFalse(MarketHours.weekendClosedFor("BTC/USDT", millis))
    }

    @Test fun `the offline seed covers the 5 top high-volume coins and stays consistent`() {
        assertEquals(5, CryptoCatalog.symbols.size)
        val bases = CryptoCatalog.symbols.map { it.id.substringBefore('/') }
        assertEquals(listOf("BTC", "ETH", "SOL", "BNB", "XRP"), bases)
        CryptoCatalog.symbols.forEach {
            assertEquals(it.id.replace("/", ""), it.binance)
            assertTrue("bad digits for ${it.id}", it.digits in 0..8)
        }
    }

    @Test fun `every seeded coin resolves and has its own unique dedicated source`() {
        val ids = SourceCatalog.all.map { it.id }.toSet()
        val cryptoSources = mutableSetOf<String>()
        CryptoCatalog.watchlistSeed.forEach { coin ->
            val watch = WatchCatalog.find(coin.id)!!
            val source = watch.defaultSources.single()
            assertTrue("unknown source '$source' for ${coin.id}", source in ids)
            assertTrue("duplicate source '$source'", cryptoSources.add(source))
            assertEquals("BINANCE:${coin.binance}", TradingViewSymbols.find(coin.id))
        }
        assertEquals(5, cryptoSources.size)
    }

    @Test fun `a 24-7 venue is never shown a weekend-closed banner`() {
        val saturday = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val crypto = MarketHours.sessionWindowFor("BTC/USDT", saturday)
        assertFalse(crypto.closed)
        assertNull(crypto.nextChangeAt)

        val gold = MarketHours.sessionWindowFor("XAU/USD", saturday)
        assertTrue(gold.closed)
    }

    @Test fun `global gold silver and dow each have a single dedicated source`() {
        val gold = WatchCatalog.find("XAU/USD")!!
        assertEquals(listOf("gold_api_xau"), gold.defaultSources)
        assertEquals("OANDA:XAUUSD", TradingViewSymbols.find(gold.id))

        val silver = WatchCatalog.find("XAG/USD")!!
        assertEquals(listOf("gold_api_xag"), silver.defaultSources)
        assertEquals("OANDA:XAGUSD", TradingViewSymbols.find(silver.id))

        val dow = WatchCatalog.find("DOW")!!
        assertEquals(listOf("yahoo_dow"), dow.defaultSources)
        assertEquals("BLACKBULL:US30", TradingViewSymbols.find(dow.id))
    }
}
