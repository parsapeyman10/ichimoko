package com.aurum.edge.data

import com.aurum.edge.domain.Interval
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexThreeMinuteFetchTest {
    @Test fun `M3 fetch pages genuine minute candles and never requests unsupported resolution`() = runBlocking {
        val end = 1_800_000_000_000L / Interval.M3.millis * Interval.M3.millis
        val requested = mutableListOf<Pair<Long, Long>>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            assertEquals("1", url.queryParameter("resolution"))
            assertEquals("BTCUSDT", url.queryParameter("symbol"))
            val from = url.queryParameter("from")!!.toLong()
            val to = url.queryParameter("to")!!.toLong()
            requested += from to to
            val times = generateSequence(from) { previous -> (previous + 60).takeIf { it <= to } }
                .filter { it % 60L == 0L }.toList()
            val n = times.size
            val json = """{"s":"ok","t":[${times.joinToString()}],"o":[${List(n) { "100" }.joinToString()}],"h":[${List(n) { "102" }.joinToString()}],"l":[${List(n) { "99" }.joinToString()}],"c":[${List(n) { "101" }.joinToString()}],"v":[${List(n) { "2" }.joinToString()}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }).build()
        val candles = NobitexHistoryClient(client).fetchCandles("BTC/USDT", Interval.M3,
            desiredSize = 360, minimumSize = 320, now = end).candles
        assertTrue(requested.size >= 3)
        assertTrue(requested.all { it.second - it.first < 350 * 60 })
        assertEquals(360, candles.size)
        assertTrue(candles.zipWithNext().all { (a, b) -> b.time - a.time == Interval.M3.millis })
        assertEquals(end - Interval.M3.millis, candles.last().time)
        assertEquals(6.0, candles.last().volume, 0.0)
    }
}
