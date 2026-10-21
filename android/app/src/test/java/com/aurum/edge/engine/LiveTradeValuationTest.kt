package com.aurum.edge.engine

import com.aurum.edge.domain.Interval
import com.aurum.edge.core.LiveTradeValuation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTradeValuationTest {
    private fun trade(symbol: String = "BTC/USDT", side: SignalAction = SignalAction.BUY) = PaperTrade(
        id = symbol, symbol = symbol, interval = Interval.M5, action = side,
        entry = 100.0, stopLoss = if (side == SignalAction.BUY) 90.0 else 110.0,
        takeProfit = if (side == SignalAction.BUY) 120.0 else 80.0,
        confidence = 80.0, riskReward = 2.0, openedAt = 1_700_000_000_000L,
        positionOz = 2.0, commissionUsd = 1.0, spreadCostUsd = 1.0,
    )

    @Test fun `no current quote cannot be replaced with entry price or a fabricated pnl`() {
        val open = trade()
        assertNull(LiveTradeValuation.from(open, null))
        assertNull(LiveTradeValuation.from(open, Double.NaN))
        assertNull(LiveTradeValuation.from(open, 0.0))
        assertNull(LiveTradeValuation.total(listOf(open), emptyMap()))
        assertNull(LiveTradeValuation.total(listOf(open, trade("ETH/USDT")),
            mapOf(open.symbol to 110.0)))
    }

    @Test fun `a real price equal to entry is still a quote but a missing quote is not`() {
        val open = trade()
        val same = LiveTradeValuation.from(open, 100.0)!!
        assertEquals(100.0, same.price, 0.0)
        assertEquals(-2.0, same.netPnlUsd, 0.0001)
        assertEquals(0.0, same.pnlPercent, 0.0)
        val latest = LiveTradeValuation.from(open, 110.0)!!
        assertEquals(18.0, latest.netPnlUsd, 0.0001)
        assertTrue(latest.progress > same.progress)
        assertEquals(18.0, LiveTradeValuation.total(listOf(open), mapOf(open.symbol to 110.0))!!, 0.0001)
    }

    @Test fun `short progression and multi-position total require fresh prices for each symbol`() {
        val buy = trade()
        val sell = trade("ETH/USDT", SignalAction.SELL)
        val prices = mapOf(buy.symbol to 110.0, sell.symbol to 90.0)
        assertEquals(18.0, LiveTradeValuation.from(sell, 90.0)!!.netPnlUsd, 0.0001)
        assertEquals(36.0, LiveTradeValuation.total(listOf(buy, sell), prices)!!, 0.0001)
        assertEquals(0.0, LiveTradeValuation.total(emptyList(), emptyMap())!!, 0.0)
    }
}
