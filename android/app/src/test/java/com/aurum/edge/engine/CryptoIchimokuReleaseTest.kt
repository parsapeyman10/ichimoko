package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic *unit fixtures*, not real exchange prices, performance or a backtest. */
class CryptoIchimokuReleaseTest {
    private val start = 1_700_000_000_000L
    private val bars = List(100) { i ->
        Candle(start + i * 300_000L, 100.0, 100.2, 99.8, 100.0, 100.0)
    }.dropLast(1) + Candle(start + 99 * 300_000L, 100.0, 100.9, 99.9, 100.7, 150.0)
    private val tenkan = MutableList<Double?>(100) { 100.0 }.also { it[99] = 100.4 }
    private val kijun = MutableList<Double?>(100) { 100.0 }.also { it[99] = 100.15 }
    private val a = MutableList<Double?>(100) { 100.05 }.also { it[99] = 100.275 }
    private val b = MutableList<Double?>(100) { 100.0 }
    private val ichi = Ichimoku(tenkan, kijun, a, b, List(100) { null }, 24)

    @Test fun `only confirmed compression close chikou cloud clearance and volume form a release`() {
        val result = CryptoIchimokuRelease.assess(bars, ichi, 0.4, SignalAction.BUY)
        assertTrue(result.detail, result.ready)
        assertEquals(5, result.compressedBars)
        assertTrue(result.chikouClear)
        assertTrue(result.breakout)
        assertTrue(result.structureConfirmed)
        assertTrue(result.volumeConfirmed)
        assertFalse(CryptoIchimokuRelease.assess(bars, ichi, 0.4, SignalAction.SELL).ready)
        assertFalse(CryptoIchimokuRelease.assess(bars, ichi, 0.4, SignalAction.NO_TRADE).ready)
    }

    @Test fun `bearish Chikou release is directional and needs a real downside close`() {
        val down = bars.dropLast(1) + bars.last().copy(
            open = 100.0, high = 100.1, low = 99.1, close = 99.3)
        val bearish = ichi.copy(
            tenkan = tenkan.toMutableList().also { it[99] = 99.6 },
            kijun = kijun.toMutableList().also { it[99] = 99.85 },
            senkouA = a.toMutableList().also { it[99] = 99.725 },
        )
        assertTrue(CryptoIchimokuRelease.assess(down, bearish, 0.4, SignalAction.SELL).ready)
        assertFalse(CryptoIchimokuRelease.assess(down, bearish, 0.4, SignalAction.BUY).ready)
    }

    @Test fun `a thin cloud alone never authorizes a crypto breakout`() {
        assertFalse(CryptoIchimokuRelease.assess(bars.dropLast(1) + bars.last().copy(
            close = 100.1, high = 100.2), ichi, 0.4, SignalAction.BUY).ready)
        assertFalse(CryptoIchimokuRelease.assess(bars.dropLast(1) + bars.last().copy(
            volume = 0.0), ichi, 0.4, SignalAction.BUY).ready)
        // The Chikou has to clear the cloud at its OWN display index 24 bars behind.
        // Change only that old cloud, not today's projected cloud or the breakout candle.
        val cloudAtLag = ichi.copy(senkouA = a.toMutableList().also { it[51] = 105.0 })
        val blocked = CryptoIchimokuRelease.assess(bars, cloudAtLag, 0.4, SignalAction.BUY)
        assertFalse(blocked.chikouClear)
        assertFalse(blocked.ready)
        assertFalse(CryptoIchimokuRelease.assess(bars.take(80), ichi, 0.0, SignalAction.BUY).ready)
    }

    private fun closedSeries(interval: Interval, now: Long, release: Boolean = false): List<Candle> {
        val end = now / interval.millis * interval.millis - interval.millis
        return (0 until 320).map { j ->
            val time = end - (319 - j) * interval.millis
            when {
                release && j == 319 -> Candle(time, 100.1, 100.9, 100.0, 100.7, 150.0)
                release && j in 312..318 -> Candle(time, 100.0, 100.25, 99.95, 100.1, 100.0)
                else -> Candle(time, 100.0, 100.2, 99.8, 100.0, 100.0)
            }
        }
    }

    @Test fun `same four-layer engine can act on confirmed closed crypto release but not missing frames`() {
        val now = System.currentTimeMillis()
        val base = closedSeries(Interval.M5, now, release = true)
        val frames = V1Scoring.intervals.associateWith { target ->
            if (target == Interval.M5) base else closedSeries(target, now)
        }
        val confirmed = SignalEngine.evaluate(base, Interval.M5, 60.0, "BTC/USDT",
            CategoryStrategy.HYBRID, frames)
        assertEquals(confirmed.blockers.toString(), SignalAction.BUY, confirmed.action)
        assertTrue(confirmed.confluence.first().detail.contains("فشردگی/چیکوی رمزارز"))
        val explicitRange = SignalEngine.evaluate(base, Interval.M5, 60.0, "BTC/USDT",
            CategoryStrategy.RANGE, frames)
        assertFalse(explicitRange.confluence.first().detail.contains("فشردگی/چیکوی رمزارز"))
        val fx = SignalEngine.evaluate(base, Interval.M5, 60.0, "EUR/USD",
            CategoryStrategy.HYBRID, frames)
        assertFalse(fx.confluence.first().detail.contains("فشردگی/چیکوی رمزارز"))
        val missing = SignalEngine.evaluate(base, Interval.M5, 60.0, "BTC/USDT",
            CategoryStrategy.HYBRID, mapOf(Interval.M5 to base))
        assertEquals(SignalAction.NO_TRADE, missing.action)
        assertTrue(missing.blockers.any { it.contains("۳ تایم‌فریمی کامل نیست") })
    }

    @Test fun `M3 and M15 are never entry bases while three independent frames still confirm M5`() {
        val now = System.currentTimeMillis()
        val m3 = closedSeries(Interval.M3, now, release = true)
        val three = V1Scoring.intervals.associateWith { closedSeries(it, now) }
        assertEquals(3, three.size)
        assertFalse(Interval.M3 in V1Scoring.intervals)
        val complete = SignalEngine.evaluate(m3, Interval.M3, 60.0, "BTC/USDT",
            CategoryStrategy.HYBRID, three)
        assertEquals(SignalAction.NO_TRADE, complete.action)
        assertTrue(complete.blockers.any { it.contains("فقط") && it.contains("5m") })
        val m15 = closedSeries(Interval.M15, now, release = true)
        val higherBase = SignalEngine.evaluate(m15, Interval.M15, 60.0, "BTC/USDT",
            CategoryStrategy.HYBRID, three)
        assertEquals(SignalAction.NO_TRADE, higherBase.action)
        assertTrue(higherBase.blockers.any { it.contains("5m") })
        val missingM15 = SignalEngine.evaluate(m3, Interval.M3, 60.0, "BTC/USDT",
            CategoryStrategy.HYBRID, three - Interval.M15)
        assertEquals(SignalAction.NO_TRADE, missingM15.action)
        assertTrue(missingM15.blockers.any { it.contains("۳ تایم‌فریمی کامل نیست") })
    }
}
