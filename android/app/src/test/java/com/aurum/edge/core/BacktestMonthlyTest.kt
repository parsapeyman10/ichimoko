package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * آزمون‌های اجرای «ماه‌به‌ماه».
 *
 * قرارداد این فایل سه چیز است:
 *  ۱. ماه‌به‌ماه‌کردن **نتیجه را عوض نمی‌کند** (اختلاف کم، نه دو داستان متفاوت).
 *  ۲. هر ماه جدا گزارش می‌شود و ماه‌ها با زمان درست جدا شده‌اند.
 *  ۳. مرز ماه با «گرم‌کردنِ حمل‌شده» درست کار می‌کند؛ وگرنه کندل اول هر ماه کور می‌ماند.
 */
class BacktestMonthlyTest {

    /**
     * سری مصنوعی چندماهه با روند و نوسان، روی شبکهٔ ۱۵ دقیقه (بدون شکاف).
     *
     * ۲٬۸۸۰ کندل = ۳۰ روز × ۹۶ کندل ۱۵دقیقه؛ با ۹۰۰ کندل (۹ روز) هر سه ماه داخل یک ماه
     * تقویمی می‌افتاد و آزمون «تعداد ماه» بی‌معنا می‌شد.
     */
    private fun series(months: Int = 3, barsPerMonth: Int = 2_880): List<Candle> {
        val stepMs = 15 * 60_000L
        val start = 1_735_689_600_000L // 2025-01-01T00:00:00Z
        val n = months * barsPerMonth
        val out = ArrayList<Candle>(n)
        var price = 2_300.0
        for (i in 0 until n) {
            val trend = i * 0.03
            val wave = 9.0 * sin(i / 11.0) + 4.0 * sin(i / 3.7)
            val open = price
            val close = 2_300.0 + trend + wave
            out += Candle(
                time = start + i * stepMs,
                open = open,
                high = maxOf(open, close) + 2.2,
                low = minOf(open, close) - 2.2,
                close = close,
                volume = 500.0 + (i % 23) * 10.0,
                closed = true,
            )
            price = close
        }
        return out
    }

    private fun config() = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        symbol = "XAU/USD",
        warmupBars = 60,
    )

    @Test
    fun `monthly run splits by calendar month and reports every month`() {
        val periodic = BacktestReplayEngine.runPeriodic(series(months = 3), config())
        assertEquals("سه ماه باید گزارش شود", 3, periodic.months)
        assertEquals(listOf("2025-01", "2025-02", "2025-03"), periodic.periods.map { it.label })
        assertTrue("هر ماه باید کندل داشته باشد", periodic.periods.all { it.candles > 0 })
        assertTrue("گزارش ماهانه نباید خالی باشد", periodic.summaryRows.size == 3)
        assertTrue(periodic.summaryRows.first().startsWith("2025-01"))
    }

    @Test
    fun `monthly run keeps the same edge as a single pass`() {
        val candles = series(months = 3)
        val whole = BacktestReplayEngine.run(candles, config()).totals
        val monthly = BacktestReplayEngine.runPeriodic(candles, config()).totals

        assertTrue("در هر دو حالت باید معامله ساخته شود", (whole?.trades ?: 0) > 0 && (monthly?.trades ?: 0) > 0)

        // مرز ماه چند معامله را جابه‌جا می‌کند؛ کمتر از ۲۰٪ اختلاف قابل قبول است و
        // بیشتر از آن یعنی «گرم‌کردنِ حمل‌شده» کار نمی‌کند.
        val tradeGap = abs((monthly!!.trades - whole!!.trades).toDouble()) / whole.trades
        assertTrue("اختلاف شمار معامله بین ماه‌به‌ماه و یک‌جا باید کم باشد ($tradeGap)", tradeGap <= 0.20)

        val rGap = abs((monthly.expectancyR) - (whole.expectancyR))
        assertTrue("انتظار R نباید با ماه‌به‌ماه‌کردن بپرد ($rGap)", rGap <= 0.20)
    }

    @Test
    fun `monthly result converts to a normal result with monthly lines`() {
        val config = config()
        val periodic = BacktestReplayEngine.runPeriodic(series(months = 2), config)
        val result = with(BacktestReplayEngine) { periodic.toResult(config) }
        assertEquals(periodic.trades.size, result.trades.size)
        assertEquals(periodic.periods.size, result.periodSummariesFa.size)
        assertTrue(result.stats.isNotEmpty())
        assertTrue("منحنی سرمایه باید ساخته شود", result.equityCurve.isNotEmpty())
    }

    @Test
    fun `runAuto splits by calendar month and never by bar count`() {
        val light = series(months = 1, barsPerMonth = 500)
        val heavy = series(months = 3)

        // دادهٔ درون‌یک‌ماهه: یک اجرای کامل، بدون تکه‌تکه‌کردن — «اگر کوچک‌تر از یک ماه بود
        // همان کافی است و کوچک‌ترش نکن».
        assertEquals(1, BacktestReplayEngine.monthCount(light))
        assertTrue(!BacktestReplayEngine.usesMonthlyMode(light, config()))
        assertTrue(
            "دادهٔ یک‌ماهه نباید خط ماهانه بسازد",
            BacktestReplayEngine.runAuto(light, config()).periodSummariesFa.isEmpty(),
        )

        // دادهٔ چندماهه: ماه‌به‌ماه، هر ماه یک خط گزارش.
        assertEquals(3, BacktestReplayEngine.monthCount(heavy))
        assertTrue(BacktestReplayEngine.usesMonthlyMode(heavy, config()))
        assertEquals(3, BacktestReplayEngine.runAuto(heavy, config()).periodSummariesFa.size)
    }
}
