package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.TradeReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal chart may only ever REPLAY bars the device already verified. These tests pin the
 * two claims the UI makes: what is drawn came from the given candles, and a period the phone
 * never received is reported as missing instead of being filled in.
 */
class TradeReplayTest {

    private val interval = Interval.M5
    private val step = interval.millis
    private val base = 1_800_000_000_000L - (1_800_000_000_000L % interval.millis)

    private fun series(from: Long, count: Int): List<Candle> = (0 until count).map { index ->
        val time = from + index * step
        Candle(time, 3000.0, 3005.0, 2995.0, 3001.0, 12.0)
    }

    private fun trade(openedAt: Long, closedAt: Long?) = PaperTrade(
        id = "trade-1",
        symbol = "XAU/USD",
        interval = interval,
        action = SignalAction.BUY,
        entry = 3000.0,
        stopLoss = 2990.0,
        takeProfit = 3020.0,
        confidence = 81.0,
        riskReward = 2.0,
        openedAt = openedAt,
        closedAt = closedAt,
        exitPrice = if (closedAt == null) null else 3020.0,
        pnlUsd = if (closedAt == null) null else 20.0,
    )

    @Test fun `bar time floors to the bar that contains the moment`() {
        assertEquals(base, TradeReplay.barTimeOf(base, interval))
        assertEquals(base, TradeReplay.barTimeOf(base + step - 1L, interval))
        assertEquals(base + step, TradeReplay.barTimeOf(base + step, interval))
    }

    @Test fun `a covered trade draws only real cached bars and marks entry and exit`() {
        val candles = series(base, 120)
        val window = TradeReplay.window(
            trade(base + 40 * step + 61_000L, base + 70 * step + 10_000L),
            candles,
            now = base + 130 * step,
        )
        assertEquals(TradeReplay.Coverage.FULL, window.coverage)
        assertTrue(window.hasChart)
        assertEquals(base + 40 * step, window.entryBarTime)
        assertEquals(base + 70 * step, window.exitBarTime)
        // nothing synthesised: every drawn bar is one of the provided candles
        assertTrue(window.bars.all { bar -> candles.any { it == bar } })
        assertEquals(base + (40 - TradeReplay.PADDING_BARS) * step, window.bars.first().time)
        assertEquals(base + (70 + TradeReplay.PADDING_BARS) * step, window.bars.last().time)
    }

    @Test fun `a hole in local history is reported, never filled`() {
        val candles = series(base, 60) // the device stopped receiving before the exit
        val window = TradeReplay.window(
            trade(base + 40 * step, base + 80 * step),
            candles,
            now = base + 100 * step,
        )
        assertEquals(TradeReplay.Coverage.PARTIAL, window.coverage)
        assertEquals(base + 40 * step, window.entryBarTime)
        assertNull(window.exitBarTime)
        assertTrue(window.detail.contains("ناقص"))
        assertTrue(window.bars.none { it.time > base + 59 * step })
    }

    @Test fun `no candles for the period means no chart at all`() {
        val empty = TradeReplay.window(trade(base + 10 * step, base + 12 * step), emptyList())
        assertEquals(TradeReplay.Coverage.MISSING, empty.coverage)
        assertFalse(empty.hasChart)
        assertTrue(empty.bars.isEmpty())

        val unrelated = TradeReplay.window(
            trade(base + 10 * step, base + 12 * step),
            series(base - 5_000 * step, 50), // real bars, but from another period
        )
        assertEquals(TradeReplay.Coverage.MISSING, unrelated.coverage)
        assertTrue(unrelated.bars.isEmpty())
        assertTrue(unrelated.detail.contains("ساختگی"))
    }

    @Test fun `a very long position is trimmed, not thinned out`() {
        val candles = series(base, 1_200)
        val window = TradeReplay.window(
            trade(base + 20 * step, base + 900 * step),
            candles,
            now = base + 1_200 * step,
        )
        assertEquals(TradeReplay.MAX_BARS, window.bars.size)
        assertEquals(TradeReplay.Coverage.PARTIAL, window.coverage)
        assertNull(window.exitBarTime)
        // still a contiguous slice of the real series — no resampling, no averaged bars
        window.bars.forEachIndexed { index, bar ->
            assertEquals(base + (20 - TradeReplay.PADDING_BARS + index) * step, bar.time)
        }
    }

    @Test fun `an open position is drawn up to the newest bar and says so`() {
        val window = TradeReplay.window(
            trade(base + 60 * step, null),
            series(base, 100),
            now = base + 99 * step,
        )
        assertEquals(TradeReplay.Coverage.FULL, window.coverage)
        assertEquals(base + 60 * step, window.entryBarTime)
        assertNull(window.exitBarTime)
        assertEquals(base + 99 * step, window.bars.last().time)
        assertTrue(window.detail.contains("باز"))
    }
}
