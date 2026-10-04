package com.aurum.edge.data

import com.aurum.edge.core.MarketHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Crypto runs entirely on the phone: Binance public market data, no key, no backend.
 *
 * These tests pin the parts that silently corrupt a trading screen when they are wrong —
 * forming bars treated as closed, malformed rows accepted, a 24/7 venue muted by the forex
 * weekend gate, and prices printed at the wrong scale.
 */
class CryptoOnDeviceTest {

    // ---------- catalog ----------

    @Test fun `crypto symbols are chartable and recognised`() {
        assertTrue(CryptoCatalog.isCrypto("BTC/USDT"))
        assertTrue(CryptoCatalog.isCrypto("btc/usdt"))      // case-insensitive
        assertFalse(CryptoCatalog.isCrypto("XAU/USD"))
        assertFalse(CryptoCatalog.isCrypto("EUR/USD"))
        assertNotNull(CryptoCatalog.find("DOGE/USDT"))
        assertEquals("BTCUSDT", CryptoCatalog.find("BTC/USDT")!!.binance)
    }

    @Test fun `only the crypto majors sit in the watchlist, the rest stay searchable`() {
        // A 45-row watchlist is noise and polls the provider for rows nobody reads.
        assertTrue(CryptoCatalog.watchlistSeed.size <= 8)
        CryptoCatalog.watchlistSeed.forEach { coin ->
            assertNotNull("missing from watch catalog: ${coin.id}", WatchCatalog.find(coin.id))
            assertTrue("not chartable: ${coin.id}", coin.id in WatchCatalog.chartSymbols)
        }
        assertTrue("BTC/USDT" in CryptoCatalog.watchlistSeed.map { it.id })
        // The wider seed is still resolvable even though it is not a watch row.
        assertNotNull(CryptoCatalog.find("PEPE/USDT"))
        // The existing forex workspace must be untouched.
        assertTrue("XAU/USD" in WatchCatalog.chartSymbols)
        assertTrue("EUR/USD" in WatchCatalog.chartSymbols)
    }

    @Test fun `display decimals follow the instrument, not a fixed two`() {
        assertEquals(2, CryptoCatalog.digitsFor("BTC/USDT"))
        assertEquals(5, CryptoCatalog.digitsFor("DOGE/USDT"))
        assertEquals(4, CryptoCatalog.digitsFor("XRP/USDT"))
        assertEquals(2, CryptoCatalog.digitsFor("XAU/USD"))
        assertEquals(3, CryptoCatalog.digitsFor("USD/JPY"))
        assertEquals(5, CryptoCatalog.digitsFor("EUR/USD"))
    }

    // ---------- the weekend gate ----------

    @Test fun `crypto is never weekend-closed but forex still is`() {
        // A Saturday, when the forex market is definitely shut.
        val saturday = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        val millis = saturday.toInstant().toEpochMilli()

        assertTrue(MarketHours.forexWeekendClosed(millis))
        assertTrue(MarketHours.weekendClosedFor("XAU/USD", millis))
        assertTrue(MarketHours.weekendClosedFor("EUR/USD", millis))
        assertFalse(MarketHours.weekendClosedFor("BTC/USDT", millis))
    }


    // ---------- the seeded list ----------

    @Test fun `the offline seed covers the major coins and stays consistent`() {
        assertTrue("seed is too small: ${CryptoCatalog.symbols.size}", CryptoCatalog.symbols.size >= 30)
        val bases = CryptoCatalog.symbols.map { it.id.substringBefore('/') }
        listOf("BTC", "ETH", "SOL", "XRP", "DOGE", "SHIB", "PEPE", "TON", "DOT", "LTC",
            "ATOM", "NEAR", "ARB", "OP", "SUI", "AAVE").forEach {
            assertTrue("missing from seed: $it", it in bases)
        }
        // No duplicates, and every row must be a real Binance USDT ticker.
        assertEquals(bases.distinct().size, bases.size)
        CryptoCatalog.symbols.forEach {
            assertEquals(it.id.replace("/", ""), it.binance)
            assertTrue("bad digits for ${it.id}", it.digits in 0..8)
        }
    }

    @Test fun `every seeded coin resolves even when it is not a watch row`() {
        CryptoCatalog.symbols.forEach { coin ->
            assertNotNull("does not resolve: ${coin.id}", CryptoCatalog.find(coin.id))
            assertTrue("bad digits for ${coin.id}", coin.digits in 0..8)
        }
    }

    @Test fun `the watchlist source for crypto actually exists`() {
        // A catalog row naming a provider the fetcher does not know renders as a row that
        // can never load. Every crypto row must point at a registered source.
        val ids = SourceCatalog.all.map { it.id }.toSet()
        CryptoCatalog.watchlistSeed.forEach { coin ->
            WatchCatalog.find(coin.id)!!.defaultSources.forEach { source ->
                assertTrue("unknown source '$source' for ${coin.id}", source in ids)
            }
        }
    }

    @Test fun `binance timestamps are read as millis, not seconds`() {
        // closeTime is already epoch millis; treating it as seconds would put the receipt
        // tens of thousands of years in the future.
        assertEquals(SourceTime.UNIX_MILLIS, SourceCatalog.binance.timestampMode)
    }


    // ---------- the fixes requested after field use ----------

    @Test fun `a 24-7 venue is never shown a weekend-closed banner`() {
        val saturday = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val crypto = MarketHours.sessionWindowFor("BTC/USDT", saturday)
        assertFalse(crypto.closed)
        assertNull(crypto.nextChangeAt)      // nothing to wait for

        val gold = MarketHours.sessionWindowFor("XAU/USD", saturday)
        assertTrue(gold.closed)              // forex genuinely is shut
    }

    @Test fun `global gold has a source that serves it`() {
        val row = WatchCatalog.find("XAU/USD")!!
        assertEquals("gold_api_public", row.defaultSources.single())
        assertEquals("XAU", row.providerCodes["gold_api_public"])
        assertTrue(SourceCatalog.all.any { it.id == "gold_api_public" })
        // Yahoo stays as a secondary mapping rather than being the one that must work.
        assertTrue("stocks_yahoo" in row.providerCodes)
    }
}
