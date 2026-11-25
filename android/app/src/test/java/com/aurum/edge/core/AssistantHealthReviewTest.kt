package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class AssistantHealthReviewTest {
    @Test fun staleQuoteAndInvalidRiskAreReportedWithoutMakingAnEntryDecision() {
        val now = 1_000_000L
        val trade = PaperTrade(id = "1", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0, riskReward = 2.0,
            openedAt = 1L, fixedRiskUsd = 0.0)
        val warnings = AssistantHealthReview.inspect(listOf(trade), mapOf("BTC/USDT" to PriceTick(101.0, now - 100_000L)), now)
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.contains("قیمت تازه") })
        assertTrue(warnings.any { it.contains("ریسک") })
        assertTrue(AssistantHealthReview.inspect(listOf(trade.copy(fixedRiskUsd = 5.0)), mapOf("BTC/USDT" to PriceTick(101.0, now)), now).isEmpty())
    }
}
