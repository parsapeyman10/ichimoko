package com.aurum.edge.data

import com.aurum.edge.core.TradeQuotePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexTradeQuoteTest {
    private val now = 1_780_000_000_000L
    private fun body(trades: String) = """{"status":"ok","trades":[$trades]}"""

    @Test fun `provider millisecond timestamp chooses newest actual trade not response time`() {
        val tick = NobitexTradeQuote.parse(body(
            """{"time":${now - 32_000},"price":"13.50"},{"time":${now - 2_000},"price":"13.75"}"""), now)
        assertEquals(now - 2_000, tick.at)
        assertEquals(13.75, tick.price, 0.0)
        assertTrue(TradeQuotePolicy.accepts(tick, now))
    }

    @Test fun `missing stale malformed and future trades never become synthetic ticks`() {
        listOf(
            body(""),
            body("""{"price":"100"}"""),
            body("""{"time":${now - 46_000},"price":"100"}"""),
            body("""{"time":${now + 11_000},"price":"100"}"""),
            body("""{"time":${now - 2_000},"price":"NaN"}"""),
            """{"status":"failed","trades":[{"time":$now,"price":"100"}]}""",
        ).forEach { assertThrows(DataFeedException::class.java) { NobitexTradeQuote.parse(it, now) } }
    }
}
