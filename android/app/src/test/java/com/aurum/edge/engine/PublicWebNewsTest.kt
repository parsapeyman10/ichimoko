package com.aurum.edge.engine

import com.aurum.edge.data.NewsGate
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.PublicFeed
import com.aurum.edge.data.PublicFeedState
import com.aurum.edge.data.PublicNewsCategory
import com.aurum.edge.data.PublicNewsFeeds
import com.aurum.edge.data.PublicWebNewsRepository
import com.aurum.edge.data.parsePublicFeed
import com.aurum.edge.data.safePublisherLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PublicWebNewsTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
    private val fa = PublicNewsFeeds.all.first { it.id == "irib" }
    private val en = PublicNewsFeeds.all.first { it.id == "fxstreet" }
    private val atom = PublicNewsFeeds.all.first { it.id == "bls_cpi" }

    private fun rss(title: String = "قیمت طلا و دلار در بازار", date: String = "Thu, 24 Sep 2026 11:40:00 GMT",
                    url: String = "https://www.irib-news.ir/fa/news/123") = """
        <?xml version="1.0"?><rss version="2.0"><channel><title>اقتصاد</title>
        <item><title>$title</title><link>$url</link><pubDate>$date</pubDate>
        <description>&lt;p&gt;خبر کوتاه &amp;amp; منبع&lt;/p&gt;</description></item></channel></rss>
    """.trimIndent()

    @Test fun `persian RSS and english Atom expose publisher headlines without inventing a verdict`() {
        val parsed = parsePublicFeed(rss(), fa, now)
        assertEquals(1, parsed.size)
        assertEquals("قیمت طلا و دلار در بازار", parsed.single().title)
        assertEquals("خبر کوتاه & منبع", parsed.single().excerpt)
        assertEquals(now - 20 * 60_000L, parsed.single().publishedAt)
        assertEquals(fa.url, parsed.single().feed.url)
        assertEquals(now - 20 * 60_000L, parsePublicFeed(
            rss(date = "Thu, 24 Sep 2026 15:10:00 +0330"), fa, now).single().publishedAt)
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom">
            <entry><title>US CPI increased in August</title>
            <link rel="self" href="https://www.bls.gov/feed/cpi.rss"/>
            <link rel="alternate" href="https://www.bls.gov/news.release/cpi.nr0.htm"/>
            <updated>2026-09-16T12:30:00Z</updated><summary>Monthly report</summary></entry>
            </feed>"""
        val result = parsePublicFeed(xml, atom, now)
        assertEquals(1, result.size)
        assertEquals("https://www.bls.gov/news.release/cpi.nr0.htm", result.single().url)
        assertTrue(result.single().publishedAt < now - 24 * 3_600_000L) // periodic != breaking news
        assertEquals(NewsGate.UNKNOWN, PersianNewsState().gate) // public headlines do not change the server gate
    }

    @Test fun `invalid timestamps or links and stale items cannot masquerade as new headlines`() {
        assertTrue(parsePublicFeed(rss(date = "24 Sep 2026 11:40"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(date = "Thu, 24 Sep 2026 12:16:00 GMT"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(date = "Mon, 21 Sep 2026 11:40:00 GMT"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(url = "https://www.irib-news.ir.evil.com/article"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(url = "http://www.irib-news.ir/article"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(url = "https://thief@www.irib-news.ir/article"), fa, now).isEmpty())
        assertTrue(parsePublicFeed(rss(title = "US CPI today"), fa, now).isEmpty())
        assertEquals(null, safePublisherLink("javascript:alert(1)", fa))
        assertEquals(null, safePublisherLink("https://www.fxstreet.com:444/news/1", en))
        try {
            parsePublicFeed("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///data/data/private'>]><rss>&x;</rss>", fa, now)
            fail("external entities must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `partial feed failure keeps cached headlines visibly non-online and never approves news gate`() = runBlocking {
        val current = System.currentTimeMillis()
        val stamp = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(current - 5 * 60_000L))
        val feedA = fa
        val feedB = fa.copy(id = "second", title = "آینهٔ آزمون", url = "https://www.yjc.ir/fa/rss/6")
        var tick = 1_000L
        var failSecond = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val second = chain.request().url.host == "www.yjc.ir"
            val code = if (second && failSecond) 503 else 200
            val link = if (second) "https://www.yjc.ir/fa/news/456" else "https://www.irib-news.ir/fa/news/123"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("unit test").body(rss(date = stamp, url = link).toResponseBody("application/rss+xml".toMediaType()))
                .build()
        }.build()
        val repository = PublicWebNewsRepository(CoroutineScope(SupervisorJob() + Dispatchers.Default), client,
            listOf(feedA, feedB), monotonicMillis = { tick })
        repository.refresh()
        assertEquals(2, repository.state.value.headlines.size)
        assertTrue(repository.state.value.feeds.all { it.state == PublicFeedState.ONLINE })
        failSecond = true
        tick += 61_000L
        repository.refresh()
        val result = repository.state.value
        assertFalse(result.loading)
        assertEquals(2, result.headlines.size) // second survives only as short-lived display cache
        assertEquals(PublicFeedState.FAILED, result.feeds.single { it.feed.id == "second" }.state)
        assertEquals(NewsGate.UNKNOWN, PersianNewsState().gate)
        assertTrue(result.headlines.all { it.publishedAt > 0 && it.url.startsWith("https://") })
    }
}
