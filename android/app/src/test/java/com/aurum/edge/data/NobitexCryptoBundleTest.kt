package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Nobitex is the crypto reference: one venue, three independent frames, no silent mixing. */
class NobitexCryptoBundleTest {
    private val now = System.currentTimeMillis()

    @Before fun reset() { SourceIntegrity.shared.resetForTests() }

    private fun closedSeries(frame: Interval, count: Int, price: Double = 100.0): List<Candle> {
        val end = now / frame.millis * frame.millis
        return (0 until count).map { i ->
            Candle(end - (count - i) * frame.millis,
                price, price + 1.0, price - 1.0, price, 10.0, closed = true)
        }
    }

    private fun bundleWith(
        read: suspend (String, Interval, Int, Int, Long) -> PublicCandleHistoryClient.Result,
    ) = NobitexCryptoBundle(NobitexHistoryClient(), read)

    @Test fun `all three frames come from nobitex and are fetched independently`() = runBlocking {
        val calls = mutableListOf<Interval>()
        val bundle = bundleWith { symbol, frame, desired, _, _ ->
            assertEquals("BTC/USDT", symbol)
            calls += frame
            PublicCandleHistoryClient.Result(
                closedSeries(frame, desired), NobitexHistoryClient.PROVIDER)
        }

        val book = bundle.fetch("BTC/USDT", now)
        assertEquals(NobitexHistoryClient.PROVIDER, book.provider)
        assertEquals(listOf(Interval.M5, Interval.M15, Interval.H1), calls)
        assertEquals(HistoryPolicy.fetchSize(Interval.M5), book.frames.getValue(Interval.M5).size)
        assertEquals(HistoryPolicy.fetchSize(Interval.M15), book.frames.getValue(Interval.M15).size)
        assertEquals(HistoryPolicy.fetchSize(Interval.H1), book.frames.getValue(Interval.H1).size)
        assertEquals(book.frames.getValue(Interval.M5), book.m5)
    }

    @Test fun `a short or stale frame fails the whole reference instead of mixing venues`() = runBlocking {
        val bundle = bundleWith { _, frame, desired, _, _ ->
            val count = if (frame == Interval.H1) 3 else desired
            PublicCandleHistoryClient.Result(closedSeries(frame, count), NobitexHistoryClient.PROVIDER)
        }
        try {
            bundle.fetch("BTC/USDT", now)
            fail("an incomplete H1 frame must not produce a usable crypto reference")
        } catch (expected: DataFeedException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("1H"))
        }
    }

    @Test fun `a pair nobitex does not serve is rejected before any request`() = runBlocking {
        var called = false
        val bundle = bundleWith { _, _, _, _, _ ->
            called = true
            PublicCandleHistoryClient.Result(emptyList(), NobitexHistoryClient.PROVIDER)
        }
        try {
            bundle.fetch("AAPL", now)
            fail("a non-crypto symbol must not be treated as a Nobitex pair")
        } catch (expected: DataFeedException) {
            assertTrue(expected.message.orEmpty(), expected.message.isNullOrBlank().not())
        }
        assertFalse(called)
    }
}
