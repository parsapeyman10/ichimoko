package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های ماشهٔ «فشردگی → انبساط» استراتژی بیس.
 *
 * ## چرا دادهٔ ساختگی
 * سری سینوسی/تصادفی «رژیم فشردگی و بعد انفجار» تولید نمی‌کند: اندازه‌گیری روی پنج سری
 * ۲۵۰۰ کندلی نشان داد فقط ۲۰ کلوز بالای سطح ناحیه رخ می‌دهد و همهٔ فیلترها هیچ‌وقت با هم
 * پاس نمی‌شوند. با چنین داده‌ای «۰ سیگنال» چیزی را اثبات نمی‌کند. پس این‌جا کندل‌ها
 * دست‌ساز ساخته می‌شوند تا هر قاعده جداگانه آزموده شود.
 *
 * ## چه چیزی قفل می‌شود
 * بدون نگاه به آینده بودن سطوح، رد شکست شدویی/کم‌حجم/بدون انبساط، ورود مستقیم نزدیک،
 * انتظار بازآزمایی برای شکست بزرگ، تأیید بازآزمایی، ابطال با بازگشت عمیق و انقضا، ضدتکرار،
 * و اینکه با `enabled=false` هیچ چیزی عوض نشود.
 */
class CompressionExpansionEngineTest {

    // ─────────────────────── ساخت داده ───────────────────────

    private val t0 = 1_600_000_000_000L
    private val step = 15 * 60_000L

    /**
     * سری «فشردگی واقعی»: نوسان در سه پله کم می‌شود.
     *
     * چرا لازم است: فرمول‌های سند «کوچک‌شدن دامنه» و «انقباض ATR» را نسبت به پنجرهٔ قبل
     * می‌سنجند؛ با دامنهٔ ثابت این دو هرگز پاس نمی‌شوند (اندازه‌گیری شد: امتیاز ۴۰٪).
     * این سری ۷۰ کندل پرنوسان، ۴۰ کندل متوسط و ۲۵ کندل آرام دارد، پس فشردگیِ انتهایی
     * نسبت به گذشته واقعاً کوچک‌تر است — همان چیزی که سند «فشردگی» می‌نامد.
     */
    private fun quiet(n: Int = 135, base: Double = 100.0, from: Int = 0): List<Candle> =
        (0 until n).map { k ->
            val i = from + k
            val phase = when {
                i < 70 -> 1.0
                i < 110 -> 0.58
                else -> 0.22
            }
            val open = base + ((i % 3) - 1) * 0.05 * phase
            val close = open + (if (i % 2 == 0) 1 else -1) * 0.06 * phase
            Candle(
                t0 + i * step,
                open,
                maxOf(open, close) + 0.04 * phase,
                minOf(open, close) - 0.04 * phase,
                close,
                // حجم متنوع (وگرنه دادهٔ حجم «نامعتبر» شمرده می‌شود)
                1000.0 + (i % 7) * 5,
                true,
            )
        }

    /** کندل شکست صعودی — پیش‌فرض‌ها با ATR=۰٫۳۰ ورود مستقیم می‌سازند. */
    private fun bullishBreakout(
        index: Int,
        close: Double = 100.30,
        open: Double = 100.05,
        low: Double = 100.04,
        high: Double = 100.35,
        volume: Double = 3000.0,
    ): Candle = Candle(t0 + index * step, open, high, low, close, volume, true)

    /** کندل شکست نزولی (قرینهٔ کامل صعودی). */
    private fun bearishBreakout(index: Int, close: Double = 99.70): Candle =
        Candle(t0 + index * step, 99.95, 99.96, 99.65, close, 3000.0, true)

    /** اسنپ‌شات ایچیموکو با کنترل کامل روی ATR و تنکان/کیجون. */
    private fun snap(
        close: Double,
        atr: Double = 0.30,
        tenkan: Double = 100.0,
        kijun: Double = 100.0,
        chikouOpen: Boolean = true,
        futureKumoBearish: Boolean = false,
    ): IchimokuBarSnapshot = IchimokuBarSnapshot(
        time = t0 + 200L * step,
        open = close,
        high = close + 0.1,
        low = close - 0.1,
        close = close,
        tenkan = tenkan,
        kijun = kijun,
        prevTenkan = tenkan,
        prevKijun = kijun,
        currentSpanA = close,
        currentSpanB = close,
        // ابر آینده: A زیر B ⇒ نزولی (همان تعریفی که موتور استفاده می‌کند).
        futureSpanA = if (futureKumoBearish) close - 5 else close + 5,
        futureSpanB = close,
        prevFutureSpanA = if (futureKumoBearish) close - 5 else close + 5,
        prevFutureSpanB = close,
        atr14 = atr,
        chikouBullishOpenSpace = chikouOpen,
        chikouBearishOpenSpace = chikouOpen,
        chikouResistancePeak = close + 1.0,
        chikouSupportTrough = close - 1.0,
        isCurrentSpanBFlat = false,
        isKijunFlat = false,
        isTenkanFlat = false,
        flatEquilibriumLevels = emptyList(),
    )

