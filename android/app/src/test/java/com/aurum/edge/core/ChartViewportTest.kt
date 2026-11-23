package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartViewportTest {
    @Test fun smallAndInvalidHeightsDoNotThrow() {
        assertEquals(0f, ChartViewport.clampY(5f, 0f, 18f), 0f)
        assertEquals(5f, ChartViewport.clampY(9f, 10f, 18f), 0f)
        assertEquals(50f, ChartViewport.clampY(Float.NaN, 100f, 18f), 0f)
        assertEquals(82f, ChartViewport.clampY(90f, 100f, 18f), 0f)
    }
}
