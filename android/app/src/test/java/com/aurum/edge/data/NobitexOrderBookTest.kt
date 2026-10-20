package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why this parser is strict: the bid/ask it produces is the only thing standing between a
 * two-sided quote and an invented spread. Every rejected case below must stay rejected — a
 * "helpful" fallback here would let a cost gate pass on a number nobody measured.
 */
class NobitexOrderBookTest {
    private val now = 1_800_000_000_000L

    private fun book(
        bids: String = """[["517800","294.14"],["517850","5215.36"]]""",
        asks: String = """[["517900","70"],["517950","4"]]""",
        status: String = "ok",
        lastUpdate: Long = now,
    ) = """{"status":"$status","lastUpdate":$lastUpdate,"lastTradePrice":"517870",
        |"bids":$bids,"asks":$asks}""".trimMargin()

    @Test fun `best prices are taken from the levels, not from array order`() {
        // Nobitex has shipped both ascending and descending arrays, so the extremes are computed.
        val parsed = NobitexOrderBook.parse(
            book(bids = """[["517860","1"],["517980","2"],["517900","3"]]""",
                 asks = """[["518200","1"],["518000","2"],["518100","3"]]"""), now)
        assertEquals(517_980.0, parsed.bid, 1e-9)
        assertEquals(518_000.0, parsed.ask, 1e-9)
        assertEquals(20.0, parsed.spread, 1e-9)
        assertEquals(now, parsed.at)
    }

    @Test fun `a book with crossed sides is refused, never relabelled`() {
        // If the provider has swapped bids and asks, the honest answer is "no spread", not a swap.
        val failure = assertThrows(DataFeedException::class.java) {
            NobitexOrderBook.parse(book(bids = """[["518200","1"]]""", asks = """[["518000","1"]]"""), now)
        }
        assertTrue(failure.message!!.contains("وارونه"))
    }

    @Test fun `a stale book is not a quote`() {
        assertThrows(DataFeedException::class.java) {
            NobitexOrderBook.parse(book(lastUpdate = now - NobitexOrderBook.MAX_AGE_MS - 1), now)
        }
        // Future-dated timestamps beyond the tolerance are rejected too.
        assertThrows(DataFeedException::class.java) {
            NobitexOrderBook.parse(book(lastUpdate = now + 60_000), now)
        }
    }

    @Test fun `a non-ok status, an empty side or a missing timestamp fails closed`() {
        assertThrows(DataFeedException::class.java) { NobitexOrderBook.parse(book(status = "failed"), now) }
        assertThrows(DataFeedException::class.java) { NobitexOrderBook.parse(book(bids = "[]"), now) }
        assertThrows(DataFeedException::class.java) { NobitexOrderBook.parse(book(asks = "[]"), now) }
        assertThrows(DataFeedException::class.java) {
            NobitexOrderBook.parse("""{"status":"ok","bids":[["1","1"]],"asks":[["2","1"]]}""", now)
        }
        assertThrows(DataFeedException::class.java) { NobitexOrderBook.parse("not json", now) }
    }

    @Test fun `malformed levels are dropped instead of poisoning the best price`() {
        val parsed = NobitexOrderBook.parse(
            book(bids = """[["oops","1"],["-5","1"],["517900","2"]]""", asks = """[["518100","3"],[],null]"""), now)
        assertEquals(517_900.0, parsed.bid, 1e-9)
        assertEquals(518_100.0, parsed.ask, 1e-9)
    }

    @Test fun `a merged quote keeps the trade price and the older timestamp`() {
        val book = NobitexOrderBook.parse(book(lastUpdate = now - 4_000L), now)
        val trade = PriceTick(517_870.0, now - 1_000L)
        // What SpotFallbackClient does with the two halves: the tape sets the price, the book sets
        // the spread, and the usable time is the older of the two, so a stale side cannot look fresh.
        val merged = trade.copy(bid = book.bid, ask = book.ask, at = minOf(trade.at, book.at))
        assertEquals(517_870.0, merged.price, 1e-9)
        assertEquals(book.bid, merged.bid!!, 1e-9)
        assertEquals(now - 4_000L, merged.at)
        assertTrue(TradeQuotePolicy.accepts(merged, now))
    }
}
