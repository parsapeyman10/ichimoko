package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.AiNewsVerdict
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsSourceStatus
import com.aurum.edge.data.PersianHeadline
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.*
import org.junit.Test

/** Combined contract: ICT can only further restrict the existing 9/9 PAPER policy. */
class IctEntryRulesTest {
    private val now = 1_800_000_000_000L // 03:00 in New York (London window)
    private val bar = now - Interval.M5.millis
    private val config = AppSettings(symbol = "XAU/USD", interval = Interval.M5,
        backgroundMonitor = true, autoPaperTrading = true)
    private val news = PersianNewsState(
        articles = listOf(PersianHeadline("gold-news", "Gold", "fixture", "Publisher",
            "https://publisher.example/gold", now - 60_000, "LOW", "BUY", "rules")),
        gate = NewsGate.CLEAR, lastCheckedAt = now,
        sources = listOf(NewsSourceStatus("Publisher", "online", "https://publisher.example/rss")),
        ai = AiNewsVerdict("AVAILABLE", "XAU/USD", "BUY", 91.0, "test-model",
            "fixture", now, listOf("gold-news")),
    )
    private val signal = Signal(SignalAction.BUY, 87.0, entry = 3000.0,
        stopLoss = 2994.5, takeProfit = 3009.0, interval = Interval.M5, barTime = bar,
        confluence = (1..8).map { ConfluenceItem("فنی $it", true, "fixture") })
    private val verified get() = NewsConfluence.apply(signal, "XAU/USD", news, now)!!
    private val market get() = MarketState(symbol = "XAU/USD", interval = Interval.M5,
        candles = IctTestBars.readyAt(bar), lastPrice = 3000.0,
        feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = verified)

    @Test fun `valid pattern AND nine checks permit only paper candidate`() {
        assertTrue(IctEntryRules.assess(market, now).allowed)
        val evidence = IctEntryRules.approvedEvidence(market, now)!!
        assertTrue(evidence.matches(verified, market.symbol, market.lastPrice!!))
        assertEquals(evidence.sweepAt, evidence.levelsConfirmedAt)
        assertEquals(3, evidence.supportTouches)
        assertEquals(IctRangeAnalyzer.ZoneKind.FVG, IctRangeAnalyzer.analyze(market.candles, Interval.M5).buy.fvg?.kind)
        assertFalse(evidence.copy(barTime = bar - Interval.M5.millis).matches(verified, market.symbol, market.lastPrice!!))
        assertNull(PaperAutoRules.blocker(market, config, news, now))
        assertNull(PaperAutoRules.opportunityBlocker(market, config.copy(autoPaperTrading = false), news, now))
    }

    @Test fun `support touch, midrange, no closed retest and invalid history fail closed`() {
        val current = market
        val touched = current.candles.take(32) + current.candles.drop(32).map { c ->
            c.copy(open = 2997.1, high = 2998.1, low = 2996.82, close = 2997.3)
        }
        listOf(
            current.copy(candles = touched, lastPrice = 2997.3),
            current.copy(candles = current.candles.dropLast(1) + current.candles.last().copy(closed = false)),
            current.copy(candles = current.candles.takeLast(3)),
            current.copy(candles = current.candles.mapIndexed { i, c ->
                if (i == 31) c.copy(time = c.time + Interval.M5.millis * 2) else c
            }),
        ).forEachIndexed { i, state ->
            assertNotNull("block $i", PaperAutoRules.blocker(state, config, news, now))
        }
        val middle = current.copy(lastPrice = (2996.82 + 3009.42) / 2.0)
        assertTrue(PaperAutoRules.blocker(middle, config, news, now)?.contains("ICT") == true)
    }

    @Test fun `real entry stop must be beyond sweep and target before resistance`() {
        val current = market
        val unsafeStop = current.copy(signal = verified.copy(stopLoss = 2996.0))
        val overTarget = current.copy(signal = verified.copy(takeProfit = 3010.0))
        listOf(unsafeStop, overTarget).forEach {
            assertNotNull(PaperAutoRules.blocker(it, config, news, now))
        }
    }

    @Test fun `even a ready structure cannot replace fresh ninth news check`() {
        val invalidNews = news.copy(ai = AiNewsVerdict())
        assertNotNull(PaperAutoRules.blocker(market, config, invalidNews, now))
        assertNotNull(PaperAutoRules.blocker(market.copy(showingCachedData = true), config, news, now))
    }

    @Test fun `live closed candle plan moves stop beyond sweep and TP inside range`() {
        val raw = market.copy(signal = verified.copy(stopLoss = 2996.0, takeProfit = 3012.0))
        assertNotNull(PaperAutoRules.blocker(raw, config, news, now))
        val planned = IctEntryRules.withSafePlan(raw)
        assertTrue(planned.signal!!.stopLoss!! < 2995.0)
        assertTrue(planned.signal!!.takeProfit!! <= 3009.02)
        assertEquals(raw.signal!!.confluence, planned.signal!!.confluence)
        assertNull(PaperAutoRules.blocker(planned, config, news, now))
    }

    @Test fun `short needs ceiling sweep and short plan as well`() {
        val reversedNews = news.copy(ai = news.ai.copy(direction = "SELL"),
            articles = news.articles.map { it.copy(direction = "SELL") })
        val raw = signal.copy(action = SignalAction.SELL, stopLoss = 3005.5,
            takeProfit = 2991.5)
        val sell = NewsConfluence.apply(raw, "XAU/USD", reversedNews, now)!!
        val current = market.copy(candles = IctTestBars.readyAt(bar, mirror = true), signal = sell)
        assertEquals(SignalAction.SELL, sell.action)
        assertEquals(IctRangeAnalyzer.State.READY, IctRangeAnalyzer.analyze(current.candles, Interval.M5).sell.state)
        assertNull(PaperAutoRules.blocker(current, config, reversedNews, now))
    }
}
