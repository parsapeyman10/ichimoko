package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class CloudLifetimeEvidenceTest {
    @Test fun `counts age and known future twist without inventing endpoints`() {
        val signs = listOf(-1) + List(30) { 1 } + listOf(-1, -1)
        val s = CloudLifetimeEvidence.fromSigns(signs, 28, 10)!!
        assertEquals(28, s.ageBars)
        assertFalse(s.ageCensored)
        assertEquals(3, s.twistAheadBars)
        assertTrue(s.longNearTwist)
        val truncated = CloudLifetimeEvidence.fromSigns(List(40) { 1 }, 28, 10)!!
        assertTrue(truncated.ageCensored)
        assertNull(truncated.twistAheadBars)
        assertFalse(truncated.longNearTwist)
        assertNull(CloudLifetimeEvidence.fromSigns(listOf(1, 0, -1), 1, 10))
    }

    @Test fun `only closed history up to signal and no gaps`() {
        val interval = Interval.M15
        val bars = (0..119).map { i -> Candle(1_000_000L + i * interval.millis, 100.0 + i, 101.0 + i, 99.0 + i, 100.0 + i, closed = true) }
        val cfg = BaseStrategyConfig(tenkanPeriod = 2, kijunPeriod = 3, senkouBPeriod = 4, displacement = 4)
        val signal = bars[100].time
        val now = signal + interval.millis
        val a = CloudLifetimeEvidence.measure(bars, interval, signal, now, cfg)
        assertNotNull(a)
        val altered = bars.mapIndexed { i, c -> if (i > 100) c.copy(high = 10000.0 + i, low = 1.0) else c }
        assertEquals(a, CloudLifetimeEvidence.measure(altered, interval, signal, now, cfg))
        assertNull(CloudLifetimeEvidence.measure(bars.filterIndexed { i, _ -> i != 50 }, interval, signal, now, cfg))
        assertNull(CloudLifetimeEvidence.measure(bars, interval, signal, signal, cfg))
    }

    @Test fun `lessons exclude future unknown other markets and require samples`() {
        val now = 1_000_000_000L
        fun trade(id: String, near: Boolean) = PaperTrade(id = id, symbol = "BTC/USDT", interval = Interval.M15,
            action = SignalAction.BUY, entry = 100.0, stopLoss = 90.0, takeProfit = 120.0, confidence = 80.0,
            riskReward = 2.0, openedAt = now - 8_000_000L - id.toLong(), closedAt = now - 4_000_000L,
            pnlUsd = if (near) -5.0 else 10.0, fixedRiskUsd = 5.0, origin = PaperTrade.ORIGIN_LIVE,
            cloudAgeBars = if (near) 30 else 10, cloudTwistAheadBars = 3, cloudSignalBarTime = now - 9_000_000L)
        val samples = (1..60).map { trade(it.toString(), it <= 30) }
        val lesson = CloudLifetimeEvidence.lesson(samples, "BTC/USDT", samples.first().strategyId, Interval.M15, SignalAction.BUY, now)
        assertEquals(30, lesson.near.count)
        assertEquals(-1.0, lesson.near.meanR!!, 0.0)
        assertEquals(2.0, lesson.other.meanR!!, 0.0)
        val insufficient = CloudLifetimeEvidence.lesson(samples.take(29), "BTC/USDT", samples.first().strategyId, Interval.M15, SignalAction.BUY, now)
        assertNull(insufficient.near.meanR)
    }
}
