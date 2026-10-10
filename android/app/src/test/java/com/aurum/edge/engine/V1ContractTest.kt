package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Signal
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperTrailing
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import com.aurum.edge.data.AiNewsVerdict
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsSourceStatus
import com.aurum.edge.data.PersianHeadline
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class V1ContractTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private fun bars(interval: Interval): List<Candle> = (0 until 240).map { i ->
        val start = now - (240 - i) * interval.millis
        val price = 100.0 + i * .02 + (i % 5) * .08
        Candle(start, price, price + .25, price - .25, price + .03, 100.0, true)
    }

    @Test fun unknownTimeframesNeverCreateTradesOrFillWithInventedM1() {
        val base = bars(Interval.M5)
        val signal = SignalEngine.evaluate(base, Interval.M5, 60.0,
            symbol = "EUR/USD", mode = CategoryStrategy.HYBRID)
        assertEquals(SignalAction.NO_TRADE, signal.action)
        assertEquals(4, signal.confluence.size)
        assertTrue(signal.blockers.any { it.contains("پشتهٔ ۷") })
        assertTrue(signal.confluence.last().detail.contains("1m:نامعلوم") ||
            signal.confluence.last().detail.contains("1M:نامعلوم"))
        assertFalse(signal.isActionable)
    }

    @Test fun fullRealSevenFrameStackIsScoredAndOldFutureBarsCannotParticipate() {
        val data = V1Scoring.intervals.associateWith(::bars)
        val signal = SignalEngine.evaluate(data.getValue(Interval.M5), Interval.M5, 85.0,
            symbol = "EUR/USD", timeframes = data)
        assertEquals(4, signal.confluence.size)
        assertEquals(7, V1Scoring.intervals.size)
        assertTrue(signal.confluence.last().detail.contains("1D:"))
        assertFalse(signal.blockers.any { it.contains("پشتهٔ ۷") })
        val future = data.toMutableMap()
        future[Interval.M1] = data.getValue(Interval.M1).map {
            it.copy(time = it.time + 2L * Interval.D1.millis)
        }
        val rejected = SignalEngine.evaluate(data.getValue(Interval.M5), Interval.M5, 60.0,
            symbol = "EUR/USD", timeframes = future)
        assertEquals(SignalAction.NO_TRADE, rejected.action)
        assertTrue(rejected.blockers.any { it.contains("پشتهٔ ۷") })
    }

    private fun trade(id: String, symbol: String, opened: Long, closed: Long? = null,
                      pnl: Double? = null) = PaperTrade(id, symbol, Interval.M5, SignalAction.BUY,
        100.0, 99.0, 102.0, 85.0, 2.0, opened, closedAt = closed, pnlUsd = pnl,
        positionOz = 5.0, initialStopLoss = 99.0)

    @Test fun riskSlotsDailyHeadroomAndThreeConsecutiveLosses() {
        val open = listOf(trade("1", "EUR/USD", 1L), trade("2", "GBP/USD", 2L))
        assertNotNull(PaperPortfolioPolicy.blocker(open, "USD/JPY", 1000.0, 5.0, now = now))
        assertNotNull(PaperPortfolioPolicy.blocker(open.take(1), "AAPL", 1000.0, 5.01, now = now))
        val losses = (1..3).map { trade("x$it", "AAPL", now - it * 1000L, now - it * 500L, -5.0) }
        assertTrue(PaperPortfolioPolicy.blocker(losses, "EUR/USD", 985.0, 5.0, now = now)!!.contains("۳ باخت"))
        val winning = trade("win", "TSLA", now, now - 1L, 1.0)
        assertNotNull(PaperPortfolioPolicy.blocker(losses + winning + open.take(1),
            "AAPL", 986.0, 5.0, now = now)) // 15 closed loss + 5 open + 5 new > 2%
    }

    @Test fun validatedOppositeAiVetoesButUnavailableAiCannotAuthorizeTrades() {
        val labels = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
            "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۷ تایم‌فریمی")
        val raw = Signal(SignalAction.BUY, 100.0, entry = 100.0,
            stopLoss = 99.0, takeProfit = 101.5, interval = Interval.M5,
            barTime = now - Interval.M5.millis,
            confluence = labels.map { ConfluenceItem(it, true, "real-frame fixture", scorePercent = 25) })
        val headline = PersianHeadline("id1", "FX context", "fixture", "Publisher",
            "https://publisher.example/news", now - 60_000L,
            "MEDIUM", "SELL", "rule-label", "en")
        val news = PersianNewsState(
            articles = listOf(headline),
            sources = listOf(NewsSourceStatus("Publisher", "online", "https://publisher.example/rss"),
                NewsSourceStatus("Forex Factory", "online", "https://nfs.faireconomy.media/ff_calendar_thisweek.json")),
            gate = NewsGate.CLEAR, lastCheckedAt = now, calendarCheckedAt = now,
            ai = AiNewsVerdict("AVAILABLE", "EUR/USD", "SELL", 91.0,
                "test-model", "opposing validated evidence", now, listOf("id1")))
        val vetoed = NewsConfluence.apply(raw, "EUR/USD", news, now)!!
        assertEquals(SignalAction.NO_TRADE, vetoed.action)
        assertTrue(vetoed.blockers.any { it.contains("وتوی AI") })
        assertEquals(SignalAction.BUY,
            NewsConfluence.apply(raw, "EUR/USD", news.copy(ai = AiNewsVerdict()), now)!!.action)
    }

    @Test fun shortTrailingLocksTheFirstRAfterTwoR() {
        val short = trade("short", "AAPL", now).copy(action = SignalAction.SELL,
            stopLoss = 101.0, initialStopLoss = 101.0)
        val one = PaperTrailing.advance(short, 100.0, 99.0)
        assertEquals(100.0, one.stopLoss, .00001)
        val two = PaperTrailing.advance(one, 99.0, 98.0)
        assertEquals(99.0, two.stopLoss, .00001)
        assertEquals(99.0, PaperTrailing.advance(two, 99.5, 99.0).stopLoss, .00001)
    }

    @Test fun trailingMovesAtOneAndTwoRAndNeverShrinksTheLock() {
        val original = trade("1", "AAPL", now)
        assertEquals(5.0, original.riskUsd, .00001)
        val atOne = PaperTrailing.advance(original, 101.0, 100.0)
        assertEquals(100.0, atOne.stopLoss, .00001)
        val atTwo = PaperTrailing.advance(atOne, 102.0, 101.0)
        assertEquals(101.0, atTwo.stopLoss, .00001)
        val pastThree = PaperTrailing.advance(atTwo, 103.0, 102.0)
        assertEquals(102.0, pastThree.stopLoss, .00001)
        assertEquals(pastThree.stopLoss, PaperTrailing.advance(pastThree, 102.1, 101.5).stopLoss, .00001)
        assertEquals(5.0, pastThree.riskUsd, .00001) // original immutable risk in USD
    }
}
