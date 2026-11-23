package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartViewportTest {
    @Test fun markersDoNotSnapAcrossGapsOrOutsideVisibleRange() {
        val times = listOf(1000L, 2000L, 4000L)
        assertEquals(0, ChartViewport.candleSlot(times, 1999L, 1000L))
        assertEquals(1, ChartViewport.candleSlot(times, 2000L, 1000L))
        assertEquals(null, ChartViewport.candleSlot(times, 3000L, 1000L))
        assertEquals(null, ChartViewport.candleSlot(times, 5000L, 1000L))
        assertEquals(null, ChartViewport.candleSlot(times, 999L, 1000L))
    }
    @Test fun smallAndInvalidHeightsDoNotThrow() {
        assertEquals(0f, ChartViewport.clampY(5f, 0f, 18f), 0f)
        assertEquals(5f, ChartViewport.clampY(9f, 10f, 18f), 0f)
        assertEquals(50f, ChartViewport.clampY(Float.NaN, 100f, 18f), 0f)
        assertEquals(82f, ChartViewport.clampY(90f, 100f, 18f), 0f)
    }
}
