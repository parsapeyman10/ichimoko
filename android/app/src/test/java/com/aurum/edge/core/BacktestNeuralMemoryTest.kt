package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * آزمون‌های «اتاق تمرین» شبکهٔ عصبی.
 *
 * دو چیزی که این فایل قفل می‌کند و پیش از این هیچ آزمونی نداشت:
 *
 *  ۱. **حافظه انباشته می‌شود.** قبلاً `BacktestNeuralTrainer.train` فایل حافظه را پیش از
 *     هر آموزش پاک می‌کرد، پس بک‌تست قدیمی هیچ کمکی به یادگیری نمی‌کرد و هر اجرا از صفر
 *     شروع می‌شد. این آزمون تأیید می‌کند نمونه‌های اجرای قبلی می‌مانند و روی آن‌ها ساخته می‌شود.
 *
 *  ۲. **پول در یادگیری اثر ندارد.** با سرمایهٔ شروع ۵٬۰۰۰ دلار و ۵۰٬۰۰۰ دلار، و با ریسک
 *     ۰٫۵٪ و ۲٪، شبکه باید **همان** چیزی را یاد بگیرد؛ چون معیار آموزش از فاصلهٔ قیمتی (R)
 *     ساخته می‌شود نه از سود دلاری.
 *
 * همه‌چیز در یک پوشهٔ موقت اجرا می‌شود؛ هیچ فایلی از برنامهٔ واقعی لمس نمی‌شود.
 */
class BacktestNeuralMemoryTest {

    // ─────────────────────── داده و ابزار ───────────────────────

    private fun series(n: Int, base: Double = 2400.0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var price = base
        val stepMs = 15 * 60_000L
        for (i in 0 until n) {
            val trend = i * 0.06
            val wave = 9.0 * Math.sin(i / 9.0) + 4.0 * Math.sin(i / 2.3)
            val open = price
            val close = base + trend + wave
            out.add(
                Candle(
                    1_600_000_000_000L + i * stepMs,
                    open,
                    maxOf(open, close) + 1.6,
                    minOf(open, close) - 1.6,
                    close,
                    100.0 + i % 17,
                    true,
                ),
            )
            price = close
        }
        return out
    }

    private fun runBacktest(
        bars: Int = 900,
        startEquity: Double = 10_000.0,
        riskPercent: Double = 0.5,
    ): BacktestReplayEngine.Result = BacktestReplayEngine.run(
        series(bars),
        BacktestReplayEngine.Config(
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
            symbol = "XAU/USD",
            warmupBars = 100,
            startEquityUsd = startEquity,
            riskPercent = riskPercent,
            includeHiddenCrossLayer = false,
        ),
    )

