package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.IctRangeAnalyzer.State
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class IctRangeAnalyzerTest {
    private val step = Interval.M5.millis
    // Test-only OHLC fixtures. NEVER used as market data by the app.
    private val base = Instant.parse("2024-01-15T11:45:00Z").toEpochMilli() // 06:45 EST
    private val centers = listOf(100.6, 100.2, 100.8, 101.6, 102.6, 103.4,
        103.8, 103.4, 102.7, 101.9, 101.2, 100.6)

    private fun candle(i: Int, open: Double, high: Double, low: Double, close: Double,
                       closed: Boolean = true) = Candle(base + i * step, open, high, low, close,
        closed = closed)

    private fun baseline(): List<Candle> = (0 until 32).map { i ->
        val center = centers[(i + 6) % centers.size]
        if (i == 31) candle(i, 100.35, 100.55, 99.90, 100.15) // opposite OB candidate
        else candle(i, center - 0.10, center + 0.30, center - 0.30, center + 0.10)
    }

    private fun sweep() = candle(32, 100.15, 100.85, 99.35, 100.45)
    private fun shift() = candle(33, 100.45, 101.75, 100.40, 101.55)
    private fun fvg() = candle(34, 101.16, 101.70, 101.05, 101.45)
    private fun retest() = candle(35, 100.82, 101.10, 100.78, 100.96)
    private fun setup() = baseline() + listOf(sweep(), shift(), fvg(), retest())

    @Test fun `range uses repeated levels from bars before sweep then confirms retest`() {
        val assessment = IctRangeAnalyzer.analyze(setup(), Interval.M5)
        val range = requireNotNull(assessment.range)
        assertEquals(99.90, range.support, 0.00001)
        assertEquals(104.10, range.resistance, 0.00001)
        assertTrue(range.supportTouches >= 2)
        assertTrue(range.resistanceTouches >= 2)
        assertTrue(range.confirmedAt < sweep().time)
        assertEquals(State.READY, assessment.buy.state)
        assertEquals(sweep().time, assessment.buy.sweepAt)
        assertEquals(shift().time, assessment.buy.shiftAt)
        assertEquals(fvg().time, assessment.buy.fvg?.at)
        assertNotNull(assessment.buy.orderBlock)
        assertEquals(retest().time, assessment.buy.retestAt)
        assertTrue((assessment.buy.rewardRisk ?: 0.0) >= 1.5)
        assertEquals(IctRangeAnalyzer.Session.NEW_YORK, assessment.window?.session)
    }

    @Test fun `mirror-image ceiling sweep requires the same evidence for a short`() {
        val mirrored = setup().map { c -> c.copy(open = 204.0 - c.open,
            high = 204.0 - c.low, low = 204.0 - c.high, close = 204.0 - c.close) }
        val assessment = IctRangeAnalyzer.analyze(mirrored, Interval.M5)
        assertEquals(State.READY, assessment.sell.state)
        assertFalse(assessment.buy.ready)
        assertEquals(99.90, assessment.range?.support ?: 0.0, 0.00001)
        assertEquals(104.10, assessment.range?.resistance ?: 0.0, 0.00001)
    }

    @Test fun `an open confirmation or future extreme cannot backfill a signal`() {
        val beforeClose = IctRangeAnalyzer.analyze(baseline() + listOf(sweep(), shift(), fvg()), Interval.M5)
        assertNotEquals(State.READY, beforeClose.buy.state)
        val withOpen = IctRangeAnalyzer.analyze(setup().dropLast(1) +
            retest().copy(closed = false, high = 9000.0, low = 0.0001), Interval.M5)
        assertEquals(beforeClose, withOpen)
        // A later bar can update the CURRENT snapshot, never the earlier assessment.
        assertEquals(State.READY, IctRangeAnalyzer.analyze(setup(), Interval.M5).buy.state)
    }

    @Test fun `touching support or moving sideways never authorizes entry`() {
        val later = listOf(
            candle(32, 100.5, 101.1, 100.0, 100.5),
            candle(33, 100.5, 101.2, 100.0, 100.7),
            candle(34, 100.7, 101.3, 100.1, 100.8),
            candle(35, 100.8, 101.4, 100.2, 101.0),
        )
        val evaluation = IctRangeAnalyzer.analyze(baseline() + later, Interval.M5)
        assertNotEquals(State.READY, evaluation.buy.state)
        assertNotNull(evaluation.range)
    }

    @Test fun `sweep without shift and shift without imbalance are blocked`() {
        val noShift = baseline() + sweep() + listOf(
            candle(33, 100.4, 100.9, 100.2, 100.6),
            candle(34, 100.6, 100.9, 100.2, 100.5),
        )
        assertEquals(State.WAIT_MSS, IctRangeAnalyzer.analyze(noShift, Interval.M5).buy.state)
        val noGap = baseline() + sweep() + shift() +
            candle(34, 100.9, 101.3, 100.7, 101.0)
        assertEquals(State.WAIT_FVG, IctRangeAnalyzer.analyze(noGap, Interval.M5).buy.state)
    }

    @Test fun `risk reward and session gate the entire setup`() {
        val highRetest = candle(35, 101.1, 101.7, 100.9, 101.6)
        assertEquals(State.POOR_REWARD_RISK,
            IctRangeAnalyzer.analyze(setup().dropLast(1) + highRetest, Interval.M5).buy.state)
        val outside = setup().map { it.copy(time = it.time + 4 * 60 * 60_000L) }
        assertEquals(State.OUTSIDE_SESSION,
            IctRangeAnalyzer.analyze(outside, Interval.M5).buy.state)
    }

    @Test fun `invalid gaps candles and higher timeframes fail closed`() {
        assertEquals(State.INVALID_DATA, IctRangeAnalyzer.analyze(baseline(), Interval.M5).buy.state)
        assertEquals(State.INVALID_DATA, IctRangeAnalyzer.analyze(setup(), Interval.H1).buy.state)
        assertEquals(State.INVALID_DATA,
            IctRangeAnalyzer.analyze(setup().mapIndexed { i, c ->
                if (i == 30) c.copy(high = c.low - 1.0) else c
            }, Interval.M5).buy.state)
        assertEquals(State.INVALID_DATA,
            IctRangeAnalyzer.analyze(setup().mapIndexed { i, c ->
                if (i >= 30) c.copy(time = c.time + 3 * step) else c
            }, Interval.M5).buy.state)
    }

    @Test fun `New York daylight savings controls ICT windows not device timezone`() {
        val winter = IctRangeAnalyzer.sessionAt(Instant.parse("2024-01-15T12:30:00Z").toEpochMilli())
        val summer = IctRangeAnalyzer.sessionAt(Instant.parse("2024-07-15T11:30:00Z").toEpochMilli())
        assertEquals(IctRangeAnalyzer.Session.NEW_YORK, winter.session)
        assertEquals(IctRangeAnalyzer.Session.NEW_YORK, summer.session)
        assertEquals(IctRangeAnalyzer.Session.OUTSIDE,
            IctRangeAnalyzer.sessionAt(Instant.parse("2024-01-15T11:30:00Z").toEpochMilli()).session)
        assertEquals(IctRangeAnalyzer.Session.LONDON,
            IctRangeAnalyzer.sessionAt(Instant.parse("2024-03-10T07:30:00Z").toEpochMilli()).session)
    }
}
