package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های لایهٔ رفتاری «عادت‌های معاملاتی» — هر تست، یکی از «تست»های کارت قاعده در
 * سند انتقال دانش v3 است (Rule 1..Rule 39 از کتاب Steve Burns و Holly Burns).
 *
 * این‌جا فقط عدد و قاعده تست می‌شود؛ هیچ ادعای سودآوری‌ای نیست. ادعای «ضرر نکن» جای دیگری
 * سنجیده می‌شود: آزمایشگاه دادهٔ ساختگی `tools/strategy_lab/Habits.kt` با مونت‌کارلو.
 */
class TradingHabitsPolicyTest {

    private fun trade(
        id: String,
        symbol: String = "XAU/USD",
        action: SignalAction = SignalAction.BUY,
        entry: Double = 2_400.0,
        fixedRiskUsd: Double = 100.0,
        pnlUsd: Double? = null,
        closedAt: Long? = null,
    ) = PaperTrade(
        id = id,
        symbol = symbol,
        interval = Interval.M15,
        action = action,
        entry = entry,
        stopLoss = if (action == SignalAction.BUY) entry - 2.0 else entry + 2.0,
        takeProfit = if (action == SignalAction.BUY) entry + 4.0 else entry - 4.0,
        riskReward = 2.0,
        confidence = 85.0,
        openedAt = 1_760_000_000_000L,
        closedAt = closedAt,
        pnlUsd = pnlUsd,
        fixedRiskUsd = fixedRiskUsd,
    )

    private fun request(
        riskPct: Double = 0.5,
        rr: Double = 2.0,
        atr: Double = 1.0,
        stopDistance: Double = 2.0,
        equity: Double = 100_000.0,
        peak: Double = 100_000.0,
        openRiskPct: Double = 0.0,
        lossStreak: Int = 0,
        setupScore: Double = 90.0,
        openTrades: List<PaperTrade> = emptyList(),
        livePrice: Double = 2_400.0,
        hasSignal: Boolean = true,
        limits: TradingHabitsPolicy.Limits = TradingHabitsPolicy.Limits(),
    ): TradingHabitsPolicy.EntryRequest {
        val entry = 2_400.0
        val stop = entry - stopDistance
        val tp = entry + stopDistance * rr
        return TradingHabitsPolicy.EntryRequest(
            symbol = "XAU/USD",
            action = SignalAction.BUY,
            entry = entry,
            stopLoss = stop,
            takeProfit = tp,
            atr = atr,
            equityUsd = equity,
            peakEquityUsd = peak,
            requestedRiskPct = riskPct,
            openRiskPct = openRiskPct,
            lossStreak = lossStreak,
            setupScorePct = setupScore,
            livePrice = livePrice,
            openTrades = openTrades,
            hasQuantifiedSignal = hasSignal,
            limits = limits,
        )
    }

    // ── R01: جدول نرخ برد لازم ────────────────────────────────────────────────

    @Test
    fun `R01 break even win rate matches the book table`() {
        assertEquals(50.0, TradingHabitsPolicy.neededWinRatePct(1.0), 0.01)
        assertEquals(33.33, TradingHabitsPolicy.neededWinRatePct(2.0), 0.01)
        assertEquals(25.0, TradingHabitsPolicy.neededWinRatePct(3.0), 0.01)
        assertEquals(16.67, TradingHabitsPolicy.neededWinRatePct(5.0), 0.01)
    }

    @Test
    fun `R01 system with 1 to 2 and 30 percent win rate is losing`() {
        // تست کارت R01: R:R=1:2 با نرخ برد ۳۰٪ باید زیان‌ده شناخته شود.
        val need = TradingHabitsPolicy.neededWinRatePct(2.0)
        assertTrue("۳۰٪ کمتر از حد لازم ${need}٪ است", 30.0 < need)
    }

    // ── R03: کف پاداش ─────────────────────────────────────────────────────────

    @Test
    fun `R03 plan with 1 to 1 reward is rejected`() {
        val d = TradingHabitsPolicy.evaluate(request(rr = 1.0))
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R03") })
    }

    @Test
    fun `R03 plan with 2R or more is accepted`() {
        val d = TradingHabitsPolicy.evaluate(request(rr = 2.4))
        assertTrue(d.allowed)
    }

    // ── R02 / R22: سیگنال کمّی ────────────────────────────────────────────────

    @Test
    fun `R02 entry without a quantified signal is rejected`() {
        val d = TradingHabitsPolicy.evaluate(request(hasSignal = false))
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R02") })
    }

    // ── R23: افت از قله ───────────────────────────────────────────────────────

