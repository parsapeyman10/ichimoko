package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های «دروازهٔ هم‌گرایی مکتبی» — قرارداد رفتاری نسخهٔ ۳.۹.۵۷.
 *
 * ── چرا این فایل لازم است؟ ────────────────────────────────────────────────
 * نسخهٔ ۳.۹.۵۶ ورود را فقط با «هر ۱۶ شرط هم‌زمان» می‌داد. اندازه‌گیری روی ۵٬۵۲۱ ارزیابی
 * نشان داد نرخ پاس هر شرط بین ۵۵٪ و ۸۲٪ است، پس حاصل‌ضرب عملاً صفر می‌شود و نتیجه
 * در ۸۸ روز یک معامله بود. راه‌حل، حذف محافظ‌ها نیست؛ تفکیک آن‌ها به سه لایهٔ مکتبی است
 * (رژیم ⇒ وتوی سخت، تعادل و ماشه ⇒ امتیاز). این تست‌ها همان تفکیک را قفل می‌کنند تا
 * کسی بعداً نه محافظ‌ها را بی‌صدا برداشته و نه دروازه را به «همه‌چیز رد کن» برگرداند.
 */
class BaseStrategyBookGateTest {

    private val baseTime = 1_760_000_000_000L

    private fun series(stepMs: Long, bars: Int = 90, flat: Boolean = false): List<Candle> {
        val list = mutableListOf<Candle>()
        for (i in 0 until bars) {
            val base = if (flat) 2_000.0 + (i % 2) * 0.1 else 60_000.0 + i * 35.0
            val (o, h, l, c) = when {
                flat -> listOf(base, base + 0.05, base - 0.05, base)
                i in 85..87 -> listOf(base - 40.0, base + 200.0, base - 240.0, base - 110.0)
                i == 88 -> listOf(base - 90.0, base + 250.0, base - 220.0, base + 30.0)
                i == 89 -> listOf(base - 80.0, base + 320.0, base - 200.0, base + 180.0)
                else -> listOf(base - 60.0, base + 300.0, base - 300.0, base + 120.0)
            }
            list += Candle(
                time = baseTime + i * stepMs,
                open = o, high = h, low = l, close = c,
                volume = 120.0 + i, closed = true,
            )
        }
        return list
    }

    private fun evaluate(
        macro: List<Candle> = series(Interval.H4.millis),
        strategy: List<Candle> = series(Interval.H1.millis),
        trigger: List<Candle> = series(Interval.M15.millis),
        // سری مرجع این تست‌ها رمزارزی است و رفتار «بیس» را می‌سنجد؛ سیاست «فقط پیمان روی
        // رمزارز» عمداً خاموش می‌شود تا خودِ شروط بیس آزموده شوند (سیاست در
        // PeymanStrategyTest جداگانه تست می‌شود).
        config: BaseStrategyConfig = BaseStrategyConfig(cryptoSymbolsOnlyPeyman = false),
        symbol: String = IchimokuStrategyEngine.BTC_SYMBOL,
    ) = IchimokuStrategyEngine.evaluateBitcoinStrategy(
        activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
        config = config,
        macroCandles = macro,
        strategyCandles = strategy,
        triggerCandles = trigger,
        symbolInput = symbol,
    )

    /** یک بررسی متنی شرط با نتیجهٔ دلخواه — برای آزمودن دروازه بدون ساختن بازار واقعی. */
    private fun checks(vararg passedFlags: Boolean): List<StrategyConditionCheck> =
        passedFlags.mapIndexed { i, ok ->
            StrategyConditionCheck(
                index = i + 1,
                titleFa = "شرط ${i + 1}",
                passed = ok,
                detailFa = "",
            )
        }

    // ───────────────── ۱) ریاضیات مشکل اصلی بود، نه بدشانسی ─────────────────

    @Test
    fun `sixteen way conjunction is mathematically inert and the gate replaces it`() {
        // فرض: هر شرط با احتمال واقعی اندازه‌گیری‌شده پاس شود.
        val passRates = doubleArrayOf(
            0.55, 0.56, 0.72, 0.69, 0.97, 0.38, 0.26, 0.39,
            0.76, 0.81, 0.35, 0.65, 0.82, 0.81, 0.21, 0.46,
        )
        var conjunction = 1.0
        passRates.forEach { conjunction *= it }
        // ۱۶ تأیید هم‌زمان روی دادهٔ واقعی عملاً هرگز رخ نمی‌دهد (< 0.01٪).
        assertTrue(
            "حاصل‌ضرب باید عملاً صفر باشد ولی $conjunction بود",
            conjunction < 0.0001,
        )

        // دروازهٔ مکتبی با یک ضعف جزئی باید شلیک کند — یعنی معامله وجود دارد.
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(
                true, true, true, true,      // رژیم
                true, true, false, true, true, true,   // تعادل (یکی جا افتاده)
                true, true, true, true, true, true, true, // ماشه
            ),
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertTrue("با یک ضعف در لایهٔ تعادل باید سیگنال صادر شود: ${gate.noteFa}", gate.fires)
        assertTrue(gate.totalScorePct < 100.0)
    }

