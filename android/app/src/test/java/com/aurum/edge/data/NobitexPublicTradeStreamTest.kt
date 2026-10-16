package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexPublicTradeStreamTest {
    private val now = 1_780_000_000_000L
    private fun subscribed(recovered: Boolean = false, wasRecovering: Boolean = false,
                           recoverable: Boolean = true) =
        NobitexPublicTradeStream.Event.Subscribed(wasRecovering, recovered, recoverable)

    @Test fun `public trade message uses same venue time price and validated freshness`() {
        val data = """{"price":"84251.20","time":${now - 50},"type":"sell","volume":"0.003"}"""
        val trade = NobitexStreamTrade.parse(data, now)
        assertEquals(now - 50, trade.at)
        assertEquals(84251.20, trade.price, 0.00001)
        assertEquals("sell", trade.side)
        assertThrows(DataFeedException::class.java) {
            NobitexStreamTrade.parse(data.replace("${now - 50}", "${now - 46_000}"), now)
        }
        assertThrows(DataFeedException::class.java) {
            NobitexStreamTrade.parse(data.replace("0.003", "-1"), now)
        }
        assertThrows(DataFeedException::class.java) {
            NobitexStreamTrade.parse(data.replace("sell", "unknown"), now)
        }
        assertThrows(DataFeedException::class.java) {
            NobitexStreamTrade.parse(data.replace("84251.20", "NaN"), now)
        }
    }

    @Test fun `recoverable stream preserves order deduplicates and detects missing offset`() {
        val cursor = TradeStreamCursor(now)
        assertTrue(cursor.subscribed(subscribed()))
        assertEquals(TradeStreamCursor.Verdict.SKIP, cursor.publication(now - 1, 1))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now, 10))
        assertEquals(TradeStreamCursor.Verdict.SKIP, cursor.publication(now, 10))
        assertFalse(cursor.subscribed(subscribed(recovered = true, wasRecovering = true)))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 1, 11))
        assertEquals(TradeStreamCursor.Verdict.GAP_APPLY, cursor.publication(now + 3, 14))
        assertEquals(TradeStreamCursor.Verdict.SKIP, cursor.publication(now + 3, 14))
        // A later publisher offset, not a millisecond clock, defines stream order.
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 2, 15))
    }

    @Test fun `unrecoverable reconnect and REST handoff must not masquerade as gapless`() {
        val cursor = TradeStreamCursor(now)
        assertTrue(cursor.rest(now + 1))
        assertTrue(cursor.subscribed(subscribed()))
        assertEquals(TradeStreamCursor.Verdict.GAP, cursor.publication(now + 1, 7))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 2, 8))
        assertTrue(cursor.subscribed(subscribed(recovered = false, wasRecovering = true)))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 5, 100))
        assertTrue(cursor.rest(now + 10))
        assertFalse(cursor.rest(now + 9))
        assertFalse(cursor.rest(now + 10))
        assertEquals(TradeStreamCursor.Verdict.GAP, cursor.publication(now + 9, 101))
        assertEquals(TradeStreamCursor.Verdict.GAP_APPLY, cursor.publication(now + 11, 102))
    }

    @Test fun `recoverable channel missing offset is an audited gap not a forged sequence`() {
        val cursor = TradeStreamCursor(now)
        assertTrue(cursor.subscribed(subscribed()))
        assertEquals(TradeStreamCursor.Verdict.GAP_APPLY, cursor.publication(now, 0))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 1, 8))
    }

    @Test fun `nonrecoverable channel does not infer sequence continuity from offsets`() {
        val cursor = TradeStreamCursor(now)
        assertTrue(cursor.subscribed(subscribed(recoverable = false)))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now, 1))
        assertEquals(TradeStreamCursor.Verdict.APPLY, cursor.publication(now + 1, 9))
        assertTrue(cursor.subscribed(subscribed(recoverable = false)))
    }
}
