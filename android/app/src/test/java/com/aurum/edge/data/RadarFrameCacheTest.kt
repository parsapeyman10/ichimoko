package com.aurum.edge.data

import com.aurum.edge.core.Candle
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
    @Test fun `reuse requires the latest fully closed interval and all original health gates`() {
        val interval = Interval.M5
        val boundary = 1_800_000_000_000L / interval.millis * interval.millis
        val rows = (0 until 320).map { i ->
            Candle(boundary - (320 - i) * interval.millis, 100.0, 101.0, 99.0, 100.0, 10.0)
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

    @Test fun `concurrent same frame reads coalesce and a second sweep reuses valid bars`() = runBlocking {
        AppNetwork.configure(false)
        val interval = Interval.M5
        val boundary = System.currentTimeMillis() / interval.millis * interval.millis
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = if (chain.request().url.encodedPath.contains("currency_pairs"))
                """{"id":"BTC_USDT","base":"BTC","quote":"USDT","type":"normal","trade_status":"tradable"}"""
            else (0 until 320).joinToString(",", "[", "]") { i ->
                val time = (boundary - (320 - i) * interval.millis) / 1000
                """["$time","10","100","101","99","100","4",true]"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CryptoRadarCandleClient(http)
        val rows = (1..3).map { async { client.fetch("BTC/USDT", interval, CryptoRadarCandleClient.Venue.GATE) } }.awaitAll()
        assertEquals(320, rows.first().candles.size)
        assertEquals(2, calls.get()) // one listing, one history, no duplicate page/refresh calls
        assertEquals(rows.first(), client.fetch("BTC/USDT", interval, CryptoRadarCandleClient.Venue.GATE))
        assertEquals(2, calls.get())
    }

    @Test fun `Chromium proxy uses documented SOCKS scheme with no direct fallback`() {
        assertEquals("socks://127.0.0.1:19050", WebProxyRouting.proxyRule(19050))
        assertThrows(IllegalArgumentException::class.java) { WebProxyRouting.proxyRule(0) }
        assertThrows(IllegalArgumentException::class.java) { WebProxyRouting.proxyRule(65536) }
        assertNotEquals(WebProxyRouting.Status.WAITING_TOR.message, WebProxyRouting.Status.UNSUPPORTED.message)
    }
}
