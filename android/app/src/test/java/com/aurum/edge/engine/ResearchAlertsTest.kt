package com.aurum.edge.engine

import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.PublicFeedHealth
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicHeadline
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.data.ResearchAlerts
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.parseForexCalendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ResearchAlertsTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private fun calendar(eventTime: Long, actual: String? = null, checkedAt: Long = now): ForexCalendarState {
        val release = actual?.let { ",\"actual\":\"$it\"" }.orEmpty()
        val json = """[{"title":"Core CPI y/y","country":"USD",
            "date":"${Instant.ofEpochMilli(eventTime)}","impact":"High","forecast":"2.1%","previous":"2.0%"$release}]"""
        return ForexCalendarState(parseForexCalendar(Json.parseToJsonElement(json) as JsonArray, now), checkedAt)
    }

    @Test fun `FF scheduled pending and published alerts never pretend there was a result`() {
        val scheduled = calendar(now + 15 * 60_000L)
        assertEquals(1, ResearchAlerts.forex(scheduled, now).size)
        assertTrue(ResearchAlerts.forex(scheduled, now).single().text.contains("نتیجه هنوز منتشر نشده"))
        val pending = calendar(now - 60_000L)
        assertTrue(ResearchAlerts.forex(pending, now).single().text.contains("نتیجه در این خروجی نیست"))
        val released = calendar(now - 60_000L, "2.5%")
        assertTrue(ResearchAlerts.forex(released, now).single().text.contains("بالاتر از پیش‌بینی"))
        assertFalse(ResearchAlerts.forex(released, now).single().title.contains("معاملهٔ ثبت"))
        assertTrue(ResearchAlerts.forex(calendar(now - 60_000L, checkedAt = now - 21 * 60_000L), now).isEmpty())
        assertTrue(ResearchAlerts.forex(calendar(now - 16 * 60_000L), now).isEmpty())
    }

    @Test fun `only dated fresh same-space publisher headline can inform a research notification`() {
        val feed = PublicNewsFeeds.all.first { it.id == "coindesk" }
        val item = PublicHeadline("Bitcoin ETF decision", "", "https://www.coindesk.com/asset", now - 60_000L,
            now, feed)
        val state = PublicWebNewsState(listOf(item), listOf(PublicFeedHealth(feed, PublicFeedState.ONLINE, now)))
        assertTrue(ResearchAlerts.latestHeadline(state, ResearchSpace.NOBITEX, now)!!.title.contains("نه اطلاعیه"))
        assertEquals(null, ResearchAlerts.latestHeadline(state, ResearchSpace.FOREX, now))
        assertEquals(null, ResearchAlerts.latestHeadline(state, ResearchSpace.IRAN_STOCKS, now))
        assertEquals(null, ResearchAlerts.latestHeadline(state.copy(feeds = listOf(PublicFeedHealth(feed,
            PublicFeedState.FAILED, now))), ResearchSpace.CRYPTO, now))
        assertEquals(null, ResearchAlerts.latestHeadline(state, ResearchSpace.CRYPTO, now + 11 * 60_000L))
    }
}
