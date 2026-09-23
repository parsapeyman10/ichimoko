package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.data.NobitexPublicData
import com.aurum.edge.data.NobitexQuote
import com.aurum.edge.data.NobitexSnapshot
import com.aurum.edge.data.parseNobitexHistory
import com.aurum.edge.data.parseNobitexQuote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured shapes of public provider replies; tests never rely on external Internet. */
class NobitexPublicContractTest {
    private val now = 1_790_167_500_000L
    private val history = """{"s":"ok","t":[1790166300,1790166600,1790166900],
        "o":[85384.54,85499.64,85499.0],"h":[85499.99,85499.64,85499.0],
        "l":[85384.38,85408.41,85396.0],"c":[85499.64,85499.0,85396.1],
        "v":[0.02102994,0.04265743,0.01200518]}"""
    private val stats = """{"status":"ok","stats":{"btc-usdt":{"isClosed":false,
        "bestSell":"85498","bestBuy":"85397.1","latest":"85498",
        "dayChange":"-0.31"}},"global":{"binance":{}}}"""
    private fun root(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun parsesActualUdfAndStatsSchemaWithoutInventingBarsOrExchangeTimestamp() {
        val bars = parseNobitexHistory(root(history), Interval.M5, now)
        val quote = parseNobitexQuote(root(stats), NobitexMarket.BTC_USDT, now)
        assertEquals(3, bars.size)
        assertTrue(bars.all { it.closed })
        assertEquals(85_396.1, bars.last().close, 0.0001)
        assertEquals(85_397.1, quote.bestBuy, 0.0001)
        assertEquals(85_498.0, quote.bestSell, 0.0001)
        assertEquals(now, quote.receivedAt)
        assertNull(NobitexSnapshot(NobitexMarket.BTC_USDT, Interval.M5, bars, quote, now).lastClosed?.takeIf { !it.closed })
        val whileOpen = parseNobitexHistory(root(history), Interval.M5, 1_790_166_950_000L)
        assertTrue(!whileOpen.last().closed)
    }

    @Test fun rejectMisalignedArraysInvalidSymbolsFailedQuoteAndFutureBars() {
        listOf(
            history.replace("\"s\":\"ok\"", "\"s\":\"no_data\""),
            history.replace("85396.1]", "85396.1,85396.1]"),
            history.replace("1790166900", "1790169600"),
            history.replace("85384.38", "90000"),
            history.replace("1790166900", "1790166600"),
        ).forEach { payload ->
            assertTrue(runCatching { parseNobitexHistory(root(payload), Interval.M5, now) }.isFailure)
        }
        assertTrue(runCatching { parseNobitexQuote(root(stats.replace("btc-usdt", "eth-usdt")),
            NobitexMarket.BTC_USDT, now) }.isFailure)
        assertTrue(runCatching { parseNobitexQuote(root(stats.replace("false", "null")),
            NobitexMarket.BTC_USDT, now) }.isFailure)
        assertTrue(runCatching { parseNobitexQuote(root(stats.replace("\"bestSell\":\"85498\"",
            "\"bestSell\":\"85100\"")), NobitexMarket.BTC_USDT, now) }.isFailure)
    }

    @Test fun btcIrtLiveResponsesDisagreeTenfoldSoPracticeAndUnitConversionAreForbidden() {
        val rialStats = """{"status":"ok","stats":{"btc-rls":{"isClosed":false,
            "bestSell":"195299999880","bestBuy":"195000000250","latest":"195000000220"}}}"""
        val rialHistory = """{"s":"ok","t":[1790166600,1790166900],
            "o":[19522000000,19531013020],"h":[19569996848,19568802733],
            "l":[19511027611,19531013020],"c":[19531000011,19534025255],
            "v":[0.02723703,0.01238743]}"""
        val bars = parseNobitexHistory(root(rialHistory), Interval.M5, now)
        val quote = parseNobitexQuote(root(rialStats), NobitexMarket.BTC_IRT, now)
        val snapshot = NobitexSnapshot(NobitexMarket.BTC_IRT, Interval.M5, bars, quote, now)
        assertTrue(quote.latest / bars.last().close in 9.0..11.0)
        assertNotNull(snapshot.practiceBlocker(now))
        assertTrue(NobitexPublicData.csv(snapshot).contains("unverified_BTCIRT_history_unit"))
    }

    @Test fun onlyTwoPublicGetEndpointsAreCalledAndRateIsBounded() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url} ${request.header("User-Agent")}"
            assertEquals("GET", request.method)
            assertNull(request.header("Authorization"))
            assertEquals("apiv2.nobitex.ir", request.url.host)
            val payload = if (request.url.encodedPath == "/market/udf/history") history else stats
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val api = NobitexPublicData(client) { now }
        val snapshot = api.download(NobitexMarket.BTC_USDT, Interval.M5)
        assertEquals(2, requests.size)
        assertEquals(3, snapshot.candles.size)
        assertTrue(requests.all { it.startsWith("GET https://apiv2.nobitex.ir/market/") &&
            it.endsWith("TraderBot/AurumEdge-1.0.0") })
        assertTrue(runCatching { api.download(NobitexMarket.BTC_USDT, Interval.M5) }.isFailure)
        assertEquals(2, requests.size)
    }
}
