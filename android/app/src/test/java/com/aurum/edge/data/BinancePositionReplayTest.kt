package com.aurum.edge.data

import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Signal
import com.aurum.edge.domain.SignalAction
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
    /** These are already-open LEGACY fixtures loaded from disk, not new entries.
     * Retiring the old crypto strategy must not disable old same-venue exits.
     */
    private suspend fun legacy(venue: String, path: File = file()): JournalStore {
        val trade = com.aurum.edge.core.PaperTrade("legacy", "BTC/USDT", Interval.M5,
            SignalAction.BUY, 100.0, 90.0, 125.0, 100.0, 2.5, at,
            positionOz = 1.0, initialStopLoss = 90.0, trailExtreme = 100.0, cryptoVenue = venue)
        path.writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.aurum.edge.core.PaperTrade.serializer()),
            listOf(trade)))
        return JournalStore(context, path).also { it.load() }
    }
    private fun row(id: Long, price: Double, delay: Long = id) =
        BinanceSpotClient.Aggregate(id, PriceTick(price, at + delay))

    @Test fun `new compression position exits on next ordered real print after M5 close`() = runBlocking {
        val path = file()
        val trade = com.aurum.edge.core.PaperTrade("compression", "BTC/USDT", Interval.M5,
            SignalAction.BUY, 100.0, 99.0, 103.0, 100.0, 3.0,
            at - 2*Interval.M5.millis, positionOz = 1.0,
            initialStopLoss = 99.0, trailExtreme = 100.0,
            cryptoVenue = BinanceSpotClient.VENUE, cryptoEntryAtr = 1.0)
        path.writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.aurum.edge.core.PaperTrade.serializer()),
            listOf(trade)))
        val journal = JournalStore(context, path).also { it.load() }
        val end = at / Interval.M5.millis * Interval.M5.millis
        val total = com.aurum.edge.core.HistoryPolicy.LIVE_MIN_CANDLES
        val bars = (0 until total).map { index ->
            com.aurum.edge.domain.Candle(end - (total - index) * Interval.M5.millis,
                100.0, 100.3, if (index == total - 1) 98.7 else 99.7,
                if (index == total - 1) 99.0 else 100.0, 100.0)
        }
        assertFalse(journal.applyCryptoClosedBars("BTC/USDT", trade.openedAt,
            "OTHER_VENUE", bars, emptyList(), at))
        assertTrue(journal.applyCryptoClosedBars("BTC/USDT", trade.openedAt,
            BinanceSpotClient.VENUE, bars, emptyList(), at))
        assertTrue(journal.trades.value.single().isOpen) // candle close is NOT a fill
        assertEquals(end, journal.trades.value.single().cryptoExitPendingAt)
        val closed = journal.settleBinanceBatch("BTC/USDT", trade.openedAt,
            listOf(BinanceSpotClient.Aggregate(1, PriceTick(99.5, at + 1))))
        assertEquals(99.5, closed.single().exitPrice!!, 0.00001)
        assertTrue(closed.single().exitReason.orEmpty().contains("کیجون"))
        assertFalse(journal.trades.value.single().isOpen)
    }

    @Test fun `nobitex compression position exits only on nobitex bars, never binance bars`() = runBlocking {
        val path = file()
        val trade = com.aurum.edge.core.PaperTrade("compression-nobitex", "BTC/USDT", Interval.M5,
            SignalAction.BUY, 100.0, 99.0, 103.0, 100.0, 3.0,
            at - 2*Interval.M5.millis, positionOz = 1.0,
            initialStopLoss = 99.0, trailExtreme = 100.0,
            cryptoVenue = NobitexReference.VENUE, cryptoEntryAtr = 1.0)
        path.writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.aurum.edge.core.PaperTrade.serializer()),
            listOf(trade)))
        val journal = JournalStore(context, path).also { it.load() }
        val end = at / Interval.M5.millis * Interval.M5.millis
        val total = com.aurum.edge.core.HistoryPolicy.LIVE_MIN_CANDLES
        val bars = (0 until total).map { index ->
            com.aurum.edge.domain.Candle(end - (total - index) * Interval.M5.millis,
                100.0, 100.3, if (index == total - 1) 98.7 else 99.7,
                if (index == total - 1) 99.0 else 100.0, 100.0)
        }
        // A Nobitex position must not be trailed with Binance candles ...
        assertFalse(journal.applyCryptoClosedBars("BTC/USDT", trade.openedAt,
            BinanceSpotClient.VENUE, bars, emptyList(), at))
        // ... but its own venue is a writable venue, not the Binance-only gate it used to be.
        assertTrue(journal.applyCryptoClosedBars("BTC/USDT", trade.openedAt,
            NobitexReference.VENUE, bars, emptyList(), at))
        assertTrue(journal.trades.value.single().isOpen) // a closed candle is still not a fill
        assertEquals(end, journal.trades.value.single().cryptoExitPendingAt)
        assertEquals(NobitexReference.VENUE, journal.trades.value.single().cryptoVenue)
    }

    @Test fun `retired crypto entry signal is rejected but old positions remain loadable`() = runBlocking {
        val fresh = JournalStore(context, file()).also { it.load() }
        val legacySignal = Signal(SignalAction.BUY, 100.0, entry = 100.0,
            stopLoss = 90.0, takeProfit = 130.0, interval = Interval.M5,
            barTime = at - Interval.M5.millis,
            ichimokuEntry = com.aurum.edge.core.IchimokuEntryEvidence(
                SignalAction.BUY, at - Interval.M5.millis, at - Interval.M5.millis, Interval.M5, 5),
            confluence = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
                "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی").map {
                ConfluenceItem(it, true, "old fixture", scorePercent = 25)
            })
        // BTC is owned by BTC-ARES now, so an old compression/four-layer signal must be refused
        // there instead of being interpreted by a model that no longer serves this symbol.
        assertTrue(runCatching { fresh.open(legacySignal, "BTC/USDT", 100.0,
            50_000.0, 0.5, observedAt = at) }.exceptionOrNull()?.message
            .orEmpty().contains("BTC-ARES"))
        assertTrue(fresh.trades.value.isEmpty())
    }

    @Test fun `old position keeps Nobitex settlement even after app changes default venue`() = runBlocking {
        val store = legacy("NOBITEX")
        assertEquals("NOBITEX", store.trades.value.single().cryptoVenue)
        assertTrue(store.settleBinanceBatch("BTC/USDT", at, listOf(row(100, 80.0))).isEmpty())
        assertTrue(store.trades.value.single().isOpen)
        assertEquals(1, store.settleTick("BTC/USDT", 80.0, at + 100L, expectedOpenedAt = at).size)
    }

    @Test fun `failed Binance replay hides last quote instead of valuing an unverified open position`() = runBlocking {
        val store = legacy(BinanceSpotClient.VENUE)
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
        val store = legacy(BinanceSpotClient.VENUE, file)
        val opened = store.trades.value.single()
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
