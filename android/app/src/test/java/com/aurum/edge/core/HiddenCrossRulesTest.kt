package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * آزمون‌های «هیدن کراس» — همان پنج قاعده‌ای که در آموزش اصلی هست و نه بیشتر.
 *
 * پیش از این، `HIDDEN_CROSS` **هیچ معامله‌ای نمی‌ساخت**: ماشه‌اش به ده شرط وابسته بود
 * (آنالوگ آماری، تازگی کراس، بدنهٔ کندل، هم‌سویی تایم‌فریم، شیب، …) که هیچ‌کدام در آموزش
 * نبودند و روی هم عملاً هیچ کندلی از آن‌ها رد نمی‌شد. این فایل سه چیز را قفل می‌کند:
 *
 *  ۱. هر پنج قاعده مستقل کار می‌کند و با دادهٔ سالم همه پاس می‌شوند.
 *  ۲. شکستن هر قاعده، همان قاعده را رد می‌کند (نه بیشتر، نه کمتر).
 *  ۳. استراتژی روی دادهٔ واقع‌نما **واقعاً معامله می‌سازد** — تا دوباره به «۰ معامله» برنگردد.
 */
class HiddenCrossRulesTest {

    // ─────────────────────── ساخت هندسهٔ مصنوعی ───────────────────────

    /**
     * یک `IchimokuBarSnapshot` با کنترل کامل روی فیلدهای هیدن کراس.
     *
     * بقیهٔ فیلدها مقدار پیش‌فرض می‌گیرند؛ چون قواعد هیدن کراس فقط به نوار ابر مخفی،
     * کراس برابری و تاج نگاه می‌کنند — و همین «نگاه‌نکردن به بقیه» چیزی است که آزمون
     * باید تضمین کند.
     */
    private fun snapshot(
        close: Double,
        tenkan: Double,
        kijun: Double,
        thicknessAtr: Double,
        equalCrossLevel: Double,
        equalCrossBars: Int,
        equalCrossBarsSinceEnd: Int,
        crownLevel: Double,
        chikouFree: Boolean = true,
    ): IchimokuBarSnapshot = IchimokuBarSnapshot(
        time = 1_700_000_000_000L,
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        tenkan = tenkan,
        kijun = kijun,
        prevTenkan = tenkan,
        prevKijun = kijun,
        currentSpanA = close,
        currentSpanB = close - 2.0,
        futureSpanA = close,
        futureSpanB = close - 2.0,
        prevFutureSpanA = close,
        prevFutureSpanB = close - 2.0,
        atr14 = 10.0,
        chikouBullishOpenSpace = chikouFree,
        chikouBearishOpenSpace = chikouFree,
        chikouResistancePeak = close + 8.0,
        chikouSupportTrough = close - 8.0,
        isCurrentSpanBFlat = false,
        isKijunFlat = false,
        isTenkanFlat = false,
        flatEquilibriumLevels = emptyList(),
        hiddenCloudTop = maxOf(tenkan, kijun),
        hiddenCloudBottom = minOf(tenkan, kijun),
        hiddenCloudThicknessAtr = thicknessAtr,
        equalCrossLevel = equalCrossLevel,
        equalCrossBars = equalCrossBars,
        equalCrossBarsSinceEnd = equalCrossBarsSinceEnd,
        crownLevel = crownLevel,
    )

    /**
     * هندسهٔ سالم «بازگشت به تاج»: نوار باریک، کراس بالغ، قیمت روی سطح تاج و رد شده.
     *
     * ATR در snapshot برابر ۱۰ است، پس سقف لمس تاج = ۰٫۳۵ × ۱۰ = ۳٫۵ قیمت. تاج ۲۴۰۹ و
     * کلوز ۲۴۱۰ → فاصله ۱ < ۳٫۵ و کلوز بالای تاج → «رد شدن» برای خرید برقرار است.
     */
    private fun healthyLong() = snapshot(
        close = 2410.0,
        tenkan = 2408.0,
        kijun = 2404.0, // تنکان بالای کیجون → هم‌سو با خرید · میانهٔ نوار ۲۴۰۶ < کلوز
        thicknessAtr = 0.30, // باریک (سقف ۰٫۵۵)
        equalCrossLevel = 2405.0,
        equalCrossBars = 5, // ≥ ۳
        equalCrossBarsSinceEnd = 12, // بالغ (≥ ۵) و در عمر تاج
        crownLevel = 2409.0, // ۱ واحد زیر کلوز = لمس و رد شدن
    )

    private fun evaluate(
        cloud: IchimokuBarSnapshot,
        long: Boolean = true,
        tenkanAboveKijun: Boolean = true,
        chikouFree: Boolean = true,
    ) = HiddenCrossEngine.spec(cloud, long, tenkanAboveKijun, chikouFree)

