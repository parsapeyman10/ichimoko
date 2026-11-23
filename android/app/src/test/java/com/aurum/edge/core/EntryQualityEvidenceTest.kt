package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class EntryQualityEvidenceTest {
    private val model = BitcoinStrategyModel.BASE_ICHIMOKU.id
    private val rating = TechnicalRating(20, 2, 4)
    private fun t(i: Int, pnl: Double = 5.0) = PaperTrade(id = "$i", symbol = "BTC/USDT", interval = Interval.M5, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0, riskReward = 2.0,
        openedAt = 86_400_000L + 8 * 3_600_000L + i * 1000, closedAt = 86_400_000L + 9 * 3_600_000L + i * 1000, pnlUsd = pnl,
        fixedRiskUsd = 5.0, strategyId = model, tech1hBuy = 20, tech1hNeutral = 2, tech1hSell = 4,
        tech4hBuy = 20, tech4hNeutral = 2, tech4hSell = 4)
    private val now = 2 * 86_400_000L + 8 * 3_600_000L
    @Test fun needsThirtyMatchingPastRealSnapshots() {
        val base = List(30) { t(it) }
        assertEquals(0.0, EntryQualityEvidence.evaluate(base.take(29), "BTC/USDT", model, SignalAction.BUY, now, rating, rating).priorityAdjustment, 0.0)
        val evidence = EntryQualityEvidence.evaluate(base, "BTC/USDT", model, SignalAction.BUY, now, rating, rating)
        assertEquals(30, evidence.samples); assertEquals(1.0, evidence.meanR!!, 1e-9); assertEquals(2.5, evidence.priorityAdjustment, 1e-9)
        assertNull(EntryQualityEvidence.evaluate(base, "BTC/USDT", model, SignalAction.BUY, now, null, rating).meanR)
        val invalid = base.map { it.copy(techSnapshotRebuilt = true) }
        assertEquals(0, EntryQualityEvidence.evaluate(invalid, "BTC/USDT", model, SignalAction.BUY, now, rating, rating).samples)
        val duplicates = List(30) { base[0].copy(id = "dup$it") }
        assertEquals(1, EntryQualityEvidence.evaluate(duplicates, "BTC/USDT", model, SignalAction.BUY, now, rating, rating).samples)
        val recovered = base.map { it.copy(exitReason = "بازیابی ساعات قطعی") }
        assertEquals(0, EntryQualityEvidence.evaluate(recovered, "BTC/USDT", model, SignalAction.BUY, now, rating, rating).samples)
        assertEquals(0, EntryQualityEvidence.evaluate(base, "BTC/USDT", model, SignalAction.BUY, base[0].closedAt!!, rating, rating).samples)
    }
    @Test fun missingRiskAndBacktestCannotTrain() {
        val bad = List(40) { t(it, -5.0).copy(fixedRiskUsd = 0.0) } + List(40) { t(it, -5.0).copy(origin = PaperTrade.ORIGIN_BACKTEST) }
        assertEquals(0, EntryQualityEvidence.evaluate(bad, "BTC/USDT", model, SignalAction.BUY, now, rating, rating).samples)
    }
}
