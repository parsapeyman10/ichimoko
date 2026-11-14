package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * آزمون‌های «فایل سنگین».
 *
 * سه چیزی که این فایل تضمین می‌کند و پیش از این هیچ‌کدام آزمون نداشت:
 *  ۱. بریدن پنجرهٔ دید موتور، **نتیجه را عوض نمی‌کند** (فقط سرعت را).
 *  ۲. ورود فایل بزرگ‌تر از سقف، پیام راهنما می‌دهد نه OutOfMemory.
 *  ۳. برآورد زمان پیش از اجرا واقع‌بینانه و یکنواست.
 *
 * هیچ‌کدام به فایل واقعی یا شبکه دست نمی‌زنند.
 */
class HeavyCsvGuardTest {

    // ────────────────────── هندسهٔ کندل مصنوعی ──────────────────────

    /**
     * سری کندل مصنوعی با روند و پولبک.
     *
     * مهم: این سری باید واقعاً **معامله** بسازد. اگر صفر معامله بدهد، مقایسهٔ «پنجرهٔ بریده
     * با کل تاریخچه یکی است» بی‌معنا می‌شود چون هر دو صفر هستند. پس خود آزمون تعداد
     * معامله‌ها را هم بررسی می‌کند.
     */
    private fun series(n: Int, base: Double = 2000.0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var price = base
        val stepMs = 15 * 60_000L
        for (i in 0 until n) {
            // روند صعودیِ پله‌ای + نوسان کوتاه: هم کراس می‌سازد هم حد ضرر می‌گیرد.
            val trend = i * 0.06
            val wave = 9.0 * kotlin.math.sin(i / 9.0) + 4.0 * kotlin.math.sin(i / 2.3)
            val open = price
            val close = base + trend + wave
            val high = maxOf(open, close) + 1.6
            val low = minOf(open, close) - 1.6
            out.add(Candle(1_600_000_000_000L + i * stepMs, open, high, low, close, 100.0 + i % 17, true))
            price = close
        }
        return out
    }

    private fun config(all: Boolean = false) = BacktestReplayEngine.Config(
        strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        symbol = "XAU/USD",
        warmupBars = 100,
        runAllStrategies = all,
        includeHiddenCrossLayer = true,
    )

    private fun fingerprint(bars: Int, windowBars: Int, all: Boolean = false): String {
        val r = BacktestReplayEngine.runWithWindow(series(bars), config(all), windowBars)
        return buildString {
            append("trades=").append(r.trades.size)
            append(" net=").append("%.6f".format(r.totals?.netPnlUsd ?: 0.0))
            append(" pf=").append("%.6f".format(r.totals?.profitFactor ?: 0.0))
            // فهرست معامله‌ها هم باید یکی باشد، نه فقط جمع کل.
            r.trades.forEach { t ->
                append(" | ").append(t.entryTime).append(':')
                append("%.4f".format(t.entryPrice)).append(':')
                append("%.4f".format(t.netPnlUsd))
            }
        }
    }

    // ────────────────────── ۱. بریدن پنجره نتیجه را عوض نمی‌کند ──────────────────────

    @Test
    fun `window cut keeps single strategy result identical`() {
        for (bars in listOf(900, 2_000)) {
            val full = fingerprint(bars, Int.MAX_VALUE)
            val cut = fingerprint(bars, BacktestSeries.ENGINE_WINDOW_BARS)
            assertEquals("bars=$bars — پنجرهٔ بریده باید همان نتیجهٔ کل تاریخچه را بدهد", full, cut)
        }
    }

    @Test
    fun `test series actually produces trades so the comparison is meaningful`() {
        // اگر این آزمون بیفتد، آزمون‌های «یکسان بودن» بالا بی‌معنا شده‌اند (۰ = ۰).
        val r = BacktestReplayEngine.runWithWindow(series(2_000), config(all = true), BacktestSeries.ENGINE_WINDOW_BARS)
        assertTrue("سری آزمون باید حداقل یک معامله بسازد، وگرنه مقایسه بی‌اثر است", r.trades.isNotEmpty())
    }

