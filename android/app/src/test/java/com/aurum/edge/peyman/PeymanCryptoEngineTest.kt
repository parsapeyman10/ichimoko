package com.aurum.edge.peyman

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.peyman.BtcContextGate
import com.aurum.edge.core.peyman.BtcMarketContext
import com.aurum.edge.core.peyman.CryptoMarketRegime
import com.aurum.edge.core.peyman.CryptoMarketRegimeEngine
import com.aurum.edge.core.peyman.CryptoPortfolioRiskGate
import com.aurum.edge.core.peyman.CryptoStructuralBreakEngine
import com.aurum.edge.core.peyman.NeoWaveEnsembleAgreement
import com.aurum.edge.core.peyman.PeymanCryptoConfig
import com.aurum.edge.core.peyman.PeymanCryptoDecision
import com.aurum.edge.core.peyman.PeymanCryptoEngine
import com.aurum.edge.core.peyman.PeymanCryptoInput
import com.aurum.edge.core.peyman.PeymanEntryMode
import com.aurum.edge.core.peyman.PeymanExecutionMode
import com.aurum.edge.core.peyman.PeymanSetupStatus
import com.aurum.edge.core.peyman.PeymanSetupType
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * آزمون‌های موتور «پیمان کریپتو حرفه‌ای».
 *
 * فیکسچرها عمداً دستی و قطعی‌اند (نه تصادفی): هر آزمون باید دقیقاً یک قاعده را بسنجد و
 * اگر روزی آستانه‌ای عوض شود، مشخص باشد کدام رفتار شکسته است.
 */
class PeymanCryptoEngineTest {

    private val t0 = 1_700_000_000_000L

    // ─────────────────────────── فیکسچرها ───────────────────────────

