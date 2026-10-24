package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourceProgressTest {
    private fun health(source: String) = SourceHealth(source, source, "HEALTHY",
        2_000L, 15L, 1, 1)

    @Test fun `one finished source does not wait for another and leaves its quote untouched`() {
        val state = WatchState(quotes = mapOf("BTC/USDT" to mapOf("crypto" to
            Quote("BTC/USDT", "BTC", price = 100.0, ts = 1_000L, sourceId = "crypto"))))
        val afterFx = mergeWatchSource(state, "fx", listOf(WatchQuoteUpdate("EUR/USD", "$",
            Quote("EUR/USD", "EUR", price = 1.2, ts = 2_000L, sourceId = "fx"))), health("fx"))
        assertEquals(100.0, afterFx.quotes.getValue("BTC/USDT").getValue("crypto").price!!, 0.0)
        assertEquals(1.2, afterFx.quotes.getValue("EUR/USD").getValue("fx").price!!, 0.0)
        assertEquals(setOf("fx"), afterFx.sourceHealth.keys)
    }

    @Test fun `failed source retains the original price and time only as stale`() {
        val original = Quote("BTC/USDT", "BTC", price = 100.0, ts = 1_000L, sourceId = "crypto")
        val state = WatchState(quotes = mapOf("BTC/USDT" to mapOf("crypto" to original)))
        val failed = mergeWatchSource(state, "crypto", listOf(WatchQuoteUpdate("BTC/USDT", "$",
            Quote("BTC/USDT", "BTC", error = "429", ts = 2_000L, sourceId = "crypto"))), health("crypto"))
        val retained = failed.quotes.getValue("BTC/USDT").getValue("crypto")
        assertEquals(original.ts, retained.ts)
        assertEquals(original.price, retained.price)
        assertTrue(retained.stale)
        assertEquals("429", retained.error)
    }
}
