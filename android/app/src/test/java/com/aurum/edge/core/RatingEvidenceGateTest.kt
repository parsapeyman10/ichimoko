package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class RatingEvidenceGateTest {
    private val id = BitcoinStrategyModel.BASE_ICHIMOKU.id
    private val buy = TechnicalRating(20, 2, 4)
    private val sell = TechnicalRating(4, 2, 20)
    private fun trade(i: Int, pnl: Double, symbol: String = "BTC/USDT", rebuilt: Boolean = false) = PaperTrade(
        id = "t$i", symbol = symbol, interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0, riskReward = 2.0,
        openedAt = 1000L, closedAt = 2000L, pnlUsd = pnl, strategyId = id,
        tech1hBuy = 20, tech1hNeutral = 2, tech1hSell = 4,
        tech4hBuy = 4, tech4hNeutral = 2, tech4hSell = 20, techSnapshotRebuilt = rebuilt)
    @Test fun blocksOnlyWhenEvidenceStronglyBelowBreakEven() {
        val bad = List(30) { trade(it, -5.0) }
        val result = RatingEvidenceGate.evaluate(bad, "BTC/USDT", id, SignalAction.BUY, buy, sell, 2.0)
        assertTrue(result.blocked); assertEquals(30, result.samples); assertEquals(0, result.wins)
        assertFalse(RatingEvidenceGate.evaluate(bad.take(29), "BTC/USDT", id, SignalAction.BUY, buy, sell, 2.0).blocked)
        assertFalse(RatingEvidenceGate.evaluate(List(30) { trade(it, 10.0) }, "BTC/USDT", id, SignalAction.BUY, buy, sell, 2.0).blocked)
    }
    @Test fun missingOrDifferentDataDoesNotBlock() {
        val bad = List(40) { trade(it, -5.0, "ETH/USDT") } + List(40) { trade(it, -5.0, rebuilt = true) }
        assertEquals(0, RatingEvidenceGate.evaluate(bad, "BTC/USDT", id, SignalAction.BUY, buy, sell, 2.0).samples)
        assertFalse(RatingEvidenceGate.evaluate(bad, "BTC/USDT", id, SignalAction.BUY, null, sell, 2.0).blocked)
    }
}
