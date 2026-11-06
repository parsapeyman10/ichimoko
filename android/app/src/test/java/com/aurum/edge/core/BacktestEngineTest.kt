package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های موتور بک‌تست.
 *
 * نکتهٔ مهم دربارهٔ این تست‌ها: هیچ‌کدام به اینترنت، فایل واقعی یا حساب برنامه دست نمی‌زنند.
 * همه روی متن CSV و لیست کندلِ ساخته‌شده در حافظه اجرا می‌شوند — همان چیزی که در APK هم
 * اجرا می‌شود.
 */
class BacktestEngineTest {

    // ─────────────────────────── CSV ───────────────────────────

    private val dukascopyCsv = """
        timestamp,open,high,low,close,volume
        1704067200000,1.1000,1.1050,1.0990,1.1040,1200
        1704068100000,1.1040,1.1080,1.1020,1.1070,1500
        1704069000000,1.1070,1.1090,1.1000,1.1010,900
    """.trimIndent()

    @Test
    fun `dukascopy candle csv is parsed with header and millisecond timestamps`() {
        val result = BacktestCsvReader.parse(dukascopyCsv, fileName = "eurusd-m15-bid-2024-01-01-2024-06-01.csv")

        assertEquals(3, result.candles.size)
        assertEquals(BacktestCsvReader.SourceKind.CANDLE, result.sourceKind)
        assertEquals(1704067200000L, result.candles.first().time)
        assertEquals(1.1040, result.candles.first().close, 1e-9)
        assertEquals(1200.0, result.candles.first().volume, 1e-9)
        assertEquals(Interval.M15, result.inferredInterval)
        assertEquals("EUR/USD", result.suggestedSymbol)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun `csv without volume column is accepted because cli omits it by default`() {
        val noVolume = """
            timestamp,open,high,low,close
            1704067200000,1.1000,1.1050,1.0990,1.1040
            1704068100000,1.1040,1.1080,1.1020,1.1070
        """.trimIndent()
        val result = BacktestCsvReader.parse(noVolume)
        assertEquals(2, result.candles.size)
        assertEquals(0.0, result.candles.first().volume, 1e-9)
        // بدون volume هیچ ردیفی «تخت» نیست؛ نباید دور ریخته شود.
        assertEquals(0, result.flatRowsDropped)
    }

    @Test
    fun `tick csv is converted to mid price candles`() {
        val tick = """
            timestamp,askPrice,bidPrice,askVolume,bidVolume
            1704067200000,1.1002,1.1000,10,5
            1704067200500,1.1004,1.1002,8,4
        """.trimIndent()
        val result = BacktestCsvReader.parse(tick, fileName = "xauusd-tick-bid.csv")
        assertEquals(BacktestCsvReader.SourceKind.TICK, result.sourceKind)
        // هر tick یک کندل یک‌نقطه‌ای با میانگین ask/bid می‌شود.
        assertEquals(1.1001, result.candles.first().close, 1e-9)
        assertEquals(15.0, result.candles.first().volume, 1e-9)
    }

    @Test
    fun `broken rows are skipped instead of poisoning the series`() {
        val dirty = """
            timestamp,open,high,low,close,volume
            1704067200000,1.1000,1.1050,1.0990,1.1040,1200
            not-a-number,1.1,1.2,1.0,1.1,10
            1704068100000,1.1040,1.1080,1.1020,1.1070,1500
            1704069000000,1.1070,1.1000,1.1090,1.1010,900
            1704070000000,1.1070,1.1000,1.1090,1.1010,900
        """.trimIndent()
        val result = BacktestCsvReader.parse(dirty)
        // ردیف بدِ زمان + دو ردیف با high<low حذف می‌شوند.
        assertEquals(2, result.candles.size)
        assertEquals(3, result.skippedRows)
    }

    @Test
    fun `duplicate timestamps keep exactly one row`() {
        val dup = """
            timestamp,open,high,low,close,volume
            1704067200000,1.1000,1.1050,1.0990,1.1040,1200
            1704067200000,1.1000,1.1050,1.0990,1.1045,1200
            1704068100000,1.1040,1.1080,1.1020,1.1070,1500
        """.trimIndent()
        val result = BacktestCsvReader.parse(dup)
        assertEquals(2, result.candles.size)
        assertEquals(1, result.duplicateRows)
        // اولین ردیف برنده است (رفتار صریح و مستند).
        assertEquals(1.1040, result.candles.first().close, 1e-9)
    }

    @Test
    fun `flat weekend candles are dropped by default and kept on request`() {
        val flats = """
            timestamp,open,high,low,close,volume
            1704067200000,1.1000,1.1050,1.0990,1.1040,1200
            1704068100000,1.1040,1.1040,1.1040,1.1040,0
            1704069000000,1.1040,1.1080,1.1020,1.1070,1500
        """.trimIndent()
        val dropped = BacktestCsvReader.parse(flats, dropFlatBars = true)
        assertEquals(2, dropped.candles.size)
        assertEquals(1, dropped.flatRowsDropped)

        val kept = BacktestCsvReader.parse(flats, dropFlatBars = false)
        assertEquals(3, kept.candles.size)
        assertEquals(0, kept.flatRowsDropped)
    }

    @Test
    fun `unsupported header fails with a readable message`() {
        val error = runCatching { BacktestCsvReader.parse("foo,bar\n1,2") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("dukascopy-node"))
    }

    @Test
    fun `second based and iso timestamps are both understood`() {
        assertEquals(1_704_067_200_000L, BacktestCsvReader.parseTimestamp("1704067200"))
        assertEquals(1_704_067_200_000L, BacktestCsvReader.parseTimestamp("1704067200000"))
        assertEquals(1_704_067_200_000L, BacktestCsvReader.parseTimestamp("2024-01-01T00:00:00Z"))
        assertNull(BacktestCsvReader.parseTimestamp(""))
    }

    @Test
    fun `timezone offset shifts timestamps back to utc`() {
        val shifted = BacktestCsvReader.parse(dukascopyCsv, timezoneOffsetMinutes = 60)
        // فایل با UTC+1 ساخته شده؛ زمان واقعی UTC یک ساعت عقب‌تر است.
        assertEquals(1704067200000L - 3_600_000L, shifted.candles.first().time)
    }

    // ───────────────────────── سری و برش ─────────────────────────

    @Test
    fun `resample groups candles into higher timeframe buckets`() {
        val m5 = (0 until 24).map { i ->
            Candle(
                time = i * Interval.M5.millis,
                open = 100.0 + i,
                high = 101.0 + i,
                low = 99.0 + i,
                close = 100.5 + i,
                volume = 10.0,
            )
        }
        val h1 = BacktestSeries.resample(m5, Interval.H1)
        assertEquals(2, h1.size)
        assertEquals(m5.first().open, h1.first().open, 1e-9)
        assertEquals(m5.take(12).maxOf { it.high }, h1.first().high, 1e-9)
        assertEquals(m5.take(12).minOf { it.low }, h1.first().low, 1e-9)
        assertEquals(m5[11].close, h1.first().close, 1e-9)
        assertEquals(120.0, h1.first().volume, 1e-9)
    }

    @Test
    fun `closedUpTo never exposes a still forming bar`() {
        val bars = (0 until 10).map { i ->
            Candle(time = i * Interval.M15.millis, open = 1.0, high = 2.0, low = 0.5, close = 1.5, volume = 1.0)
        }
        val size = Interval.M15.millis
        // درست در لحظهٔ بسته شدن کندل سوم: سه کندل باید دیده شوند.
        val atClose = BacktestSeries.closedUpTo(bars, size, 3 * size)
        assertEquals(3, atClose.size)
        // یک میلی‌ثانیه قبل از بسته شدن کندل چهارم: هنوز سه کندل.
        val justBefore = BacktestSeries.closedUpTo(bars, size, 4 * size - 1)
        assertEquals(3, justBefore.size)
        // و در لحظهٔ بسته شدن کندل چهارم: چهار کندل.
        assertEquals(4, BacktestSeries.closedUpTo(bars, size, 4 * size).size)
    }

    // ───────────────────────── اجرای بک‌تست ─────────────────────────

    private fun trendingCandles(count: Int, stepPercent: Double = 0.15): List<Candle> {
        var price = 100.0
        return (0 until count).map { i ->
            val open = price
            val drift = price * stepPercent / 100.0
            val wave = if (i % 7 == 3) -drift * 1.8 else drift
            val close = (open + wave)
            price = close
            Candle(
                time = i * Interval.M15.millis,
                open = open,
                high = maxOf(open, close) * 1.0006,
                low = minOf(open, close) * 0.9994,
                close = close,
                volume = 100.0 + i,
            )
        }
    }

    @Test
    fun `backtest runs on a series and every trade is internally consistent`() {
        val candles = trendingCandles(600)
        val config = BacktestReplayEngine.Config(
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
            symbol = "XAU/USD",
            baseInterval = Interval.M15,
            triggerInterval = Interval.M15,
            warmupBars = 80,
            captureEntryReports = true,
        )
        val result = BacktestReplayEngine.run(candles, config)

        assertTrue("بک‌تست باید حداقل یک معامله بسازد", result.trades.isNotEmpty())
        assertTrue(result.steps > 400)
        result.trades.forEach { trade ->
            assertTrue("ورود باید مثبت باشد", trade.entryPrice > 0.0)
            assertTrue("حد ضرر باید مثبت باشد", trade.stopLoss > 0.0)
            assertTrue("حد سود باید مثبت باشد", trade.takeProfit > 0.0)
            assertTrue("زمان خروج نباید قبل از ورود باشد", (trade.exitTime ?: Long.MAX_VALUE) >= trade.entryTime)
            assertTrue("R باید محدود و معقول باشد", trade.rMultiple.isFinite() && kotlin.math.abs(trade.rMultiple) < 20.0)
            assertFalse("هر معامله باید دلیل خروج داشته باشد", trade.exitReason == BacktestReplayEngine.ExitReason.OPEN)
        }
        // گزارش‌های ورود برای آموزش عصبی ثبت شده‌اند.
        assertTrue(result.entryReports.isNotEmpty())
        assertNotNull(result.totals)
    }

    @Test
    fun `backtest is reproducible for the same input`() {
        val candles = trendingCandles(500)
        val config = BacktestReplayEngine.Config(warmupBars = 80)
        val a = BacktestReplayEngine.run(candles, config)
        val b = BacktestReplayEngine.run(candles, config)
        assertEquals(a.trades.size, b.trades.size)
        assertEquals(a.totals?.netPnlUsd ?: 0.0, b.totals?.netPnlUsd ?: 0.0, 1e-9)
    }

    @Test
    fun `no trade is opened before the warmup window ends`() {
        val candles = trendingCandles(400)
        val config = BacktestReplayEngine.Config(warmupBars = 200)
        val result = BacktestReplayEngine.run(candles, config)
        val firstEvaluableTime = candles[200].time
        result.trades.forEach { trade ->
            assertTrue(
                "ورود قبل از پایان گرم‌شدن رخ داده: ${trade.entryTime} < $firstEvaluableTime",
                trade.entryTime >= firstEvaluableTime,
            )
        }
    }

    @Test
    fun `too few candles fails with an explanatory message instead of a silent zero`() {
        val error = runCatching {
            BacktestReplayEngine.run(trendingCandles(20), BacktestReplayEngine.Config(warmupBars = 80))
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("کندل کافی نیست"))
    }

    @Test
    fun `cutting the series at the cursor changes nothing in the past`() {
        val candles = trendingCandles(600)
        val full = BacktestReplayEngine.run(
            candles,
            BacktestReplayEngine.Config(warmupBars = 120, maxBars = 400),
        )
        val cut = BacktestReplayEngine.run(
            candles.take(401),
            BacktestReplayEngine.Config(warmupBars = 120, maxBars = 400),
        )
        // همان کندل‌های گذشته باید همان معامله‌ها را بدهند: هیچ نگاهی به آینده وجود ندارد.
        assertEquals(full.trades.size, cut.trades.size)
        full.trades.zip(cut.trades).forEach { (a, b) ->
            assertEquals(a.entryTime, b.entryTime)
            assertEquals(a.entryPrice, b.entryPrice, 1e-9)
        }
    }

    @Test
    fun `same bar stop and target is resolved as a loss by default`() {
        val config = BacktestReplayEngine.Config()
        assertFalse(config.optimisticSameBarFill)
        val pessimistic = config.copy(optimisticSameBarFill = false)
        val optimistic = config.copy(optimisticSameBarFill = true)
        assertFalse(pessimistic.optimisticSameBarFill)
        assertTrue(optimistic.optimisticSameBarFill)
    }
}