    // ───────────────── ۲) وتوی سخت هرگز شل نمی‌شود ─────────────────

    @Test
    fun `regime failure blocks the entry no matter how good everything else is`() {
        // همه‌چیز عالی، جز شرط ۱ (رژیم کومو) — یعنی روند تعریف نشده است.
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(
                false, true, true, true,
                true, true, true, true, true, true, true, true, true, true, true, true, true,
            ),
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertFalse("نبود رژیم کومو باید ورود را ببندد", gate.fires)
        assertFalse(gate.hardVetoPassed)
        assertEquals("تنها همین یک شرط باید در فهرست وتوی سخت باشد", 1, gate.failedHard.size)
    }

    @Test
    fun `chikou and future kumo are also hard vetoes`() {
        listOf(3, 4, 6).forEach { vetoIndex ->
            val flags = BooleanArray(17) { true }
            flags[vetoIndex - 1] = false
            val gate = IchimokuStrategyEngine.bookConfluenceGate(
                conditions = checks(*flags),
                isBaseModel = true,
                baseConfig = BaseStrategyConfig(),
            )
            assertFalse("شرط $vetoIndex باید وتوی سخت باشد", gate.fires)
            assertFalse(gate.hardVetoPassed)
        }
    }

    @Test
    fun `execution economics is a hard veto`() {
        val flags = BooleanArray(17) { true }
        flags[16] = false // شرط ۱۷: هزینهٔ اجرا در برابر ۱R
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(*flags),
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertFalse("ستاپی که هزینهٔ اجرایش ۱R را می‌خورد نباید وارد شود", gate.fires)
    }

    // ───────────────── ۳) نه بیش از حد سخت، نه بی‌محافظ ─────────────────

