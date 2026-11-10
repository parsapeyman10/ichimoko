package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های «استراتژی بیس» — قرارداد رفتاری تغییرهای نسخهٔ ۳.۹.۵۶.
 *
 * این تست‌ها به اینترنت، فایل واقعی یا حساب برنامه دست نمی‌زنند؛ همه روی سری کندلِ
 * ساخته‌شده در حافظه اجرا می‌شوند.
 *
 * هر تغییری که یکی از این رفتارها را برگرداند، باید یکی از این تست‌ها را بشکند:
 *  ۱) محافظ رِنج: کوپل عمدی شرط ۱۳ و فاز روند (شرط ۱۵)
 *  ۲) خواندن سقف اسنایپر از پروفایل استراتژی + برش سخت `[0.20 .. 0.55]`
 *  ۳) تقارن دالان `[۱.۵R .. ۳R]` حول یک ۱R واحد
 *  ۴) خاموش‌شدنی بودن لایهٔ هیدن کراس در «بیس»
 *  ۵) نبود شرط «همیشه درست» در شرط ۱۱
 */
class BaseStrategyDeepDiveTest {

    private val baseTime = 1_760_000_000_000L

    /**
     * سری صعودی با پولبک کنترل‌شده در سه کندل آخر — همان الگویی که در تست‌های موجود
     * (RiskAndCalendarEngineTest) هر ۱۶ شرط را پاس می‌کند و «سیگنال خرید» می‌دهد.
     */
    private fun bullishPullbackSeries(stepMs: Long, bars: Int = 90): List<Candle> {
        val list = mutableListOf<Candle>()
        for (i in 0 until bars) {
            val base = 60_000.0 + i * 35.0
            val (o, h, l, c) = when {
                i in 85..87 -> listOf(base - 40.0, base + 200.0, base - 240.0, base - 110.0)
                i == 88 -> listOf(base - 90.0, base + 250.0, base - 220.0, base + 30.0)
                i == 89 -> listOf(base - 80.0, base + 320.0, base - 200.0, base + 180.0)
                else -> listOf(base - 60.0, base + 300.0, base - 300.0, base + 120.0)
            }
            list += Candle(
                time = baseTime + i * stepMs,
                open = o,
                high = h,
                low = l,
                close = c,
                volume = 120.0 + i,
                closed = true,
            )
        }
        return list
    }

    /**
     * سری «زیگزاگ صعودی»: شیب کلی مثبت است ولی کلوزها یک بالا/یک پایین می‌روند.
     * نتیجه: کیجون و SSB حرکت می‌کنند (فلت نیستند) اما نرخ کارایی کافمن (ER14) پایین است.
     * این تنها حالتی است که در آن شرط ۱۳ می‌شکند بدون اینکه بازار «رِنج تخت» باشد.
     */
    private fun zigzagTrendSeries(stepMs: Long, bars: Int = 90): List<Candle> {
        val list = mutableListOf<Candle>()
        for (i in 0 until bars) {
            val base = 60_000.0 + i * 35.0
            val up = i % 2 == 0
            val o = if (up) base - 500.0 else base + 500.0
            val c = if (up) base + 400.0 else base - 400.0
            val h = maxOf(o, c) + 60.0
            val l = minOf(o, c) - 60.0
            list += Candle(
                time = baseTime + i * stepMs,
                open = o,
                high = h,
                low = l,
                close = c,
                volume = 150.0,
                closed = true,
            )
        }
        return list
    }

    /** بازار تخت با نویز بسیار کم: ER عملاً صفر، کیجون و SSB تخت ⇒ رِنج واقعی. */
    private fun flatRangeSeries(stepMs: Long, bars: Int = 90): List<Candle> {
        val list = mutableListOf<Candle>()
        for (i in 0 until bars) {
            val mid = if (i % 2 == 0) 2_000.0 else 2_000.1
            list += Candle(
                time = baseTime + i * stepMs,
                open = mid,
                high = mid + 0.05,
                low = mid - 0.05,
                close = mid,
                volume = 100.0,
                closed = true,
            )
        }
        return list
    }

