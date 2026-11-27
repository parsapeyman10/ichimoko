package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TradeFilterTest {
    private val at = Instant.parse("2026-01-01T08:00:00Z").toEpochMilli()
    private fun trade(id: String, pnl: Double?, risk: Double = 5.0, closed: Boolean = true, origin: String = PaperTrade.ORIGIN_LIVE) = PaperTrade(
        id = id, symbol = "BTC/USDT", interval = Interval.M15, action = SignalAction.BUY, entry = 100.0,
        stopLoss = 99.0, takeProfit = 102.0, confidence = 70.0, riskReward = 2.0, openedAt = at,
        closedAt = if (closed) at + 3600000 else null, pnlUsd = pnl, fixedRiskUsd = risk, origin = origin,
        strategyId = "abc", strategyTitleFa = "آزمون", note = "یادداشت")
    @Test fun combinedFiltersAndDatesAndUnknowns() {
        val win = trade("win", 10.0)
        val loss = trade("loss", -5.0)
        val open = trade("open", null, closed = false)
        val unknown = trade("unknown", null)
        val all = listOf(win, loss, open, unknown)
        val c = TradeFilter.Criteria(choices = mapOf("outcome" to "برد", "session" to SessionPerformanceReport.sessionLabel("LONDON"), "tech1h" to TradeFilter.UNKNOWN), fromDate = "2026-01-01", toDate = "2026-01-01", ranges = mapOf("realizedR" to ("۱.۵" to "2")))
        assertEquals(listOf(win), TradeFilter.apply(all, c))
        assertEquals(listOf(unknown), TradeFilter.apply(all, TradeFilter.Criteria(choices = mapOf("outcome" to "بسته؛ نتیجه نامعلوم"))))
        assertEquals(4, TradeFilter.apply(all, TradeFilter.Criteria(query = "یادداشت")).size)
        assertTrue(TradeFilter.apply(all, TradeFilter.Criteria(fromDate = "2026-01-02")).isEmpty())
        assertTrue(TradeFilter.apply(listOf(win.copy(openedAt = 0)), TradeFilter.Criteria(toDate = "2026-01-01")).isEmpty())
        assertEquals(4, TradeFilter.apply(all, TradeFilter.Criteria(fromDate = "۲۰۲۶-۰۱-۰۱", toDate = "۲۰۲۶-۰۱-۰۱")).size)
        assertEquals(4, TradeFilter.apply(all, TradeFilter.Criteria(choices = mapOf("tag" to TradeFilter.UNKNOWN))).size)
        assertEquals(listOf(win.copy(outcomeTags = listOf("TEST"))), TradeFilter.apply(listOf(win.copy(outcomeTags = listOf("TEST")), loss), TradeFilter.Criteria(choices = mapOf("tag" to "TEST"))))
    }
    @Test fun invalidRangesFailClosedAndSummariesDoNotInventRiskOrResults() {
        assertNotNull(TradeFilter.validation(TradeFilter.Criteria(ranges = mapOf("pnl" to ("2" to "1")))))
        assertNotNull(TradeFilter.validation(TradeFilter.Criteria(fromDate = "bad")))
        assertTrue(TradeFilter.apply(listOf(trade("x", 1.0)), TradeFilter.Criteria(ranges = mapOf("pnl" to ("NaN" to "")))).isEmpty())
        val s = TradeFilter.summary(listOf(trade("w", 10.0), trade("l", -5.0, risk = 0.0), trade("u", null), trade("o", null, closed = false)))
        assertEquals(1, s.wins); assertEquals(1, s.losses); assertEquals(1, s.unknown); assertEquals(1, s.open)
        assertEquals(1, s.rCount); assertEquals(2.0, s.meanR!!, 1e-9); assertEquals(2.0, s.profitFactor!!, 1e-9)
    }
    @Test fun sourceFilterSeparatesBacktests() {
        val live = trade("a", 5.0)
        val back = trade("b", 5.0, origin = PaperTrade.ORIGIN_BACKTEST)
        assertEquals(listOf(back), TradeFilter.apply(listOf(live, back), TradeFilter.Criteria(choices = mapOf("origin" to "بک‌تست"))))
    }
}
