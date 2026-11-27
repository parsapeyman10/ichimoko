package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class ProfitLockPolicyTest {
    private fun trade(action: SignalAction) = PaperTrade(
        id = "t", symbol = "BTC/USDT", interval = Interval.M15, action = action, entry = 100.0,
        stopLoss = if (action == SignalAction.BUY) 90.0 else 110.0,
        initialStopLoss = if (action == SignalAction.BUY) 90.0 else 110.0,
        takeProfit = if (action == SignalAction.BUY) 130.0 else 70.0,
        confidence = 80.0, riskReward = 3.0, openedAt = 1_000L, positionOz = 1.0,
        fixedRiskUsd = 10.0, netSettlementEnabled = true, settlementFeeUsd = 2.0,
    )

    @Test fun `both sides require net 1 point 5 R and lock costs plus a cent`() {
        for (action in listOf(SignalAction.BUY, SignalAction.SELL)) {
            val t = trade(action)
            val sign = if (action == SignalAction.BUY) 1 else -1
            assertNull(ProfitLockPolicy.breakEvenStop(t, PriceTick(100.0 + sign * 16.9, 10_000)))
            val stop = ProfitLockPolicy.breakEvenStop(t, PriceTick(100.0 + sign * 17.0, 10_000))!!
            assertTrue((stop - 100.0) * sign > 2.0)
            assertTrue(RiskAndCalendarEngine.computeExitPnlUsd(t, stop).second >= 0.01)
            assertNull(ProfitLockPolicy.breakEvenStop(t.copy(stopLoss = stop), PriceTick(100.0 + sign * 18.0, 11_000)))
            assertNull(ProfitLockPolicy.breakEvenStop(t, PriceTick(100.0 + sign * 17.0, 10_000, isTradeTick = false)))
        }
    }

    @Test fun `active stop not original determines exit and gap fills at observed price`() {
        for (action in listOf(SignalAction.BUY, SignalAction.SELL)) {
            val sign = if (action == SignalAction.BUY) 1 else -1
            val t = trade(action).copy(stopLoss = 100.0 + sign * 2.01, stopUpdatedAt = 9_000L)
            val hit = RiskAndCalendarEngine.evaluateAutoExit(t, PriceTick(100.0 + sign * 2.01, 10_000L))!!
            assertEquals(AutoExitKind.STOP_LOSS, hit.kind)
            assertTrue(hit.netPnlUsd >= 0.0)
            val gap = RiskAndCalendarEngine.evaluateAutoExit(t, PriceTick(100.0 - sign * 2.0, 10_000L))!!
            assertEquals(100.0 - sign * 2.0, gap.exitPrice, 1e-9)
            assertTrue(gap.netPnlUsd < 0)
            assertNull(RiskAndCalendarEngine.evaluateAutoExit(t, PriceTick(100.0, 8_000L)))
        }
    }

    @Test fun `historical replay does not apply a stop before its activation`() {
        val trade = trade(SignalAction.BUY).copy(stopLoss = 102.01, stopUpdatedAt = 2_000_000L)
        val old = Candle(1_000_000L, 105.0, 106.0, 101.0, 104.0, closed = true)
        val next = Candle(2_100_000L, 105.0, 106.0, 102.0, 104.0, closed = true)
        assertNull(RiskAndCalendarEngine.evaluateCandleWindow(trade, listOf(old)))
        assertEquals(102.01, RiskAndCalendarEngine.evaluateCandleWindow(trade, listOf(old, next))!!.exitPrice, 1e-9)
        assertNull(RiskAndCalendarEngine.evaluateCandleWindow(trade.copy(stopUpdatedAt = null), listOf(next)))
    }
}
