package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SessionPerformanceReportTest {
    private fun trade(id: String, hour: Int, pnl: Double, backtest: Boolean = false, risk: Double = 5.0) = PaperTrade(
        id = id, symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0, riskReward = 2.0,
        openedAt = 86_400_000L + hour * 3_600_000L, closedAt = 90_000_000L + hour * 3_600_000L,
        pnlUsd = pnl, fixedRiskUsd = risk, origin = if (backtest) PaperTrade.ORIGIN_BACKTEST else PaperTrade.ORIGIN_LIVE)
    @Test fun ranksOnlySufficientRealRiskSamplesAndExcludesBacktests() {
        val data = List(30) { trade("a$it", 8, 5.0) } + List(29) { trade("b$it", 13, 25.0) } +
            trade("test", 13, 500.0, true) + trade("missing", 13, 50.0, risk = 0.0)
        val report = SessionPerformanceReport.build(data)
        assertEquals("LONDON", report.bestSession!!.key)
        assertEquals(30, report.bySession.first { it.key == "LONDON_NY" }.count)
        assertEquals(29, report.bySession.first { it.key == "LONDON_NY" }.rSamples)
        assertEquals(60, report.bySymbol.single().count)
    }
    @Test fun noRecommendationForInsufficientOrNegativeEvidence() {
        assertNull(SessionPerformanceReport.build(List(29) { trade("$it", 8, 5.0) }).bestSession)
        assertNull(SessionPerformanceReport.build(List(30) { trade("$it", 8, -5.0) }).bestSession)
    }
}
