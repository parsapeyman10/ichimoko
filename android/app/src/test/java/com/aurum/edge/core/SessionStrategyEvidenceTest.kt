package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SessionStrategyEvidenceTest {
    private fun trade(id: String, hour: Int, pnl: Double, side: SignalAction = SignalAction.BUY) = PaperTrade(
        id = id, symbol = "XAU/USD", interval = Interval.M5, action = side, entry = 100.0,
        stopLoss = 99.0, initialStopLoss = 99.0, takeProfit = 103.0, confidence = 80.0, riskReward = 3.0,
        openedAt = 1_704_067_200_000L + hour * 3_600_000L, closedAt = 1_704_070_800_000L + hour * 3_600_000L,
        pnlUsd = pnl, fixedRiskUsd = 5.0, strategyId = "one")
    @Test fun `sessions directions strategies and backtests stay separate`() {
        val a = trade("a", 8, 10.0); val b = trade("b", 8, -5.0, SignalAction.SELL)
        val data = listOf(a, b, trade("c", 8, 10.0).copy(origin = "BACKTEST"), trade("d", 8, 10.0).copy(strategyId = "other"), trade("e", 0, -5.0))
        val rows = SessionStrategyEvidence.build(data, "XAU/USD", "one", Interval.M5, null)
        assertEquals(5, rows.size)
        val london = rows.first { it.session == "LONDON" }
        assertEquals(2, london.count); assertEquals(2.0, london.profitFactor!!, 1e-9)
        assertEquals(0.5, london.meanR!!, 1e-9); assertEquals(1.0, london.maxDrawdownR!!, 1e-9)
        assertEquals(1, SessionStrategyEvidence.build(data, "XAU/USD", "one", Interval.M5, SignalAction.SELL).first { it.session == "LONDON" }.count)
        assertEquals(0, SessionStrategyEvidence.build(data, "BTC/USDT", "one", Interval.M5, null).sumOf { it.count })
    }
    @Test fun `unknown original stop and legacy cost never claimed valid`() {
        assertTrue(SessionStrategyEvidence.planReviewFa(trade("a", 1, 5.0).copy(initialStopLoss = null)).contains("نامعلوم"))
        assertTrue(SessionStrategyEvidence.planReviewFa(trade("b", 1, 5.0)).contains("قدیمی"))
    }
    @Test fun `profiles include independent directions and empty sessions`() {
        val profiles = SessionStrategyEvidence.profiles(listOf(trade("a", 8, 10.0)))
        assertTrue(profiles.all { it.sessions.size == 5 })
        assertTrue(profiles.any { it.symbol == "BTC/USDT" && it.strategyId == "one" && it.timeframe == "5m" && it.direction == "BUY" && it.sessions.all { row -> row.count == 0 } })
        assertEquals(0, profiles.first { it.symbol == "XAU/USD" && it.strategyId == "one" && it.timeframe == "5m" && it.direction == "SELL" }.sessions.sumOf { it.count })
    }
}
