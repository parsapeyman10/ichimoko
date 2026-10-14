package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CryptoRadarBundleTest {
    private val now = System.currentTimeMillis()

    private fun closedM5Series(price: Double = 100.0): List<Candle> {
        val step = Interval.M5.millis
        val end = now / step * step
        return (0 until HistoryPolicy.LIVE_REQUEST_CANDLES).map { i ->
            Candle(end - (HistoryPolicy.LIVE_REQUEST_CANDLES - i) * step,
                price, price + 1.0, price - 1.0, price, 10.0, closed = true)
        }
    }

    @Test fun `each crypto symbol fetches only 1500 M5 candles from its dedicated source and converts M15 and H1`() = runBlocking {
        val calls = mutableListOf<Triple<String, Interval, CryptoRadarBundle.Source>>()
        val bundle = CryptoRadarBundle { symbol, frame, source ->
            calls += Triple(symbol, frame, source)
            PublicCandleHistoryClient.Result(closedM5Series(), "${source.label} ($symbol)")
        }

        val btc = bundle.fetch("BTC/USDT", now)
        assertEquals(CryptoRadarBundle.Source.BINANCE, btc.venue)
        assertEquals(1500, btc.frames.getValue(Interval.M5).size)
        assertEquals(500, btc.frames.getValue(Interval.M15).size)
        assertEquals(125, btc.frames.getValue(Interval.H1).size)

        val eth = bundle.fetch("ETH/USDT", now)
        assertEquals(CryptoRadarBundle.Source.OKX, eth.venue)

        val sol = bundle.fetch("SOL/USDT", now)
        assertEquals(CryptoRadarBundle.Source.GATE, sol.venue)

        val bnb = bundle.fetch("BNB/USDT", now)
        assertEquals(CryptoRadarBundle.Source.MEXC, bnb.venue)

        val xrp = bundle.fetch("XRP/USDT", now)
        assertEquals(CryptoRadarBundle.Source.KRAKEN, xrp.venue)

        // Verify ONLY M5 was fetched over the network for all 5 symbols, and all 5 used distinct sources
        assertEquals(5, calls.size)
        assertTrue(calls.all { it.second == Interval.M5 })
        assertEquals(5, calls.map { it.third }.distinct().size)
    }

    @Test fun `incomplete M5 history is rejected instead of fabricating candles`() = runBlocking {
        val bundle = CryptoRadarBundle { _, _, source ->
            PublicCandleHistoryClient.Result(closedM5Series().drop(1), source.label)
        }
        assertTrue(runCatching { bundle.fetch("BTC/USDT", now) }.isFailure)
    }
}
