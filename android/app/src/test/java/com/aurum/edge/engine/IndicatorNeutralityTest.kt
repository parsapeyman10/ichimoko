package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndicatorNeutralityTest {
    private fun bars(closes: List<Double>) = closes.mapIndexed { i, close ->
        Candle(1_700_000_000_000L + i * 60_000L, close, close, close, close)
    }

    @Test fun flatRsiIsNeutralAndOneWayMovementHasDefinedExtremes() {
        val flat = Indicators.rsi(bars(List(40) { 100.0 }), 7)
        assertNull(flat[6])
        assertEquals(50.0, flat[7]!!, 0.0)
        assertEquals(50.0, flat.last()!!, 0.0)
        assertEquals(100.0, Indicators.rsi(bars((0..39).map { it.toDouble() + 100 }), 7).last()!!, 0.0)
        assertEquals(0.0, Indicators.rsi(bars((0..39).map { 100.0 - it }), 7).last()!!, 0.0)
    }
}