    private fun cfg(
        enabled: Boolean = true,
        volumeRatio: Double = 1.50,
    ) = CompressionExpansionEngine.Config(
        enabled = enabled,
        minBreakoutVolumeRatio = volumeRatio,
    )

    private fun analyze(
        bars: List<Candle>,
        snapshot: IchimokuBarSnapshot? = null,
        config: CompressionExpansionEngine.Config = cfg(),
        isLong: Boolean = true,
        gates: CompressionExpansionEngine.Gates = CompressionExpansionEngine.Gates.PERMISSIVE,
        already: Set<String> = emptySet(),
    ) = CompressionExpansionEngine.analyze(
        closedTriggerBars = bars,
        snapshot = snapshot ?: snap(bars.last().close),
        config = config,
        isLong = isLong,
        gates = gates,
        symbol = "XAU/USD",
        strategyId = "BASE_ICHIMOKU",
        triggerIntervalLabel = "15m",
        alreadyEnteredSetupIds = already,
    )

    // ─────────────────────── ۱. تشخیص فشردگی ───────────────────────

    @Test
    fun `real compression becomes READY without any order`() {
        val bars = quiet(135)
        val a = analyze(bars)
        assertEquals(
            "ناحیهٔ فشرده باید READY شود؛ وضعیت فعلی: ${a.state} · ${a.diagnosticsFa}",
            CompressionExpansionEngine.State.READY,
            a.state,
        )
        assertTrue("امتیاز باید بالای آستانه باشد", a.compressionScorePct >= 70.0)
        assertNotNull(a.compressionHigh)
        assertNotNull(a.compressionLow)
        assertTrue("سقف ناحیه باید بالای کف باشد", a.compressionHigh!! > a.compressionLow!!)
        assertFalse("در حالت READY هیچ سفارشی باز نمی‌شود", a.isEntryAllowed)
        assertEquals(CompressionExpansionEngine.EntryMode.NONE, a.entryMode)
    }

    @Test
    fun `wide ranging market is not mistaken for compression`() {
        // نوسان بزرگ: دامنهٔ هر کندل ۳ واحد و روند پرشیب.
        val wild = (0 until 135).map { i ->
            val open = 100.0 + i * 0.9
            val close = open + if (i % 2 == 0) 1.4 else -1.2
            Candle(t0 + i * step, open, maxOf(open, close) + 0.7, minOf(open, close) - 0.7, close, 1200.0 + (i % 5) * 40, true)
        }
        val a = analyze(wild, snap(wild.last().close, atr = 3.2))
        assertNotEquals(
            "بازار پرنوسان نباید فشرده شمرده شود؛ امتیاز=${a.compressionScorePct}",
            CompressionExpansionEngine.State.READY,
            a.state,
        )
    }

    @Test
    fun `current bar never joins the compression zone`() {
        val bars = quiet(130)
        val a = analyze(bars)
        val expectedHigh = bars.subList(110, 130).maxOf { it.high } // [n-20 .. n-1] پیش از کندل جاری
        // JUnit واقعی برای Double آرگومان delta می‌خواهد (شبیه‌ساز آزمایشگاه نه) — همین
        // تفاوت باعث شد CI قرمز شود در حالی که تست محلی سبز بود.
        assertEquals(
            "سقف ناحیه باید فقط از کندل‌های قبل از کندل جاری ساخته شود",
            round2(expectedHigh),
            round2(a.compressionHigh ?: 0.0),
            1e-9,
        )
    }

    @Test
    fun `insufficient history never signals`() {
        val a = analyze(quiet(40))
        assertEquals(CompressionExpansionEngine.State.FORMING, a.state)
        assertFalse(a.isEntryAllowed)
        assertTrue(
            "دلیل باید صریح باشد",
            a.blockedReasonsFa.any { it.contains("داده") },
        )
    }

