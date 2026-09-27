package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.NobitexBookTop
import com.aurum.edge.data.NobitexMarket
import com.aurum.edge.data.NobitexPracticeRules
import com.aurum.edge.data.NobitexPracticeStore
import com.aurum.edge.data.NobitexQuote
import com.aurum.edge.data.NobitexSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** BUY spot is an isolated paper exercise; only later actual public bid observations may close it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NobitexPracticeStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L

    private fun snapshot(at: Long = now, bid: Double = 85_400.0, ask: Double = 85_500.0,
                         market: NobitexMarket = NobitexMarket.BTC_USDT,
                         closed: Boolean = false, bookBid: Double = bid,
                         bookAsk: Double = ask) = NobitexSnapshot(
        market, Interval.M5,
        (1..220).map { i ->
            val time = at - (221 - i) * Interval.M5.millis
            Candle(time, 85_450.0, 85_600.0, 85_300.0, 85_450.0, 0.05, closed = true)
        },
        NobitexQuote(market, 85_450.0, bid, ask, -0.2, closed, at - 1_000), at,
        book = NobitexBookTop(market, bookBid, bookAsk, at - 1_000, at - 500))

    @Test fun paperBuyNeedsValidQuoteAndNeverCountsAsXauTrade() = runBlocking {
        val root = Files.createTempDirectory("nobitex-practice").toFile()
        val file = File(root, "practice.json")
        val practice = NobitexPracticeStore(context, file).also { it.load() }
        val gold = JournalStore(context, File(root, "gold.json")).also { it.load() }
        val data = snapshot(bookAsk = 85_510.0) // Book top differs slightly from stats ask.
        val ticket = NobitexPracticeRules.preview(data, 100.0, 2.0, 4.0, now)
        assertEquals(85_510.0, ticket.ask, 0.0001)
        assertTrue(ticket.quantityBtc > 0)
        assertTrue(ticket.notional <= 100.0)
        assertTrue(ticket.stop < ticket.ask && ticket.target > ticket.ask)
        val opened = practice.open(data, 100.0, 2.0, 4.0, ticket.ask, data.quote.receivedAt, now)
        assertEquals("BTCUSDT", opened.symbol)
        assertEquals(data.book!!.updatedAt, opened.orderBookUpdatedAt)
        assertEquals(data.book!!.bestAsk, opened.entryAsk, 0.0001)
        assertTrue(opened.isOpen)
        assertEquals(0, gold.stats().total)
        assertEquals(0, gold.stats().open)
        assertNotNull(opened.conditionNote)
        assertTrue(runCatching { practice.open(data, 100.0, 2.0, 4.0, ticket.ask, data.quote.receivedAt, now) }.isFailure)
        assertTrue(runCatching { practice.close(opened.id, data, now) }.isFailure) // same quote as entry
        assertTrue(practice.settle(data, now).isEmpty())
        assertEquals(opened.id, NobitexPracticeStore(context, file).also { it.load() }.trades.value.single().id)
        val next = snapshot(now + 40_000L, bid = ticket.target + 5, ask = ticket.target + 100,
            bookBid = ticket.target + 6, bookAsk = ticket.target + 101)
        // Re-fetching stats with a book timestamp from BEFORE entry must not invent a fill.
        val oldBook = next.copy(book = next.book!!.copy(updatedAt = data.book!!.updatedAt))
        assertNull(oldBook.practiceBlocker(now + 40_000L))
        assertTrue(practice.settle(oldBook, now + 40_000L).isEmpty())
        assertTrue(runCatching { practice.close(opened.id, oldBook, now + 40_000L) }.isFailure)
        assertEquals(1, practice.settle(next, now + 40_000L).size)
        val closed = NobitexPracticeStore(context, file).also { it.load() }.trades.value.single()
        assertFalse(closed.isOpen)
        assertEquals(next.book!!.bestBid, closed.exitBid!!, 0.0001)
        assertTrue(next.quote.bestBuy != closed.exitBid)
        assertEquals(kotlin.math.round((closed.exitBid!! - closed.entryAsk) * closed.quantityBtc * 100) / 100,
            closed.pnlQuote!!, 0.001)
        assertEquals(0, gold.stats().total)
        assertTrue(practice.settle(next, now + 40_000L).isEmpty())
    }

    @Test fun wrongUnitStaleSpreadClosedMarketOrRiskNeverBecomeAnOrder() {
        val data = snapshot()
        assertNull(data.practiceBlocker(now))
        assertNotNull(snapshot(market = NobitexMarket.BTC_IRT).practiceBlocker(now))
        assertNotNull(snapshot(closed = true).practiceBlocker(now))
        assertNotNull(snapshot(bid = 80_000.0).practiceBlocker(now))
        assertNotNull(data.copy(book = null).practiceBlocker(now))
        assertNotNull(data.copy(book = data.book!!.copy(updatedAt = now - 90_000)).practiceBlocker(now))
        assertNotNull(data.copy(book = data.book!!.copy(updatedAt = now + 10_000)).practiceBlocker(now))
        assertNotNull(data.copy(book = data.book!!.copy(bestAsk = 91_000.0)).practiceBlocker(now))
        assertNotNull(data.practiceBlocker(now + 90_000))
        assertTrue(runCatching { NobitexPracticeRules.preview(data, 100.0, 10.0, 4.0, now) }.isFailure)
        assertTrue(runCatching { NobitexPracticeRules.preview(data, 50_000.0, 2.0, 4.0, now) }.isFailure)
        assertTrue(runCatching { NobitexPracticeRules.preview(data.copy(candles = data.candles.take(20)),
            100.0, 2.0, 4.0, now) }.isFailure)
    }

    @Test fun damagedLedgerIsNotClearedOrUsedAsNewBalance() = runBlocking {
        val file = File(Files.createTempDirectory("nobitex-broken").toFile(), "broken.json")
        file.writeText("{corrupted}")
        val store = NobitexPracticeStore(context, file)
        try { store.load(); throw AssertionError("corrupt ledger loaded") } catch (_: IllegalStateException) { }
        assertNotNull(store.loadError.value)
        try { store.clear(); throw AssertionError("corrupt ledger erased") } catch (_: IllegalStateException) { }
        assertEquals("{corrupted}", file.readText())
    }
}
