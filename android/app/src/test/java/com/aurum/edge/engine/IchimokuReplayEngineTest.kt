package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IchimokuReplayEngineTest {
    private fun candles(count: Int, offset: Double = 0.0): List<Candle> = (0 until count).map { i ->
        val close = 100.0 + offset + i * 0.1
        Candle(i * Interval.M5.millis + 1_700_000_000_000L, close - 0.2, close + 0.8, close - 0.8, close, 10.0)
    }

    @Test fun `ichimoku exposes all five lines with standard displacement and no padding`() {
        val bars = candles(12)
        val ichi = Ichimoku.compute(bars, tenkanPeriod = 3, kijunPeriod = 4, spanBPeriod = 5, displacement = 2)

        assertEquals(12, ichi.tenkan.size)
        assertEquals(12, ichi.kijun.size)
        assertEquals(12, ichi.senkouA.size)
        assertEquals(12, ichi.senkouB.size)
        assertEquals(12, ichi.chikou.size)
        assertNull(ichi.tenkan[1])
        assertNull(ichi.senkouB[3])
        assertNull(ichi.chikou[10])
        assertEquals(bars[3].close, ichi.chikou[1]!!, 1e-9)

        // Span A/B are computed at their source bar and become visible only two bars later.
        assertNull(ichi.cloudAt(5))
        assertEquals(ichi.senkouA[4]!!, ichi.cloudAt(6)!!.first, 1e-9)
        assertEquals(ichi.senkouB[4]!!, ichi.cloudAt(6)!!.second, 1e-9)
    }

    @Test fun `replay snapshot cannot change from candles after the cursor`() {
        val prefix = candles(260)
        val futureChanged = prefix + candles(80, offset = 10_000.0).mapIndexed { index, candle ->
            candle.copy(time = prefix.last().time + (index + 1) * Interval.M5.millis)
        }
        val config = ReplayEngine.Config(1000.0, 0.5, 0.30, 0.05, 85.0, SignalProfile.BASE)
        val first = ReplayEngine.create(prefix, Interval.M5, "XAU/USD", "fixture", config, startCursor = 210)
        val second = ReplayEngine.create(futureChanged, Interval.M5, "XAU/USD", "fixture", config, startCursor = 210)
        val a = ReplayEngine.snapshot(first)
        val b = ReplayEngine.snapshot(second)

        assertEquals(a.visibleBars, b.visibleBars)
        assertEquals(a.signal?.action, b.signal?.action)
        assertEquals(a.signal?.confidence, b.signal?.confidence)
        assertEquals(a.report?.trades, b.report?.trades)
        assertTrue(a.visibleBars.none { it.time > first.currentBar!!.time })
    }

    @Test fun `replay step seek and reset are immutable`() {
        val config = ReplayEngine.Config(1000.0, 0.5, 0.30, 0.05, 85.0, SignalProfile.BASE)
        val original = ReplayEngine.create(candles(300), Interval.M5, "XAU/USD", "fixture", config, 210)
        val moved = ReplayEngine.step(original, 4)
        val rewound = ReplayEngine.seek(moved, 3)
        val reset = ReplayEngine.reset(moved)

        assertEquals(210, original.cursor)
        assertEquals(214, moved.cursor)
        assertEquals(3, rewound.cursor)
        assertEquals(210, reset.cursor)
        assertEquals(214, moved.visibleBars.size - 1)
    }
}
