package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IchimokuAlignmentTest {
    private val candles = List(100) { i ->
        Candle(1_700_000_000_000L + i * 60_000L, 100.0,
            if (i == 99) 112.0 else 105.0, 95.0, if (i == 99) 110.0 else 100.0)
    }

    @Test fun `visible cloud is lagged but projected cloud uses current values without future candles`() {
        val rawA = MutableList<Double?>(100) { 95.0 }
        val rawB = MutableList<Double?>(100) { 90.0 }
        rawA[75] = 101.0
        rawB[75] = 99.0
        rawA[99] = 115.0
        rawB[99] = 106.0
        val ichi = Ichimoku(List(100) { 108.0 }, List(100) { 107.0 }, rawA, rawB,
            List(100) { null }, 24)
        val read = IchimokuAlignment.read(candles, ichi, 2.0)!!
        assertEquals(101.0 to 99.0, read.visibleCloud)
        assertEquals(115.0 to 106.0, read.projectedCloud)
        assertTrue(read.chikouVsHigh) // current close against bar 75, NOT a future Chikou element
        assertTrue(read.trendAligned(SignalAction.BUY, 110.0))
        assertFalse(read.trendAligned(SignalAction.SELL, 110.0))
        val overextendedBars = candles.dropLast(1) + candles.last().copy(high = 122.0, close = 120.0)
        assertFalse(IchimokuAlignment.read(overextendedBars, ichi, 2.0)!!
            .trendAligned(SignalAction.BUY, 120.0)) // price/line gap > 4 ATR
        assertNull(IchimokuAlignment.read(candles.take(24), ichi, 2.0))
        assertNull(IchimokuAlignment.read(candles, ichi, 0.0))
        val cloudAgainst = ichi.copy(senkouA = rawA.toMutableList().also { it[99] = 80.0 })
        assertFalse(IchimokuAlignment.read(candles, cloudAgainst, 2.0)!!
            .trendAligned(SignalAction.BUY, 110.0))
        val fallingTenkan = ichi.copy(tenkan = MutableList<Double?>(100) { 108.0 }.also { it[96] = 110.0 })
        val diverging = IchimokuAlignment.read(candles, fallingTenkan, 2.0)!!
        assertFalse(diverging.linesMovingUp)
        assertFalse(diverging.trendAligned(SignalAction.BUY, 110.0))
    }

    @Test fun `range mode checks compression rather than requiring directional cloud`() {
        val ichi = Ichimoku(List(100) { 101.0 }, List(100) { 100.0 },
            List(100) { 108.0 }, List(100) { 90.0 }, List(100) { null }, 24)
        val rangeBars = candles.dropLast(1) + candles.last().copy(high = 105.0, close = 100.0)
        val read = IchimokuAlignment.read(rangeBars, ichi, 2.0)!!
        assertFalse(read.rangeAligned(110.0))
        assertTrue(read.rangeAligned(100.0))
    }
}
