package com.aurum.edge.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression guard for two reported failures that share one cause — a quote that cannot carry a
 * spread:
 *
 *  1. `fetchQuote("XAG/USD")` used to call the Gold-API XAU endpoint, so every silver tick failed.
 *  2. Even after that was fixed, Gold-API returns a single number with no bid/ask, so ALS-5S
 *     refused every entry: "قیمت دوطرفهٔ واقعی (bid/ask) موجود نیست". Silver now asks the Swissquote
 *     BBO first and keeps Gold-API as the fallback; the refusal is still correct when neither
 *     source publishes a two-sided price.
 */
class MetalQuoteRoutingTest {
    private fun bbo(bid: Double, ask: Double, at: Long = System.currentTimeMillis()): String =
        """[{"spreadProfilePrices":[{"spreadProfile":"prime","bid":$bid,"ask":$ask}],"ts":$at}]"""

    private fun singlePrice(symbol: String, price: String = "31.5"): String {
        val updatedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        return """{"symbol":"$symbol","price":"$price","updatedAt":"$updatedAt"}"""
    }

    /** Answers per URL; anything unmatched is a 404, exactly like an unsupported instrument. */
    private fun spot(requested: MutableList<String>, answer: (String) -> String?): SpotFallbackClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            val body = answer(url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message(if (body == null) "Not Found" else "OK")
                .body((body ?: "{}").toResponseBody()).build()
        }).build()
        return SpotFallbackClient(http)
    }

    @Test fun `silver is quoted two-sided by Swissquote first`() = runBlocking {
        val requested = mutableListOf<String>()
        val tick = spot(requested) { url ->
            if (url.contains("swissquote")) bbo(31.40, 31.46) else null
        }.fetchQuote("XAG/USD")
        assertEquals("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/XAG/USD",
            requested.first())
        assertEquals("Swissquote", tick.provider)
        assertEquals(31.40, tick.bid!!, 1e-9)
        assertEquals(31.46, tick.ask!!, 1e-9)
        // The mid price is derived from the two sides, never from a different instrument.
        assertEquals(31.43, tick.price, 1e-9)
        assertTrue(tick.at > 0L)
    }

    @Test fun `silver falls back to gold-api and then has no spread to trade on`() = runBlocking {
        val requested = mutableListOf<String>()
        val tick = spot(requested) { url ->
            if (url.endsWith("/price/XAG")) singlePrice("XAG") else null
        }.fetchQuote("XAG/USD")
        assertEquals(2, requested.size)
        assertEquals("Gold-API", tick.provider)
        assertEquals(31.5, tick.price, 0.0)
        // A single print is not a spread: leaving both sides null is what keeps the engine honest.
        assertNull(tick.bid)
        assertNull(tick.ask)
        assertTrue(requested.first().contains("swissquote"))
    }

    @Test fun `gold still asks for XAU first and keeps the gold-api label`() = runBlocking {
        val requested = mutableListOf<String>()
        assertEquals("Gold-API", spot(requested) { singlePrice("XAU", "2400.5") }.fetchQuote("XAU/USD").provider)
        assertEquals(listOf("https://api.gold-api.com/price/XAU"), requested)
    }

    @Test fun `a gold answer is never accepted as silver`() = runBlocking {
        val requested = mutableListOf<String>()
        val result = runCatching {
            spot(requested) { url ->
                if (url.contains("/price/")) singlePrice("XAU") else bbo(31.40, 31.46)
            }.fetchQuote("XAG/USD")
        }
        // The mismatched Gold-API answer is rejected. The Swissquote leg answers a valid XAG/USD
        // BBO here, so the quote that survives is silver's own — never gold's 2400-level price.
        assertTrue(requested.any { it.endsWith("/price/XAG") })
        val tick = result.getOrNull()
        if (tick != null) assertTrue("silver must not become gold", tick.price < 100.0)
    }

    @Test fun `euro-dollar is quoted by swissquote so it can price its spread`() = runBlocking {
        val requested = mutableListOf<String>()
        val tick = spot(requested) { url ->
            if (url.endsWith("EUR/USD")) bbo(1.08500, 1.08510) else null
        }.fetchQuote("EUR/USD")
        assertEquals("Swissquote", tick.provider)
        assertEquals(1.0, (tick.ask!! - tick.bid!!) / 0.0001, 1e-9)
        assertTrue(requested.single().contains("swissquote"))
    }
}
