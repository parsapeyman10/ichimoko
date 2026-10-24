package com.aurum.edge.data

import com.aurum.edge.domain.Interval
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPositionDisplayTest {
    private val now = 1_800_000_000_000L
    private fun trade(id: String = "one", openedAt: Long = now - 100_000L) = PaperTrade(
        id = id, symbol = "EUR/USD", interval = Interval.M5, action = SignalAction.BUY,
        entry = 1.1, stopLoss = 1.09, takeProfit = 1.12, confidence = 90.0,
        riskReward = 2.0, openedAt = openedAt)

    @Test fun `last genuine tick survives a feed failure for display only`() {
        val position = trade()
        val tick = PriceTick(1.105, now - 10_000L)
        val snapshot = OpenPositionDisplay.record(emptyMap(), position, tick, now)
        assertEquals(tick.at, OpenPositionDisplay.forTrade(position, snapshot)?.tickAt)
        // More than ten seconds of silence must not erase the original trade-specific mark.
        val afterSilence = OpenTickSnapshot.updated(emptyMap(), position.symbol, null,
            setOf(position.symbol), now + 11_000L)
        assertTrue(afterSilence.isEmpty()) // no old price is put back into executable ticks
        assertEquals(tick, PriceTick(OpenPositionDisplay.forTrade(position, snapshot)!!.price,
            OpenPositionDisplay.forTrade(position, snapshot)!!.tickAt))
        // Live valuation stays unknown. The card alone keeps the last real mark without a feed alert.
        assertNull(LiveTradeValuation.total(listOf(position), emptyMap()))
        assertTrue(LiveTradeValuation.from(position, snapshot.getValue(position.id).price) != null)
        assertEquals(snapshot, OpenPositionDisplay.record(snapshot, position,
            PriceTick(1.106, now - 20_000L), now)) // even a fresh out-of-order arrival cannot move the marker back
    }

    @Test fun `no old mark leaks to a new trade of the same symbol or a closed trade`() {
        val oldTrade = trade()
        val saved = OpenPositionDisplay.record(emptyMap(), oldTrade, PriceTick(1.105, now), now)
        assertNull(OpenPositionDisplay.forTrade(trade("two", now + 1L), saved))
        assertNull(OpenPositionDisplay.forTrade(trade("one", now + 1L), saved))
        assertNull(OpenPositionDisplay.forTrade(oldTrade.copy(closedAt = now), saved))
        assertTrue(OpenPositionDisplay.onlyOpen(saved, listOf(trade("two", now + 1L))).isEmpty())
    }

    @Test fun `fake historical or pre-entry prices never become snapshots`() {
        val position = trade()
        assertFalse(OpenPositionDisplay.record(emptyMap(), position,
            PriceTick(1.1, now, isTradeTick = false), now).isNotEmpty())
        assertTrue(OpenPositionDisplay.record(emptyMap(), position,
            PriceTick(1.1, position.openedAt - 1L), now).isEmpty())
    }
}
