package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test

class TradingShadowComparisonTest {
    private val step = Interval.M15.millis
    private fun sample(): PaperTrade = PaperTrade(
        id = "shadow", symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 90.0, initialStopLoss = 90.0, takeProfit = 120.0,
        confidence = 80.0, riskReward = 2.0, openedAt = 60*step, closedAt = 62*step,
        pnlUsd = 19.0, fixedRiskUsd = 10.0, positionOz = 1.0, netSettlementEnabled = true,
        settlementFeeUsd = 1.0, origin = PaperTrade.ORIGIN_LIVE,
    )
    private fun bars() = List(60) { i -> Candle(i*step, 100.0, 101.0, 99.0, 100.0) }

    @Test fun `both arms see same target and do not mutate journal trade`() {
        val t = sample()
        val input = bars() + listOf(Candle(60*step, 100.0, 105.0, 99.0, 104.0), Candle(61*step, 104.0, 121.0, 103.0, 120.0))
        val result = TradingShadowComparison.compare(t, input, 63*step).comparison!!
        assertEquals(19.0, result.currentNetUsd, 0.001)
        assertEquals(result.currentNetUsd, result.candidateNetUsd, 0.0)
        assertEquals(90.0, t.stopLoss, 0.0)
        assertNotNull(t.closedAt)
    }

    @Test fun `partial entry missing history and bar touching both barriers are rejected`() {
        val t = sample()
        val both = bars() + Candle(60*step, 100.0, 121.0, 89.0, 100.0)
        assertNull(TradingShadowComparison.compare(t, both, 62*step).comparison)
        assertNull(TradingShadowComparison.compare(t.copy(openedAt = t.openedAt + 1000), both, 62*step).comparison)
        assertNull(TradingShadowComparison.compare(t, both.drop(1), 62*step).comparison)
        assertNull(TradingShadowComparison.compare(t.copy(netSettlementEnabled = false), both, 62*step).comparison)
    }

    @Test fun `gap fill is not invented at stop`() {
        val input = bars() + Candle(60*step, 100.0, 101.0, 99.0, 100.0) + Candle(61*step, 85.0, 86.0, 84.0, 85.0)
        val result = TradingShadowComparison.compare(sample(), input, 63*step).comparison!!
        assertEquals(-16.0, result.currentNetUsd, 0.001)
        assertEquals(-16.0, result.candidateNetUsd, 0.001)
    }
}
