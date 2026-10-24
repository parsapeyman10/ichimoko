package com.aurum.edge.engine

import com.aurum.edge.domain.Interval
import com.aurum.edge.data.DukascopyHistoryClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `maps chart instruments and intervals for the JSON transport`() {
        assertEquals("XAU/USD", DukascopyHistoryClient.chartInstrument("XAUUSD"))
        assertEquals("XAG/USD", DukascopyHistoryClient.chartInstrument("XAGUSD"))
        assertEquals("USD/JPY", DukascopyHistoryClient.chartInstrument("USDJPY"))
        assertEquals("5MIN", DukascopyHistoryClient.chartInterval(Interval.M5))
        assertEquals("15MIN", DukascopyHistoryClient.chartInterval(Interval.M15))
        assertEquals("1HOUR", DukascopyHistoryClient.chartInterval(Interval.H1))
        assertEquals("1DAY", DukascopyHistoryClient.chartInterval(Interval.D1))
    }

    @Test fun `parses dukascopy chart jsonp and drops the forming candle`() {
        val now = 1_792_399_800_000L // aligned to 5 minutes
        val body = "aurum([[${now - 300_000},4172.1,4173.2,4171.0,4172.5,0.20]," +
            "[${now - 600_000},4170.5,4172.9,4169.8,4172.1,0.18]," +
            "[${now - 900_000},4168.0,4171.4,4167.2,4170.5,0.22]," +
            "[$now,4172.5,4174.0,4172.0,4173.1,0.05]]);"

        val bars = DukascopyHistoryClient.parseChartPayload(body, Interval.M5, now)

        assertEquals(3, bars.size)
        assertEquals(now - 900_000L, bars.first().time)
        assertEquals(4168.0, bars.first().open, 0.00001)
        assertEquals(4171.4, bars.first().high, 0.00001)
        assertEquals(4167.2, bars.first().low, 0.00001)
        assertEquals(4170.5, bars.first().close, 0.00001)
        assertEquals(now - 300_000L, bars.last().time)
        assertTrue(bars.all { it.closed })
    }

    @Test fun `rejects a chart payload without jsonp structure`() {
        val failure = runCatching {
            DukascopyHistoryClient.parseChartPayload("not-json", Interval.M5)
        }.exceptionOrNull()
        assertTrue(failure is com.aurum.edge.data.DataFeedException)
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
