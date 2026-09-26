package com.aurum.edge.engine

import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.PublicFeedHealth
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicHeadline
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.ResearchState
import com.aurum.edge.data.parseForexCalendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Real public FF export shape; results are fixture inputs, never manufactured at runtime. */
class NewsResearchTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private fun calendar(at: Long, actual: String? = null, forecast: String = "210K"): ForexCalendarState {
        val date = java.time.Instant.ofEpochMilli(at).toString()
        val result = actual?.let { ",\"actual\":\"$it\"" }.orEmpty()
        val json = """[{"title":"Non-Farm Employment Change","country":"USD","date":"$date",
            "impact":"High","forecast":"$forecast","previous":"190K"$result}]"""
        val events = parseForexCalendar(Json.parseToJsonElement(json) as JsonArray, now)
        return ForexCalendarState(events, now)
    }

    @Test fun `scheduled event and elapsed event without actual are not released results`() {
        val planned = calendar(now + 20 * 60_000L)
        val upcoming = NewsResearch.gold(planned.events.single(), planned, now)
        assertEquals(ResearchState.SCHEDULED, upcoming.state)
        assertTrue(upcoming.detail.contains("نتیجه هنوز منتشر نشده"))
        val passed = calendar(now - 10 * 60_000L)
        val missing = NewsResearch.gold(passed.events.single(), passed, now)
        assertEquals(ResearchState.AWAITING_RESULT, missing.state)
        assertTrue(missing.detail.contains("نتیجهٔ واقعی نیستند"))
        assertEquals(NewsGate.UNKNOWN, com.aurum.edge.data.PersianNewsState().gate)
    }

    @Test fun `numeric surprise is only a comparison not an XAU direction`() {
        val released = calendar(now - 5 * 60_000L, actual = "220K")
        val note = NewsResearch.gold(released.events.single(), released, now)
        assertEquals(ResearchState.PUBLISHED, note.state)
        assertTrue(note.detail.contains("بالاتر از پیش‌بینی"))
        assertTrue(note.detail.contains("نه تحلیل مدل/سیگنال"))
        assertFalse(note.detail.contains("BUY") || note.detail.contains("SELL"))
        val mixed = calendar(now - 5 * 60_000L, actual = "5.0%", forecast = "210K")
        assertTrue(NewsResearch.gold(mixed.events.single(), mixed, now).detail.contains("هم‌واحد"))
        val stale = released.copy(checkedAt = now - 21 * 60_000L)
        assertEquals(ResearchState.UNKNOWN, NewsResearch.gold(stale.events.single(), stale, now).state)
        assertEquals(ResearchState.AWAITING_RESULT, NewsResearch.gold(released.events.single(),
            released.copy(checkedAt = now - 10 * 60_000L), now).state)
    }

    @Test fun `cross workspace cached and outdated publisher headlines never become insight evidence`() {
        val feed = PublicNewsFeeds.all.single { it.id == "coindesk" }
        val item = PublicHeadline("Bitcoin ETF update", "Excerpt", "https://www.coindesk.com/a", now - 60_000L,
            now, feed)
        val fresh = PublicWebNewsState(headlines = listOf(item),
            feeds = listOf(PublicFeedHealth(feed, PublicFeedState.ONLINE, now)))
        assertEquals(ResearchState.CONTEXT, NewsResearch.headline(item, fresh, ResearchSpace.CRYPTO, now).state)
        assertEquals(ResearchState.UNKNOWN, NewsResearch.headline(item, fresh, ResearchSpace.FOREX, now).state)
        assertTrue(NewsResearch.headline(item, fresh, ResearchSpace.NOBITEX, now).detail.contains("اطلاعیهٔ رسمی نوبیتکس"))
        assertEquals(ResearchState.UNKNOWN, NewsResearch.headline(item, fresh.copy(loading = true),
            ResearchSpace.CRYPTO, now).state)
        assertEquals(ResearchState.UNKNOWN, NewsResearch.headline(item, fresh, ResearchSpace.CRYPTO,
            now + 24 * 3_600_000L).state)
        assertEquals(ResearchState.UNKNOWN, NewsResearch.headline(item, fresh.copy(feeds = listOf(
            PublicFeedHealth(feed, PublicFeedState.FAILED, now))), ResearchSpace.CRYPTO, now).state)
    }
}
