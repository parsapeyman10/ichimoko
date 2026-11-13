package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «ترمز زمان» و «سقف‌های قابل تنظیم».
 *
 * ریشهٔ این تست‌ها گزارش واقعی ۱۶۰ معامله است: بعد سوم معامله — زمان — هیچ‌جا سنجیده نمی‌شد و
 * سقف پوزیشن هم داخل دروازه سخت‌کد بود، پس کاربر نمی‌توانست ببیند یا تغییر دهد.
 */
class TimeStopAndLimitsTest {

    private val t0 = 1_760_000_000_000L

    private fun trade(
        entry: Double = 90_000.0,
        stop: Double = 89_400.0,
        target: Double = 91_400.0,
        interval: Interval = Interval.M5,
        action: SignalAction = SignalAction.BUY,
    ) = PaperTrade(
        id = "T1",
        symbol = "BTC/USDT",
        interval = interval,
        action = action,
        entry = entry,
        stopLoss = stop,
        takeProfit = target,
        riskReward = 2.0,
        confidence = 85.0,
        openedAt = t0,
        initialStopLoss = stop,
    )

    // ─────────────────────────── ترمز زمان ───────────────────────────

    @Test
    fun `time stop does not fire before the window closes`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_010.0,
            now = t0 + 5 * Interval.M5.millis, // ۵ کندل از ۲۰ کندل
        )
        assertFalse(evaluate.shouldClose)
        assertEquals(5, evaluate.barsHeld)
    }

    @Test
    fun `time stop closes a flat trade once the window closes`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_010.0, // ~۰.۰۲R
            now = t0 + IchimokuStrategyEngine.DEFAULT_MAX_HOLD_BARS * Interval.M5.millis,
        )
        assertTrue("معاملهٔ بی‌حرکت باید بسته شود", evaluate.shouldClose)
        assertTrue(evaluate.reasonFa.contains("خروج زمانی"))
        // ۲۰ کندل ۵ دقیقه‌ای = ۱۰۰ دقیقه
        assertEquals(
            100.0,
            (IchimokuStrategyEngine.DEFAULT_MAX_HOLD_BARS * Interval.M5.millis) / 60_000.0,
            1e-6,
        )
    }

    @Test
    fun `time stop keeps a trade that already made enough R`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_400.0, // +۰.۶۷R
            now = t0 + 40 * Interval.M5.millis,
        )
        assertFalse("معامله‌ای که R کافی ساخته باید بماند", evaluate.shouldClose)
    }

    @Test
    fun `time stop respects the take profit even inside the window`() {
        val t = trade()
        assertFalse(
            IchimokuStrategyEngine.evaluateTimeStop(
                trade = t,
                livePrice = 91_500.0,
                now = t0 + 30 * Interval.M5.millis,
            ).shouldClose,
        )
    }

    @Test
    fun `time stop works for shorts too`() {
        val t = trade(action = SignalAction.SELL, stop = 90_600.0, target = 88_600.0)
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_590.0, // اندکی ضرر برای SELL
            now = t0 + 30 * Interval.M5.millis,
        )
        assertTrue(evaluate.shouldClose)
        assertTrue(evaluate.currentRMultiple < 0.0)
    }

    @Test
    fun `time stop never fires without a live price`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = null,
            now = t0 + 999 * Interval.M5.millis,
        )
        assertFalse("بدون قیمت معتبر قضاوت نمی‌شود", evaluate.shouldClose)
    }

    @Test
    fun `time stop is measured in bars of the entry timeframe`() {
        val h1 = trade(interval = Interval.H1)
        // ۱۹ کندل ۱ ساعته هنوز داخل پنجره است
        assertFalse(
            IchimokuStrategyEngine.evaluateTimeStop(
                trade = h1,
                livePrice = 90_010.0,
                now = t0 + 19 * Interval.H1.millis,
            ).shouldClose,
        )
        // ۲۰ کندل ۱ ساعته = پنجره بسته
        assertTrue(
            IchimokuStrategyEngine.evaluateTimeStop(
                trade = h1,
                livePrice = 90_010.0,
                now = t0 + 20 * Interval.H1.millis,
            ).shouldClose,
        )
    }

    // ───────────────── سقف‌های قابل تنظیم (جای سخت‌کد) ─────────────────

    @Test
    fun `settings expose the safety gate ceilings with the old defaults`() {
        val defaults = AppSettings()
        assertTrue(
            "سقف پیش‌فرض پوزیشن هم‌زمان باید عدد کوچک و محافظتی باشد",
            defaults.maxOpenTrades in 1..5,
        )
        assertTrue(
            "سقف پیش‌فرض هر نماد باید ۱ باشد تا روی یک نماد انباشت نشود",
            defaults.maxOpenTradesPerSymbol in 1..2,
        )
        assertTrue(
            "سقف هم‌زمان نباید از سقف هر نماد کمتر باشد",
            defaults.maxOpenTrades >= defaults.maxOpenTradesPerSymbol,
        )
    }

    @Test
    fun `gate honours a user lowered concurrent position ceiling`() {
        val open = listOf(
            PaperTrade(
                id = "O1",
                symbol = "BTC/USDT",
                interval = Interval.M15,
                action = SignalAction.BUY,
                entry = 90_000.0,
                stopLoss = 89_400.0,
                takeProfit = 91_400.0,
                riskReward = 2.0,
                confidence = 85.0,
                openedAt = t0,
            ),
        )
        val strict = EntrySafetyGate.evaluate(
            symbol = "ETH/USDT",
            interval = Interval.M15,
            openTrades = open,
            nowMillis = t0,
            signalBarTime = t0,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            limits = EntrySafetyGate.Limits(maxConcurrentPositions = 1),
            enforceSession = false,
        )
        assertFalse("با سقف ۱، پوزیشن دوم نباید باز شود", strict.allowed)

        val relaxed = EntrySafetyGate.evaluate(
            symbol = "ETH/USDT",
            interval = Interval.M15,
            openTrades = open,
            nowMillis = t0,
            signalBarTime = t0,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            limits = EntrySafetyGate.Limits(maxConcurrentPositions = 2),
            enforceSession = false,
        )
        assertTrue(relaxed.allowed)
    }

    @Test
    fun `risk brake migration defaults are protective not wide open`() {
        assertTrue(
            "سقف ضرر روزانهٔ پیش‌فرضِ مهاجرت باید محافظتی باشد",
            com.aurum.edge.data.SettingsStore.DEFAULT_DAILY_LOSS_LIMIT_PERCENT <= 10.0,
        )
        assertTrue(
            "سقف افت سرمایهٔ پیش‌فرضِ مهاجرت باید محافظتی باشد",
            com.aurum.edge.data.SettingsStore.DEFAULT_MAX_DRAWDOWN_LIMIT_PERCENT <= 25.0,
        )
    }

    // ───────────────── گزارش صادق: نمونه صفر ≠ مقدار صفر ─────────────────

    @Test
    fun `zero samples render as not measured instead of a fake zero`() {
        val R = com.aurum.edge.core.NeuralReportText
        assertEquals(R.NOT_MEASURED, R.pct(0.0, samples = 0))
        assertEquals(R.NOT_MEASURED, R.number(0.0, 2, samples = 0))
        assertNotNull(R.pct(0.0, samples = 5))
        assertTrue(R.pct(0.0, samples = 5).contains("0.0"))
        // نمونهٔ نامعلوم (پیش‌فرض) مثل قبل قالب‌بندی می‌شود
        assertTrue(R.pct(42.0).contains("42.0"))
    }

    @Test
    fun `learning profiles no longer ship fabricated win rates before any sample`() {
        val fresh = PerStrategyNeuralTuningProfile(strategyId = "BASE", strategyTitleFa = "بیس", strategyBadgeFa = "B")
        assertEquals("پیش از اولین نمونه نباید نرخ برد ساختگی نشان داده شود", 0.0, fresh.winRatePct, 1e-9)
        assertEquals(0.0, fresh.expectedProfitFactor, 1e-9)
        assertEquals(0, fresh.samplesAnalyzed)
    }

    @Test
    fun `symbol profile has no invented profit factor either`() {
        val fresh = PerSymbolNeuralTuningProfile(symbol = "BTC/USDT")
        assertEquals(0.0, fresh.expectedProfitFactor, 1e-9)
        assertEquals(0.0, fresh.winRatePct, 1e-9)
    }
}
