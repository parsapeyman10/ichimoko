package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedContractTest {
    private val conditions = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
        "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۷ تایم‌فریمی").map {
        ConfluenceItem(it, true, "fixture", scorePercent = 25)
    }
    private val candidate = Signal(SignalAction.BUY, 100.0, entry = 100.0,
        stopLoss = 95.0, takeProfit = 110.0, interval = Interval.M5, barTime = 1_700_000_000_000L,
        confluence = conditions)

    @Test fun fourScoredLayersAreRequiredAndNewsNeverRepairsMissingEvidence() {
        assertTrue(TechnicalEvidence.confirmed(TechnicalEvidence.items(candidate)))
        for (index in conditions.indices) {
            val incomplete = candidate.copy(confluence = conditions.filterIndexed { i, _ -> i != index })
            assertFalse(TechnicalEvidence.confirmed(TechnicalEvidence.items(incomplete)))
            assertEquals(SignalAction.NO_TRADE,
                NewsConfluence.apply(incomplete, "XAU/USD", PersianNewsState())!!.action)
        }
        val unknown = candidate.copy(confluence = conditions.toMutableList().also {
            it[0] = it[0].copy(status = ConfluenceStatus.UNKNOWN)
        })
        assertFalse(TechnicalEvidence.confirmed(TechnicalEvidence.items(unknown)))
        assertFalse(TechnicalEvidence.confirmed(conditions + conditions[0])) // duplicate layer must not authorize entry
        assertEquals(SignalAction.BUY,
            NewsConfluence.apply(candidate, "XAU/USD", PersianNewsState())!!.action)
    }

    @Test fun portfolioCapSlotsAndRiskAreOnePolicy() {
        fun trade(symbol: String, bar: Long) = PaperTrade(symbol, symbol, Interval.M5,
            SignalAction.BUY, 100.0, 99.0, 102.0, 85.0, 2.0, bar,
            positionOz = 4.0, signalBarTime = bar)
        val three = listOf(trade("BTC/USD", 1L), trade("EUR/USD", 2L), trade("XAU/USD", 3L))
        assertEquals(4, PaperPortfolioPolicy.MAX_OPEN)
        assertEquals(0.005, PaperPortfolioPolicy.MAX_RISK_PER_TRADE, 0.0)
        assertEquals(0.02, PaperPortfolioPolicy.MAX_DAILY_LOSS, 0.0)
        assertNull(PaperPortfolioPolicy.blocker(three, "AAPL", 1_000.0, 5.0))
        assertTrue(PaperPortfolioPolicy.blocker(three + trade("AAPL", 4L), "TSLA", 1_000.0, 5.0) != null)
        assertNull(PaperPortfolioPolicy.blocker(three, "ETH/USD", 1_000.0, 5.0))
        assertTrue(PaperPortfolioPolicy.blocker(three + trade("ETH/USD", 4L),
            "SOL/USD", 1_000.0, 5.0) != null)
        assertTrue(PaperPortfolioPolicy.blocker(three, "AAPL", 100.0, 1.0) != null)
        assertTrue(PaperPortfolioPolicy.blocker(listOf(trade("EUR/USD", 5L)),
            "EUR/USD", 1_000.0, 0.0, 5L) != null)
    }

    @Test fun replayCursorUsesTheSameDecisionAsLiveWithoutSeeingFutureBars() {
        val bars = (0 until 260).map { i ->
            val price = 100.0 + i * 0.1
            Candle(1_700_000_000_000L + i * Interval.M5.millis,
                price, price + 0.6, price - 0.6, price + 0.1, 100.0)
        }
        val config = ReplayEngine.Config(1_000.0, 0.5, 0.3, 0.05, 85.0, SignalProfile.BASE)
        val session = ReplayEngine.create(bars, Interval.M5, "XAU/USD", "fixture", config, 220)
        val snapshot = ReplayEngine.snapshot(session)
        val live = SignalEngine.evaluate(bars.take(221), Interval.M5)
        assertEquals(live.action, snapshot.signal?.action)
        assertEquals(live.blockers, snapshot.signal?.blockers)
        assertEquals(live.barTime, snapshot.signal?.barTime)
        val futureSnapshot = ReplayEngine.snapshot(ReplayEngine.create(
            bars + bars.last().copy(time = bars.last().time + Interval.M5.millis,
                high = 10_001.0, close = 10_000.0), Interval.M5,
            "XAU/USD", "fixture", config, 220))
        assertEquals(live.action, futureSnapshot.signal?.action)
        assertEquals(live.blockers, futureSnapshot.signal?.blockers)
        assertEquals(live.barTime, futureSnapshot.signal?.barTime)
    }
}
