package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards the three changes that brought the app's traffic down. Each test states the behaviour
 * that must hold, not an exact byte count: bytes depend on the provider, request counts do not.
 */
class DataUsageBudgetTest {

    @Before fun reset() {
        SpotFallbackClient.clearQuoteCache()
        SpotFallbackClient.resetYahooProbe()
    }

    @After fun clean() {
        SpotFallbackClient.clearQuoteCache()
        SpotFallbackClient.resetYahooProbe()
    }

    // ── shared quote ────────────────────────────────────────────────────────────────────────

    @Test fun `two components asking within the window share one request`() = runBlocking {
        var now = 1_000L
        val calls = AtomicInteger()
        val coalescer = QuoteCoalescer(ttlMs = 1_200L) { now }
        val load: suspend () -> PriceTick = { calls.incrementAndGet(); PriceTick(1.0850, now) }

        val first = coalescer.quote("EUR/USD", load)
        now += 400L
        val second = coalescer.quote("EUR/USD", load)
        assertEquals(first, second)
        assertEquals("the service and the UI must not download the same quote twice", 1, calls.get())

        // The window is short by design: a decision can never ride on an old price.
        now += 900L
        coalescer.quote("EUR/USD", load)
        assertEquals(2, calls.get())
    }

    @Test fun `concurrent callers are single-flight, not duplicated`() = runBlocking {
        val calls = AtomicInteger()
        var now = 5_000L
        val coalescer = QuoteCoalescer(ttlMs = 1_200L) { now }
        val results = (1..8).map {
            async(Dispatchers.Default) {
                coalescer.quote("XAU/USD") {
                    calls.incrementAndGet()
                    Thread.sleep(30)
                    PriceTick(2400.0, now)
                }
            }
        }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it.price == 2400.0 })
    }

    @Test fun `a failed read leaves nothing cached`() = runBlocking {
        var now = 9_000L
        var calls = 0
        val coalescer = QuoteCoalescer(ttlMs = 1_200L) { now }
        val failure = runCatching { coalescer.quote("XAG/USD") { calls++; throw DataFeedException("پاسخ نداد") } }
        assertTrue(failure.isFailure)
        // A fabricated or remembered quote would hide the outage and could authorize an entry.
        val retry = coalescer.quote("XAG/USD") { calls++; PriceTick(31.4, now) }
        assertEquals(31.4, retry.price, 0.0)
        assertEquals(2, calls)
    }

    @Test fun `different symbols never share an entry`() = runBlocking {
        val now = 12_000L
        var calls = 0
        val coalescer = QuoteCoalescer(ttlMs = 1_200L) { now }
        coalescer.quote("EUR/USD") { calls++; PriceTick(1.085, now) }
        val gold = coalescer.quote("XAU/USD") { calls++; PriceTick(2400.0, now) }
        assertEquals(2400.0, gold.price, 0.0)
        assertEquals(2, calls)
    }

    // ── bar-scoped radar history ───────────────────────────────────────────────────────────

    private fun bar(time: Long) = Candle(time, 1.085, 1.086, 1.084, 1.0855, 100.0, true)

    private fun frames(newestM5: Long) = mapOf(
        Interval.M5 to (0 until 300).map { bar(newestM5 - (299 - it) * Interval.M5.millis) },
        Interval.M15 to (0 until 250).map { bar(newestM5 - (249 - it) * Interval.M15.millis) },
        Interval.H1 to (0 until 220).map { bar(newestM5 - (219 - it) * Interval.H1.millis) },
    )

    @Test fun `a bundle lives until the next bar can exist, not for one sweep`() {
        val newest = 1_799_999_700_000L // aligned M5 bar open
        val cache = ScanHistoryCache()
        val stored = cache.record("EUR/USD", "Dukascopy", 7L, frames(newest), now = newest + 60_000L)
        assertEquals(newest + Interval.M5.millis + 2_000L, stored!!.validUntil)

        // 60 s sweeps inside the same bar: no reason to download 950 candles again.
        assertTrue(cache.isFresh("EUR/USD", newest + 120_000L))
        assertTrue(cache.isFresh("EUR/USD", newest + 280_000L))
        // The moment a newer bar can exist the data is re-read.
        assertNull(cache.get("EUR/USD", newest + Interval.M5.millis + 3_000L))
    }

    @Test fun `an expired or empty bundle is never stored`() {
        val newest = 1_799_999_700_000L
        val cache = ScanHistoryCache()
        assertNull(cache.record("EUR/USD", "Dukascopy", 7L, frames(newest),
            now = newest + Interval.M5.millis + 5_000L))
        assertNull(cache.record("EUR/USD", "Dukascopy", 7L, mapOf(Interval.M5 to emptyList()), now = newest))
        assertNull(cache.get("EUR/USD", newest))
    }

    @Test fun `invalidation forces a real download and is per symbol`() {
        val newest = 1_799_999_700_000L
        val cache = ScanHistoryCache()
        cache.record("EUR/USD", "Dukascopy", 7L, frames(newest), now = newest)
        cache.record("XAU/USD", "Dukascopy", 7L, frames(newest), now = newest)
        cache.invalidate("EUR/USD")
        assertNull(cache.get("EUR/USD", newest))
        assertTrue(cache.isFresh("XAU/USD", newest))
        cache.invalidate()
        assertNull(cache.get("XAU/USD", newest))
    }

    // ── Dow quote: one bar, not a whole day of candles ─────────────────────────────────────

    private fun yahoo(requested: MutableList<String>, answer: (String) -> Pair<Int, String>): SpotFallbackClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            requested += url
            val (code, body) = answer(url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("mock").body(body.toResponseBody()).build()
        }).build()
        return SpotFallbackClient(http)
    }

    private fun meta(price: String, at: Long): String =
        """{"chart":{"result":[{"meta":{"regularMarketPrice":$price,"regularMarketTime":${at / 1000}}}],"error":null}}"""

    @Test fun `the Dow quote asks for the smallest series and keeps the meta price`() = runBlocking {
        val requested = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val tick = yahoo(requested) { 200 to meta("41234.5", now) }.fetchQuote("DOW")
        assertEquals("Yahoo Dedicated (^DJI)", tick.provider)
        assertEquals(41234.5, tick.price, 0.0)
        assertEquals(1, requested.size)
        // interval=1d&range=1d: one bar instead of ~78, same meta block, ~20× fewer bytes.
        assertTrue(requested.single(), requested.single().contains("interval=1d&range=1d"))
    }

    @Test fun `a rejected light request falls back, then stops paying twice`() = runBlocking {
        val requested = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val spot = yahoo(requested) { url ->
            if (url.contains("interval=1d&range=1d")) 400 to """{"chart":{"result":null}}"""
            else 200 to meta("41234.5", now)
        }
        repeat(4) {
            spot.fetchQuote("DOW")
            SpotFallbackClient.clearQuoteCache() // force a real request each round
        }
        // Three light attempts were enough to conclude the endpoint rejects it, then one per poll.
        assertEquals(3, requested.count { it.contains("interval=1d&range=1d") })
        assertEquals(4, requested.count { it.contains("interval=5m&range=1d") })
        assertEquals(7, requested.size)
    }

    @Test fun `the passive stream is not a one-second poll`() {
        // 1 s kept the radio awake for a quote that only has to be younger than 45 s.
        assertEquals(5_000L, SpotFallbackClient.POLL_INTERVAL_MS)
    }
}
