package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the parts of the app that must never invent data.
 *
 * The candle fixtures below are *test input* — they are not shipped and never reach the UI. What is
 * being checked is the behaviour of the engine: aggregation must keep real OHLC values and drop
 * incomplete buckets, the walk-forward split must keep each trade inside its own window, and a
 * small account must never be given a position below the broker minimum lot.
 */
class EngineTruthTest {

    private fun fixture(count: Int, interval: Interval, startTime: Long = 1_700_000_100_000L, seed: Int = 42): List<Candle> {
        val random = Random(seed)
        val bars = ArrayList<Candle>(count)
        var price = 3300.0
        for (i in 0 until count) {
            val drift = kotlin.math.sin(i / 25.0) * 1.1 + kotlin.math.sin(i / 6.0) * 0.5
            val open = price
            val close = open + drift + random.nextDouble(-0.6, 0.6)
            val high = maxOf(open, close) + random.nextDouble(0.0, 0.4)
            val low = minOf(open, close) - random.nextDouble(0.0, 0.4)
            bars += Candle(
                time = startTime + i * interval.millis,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = random.nextDouble(100.0, 900.0),
                closed = true,
            )
            price = close
        }
        return bars
    }

    @Test
    fun aggregationPreservesRealOhlcAndDropsPartialBuckets() {
        val minutes = fixture(count = 15, interval = Interval.M1)
        val fiveMinute = MtfAnalyzer.resample(minutes, Interval.M5, Interval.M1)

        assertEquals(3, fiveMinute.size)
        fiveMinute.forEachIndexed { bucketIndex, bar ->
            val group = minutes.subList(bucketIndex * 5, bucketIndex * 5 + 5)
            assertEquals(group.first().open, bar.open, 1e-9)
            assertEquals(group.maxOf { it.high }, bar.high, 1e-9)
            assertEquals(group.minOf { it.low }, bar.low, 1e-9)
            assertEquals(group.last().close, bar.close, 1e-9)
            assertEquals(group.sumOf { it.volume }, bar.volume, 1e-6)
        }
        // the newest bucket is still forming, so it must not be treated as a closed bar
        assertTrue(!fiveMinute.last().closed)
        assertTrue(fiveMinute.dropLast(1).all { it.closed })

        // a bucket that only holds a single bar is incomplete: it is dropped, never padded
        val single = MtfAnalyzer.resample(minutes.take(1), Interval.M5, Interval.M1)
        assertTrue(single.isEmpty())
    }

    @Test
    fun walkForwardKeepsTradesInsideTheirOwnWindow() {
        val bars = fixture(count = 900, interval = Interval.M5)
        val report = Backtester.walkForward(
            candles = bars,
            interval = Interval.M5,
            symbol = "XAU/USD",
            initialBalance = 1000.0,
            riskPercent = 0.5,
        )

        assertTrue(report.splitIndex in 1 until bars.size)
        assertTrue(report.inSample.trades.all { it.entryTime < report.splitTime })
        assertTrue(report.outOfSample.trades.all { it.entryTime >= report.splitTime })
        assertTrue(report.verdict.isNotBlank())
        assertEquals(report.bars, bars.size)
    }

    @Test
    fun smallAccountNeverGetsAnImpossiblePosition() {
        val bars = fixture(count = 900, interval = Interval.M5)
        val result = Backtester.run(
            candles = bars,
            interval = Interval.M5,
            symbol = "XAU/USD",
            initialBalance = 100.0,
            riskPercent = 0.5,
            minPositionOz = 1.0,
        )

        assertTrue(result.trades.all { it.positionOz >= result.minPositionOz - 1e-9 })
        // with a $100 account the engine either skips setups, or reports the ones it could size
        assertTrue(result.skippedMinLot >= 0)
        assertTrue(result.finalBalance > 0.0)
    }

    @Test
    fun mtfNeedsRealHistoryBeforeItSaysAnything() {
        assertNull(MtfAnalyzer.analyze(fixture(count = 10, interval = Interval.M5), Interval.M5))
        val snapshot = MtfAnalyzer.analyze(fixture(count = 600, interval = Interval.M5), Interval.M5)
        assertNotNull(snapshot)
        snapshot!!.frames.forEach { frame ->
            // every reported timeframe is one that could actually be built from the base bars
            assertTrue(frame.interval in MtfAnalyzer.framesFor(Interval.M5))
            assertTrue(frame.strength in 0..100)
        }
    }
}