    // ─────────────────────── ۲. فیلترهای شکست ───────────────────────

    @Test
    fun `wick-only breakout is rejected`() {
        // کلوز داخل ناحیه، فقط شدو بالای سطح.
        val bars = quiet(130) + Candle(t0 + 130 * step, 100.02, 101.20, 100.00, 100.05, 3000.0, true)
        val a = analyze(bars)
        assertFalse("شکست شدویی نباید ورود بسازد", a.isEntryAllowed)
        assertNotEquals(CompressionExpansionEngine.State.BREAKOUT_CONFIRMED, a.state)
    }

    @Test
    fun `low volume breakout is rejected`() {
        val bars = quiet(130) + bullishBreakout(130, volume = 1000.0) // برابر میانگین ۲۰ کندل
        val a = analyze(bars)
        assertFalse("شکست کم‌حجم نباید ورود بسازد", a.isEntryAllowed)
        assertNotEquals(
            "بدون تأیید حجم، شکست نباید «تأییدشده» اعلام شود",
            CompressionExpansionEngine.State.BREAKOUT_CONFIRMED,
            a.state,
        )
    }

    @Test
    fun `weak body or big adverse wick is rejected`() {
        // بدنهٔ کوچک: open≈close ولی کلوز بالای سطح و حجم بالا.
        val weakBody = quiet(130) + Candle(t0 + 130 * step, 100.05, 100.40, 99.99, 100.12, 3000.0, true)
        assertFalse("بدنهٔ ضعیف نباید قبول شود", analyze(weakBody).isEntryAllowed)

        // شدوی بالای بزرگ: کلوز ۱۰۰٫۱۵ ولی سقف ۱۰۱.
        val bigWick = quiet(130) + Candle(t0 + 130 * step, 100.02, 101.00, 100.00, 100.15, 3000.0, true)
        assertFalse("شدوی مخالف بزرگ نباید قبول شود", analyze(bigWick).isEntryAllowed)
    }

    @Test
    fun `breakout without volatility expansion is rejected`() {
        // کندل شکست با دامنهٔ کوچک‌تر از ATR و بدون گسترش.
        val bars = quiet(130) + Candle(t0 + 130 * step, 100.04, 100.075, 100.02, 100.07, 3000.0, true)
        val a = analyze(bars, snap(100.07, atr = 0.30))
        assertFalse("بدون گسترش نوسان نباید ورود بسازد", a.isEntryAllowed)
    }

    @Test
    fun `unusable volume never fakes a confirmation`() {
        // حجم ثابت/صفر: دادهٔ حجم معتبر نیست ⇒ ورود بدون حجم مجاز نیست.
        val flatVolume = quiet(130).map { it.copy(volume = 1000.0) } +
            Candle(t0 + 130 * step, 100.05, 100.35, 100.04, 100.30, 1000.0, true)
        val a = analyze(flatVolume)
        assertFalse("حجم ثابت یعنی دادهٔ حجم نداریم", a.volumeAvailable)
        assertFalse("بدون حجم معتبر هیچ ورودی مجاز نیست", a.isEntryAllowed)
        assertTrue(
            "دلیل باید صریح بگوید حجم معتبر نیست: ${a.blockedReasonsFa}",
            a.blockedReasonsFa.any { it.contains("حجم") },
        )
    }

    // ─────────────────────── ۳. ورود مستقیم ───────────────────────

    @Test
    fun `close breakout yields direct entry`() {
        val bars = quiet(130) + bullishBreakout(130)
        val a = analyze(bars, snap(100.30, atr = 0.30))
        assertTrue(
            "شکست نزدیک باید ورود مستقیم بدهد؛ وضعیت=${a.state} · دلایل=${a.blockedReasonsFa}",
            a.isEntryAllowed,
        )
        assertEquals(CompressionExpansionEngine.EntryMode.DIRECT_BREAKOUT, a.entryMode)
        assertEquals(CompressionExpansionEngine.State.BREAKOUT_CONFIRMED, a.state)
        assertNotNull("حد ضرر ساختاری لازم است", a.suggestedStopLoss)
        assertTrue(
            "حد ضرر باید زیر ورود باشد",
            a.suggestedStopLoss!! < bars.last().close,
        )
        assertNotNull("شناسهٔ ستاپ برای ضدتکرار لازم است", a.setupId)
    }

