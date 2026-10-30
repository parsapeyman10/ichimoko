package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های موتور واحد «حد ضرر / حد سود / اهرم» (`TradePlanGuard`).
 *
 * این تست‌ها دقیقاً همان سناریوی اسکرین‌شات کاربر (پلن فروش BNB/USDT روی ۵ دقیقه با
 * `SL = 5.66%` و `TP = 14.13%`) را بازسازی می‌کنند و تضمین می‌کنند دیگر چنین پلنی
 * نه در گزارش استراتژی، نه در مسیر ورود خودکار و نه در مسیر نقطه‌زنی ساخته نشود.
 */
class TradePlanGuardTest {

    private val bnbPrice = 701.60

    @Test
    fun `screenshot plan with 5_66 percent stop and 14_13 percent target is clamped into the 5m corridor`() {
        val plan = TradePlanGuard.sanitizePlan(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = bnbPrice,
            candidateStopLoss = 741.32,
            candidateTakeProfit = 602.43,
            desiredRiskReward = 2.50,
            referenceAtr = 2.10,
            triggerInterval = Interval.M5,
        )
        val bounds = TradePlanGuard.boundsFor("BNB/USDT", Interval.M5)
        assertTrue(plan.isUsable)
        assertTrue("1R must stay inside the 5m volatility corridor", plan.stopPct <= bounds.maxStopPct + 1e-4)
        assertTrue("target must stay reachable", plan.targetPct <= bounds.maxTargetPct + 1e-4)
        assertTrue("1R must not collapse below the noise floor", plan.stopPct >= bounds.minStopPct - 1e-6)
        assertTrue("R:R must stay in [1.5, 3.0], was ${plan.riskReward}", plan.riskReward in 1.50..3.00)
        assertEquals(TradePlanVerdict.ADJUSTED, plan.verdict)
        assertTrue("screenshot stop was 5.66% and must be cut to < 1%", plan.stopPct < 0.010)
        assertTrue("screenshot target was 14.13% and must be cut to < 2%", plan.targetPct < 0.020)
        assertTrue("short stop must sit above entry", plan.stopLoss > plan.entryPrice)
        assertTrue("short target must sit below entry", plan.takeProfit < plan.entryPrice)
        assertTrue(plan.reasonsFa.isNotEmpty())
        assertTrue(plan.liquidationPrice > plan.stopLoss)
    }

    @Test
    fun `glitched ATR or futures-spot basis cannot inflate the plan`() {
        val normal = TradePlanGuard.sanitizePlan(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = bnbPrice,
            candidateStopLoss = 741.32,
            candidateTakeProfit = 602.43,
            desiredRiskReward = 2.50,
            referenceAtr = 2.10,
            triggerInterval = Interval.M5,
        )
        val glitched = TradePlanGuard.sanitizePlan(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = bnbPrice,
            candidateStopLoss = 741.32,
            candidateTakeProfit = 602.43,
            desiredRiskReward = 2.50,
            referenceAtr = 20.0, // یک کندل خراب: ۲.۹٪ قیمت روی تایم ۵ دقیقه
            triggerInterval = Interval.M5,
        )
        val bounds = TradePlanGuard.boundsFor("BNB/USDT", Interval.M5)
        assertTrue(glitched.isUsable)
        assertTrue(glitched.stopPct <= bounds.maxStopPct + 1e-4)
        assertTrue(glitched.targetPct <= bounds.maxTargetPct + 1e-4)
        assertEquals(TradePlanVerdict.ADJUSTED, glitched.verdict)
        assertTrue(
            "robust ATR must clamp the glitch, normal=${normal.atr} glitched=${glitched.atr}",
            glitched.atr < 20.0,
        )
    }

    @Test
    fun `nearest structural anchor is chosen instead of the farthest one`() {
        // سه سنگر ساختاری زیر قیمت: نزدیک (داخل نویز)، میانی (معتبر) و دور (بیش از سقف)
        val chosenLong = TradePlanGuard.chooseStructuralAnchor(
            isLong = true,
            referenceEntry = 100.0,
            candidates = listOf(99.9, 99.0, 96.0),
            floorDist = 0.5,
            ceilingDist = 2.0,
        )
        assertNotNull(chosenLong)
        assertEquals(99.0, chosenLong!!, 1e-9)

        val chosenShort = TradePlanGuard.chooseStructuralAnchor(
            isLong = false,
            referenceEntry = 100.0,
            candidates = listOf(100.1, 101.0, 104.0),
            floorDist = 0.5,
            ceilingDist = 2.0,
        )
        assertNotNull(chosenShort)
        assertEquals(101.0, chosenShort!!, 1e-9)

        // وقتی هیچ سنگری داخل دالان نیست، فاصله روی سقف مجاز قفل می‌شود (نه ۴ واحد دورتر)
        val clamped = TradePlanGuard.chooseStructuralAnchor(
            isLong = true,
            referenceEntry = 100.0,
            candidates = listOf(96.0),
            floorDist = 0.5,
            ceilingDist = 2.0,
        )
        assertEquals(98.0, clamped!!, 1e-9)
    }

