package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are confined to JVM tests; production replay has no decision override. */
class BacktesterExecutionTest {
    private val interval = Interval.M5
    private fun bars(count: Int = 215): List<Candle> = (0 until count).map { i ->
        Candle(1_700_000_000_000L + i * interval.millis,
            open = 100.0, high = 100.4, low = 99.6, close = 100.0, volume = 100.0)
    }
    private fun buy(at: Candle, stop: Double = 95.0, target: Double = 105.0) =
        Signal(SignalAction.BUY, 90.0, entry = at.close, stopLoss = stop,
            takeProfit = target, barTime = at.time, interval = interval)
    private fun run(candles: List<Candle>, signalIndex: Int, target: Double = 105.0) =
        Backtester.runWithDecisions(candles, interval, { i ->
            if (i == signalIndex) buy(candles[i], target = target)
            else Signal(SignalAction.NO_TRADE, 0.0)
        }, spreadPrice = 0.20, commissionPerOz = 0.0)

    @Test fun `close signal fills at NEXT bar open and can hit conservative stop in entry bar`() {
        val input = bars().toMutableList()
        input[213] = input[213].copy(open = 101.0, high = 107.0, low = 94.0, close = 100.0)
        val result = run(input, signalIndex = 212)
        val trade = result.trades.single()
        assertEquals(input[213].time, trade.entryTime)
        assertTrue(trade.entryTime > input[212].time)
        assertEquals(101.1, trade.entry, 1e-8)
        assertEquals(input[213].time, trade.exitTime) // eligible for SL/TP on entry bar
        assertEquals("حد ضرر", trade.exitReason) // both target and stop touched: stop first
        assertEquals(95.0, trade.exit, 1e-8)
        assertEquals(trade.pnlUsd, result.netPnl, 1e-8)
        assertFalse(result.openAtEnd)
    }

    @Test fun `end of history never books an unrealized win and last-bar signal cannot fill`() {
        val input = bars().toMutableList()
        input[214] = input[214].copy(open = 101.0, high = 102.0, low = 100.5, close = 101.0)
        val open = run(input, signalIndex = 213)
        assertTrue(open.trades.isEmpty())
        assertTrue(open.openAtEnd)
        assertEquals(10_000.0, open.finalBalance, 1e-8)
        assertEquals(0.0, open.netPnl, 1e-8)
        assertEquals(0.0, open.feesUsd, 1e-8)
        assertTrue(open.note.contains("باز ماند"))
        assertFalse(run(input, signalIndex = 214).openAtEnd)
    }

    @Test fun `discretionary close waits for next open and long stop gaps through at worse bid`() {
        val input = bars(240).toMutableList()
        input[225] = input[225].copy(open = 103.0, high = 103.4, low = 102.8, close = 103.0)
        val timed = run(input, signalIndex = 211, target = 200.0).trades.single()
        assertEquals(input[212].time, timed.entryTime)
        assertEquals(input[225].time, timed.exitTime) // time stop on close[224], not close[224] fill
        assertEquals(102.9, timed.exit, 1e-8)
        assertTrue(timed.exitReason.contains("زمان"))

        val gap = bars().toMutableList()
        gap[213] = gap[213].copy(open = 101.0, high = 101.5, low = 100.5, close = 101.0)
        gap[214] = gap[214].copy(open = 90.0, high = 92.0, low = 88.0, close = 90.0)
        val stopped = run(gap, signalIndex = 212).trades.single()
        assertEquals("حد ضرر", stopped.exitReason)
        assertEquals(89.9, stopped.exit, 1e-8) // not the optimistic stop=95
    }

    @Test fun `temporal gap rejects pending entry and leaves crossed open trade unresolved`() {
        val input = bars().toMutableList()
        input[214] = input[214].copy(time = input[214].time + interval.millis)
        val rejected = run(input, signalIndex = 213)
        assertEquals(1, rejected.skippedGap)
        assertTrue(rejected.trades.isEmpty())
        assertFalse(rejected.openAtEnd)

        input[213] = input[213].copy(open = 101.0, high = 101.5, low = 100.5, close = 101.0)
        val unresolved = run(input, signalIndex = 212)
        assertEquals(1, unresolved.unresolvedGap)
        assertTrue(unresolved.trades.isEmpty())
        assertFalse(unresolved.openAtEnd)
        assertEquals(0.0, unresolved.netPnl, 1e-8)
    }

    @Test fun `short stop wins ambiguous bar and pending exits respect protective opening gaps`() {
        val input = bars().toMutableList()
        input[213] = input[213].copy(open = 99.0, high = 106.0, low = 94.0, close = 100.0)
        val sell = Backtester.runWithDecisions(input, interval, { i ->
            if (i == 212) Signal(SignalAction.SELL, 90.0, stopLoss = 105.0,
                takeProfit = 95.0, barTime = input[i].time, interval = interval)
            else Signal(SignalAction.NO_TRADE, 0.0)
        }, spreadPrice = 0.20, commissionPerOz = 0.0).trades.single()
        assertEquals(98.9, sell.entry, 1e-8)
        assertEquals("حد ضرر", sell.exitReason)
        assertEquals(105.0, sell.exit, 1e-8)

        val longGap = input[214].copy(open = 110.0, high = 110.5, low = 109.5, close = 110.0)
        assertEquals(105.0, BarFillRules.nextOpenExit(longGap, SignalAction.BUY,
            95.0, 105.0, 0.20, "زمان").fill, 1e-8) // no windfall beyond target
        val shortGap = input[214].copy(open = 110.0, high = 110.5, low = 109.5, close = 110.0)
        val adverse = BarFillRules.nextOpenExit(shortGap, SignalAction.SELL,
            105.0, 95.0, 0.20, "زمان")
        assertEquals(110.1, adverse.fill, 1e-8)
        assertTrue(adverse.reason.contains("گپ"))
    }

    @Test fun `price gaps through original risk and reward cancel an entry rather than invent a fill`() {
        val input = bars().toMutableList()
        input[214] = input[214].copy(open = 106.0, high = 106.5, low = 105.8, close = 106.0)
        val skipped = run(input, signalIndex = 213)
        assertEquals(1, skipped.skippedFill)
        assertTrue(skipped.trades.isEmpty())
        assertFalse(skipped.openAtEnd)
        assertEquals(0.0, skipped.netPnl, 1e-8)
    }
}