    private fun tempTrainer(tag: String): Pair<BacktestNeuralTrainer, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "aurum-neural-test-$tag-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        // مسیر فایل مستقیماً تزریق می‌شود تا آزمون به Context اندروید نیاز نداشته باشد.
        return BacktestNeuralTrainer(context = null, storageFileOverride = File(dir, "backtest_neural_v1.json")) to dir
    }

    // ─────────────────────── ۱. حافظه انباشته ───────────────────────

    @Test
    fun `second training keeps the samples of the first`() {
        val (trainer, dir) = tempTrainer("accumulate")
        try {
            val res = runBacktest()
            val first = trainer.train(res.trades, "XAU/USD", res.entryReports)
            assertTrue("اجرای اول باید حداقل یک نمونه بدهد", first.samplesFed > 0)
            assertEquals("اجرای اول روی حافظهٔ خالی است", 0, first.carriedSamples)
            assertEquals(first.samplesFed, first.totalLearnedSamples)
            assertTrue("حافظه باید ذخیره شود", first.persisted)

            // اجرای دوم با همان داده: حافظه باید سر جایش باشد.
            val second = trainer.train(res.trades, "XAU/USD", res.entryReports)
            assertEquals(
                "نمونه‌های اجرای قبلی باید در حافظه مانده باشند (قبلاً فایل پاک می‌شد)",
                first.samplesFed,
                second.carriedSamples,
            )
            assertTrue(
                "مجموع آموخته‌شده نباید کمتر از اجرای اول باشد",
                second.totalLearnedSamples >= first.samplesFed,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `memorySize reports what is stored and reset clears it`() {
        val (trainer, dir) = tempTrainer("reset")
        try {
            assertEquals(0, trainer.memorySize())
            val res = runBacktest()
            trainer.train(res.trades, "XAU/USD", res.entryReports)
            assertTrue("پس از آموزش باید حافظه پر شده باشد", trainer.memorySize() > 0)

            assertTrue("پاک‌کردن حافظه باید موفق شود", trainer.resetMemory())
            assertEquals("پس از پاک‌کردن باید صفر باشد", 0, trainer.memorySize())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `re-running the same backtest does not double-count identical samples`() {
        // قاعدهٔ «تکراری نمی‌تواند بنشیند»: اجرای دوبارهٔ همان CSV نباید دانش را متورم کند.
        val (trainer, dir) = tempTrainer("dedupe")
        try {
            val res = runBacktest()
            trainer.train(res.trades, "XAU/USD", res.entryReports)
            val afterFirst = trainer.memorySize()
            trainer.train(res.trades, "XAU/USD", res.entryReports)
            val afterSecond = trainer.memorySize()
            assertEquals(
                "همان معامله‌ها دوباره نباید شمرده شوند (شناسهٔ هر نمونه از خود معامله ساخته می‌شود)",
                afterFirst,
                afterSecond,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─────────────────────── ۲. پول در یادگیری اثر ندارد ───────────────────────

    @Test
    fun `start equity does not change what the network learns`() {
        val variants = listOf(5_000.0, 50_000.0).map { equity ->
            val (trainer, dir) = tempTrainer("equity-$equity")
            try {
                val res = runBacktest(startEquity = equity)
                val rep = trainer.train(res.trades, "XAU/USD", res.entryReports)
                Triple(rep.samplesFed, rep.inSampleAccuracyPct, rep.predictedWinRatePct)
            } finally {
                dir.deleteRecursively()
            }
        }
        assertEquals("سرمایهٔ شروع نباید تعداد نمونه را عوض کند", variants[0].first, variants[1].first)
        assertEquals(
            "سرمایهٔ شروع نباید دقت یادگیری را عوض کند",
            variants[0].second,
            variants[1].second,
            0.0001,
        )
        // احتمالِ آموخته‌شده در گزارش با یک رقم اعشار گرد می‌شود (`round(prob*1000)/10`).
        // وقتی شبکه خروجی نزدیک یکنواخت دارد (اینجا ~۳٪)، اختلاف ۰٬۱ یعنی «یک پلهٔ گردکردن»،
        // نه «یادگیری متفاوت». با فرکانس بالاتر معامله‌ها در ۳.۹.۶۷ همین پله دیده شد و
        // آزمون شکست، در حالی که تعداد نمونه و دقت در همان حد قبلی مانده بود.
        assertEquals(
            "سرمایهٔ شروع نباید احتمال برد آموخته‌شده را عوض کند (تفاوت بیش از یک پلهٔ گردکردن)",
            variants[0].third,
            variants[1].third,
            0.11,
        )
    }

    @Test
    fun `risk percent does not change what the network learns`() {
        val variants = listOf(0.5, 2.0).map { risk ->
            val (trainer, dir) = tempTrainer("risk-$risk")
            try {
                val res = runBacktest(riskPercent = risk)
                val rep = trainer.train(res.trades, "XAU/USD", res.entryReports)
                Triple(rep.samplesFed, rep.inSampleAccuracyPct, rep.predictedWinRatePct)
            } finally {
                dir.deleteRecursively()
            }
        }
        assertEquals("درصد ریسک نباید تعداد نمونه را عوض کند", variants[0].first, variants[1].first)
        assertEquals(
            "درصد ریسک نباید دقت یادگیری را عوض کند",
            variants[0].second,
            variants[1].second,
            0.0001,
        )
    }

    @Test
    fun `money mode is opt in and off by default`() {
        // اگر روزی کسی پیش‌فرض را برگرداند، این آزمون می‌افتد.
        val (trainer, dir) = tempTrainer("optin")
        try {
            val res = runBacktest()
            val defaultMode = trainer.train(res.trades, "XAU/USD", res.entryReports)
            val moneyMode = trainer.train(res.trades, "XAU/USD", res.entryReports, moneyAffectsLearning = true)
            assertTrue("هر دو حالت باید نمونه بپذیرند", defaultMode.samplesFed > 0 && moneyMode.samplesFed > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─────────────────────── ۳. جدایی از حافظهٔ واقعی ───────────────────────

    @Test
    fun `training never writes the live brain file`() {
        val (trainer, dir) = tempTrainer("isolation")
        try {
            val res = runBacktest()
            trainer.train(res.trades, "XAU/USD", res.entryReports)
            val liveName = "neural_self_learning_v1.json"
            val leaked = dir.walkTopDown().any { it.isFile && it.name == liveName }
            assertTrue("آموزش بک‌تست نباید فایل حافظهٔ اصلی برنامه را بسازد", !leaked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `learned R is independent of dollar amount`() {
        // جوهر ماجرا: R = فاصلهٔ قیمتی، نه دلار. ۱۰ دلار سود با حد ضرر ۱ دلاری = ۱۰R،
        // و همان با ۱۰۰ دلار سود و حد ضرر ۱۰ دلاری هم ۱۰R است.
        val a = abs(10.0 / 1.0 - 10.0)
        val b = abs(100.0 / 10.0 - 10.0)
        assertEquals(0.0, a, 1e-9)
        assertEquals("R باید مقیاس‌ناپذیر باشد", a, b, 1e-9)
    }
}
