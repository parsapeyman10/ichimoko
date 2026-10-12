package com.aurum.edge.data

import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Real AtomicFile-backed cursor/exit tests, no network and no invented feed successes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BinancePositionReplayTest {
    private val at = System.currentTimeMillis() - 10_000L
    private val context get() = RuntimeEnvironment.getApplication()
    private fun file() = File(Files.createTempDirectory("binance-ledger").toFile(), "journal.json")
    private val signal = Signal(SignalAction.BUY, 100.0, entry = 100.0, stopLoss = 90.0,
        takeProfit = 125.0, interval = Interval.M5, barTime = at - Interval.M5.millis,
        confluence = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
            "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۷ تایم‌فریمی").map {
            ConfluenceItem(it, true, "fixture", scorePercent = 25)
        })
    private fun row(id: Long, price: Double, delay: Long = id) =
        BinanceSpotClient.Aggregate(id, PriceTick(price, at + delay))

    @Test fun `old position keeps Nobitex settlement even after app changes default venue`() = runBlocking {
        val store = JournalStore(context, file()).also { it.load() }
        store.open(signal, "BTC/USDT", 100.0, 50_000.0, 0.5, manual = true, observedAt = at)
        assertEquals("NOBITEX", store.trades.value.single().cryptoVenue)
        assertTrue(store.settleBinanceBatch("BTC/USDT", at, listOf(row(100, 80.0))).isEmpty())
        assertTrue(store.trades.value.single().isOpen)
        assertEquals(1, store.settleTick("BTC/USDT", 80.0, at + 100L, expectedOpenedAt = at).size)
    }

    @Test fun `failed Binance replay hides last quote instead of valuing an unverified open position`() = runBlocking {
        val store = JournalStore(context, file()).also { it.load() }
        store.open(signal, "BTC/USDT", 100.0, 50_000.0, 0.5,
            manual = true, observedAt = at, cryptoVenue = BinanceSpotClient.VENUE)
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val responseBody = if (calls == 1)
                """[{"a":100,"p":"100","q":"1","T":${System.currentTimeMillis() - 1_000L}}]"""
            else "blocked"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (calls == 1) 200 else 451).message("mock")
                .body(responseBody.toResponseBody()).build()
        }.build()
        val monitor = OpenTradeTickMonitor(store, binance = BinanceSpotClient(http))
        assertNotNull(monitor.poll("BTC/USDT", at))
        assertNull(monitor.quoteForManualClose("BTC/USDT", at))
        // The 1s REST throttle must not re-expose the earlier quote after replay failed.
        assertNull(monitor.poll("BTC/USDT", at))
        assertEquals(2, calls)
        assertTrue(store.trades.value.single().isOpen)
        assertNotNull(store.trades.value.single().tickCoverageGapAt)
    }

    @Test fun `new venue rejects legacy REST and candles then persists ID in one atomic batch`() = runBlocking {
        val file = file()
        val store = JournalStore(context, file).also { it.load() }
        val opened = store.open(signal, "BTC/USDT", 100.0, 50_000.0, 0.5,
            manual = true, observedAt = at, cryptoVenue = BinanceSpotClient.VENUE)
        assertFalse(opened.usesTickArchive)
        assertTrue(store.settleTick("BTC/USDT", 80.0, at + 1L).isEmpty())
        assertTrue(store.trades.value.single().isOpen)
        store.settleBinanceBatch("BTC/USDT", at, listOf(row(100, 100.0), row(101, 101.0)))
        assertEquals(101L, JournalStore(context, file).also { it.load() }.trades.value.single().binanceAggId)
        assertTrue(store.settleBinanceBatch("BTC/USDT", at, listOf(row(101, 80.0))).isEmpty())
        assertTrue(store.trades.value.single().isOpen)
        assertTrue(runCatching {
            store.settleBinanceBatch("BTC/USDT", at, listOf(row(103, 80.0)))
        }.isFailure)
        assertTrue(store.trades.value.single().isOpen)
        assertEquals(101L, store.trades.value.single().binanceAggId)
        assertEquals(1, store.settleBinanceBatch("BTC/USDT", at,
            listOf(row(102, 99.0), row(103, 80.0))).size)
        val closed = JournalStore(context, file).also { it.load() }.trades.value.single()
        assertEquals(103L, closed.binanceAggId)
        assertEquals(90.0, closed.exitPrice!!, 0.0)
        assertEquals(BinanceSpotClient.VENUE, closed.cryptoVenue)
    }
}
