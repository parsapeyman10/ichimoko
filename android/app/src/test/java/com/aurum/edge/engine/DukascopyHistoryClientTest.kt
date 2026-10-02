package com.aurum.edge.engine

import com.aurum.edge.core.Interval
import com.aurum.edge.data.DukascopyHistoryClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DukascopyHistoryClientTest {
    @Test fun `maps gold to spot dukascopy symbol and point scale`() {
        assertEquals("XAUUSD", DukascopyHistoryClient.instrument("XAU/USD"))
        assertEquals(1_000.0, DukascopyHistoryClient.pointScale("XAUUSD"), 0.0)
        assertEquals(1_000.0, DukascopyHistoryClient.pointScale("USDJPY"), 0.0)
        assertEquals(100_000.0, DukascopyHistoryClient.pointScale("EURUSD"), 0.0)
    }

    @Test fun `parses dukascopy candle binary without changing gold prices`() {
        val base = 1_704_067_200_000L // 2024-01-01T00:00:00Z
        val raw = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putInt(0).putInt(4_137_000).putInt(4_138_100).putInt(4_136_200).putInt(4_137_550).putFloat(2.5f)
            .putInt(60).putInt(4_137_550).putInt(4_137_800).putInt(4_137_100).putInt(4_138_400).putFloat(3.0f)
            .array()

        val bars = DukascopyHistoryClient.parseRawCandlePayload(
            raw = raw,
            pointScale = 1_000.0,
            baseTime = base,
            interval = Interval.M1,
            now = base + 120_000L,
        )

        assertEquals(2, bars.size)
        assertEquals(base, bars.first().time)
        assertEquals(4137.0, bars.first().open, 0.00001)
        assertEquals(4138.1, bars.first().high, 0.00001)
        assertEquals(4136.2, bars.first().low, 0.00001)
        assertEquals(4137.55, bars.first().close, 0.00001)
        assertEquals(base + 60_000L, bars.last().time)
        assertEquals(4138.4, bars.last().high, 0.00001)
        assertEquals(4137.8, bars.last().close, 0.00001)
    }
}
