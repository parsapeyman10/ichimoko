package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «ترمز زمان» (حالا خاموش) و «سقف‌های قابل تنظیم».
 *
 * تاریخچه: این تُرمِز در نسخهٔ ۳.۹.۵۸ اضافه شد (۲۰ کندل). در ۳.۹.۷۶ به درخواست صریح کاربر
 * **از مسیر زندهٔ برنامه حذف شد**: بستن یک معاملهٔ روندی فقط به‌خاطر شمارش کندل، تز را باطل
 * نمی‌کند. خروج باید یکی از سه دلیل ساختاری را داشته باشد: حد ضرر، هدف، یا ابطال روند در سود.
 * این تست‌ها همان تصمیم را قفل می‌کنند تا کسی دوباره تایمر را برنگرداند.
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
    fun `time stop is disabled by default`() {
        assertEquals(0, IchimokuStrategyEngine.DEFAULT_MAX_HOLD_BARS)
        assertFalse(
            "توقف زمانی در بک‌تست هم خاموش است",
            BacktestReplayEngine.Config().useTimeStop,
        )
    }

    @Test
    fun `no trade is ever closed because time passed`() {
        val t = trade()
        // ۵۰۰ کندل بعد، معامله‌ای که فقط ۰٫۰۲R پیشرفت کرده — نباید بسته شود.
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_010.0,
            now = t0 + 500 * Interval.M5.millis,
        )
        assertFalse("زمان به‌تنهایی هرگز معامله را نمی‌بندد", evaluate.shouldClose)
        assertEquals("", evaluate.reasonFa)
    }

    @Test
    fun `the timer only works when someone explicitly asks for it`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_010.0,
            now = t0 + 20 * Interval.M5.millis,
            maxHoldBars = 20, // صریح، فقط برای آزمایش A/B
        )
        assertTrue(evaluate.shouldClose)
        assertTrue(evaluate.reasonFa.contains("خروج زمانی"))
    }

    @Test
    fun `time stop keeps a trade that already made enough R`() {
        val t = trade()
        val evaluate = IchimokuStrategyEngine.evaluateTimeStop(
            trade = t,
            livePrice = 90_400.0, // +۰.۶۷R
            now = t0 + 40 * Interval.M5.millis,
            maxHoldBars = 20,
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
                maxHoldBars = 20,
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
            maxHoldBars = 20,
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
    fun `the experimental timer is measured in bars of the entry timeframe`() {
        val h1 = trade(interval = Interval.H1)
        assertFalse(
            IchimokuStrategyEngine.evaluateTimeStop(
                trade = h1,
                livePrice = 90_010.0,
                now = t0 + 19 * Interval.H1.millis,
                maxHoldBars = 20,
            ).shouldClose,
        )
        assertTrue(
            IchimokuStrategyEngine.evaluateTimeStop(
                trade = h1,
                livePrice = 90_010.0,
                now = t0 + 20 * Interval.H1.millis,
                maxHoldBars = 20,
            ).shouldClose,
        )
    }

    // ───────────────── سقف‌های قابل تنظیم (جای سخت‌کد) ─────────────────

    @Test
    fun `settings expose no position ceilings by default`() {
        val defaults = AppSettings()
        // درخواست کاربر: سقف پوزیشن باز یک «دلیل اضافهٔ رد معامله» بود و برداشته شد.
        assertEquals(0, defaults.maxOpenTrades)
        assertEquals(0, defaults.maxOpenTradesPerSymbol)
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
}