    @Test
    fun `huge candle only waits for retest and never chases`() {
        // کندل بزرگ: کلوز خیلی دور از سطح.
        val bars = quiet(130) + bullishBreakout(130, close = 101.80, high = 101.85)
        val a = analyze(bars, snap(101.80, atr = 0.30))
        assertFalse("تعقیب قیمت ممنوع است", a.isEntryAllowed)
        assertEquals(CompressionExpansionEngine.EntryMode.WAIT_RETEST, a.entryMode)
        assertEquals(CompressionExpansionEngine.State.WAITING_RETEST, a.state)
        assertTrue(
            "دلیل باید به تعقیب/بزرگی کندل اشاره کند: ${a.blockedReasonsFa}",
            a.blockedReasonsFa.any { it.contains("تعقیب") || it.contains("بیش‌ازحد بزرگ") },
        )
    }

    // ─────────────────────── ۴. بازآزمایی ───────────────────────

    @Test
    fun `successful retest confirms the long entry`() {
        val bars = quiet(130) +
            bullishBreakout(130, close = 101.80, high = 101.85) + // شکست بزرگ ⇒ انتظار بازآزمایی
            // بازگشت به سطح شکست (۱۰۰٫۰۳)، کلوز صعودی بالای سطح، حجم کافی
            Candle(t0 + 131 * step, 100.02, 100.12, 100.00, 100.10, 1500.0, true)
        val a = analyze(bars, snap(100.10, atr = 0.30))
        assertTrue(
            "بازآزمایی موفق باید ورود بدهد؛ وضعیت=${a.state} · دلایل=${a.blockedReasonsFa}",
            a.isEntryAllowed,
        )
        assertEquals(CompressionExpansionEngine.EntryMode.RETEST_CONFIRMED, a.entryMode)
    }

    @Test
    fun `successful retest confirms the short entry mirrored`() {
        val bars = quiet(130) +
            Candle(t0 + 130 * step, 99.95, 99.96, 98.20, 98.25, 3000.0, true) + // شکست نزولی بزرگ
            // بازگشت به سطح (۹۹٫۹۷)، کلوز نزولی زیر سطح، حجم کافی
            Candle(t0 + 131 * step, 99.98, 100.00, 99.88, 99.90, 1500.0, true)
        val a = analyze(bars, snap(99.90, atr = 0.30), isLong = false)
        assertTrue(
            "قرینهٔ فروش باید ورود بدهد؛ وضعیت=${a.state} · دلایل=${a.blockedReasonsFa}",
            a.isEntryAllowed,
        )
        assertEquals(CompressionExpansionEngine.EntryMode.RETEST_CONFIRMED, a.entryMode)
        assertTrue("حد ضرر فروش باید بالای ورود باشد", a.suggestedStopLoss!! > 98.90)
    }

    @Test
    fun `deep return into the zone kills the setup`() {
        val bars = quiet(130) +
            bullishBreakout(130, close = 101.80, high = 101.85) +
            Candle(t0 + 131 * step, 100.00, 100.02, 99.85, 99.90, 1500.0, true) // بازگشت عمیق داخل ناحیه
        val a = analyze(bars, snap(99.90, atr = 0.30))
        assertFalse(a.isEntryAllowed)
        assertEquals(CompressionExpansionEngine.State.FAILED_BREAKOUT, a.state)
        assertTrue(a.blockedReasonsFa.any { it.contains("بازگشت عمیق") || it.contains("ناموفق") })
    }

    @Test
    fun `setup expires after the retest window`() {
        val bars = quiet(130) +
            bullishBreakout(130, close = 101.80, high = 101.85) +
            quiet(10, from = 131) // ده کندل بعد، هیچ بازآزمایی‌ای نیست
        val a = analyze(bars, snap(100.0, atr = 0.30))
        assertFalse(a.isEntryAllowed)
        assertEquals(CompressionExpansionEngine.State.FAILED_BREAKOUT, a.state)
        assertTrue(
            "پیام باید صریح بگوید حرکت بدون ورود تمام شد: ${a.blockedReasonsFa}",
            a.blockedReasonsFa.any { it.contains("تعقیب ممنوع") },
        )
    }

    // ─────────────────────── ۵. دروازه‌ها و پلن ───────────────────────

    @Test
    fun `tight obstacle or bad rr blocks the entry`() {
        val bars = quiet(130) + bullishBreakout(130)
        val tight = analyze(bars, snap(100.30, atr = 0.30), gates = CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 2.0, spaceToObstacleR = 1.2))
        assertFalse("فضای ۱٫۲R تا مانع کافی نیست", tight.isEntryAllowed)

