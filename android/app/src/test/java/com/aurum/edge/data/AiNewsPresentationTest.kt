package com.aurum.edge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiNewsPresentationTest {
    private val now = 1_800_000_000_000L
    private val feed = PublicFeed("publisher", "ناشر", "https://example.org/rss", "en", PublicNewsCategory.MARKETS, 48)
    private val headline = PublicHeadline("Gold rises", "", "https://example.org/news/1", now - 60_000L, now, feed)
    private val article = PersianHeadline("evidence-1", headline.title, "", feed.title, headline.url,
        headline.publishedAt, "HIGH", "NEUTRAL", "نه AI")
    private val verdict = AiNewsVerdict("ADVISORY", "XAU/USD", "BUY", 64.0, "real-model",
        "خلاصهٔ کوتاه مدل با شاهد همین خبر", now, listOf(article.id))
    private val state = PersianNewsState(articles = listOf(article),
        sources = listOf(NewsSourceStatus(feed.title, "online", feed.url)),
        lastCheckedAt = now, aiBySymbol = mapOf("XAU/USD" to verdict))

    @Test fun `high impact model analysis is shown as advisory even when trade gate blocked`() {
        val rows = AiNewsPresentation.forHeadline(headline, state.copy(gate = NewsGate.BLOCKED), now)
        assertEquals(1, rows.size)
        assertEquals("XAU/USD", rows.single().symbol)
        assertEquals(64.0, rows.single().confidence, 0.001)
    }

    @Test fun `unrelated stale or fake verdict cannot be called an AI news summary`() {
        assertTrue(AiNewsPresentation.forHeadline(headline.copy(url = "https://example.org/news/2"), state, now).isEmpty())
        assertTrue(AiNewsPresentation.forHeadline(headline, state.copy(aiBySymbol = mapOf(
            "XAU/USD" to verdict.copy(model = "deterministic-fallback"))), now).isEmpty())
        assertTrue(AiNewsPresentation.forHeadline(headline, state, now + 181_000L).isEmpty())
        assertTrue(AiNewsPresentation.forHeadline(headline, state.copy(sources = emptyList()), now).isEmpty())
    }
}
