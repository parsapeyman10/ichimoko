package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.VolumeSessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeSessionStatsTest {
    private val day = 24L * 60 * 60_000
    private val now = 20L * day
    private val bars = (0 until 7 * 24).map { i ->
        val hour = i % 24
        Candle(now - (7 * 24 - i) * Interval.H1.millis, 1.0, 1.0, 1.0, 1.0,
            volume = if (hour == 14) 100.0 else 2.0)
    }

    @Test fun `only measured crypto H1 sessions with daily coverage are ranked`() {
        val ranked = VolumeSessionStats.cryptoHours("BTC/USDT", Interval.H1, bars, now)
        assertEquals(3, ranked.size)
        assertEquals(14, ranked.first().utcHour)
        assertEquals(7, ranked.first().samples)
        assertEquals(100.0, ranked.first().meanVolume, 0.001)
        assertTrue(VolumeSessionStats.cryptoHours("BTC/USDT", Interval.M5, bars, now).isEmpty())
        assertTrue(VolumeSessionStats.cryptoHours("USOIL", Interval.H1, bars, now).isEmpty())
    }

    @Test fun `missing, zero, stale or insufficient volume yields no hours`() {
        assertTrue(VolumeSessionStats.cryptoHours("BTC/USDT", Interval.H1,
            bars.dropLast(50), now).isEmpty())
        assertTrue(VolumeSessionStats.cryptoHours("BTC/USDT", Interval.H1,
            bars.mapIndexed { i, bar -> if (i == 5) bar.copy(volume = 0.0) else bar }, now).isEmpty())
        assertTrue(VolumeSessionStats.cryptoHours("BTC/USDT", Interval.H1, bars,
            now + 20 * day).isEmpty())
    }
}