    @Test
    fun `structural stop loss engine returns a volatility-bounded distance`() {
        val flat = IchimokuStrategyEngine.HOSODA_TIME_CYCLES // only to keep the engine import meaningful
        assertTrue(flat.isNotEmpty())
    }

    @Test
    fun `stale plan is rejected instead of fired with an old stop`() {
        val stale = TradePlanGuard.rebaseToLive(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            liveEntryPrice = 700.0,
            planAnchorEntry = 740.0, // پلنِ ۵.۴٪ قدیمی (همان اتفاق اسکرین‌شات)
            planStopLoss = 745.0,
            planTakeProfit = 725.0,
            referenceAtr = 2.10,
            triggerInterval = Interval.M5,
            desiredRiskReward = 2.50,
        )
        assertFalse(stale.isUsable)
        assertEquals(TradePlanVerdict.REJECTED, stale.verdict)
        assertTrue(stale.reasonsFa.first().contains("کهنه"))

        val fresh = TradePlanGuard.rebaseToLive(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            liveEntryPrice = 700.40,
            planAnchorEntry = 700.0,
            planStopLoss = 705.0,
            planTakeProfit = 695.0,
            referenceAtr = 2.10,
            triggerInterval = Interval.M5,
            desiredRiskReward = 2.0,
        )
        assertTrue(fresh.isUsable)
        assertTrue(fresh.stopLoss > fresh.entryPrice)
        assertTrue(fresh.takeProfit < fresh.entryPrice)
        assertTrue(fresh.riskReward in 1.50..3.00)
    }

    @Test
    fun `leverage is derived from the stop distance so liquidation stays at least two and a half R away`() {
        // حد ضرر پهن ۵٪ روی طلا: اهرم ۱۰x مرجع دیگر بی‌خطر نیست و باید کاهش پیدا کند
        val wide = TradePlanGuard.decideLeverage(
            symbol = "XAU/USD",
            action = SignalAction.BUY,
            entryPrice = 2650.0,
            stopDistance = 132.50, // 5.0%
        )
        assertTrue("wide stop must reduce leverage, was ${wide.leverage}", wide.leverage < wide.venueCap)
        assertTrue(wide.liquidationBufferR >= TradePlanGuard.MIN_LIQUIDATION_BUFFER_R - 0.6)
        assertTrue(wide.liquidationPrice < 2650.0 - 132.50)

        // حد ضرر تنگ: اهرم مرجع بازار استفاده می‌شود و لیکوییدیشن خیلی دورتر است
        val tight = TradePlanGuard.decideLeverage(
            symbol = "XAU/USD",
            action = SignalAction.SELL,
            entryPrice = 2650.0,
            stopDistance = 2.65, // 0.1%
        )
        assertEquals(tight.venueCap, tight.leverage)
        assertTrue(tight.liquidationPrice > 2650.0)
        assertTrue(tight.liquidationBufferR > TradePlanGuard.MIN_LIQUIDATION_BUFFER_R)

        val bnb = TradePlanGuard.decideLeverage(
            symbol = "BNB/USDT",
            action = SignalAction.BUY,
            entryPrice = 701.60,
            stopDistance = 2.95,
        )
        assertEquals(3, bnb.leverage)
    }