    @Test
    fun `window cut keeps all strategies result identical`() {
        for (bars in listOf(900, 2_000)) {
            val full = fingerprint(bars, Int.MAX_VALUE, all = true)
            val cut = fingerprint(bars, BacktestSeries.ENGINE_WINDOW_BARS, all = true)
            assertEquals("bars=$bars — حالت همهٔ استراتژی‌ها هم باید یکسان بماند", full, cut)
        }
    }

    @Test
    fun `window constant covers what the engine actually reads`() {
        // موتور در بک‌تست ۲۰۰ کندل آخر را نگه می‌دارد و آنالوگ ابر مخفی ۲۶۰ عقب را می‌بیند؛
        // پنجره باید از هر دو بزرگ‌تر باشد، وگرنه نتیجه عوض می‌شود.
        assertTrue(
            "پنجرهٔ موتور (${BacktestSeries.ENGINE_WINDOW_BARS}) باید ≥ ۲۶۰ باشد",
            BacktestSeries.ENGINE_WINDOW_BARS >= IchimokuStrategyEngine.SNAPSHOT_KEEP_BARS,
        )
        assertTrue(
            "پنجره باید ≥ ۲۰۰ (SNAPSHOT_KEEP_BARS_SHORT) باشد",
            BacktestSeries.ENGINE_WINDOW_BARS >= IchimokuStrategyEngine.SNAPSHOT_KEEP_BARS_SHORT,
        )
    }

    // ────────────────────── ۲. برآورد زمان پیش از اجرا ──────────────────────

    @Test
    fun `estimate grows with bars and respects the bar cap`() {
        val small = BacktestReplayEngine.estimateMs(1_000, runAllStrategies = false)
        val big = BacktestReplayEngine.estimateMs(10_000, runAllStrategies = false)
        assertTrue("برآورد باید با تعداد کندل رشد کند", big > small)

        val capped = BacktestReplayEngine.estimateMs(50_000, runAllStrategies = false, maxBars = 2_000)
        assertEquals(
            "با سقف کندل، برآورد باید روی همان سقف حساب شود",
            BacktestReplayEngine.estimateMs(2_000, runAllStrategies = false),
            capped,
        )
    }

    @Test
    fun `all strategies estimate is heavier than single`() {
        val one = BacktestReplayEngine.estimateMs(5_000, runAllStrategies = false)
        val all = BacktestReplayEngine.estimateMs(5_000, runAllStrategies = true)
        assertTrue("همهٔ استراتژی‌ها باید سنگین‌تر برآورد شود", all > one * 2)
    }

    @Test
    fun `estimate sentence is persian and mentions the cap when it applies`() {
        val s = BacktestReplayEngine.estimateSentenceFa(40_000, runAllStrategies = true, maxBars = 5_000)
        assertTrue("باید به سقف کندل اشاره کند", s.contains("5") || s.contains("٥"))
        assertTrue("باید زمان را بگوید", s.contains("دقیقه") || s.contains("ثانیه"))
        assertTrue("پیش از این تخمین زمان فقط بعد از ۳٪ پیشرفت می‌آمد", s.isNotBlank())
    }

    @Test
    fun `estimate of a heavy run exceeds the heavy threshold`() {
        // ۲۰۰ هزار کندل در حالت همهٔ استراتژی‌ها باید «سنگین» طبقه‌بندی شود، وگرنه
        // هشدار پیش از اجرا هیچ‌وقت به کاربر نشان داده نمی‌شود.
        val heavyMs = BacktestReplayEngine.estimateMs(200_000, runAllStrategies = true)
        assertTrue(
            "برآورد باید از آستانهٔ سنگین (۵ دقیقه) بگذرد ولی  heavyMs=$heavyMs",
            heavyMs > 300_000,
        )
    }

