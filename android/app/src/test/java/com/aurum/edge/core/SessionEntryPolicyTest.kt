package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class SessionEntryPolicyTest {
    private fun trade(i: Int, hour: Int, pnl: Double) = PaperTrade(
        id = "$i-$hour", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0, riskReward = 2.0,
        openedAt = 86_400_000L + hour * 3_600_000L, closedAt = 172_800_000L,
        pnlUsd = pnl, fixedRiskUsd = 5.0, strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id)
    @Test fun requiresTwoSufficientGroupsAndDoesNotUseFuture() {
        val one = List(30) { trade(it, 8, 5.0) }
        val two = one + List(30) { trade(it, 13, 10.0) }
        val id = BitcoinStrategyModel.BASE_ICHIMOKU.id
        assertNull(SessionEntryPolicy.preferred(one, "BTC/USDT", id, 200_000_000L))
        assertNull(SessionEntryPolicy.preferred(two, "BTC/USDT", id, 100_000_000L))
        assertEquals("LONDON_NY", SessionEntryPolicy.preferred(two, "BTC/USDT", id, 200_000_000L)?.key)
    }
}
