package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CryptoQuoteTaskIsolationTest {
    @Test fun `one stalled pair does not hold another pair quote mutex`() = runBlocking {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val symbol = chain.request().url.queryParameter("symbol")
            if (symbol == "VETUSDT") {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
            val json = """[{"a":101,"T":${System.currentTimeMillis()},"p":"101","q":"1"}]"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }).build()
        val spot = BinanceSpotClient(http)
        try {
            val slow = async(Dispatchers.IO) { spot.fetchQuote("VET/USDT") }
            check(entered.await(5, TimeUnit.SECONDS))
            val fast = async(Dispatchers.IO) { spot.fetchQuote("SAND/USDT") }
            assertEquals(101.0, withTimeout(2_500L) { fast.await() }.price, 0.0)
            unblock.countDown()
            assertEquals(101.0, slow.await().price, 0.0)
        } finally {
            unblock.countDown()
        }
    }
}