    // ─────────────────────── ۱. دادهٔ سالم: همه پاس ───────────────────────

    @Test
    fun `healthy geometry passes all five rules`() {
        val spec = evaluate(healthyLong())
        assertEquals("هیدن کراس باید دقیقاً ۵ قاعده داشته باشد", 5, spec.conditions.size)
        assertTrue(
            "با هندسهٔ سالم هر پنج قاعده باید پاس شوند؛ ردشده‌ها: " +
                spec.conditions.filterNot { it.passed }.map { it.titleFa },
            spec.allPassed,
        )
    }

    @Test
    fun `rules are numbered one to five in order`() {
        assertEquals(listOf(1, 2, 3, 4, 5), evaluate(healthyLong()).conditions.map { it.index })
    }

    // ─────────────────────── ۲. هر قاعده جدا ───────────────────────

    @Test
    fun `rule 1 rejects a thick hidden cloud`() {
        val thick = healthyLong().copy(hiddenCloudThicknessAtr = 1.20)
        val spec = evaluate(thick)
        assertFalse("ابر مخفی ضخیم باید رد شود", spec.conditions[0].passed)
        // بقیه نباید تاوانش را بدهند (قاعده‌ها مستقل‌اند).
        assertTrue("قاعده ۲ نباید به‌خاطر ضخامت رد شود", spec.conditions[1].passed)
    }

    @Test
    fun `rule 2 rejects price on the wrong side of the band and mismatched tenkan order`() {
        // «سمت روند» میانهٔ نوار است؛ قیمت زیر میانه (۲۴۰۶) با معاملهٔ خرید نمی‌خواند.
        val wrongSide = healthyLong().copy(close = 2405.0)
        assertFalse("قیمت زیر میانهٔ نوار نباید برای خرید پاس شود", evaluate(wrongSide).conditions[1].passed)

        // داخل نوار بودن در لحظهٔ بازگشت مجاز است (در عکس‌ها همین‌طور است) — شرط نیست.
        val insideBand = healthyLong().copy(close = 2407.0)
        assertTrue(
            "قیمت داخل نوار ولی در سمت روند باید پاس شود (بازگشت به تاج)",
            evaluate(insideBand).conditions[1].passed,
        )

        // ترتیب تنکان/کیجون برخلاف جهت معامله.
        assertFalse(
            "تنکان زیر کیجون با معاملهٔ خرید نباید پاس شود",
            evaluate(healthyLong(), long = true, tenkanAboveKijun = false).conditions[1].passed,
        )
    }

    @Test
    fun `rule 3 rejects a chikou that is stuck to price`() {
        val spec = evaluate(healthyLong(), chikouFree = false)
        assertFalse("چیکوی درگیر باید رد شود", spec.conditions[2].passed)
        assertTrue("بقیهٔ قواعد نباید به‌خاطر چیکو رد شوند", spec.conditions[0].passed)
    }

    @Test
    fun `rule 4 rejects a missing equal cross and a stale one`() {
        val noCross = healthyLong().copy(equalCrossLevel = 0.0, equalCrossBars = 0)
        assertFalse("بدون کراس برابری، قاعده ۴ باید رد شود", evaluate(noCross).conditions[3].passed)

        val tooShort = healthyLong().copy(equalCrossBars = 2) // حد لازم ۳
        assertFalse("هم‌پوشانی دو کندلی کافی نیست", evaluate(tooShort).conditions[3].passed)

        val stale = healthyLong().copy(equalCrossBarsSinceEnd = HiddenCrossEngine.MAX_EQUAL_CROSS_AGE_BARS + 1)
        assertFalse("کراس کهنه باید رد شود", evaluate(stale).conditions[3].passed)

        // کراس «نابالغ»: قیمت هنوز به تاج برنگشته است؛ در عکس‌ها معامله روی خود کراس نیست.
        val immature = healthyLong().copy(equalCrossBarsSinceEnd = HiddenCrossEngine.MIN_RETEST_AGE_BARS - 1)
        assertFalse("کراس نابالغ (بدون بازگشت) باید رد شود", evaluate(immature).conditions[3].passed)
    }

    @Test
    fun `rule 5 rejects a missing crown`() {
        val noCrown = healthyLong().copy(crownLevel = 0.0)
        assertFalse("بدون تاج، قاعده ۵ باید رد شود", evaluate(noCrown).conditions[4].passed)
    }

    @Test
    fun `rule 5 requires price to actually return to the crown`() {
        // تاج دور از قیمت = «شکست» تازه، نه «بازگشت». طبق عکس‌ها معامله این‌جا نیست.
        val far = healthyLong().copy(crownLevel = 2440.0)
        assertFalse("تاج دور از قیمت باید رد شود", evaluate(far).conditions[4].passed)

        // تاج در سمت اشتباه (بالاتر از قیمت برای معاملهٔ خرید پس از بازگشت) هم پذیرفته نمی‌شود.
        val wrongSide = healthyLong().copy(crownLevel = 2418.0)
        assertFalse("تاج بالاتر از کلوز برای خرید باید رد شود", evaluate(wrongSide).conditions[4].passed)
    }

