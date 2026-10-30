package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.*
import org.junit.Test

class ArchiveCandleCompressionTest {
    @Test fun aggregationPreservesEndpointsAndExtremes() {
        val bars = (0 until 101).map { i -> Candle(i.toLong(), 100.0 + i, 102.0 + i, 99.0 + i, 101.0 + i, 1.0) }
        val result = compressArchiveCandles(bars, 10)
        assertTrue(result.size <= 10)
        assertEquals(bars.first().open, result.first().open, 0.0)
        assertEquals(bars.last().close, result.last().close, 0.0)
        assertEquals(bars.maxOf { it.high }, result.maxOf { it.high }, 0.0)
        assertEquals(bars.minOf { it.low }, result.minOf { it.low }, 0.0)
        assertEquals(101.0, result.sumOf { it.volume }, 0.0)
    }
    @Test fun sortsAndDropsDuplicateAndInvalidCandles() {
        val c = Candle(1, 1.0, 2.0, 0.5, 1.5)
        assertEquals(listOf(c), compressArchiveCandles(listOf(c, c, c.copy(time = 2, close = Double.NaN)), 5))
        assertTrue(compressArchiveCandles(emptyList(), 5).isEmpty())
    }
}
