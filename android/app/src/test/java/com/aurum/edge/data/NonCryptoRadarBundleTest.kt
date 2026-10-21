package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NonCryptoRadarBundleTest {
    private val now = System.currentTimeMillis()

    @Before fun reset() { SourcePromotion.resetForTests() }

    private fun closedSeries(frame: Interval, count: Int, price: Double = 2400.0): List<Candle> {
        val end = now / frame.millis * frame.millis
        return (0 until count).map { i ->
            Candle(end - (count - i) * frame.millis,
                price, price + 1.0, price - 1.0, price, 10.0, closed = true)
        }
    }

    @Test fun `each non-crypto symbol fetches M5 M15 H1 independently from its dedicated source`() = runBlocking {
        val calls = mutableListOf<Triple<String, Interval, SourceRouting.HistorySource>>()
        val bundle = NonCryptoRadarBundle { symbol, frame, source, _ ->
            calls += Triple(symbol, frame, source)
            PublicCandleHistoryClient.Result(
                closedSeries(frame, HistoryPolicy.fetchSize(frame)), source.providerLabel)
        }

        val gold = bundle.fetch("XAU/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY_XAU, gold.source)
        assertEquals(400, gold.frames.getValue(Interval.M5).size)
        assertEquals(300, gold.frames.getValue(Interval.M15).size)
        assertEquals(250, gold.frames.getValue(Interval.H1).size)
        assertTrue(gold.priorFailures.isEmpty())

        assertEquals(SourceRouting.HistorySource.DUKASCOPY_XAG, bundle.fetch("XAG/USD", "", now).source)
        assertEquals(SourceRouting.HistorySource.YAHOO_DOW, bundle.fetch("DOW", "", now).source)
        assertEquals(SourceRouting.HistorySource.YAHOO_EURUSD, bundle.fetch("EUR/USD", "", now).source)

        // Three independent frame fetches per symbol — nothing is resampled from M5
        assertEquals(12, calls.size)
        assertEquals(setOf(Interval.M5, Interval.M15, Interval.H1), calls.map { it.second }.toSet())
        assertEquals(4, calls.map { it.third }.distinct().size)
    }

    @Test fun `unresponsive dedicated source is replaced and the responder becomes primary`() = runBlocking {
        val bundle = NonCryptoRadarBundle { _, frame, source, _ ->
            if (source == SourceRouting.HistorySource.YAHOO_EURUSD)
                throw DataFeedException("Yahoo unreachable")
            PublicCandleHistoryClient.Result(
                closedSeries(frame, HistoryPolicy.fetchSize(frame)), source.providerLabel)
        }
        val eur = bundle.fetch("EUR/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY, eur.source)
        assertEquals(1, eur.priorFailures.size)
        assertTrue(eur.priorFailures.single().contains("Yahoo"))
        assertEquals("DUKASCOPY", SourcePromotion.primaryId("EUR/USD", SourcePromotion.Kind.HISTORY))
    }

    @Test fun `spot gold never falls back to a publisher that cannot serve it`() = runBlocking {
        val requested = mutableListOf<SourceRouting.HistorySource>()
        val bundle = NonCryptoRadarBundle { _, _, source, _ ->
            requested += source
            throw DataFeedException("${source.providerLabel} unreachable")
        }
        assertTrue(runCatching { bundle.fetch("XAU/USD", "", now) }.isFailure)
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY_XAU), requested.distinct())
        assertTrue(requested.all { it == SourceRouting.HistorySource.DUKASCOPY_XAU })
    }

    @Test fun `incomplete history from every source is rejected`() = runBlocking {
        val bundle = NonCryptoRadarBundle { _, frame, source, _ ->
            PublicCandleHistoryClient.Result(
                closedSeries(frame, 100), source.providerLabel)
        }
        assertTrue(runCatching { bundle.fetch("XAU/USD", "", now) }.isFailure)
    }
}
