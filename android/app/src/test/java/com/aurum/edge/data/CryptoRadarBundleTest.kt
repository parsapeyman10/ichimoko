package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CryptoRadarBundleTest {
    private val now = System.currentTimeMillis()
    private fun bars(interval: Interval, stale: Boolean = false): List<Candle> {
        val last = now / interval.millis * interval.millis - interval.millis -
            if (stale) 4*interval.millis else 0L
        return (0 until 320).map { i ->
            Candle(last - (319-i)*interval.millis, 100.0, 101.0, 99.0, 100.0, 10.0)
        }
    }

    @Test fun `stale M15 rejects entire first venue without mixing with OKX`() = runBlocking {
        val requested = mutableListOf<Pair<CryptoRadarBundle.Source,Interval>>()
        val client = CryptoRadarBundle { _, interval, venue ->
            requested += venue to interval
            PublicCandleHistoryClient.Result(bars(interval,
                stale = venue == CryptoRadarBundle.Source.GATE && interval == Interval.M15),
                venue.label)
        }
        val result = client.fetch("BTC/USDT", now)
        assertEquals(CryptoRadarBundle.Source.OKX, result.venue)
        assertEquals("OKX Spot", result.provider)
        assertEquals(3, result.frames.size)
        assertTrue(result.priorFailures.single().contains("Gate"))
        assertEquals(listOf(Interval.M5, Interval.M15, Interval.M5, Interval.M15, Interval.H1),
            requested.map { it.second })
        assertFalse(CandleSourceHealth.ready(bars(Interval.M5).drop(1), Interval.M5, now, true, true))
        assertFalse(CandleSourceHealth.ready(bars(Interval.M5).dropLast(1), Interval.M5, now, true, true))
    }

    @Test fun `all failed venues never yield a radar signal`() = runBlocking {
        val feed = CryptoRadarBundle { _, interval, venue ->
            PublicCandleHistoryClient.Result(bars(interval).map {
                if (venue == CryptoRadarBundle.Source.KRAKEN) it.copy(volume = 0.0) else it.copy(closed = false)
            }, venue.label)
        }
        assertTrue(runCatching { feed.fetch("BTC/USDT", now) }.exceptionOrNull()?.message
            .orEmpty().contains("هیچ منبع"))
    }

    @Test fun `venue parsers reject partial candle and wrong pair execution`() {
        val client = CryptoRadarCandleClient()
        val t = (now / Interval.M5.millis * Interval.M5.millis - Interval.M5.millis)
        val gate = """[["${t/1000}","10","100","101","99","100","4",true]]"""
        assertEquals(t, client.parseGate(gate, Interval.M5, now).single().time)
        assertTrue(client.parseGate(gate.replace("true", "false"), Interval.M5, now).isEmpty())
        val okx = """{"code":"0","data":[["$t","100","101","99","100","4","10","20","1"]]}"""
        assertEquals(4.0, client.parseOkx(okx, Interval.M5, now).single().volume, 0.00001)
        assertTrue(client.parseOkx(okx.replace("\"1\"]", "\"0\"]"), Interval.M5, now).isEmpty())
        val kraken = """{"error":[],"result":{"XBTUSDT":[[${t/1000},"100","101","99","100","100.1","4","12"]],"last":123}}"""
        assertEquals(4.0, client.parseKraken(kraken, Interval.M5, now).single().volume, 0.00001)
        assertTrue(client.parseKraken(kraken.replace("${t/1000}", "${now/1000}"),
            Interval.M5, now).isEmpty()) // Kraken's trailing forming bar
        val krakenTrade = """{"error":[],"result":{"XBTUSDT":[["100","2","${now/1000.0}","b","l",""]],"last":"99"}}"""
        assertEquals(100.0, client.parseKrakenTrade(krakenTrade).price, 0.00001)
        assertThrows(DataFeedException::class.java) {
            client.parseKrakenTrade("""{"error":[],"result":{"A":[],"B":[],"last":"99"}}""")
        }
        assertThrows(DataFeedException::class.java) {
            client.parseGateTrade("""[{"currency_pair":"ETH_USDT","price":"100","amount":"1","create_time_ms":"$now"}]""", "BTC_USDT")
        }
        assertThrows(DataFeedException::class.java) {
            client.parseOkxTrade("""{"code":"0","data":[{"instId":"ETH-USDT","px":"100","sz":"1","ts":"$now"}]}""", "BTC-USDT")
        }
    }
}
