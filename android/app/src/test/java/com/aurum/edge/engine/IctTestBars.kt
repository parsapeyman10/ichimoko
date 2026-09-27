package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import java.time.Instant

/** Market-shaped OHLC only for JVM tests; never shipped in the APK or a feed. */
object IctTestBars {
    private val start = Instant.parse("2024-01-15T11:45:00Z").toEpochMilli()
    private val step = Interval.M5.millis
    private val centers = listOf(100.6, 100.2, 100.8, 101.6, 102.6, 103.4,
        103.8, 103.4, 102.7, 101.9, 101.2, 100.6)

    /** [barTime] is the retest bar OPEN. Its close must be in a New York ICT window. */
    fun readyAt(barTime: Long, entry: Double = 3000.0, mirror: Boolean = false): List<Candle> {
        fun candle(i: Int, open: Double, high: Double, low: Double, close: Double) =
            Candle(start + i * step, open, high, low, close)
        val range = (0 until 32).map { i ->
            val center = centers[(i + 6) % centers.size]
            if (i == 31) candle(i, 100.35, 100.55, 99.90, 100.15)
            else candle(i, center - 0.10, center + 0.30, center - 0.30, center + 0.10)
        }
        val model = range + listOf(
            candle(32, 100.15, 100.85, 99.35, 100.45),
            candle(33, 100.45, 101.75, 100.40, 101.55),
            candle(34, 101.16, 101.70, 101.05, 101.45),
            candle(35, 100.82, 101.10, 100.78, 100.96),
        )
        val offset = entry - (if (mirror) 204.0 - 100.96 else 100.96) * 3.0
        return model.map { c ->
            val open = if (mirror) 204.0 - c.open else c.open
            val high = if (mirror) 204.0 - c.low else c.high
            val low = if (mirror) 204.0 - c.high else c.low
            val close = if (mirror) 204.0 - c.close else c.close
            c.copy(time = barTime - 35 * step + (c.time - start),
                open = open * 3.0 + offset, high = high * 3.0 + offset,
                low = low * 3.0 + offset, close = close * 3.0 + offset)
        }
    }
}
