package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeMinuteCandlesTest {
    private val start = 1_800_000_000_000L / Interval.M3.millis * Interval.M3.millis
    private fun minute(i: Int) = Candle(start + i * Interval.M1.millis,
        100.0 + i, 102.0 + i, 99.0 + i, 101.0 + i, 2.0)

    @Test fun `three genuine closed aligned minutes become exactly one closed M3 with volume`() {
        val result = ThreeMinuteCandles.fromMinutes((0..2).map(::minute), start + Interval.M3.millis)
        assertEquals(1, result.size)
        assertEquals(start, result.single().time)
        assertEquals(100.0, result.single().open, 0.0)
        assertEquals(104.0, result.single().high, 0.0)
        assertEquals(99.0, result.single().low, 0.0)
        assertEquals(103.0, result.single().close, 0.0)
        assertEquals(6.0, result.single().volume, 0.0)
        assertTrue(result.single().closed)
    }

    @Test fun `forming M3 and a missing minute never turn into a tradeable candle`() {
        val input = (0..8).map(::minute)
        assertEquals(2, ThreeMinuteCandles.fromMinutes(input, start + 8 * Interval.M1.millis).size)
        val hole = input.filter { it.time != start + 4 * Interval.M1.millis }
        val contiguous = ThreeMinuteCandles.fromMinutes(hole, start + 9 * Interval.M1.millis)
        assertEquals(1, contiguous.size)
        assertEquals(start + 6 * Interval.M1.millis, contiguous.single().time)
    }

    @Test fun `320 contiguous M3 candles require 960 real one minute candles`() {
        val full = (0 until 960).map(::minute)
        assertEquals(320, ThreeMinuteCandles.fromMinutes(full, start + 960 * Interval.M1.millis).size)
        assertTrue(ThreeMinuteCandles.fromMinutes(full.dropLast(1), start + 960 * Interval.M1.millis).size < 320)
        assertTrue(ThreeMinuteCandles.fromMinutes(full.filterIndexed { i, _ -> i != 550 },
            start + 960 * Interval.M1.millis).size < 320)
        assertTrue(ThreeMinuteCandles.fromMinutes(full + full[8].copy(close = 5.0),
            start + 960 * Interval.M1.millis).isEmpty())
    }

    @Test fun `no native Nobitex three minute resolution is ever mislabeled`() {
        assertThrows(DataFeedException::class.java) { NobitexHistoryClient.resolution(Interval.M3) }
        assertEquals(Interval.M3, Interval.fromLabel("3m")) // saved journal interval survives reload
    }

    @Test fun `only five minute base can be selected regardless of other scores`() {
        fun read(frame: Interval, action: SignalAction, confidence: Double) =
            CryptoIntervalChoice.Read(frame, listOf(minute(0)), Signal(action, confidence,
                interval = frame))
        val m3 = read(Interval.M3, SignalAction.NO_TRADE, 95.0)
        val m5 = read(Interval.M5, SignalAction.BUY, 80.0)
        val m15 = read(Interval.M15, SignalAction.SELL, 90.0)
        assertEquals(Interval.M5, CryptoIntervalChoice.pick(listOf(m3, m5, m15))?.interval)
        assertEquals(Interval.M5, CryptoIntervalChoice.pick(listOf(m3, m5))?.interval)
        assertEquals(Interval.M5, CryptoIntervalChoice.pick(listOf(m3, read(Interval.M5,
            SignalAction.NO_TRADE, 75.0)))?.interval)
        assertFalse(CryptoIntervalChoice.pick(listOf(m3, m15)) != null)
        assertFalse(CryptoIntervalChoice.pick(listOf(CryptoIntervalChoice.Read(Interval.M5,
            listOf(minute(0)), m15.signal))) != null) // mismatched evidence is not 5m
        assertFalse(CryptoIntervalChoice.pick(emptyList()) != null)
    }
}
