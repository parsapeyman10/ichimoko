package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CryptoRadarBundleTest {
    private val now = System.currentTimeMillis()

    @Before fun reset() { SourcePromotion.resetForTests() }

    private fun closedSeries(frame: Interval, count: Int, price: Double = 100.0): List<Candle> {
        val end = now / frame.millis * frame.millis
        return (0 until count).map { i ->
            Candle(end - (count - i) * frame.millis,
                price, price + 1.0, price - 1.0, price, 10.0, closed = true)
        }
    }

    @Test fun `each crypto symbol fetches M5 M15 H1 independently from its dedicated venue`() = runBlocking {
        val calls = mutableListOf<Triple<String, Interval, CryptoRadarBundle.Source>>()
        val bundle = CryptoRadarBundle { symbol, frame, source ->
            calls += Triple(symbol, frame, source)
            PublicCandleHistoryClient.Result(
                closedSeries(frame, HistoryPolicy.fetchSize(frame)), "${source.label} ($symbol)")
        }

        val btc = bundle.fetch("BTC/USDT", now)
        assertEquals(CryptoRadarBundle.Source.BINANCE, btc.venue)
        assertEquals(400, btc.frames.getValue(Interval.M5).size)
        assertEquals(300, btc.frames.getValue(Interval.M15).size)
        assertEquals(250, btc.frames.getValue(Interval.H1).size)
        assertTrue(btc.priorFailures.isEmpty())

        assertEquals(CryptoRadarBundle.Source.OKX, bundle.fetch("ETH/USDT", now).venue)
        assertEquals(CryptoRadarBundle.Source.GATE, bundle.fetch("SOL/USDT", now).venue)
        assertEquals(CryptoRadarBundle.Source.MEXC, bundle.fetch("BNB/USDT", now).venue)
        assertEquals(CryptoRadarBundle.Source.KRAKEN, bundle.fetch("XRP/USDT", now).venue)

        assertEquals(15, calls.size)
        assertEquals(setOf(Interval.M5, Interval.M15, Interval.H1), calls.map { it.second }.toSet())
        assertEquals(5, calls.map { it.third }.distinct().size)
    }

    @Test fun `unresponsive dedicated venue is replaced and the responder becomes primary`() = runBlocking {
        val bundle = CryptoRadarBundle { symbol, frame, source ->
            if (source == CryptoRadarBundle.Source.BINANCE)
                throw DataFeedException("Binance unreachable")
            PublicCandleHistoryClient.Result(
                closedSeries(frame, HistoryPolicy.fetchSize(frame)), "${source.label} ($symbol)")
        }
        val btc = bundle.fetch("BTC/USDT", now)
        assertEquals(CryptoRadarBundle.Source.OKX, btc.venue)
        assertEquals(1, btc.priorFailures.size)
        assertEquals("OKX", SourcePromotion.primaryId("BTC/USDT", SourcePromotion.Kind.CRYPTO))
    }

    @Test fun `incomplete history from every venue is rejected instead of fabricating candles`() = runBlocking {
        val bundle = CryptoRadarBundle { _, frame, source ->
            PublicCandleHistoryClient.Result(closedSeries(frame, 100), source.label)
        }
        assertTrue(runCatching { bundle.fetch("BTC/USDT", now) }.isFailure)
    }
}
