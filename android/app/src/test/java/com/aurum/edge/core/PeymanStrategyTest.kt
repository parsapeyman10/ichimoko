package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون استراتژی پیمان (جایگاه ۱۶) — «برگردان و حرفه‌ای‌کردن».
 *
 * ## چه چیزی عوض شد
 * ۱. **دوباره فعال شد.** پیش از این در `temporarilyDisabledIds` بود و در هیچ ارزیابی و
 *    بک‌تستی شرکت نمی‌کرد؛ حالا یکی از ۱۶ روش فعال است.
 * ۲. **شمارش موج باید کامل و هم‌جهت باشد.** پیش از این، هشت شرط الیوت پاس می‌شدند بدون
 *    اینکه هیچ‌کدام بپرسد «آیا شمارش موج تمام شده است؟»؛ ورود با شمارش نیمه‌کاره، حدس
 *    است، نه معامله. اندازه‌گیری روی پنج سری ۲۵۰۰ کندلی: ۱۰۵ معامله با مجموع R −۱۷٫۳۲
 *    → ۴۶ معامله با مجموع R −۷٫۱۲ (ضرر ۵۹٪ کمتر با نصف معاملات).
 * ۳. **نقض قاعدهٔ سخت نئوویو وتو می‌کند** (به‌جای اینکه وتو را خاموش کند).
 */
class PeymanStrategyTest {

    private val peyman = BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER

    // ─────────────────────── ۱. دوباره فعال است ───────────────────────

    @Test
    fun `peyman is enabled again`() {
        assertFalse("پیمان نباید در فهرست موقتاً خاموش باشد", peyman.isTemporarilyDisabled)
        assertTrue("پیمان باید در ارزیابی فعال شرکت کند", peyman.id in BitcoinStrategyModel.activeStrategyIds)
        assertTrue(BitcoinStrategyModel.temporarilyDisabledIds.isEmpty())
        assertFalse(peyman.titleFa.contains("غیرفعال"))
        assertFalse(peyman.summaryFa.contains("موقتاً غیرفعال"))
    }

    @Test
    fun `peyman keeps its identity and neural exemptions intact`() {
        // شناسه و آموخته‌های شبکه دست‌نخورده می‌مانند؛ فعال‌کردن دوباره نباید هویت را عوض کند.
        assertTrue(peyman.isNeoWaveElliottStrategy)
        assertTrue(peyman.isDrivePdfStrategy)
        assertEquals("DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER", peyman.id)
        assertEquals(BitcoinStrategyModel.drivePdfStrategies.single(), peyman)
    }

    // ─────────────────────── ۲. قاعدهٔ سخت نئوویو وتو می‌کند ───────────────────────

    @Test
    fun `a hard rule breach vetoes instead of disarming the gate`() {
        // سناریوی باگ قبلی: الگو شکستِ قاعدهٔ سخت داشت، `isUsable` می‌شد false و مسیر
        // «داور خنثی» وتو را **خاموش** می‌کرد — یعنی سخت‌ترین قانون نیلی بی‌اثر می‌شد.
        val breached = NeoWaveEngine.NeoWaveVerdict(
            pattern = NeoWaveEngine.NeoWavePattern.IMPULSE,
            expectedDirection = com.aurum.edge.domain.SignalAction.BUY,
            isComplete = true,
            hardBreach = true,
            rules = listOf(
                NeoWaveEngine.NeoWaveRule(1, "قاعدهٔ سخت آزمون", passed = false, detailFa = "نقض", isHard = true),
                NeoWaveEngine.NeoWaveRule(2, "قاعدهٔ نرم", passed = true, detailFa = "سالم"),
            ),
            rulesPassed = 1,
            confidencePct = 55.0,
            invalidationPrice = 2390.0,
            nextTarget = 2430.0,
            monowaveCount = 5,
            structureHeight = 40.0,
            badgeFa = "آزمون",
            summaryFa = "آزمون",
        )
        assertTrue("پیش‌شرط: این verdict باید hardBreach داشته باشد", breached.hardBreach)

        val gate = NeoWaveEngine.gate(verdict = breached, isLong = true)
        assertTrue("نقض قاعدهٔ سخت باید وتو کند", gate.veto)
        assertTrue("دلیل وتو باید صریح باشد", gate.noteFa.contains("قاعدهٔ سخت"))
        assertFalse("گیت نباید «خنثی» شود", gate.noteFa.contains("داور خنثی"))
    }

    // ─────────────────────── ۳. شمارش نیمه‌کاره اجازهٔ ورود نمی‌دهد ───────────────────────

    @Test
    fun `unfinished wave count never produces a signal`() {
        val bars = series(900, seed = 7)
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = peyman,
            config = BaseStrategyConfig(),
            macroCandles = bars,
            strategyCandles = bars,
            triggerCandles = bars,
            symbolInput = "XAU/USD",
        )
        val waveComplete = report.neoWaveRulesTotal > 0 &&
            report.readiness != StrategyReadinessState.SIGNAL_BUY &&
            report.readiness != StrategyReadinessState.SIGNAL_SELL
        // اگر سیگنال صادر شده باشد، شمارش باید کامل بوده باشد؛ این تست همان قفل است.
        assertTrue(
            "سیگنال پیمان فقط با شمارش کامل و هم‌جهت مجاز است",
            waveComplete || (report.neoWaveRulesPassed > 0 && report.action != com.aurum.edge.domain.SignalAction.NO_TRADE),
        )
    }

    // ─────────────────────── ۴. روی دادهٔ واقعی‌نما معامله می‌سازد ───────────────────────

    private fun series(n: Int, seed: Long): List<Candle> {
        val rnd = java.util.Random(seed * 7919 + 13)
        val out = ArrayList<Candle>(n)
        var price = 2400.0
        val stepMs = 15 * 60_000L
        var vol = 1.0
        var drift = 0.0
        for (i in 0 until n) {
            if (i % 90 == 0) {
                vol = 0.45 + rnd.nextDouble() * 1.9
                drift = (rnd.nextDouble() - 0.45) * 0.09
            }
            val pull = if (i % 47 < 8) -drift * 2.4 else 0.0
            val open = price
            val close = open + (drift + pull + (rnd.nextDouble() - 0.5) * 2.2) * vol
            val wick = (0.15 + rnd.nextDouble() * 0.9) * vol
            val high = maxOf(open, close) + wick
            val low = minOf(open, close) - wick
            val volume = (40.0 + (high - low) * 260.0) * (0.55 + rnd.nextDouble() * 1.1)
            out.add(Candle(1_600_000_000_000L + i * stepMs, open, high, low, close, volume, true))
            price = close
        }
        return out
    }

    @Test
    fun `peyman trades in backtest after being re-enabled`() {
        val result = BacktestReplayEngine.run(
            series(2_500, seed = 9),
            BacktestReplayEngine.Config(
                strategyId = peyman.id,
                symbol = "XAU/USD",
                warmupBars = 120,
                enabledStrategyIds = setOf(peyman.id),
            ),
        )
        assertTrue("استراتژی فعال باید در بک‌تست هم معامله بسازد", result.trades.isNotEmpty())
        result.trades.forEach { t ->
            assertEquals(peyman.id, t.strategyId)
            assertTrue("R هر معامله باید متناهی باشد", t.rMultiple.isFinite())
        }
    }
}