    /**
     * سری تایم ماشه: ۱۲۰ کندل نرمال + ۳۴ کندل فشردگی + کندل شکست + (اختیاری) بازآزمایی/ادامه.
     */
    private fun compressionSeries(
        breakoutBody: Double = 0.55,
        breakoutVolume: Double = 3200.0,
        wickOnly: Boolean = false,
        retest: Boolean = false,
        afterBars: Int = 0,
        deepReturn: Boolean = false,
    ): List<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        var idx = 0
        fun add(o: Double, c: Double, hi: Double, lo: Double, v: Double) {
            out.add(Candle(t0 + idx * 900_000L, o, hi, lo, c, v, true))
            idx++
            price = c
        }
        for (k in 0 until 120) {
            val r = 1.0
            val c = if (k % 2 == 0) price + 0.25 else price - 0.15
            add(price, c, max(price, c) + r * 0.15, min(price, c) - r * 0.15, 1000.0 + (k % 5) * 40)
        }
        var level = Double.NEGATIVE_INFINITY
        for (k in 0 until 34) {
            val f = 1.0 - 0.92 * k / 34.0
            val r = 0.30 + 0.45 * f
            val c = if (k % 2 == 0) price + r * 0.20 else price - r * 0.20
            val hi = max(price, c) + r * 0.15
            if (k >= 14 && hi > level) level = hi
            add(price, c, hi, min(price, c) - r * 0.15, 1000.0 - 280.0 * (1 - f))
        }
        val open = price
        if (wickOnly) {
            add(open, open - 0.05, open + breakoutBody + 0.30, open - 0.30, breakoutVolume)
        } else {
            add(open, open + breakoutBody, open + breakoutBody + 0.05, open - 0.12, breakoutVolume)
        }
        if (retest) {
            val lvl = if (level.isFinite()) level else price
            val oo = lvl + 0.10
            val cc = lvl + 0.30
            add(oo, cc, max(oo, cc) + 0.03, lvl + 0.02, 900.0)
        }
        if (deepReturn) {
            add(price, price - 0.90, price + 0.05, price - 1.00, 1800.0)
        }
        repeat(afterBars) {
            add(price, price + 0.05, price + 0.10, price - 0.10, 950.0)
        }
        return out
    }

    /** سری استراتژی (۱H) با زیگزاگ واضح تا نئوویو شمارش معتبر بدهد. */
    private fun strategySeries(): List<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        var idx = 0
        fun leg(len: Int, step: Double, vol: Double) {
            for (k in 0 until len) {
                val hi = max(price, price + step) + 0.18
                val lo = min(price, price + step) - 0.18
                out.add(Candle(t0 + idx * 3_600_000L, price, hi, lo, price + step, vol, true))
                idx++
                price += step
            }
        }
        for (k in 0 until 60) {
            val c = price + if (k % 2 == 0) 0.3 else -0.2
            out.add(Candle(t0 + idx * 3_600_000L, price, max(price, c) + 0.15, min(price, c) - 0.15, c, 1000.0, true))
            idx++
            price = c
        }
        leg(10, 2.2, 1600.0)
        leg(6, -0.9, 1200.0)
        leg(12, 2.6, 1800.0)
        leg(5, -0.8, 1100.0)
        leg(14, 2.4, 1700.0)
        for (k in 0 until 12) {
            val c = price + if (k % 2 == 0) 0.4 else -0.3
            out.add(Candle(t0 + idx * 3_600_000L, price, max(price, c) + 0.15, min(price, c) - 0.15, c, 1200.0, true))
            idx++
            price = c
        }
        return out
    }

    private fun macroSeries(): List<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        for (k in 0 until 60) {
            out.add(Candle(t0 + k * 4 * 3_600_000L, price, price + 0.9, price - 0.4, price + 0.5, 5000.0, true))
            price += 0.5
        }
        return out
    }

    /** هم‌مبنا کردن تایم‌فریم‌ها با آخرین قیمت ماشه. */
    private fun rescale(series: List<Candle>, target: Double): List<Candle> {
        val k = target / series.last().close
        return series.map { it.copy(open = it.open * k, high = it.high * k, low = it.low * k, close = it.close * k) }
    }

    private fun decide(
        bars: List<Candle>,
        config: PeymanCryptoConfig = PeymanCryptoConfig.PAPER,
        entered: Set<String> = emptySet(),
        nowOffsetMinutes: Long = 1L,
        symbol: String = "SOL/USDT",
        btc: BtcMarketContext? = null,
        openPositions: List<PaperTrade> = emptyList(),
        todayLossPct: Double = 0.0,
        consecutiveLosses: Int = 0,
        fakeBreakouts: Int = 0,
        macro: List<Candle>? = null,
        strategy: List<Candle>? = null,
    ): PeymanCryptoDecision {
        val last = bars.last().close
        return PeymanCryptoEngine.decide(
            PeymanCryptoInput(
                symbol = symbol,
                macroBars = macro ?: rescale(macroSeries(), last),
                strategyBars = strategy ?: rescale(strategySeries(), last),
                triggerBars = bars,
                btcContext = btc ?: BtcMarketContext(
                    regime = CryptoMarketRegime.TREND_EXPANSION,
                    trendDirection = SignalAction.BUY,
                    atrPercentile = 55.0,
                    relativeStrength = 1.2,
                    dataTimestamp = bars.last().time + 60_000L,
                ),
                openPositions = openPositions,
                config = config,
                nowMillis = bars.last().time + nowOffsetMinutes * 60_000L,
                triggerInterval = Interval.M15,
                todayLossPct = todayLossPct,
                consecutiveLosses = consecutiveLosses,
                fakeBreakoutsSinceLastWin = fakeBreakouts,
                alreadyEnteredSetupIds = entered,
            ),
        )
    }

    // ─────────────────────── ۱. حالت‌های اجرا ───────────────────────

    @Test
    fun `disabled by default and evaluates nothing`() {
        val d = decide(compressionSeries(), config = PeymanCryptoConfig())
        assertEquals("پیش‌فرض باید خاموش باشد", PeymanExecutionMode.DISABLED, d.mode)
        assertFalse("در حالت خاموش هیچ ورودی مجاز نیست", d.isTradeAllowed)
        assertEquals("ستاپی ارزیابی نمی‌شود", PeymanSetupType.NONE, d.setupType)
        assertNull(d.entry)
    }

    @Test
    fun `shadow mode records the decision without opening a trade`() {
        val d = decide(compressionSeries(), config = PeymanCryptoConfig.SHADOW)
        assertEquals(PeymanExecutionMode.SHADOW, d.mode)
        assertFalse("سایه هرگز معامله باز نمی‌کند", d.isTradeAllowed)
        assertEquals(SignalAction.NO_TRADE, d.action)
        assertTrue("تصمیم در سایه باید کامل ثبت شود", d.qualityScore > 0.0)
        assertTrue("Trace باید دلیل داشته باشد", d.trace.finalDecisionFa.isNotBlank())
    }

    @Test
    fun `paper mode allows a clean compression breakout`() {
        val d = decide(compressionSeries())
        assertTrue("ورود باید مجاز باشد: ${d.trace.hardGateFailures}", d.isTradeAllowed)
        assertEquals(SignalAction.BUY, d.action)
        assertEquals(PeymanSetupType.COMPRESSION_EXPANSION, d.setupType)
        assertTrue("امتیاز کیفیت باید از آستانه بگذرد: ${d.qualityScore}", d.qualityScore >= 78.0)
        assertNotNull(d.entry)
        assertNotNull(d.stopLoss)
        assertNotNull(d.takeProfit)
        assertTrue("حد ضرر باید زیر ورود باشد", (d.stopLoss ?: 0.0) < (d.entry ?: 0.0))
        assertTrue("حد سود باید بالای ورود باشد", (d.takeProfit ?: 0.0) > (d.entry ?: 0.0))
        assertTrue("R:R مؤثر باید حداقل ۱٫۵ باشد", (d.riskRewardAfterCosts ?: 0.0) >= 1.5)
        assertNotNull("شناسهٔ یکتا لازم است", d.setupId)
    }

    // ─────────────────────── ۲. سلامت داده ───────────────────────

    @Test
    fun `stale feed blocks every entry`() {
        val d = decide(compressionSeries(), nowOffsetMinutes = 1_000L)
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "دلیل باید صریح به کهنگی داده اشاره کند: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("کهنه") },
        )
    }

    @Test
    fun `duplicate timestamps block every entry`() {
        val bars = compressionSeries().toMutableList()
        bars[bars.size - 2] = bars[bars.size - 2].copy(time = bars[bars.size - 3].time)
        val d = decide(bars)
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "زمان تکراری باید صریح گزارش شود: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("ترتیب زمانی") || it.contains("تکراری") },
        )
    }

    @Test
    fun `flat prices with zero ATR never produce a signal`() {
        val flat = (0 until 130).map { Candle(t0 + it * 900_000L, 100.0, 100.0, 100.0, 100.0, 1000.0, true) }
        val d = decide(flat)
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "ATR صفر باید صریح گزارش شود: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("ATR") },
        )
    }

    @Test
    fun `price basis mismatch between timeframes blocks entries`() {
        val bars = compressionSeries()
        // مبنای ماکرو ۲۰٪ بالاتر ⇒ تحلیل روی دو مبنای مختلف بی‌معناست.
        val macro = rescale(macroSeries(), bars.last().close * 1.20)
        val d = decide(bars, macro = macro)
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "اختلاف مبنا باید گزارش شود: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("مبنا") },
        )
    }

    @Test
    fun `non crypto symbols are out of scope`() {
        val d = decide(compressionSeries(), symbol = "XAU/USD")
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "باید صریح بگوید فقط رمزارز: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("رمزارز") },
        )
        assertEquals(AssetClass.COMMODITY, AssetClass.of("XAU/USD"))
    }

    @Test
    fun `volume data is never fabricated`() {
        // همهٔ حجم‌ها یکسان (از جمله کندل شکست) ⇒ حجم قابل‌اعتماد نیست.
        val bars = compressionSeries().map { it.copy(volume = 1000.0) }
        val d = decide(bars)
        assertFalse("بدون حجم معتبر نباید ورودی ساخته شود", d.isTradeAllowed)
        assertTrue(
            "دلیل باید به حجم اشاره کند: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("حجم") },
        )
    }

    // ─────────────────────── ۳. BOS و بازآزمایی ───────────────────────

    @Test
    fun `wick only breakout is rejected`() {
        val d = decide(compressionSeries(wickOnly = true))
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "باید صریح بگوید شکست فقط شدو بود: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("شدو") },
        )
    }

    @Test
    fun `low volume breakout is rejected`() {
        val d = decide(compressionSeries(breakoutVolume = 1000.0))
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "باید صریح به حجم اشاره کند: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("حجم") },
        )
    }

    @Test
    fun `oversized breakout candle waits for retest instead of chasing`() {
        val d = decide(compressionSeries(breakoutBody = 3.2))
        assertFalse("تعقیب ممنوع", d.isTradeAllowed)
        assertEquals(PeymanSetupStatus.WAITING_RETEST, d.setupStatus)
        assertTrue(
            "پیام باید تعقیب ممنوع باشد: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("تعقیب") } || d.trace.bosStatusFa.contains("تعقیب"),
        )
    }

    @Test
    fun `successful retest confirms the entry`() {
        val d = decide(compressionSeries(retest = true))
        assertTrue("بازآزمایی معتبر باید ورود بدهد: ${d.trace.hardGateFailures}", d.isTradeAllowed)
        assertEquals(PeymanEntryMode.RETEST_CONFIRMED, d.entryMode)
    }

    @Test
    fun `deep return into the zone invalidates the setup`() {
        val d = decide(compressionSeries(deepReturn = true))
        assertFalse(d.isTradeAllowed)
        assertEquals(PeymanSetupStatus.INVALIDATED, d.setupStatus)
        assertTrue(
            "دلیل باید بازگشت عمیق باشد: ${d.trace.hardGateFailures}",
            d.trace.hardGateFailures.any { it.contains("بازگشت عمیق") },
        )
    }

    @Test
    fun `setup expires after the wait window`() {
        val bars = compressionSeries(afterBars = 6)
        val result = CryptoStructuralBreakEngine.detect(
            bars = bars,
            symbol = "SOL/USDT",
            rulesVersion = PeymanCryptoConfig().rulesVersion,
            intervalLabel = "15m",
            direction = SignalAction.BUY,
            config = PeymanCryptoConfig.PAPER,
            referenceAtr = 1.0,
        )
        assertEquals("پس از ۵ کندل باید منقضی شود", PeymanSetupStatus.EXPIRED, result.status)
        assertFalse(result.isEntryCandidate)
    }

    @Test
    fun `current bar never joins its own structural level`() {
        val bars = compressionSeries()
        val lookback = PeymanCryptoConfig().structureLookbackBars
        val expected = bars.subList(bars.size - 1 - lookback, bars.size - 1).maxOf { it.high }
        val result = CryptoStructuralBreakEngine.detect(
            bars = bars,
            symbol = "SOL/USDT",
            rulesVersion = PeymanCryptoConfig().rulesVersion,
            intervalLabel = "15m",
            direction = SignalAction.BUY,
            config = PeymanCryptoConfig.PAPER,
            referenceAtr = 1.0,
        )
        assertEquals("سطح باید فقط از کندل‌های قبل ساخته شود", expected, result.structuralLevel ?: 0.0, 1e-9)
    }

    @Test
    fun `one setup id means one entry`() {
        val bars = compressionSeries()
        val first = decide(bars)
        assertTrue(first.isTradeAllowed)
        val id = first.setupId
        assertNotNull(id)
        val second = decide(bars, entered = setOf(id!!))
        assertFalse("همان شکست نباید دو معامله بسازد", second.isTradeAllowed)
        assertTrue(
            "دلیل باید تکرار باشد: ${second.trace.hardGateFailures}",
            second.trace.hardGateFailures.any { it.contains("قبلاً") },
        )
    }

    // ─────────────────────── ۴. ریسک و پرتفوی ───────────────────────

    @Test
    fun `correlated long cluster is capped`() {
        val cfg = PeymanCryptoConfig.PAPER
        val longs = listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "ADA/USDT").map { sym ->
            PaperTrade(
                id = sym,
                symbol = sym,
                interval = Interval.M15,
                action = SignalAction.BUY,
                entry = 100.0,
                stopLoss = 99.0,
                takeProfit = 102.0,
                confidence = 80.0,
                riskReward = 2.0,
                openedAt = t0,
            )
        }
        val blocked = CryptoPortfolioRiskGate.evaluate(
            direction = SignalAction.BUY,
            openPositions = longs,
            config = cfg,
            todayLossPct = 0.0,
            consecutiveLosses = 0,
            fakeBreakoutsSinceLastWin = 0,
        )
        assertFalse("چهار Long هم‌جهت یک خوشه است، نه چهار ریسک مستقل", blocked.passed)
        assertEquals("CLUSTER_RISK", blocked.code)
    }

    @Test
    fun `daily loss limit and cooldowns cannot be bypassed`() {
        val cfg = PeymanCryptoConfig.PAPER
        val daily = CryptoPortfolioRiskGate.evaluate(
            SignalAction.BUY, emptyList(), cfg, todayLossPct = 5.0,
            consecutiveLosses = 0, fakeBreakoutsSinceLastWin = 0,
        )
        assertEquals("DAILY_LOSS_LIMIT", daily.code)
        val streak = CryptoPortfolioRiskGate.evaluate(
            SignalAction.BUY, emptyList(), cfg, todayLossPct = 0.0,
            consecutiveLosses = 3, fakeBreakoutsSinceLastWin = 0,
        )
        assertEquals("LOSS_STREAK_COOLDOWN", streak.code)
        val fake = CryptoPortfolioRiskGate.evaluate(
            SignalAction.BUY, emptyList(), cfg, todayLossPct = 0.0,
            consecutiveLosses = 0, fakeBreakoutsSinceLastWin = 1,
        )
        assertEquals("FAKE_BREAKOUT_COOLDOWN", fake.code)
    }

    @Test
    fun `reversal setup never risks more than half`() {
        val cfg = PeymanCryptoConfig.PAPER
        val normal = CryptoPortfolioRiskGate.effectiveRiskPct(cfg, isReversal = false)
        val reversal = CryptoPortfolioRiskGate.effectiveRiskPct(cfg, isReversal = true)
        assertTrue("ریسک برگشت باید حداکثر نصف باشد", reversal <= normal / 2.0 + 1e-9)
    }

    // ─────────────────────── ۵. رژیم، نئوویو و زمینهٔ BTC ───────────────────────

    @Test
    fun `compression regime is detected and conflict yields unknown`() {
        val compression = CryptoMarketRegimeEngine.detect(compressionSeries().dropLast(1))
        assertEquals(CryptoMarketRegime.COMPRESSION, compression.regime)
        val noisy = (0 until 140).map { k ->
            val base = 100.0 + (if (k % 2 == 0) 0.6 else -0.6)
            Candle(t0 + k * 900_000L, base, base + 0.7, base - 0.7, base + (if (k % 3 == 0) 0.4 else -0.4), 900.0 + (k % 7), true)
        }
        assertEquals(
            "نشانه‌های متناقض باید UNKNOWN بدهند، نه حدس",
            CryptoMarketRegime.UNKNOWN,
            CryptoMarketRegimeEngine.detect(noisy).regime,
        )
    }

    @Test
    fun `unknown regime or disabled reversal never creates a setup`() {
        assertEquals(
            PeymanSetupType.NONE,
            PeymanCryptoEngine.selectSetup(CryptoMarketRegime.UNKNOWN, PeymanCryptoConfig.PAPER),
        )
        assertEquals(
            "ستاپ برگشت پیش‌فرض خاموش است",
            PeymanSetupType.NONE,
            PeymanCryptoEngine.selectSetup(CryptoMarketRegime.PANIC_CAPITULATION, PeymanCryptoConfig.PAPER),
        )
        assertEquals(
            PeymanSetupType.COMPRESSION_EXPANSION,
            PeymanCryptoEngine.selectSetup(CryptoMarketRegime.COMPRESSION, PeymanCryptoConfig.PAPER),
        )
    }

    @Test
    fun `unknown regime only allows compression setup when structure is actually coiled`() {
        val cfg = PeymanCryptoConfig.tradingFor(15)
        // ساختار واقعاً فشرده → ستاپ فشردگی → انبساط مجاز است.
        assertEquals(
            PeymanSetupType.COMPRESSION_EXPANSION,
            PeymanCryptoEngine.selectSetup(
                CryptoMarketRegime.UNKNOWN, cfg,
                atrPercentile = 30.0, rangeRatio = 0.55, er14 = 0.20,
            ),
        )
        // رژیم نامشخص و ساختار فشرده نیست → همان «حدس نزن».
        assertEquals(
            PeymanSetupType.NONE,
            PeymanCryptoEngine.selectSetup(
                CryptoMarketRegime.UNKNOWN, cfg,
                atrPercentile = 90.0, rangeRatio = 1.40, er14 = 0.70,
            ),
        )
        // پیش‌فرض‌ها و PAPER هم دست‌نخورده‌اند.
        assertEquals(
            PeymanSetupType.NONE,
            PeymanCryptoEngine.selectSetup(
                CryptoMarketRegime.UNKNOWN, PeymanCryptoConfig.PAPER,
                atrPercentile = 30.0, rangeRatio = 0.55, er14 = 0.20,
            ),
        )
    }

    @Test
    fun `compression rvol threshold is configurable and defaults keep old behaviour`() {
        assertEquals(1.50, PeymanCryptoConfig.SHADOW.compressionMinRvol, 1e-9)
        assertEquals(1.50, PeymanCryptoConfig.PAPER.compressionMinRvol, 1e-9)
        val trading = PeymanCryptoConfig.tradingFor(15)
        assertTrue("آستانهٔ فشردگی باید قابل تنظیم و کمتر از سقف قدیمی باشد", trading.compressionMinRvol < 1.50)
        assertTrue(trading.allowUnknownRegimeCompression)
        // پیش‌فرضِ رفتارِ قبلی: نامشخص ⇒ مسدود.
        assertFalse(PeymanCryptoConfig.PAPER.allowUnknownRegimeCompression)
        assertFalse(PeymanCryptoConfig.SHADOW.allowUnknownRegimeCompression)
    }

    @Test
    fun `btc panic blocks alt longs and missing context blocks alts`() {
        val panic = BtcContextGate.evaluate(
            symbol = "SOL/USDT",
            isAlt = true,
            context = BtcMarketContext(CryptoMarketRegime.PANIC_CAPITULATION, SignalAction.SELL, 90.0, -3.0, t0),
            direction = SignalAction.BUY,
            nowMillis = t0 + 60_000L,
        )
        assertFalse("BTC در هراس ⇒ خرید آلت ممنوع", panic.passed)
        val missing = BtcContextGate.evaluate("SOL/USDT", true, null, SignalAction.BUY, t0)
        assertFalse("نبود زمینهٔ BTC ⇒ ورود آلت مسدود", missing.passed)
        val self = BtcContextGate.evaluate("BTC/USDT", false, null, SignalAction.BUY, t0)
        assertTrue("خود BTC به زمینهٔ بیرونی نیاز ندارد", self.passed)
    }

    @Test
    fun `trace carries version and every gate reason`() {
        val d = decide(compressionSeries(wickOnly = true))
        assertEquals(PeymanCryptoConfig().rulesVersion, d.trace.rulesVersion)
        assertTrue(d.trace.hardGateFailures.isNotEmpty())
        assertTrue(d.trace.finalDecisionFa.isNotBlank())
        assertTrue("وضعیت رژیم باید ثبت شود", d.trace.regimeFa.isNotBlank())
    }

    @Test
    fun `neo wave never triggers an entry by itself`() {
        // بدون BOS (آخرین کندل فشرده و داخل ناحیه) هیچ ورودی ساخته نمی‌شود،
        // حتی اگر شمارش نئوویو معتبر باشد.
        val bars = compressionSeries().dropLast(1)
        val d = decide(bars)
        assertFalse(d.isTradeAllowed)
        assertTrue(
            "وضعیت باید FORMING/WAITING باشد: ${d.setupStatus}",
            d.setupStatus == PeymanSetupStatus.FORMING || d.setupStatus == PeymanSetupStatus.BLOCKED ||
                d.setupStatus == PeymanSetupStatus.WAITING_RETEST,
        )
    }

    @Test
    fun `other models keep their own path because the flag is off`() {
        // پیش‌فرض BaseStrategyConfig باید خاموش باشد؛ در غیر این صورت رفتار مدل‌های دیگر عوض می‌شود.
        assertEquals(PeymanExecutionMode.DISABLED, com.aurum.edge.core.BaseStrategyConfig().peymanCrypto.mode)
        assertFalse(com.aurum.edge.core.BaseStrategyConfig().peymanCrypto.isActive)
        // و موتور «سایه» هم تصمیم معامله نمی‌سازد.
        val shadow = decide(compressionSeries(), config = PeymanCryptoConfig.SHADOW)
        assertFalse(shadow.isTradeAllowed)
        assertTrue(shadow.trace.ensembleAgreement != NeoWaveEnsembleAgreement.NONE)
    }
}