    private fun evaluate(
        macro: List<Candle> = bullishPullbackSeries(Interval.H4.millis),
        strategy: List<Candle> = bullishPullbackSeries(Interval.H1.millis),
        trigger: List<Candle> = bullishPullbackSeries(Interval.M15.millis),
        // سری مرجع این تست‌ها رمزارزی است و رفتار «بیس» را می‌سنجد؛ سیاست «فقط پیمان روی
        // رمزارز» عمداً خاموش می‌شود تا خودِ شروط بیس آزموده شوند (سیاست در
        // PeymanStrategyTest جداگانه تست می‌شود).
        config: BaseStrategyConfig = BaseStrategyConfig(cryptoSymbolsOnlyPeyman = false),
        symbol: String = "BTC/USDT",
    ) = IchimokuStrategyEngine.evaluateBitcoinStrategy(
        activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
        config = config,
        macroCandles = macro,
        strategyCandles = strategy,
        triggerCandles = trigger,
        symbolInput = symbol,
    )

    @Test
    fun `crypto symbols trade the base strategy by default and stop only if the peyman monopoly is turned on`() {
        // ۳.۹.۶۷: انحصار «فقط پیمان» پیش‌فرض خاموش شد، چون پیمان خودش پیش‌فرض DISABLED است و
        // نتیجه‌اش این بود که رمزارز هیچ‌وقت معامله نمی‌کرد (BTC: صفر معامله در ۶۴٬۸۳۴ کندل).
        val trading = evaluate(config = BaseStrategyConfig())
        assertEquals(SignalAction.BUY, trading.action)

        // و اگر کاربر انحصار را روشن کند، همان سری و همان استراتژی باید مسدود شود.
        val blocked = evaluate(config = BaseStrategyConfig(cryptoSymbolsOnlyPeyman = true))
        assertEquals(SignalAction.NO_TRADE, blocked.action)
        assertTrue(blocked.readiness != StrategyReadinessState.SIGNAL_BUY)
    }

    @Test
    fun `the peyman-only policy changes nothing on non crypto symbols`() {
        val withPolicy = evaluate(config = BaseStrategyConfig(), symbol = "XAU/USD")
        val withoutPolicy = evaluate(
            config = BaseStrategyConfig(cryptoSymbolsOnlyPeyman = false),
            symbol = "XAU/USD",
        )
        assertEquals(withoutPolicy.action, withPolicy.action)
        assertEquals(withoutPolicy.readiness, withPolicy.readiness)
    }

    // ─────────────────────────── مبنا ───────────────────────────

    @Test
    fun `base strategy still produces a full seventeen-condition long signal`() {
        val report = evaluate()
        assertEquals(17, report.conditions.size)
        assertTrue(
            report.conditions.joinToString(" | ") { "${it.index}:${it.passed}(${it.detailFa})" },
            report.conditions.all { it.passed },
        )
        assertEquals(StrategyReadinessState.SIGNAL_BUY, report.readiness)
        assertEquals(SignalAction.BUY, report.action)
        assertNotNull(report.suggestedStopLoss)
        assertNotNull(report.primaryTakeProfit)
        val rr = report.riskReward ?: 0.0
        assertTrue("R:R باید در [1.5..3.0] بماند ولی $rr بود", rr in 1.5..3.0)
    }

    // ─────────── ۱) محافظ رِنج (کوپل عمدی شرط ۱۳ و ۱۵) ───────────

    @Test
    fun `flat range is rejected by the range filter and by the trend phase`() {
        val flat = flatRangeSeries(Interval.M15.millis)
        val report = evaluate(
            macro = flatRangeSeries(Interval.H4.millis),
            strategy = flatRangeSeries(Interval.H1.millis),
            trigger = flat,
        )
        val cond13 = report.conditions.first { it.index == 13 }
        val cond15 = report.conditions.first { it.index == 15 }
        assertFalse("بازار تخت باید شرط ۱۳ را رد کند: ${cond13.detailFa}", cond13.passed)
        assertFalse("و فاز روند هم باید رِنج باشد: ${cond15.detailFa}", cond15.passed)
        assertEquals(SignalAction.NO_TRADE, report.action)
        assertEquals(TrendPhase.CHOP_RANGE, report.trendPhase)
    }

