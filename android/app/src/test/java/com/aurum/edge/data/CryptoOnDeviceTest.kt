package com.aurum.edge.data

import com.aurum.edge.core.Interval
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

    @Test fun `crypto appears in the watch catalog and the chart picker`() {
        CryptoCatalog.ids.forEach { id ->
            assertNotNull("missing from watch catalog: $id", WatchCatalog.find(id))
            assertTrue("not chartable: $id", id in WatchCatalog.chartSymbols)
        }
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

    // ---------- kline parsing ----------

    private fun kline(openTime: Long, o: String, h: String, l: String, c: String,
                      v: String, closeTime: Long) =
        """[$openTime,"$o","$h","$l","$c","$v",$closeTime,"0",0,"0","0","0"]"""

    @Test fun `real klines parse into closed candles in order`() {
        val now = 1_790_000_000_000L
        val body = "[" + listOf(
            kline(now - 900_000, "84370.91", "84465.00", "84308.00", "84410.00", "139.45", now - 600_001),
            kline(now - 600_000, "84410.00", "84493.42", "84398.12", "84448.00", "128.72", now - 300_001),
        ).joinToString(",") + "]"

        val candles = BinanceHistoryClient.parseKlines(body, "BTC/USDT", Interval.M15, now)
        assertEquals(2, candles.size)
        assertEquals(now - 900_000, candles[0].time)
        assertEquals(84370.91, candles[0].open, 1e-9)
        assertEquals(84465.00, candles[0].high, 1e-9)
        assertEquals(84410.00, candles[0].close, 1e-9)
        assertTrue(candles[0].time < candles[1].time)
    }

    @Test fun `the still-forming bar is discarded`() {
        val now = 1_790_000_000_000L
        val body = "[" + listOf(
            kline(now - 900_000, "100", "110", "90", "105", "1", now - 600_001),
            // closeTime in the future => this bar has not finished yet
            kline(now - 600_000, "105", "115", "95", "108", "1", now + 300_000),
        ).joinToString(",") + "]"

        val candles = BinanceHistoryClient.parseKlines(body, "BTC/USDT", Interval.M15, now)
        assertEquals(1, candles.size)
        assertEquals(now - 900_000, candles[0].time)
    }

    @Test fun `impossible or malformed rows are dropped rather than traded on`() {
        val now = 1_790_000_000_000L
        val body = "[" +
            kline(now - 900_000, "100", "110", "90", "105", "1", now - 600_001) + "," +
            // high below the body: not a real candle
            kline(now - 800_000, "100", "95", "90", "105", "1", now - 600_001) + "," +
            // non-numeric price
            kline(now - 700_000, "abc", "110", "90", "105", "1", now - 600_001) + "," +
            // zero price
            kline(now - 650_000, "0", "110", "90", "105", "1", now - 600_001) +
            "]"
        val candles = BinanceHistoryClient.parseKlines(body, "BTC/USDT", Interval.M15, now)
        assertEquals(1, candles.size)
    }

    @Test fun `an empty or error response fails loudly instead of returning nothing usable`() {
        assertThrows(DataFeedException::class.java) {
            BinanceHistoryClient.parseKlines("[]", "BTC/USDT", Interval.M15)
        }
        assertThrows(DataFeedException::class.java) {
            BinanceHistoryClient.parseKlines("""{"code":-1121,"msg":"Invalid symbol."}""",
                "BTC/USDT", Interval.M15)
        }
    }

    // ---------- quote parsing ----------

    @Test fun `book ticker parses and rejects a crossed or empty book`() {
        val good = BinanceHistoryClient.parseBookTicker(
            """{"symbol":"BTCUSDT","bidPrice":"84000.10","bidQty":"1","askPrice":"84000.90","askQty":"1"}""")
        assertNotNull(good)
        assertEquals(84000.10, good!!.bid, 1e-9)
        assertEquals(84000.90, good.ask, 1e-9)

        // ask below bid is a crossed book: never usable as a price
        assertNull(BinanceHistoryClient.parseBookTicker(
            """{"bidPrice":"84000.90","askPrice":"84000.10"}"""))
        assertNull(BinanceHistoryClient.parseBookTicker("""{"bidPrice":"0","askPrice":"1"}"""))
        assertNull(BinanceHistoryClient.parseBookTicker("""{}"""))
        assertNull(BinanceHistoryClient.parseBookTicker("not json"))
    }

    // ---------- interval mapping ----------

    @Test fun `supported intervals map to binance codes`() {
        assertEquals("5m", BinanceHistoryClient.interval(Interval.M5))
        assertEquals("15m", BinanceHistoryClient.interval(Interval.M15))
        assertEquals("1h", BinanceHistoryClient.interval(Interval.H1))
    }
}
