package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicCandleHistoryClientTest {
    @Test
    fun `gap detector reports missing real bars without filling them`() {
        val interval = Interval.M5
        val first = Candle(1_700_000_000_000L, 1.0, 1.1, 0.9, 1.0)
        val third = first.copy(time = first.time + interval.millis * 3L)

        val gaps = PublicCandleHistoryClient.detectGaps(listOf(first, third), interval)

        assertEquals(1, gaps.size)
        assertEquals(2L, gaps.single().missingBars)
    }
}