    @Test
    fun `rule 5 works symmetrically for a short retest`() {
        val short = snapshot(
            close = 2590.0,
            tenkan = 2592.0,
            kijun = 2596.0, // تنکان زیر کیجون → هم‌سو با فروش · میانه ۲۵۹۴ > کلوز
            thicknessAtr = 0.30,
            equalCrossLevel = 2595.0,
            equalCrossBars = 5,
            equalCrossBarsSinceEnd = 12,
            crownLevel = 2591.0, // ۱ واحد بالای کلوز = لمس و رد شدن
        )
        val spec = evaluate(short, long = false, tenkanAboveKijun = false)
        assertTrue(
            "بازگشت به تاج برای فروش باید پاس شود؛ ردشده‌ها: " +
                spec.conditions.filterNot { it.passed }.map { it.titleFa },
            spec.allPassed,
        )
    }

    // ─────────────────────── ۳. تعداد قاعده‌ها زیاد نشود ───────────────────────

    @Test
    fun `adding a condition would be caught here`() {
        // آموزش اصلی فقط همین پنج چیز را می‌گوید. اگر روزی کسی شرط ششم اضافه کرد
        // (آنالوگ آماری، بدنهٔ کندل، هم‌سویی تایم‌فریم…)، این آزمون می‌افتد — همان اشتباهی
        // که هیدن کراس را به «۰ معامله» رسانده بود.
        assertEquals(5, evaluate(healthyLong()).conditions.size)
        val titles = evaluate(healthyLong()).conditions.map { it.titleFa }
        assertTrue(titles[0].contains("ابر مخفی"))
        assertTrue(titles[1].contains("جهت"))
        assertTrue(titles[2].contains("چیکو"))
        assertTrue(titles[3].contains("کراس برابری"))
        assertTrue(titles[4].contains("تاج"))
    }

    // ─────────────────────── ۴. استراتژی واقعاً معامله می‌سازد ───────────────────────

    private fun series(n: Int, seed: Long, base: Double = 2400.0): List<Candle> {
        val out = ArrayList<Candle>(n)
        var price = base
        val stepMs = 15 * 60_000L
        val phase1 = (seed * 1.7) % 6.28
        val phase2 = (seed * 3.1) % 6.28
        for (i in 0 until n) {
            val trend = i * (0.04 + (seed % 3) * 0.02)
            val wave = 6.0 * Math.sin(i / 9.0 + phase1) + 3.0 * Math.sin(i / 2.3 + phase2)
            val open = price
            val close = base + trend + wave
            out.add(
                Candle(
                    1_600_000_000_000L + i * stepMs, open,
                    maxOf(open, close) + 1.6, minOf(open, close) - 1.6, close,
                    100.0 + i % 17, true,
                ),
            )
            price = close
        }
        return out
    }

    @Test
    fun `hidden cross strategy produces trades instead of sitting dead`() {
        val result = BacktestReplayEngine.run(
            series(2_500, seed = 5),
            BacktestReplayEngine.Config(
                strategyId = BitcoinStrategyModel.HIDDEN_CROSS.id,
                symbol = "XAU/USD",
                warmupBars = 120,
                includeHiddenCrossLayer = true,
                enabledStrategyIds = setOf(BitcoinStrategyModel.HIDDEN_CROSS.id),
            ),
        )
        assertTrue(
            "هیدن کراس باید معامله بسازد؛ ۰ معامله یعنی ماشه‌اش دوباره به شرط اضافه وابسته شده است",
            result.trades.isNotEmpty(),
        )
        assertTrue(
            "حداقل یک معامله باید همهٔ پنج شرط را هم‌زمان پاس کرده باشد (maxConditionsPassed)",
            result.maxConditionsPassed >= 5,
        )
    }

    @Test
    fun `every hidden cross trade closes with a finite R`() {
        val result = BacktestReplayEngine.run(
            series(2_500, seed = 7),
            BacktestReplayEngine.Config(
                strategyId = BitcoinStrategyModel.HIDDEN_CROSS.id,
                symbol = "XAU/USD",
                warmupBars = 120,
                includeHiddenCrossLayer = true,
                enabledStrategyIds = setOf(BitcoinStrategyModel.HIDDEN_CROSS.id),
            ),
        )
        result.trades.forEach { t ->
            assertTrue("R معامله باید متناهی باشد", t.rMultiple.isFinite())
            val exit = t.exitPrice
            assertTrue("قیمت خروج باید موجود، متناهی و مثبت باشد", exit != null && exit.isFinite() && exit > 0.0)
        }
    }
}
