package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfitRiskPolicyTest {
    private fun trade(pnl: Double?, closed: Boolean = true, backtest: Boolean = false) = PaperTrade(
        id = "t", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0,
        riskReward = 2.0, openedAt = 1000L, closedAt = if (closed) 2000L else null,
        pnlUsd = pnl, origin = if (backtest) PaperTrade.ORIGIN_BACKTEST else PaperTrade.ORIGIN_LIVE,
    )
    @Test fun usesRealizedProfitAndNeverLessThanFive() {
        assertEquals(10.0, ProfitRiskPolicy.riskUsd(listOf(trade(1000.0)), 101000.0), 0.001)
        assertEquals(5.0, ProfitRiskPolicy.riskUsd(listOf(trade(-1000.0)), 99000.0), 0.001)
        assertEquals(5.0, ProfitRiskPolicy.riskUsd(listOf(trade(1000.0, false), trade(1000.0, backtest = true)), 101000.0), 0.001)
        assertEquals(5.0, ProfitRiskPolicy.riskUsd(emptyList(), 101000.0), 0.001)
    }
    @Test fun drawdownAndBalanceAboveBaseLimitEligibleProfit() {
        assertEquals(7.5, ProfitRiskPolicy.riskUsd(listOf(trade(1000.0), trade(-500.0)), 100500.0), 0.001)
        assertEquals(5.0, ProfitRiskPolicy.riskUsd(listOf(trade(1000.0)), 100000.0), 0.001)
    }
}
