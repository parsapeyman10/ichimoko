package com.aurum.edge.engine

import com.aurum.edge.data.HeadlineImpactResearch
import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.PublicFeed
import com.aurum.edge.data.PublicFeedHealth
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicHeadline
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.ResearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HeadlineImpactResearchTest {
    private val now = Instant.parse("2026-09-29T14:00:00Z").toEpochMilli()
    private fun feed(id: String): PublicFeed = PublicNewsFeeds.all.single { it.id == id }
    private fun item(feed: PublicFeed, title: String) = PublicHeadline(title, "ناشر/چکیده", feed.url,
        now - 60_000L, now, feed)
    private fun state(item: PublicHeadline) = PublicWebNewsState(headlines = listOf(item),
        feeds = listOf(PublicFeedHealth(item.feed, PublicFeedState.ONLINE, now)))

    @Test fun `forex publisher headlines do not inherit FF rating or event reaction`() {
        val fx = item(feed("fxstreet"), "Gold and US CPI outlook")
        val insight = HeadlineImpactResearch.assess(fx, state(fx), ResearchSpace.FOREX, now)
        assertEquals(ResearchState.CONTEXT, insight.note.state)
        assertTrue(insight.priority.contains("موضوعی"))
        assertTrue(insight.relevance.contains("درجهٔ FF"))
        assertTrue(insight.possibleDirection.contains("معلوم نیست"))
        assertTrue(insight.observedEffect.contains("تقویم"))
        val bls = item(feed("bls_cpi"), "Consumer Price Index release")
        val official = HeadlineImpactResearch.assess(bls, state(bls), ResearchSpace.FOREX, now)
        assertTrue(official.priority.contains("BLS"))
        assertTrue(official.relevance.contains("رسمی"))
        assertFalse(official.relevance.contains("High"))
        assertEquals(NewsGate.UNKNOWN, PersianNewsState().gate)
    }

    @Test fun `crypto context is not official Nobitex news or an IRT reaction`() {
        val btc = item(feed("coindesk"), "Bitcoin ETF debate")
        val crypto = HeadlineImpactResearch.assess(btc, state(btc), ResearchSpace.CRYPTO, now)
        val nobitex = HeadlineImpactResearch.assess(btc, state(btc), ResearchSpace.NOBITEX, now)
        assertTrue(crypto.priority.contains("موضوعی"))
        assertTrue(crypto.relevance.contains("BTC"))
        assertTrue(nobitex.relevance.contains("نه اطلاعیهٔ نوبیتکس"))
        assertTrue(nobitex.observedEffect.contains("ریالی یا USDT"))
        assertEquals(ResearchState.UNKNOWN,
            HeadlineImpactResearch.assess(btc, state(btc), ResearchSpace.FOREX, now).note.state)
    }

    @Test fun `Iran economy headline needs Codal document and named stock to assess relevance`() {
        val iran = item(feed("irib"), "گزارش صورت مالی شرکت در بورس")
        val insight = HeadlineImpactResearch.assess(iran, state(iran), ResearchSpace.IRAN_STOCKS, now)
        assertTrue(insight.priority.contains("کدال"))
        assertTrue(insight.relevance.contains("اصل سند"))
        assertTrue(insight.possibleDirection.contains("نامعلوم"))
        assertTrue(insight.observedEffect.contains("TTM"))
        assertEquals(ResearchState.UNKNOWN,
            HeadlineImpactResearch.assess(iran, state(iran), ResearchSpace.CRYPTO, now).note.state)
    }

    @Test fun `stale failed loading or mismatched receipt cannot carry a priority`() {
        val btc = item(feed("coindesk"), "Bitcoin hack")
        val healthy = state(btc)
        listOf(
            healthy.copy(loading = true),
            healthy.copy(feeds = listOf(PublicFeedHealth(btc.feed, PublicFeedState.FAILED, now))),
            healthy.copy(feeds = listOf(PublicFeedHealth(btc.feed, PublicFeedState.ONLINE, now - 60_000L))),
        ).forEach { invalid ->
            val insight = HeadlineImpactResearch.assess(btc, invalid, ResearchSpace.CRYPTO, now)
            assertEquals(ResearchState.UNKNOWN, insight.note.state)
            assertTrue(insight.priority.contains("نامعلوم"))
            assertTrue(insight.possibleDirection.contains("نامعلوم"))
        }
        val tooOld = HeadlineImpactResearch.assess(btc, healthy, ResearchSpace.CRYPTO,
            now + 24 * 3_600_000L)
        assertEquals(ResearchState.UNKNOWN, tooOld.note.state)
    }
}
