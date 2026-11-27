package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class ProfitSettlementTest {
    private fun closedProfit(pnl: Double) = PaperTrade(
        id = "profit", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0,
        riskReward = 2.0, openedAt = 1000L, closedAt = 2000L, pnlUsd = pnl,
    )
    private fun plan(action: SignalAction, rr: Double): RiskCalculationResult {
        val direction = if (action == SignalAction.BUY) 1 else -1
        return RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BNB/USDT", accountBalance = 101000.0, riskPercent = 0.5,
            entry = 701.60, stopLoss = 701.60 - direction * 2.86,
            takeProfit = 701.60 + direction * 2.86 * rr,
            preferredAction = action, fixedRiskUsdOverride = 10.0,
        )!!
    }
    @Test fun targetAndStopActuallySettleAtDisplayedNetAmounts() {
        for (action in listOf(SignalAction.BUY, SignalAction.SELL)) {
            for (rr in listOf(2.0, 2.5, 3.0)) {
                val c = plan(action, rr)
                val t = c.toPaperTrade(openedAt = 1_000L)
                assertNull("$action $rr", ProfitRiskPolicy.planErrorFa(c, listOf(closedProfit(1000.0)), 101000.0))
                val stop = RiskAndCalendarEngine.computeExitPnlUsd(t, c.stopLoss).second
                val target = RiskAndCalendarEngine.computeExitPnlUsd(t, c.takeProfit).second
                assertEquals(-10.0, stop, 0.01)
                assertEquals(c.netProfitAtTpUsd, target, 0.01)
                assertTrue("$action $rr $target", target >= 20.0 && target <= 30.0)
                val hit = RiskAndCalendarEngine.evaluateAutoExit(t, PriceTick(c.takeProfit, 20_000L))!!
                assertEquals(c.takeProfit, hit.exitPrice, 1e-8)
                assertEquals(target, hit.netPnlUsd, 0.01)
                val candle = Candle(time = 21_000L, open = c.entry,
                    high = maxOf(c.entry, c.takeProfit), low = minOf(c.entry, c.takeProfit), close = c.takeProfit)
                val backfill = RiskAndCalendarEngine.evaluateCandleWindow(t, listOf(candle))!!
                assertEquals(target, backfill.netPnlUsd, 0.01)
            }
        }
    }
    @Test fun staleBudgetAndOutsideTargetAreRejected() {
        val trades = listOf(closedProfit(1000.0))
        assertNotNull(ProfitRiskPolicy.planErrorFa(plan(SignalAction.BUY, 2.5), emptyList(), 100000.0))
        assertNotNull(ProfitRiskPolicy.planErrorFa(plan(SignalAction.BUY, 1.5), trades, 101000.0))
        assertNotNull(ProfitRiskPolicy.planErrorFa(plan(SignalAction.BUY, 4.0), trades, 101000.0))
    }
}
