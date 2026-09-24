package com.aurum.edge.engine

import com.aurum.edge.data.NobitexSpotCatalog
import com.aurum.edge.data.NobitexSpotScanner
import com.aurum.edge.data.parseSpotStats
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures model the public stats schema; no quote is a current price or pump forecast. */
class NobitexSpotScannerTest {
    private val at = 1_800_000_000_000L
    private fun response(omit: String = "", changes: String = "2.5", volume: String = "50000") = """{
        "status":"ok","stats":{${listOf("usdt", "rls").flatMap { quote ->
            NobitexSpotCatalog.bases.map { base ->
                val key = "${base.lowercase()}-$quote"
                if (key == omit) "" else "\"$key\":{\"isClosed\":false,\"bestBuy\":\"100.0\"," +
                    "\"bestSell\":\"100.4\",\"latest\":\"100.2\",\"volumeDst\":\"${if (quote == "rls") "90000000000" else volume}\"," +
                    "\"dayChange\":\"$changes\"}"
            }
        }.filter { it.isNotBlank() }.joinToString(",")}}, "global":{"binance":{}}} """
    private fun root(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test fun bothQuotesAreSeparatedAndPositiveHistoryIsNotARealTrade() {
        val snapshot = parseSpotStats(root(response()), at)
        assertTrue(snapshot.complete)
        assertEquals(12, snapshot.pairs.size)
        assertEquals(6, snapshot.pairs.count { it.quote == "USDT" })
        assertEquals(6, snapshot.pairs.count { it.quote == "ریال" })
        assertEquals(12, snapshot.candidates)
        assertTrue(snapshot.fresh(at + 180_000))
        assertFalse(snapshot.fresh(at + 180_001))
        assertTrue(snapshot.pairs.all { it.observation.contains("بررسی دستی") })
    }

    @Test fun malformedOrIncompleteStatsNeverProduceFreshCandidates() {
        assertFalse(parseSpotStats(root(response(omit = "ada-rls")), at).complete)
        assertEquals(0, parseSpotStats(root(response(changes = "-0.5")), at).candidates)
        assertEquals(0, parseSpotStats(root(response(changes = "14.5")), at).candidates)
        assertEquals(0, parseSpotStats(root(response(volume = "3000")), at).pairs.count { it.quote == "USDT" && it.candidate })
        assertFalse(parseSpotStats(root(response().replace("\"bestSell\":\"100.4\"", "\"bestSell\":\"99.0\"")), at).complete)
        assertTrue(runCatching { parseSpotStats(root(response().replace("\"status\":\"ok\"", "\"status\":\"failed\"")), at) }.isFailure)
    }

    @Test fun onlyOneKeylessGetAndAtMostOneScanPerMinute() = runBlocking {
        val requested = mutableListOf<String>()
        var now = at
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            requested += "${req.method} ${req.url}"
            assertEquals("TraderBot/AurumEdge-1.0.0", req.header("User-Agent"))
            assertEquals("GET", req.method)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toResponseBody("application/json".toMediaType())).build()
        }).build()
        val scanner = NobitexSpotScanner(client) { now }
        assertTrue(scanner.scan().fresh(now))
        assertTrue(runCatching { scanner.scan() }.isFailure)
        now += 60_000L
        assertTrue(scanner.scan().fresh(now))
        assertEquals(2, requested.size)
        assertTrue(requested.all { it == "GET ${NobitexSpotCatalog.url}" })
    }
}
