package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.ReplayDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayEvaluationTest {
    private val interval = Interval.M5
    private val base = 1_700_000_000_000L

    private fun decision(action: String = "BUY", barTime: Long = base + interval.millis): ReplayDecision =
        ReplayDecision(
            id = "replay-test",
            symbol = "XAU/USD",
            interval = interval.label,
            barTime = barTime,
            action = action,
            entry = 100.0,
            stopLoss = if (action == "BUY") 98.0 else 102.0,
            takeProfit = if (action == "BUY") 104.0 else 96.0,
            confidence = 1.0,
            profile = "base",
            dataSource = "fixture",
            recordedAt = base,
        )

    @Test
    fun decisionDoesNotUseFutureUntilCursorAdvances() {
        val bars = listOf(
            Candle(base, 100.0, 101.0, 99.0, 100.0),
            Candle(base + interval.millis, 100.0, 101.0, 99.0, 100.0),
            Candle(base + 2 * interval.millis, 100.0, 101.0, 99.0, 100.0),
            Candle(base + 3 * interval.millis, 100.0, 103.0, 99.0, 102.0),
            Candle(base + 4 * interval.millis, 102.0, 105.0, 101.0, 104.5),
        )
        val recorded = decision()

        assertEquals(ReplayEvaluation.DECISION_ONLY, ReplayEvaluation.evaluate(recorded, bars, interval, 0).status)
        assertEquals(ReplayEvaluation.PENDING_ENTRY, ReplayEvaluation.evaluate(recorded, bars, interval, 1).status)
        assertEquals(ReplayEvaluation.OPEN, ReplayEvaluation.evaluate(recorded, bars, interval, 3).status)
        assertEquals(ReplayEvaluation.WIN, ReplayEvaluation.evaluate(recorded, bars, interval, 4).status)
        val rewound = ReplayEvaluation.evaluate(recorded, bars, interval, 3)
        assertEquals(ReplayEvaluation.OPEN, rewound.status)
        assertNull(rewound.outcomeBarTime)
    }

    @Test
    fun longAndShortResolveWithRealRevealedBars() {
        val longBars = listOf(
            Candle(base, 100.0, 100.5, 99.5, 100.0),
            Candle(base + interval.millis, 100.0, 100.5, 99.5, 100.0),
            Candle(base + 2 * interval.millis, 100.0, 101.0, 99.0, 100.0),
            Candle(base + 3 * interval.millis, 100.0, 100.5, 97.0, 98.0),
        )
        val shortBars = listOf(
            Candle(base, 100.0, 100.5, 99.5, 100.0),
            Candle(base + interval.millis, 100.0, 100.5, 99.5, 100.0),
            Candle(base + 2 * interval.millis, 100.0, 101.0, 99.0, 100.0),
            Candle(base + 3 * interval.millis, 100.0, 103.0, 101.0, 102.5),
        )

        assertEquals(ReplayEvaluation.LOSS, ReplayEvaluation.evaluate(decision("BUY"), longBars, interval, 3).status)
        assertEquals(ReplayEvaluation.LOSS, ReplayEvaluation.evaluate(decision("SELL"), shortBars, interval, 3).status)
    }

    @Test
    fun simultaneousLevelsAreStopFirstAndGapsAreNotGuessed() {
        val bothTouched = listOf(
            Candle(base, 100.0, 100.5, 99.5, 100.0),
            Candle(base + interval.millis, 100.0, 100.5, 99.5, 100.0),
            Candle(base + 2 * interval.millis, 100.0, 105.0, 97.0, 100.0),
        )
        assertEquals(ReplayEvaluation.LOSS, ReplayEvaluation.evaluate(decision(), bothTouched, interval, 2).status)
        assertEquals(98.0, ReplayEvaluation.evaluate(decision(), bothTouched, interval, 2).outcomePrice!!, 1e-9)

        val gap = listOf(
            Candle(base, 100.0, 100.5, 99.5, 100.0),
            Candle(base + interval.millis, 100.0, 100.5, 99.5, 100.0),
            Candle(base + 3 * interval.millis, 100.0, 101.0, 99.0, 100.0),
        )
        val gapResult = ReplayEvaluation.evaluate(decision(), gap, interval, 2)
        assertEquals(ReplayEvaluation.DATA_GAP, gapResult.status)
        assertNull(gapResult.outcomePrice)
    }

    @Test
    fun endOfVisibleDatasetStaysOpenWithoutInventingAnExit() {
        val bars = listOf(
            Candle(base, 100.0, 100.5, 99.5, 100.0),
            Candle(base + interval.millis, 100.0, 100.5, 99.5, 100.0),
            Candle(base + 2 * interval.millis, 100.0, 101.0, 99.0, 100.0),
        )
        val result = ReplayEvaluation.evaluate(decision(), bars, interval, bars.lastIndex)
        assertEquals(ReplayEvaluation.OPEN_AT_END, result.status)
        assertEquals(base + 2 * interval.millis, result.fillBarTime)
        assertNull(result.outcomeBarTime)
    }
}