    // ────────────────────── ۳. ورود فایل سنگین: پیام، نه کرش ──────────────────────

    @Test
    fun `heavy file size cap is documented and finite`() {
        assertTrue("سقف باید مثبت باشد", BacktestCsvLimits.MAX_CSV_BYTES > 0)
        assertEquals(
            "سقف بایت و سقف کاراکتر باید یکی باشند تا دو عدد متناقض به کاربر نشان داده نشود",
            BacktestCsvLimits.MAX_CSV_CHARS.toLong(),
            BacktestCsvLimits.MAX_CSV_BYTES,
        )
        // سقف باید از «یک فایل واقعیِ ۱۰۰ هزار کندلی» بزرگ‌تر باشد، وگرنه فایل معمولی رد می‌شود.
        val typical100kBytes = 100_000L * BacktestCsvLimits.APPROX_BYTES_PER_BAR
        assertTrue("سقف باید فایل ۱۰۰ هزار کندلی را قبول کند", BacktestCsvLimits.MAX_CSV_BYTES > typical100kBytes)
    }

    @Test
    fun `heavy file message explains why and gives a way out`() {
        val msg = BacktestCsvLimits.heavyFileMessage(60_000_000)
        assertTrue("باید اندازهٔ فایل را بگوید", msg.contains("60"))
        assertTrue("باید بگوید وارد نشد", msg.contains("وارد نشد"))
        assertTrue("باید راه‌حل تایم‌فریم بدهد", msg.contains("تایم‌فریم"))
        assertTrue("باید راه‌حل تقسیم فایل بدهد", msg.contains("split"))
        assertTrue(
            "پیام خالی یا عمومی نباشد",
            msg.length > 120,
        )
        // پیام «حین خواندن» هم باید صریح باشد که چیزی ذخیره نشده.
        val mid = BacktestCsvLimits.oversizedWhileReadingMessage()
        assertTrue("باید بگوید چیزی ذخیره نشد", mid.contains("ذخیره نشد"))
    }

    // ────────────────────── ۴. پیمایش یک‌گذرهٔ CSV ──────────────────────

    @Test
    fun `csv parsing does not depend on materialising all lines`() {
        // فایل بزرگ با سطرهای خالی/فاصله‌دار: شمارش کل سطرها باید درست بماند
        // (پیمایش یک‌گذره نباید شمارش `totalRows` را خراب کند).
        val sb = StringBuilder("timestamp,open,high,low,close,volume\n")
        val times = (0 until 2_000).map { 1_700_000_000_000L + it * 15 * 60_000L }
        times.forEachIndexed { i, t ->
            sb.append(t).append(',').append(1.10 + i * 0.0001).append(',')
                .append(1.11 + i * 0.0001).append(',').append(1.09 + i * 0.0001).append(',')
                .append(1.105 + i * 0.0001).append(",1000\n")
            if (i % 500 == 0) sb.append("   \n") // سطر خالیِ عمدی
        }
        val parsed = BacktestCsvReader.parse(sb.toString(), "eurusd-m15-bid-2024-01-01-2025-01-01.csv")
        assertEquals(2_000, parsed.candles.size)
        assertEquals("سطرهای خالی نباید در شمارش ردیف‌ها بیایند", 2_000, parsed.totalRows)
        assertEquals(0, parsed.duplicateRows)
        assertEquals(0, parsed.skippedRows)
    }

    @Test
    fun `duplicate timestamps are still counted after single pass`() {
        val csv = """
            timestamp,open,high,low,close,volume
            1704067200000,1.1,1.2,1.0,1.15,100
            1704067200000,1.1,1.3,1.0,1.16,100
            1704068100000,1.15,1.2,1.1,1.18,100
        """.trimIndent()
        val parsed = BacktestCsvReader.parse(csv)
        assertEquals("timestamp تکراری باید یک‌بار شمرده شود", 2, parsed.candles.size)
        assertEquals(1, parsed.duplicateRows)
    }
}
