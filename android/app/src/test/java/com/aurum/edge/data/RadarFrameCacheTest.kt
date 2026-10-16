package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

class RadarFrameCacheTest {
    private val count = HistoryPolicy.LIVE_REQUEST_CANDLES

    @Test fun `reuse requires 1500 closed M5 candles and all original health gates`() {
        val interval = Interval.M5
        val boundary = 1_800_000_000_000L / interval.millis * interval.millis
        val rows = (0 until count).map { i ->
            Candle(boundary - (count - i) * interval.millis, 100.0, 101.0, 99.0, 100.0, 10.0)
        }
        val client = CryptoRadarCandleClient()
        assertTrue(client.reusable(rows, interval, boundary + 1))
        assertTrue(client.reusable(rows, interval, boundary + interval.millis - 1))
        assertFalse(client.reusable(rows, interval, boundary + interval.millis))
        assertFalse(client.reusable(rows.drop(1), interval, boundary + 1))
        assertFalse(client.reusable(rows.map { it.copy(closed = false) }, interval, boundary + 1))
        assertFalse(client.reusable(rows.map { it.copy(volume = -1.0) }, interval, boundary + 1))
        assertFalse(client.reusable(rows, interval, boundary - 1))
    }

    @Test fun `concurrent same frame reads coalesce and reuse 1500 valid M5 bars`() = runBlocking {
        AppNetwork.observeNetwork("test", true, force = true)
        val interval = Interval.M5
        val boundary = System.currentTimeMillis() / interval.millis * interval.millis
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val callIndex = calls.incrementAndGet()
            val body = when {
                chain.request().url.encodedPath.contains("currency_pairs") ->
                    """{"id":"SOL_USDT","base":"SOL","quote":"USDT","type":"normal","trade_status":"tradable"}"""
                callIndex == 2 -> (0 until 950).joinToString(",", "[", "]") { i ->
                    val time = (boundary - (950 - i) * interval.millis) / 1000
                    """["$time","10","100","101","99","100","4",true]"""
                }
                else -> (0 until 600).joinToString(",", "[", "]") { i ->
                    val time = (boundary - (1550 - i) * interval.millis) / 1000
                    """["$time","10","100","101","99","100","4",true]"""
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CryptoRadarCandleClient(http, clock = { boundary + 10_000L })
        val rows = (1..3).map { async { client.fetch("SOL/USDT", interval, CryptoRadarCandleClient.Venue.GATE) } }.awaitAll()
        assertEquals(1500, rows.first().candles.size)
        assertEquals(3, calls.get()) // 1 listing + 2 history pages for 1500 candles
        assertEquals(rows.first(), client.fetch("SOL/USDT", interval, CryptoRadarCandleClient.Venue.GATE))
        assertEquals(3, calls.get())
    }
}
