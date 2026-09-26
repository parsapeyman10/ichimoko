package com.aurum.edge.engine

import com.aurum.edge.data.HeadlineImpactResearch
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.PublicFeedHealth
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicNewsCategory
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.ResearchState
import com.aurum.edge.data.parsePublicFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SenaRoutingTest {
    private val now = Instant.parse("2026-09-28T08:00:00Z").toEpochMilli()
    private val sena = PublicNewsFeeds.all.single { it.id == "sena_exchange" }

    @Test fun `market publisher is Iran only and is not mislabeled as Codal or FF`() {
        assertEquals(PublicNewsCategory.IRAN, sena.category)
        assertEquals("https://www.sena.ir/rss/tp/5", sena.url)
        val rss = """<rss version="2.0"><channel>
          <item><title>اطلاعیه بورس درباره مجمع شرکت</title>
          <link>https://www.sena.ir/news/123/test</link>
          <pubDate>Mon, 28 Sep 2026 07:59:00 GMT</pubDate>
          <description>متن منتشر شده در سنا، نه در کدال.</description></item>
          <item><title>ورود به دامنهٔ دیگر</title>
          <link>https://fake.example.com/news/1</link>
          <pubDate>Mon, 28 Sep 2026 07:59:00 GMT</pubDate></item>
        </channel></rss>"""
        val article = parsePublicFeed(rss, sena, now).single()
        val observed = PublicWebNewsState(listOf(article), listOf(PublicFeedHealth(sena, PublicFeedState.ONLINE, now)))
        assertEquals(ResearchState.CONTEXT, NewsResearch.headline(article, observed, ResearchSpace.IRAN_STOCKS, now).state)
        assertTrue(NewsResearch.headline(article, observed, ResearchSpace.IRAN_STOCKS, now).detail.contains("سنا"))
        assertTrue(HeadlineImpactResearch.assess(article, observed, ResearchSpace.IRAN_STOCKS, now)
            .relevance.contains("کدال"))
        assertFalse(NewsResearch.headline(article, observed, ResearchSpace.FOREX, now).state == ResearchState.CONTEXT)
        assertFalse(NewsResearch.headline(article, observed, ResearchSpace.NOBITEX, now).state == ResearchState.CONTEXT)
        assertFalse(NewsResearch.headline(article, observed, ResearchSpace.CRYPTO, now).state == ResearchState.CONTEXT)
    }
}
