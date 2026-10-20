package com.aurum.edge.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Regression guard for the reported silver bug: `fetchQuote("XAG/USD")` used to call the
 * Gold-API XAU endpoint, which rejected every non-XAU symbol — so the live silver tick always
 * failed. The requested metal must be part of the request AND the answer must match it.
 */
class MetalQuoteRoutingTest {
    private fun spot(requested: MutableList<String>, answeredSymbol: String? = null): SpotFallbackClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requested += chain.request().url.toString()
            val metal = chain.request().url.pathSegments.last()
            val symbol = answeredSymbol ?: metal
            val updatedAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
            val json = """{"symbol":"$symbol","price":"31.5","updatedAt":"$updatedAt"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }).build()
        return SpotFallbackClient(http)
    }

    @Test fun `silver asks for XAG and keeps the gold-api label`() = runBlocking {
        val requested = mutableListOf<String>()
        val tick = spot(requested).fetchQuote("XAG/USD")
        assertEquals(listOf("https://api.gold-api.com/price/XAG"), requested)
        assertEquals("Gold-API", tick.provider)
        assertEquals(31.5, tick.price, 0.0)
        assertTrue(tick.at > 0L)
    }

    @Test fun `gold still asks for XAU`() = runBlocking {
        val requested = mutableListOf<String>()
        assertEquals("Gold-API", spot(requested).fetchQuote("XAU/USD").provider)
        assertEquals(listOf("https://api.gold-api.com/price/XAU"), requested)
    }

    @Test fun `a gold answer is never accepted as silver`() = runBlocking {
        val requested = mutableListOf<String>()
        // Gold-API answers XAU for an XAG request: the gold price must be rejected, and the
        // Swissquote fallback (same mock, not a valid BBO payload either) must not fabricate
        // a 31.5 silver quote.
        val result = runCatching { spot(requested, answeredSymbol = "XAU").fetchQuote("XAG/USD") }
        assertTrue("a mismatched metal answer must fail closed", result.isFailure)
        assertTrue(requested.first(), requested.first().endsWith("/price/XAG"))
    }
}
