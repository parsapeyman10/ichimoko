package com.aurum.edge.engine

import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.SourceFetcher
import com.aurum.edge.data.SymbolDef
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weak-network retries are bounded and quota errors are never retried. */
class WatchRetryTest {
    private fun network(reply: (Int) -> String?): Pair<OkHttpClient, () -> Int> {
        // Per-symbol fetches run concurrently on Dispatchers.IO: the counter MUST be atomic,
        // otherwise lost increments fake a wrong retry count (flaky red).
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val n = requests.incrementAndGet()
            val body = reply(n) ?: throw IOException("temporary network interruption")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(
                if (body == "429") 429 else 200).message("fixture")
                .body(body.toResponseBody()).build()
        }).build()
        return client to { requests.get() }
    }

    @Test fun weakNetworkRecoversOnThirdAttemptWithProviderTimestamp() = runBlocking {
        val (client, calls) = network { n ->
            if (n < 3) null else
                """{"chart":{"result":[{"meta":{"regularMarketPrice":1.0852,"chartPreviousClose":1.0801,"regularMarketTime":1700000000}}],"error":null}}"""
        }
        val result = SourceFetcher(client) { }.fetchAll(SourceCatalog.yahoo,
            listOf(SymbolDef("EURUSD=X", "یورو")))
        assertEquals(3, calls())
        assertEquals(1.0852, result.quotes.single().price!!, 0.000001)
        assertNotNull(result.quotes.single().providerAt) // Yahoo publishes a full timestamp
    }

    @Test fun eachSymbolRetriesIndependentlyAndHttp429IsNotRetried() = runBlocking {
        val (client, calls) = network { null }
        val results = SourceFetcher(client) { }.fetchAll(SourceCatalog.yahoo,
            listOf(SymbolDef("EURUSD=X", "یورو"), SymbolDef("GBPUSD=X", "پوند")))
        assertEquals(6, calls()) // two symbols, three bounded attempts each, no extra fan-out
        assertTrue(results.quotes.all { it.price == null && it.error != null })
        val (limited, requests) = network { "429" }
        val blocked = SourceFetcher(limited) { }.fetchAll(SourceCatalog.yahoo,
            listOf(SymbolDef("EURUSD=X", "یورو"), SymbolDef("GBPUSD=X", "پوند")))
        assertEquals(2, requests()) // one call per symbol; a quota error is never retried
        assertTrue(blocked.quotes.all { it.price == null })
    }
}
