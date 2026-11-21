package com.aurum.edge.core
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test
class WinRateReviewTest {
    private fun trade(pnl: Double?, closed: Boolean = true, backtest: Boolean = false) = PaperTrade(
        id = "t", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0,
        riskReward = 2.0, openedAt = 1000L, closedAt = if (closed) 2000L else null,
        pnlUsd = pnl, origin = if (backtest) PaperTrade.ORIGIN_BACKTEST else PaperTrade.ORIGIN_LIVE)
    @Test fun onlyClosedLiveDecisiveTradesCount() {
        val trades = List(19) { trade(10.0) } + List(11) { trade(-5.0) } + listOf(trade(0.0), trade(10.0, false), trade(10.0, backtest = true))
        val r = WinRateReview.calculate(trades)
        assertEquals(30, r.samples); assertEquals(1, r.flat); assertEquals(135.0, r.netUsd, 1e-9); assertTrue(r.needsReview)
        assertFalse(WinRateReview.calculate(trades.drop(1)).needsReview)
    }
    @Test fun thresholdIsInclusive() {
        val r = WinRateReview.calculate(List(26) { trade(10.0) } + List(14) { trade(-5.0) })
        assertEquals(65.0, r.ratePct!!, 1e-9); assertFalse(r.needsReview)
    }
}