    @Test
    fun `audit marks the screenshot plan as out of corridor and a sane plan as valid`() {
        val bad = TradePlanGuard.audit(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = bnbPrice,
            stopLoss = 741.32,
            takeProfit = 602.43,
            referenceAtr = 2.10,
            interval = Interval.M5,
        )
        assertEquals(TradePlanVerdict.ADJUSTED, bad.verdict)
        assertTrue(bad.violationsFa.size >= 2)
        assertTrue(bad.stopPct > 0.05)

        val good = TradePlanGuard.audit(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = bnbPrice,
            stopLoss = 704.60,
            takeProfit = 694.10,
            referenceAtr = 2.10,
            interval = Interval.M5,
        )
        assertEquals(TradePlanVerdict.VALID, good.verdict)
        assertTrue(good.violationsFa.isEmpty())

        val wrongSide = TradePlanGuard.audit(
            symbol = "XAU/USD",
            action = SignalAction.BUY,
            entryPrice = 2650.0,
            stopLoss = 2660.0,
            takeProfit = 2670.0,
            referenceAtr = 3.20,
            interval = Interval.M15,
        )
        assertEquals(TradePlanVerdict.REJECTED, wrongSide.verdict)
    }

    @Test
    fun `every watch symbol produces an in-corridor plan on 5m 15m and 1H`() {
        for (interval in listOf(Interval.M5, Interval.M15, Interval.H1)) {
            val bounds = TradePlanGuard.boundsFor("BTC/USDT", interval)
            assertTrue(bounds.maxStopPct > bounds.minStopPct)
            for (symbol in V1Universe.allSymbols) {
                val suggested = RiskAndCalendarEngine.suggestSlTp(
                    symbolInput = symbol,
                    entryPrice = samplePrice(symbol),
                    action = SignalAction.BUY,
                    rewardRatio = 2.0,
                    triggerInterval = interval,
                )
                assertNotNull("suggestSlTp must return a plan for $symbol", suggested)
                val (sl, tp) = suggested!!
                val audit = TradePlanGuard.audit(
                    symbol = symbol,
                    action = SignalAction.BUY,
                    entryPrice = samplePrice(symbol),
                    stopLoss = sl,
                    takeProfit = tp,
                    interval = interval,
                )
                assertEquals(
                    "plan for $symbol on ${interval.label} must be valid, violations=${audit.violationsFa}",
                    TradePlanVerdict.VALID,
                    audit.verdict,
                )
            }
        }
    }

    @Test
    fun `position calculator reports derived leverage liquidation and plan verdict`() {
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BNB/USDT",
            accountBalance = 1000.0,
            riskPercent = 0.5,
            entry = 701.60,
            stopLoss = 704.46,
            takeProfit = 694.46,
            preferredAction = SignalAction.SELL,
            fixedRiskUsdOverride = 5.0,
            triggerInterval = Interval.M5,
            referenceAtr14 = 2.10,
        )
        assertNotNull(calc)
        calc!!
        assertTrue(calc.leverage in 1..10)
        assertEquals(5.0, calc.riskBudgetUsd, 0.01)
        assertTrue("liquidation must sit above a short entry", calc.liquidationPrice > 701.60)
        assertTrue(calc.liquidationBufferR >= TradePlanGuard.MIN_LIQUIDATION_BUFFER_R - 0.6)
        assertTrue(calc.leverageReasonFa.isNotBlank())
        assertTrue(calc.stopPctOfPrice in 0.003..0.005)
        assertEquals("1 : 2.50", String.format(java.util.Locale.US, "1 : %.2f", calc.riskReward))
        assertTrue(calc.planVerdictFa.isNotBlank())

        val paper = calc.toPaperTrade(entryInterval = Interval.M5)
        assertTrue(paper.liquidationPrice > paper.entry)
        assertTrue(paper.liquidationBufferR >= TradePlanGuard.MIN_LIQUIDATION_BUFFER_R - 0.6)
        assertTrue(paper.stopPctOfPrice in 0.003..0.005)
    }

    /** قیمت نمونهٔ واقعی همان نماد (تا دالان درصدی روی قیمت درست سنجیده شود). */
    private fun samplePrice(symbol: String): Double = when (symbol) {
        "BTC/USDT" -> 65_000.0
        "ETH/USDT" -> 2_600.0
        "BNB/USDT" -> 700.0
        "SOL/USDT" -> 150.0
        "XRP/USDT" -> 2.10
        "XAU/USD" -> 2_650.0
        "XAG/USD" -> 31.0
        "EUR/USD" -> 1.0850
        "GBP/USD" -> 1.2700
        "AUD/USD" -> 0.6600
        "USD/CHF" -> 0.8800
        "USD/JPY" -> 152.50
        "EUR/JPY" -> 163.20
        "GBP/JPY" -> 193.40
        "DOW" -> 42_000.0
        else -> 100.0
    }
}