    @Test
    fun `R23 drawdown is measured from the peak not from initial capital`() {
        // مثال کتاب: ۱۰۰ → ۱۱۰ → ۹۹ یعنی افت ۱۰٪.
        assertEquals(10.0, TradingHabitsPolicy.drawdownFromPeakPct(99.0, 110.0), 0.001)
        // همان حساب اگر از سرمایهٔ اولیه سنجیده شود ۱٪ می‌دهد — عدد غلط.
        assertEquals(1.0, TradingHabitsPolicy.drawdownFromPeakPct(99.0, 100.0), 0.001)
    }

    @Test
    fun `R23 drawdown of five percent halves the size`() {
        val flat = TradingHabitsPolicy.evaluate(request())
        val drawn = TradingHabitsPolicy.evaluate(request(equity = 94_000.0, peak = 100_000.0))
        assertEquals(1.0, flat.sizeMultiplier, 1e-9)
        assertEquals(0.5, drawn.sizeMultiplier, 1e-9)
        assertTrue(drawn.allowed)
        assertTrue(drawn.notesFa.any { it.startsWith("R23") })
    }

    @Test
    fun `R23 drawdown beyond tolerance locks auto entry`() {
        val d = TradingHabitsPolicy.evaluate(
            request(equity = 80_000.0, peak = 100_000.0),
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R23") })
    }

    // ── R29: ریاضیات جبران ────────────────────────────────────────────────────

    @Test
    fun `R29 fifty percent drawdown needs one hundred percent return`() {
        assertEquals(100.0, TradingHabitsPolicy.recoveryReturnPct(50.0), 0.01)
        assertEquals(25.0, TradingHabitsPolicy.recoveryReturnPct(20.0), 0.01)
    }

    // ── R31: پلهٔ سایز بر اساس کیفیت ──────────────────────────────────────────

    @Test
    fun `R31 grade B setup never takes the maximum size`() {
        val a = TradingHabitsPolicy.evaluate(request(setupScore = 92.0))
        val b = TradingHabitsPolicy.evaluate(request(setupScore = 70.0))
        val c = TradingHabitsPolicy.evaluate(request(setupScore = 40.0))
        assertEquals(1.0, a.sizeMultiplier, 1e-9)
        assertEquals(0.5, b.sizeMultiplier, 1e-9)
        assertEquals(0.25, c.sizeMultiplier, 1e-9)
        assertEquals(TradingHabitsPolicy.SetupGrade.A, a.grade)
        assertEquals(TradingHabitsPolicy.SetupGrade.B, b.grade)
        assertEquals(TradingHabitsPolicy.SetupGrade.C, c.grade)
    }

    // ── R32: سقف ۱٪ ───────────────────────────────────────────────────────────

    @Test
    fun `R32 risk above one percent is clamped and never blocks the trade`() {
        val d = TradingHabitsPolicy.evaluate(request(riskPct = 3.0))
        assertTrue(d.allowed)
        assertEquals(1.0, d.effectiveRiskPct, 1e-9)
        assertEquals(1_000.0, d.riskUsd, 0.01)
        assertTrue(d.notesFa.any { it.startsWith("R32") })
    }

    @Test
    fun `R32 one percent risk means one thousand dollars on a hundred thousand account`() {
        // مثال کتاب: حساب ۱۰۰هزار دلاری → حداکثر ۱٬۰۰۰ دلار ضرر؛ با فاصلهٔ ۲ دلار → ۵۰۰ سهم.
        val riskUsd = 100_000.0 * (TradingHabitsPolicy.MAX_RISK_PER_TRADE_PCT / 100.0)
        assertEquals(1_000.0, riskUsd, 0.001)
        assertEquals(500.0, riskUsd / 2.0, 0.001)
    }

    // ── R34: سقف ۳٪ ریسک باز ──────────────────────────────────────────────────

    @Test
    fun `R34 a fourth one percent position is rejected`() {
        val d = TradingHabitsPolicy.evaluate(
            request(riskPct = 1.0, openRiskPct = 2.5),
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R34") })
    }

    @Test
    fun `R34 many small positions still fit under three percent`() {
        val d = TradingHabitsPolicy.evaluate(request(riskPct = 0.25, openRiskPct = 0.5))
        assertTrue(d.allowed)
        assertEquals(0.75, d.heatPct, 1e-9)
    }

    // ── R35: سری باخت ─────────────────────────────────────────────────────────

    @Test
    fun `R35 after three losses the size is halved`() {
        val d = TradingHabitsPolicy.evaluate(request(lossStreak = 3))
        assertEquals(0.5, d.sizeMultiplier, 1e-9)
        assertTrue(d.notesFa.any { it.startsWith("R35") })
    }

    @Test
    fun `R35 after five losses the size drops to a quarter and never rises`() {
        val three = TradingHabitsPolicy.evaluate(request(lossStreak = 3))
        val five = TradingHabitsPolicy.evaluate(request(lossStreak = 5))
        assertEquals(0.25, five.sizeMultiplier, 1e-9)
        assertTrue(five.sizeMultiplier < three.sizeMultiplier)
    }

    @Test
    fun `R35 loss streak counting stops at the first win`() {
        val trades = listOf(
            trade("1", pnlUsd = -100.0, closedAt = 3_000L),
            trade("2", pnlUsd = -100.0, closedAt = 2_000L),
            trade("3", pnlUsd = +300.0, closedAt = 1_000L),
            trade("4", pnlUsd = -100.0, closedAt = 500L),
        )
        assertEquals(2, TradingHabitsPolicy.lossStreak(trades))
    }

    // ── R36: میانگین‌گیری روی ضرر ────────────────────────────────────────────

    @Test
    fun `R36 adding to a losing position is rejected`() {
        val openLoser = trade("open", entry = 2_410.0)
        val d = TradingHabitsPolicy.evaluate(
            request(openTrades = listOf(openLoser), livePrice = 2_400.0),
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R36") })
    }

    @Test
    fun `R36 adding to a winning position is not blocked by this rule`() {
        val openWinner = trade("open", entry = 2_390.0)
        val d = TradingHabitsPolicy.evaluate(
            request(openTrades = listOf(openWinner), livePrice = 2_400.0),
        )
        assertTrue(d.reasonsFa.none { it.startsWith("R36") })
    }

    // ── R38 / بخش ۴: ریسک مؤثر ───────────────────────────────────────────────

    @Test
    fun `R38 effective risk adds fifty percent volatility shock and slippage`() {
        val perUnit = TradingHabitsPolicy.effectiveRiskPerUnit(
            entry = 2_400.0, stopLoss = 2_398.0, slippageBps = 0.0,
        )
        assertEquals(3.0, perUnit, 1e-9) // ۲ دلار × ۱٫۵
    }

    @Test
    fun `R38 slippage makes the effective risk slightly larger`() {
        val withSlip = TradingHabitsPolicy.effectiveRiskPerUnit(2_400.0, 2_398.0, slippageBps = 10.0)
        val noSlip = TradingHabitsPolicy.effectiveRiskPerUnit(2_400.0, 2_398.0, slippageBps = 0.0)
        assertTrue(withSlip > noSlip)
    }

    @Test
    fun `R38 high volatility halves the size`() {
        // ATR = ۱٪ قیمت → بالاتر از آستانهٔ ۰٫۳٪ → سایز نصف
        val calm = TradingHabitsPolicy.evaluate(request(atr = 1.0))       // ATR = 0.04%
        val wild = TradingHabitsPolicy.evaluate(request(atr = 24.0))      // ATR = 1% قیمت
        assertEquals(1.0, calm.sizeMultiplier, 1e-9)
        assertEquals(0.5, wild.sizeMultiplier, 1e-9)
        assertTrue(wild.notesFa.any { it.startsWith("R38") })
        assertEquals(1.0, wild.atrPctOfPrice, 0.001)
    }

    // ── R39: حد ضرر بیرون از نویز ─────────────────────────────────────────────

    @Test
    fun `R39 stop tighter than one ATR is rejected`() {
        val d = TradingHabitsPolicy.evaluate(request(stopDistance = 0.8, atr = 1.0))
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.startsWith("R39") })
    }

    @Test
    fun `R39 stop wider than one ATR passes`() {
        val d = TradingHabitsPolicy.evaluate(request(stopDistance = 1.6, atr = 1.0))
        assertTrue(d.allowed)
        assertEquals(1.6, d.stopAtrRatio, 1e-9)
    }

    // ── R28 / R30 / R37: مدیریت معاملهٔ باز ──────────────────────────────────

    @Test
    fun `R37 before one R the stop does not move`() {
        val plan = TradingHabitsPolicy.trailStop(
            action = SignalAction.BUY, entry = 2_400.0, currentStopLoss = 2_398.0,
            currentR = 0.9, ema5 = 2_410.0,
        )
        assertFalse(plan.shouldMove)
    }

    @Test
    fun `R37 after one R the stop locks at breakeven`() {
        val plan = TradingHabitsPolicy.trailStop(
            action = SignalAction.BUY, entry = 2_400.0, currentStopLoss = 2_398.0,
            currentR = 1.2, ema5 = 0.0,
        )
        assertTrue(plan.shouldMove)
        assertEquals(2_400.0, plan.newStopLoss ?: 0.0, 1e-9)
        assertTrue(plan.lockBreakeven)
    }

    @Test
    fun `R28 trailing follows EMA5 when it is stronger`() {
        val plan = TradingHabitsPolicy.trailStop(
            action = SignalAction.BUY, entry = 2_400.0, currentStopLoss = 2_398.0,
            currentR = 2.0, ema5 = 2_415.0,
        )
        assertEquals(2_415.0, plan.newStopLoss ?: 0.0, 1e-9)
    }

    @Test
    fun `R30 the stop is never moved away from price`() {
        assertTrue(TradingHabitsPolicy.movesStopAway(SignalAction.BUY, 2_398.0, 2_390.0))
        assertFalse(TradingHabitsPolicy.movesStopAway(SignalAction.BUY, 2_398.0, 2_405.0))
        // و تریلینگ هرگز حد ضرر را دورتر نمی‌برد، حتی اگر EMA پایین‌تر باشد.
        val plan = TradingHabitsPolicy.trailStop(
            action = SignalAction.BUY, entry = 2_400.0, currentStopLoss = 2_420.0,
            currentR = 3.0, ema5 = 2_410.0,
        )
        assertFalse(plan.shouldMove)
    }

    // ── حرارت پورتفو (بخش ۴) ─────────────────────────────────────────────────

    @Test
    fun `portfolio heat uses correlation instead of a plain sum`() {
        val heat = TradingHabitsPolicy.portfolioHeatPct(listOf(1.0, 1.0), correlation = 0.8)
        assertEquals(1.897, heat, 0.01)
    }

    // ── قلهٔ سرمایه و ریسک باز ───────────────────────────────────────────────

    @Test
    fun `peak equity follows the best realised point`() {
        val trades = listOf(
            trade("1", pnlUsd = +10_000.0, closedAt = 1_000L),
            trade("2", pnlUsd = -11_000.0, closedAt = 2_000L),
        )
        assertEquals(110_000.0, TradingHabitsPolicy.peakEquityUsd(trades, 100_000.0), 0.01)
    }

    @Test
    fun `open risk percent sums the live trades only`() {
        val open = listOf(trade("o", fixedRiskUsd = 500.0), trade("c", fixedRiskUsd = 900.0, pnlUsd = 1.0, closedAt = 5L))
        assertEquals(0.5, TradingHabitsPolicy.openRiskPct(open, 100_000.0), 1e-9)
    }

    // ── لایه خاموش = رفتار قبلی ──────────────────────────────────────────────

    @Test
    fun `enabled layer blocks a plan that violates the book`() {
        val d = TradingHabitsPolicy.evaluate(
            request(riskPct = 4.0, rr = 0.5, hasSignal = false, stopDistance = 0.1, atr = 1.0),
        )
        assertFalse(d.allowed)
        assertTrue(d.blockedFa)
        // سه تخلف هم‌زمان: R02 (بدون سیگنال)، R03 (R:R)، R39 (حد ضرر داخل نویز)
        assertTrue(d.reasonsFa.any { it.startsWith("R02") })
        assertTrue(d.reasonsFa.any { it.startsWith("R03") })
        assertTrue(d.reasonsFa.any { it.startsWith("R39") })
    }

    @Test
    fun `disabled layer via limits keeps the requested risk`() {
        val d = TradingHabitsPolicy.evaluate(
            request(
                riskPct = 4.0, rr = 0.5, stopDistance = 0.1, atr = 1.0,
                limits = TradingHabitsPolicy.Limits(enabled = false),
            ),
        )
        assertTrue(d.allowed)
        assertEquals(4.0, d.effectiveRiskPct, 1e-9)
    }

    // ── ممیزی معاملات بسته‌شده ───────────────────────────────────────────────

    @Test
    fun `audit flags a loss larger than one R`() {
        val audit = TradingHabitsPolicy.auditClosed(
            listOf(
                trade("a", fixedRiskUsd = 100.0, pnlUsd = -160.0, closedAt = 1_000L),
                trade("b", fixedRiskUsd = 100.0, pnlUsd = +250.0, closedAt = 2_000L),
            ),
        )
        assertEquals(2, audit.trades)
        assertTrue(audit.violationsFa.any { it.contains("۱٫۱R") })
        assertEquals(50.0, audit.winRatePct, 0.01)
    }

    @Test
    fun `audit of an empty journal is neutral`() {
        val audit = TradingHabitsPolicy.auditClosed(emptyList())
        assertEquals(0, audit.trades)
        assertFalse(audit.expectancyPositive)
        assertTrue(audit.violationsFa.isEmpty())
    }
}