    @Test
    fun `trend phase stays coupled to the range filter on purpose`() {
        // قرارداد ۳.۹.۵۶: این کوپل عمدی است. اگر کسی `!cond13Passed` را از trendPhase بردارد،
        // یک راه ورود در بازار رِنج باز می‌شود؛ این تست همان در را می‌بندد.
        val zigzag = zigzagTrendSeries(Interval.M15.millis)
        val report = evaluate(
            macro = zigzagTrendSeries(Interval.H4.millis),
            strategy = zigzagTrendSeries(Interval.H1.millis),
            trigger = zigzag,
        )
        val cond13 = report.conditions.first { it.index == 13 }
        val cond15 = report.conditions.first { it.index == 15 }
        if (!cond13.passed) {
            assertEquals(
                "هر جا شرط ۱۳ رد شود، فاز روند باید CHOP_RANGE باشد",
                TrendPhase.CHOP_RANGE,
                report.trendPhase,
            )
            assertFalse("و شرط ۱۵ هم باید رد شود", cond15.passed)
        }
        assertEquals(SignalAction.NO_TRADE, report.action)
    }

    // ────────── ۲) سقف اسنایپر از پروفایل استراتژی ──────────

    @Test
    fun `sniper cap is read from the strategy profile and clamped hard`() {
        val id = BitcoinStrategyModel.BASE_ICHIMOKU.id
        val wide = BaseStrategyConfig(
            perStrategyOverrides = mapOf(
                id to PerStrategyConfigOverride(strategyId = id, maxSniperDistanceAtr = 0.50),
            ),
        )
        val absurd = BaseStrategyConfig(
            perStrategyOverrides = mapOf(
                id to PerStrategyConfigOverride(strategyId = id, maxSniperDistanceAtr = 0.90),
            ),
        )
        // ۰.۵۰ داخل بازهٔ مجاز است و باید واقعاً اعمال شود (پیش از این نادیده گرفته می‌شد).
        assertEquals(0.50, wide.resolvedMaxSniperDistanceAtr(id), 1e-9)
        // ۰.۹۰ باید با سقف سخت ۰.۵۵ بریده شود، نه اینکه «نقطه‌زنی» را شل کند.
        assertEquals(0.55, absurd.resolvedMaxSniperDistanceAtr(id), 1e-9)
        // پیش‌فرض بدون اورراید همان ۰.۳۵ است.
        assertEquals(0.35, BaseStrategyConfig().resolvedMaxSniperDistanceAtr(id), 1e-9)
        // و مقدار پایه هم از طریق config معمولی قابل تغییر است.
        assertEquals(0.40, BaseStrategyConfig(maxSniperDistanceAtr = 0.40)
            .resolvedMaxSniperDistanceAtr(id), 1e-9)
    }

    // ─────────── ۳) تقارن دالان R:R حول یک ۱R ───────────

    @Test
    fun `rr corridor is built from one anchor and one R`() {
        val report = evaluate()
        val b = report.rrBreakdown
        assertNotNull("گزارش باید تجزیهٔ R:R داشته باشد", b)
        b!!

        // ۱) لنگر دالان همان نقطهٔ ورود گزارش است، نه قیمت لحظه‌ای و نه حد ضرر.
        val entry = report.suggestedEntry ?: 0.0
        assertEquals("لنگر دالان باید همان ورود اسنایپر باشد", b.entryPrice, entry, 1e-6)

        // ۲) هر دو کران باید حول **همان** ۱R بسته شوند: [۱.۵R .. ۳R].
        val oneR = kotlin.math.abs(b.entryPrice - b.stopLossPrice)
        val reward = kotlin.math.abs(b.takeProfitPrice - b.entryPrice)
        assertTrue("۱R باید مثبت باشد ($oneR)", oneR > 0.0)
        assertTrue("پاداش باید مثبت باشد ($reward)", reward > 0.0)
        val realisedRr = reward / oneR
        assertTrue(
            "هدف باید داخل دالان [۱.۵R..۳R] بماند ولی ${realisedRr}R بود",
            realisedRr >= 1.5 - 0.02 && realisedRr <= 3.0 + 0.02,
        )

        // ۳) عدد بهینه هم در همان دالان است و هرگز از ۳ بیشتر نمی‌شود.
        assertTrue("عدد بهینه باید ≥ ۱.۵ باشد", b.optimalRiskReward >= 1.5 - 1e-9)
        assertTrue("عدد بهینه باید ≤ ۳ باشد", b.optimalRiskReward <= 3.0 + 1e-9)

        // ۴) و R:R گزارش‌شده باید با همان هندسه بخواند.
        assertEquals(
            "R:R گزارش با پاداش÷ریسک باید هم‌خوان باشد",
            b.actualRiskReward,
            IchimokuStrategyEngine.round2(realisedRr),
            0.10,
        )
    }

