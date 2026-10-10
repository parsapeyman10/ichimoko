package com.aurum.edge.engine

import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.data.DataFeedException
import com.aurum.edge.data.PublicCandleHistoryClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCandleHistoryClientTest {
    private val client = PublicCandleHistoryClient()
    private val startMs = 1_700_000_000_000L - (1_700_000_000_000L % Interval.M1.millis)

    private fun yahooJson(rows: Int = HistoryPolicy.TARGET_CANDLES + 2, currency: String = "USD"): String {
        val ts = (0 until rows).joinToString(",") { ((startMs / 1000L) + it * 60L).toString() }
        fun nums(offset: Double) = (0 until rows).joinToString(",") { String.format(java.util.Locale.US, "%.5f", 1.10 + offset + it * 0.00001) }
        val open = nums(0.0)
        val high = nums(0.002)
        val low = nums(-0.002)
        val close = nums(0.001)
        val volume = (0 until rows).joinToString(",") { "0" }
        return """{"chart":{"result":[{"meta":{"symbol":"EURUSD=X","currency":"$currency"},"timestamp":[$ts],"indicators":{"quote":[{"open":[$open],"high":[$high],"low":[$low],"close":[$close],"volume":[$volume]}]}}],"error":null}}"""
    }

    @Test fun `yahoo public history is identity checked sorted and keeps deep cache window`() {
        val bars = client.parseYahooChart(
            yahooJson(),
            expectedSymbol = "EUR/USD",
            expectedYahooSymbol = "EURUSD=X",
            interval = Interval.M1,
            now = startMs + (HistoryPolicy.TARGET_CANDLES + 2L) * Interval.M1.millis,
        )
        assertEquals(HistoryPolicy.TARGET_CANDLES + 2, bars.size)
        assertEquals(startMs, bars.first().time)
        assertEquals(startMs + (HistoryPolicy.TARGET_CANDLES + 1L) * Interval.M1.millis, bars.last().time)
        assertTrue(bars.all { it.closed && it.high >= it.open && it.low <= it.close })
    }

    @Test fun `short real live window is retained without a three thousand bar gate`() {
        val bars = client.parseYahooChart(
            yahooJson(rows = HistoryPolicy.LIVE_REQUEST_CANDLES + 10),
            expectedSymbol = "EUR/USD",
            expectedYahooSymbol = "EURUSD=X",
            interval = Interval.M1,
            now = startMs + 400L * Interval.M1.millis,
            trimSize = HistoryPolicy.LIVE_REQUEST_CANDLES,
        )
        assertEquals(HistoryPolicy.LIVE_REQUEST_CANDLES, bars.size)
        assertEquals(startMs + 10L * Interval.M1.millis, bars.first().time)
    }

    @Test fun `wrong public history identity fails closed`() {
        assertThrows(DataFeedException::class.java) {
            client.parseYahooChart(
                yahooJson(currency = "EUR"),
                expectedSymbol = "EUR/USD",
                expectedYahooSymbol = "EURUSD=X",
                interval = Interval.M1,
                now = startMs + 4_000L * Interval.M1.millis,
            )
        }
    }
}
