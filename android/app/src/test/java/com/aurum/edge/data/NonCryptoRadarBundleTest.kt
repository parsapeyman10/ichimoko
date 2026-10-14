package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NonCryptoRadarBundleTest {
    private val now = 1_800_000_000_000L

    private fun closedM5Series(price: Double = 2400.0): List<Candle> {
        val step = Interval.M5.millis
        val end = now / step * step
        return (0 until HistoryPolicy.LIVE_REQUEST_CANDLES).map { i ->
            Candle(end - (HistoryPolicy.LIVE_REQUEST_CANDLES - i) * step,
                price, price + 1.0, price - 1.0, price, 10.0, closed = true)
        }
    }

    @Test fun `each non-crypto symbol fetches only 1500 M5 candles from its dedicated source and converts M15 and H1`() = runBlocking {
        val calls = mutableListOf<Triple<String, Interval, SourceRouting.HistorySource>>()
        val bundle = NonCryptoRadarBundle { symbol, frame, source, _ ->
            calls += Triple(symbol, frame, source)
            PublicCandleHistoryClient.Result(closedM5Series(), source.providerLabel)
        }

        val gold = bundle.fetch("XAU/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY_XAU, gold.source)
        assertEquals(1500, gold.frames.getValue(Interval.M5).size)
        assertEquals(500, gold.frames.getValue(Interval.M15).size)
        assertEquals(125, gold.frames.getValue(Interval.H1).size)

        val silver = bundle.fetch("XAG/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY_XAG, silver.source)

        val dow = bundle.fetch("DOW", "", now)
        assertEquals(SourceRouting.HistorySource.YAHOO_DOW, dow.source)

        val eur = bundle.fetch("EUR/USD", "", now)
        assertEquals(SourceRouting.HistorySource.YAHOO_EURUSD, eur.source)

        // Only M5 is fetched over the network; M15 and H1 are converted from M5
        assertEquals(4, calls.size)
        assertTrue(calls.all { it.second == Interval.M5 })
        assertEquals(4, calls.map { it.third }.distinct().size)
    }

    @Test fun `incomplete M5 history is rejected`() = runBlocking {
        val bundle = NonCryptoRadarBundle { _, _, source, _ ->
            PublicCandleHistoryClient.Result(closedM5Series().drop(1), source.providerLabel)
        }
        assertTrue(runCatching { bundle.fetch("XAU/USD", "", now) }.isFailure)
    }
}