    // ────────── ۴) خاموش‌شدنی بودن لایهٔ هیدن کراس ──────────

    @Test
    fun `hidden cross layer is off by default because it measured as neutral`() {
        // اندازه‌گیری ۳.۹.۶۵ روی XAU/USD 15m (۴۳٬۸۱۰ کندل): «بیس + لایه» و «بیس بدون لایه»
        // دقیقاً یکسان بودند؛ لایه‌ای که هیچ اثری ندارد ولی می‌تواند معامله را ببندد،
        // نباید پیش‌فرض روشن باشد.
        assertFalse("لایهٔ هیدن کراس باید پیش‌فرض خاموش باشد", BaseStrategyConfig().includeHiddenCrossLayer)
        assertFalse(
            "پیکربندی بک‌تست هم باید همان پیش‌فرض را داشته باشد",
            com.aurum.edge.core.BacktestReplayEngine.Config().includeHiddenCrossLayer,
        )
    }

    @Test
    fun `hidden cross layer can be removed from the base strategy`() {
        val withLayer = evaluate(config = BaseStrategyConfig(includeHiddenCrossLayer = true, cryptoSymbolsOnlyPeyman = false))
        val pureBase = evaluate(config = BaseStrategyConfig(includeHiddenCrossLayer = false, cryptoSymbolsOnlyPeyman = false))

        val withDetail = withLayer.conditions.first { it.index == 9 }.detailFa
        val pureDetail = pureBase.conditions.first { it.index == 9 }.detailFa

        assertFalse(
            "بیس خالص نباید دیوار تاج هیدن کراس را در شرط ۹ ارزیابی کند: $pureDetail",
            pureDetail.contains("دیوار تاج"),
        )
        assertTrue("شرط ۹ در بیس خالص باید پاس شود: $pureDetail", pureBase.conditions.first { it.index == 9 }.passed)
        // تعداد شرط‌ها به لایه وابسته نیست: بیس همیشه ۱۷ شرط دارد (۱۶ شرط مکتبی + اقتصاد اجرا).
        assertEquals(17, pureBase.conditions.size)
        assertEquals(17, withLayer.conditions.size)
        // هر دو حالت باید همچنان سیگنال معتبر بدهند (لایه یک فیلتر است، نه موتور).
        assertEquals(StrategyReadinessState.SIGNAL_BUY, pureBase.readiness)
        if (withDetail.contains("دیوار تاج")) {
            assertTrue("متن حالت روشن باید سطح دیوار را نشان دهد", withDetail.isNotBlank())
        }
    }

    // ─────────────── ۵) نبود شرط مرده در شرط ۱۱ ───────────────

    @Test
    fun `condition eleven reports only the live components`() {
        val report = evaluate()
        val cond11 = report.conditions.first { it.index == 11 }
        assertTrue("در سری مرجع شرط ۱۱ باید پاس شود: ${cond11.detailFa}", cond11.passed)
        assertTrue(cond11.detailFa.contains("بدنه"))
        assertTrue(cond11.detailFa.contains("CLV"))
        assertTrue(cond11.detailFa.contains("حجم"))
    }
}