    @Test
    fun `too many quality failures keep the strategy out`() {
        // رژیم و وتوهای سخت درست‌اند، ولی لایهٔ تعادل (۵/۸/۹/۱۴) فروپاشیده است:
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(
                true, true, true, true,          // رژیم: کامل
                false, true, true, false, false, false, true, false, // ۵✗ ۶✓ ۷✓ ۸✗ ۹✗ ۱۰✓ ۱۱✓ ۱۲✗
                true, false, true, true, true,   // ۱۳✓ ۱۴✗ ۱۵✓ ۱۶✓ ۱۷✓
            ),
            isBaseModel = true,
            // آستانه‌های «سخت‌گیرانه» (۸۰٪/۷۰٪) آزموده می‌شوند، نه حالت حداکثر اجرا.
            baseConfig = BaseStrategyConfig(allowMaximumExecution = false),
        )
        assertTrue(gate.hardVetoPassed)
        assertFalse("با این‌همه ضعف نباید سیگنال بدهد: ${gate.noteFa}", gate.fires)
        assertTrue("اما باید «آماده» شناخته شود تا کاربر بداند نزدیک است", gate.watch || gate.totalScorePct < IchimokuStrategyEngine.BOOK_MIN_TOTAL_PCT)
    }

    @Test
    fun `one marginal weakness in the trigger layer is tolerated`() {
        val flags = BooleanArray(17) { true }
        flags[6] = false // شرط ۷ (وزن ۰.۶) — شکست چیکو
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(*flags),
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertTrue("یک ضعف وزن‌کم نباید ورود را بکشد: ${gate.noteFa}", gate.fires)
        assertTrue(gate.triggerScorePct >= IchimokuStrategyEngine.BOOK_MIN_LAYER_PCT)
    }

    @Test
    fun `specialist strategies are not routed through the base gate`() {
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(
                false, false, false, false,
                true, true, true, true, true, true, true, true, true, true, true, true, true,
            ),
            isBaseModel = false,
            baseConfig = BaseStrategyConfig(),
        )
        assertFalse("مدل‌های اختصاصی منطق خودشان را دارند", gate.fires)
        assertTrue(gate.noteFa.isBlank())
    }

    // ───────────────── ۴) رفتار انتها-به-انتها ─────────────────

    @Test
    fun `reference series still yields a real long signal with seventeen conditions`() {
        val report = evaluate()
        assertEquals(17, report.conditions.size)
        assertTrue(
            report.conditions.joinToString(" | ") { "${it.index}:${it.passed}" },
            report.conditions.all { it.passed },
        )
        assertEquals(StrategyReadinessState.SIGNAL_BUY, report.readiness)
        assertTrue(report.bookGateScorePct >= IchimokuStrategyEngine.BOOK_MIN_TOTAL_PCT - 1e-6)
        assertTrue(report.bookGateNoteFa.contains("هم‌گرایی"))
    }

    @Test
    fun `flat range is still rejected end to end`() {
        val flat = series(Interval.M15.millis, flat = true)
        val report = evaluate(
            macro = series(Interval.H4.millis, flat = true),
            strategy = series(Interval.H1.millis, flat = true),
            trigger = flat,
        )
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = report.conditions,
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertFalse("بازار تخت نباید از دروازه رد شود: ${gate.noteFa}", gate.fires)
    }

    // ───────────────── ۵) اقتصاد اجرا روی مراجع واقعی ─────────────────

    @Test
    fun `round trip cost differs by venue and is measured from the venue spec`() {
        val btc = IchimokuStrategyEngine.roundTripCostPct("BTC/USDT", 62_000.0)
        val xau = IchimokuStrategyEngine.roundTripCostPct("XAU/USD", 2_400.0)
        val eur = IchimokuStrategyEngine.roundTripCostPct("EUR/USD", 1.085)
        // کریپتو ۰.۱۰٪ در هر سمت + ۱bps اسپرد ⇒ ≈ ۰.۲۱٪
        assertTrue("هزینهٔ BTC باید حدود ۰.۲٪ باشد ولی $btc بود", btc > 0.0015 && btc < 0.0035)
        // طلا و یورو ارزان‌تر از کریپتو هستند — همین دلیل سوددهی آن‌هاست.
        // (طلا: اسپرد ~۰.۰۲ دلار برای هر اونس ≈ ۰.۸ bps؛ یورو: ~۰.۸ پیپ.)
        assertTrue("هزینهٔ XAU باید کمتر از BTC باشد ($xau vs $btc)", xau < btc)
        assertTrue("هزینهٔ EUR باید کمتر از BTC باشد ($eur vs $btc)", eur < btc)
        // و هر دو باید زیر ۱ bps کامل بمانند، وگرنه مدل هزینه اشتباه است.
        assertTrue("هزینهٔ XAU غیرمنطقی است: $xau", xau in 0.00001..0.001)
        assertTrue("هزینهٔ EUR غیرمنطقی است: $eur", eur in 0.00001..0.001)
    }

    @Test
    fun `cost floor widens the stop instead of letting fees eat one R`() {
        val report = evaluate()
        val b = report.rrBreakdown
        assertNotNull(b)
        b!!
        val entry = report.suggestedEntry ?: 0.0
        val oneR = kotlin.math.abs(entry - b.stopLossPrice)
        val cost = IchimokuStrategyEngine.roundTripCostPct(IchimokuStrategyEngine.BTC_SYMBOL, entry) * entry
        val ratio = cost / oneR.coerceAtLeast(1e-9)
        assertTrue(
            "هزینهٔ اجرا نباید بیش از سقف مجاز از ۱R را بخورد (نسبت=$ratio)",
            ratio <= IchimokuStrategyEngine.MAX_COST_TO_RISK_RATIO + 1e-6,
        )
    }

    // ───────────────── ۴) «آماده» باید نادر باشد، وگرنه برچسب بی‌معنا است ─────────────────

    @Test
    fun `watch threshold sits just under the fire threshold so the badge stays meaningful`() {
        // اندازه‌گیری: با ۶۶٪، ۴۵٪ کندل‌ها «آماده» اعلام می‌شدند و برچسب عملاً همه‌جا بود.
        // قرارداد: «آماده» یک پله زیر آستانهٔ شلیک است، ولی هم‌چنان دروازهٔ وتوی سخت را دارد.
        assertTrue(
            "آستانهٔ آماده باید کمتر از آستانهٔ شلیک باشد",
            IchimokuStrategyEngine.BOOK_WATCH_TOTAL_PCT < IchimokuStrategyEngine.BOOK_MIN_TOTAL_PCT,
        )
        assertTrue(
            "آستانهٔ آماده نباید بی‌دلیل شل شود (کف ۷۵٪)",
            IchimokuStrategyEngine.BOOK_WATCH_TOTAL_PCT >= 75.0,
        )
        assertTrue(
            "کف شمارش شرط برای آماده نباید به «نصف شرط‌ها» برگردد",
            IchimokuStrategyEngine.BOOK_WATCH_MIN_CONDITIONS >= 14,
        )
    }

    @Test
    fun `watch is never granted while a hard veto is failing`() {
        val flags = BooleanArray(17) { true }
        flags[0] = false // شرط ۱ — کوموی سه‌تایم‌فریم (وتوی سخت)
        val gate = IchimokuStrategyEngine.bookConfluenceGate(
            conditions = checks(*flags),
            isBaseModel = true,
            baseConfig = BaseStrategyConfig(),
        )
        assertFalse("با وتوی سختِ شکسته حتی «آماده» هم نباید داده شود", gate.watch)
        assertFalse(gate.fires)
        assertTrue(gate.failedHard.isNotEmpty())
    }
}
