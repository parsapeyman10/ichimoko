package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexTradeBatchTest {
    private val now = 1_780_000_000_000L
    private fun trade(ms: Long, price: Int, volume: String = "1") =
        """{"time":$ms,"price":"$price","volume":"$volume","type":"buy"}"""
    private fun body(vararg entries: String) =
        """{"status":"ok","trades":[${entries.joinToString()}]}"""

    @Test fun `replay chronological trade page rather than only its newest quote`() {
        val page = NobitexTradeBatch.parse(body(trade(now - 1000, 103), trade(now - 3000, 101),
            trade(now - 2000, 99)), now)
        assertEquals(listOf(101.0, 99.0, 103.0), page.map { it.price })
        val cursor = TradePageCursor()
        assertFalse(cursor.advance(page.take(2), now - 3000).gap)
        val next = cursor.advance(page.drop(1), now - 3000)
        assertFalse(next.gap)
        assertEquals(listOf(103.0), next.newlySeen.map { it.price })
        assertTrue(cursor.advance(listOf(NobitexTradeBatch.Trade(now, 95.0, 1.0, "sell")),
            now - 4000).gap)
    }

    @Test fun `no overlap or unavailable entry window never claims all ticks were observed`() {
        val page = NobitexTradeBatch.parse(body(trade(now - 2000, 101), trade(now - 1000, 102)), now)
        assertTrue(TradePageCursor().advance(page, now - 10_000).gap)
        assertEquals(emptyList<NobitexTradeBatch.Trade>(),
            TradePageCursor().advance(page, now - 10_000).newlySeen)
        val duplicates = listOf(page[0], page[0], page[1])
        val cursor = TradePageCursor()
        cursor.advance(page, now - 10_000)
        assertTrue(cursor.advance(duplicates, now - 10_000).gap)
        val sameMillisecond = NobitexTradeBatch.parse(body(trade(now - 2000, 101),
            trade(now - 2000, 95)), now)
        assertTrue(TradePageCursor().advance(sameMillisecond, now - 2000).gap)
    }

    @Test fun `invalid or old executed trades never enter the paper journal`() {
        assertThrows(DataFeedException::class.java) { NobitexTradeBatch.parse(body(trade(now - 1000, 100, "-1")), now) }
        assertThrows(DataFeedException::class.java) { NobitexTradeBatch.parse(body(trade(now - 46_000, 100)), now) }
        assertThrows(DataFeedException::class.java) { NobitexTradeBatch.parse(body("""{"time":$now,"price":"1"}"""), now) }
    }
}
