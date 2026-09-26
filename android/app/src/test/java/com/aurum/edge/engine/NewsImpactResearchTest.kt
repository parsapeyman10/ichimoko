package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.ForexEvent
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsImpactResearch
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.NumericSurprise
import com.aurum.edge.data.PersianNewsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Deliberately fixed test bars, never runtime-generated prices or evidence for trading. */
class NewsImpactResearchTest {
    private val now = Instant.parse("2026-09-29T14:00:00Z").toEpochMilli()
    private val at = now - 35 * 60_000L
    private val event = ForexEvent("CPI m/m", "USD", "High", at, "0.3%", "0.2%", "0.4%")
    private val calendar = ForexCalendarState(listOf(event), now)
    private val candles = (-10..10 step 5).map { offset ->
        val start = at + offset * 60_000L
        val close = if (offset == 10) 3010.0 else 3000.0
        Candle(start, close, close + 1, close - 1, close)
    }
    private val market = MarketState(candles = candles, interval = Interval.M5)

    @Test fun `publisher grade surprise conditional direction and observed opposite move stay separate`() {
        val insight = NewsImpactResearch.gold(event, calendar, market, now)
        assertTrue(insight.importance.contains("High"))
        assertTrue(insight.relevance.contains("انتظار ناشر"))
        assertEquals(NumericSurprise.ABOVE_FORECAST, NewsResearch.surprise(event, calendar, now))
        assertTrue(insight.surprise.contains("بالاتر"))
        assertTrue(insight.scenario.contains("فشار کاهشی"))
        assertTrue(insight.scenario.contains("نه دستور فروش"))
        val move = insight.observed!!
        assertEquals(at - 5 * 60_000L, move.beforeCloseAt)
        assertEquals(at + 15 * 60_000L, move.afterCloseAt)
        assertTrue(move.percent > 0) // Price rose despite a possible bearish macro scenario.
        assertFalse(move.cached)
        assertTrue(insight.observationNote.contains("علیت"))
        // Display-only calculations MUST NOT manufacture a ninth-gate model verdict.
        assertEquals(ConfluenceStatus.UNKNOWN,
            NewsConfluence.alignment("XAU/USD", SignalAction.BUY, PersianNewsState(), now).status)
    }

    @Test fun `unemployment uses opposite sign and ambiguous titles never guess direction`() {
        val unemployment = event.copy(title = "Unemployment Rate", actual = "0.4%")
        val state = calendar.copy(events = listOf(unemployment))
        assertTrue(NewsImpactResearch.gold(unemployment, state, market, now).scenario.contains("رشد XAU/USD"))
        val lower = unemployment.copy(actual = "0.1%")
        assertTrue(NewsImpactResearch.gold(lower, state.copy(events = listOf(lower)), market, now)
            .scenario.contains("فشار کاهشی"))
        val fomc = event.copy(title = "FOMC Statement")
        assertTrue(NewsImpactResearch.gold(fomc, calendar.copy(events = listOf(fomc)), market, now)
            .scenario.contains("نگاشت مطمئن"))
        assertEquals(NumericSurprise.AS_FORECAST,
            NewsResearch.surprise(event.copy(actual = "0.3%"),
                calendar.copy(events = listOf(event.copy(actual = "0.3%"))), now))
    }

    @Test fun `future missing results mismatched units stale week and non USD cannot imply an effect`() {
        val planned = event.copy(at = now + 10 * 60_000L)
        val future = NewsImpactResearch.gold(planned, calendar.copy(events = listOf(planned)), market, now)
        assertTrue(future.surprise.contains("هنوز"))
        assertNull(future.observed)
        val missing = event.copy(actual = null)
        val noResult = NewsImpactResearch.gold(missing, calendar.copy(events = listOf(missing)), market, now)
        assertTrue(noResult.surprise.contains("نیست"))
        assertNull(noResult.observed)
        val wrongUnit = event.copy(actual = "240K")
        val mismatch = NewsImpactResearch.gold(wrongUnit, calendar.copy(events = listOf(wrongUnit)), market, now)
        assertTrue(mismatch.surprise.contains("نامعتبر"))
        assertNull(mismatch.observed)
        val notUpdated = calendar.copy(checkedAt = at - 60_000L)
        assertNull(NewsResearch.surprise(event, notUpdated, now))
        assertNull(NewsImpactResearch.gold(event, notUpdated, market, now).observed)
        val stale = calendar.copy(checkedAt = now - 21 * 60_000L)
        assertNull(NewsImpactResearch.gold(event, stale, market, now).observed)
        val eur = event.copy(country = "EUR")
        val otherCurrency = NewsImpactResearch.gold(eur, calendar.copy(events = listOf(eur)), market, now)
        assertTrue(otherCurrency.relevance.contains("EUR"))
        assertNull(otherCurrency.observed)
        assertNull(NewsImpactResearch.gold(event, calendar, market.copy(symbol = "XAG/USD"), now).observed)
    }

    @Test fun `only complete closed M1 or M5 bars can produce a read only price comparison`() {
        assertNull(NewsImpactResearch.gold(event, calendar, market.copy(interval = Interval.H1), now).observed)
        assertNull(NewsImpactResearch.gold(event, calendar,
            market.copy(candles = candles.filter { it.time != at }), now).observed)
        assertNull(NewsImpactResearch.gold(event, calendar,
            market.copy(candles = candles + candles.first()), now).observed)
        assertNull(NewsImpactResearch.gold(event, calendar,
            market.copy(candles = candles.map { if (it.time == at) it.copy(closed = false) else it }), now).observed)
        assertNull(NewsImpactResearch.gold(event, calendar,
            market.copy(candles = candles.map { if (it.time == at + 10 * 60_000L) it.copy(close = 0.0) else it }), now).observed)
        assertNull(NewsImpactResearch.gold(event, calendar, market, at + 14 * 60_000L).observed)
        val cached = NewsImpactResearch.gold(event, calendar, market.copy(showingCachedData = true), now)
        assertTrue(cached.observed!!.cached)
        assertNull(NewsImpactResearch.gold(event, calendar, market, now + 7 * 60 * 60_000L).observed)
    }
}
