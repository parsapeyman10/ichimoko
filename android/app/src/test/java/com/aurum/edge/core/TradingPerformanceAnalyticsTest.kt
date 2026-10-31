package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «کارنامهٔ کلی» بازطراحی‌شده (`TradingPerformanceAnalytics`) و بازبینی پلن‌های
 * ذخیره‌شدهٔ پوزیشن‌های باز (`TradePlanGuard.reviewStoredPlan`).
 *
 * اعداد انتظاری در این تست با یک اجرای واقعی موتور روی همان دادهٔ ثابت به دست آمده‌اند تا اگر
 * روزی فرمول شاخصی تغییر کند، کارنامه بی‌صدا عدد اشتباه نشان ندهد.
 */
class TradingPerformanceAnalyticsTest {

    private val dayMs = 86_400_000L
    private val nowMs = 1_790_000_000_000L

    private fun trade(
        id: String,
        symbol: String,
        pnl: Double?,
        risk: Double = 5.0,
        closedAt: Long? = nowMs,
        open: Boolean = false,
        interval: Interval = Interval.M5,
        action: SignalAction = SignalAction.SELL,
    ) = PaperTrade(
        id = id,
        symbol = symbol,
        interval = interval,
        action = action,
        entry = 100.0,
        stopLoss = 101.0,
        takeProfit = 97.5,
        confidence = 0.7,
        riskReward = 2.5,
        openedAt = (closedAt ?: nowMs) - 3_600_000L,
        closedAt = if (open) null else closedAt,
        exitPrice = if (open) null else 100.0,
        pnlUsd = if (open) null else pnl,
        fixedRiskUsd = risk,
        initialStopLoss = 101.0,
    )

    private fun sampleTrades(): List<PaperTrade> = listOf(
        trade("1", "BTC/USDT", +10.0, closedAt = nowMs - 10 * dayMs),
        trade("2", "BTC/USDT", -5.0, closedAt = nowMs - 9 * dayMs),
        trade("3", "XAUUSD", +7.5, closedAt = nowMs - 8 * dayMs),
        trade("4", "EURUSD", -5.0, closedAt = nowMs - 2 * dayMs),
        trade("5", "DOW", +12.0, closedAt = nowMs - dayMs),
        trade("6", "ETH/USDT", 0.0, open = true),
    )

    @Test
    fun `core stats follow the closed trades exactly`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs, startingBalanceUsd = 300.0)
        assertEquals(5, report.closedTrades)
        assertEquals(1, report.openTrades)
        assertEquals(19.5, report.netPnlUsd, 1e-9)
        assertEquals(3.9, report.totalR, 1e-9)
        assertEquals(0.78, report.avgRPerTrade, 1e-9)
        assertEquals(60.0, report.winRatePct, 1e-9)
        assertEquals(2.95, report.profitFactor ?: 0.0, 1e-9)
        assertEquals("پرداخت/ریسک باید میانگین برد تقسیم بر میانگین باخت باشد", 1.97, report.payoffRatio ?: 0.0, 0.01)
        assertEquals(3.9, report.expectancyUsdPerTrade, 1e-9)
    }

    @Test
    fun `drawdown, recovery factor and streaks are measured from the equity curve`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs, startingBalanceUsd = 300.0)
        assertEquals(5.0, report.maxDrawdownUsd, 1e-9)
        assertTrue("drawdown percent must be relative to peak equity", report.maxDrawdownPctOfPeak in 1.0..2.5)
        assertEquals(3.9, report.recoveryFactor ?: 0.0, 1e-9)
        assertEquals(1, report.maxConsecutiveWins)
        assertEquals(1, report.maxConsecutiveLosses)
        assertEquals("last closed trade is a win, so current streak must be +1", 1, report.currentStreak)
    }

    @Test
    fun `equity curve is cumulative and starts from the first trade only`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs)
        assertEquals(5, report.equityCurve.size)
        assertEquals(listOf(10.0, 5.0, 12.5, 7.5, 19.5), report.equityCurve.map { it.cumulativePnlUsd })
    }

    @Test
    fun `groups split by asset class, timeframe and direction`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs)
        val crypto = report.byAssetClass.firstOrNull { it.key == AssetClass.CRYPTO.code }
        assertNotNull(crypto)
        assertEquals(2, crypto!!.trades)
        assertEquals(5.0, crypto.netPnlUsd, 1e-9)
        assertEquals(1, report.byTimeframe.size)
        assertEquals(5, report.byTimeframe.first().trades)
        assertEquals(5, report.byDirection.first().trades)
        assertEquals("R buckets must cover every closed trade", 5, report.rDistribution.counts.sum())
    }

    @Test
    fun `7 and 30 day windows only count recent closed trades`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs)
        assertEquals(7.0, report.last7DaysPnlUsd, 1e-9)
        assertEquals(19.5, report.last30DaysPnlUsd, 1e-9)
    }

    @Test
    fun `verdict is descriptive only and never blocks anything`() {
        val report = TradingPerformanceAnalytics.analyze(sampleTrades(), nowMs)
        assertTrue(report.healthColorKey in setOf("GOOD", "WARN", "BAD"))
        assertTrue(report.healthVerdictFa.isNotBlank())
        assertTrue(report.headlineFa.contains("R"))
    }

    @Test
    fun `empty journal does not crash and reports zero`() {
        val report = TradingPerformanceAnalytics.analyze(emptyList(), nowMs)
        assertEquals(0, report.closedTrades)
        assertEquals(0.0, report.netPnlUsd, 1e-9)
        assertTrue(report.equityCurve.isEmpty())
        assertTrue(report.bySymbol.isEmpty())
    }

    @Test
    fun `stored plan review repairs the legacy 5_66 percent stop without flipping direction`() {
        val review = TradePlanGuard.reviewStoredPlan(
            symbol = "BNB/USDT",
            action = SignalAction.SELL,
            entryPrice = 700.0,
            stopLoss = 741.32,
            takeProfit = 602.43,
            triggerInterval = Interval.M5,
        )
        assertTrue("legacy corridor-breaking plan must be repaired", review.needsRepair)
        assertTrue(review.guarded.isUsable)
        val bounds = TradePlanGuard.boundsFor("BNB/USDT", Interval.M5)
        assertTrue(review.guarded.stopPct <= bounds.maxStopPct + 1e-4)
        assertTrue(review.guarded.stopPct >= bounds.minStopPct - 1e-6)
        assertTrue("sell plan keeps its stop above entry", review.guarded.stopLoss > 700.0)
        assertTrue("sell plan keeps its target below entry", review.guarded.takeProfit < 700.0)
        assertTrue(review.reasonFa.isNotBlank())
    }

    @Test
    fun `stored plan review accepts plans already inside the corridor`() {
        val review = TradePlanGuard.reviewStoredPlan(
            symbol = "BTC/USDT",
            action = SignalAction.BUY,
            entryPrice = 100_000.0,
            stopLoss = 99_200.0,
            takeProfit = 102_000.0,
            triggerInterval = Interval.M15,
        )
        assertFalse("an in-corridor plan must not be rewritten", review.needsRepair)
    }
}
