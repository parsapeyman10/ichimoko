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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weak-network retries are bounded, never multiply a fixed batch or retry quota errors. */
class WatchRetryTest {
    private fun network(reply: (Int) -> String?): Pair<OkHttpClient, () -> Int> {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests++
            val body = reply(requests) ?: throw IOException("temporary network interruption")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(
                if (body == "429") 429 else 200).message("fixture")
                .body(body.toResponseBody()).build()
        }).build()
        return client to { requests }
    }

    @Test fun weakNetworkRecoversOnThirdAttemptWithoutClaimingProviderTimestamp() = runBlocking {
        val (client, calls) = network { n ->
            if (n < 3) null else "<tr data-market-nameslug='geram18' data-price='240000000'></tr>"
        }
        val result = SourceFetcher(client) { }.fetchAll(SourceCatalog.tgju,
            listOf(SymbolDef("geram18", "طلا")))
        assertEquals(3, calls())
        assertEquals(24_000_000.0, result.quotes.single().price!!, 0.001)
        assertNull(result.quotes.single().providerAt)
    }

    @Test fun fixedBatchFailureDoesNotFanOutAndHttp429IsNotRetried() = runBlocking {
        val (client, calls) = network { null }
        val results = SourceFetcher(client) { }.fetchAll(SourceCatalog.navasanGold,
            listOf(SymbolDef("18ayar", "طلا"), SymbolDef("nim", "نیم سکه"), SymbolDef("rob", "ربع سکه")))
        assertEquals(3, calls()) // one fixed JSON URL, three attempts in total
        assertTrue(results.quotes.all { it.price == null && it.error != null })
        val (limited, requests) = network { "429" }
        val blocked = SourceFetcher(limited) { }.fetchAll(SourceCatalog.tgju,
            listOf(SymbolDef("geram18", "طلا")))
        assertEquals(1, requests())
        assertNull(blocked.quotes.single().price)
        val (crypto429, cryptoCalls) = network { "429" }
        val crypto = SourceFetcher(crypto429) { }.fetchAll(SourceCatalog.coinGecko,
            listOf(SymbolDef("bitcoin", "BTC"), SymbolDef("ethereum", "ETH")))
        assertEquals(1, cryptoCalls()) // a failed batch must not exhaust quota with per-coin calls
        assertTrue(crypto.quotes.all { it.price == null })
    }
}
