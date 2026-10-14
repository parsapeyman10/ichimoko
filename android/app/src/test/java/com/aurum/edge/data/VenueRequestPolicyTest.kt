package com.aurum.edge.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class VenueRequestPolicyTest {
    @Test fun `slow response does not form a FIFO of other symbol requests`() = runBlocking {
        val policy = VenueRequestPolicy()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slow = async { policy.read(gate) { started.complete(Unit); release.await() } }
        started.await()
        assertTrue(runCatching { policy.read(gate) { error("must not send") } }.exceptionOrNull() is PublisherOccupied)
        assertEquals("available", policy.read(okx) { "available" })
        release.complete(Unit); slow.await()
    }

    private val gate = CryptoRadarCandleClient.Venue.GATE
    private val okx = CryptoRadarCandleClient.Venue.OKX

    @Test fun `startup does not mark every publisher rate limited and ready route can probe immediately`() = runBlocking {
        var route = AppNetwork.Route(ready = false)
        var calls = 0
        val policy = VenueRequestPolicy(route = { route })
        val error = runCatching { policy.read(gate) { calls++ } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("شبکه")); assertEquals(0, calls)
        route = AppNetwork.Route(ready = true, revision = 1)
        policy.read(gate) { calls++ }
        assertEquals(1, calls)
    }

    @Test fun `429 respects retry after without blocking other venues and route change permits recovery`() = runBlocking {
        var now = 100_000L
        var route = AppNetwork.Route()
        val policy = VenueRequestPolicy({ route }, { now }, { now += it })
        val error = runCatching { policy.read(gate) { throw VenueHttpFailure(429, "120") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("429"))
        assertTrue(runCatching { policy.read(gate) { throw AssertionError("must not send") } }.exceptionOrNull()!!.message!!.contains("120"))
        assertEquals("ok", policy.read(okx) { "ok" })
        route = AppNetwork.Route(ready = true, revision = 1)
        assertEquals("recovered", policy.read(gate) { "recovered" })
        runCatching { policy.read(gate) { throw VenueHttpFailure(429, "120") } }
        now += 120_000L
        assertEquals("expired", policy.read(gate) { "expired" })
    }

    @Test fun `socket failure is not described as quota and 404 does not ban other pairs`() = runBlocking {
        var now = 10_000L
        val policy = VenueRequestPolicy(clock = { now }, pause = { now += it })
        val network = runCatching { policy.read(gate) { throw IOException("private details") } }.exceptionOrNull()
        assertTrue(network!!.message!!.contains("شبکه"))
        assertFalse(network.message!!.contains("private"))
        assertFalse(network.message!!.contains("سهمیه"))
        runCatching { policy.read(okx) { throw VenueHttpFailure(404, null) } }
        assertEquals(1, policy.read(okx) { 1 })
    }

    @Test fun `request starts are paced even when responses arrive instantly`() = runBlocking {
        var now = 10_000L
        val policy = VenueRequestPolicy(clock = { now }, pause = { now += it })
        val starts = (1..3).map { policy.read(gate) { now } }
        assertEquals(listOf(10_000L, 10_350L, 10_700L), starts)
    }

    @Test fun `route cancellation and bad candle data do not poison next attempt`() = runBlocking {
        var now = 10_000L
        var route = AppNetwork.Route()
        val policy = VenueRequestPolicy({ route }, { now }, { now += it })
        runCatching { policy.read(gate) { route = route.copy(revision = 1); throw IOException("canceled") } }
        assertEquals(1, policy.read(gate) { 1 })
        runCatching { policy.read(gate) { throw DataFeedException("invalid candle") } }
        assertEquals(2, policy.read(gate) { 2 })
        val canceled = runCatching { policy.read(gate) { throw CancellationException() } }.exceptionOrNull()
        assertTrue(canceled is CancellationException)
        assertEquals(3, policy.read(gate) { 3 })
    }

    @Test fun `retry after accepts HTTP date and safely bounds malformed values`() {
        val now = 1_800_000_000_000L
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 90_000).atZone(ZoneOffset.UTC))
        assertEquals(90_000L, VenueRequestPolicy.retryDelay(date, now))
        assertEquals(60_000L, VenueRequestPolicy.retryDelay("garbage", now))
        assertEquals(86_400_000L, VenueRequestPolicy.retryDelay("999999999", now))
    }
}
