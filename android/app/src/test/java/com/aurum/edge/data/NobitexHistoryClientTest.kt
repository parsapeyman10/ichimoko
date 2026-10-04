package com.aurum.edge.data

import com.aurum.edge.core.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nobitex is the crypto source for networks where Binance answers 451.
 *
 * Its payload is TradingView's UDF shape — parallel arrays — which fails differently from
 * Binance's rows: a short column does not throw, it silently shifts every price onto the
 * wrong bar. That is the main thing pinned here.
 */
class NobitexHistoryClientTest {

    private fun udf(t: String, o: String, h: String, l: String, c: String, v: String = "") =
        """{"s":"ok","t":[$t],"o":[$o],"h":[$h],"l":[$l],"c":[$c]""" +
            (if (v.isEmpty()) "" else ""","v":[$v]""") + "}"

    @Test fun `udf columns map onto candles with seconds converted to millis`() {
        val candles = NobitexHistoryClient.parseUdf(
            udf("1790001000,1790004600", "85500.12,85900.0", "86200.0,85987.09",
                "85500.1,85508.0", "85822.09,85700.01", "3.22,1.32")
        )
        assertEquals(2, candles.size)
        assertEquals(1790001000_000L, candles[0].time)      // seconds -> millis
        assertEquals(85500.12, candles[0].open, 1e-9)
        assertEquals(86200.0, candles[0].high, 1e-9)
        assertEquals(85822.09, candles[0].close, 1e-9)
        assertTrue(candles[0].time < candles[1].time)
    }

    @Test fun `a short column is rejected rather than shifting prices onto wrong bars`() {
        assertThrows(DataFeedException::class.java) {
            NobitexHistoryClient.parseUdf(
                udf("1790001000,1790004600", "85500.12", "86200.0,85987.09",
                    "85500.1,85508.0", "85822.09,85700.01")
            )
        }
    }

    @Test fun `status fields are honoured instead of being read as a flat market`() {
        assertThrows(DataFeedException::class.java) {
            NobitexHistoryClient.parseUdf("""{"s":"no_data"}""")
        }
        assertThrows(DataFeedException::class.java) {
            NobitexHistoryClient.parseUdf("""{"s":"error","errmsg":"bad symbol"}""")
        }
        assertThrows(DataFeedException::class.java) { NobitexHistoryClient.parseUdf("nonsense") }
    }

    @Test fun `impossible candles are dropped`() {
        // high below the body, and a zero price
        val candles = NobitexHistoryClient.parseUdf(
            udf("1790001000,1790004600,1790008200",
                "100,100,0", "110,95,110", "90,90,90", "105,105,105")
        )
        assertEquals(1, candles.size)
    }

    @Test fun `tickers and resolutions follow the app's symbol shape`() {
        assertEquals("BTCUSDT", NobitexHistoryClient.ticker("BTC/USDT"))
        assertEquals("ETHUSDT", NobitexHistoryClient.ticker("eth/usdt"))
        assertEquals(null, NobitexHistoryClient.ticker("BTCUSDT"))
        assertEquals("60", NobitexHistoryClient.resolution(Interval.H1))
        assertEquals("D", NobitexHistoryClient.resolution(Interval.D1))
        assertEquals("5", NobitexHistoryClient.resolution(Interval.M5))
    }
}