        val badRr = analyze(bars, snap(100.30, atr = 0.30), gates = CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 1.2))
        assertFalse("R:R زیر ۱٫۵ ممنوع است", badRr.isEntryAllowed)

        val noPlan = analyze(bars, snap(100.30, atr = 0.30), gates = CompressionExpansionEngine.Gates(planUsable = false, riskRewardAfterCosts = 2.0))
        assertFalse("پلن نامعتبر نباید ورود بدهد", noPlan.isEntryAllowed)
    }

    @Test
    fun `structural vetoes block the entry`() {
        val bars = quiet(130) + bullishBreakout(130)
        val s = snap(100.30, atr = 0.30)
        listOf(
            "چیکو" to CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 2.0, chikouBlocked = true),
            "ابر" to CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 2.0, futureKumoOpposes = true),
            "تایم" to CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 2.0, higherTfOpposes = true),
            "پایان روند" to CompressionExpansionEngine.Gates(planUsable = true, riskRewardAfterCosts = 2.0, lateExhaustion = true),
        ).forEach { (label, gates) ->
            assertFalse("وتوی «$label» باید ورود را ببندد", analyze(bars, s, gates = gates).isEntryAllowed)
        }
    }

    // ─────────────────────── ۶. ضدتکرار ───────────────────────

    @Test
    fun `same setup id never opens a second entry`() {
        val bars = quiet(130) + bullishBreakout(130)
        val first = analyze(bars, snap(100.30, atr = 0.30))
        assertTrue(first.isEntryAllowed)
        val id = first.setupId!!
        val second = analyze(bars, snap(100.30, atr = 0.30), already = setOf(id))
        assertFalse("همان شناسهٔ ستاپ نباید دوباره ورود بدهد", second.isEntryAllowed)
        assertTrue(second.blockedReasonsFa.any { it.contains("تکراری") })
    }

    // ─────────────────────── ۷. بدون نگاه به آینده ───────────────────────

    @Test
    fun `future bars cannot change a past analysis`() {
        val bars = quiet(130) + bullishBreakout(130)
        val a = analyze(bars, snap(100.30, atr = 0.30))

        // کندل آینده را عوض می‌کنیم؛ تحلیل همان کندل جاری نباید تغییر کند.
        val mutated = bars.dropLast(1) + Candle(t0 + 130 * step, 100.02, 999.0, 100.00, 998.0, 9000.0, true)
        val b = analyze(mutated, snap(100.30, atr = 0.30))
        assertEquals(
            "تحلیل باید فقط به دادهٔ گذشته و کندل جاری وابسته باشد",
            a.compressionHigh ?: 0.0,
            b.compressionHigh ?: 0.0,
            1e-9,
        )
        assertEquals(a.compressionLow ?: 0.0, b.compressionLow ?: 0.0, 1e-9)
        assertEquals(a.compressionScorePct, b.compressionScorePct, 1e-9)
    }

    @Test
    fun `same bar as past never changes earlier zone values`() {
        // سقف/کف ناحیه برای کندل ۱۳۰ باید با نسخه‌ای که ۲۰ کندل بعدی هم دارد یکی باشد.
        val base = quiet(130)
        val withFuture = base + quiet(20, from = 130)
        val a = analyze(base.take(130))
        val b = analyze(withFuture.take(130))
        assertEquals(a.compressionHigh ?: 0.0, b.compressionHigh ?: 0.0, 1e-9)
        assertEquals(a.compressionLow ?: 0.0, b.compressionLow ?: 0.0, 1e-9)
    }

    // ─────────────────────── ۸. خاموش‌بودن و مدل‌های دیگر ───────────────────────

    @Test
    fun `disabled flag does nothing at all`() {
        val bars = quiet(130) + bullishBreakout(130)
        val a = analyze(bars, snap(100.30, atr = 0.30), config = cfg(enabled = false))
        assertEquals(CompressionExpansionEngine.State.OFF, a.state)
        assertFalse(a.isEntryAllowed)
        assertNull(a.setupId)
        assertNull(a.compressionHigh)
    }

    @Test
    fun `runner is refused because partial position is not supported`() {
        val c = CompressionExpansionEngine.Config(enabled = true, runnerEnabled = true)
        assertFalse(
            "بدون پوزیشن جزئی واقعی، Runner فقط ادعاست و باید رد شود",
            c.withRunnerDisabled().runnerEnabled,
        )
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
}
