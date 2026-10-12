package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic geometry only; not exchange candles, market performance or a backtest. */
class IchimokuEntrySetupTest {
    private val base = 1_700_000_000_000L
    private val bars = (0 until 120).map { i ->
        Candle(base + i * Interval.M5.millis, 100.0, 100.4, 99.6, 100.0)
    }
    private val atr = List<Double?>(120) { 0.4 }
    private val tenkan = MutableList<Double?>(120) { 100.0 }.also { it[119] = 100.1 }
    private val kijun = List<Double?>(120) { 100.0 }
    private val a = List<Double?>(120) { 100.02 }
    private val b = List<Double?>(120) { 100.0 }
    private val ichi = Ichimoku(tenkan, kijun, a, b, List(120) { null }, 24)
    private fun setup(c: List<Candle> = bars, i: Ichimoku = ichi, side: SignalAction = SignalAction.BUY) =
        IchimokuEntrySetup.assess(c, i, atr, Interval.M5, side)

    @Test fun `closed recent directional cross plus prior compression and visible thin cloud`() {
        val evidence = setup()
        assertNotNull(evidence)
        assertEquals(5, evidence!!.compressedBars)
        assertTrue(evidence.matches(Signal(SignalAction.BUY, 75.0,
            interval = Interval.M5, barTime = bars.last().time)))
        assertFalse(evidence.matches(Signal(SignalAction.SELL, 75.0,
            interval = Interval.M5, barTime = bars.last().time)))
        assertNull(setup(side = SignalAction.SELL))
    }

    @Test fun `flat lines or thick visible cloud never authorize an entry`() {
        assertNull(setup(i = ichi.copy(tenkan = List(120) { 100.0 })))
        val thick = b.toMutableList().also { it[119 - 24] = 99.0 }
        assertNull(setup(i = ichi.copy(senkouB = thick)))
        val noCompression = tenkan.toMutableList().also { for (j in 114..118) it[j] = 100.5 }
        assertNull(setup(i = ichi.copy(tenkan = noCompression)))
    }

    @Test fun `old or open or gapped bar cannot masquerade as a recent closed cross`() {
        val old = tenkan.toMutableList().also { it[117] = 100.1; it[118] = 100.1 }
        assertNull(setup(i = ichi.copy(tenkan = old)))
        assertNull(setup(c = bars.dropLast(1) + bars.last().copy(closed = false)))
        assertNull(setup(c = bars.mapIndexed { j, c -> if (j == 110) c.copy(time = c.time + 1) else c }))
        assertNull(setup(i = ichi.copy(senkouB = List(120) { null })))
    }

    @Test fun `bearish cross requires bearish lines to persist through last completed bar`() {
        val down = ichi.copy(tenkan = tenkan.toMutableList().also { it[119] = 99.9 })
        assertNotNull(setup(i = down, side = SignalAction.SELL))
        assertNull(setup(i = down, side = SignalAction.BUY))
    }
}
