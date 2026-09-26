package com.aurum.edge.engine

import com.aurum.edge.data.NobitexAnnouncementsRepository
import com.aurum.edge.data.NoticesStatus
import com.aurum.edge.data.parseNobitexNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NobitexAnnouncementsTest {
    @Test fun `only same origin individual HTML notices with literal date labels appear`() {
        val html = """
          <html><body>
            <a href="/announcement/news/abc123/"><div><h3>اطلاعیهٔ اختلال خدمات نوبیتکس</h3><span>۱۴۰۵/۶/۲</span></div></a>
            <a href="https://nobitex.ir/announcement/updates/def456/"><h3>بروزرسانی سرویس</h3><span>۱۴۰۵/۶/۳</span></a>
            <a href="/announcement/news/abc123/"><h3>کپی</h3></a>
            <a href="https://nobitex.ir.evil.test/announcement/news/hack/"><h3>عنوان ساختگی</h3></a>
            <a href="http://nobitex.ir/announcement/news/insecure/"><h3>اتصال ناامن</h3></a>
            <a href="//evil.test/announcement/news/redirect/"><h3>تغییر مسیر</h3></a>
            <a href="/announcement/updates/a/?x=1"><h3>لینک با پارامتر</h3></a>
            <a href="/announcement/"><h3>فهرست نه خبر</h3></a>
            <a href="/announcement/updates/unknown/"><h3>اطلاعیه بدون تاریخ</h3></a>
          </body></html>
        """.trimIndent()
        val items = parseNobitexNotices(html)
        assertEquals(3, items.size)
        assertEquals("۱۴۰۵/۶/۲", items[0].dateLabel)
        assertEquals("https://nobitex.ir/announcement/news/abc123/", items[0].url)
        assertEquals("اطلاعیهٔ اختلال خدمات نوبیتکس", items[0].title)
        assertEquals("بروزرسانی سرویس", items[1].title)
        assertEquals(null, items[2].dateLabel)
        assertTrue(parseNobitexNotices("<html><body><p>هیچ پیوند معتبری نیست</p></body></html>").isEmpty())
    }

    @Test fun `notices use only a bounded keyless GET and a failed refresh removes stale items`() = runBlocking {
        var elapsed = 60_000L
        val observedAt = 1_800_000_000_000L
        var requests = 0
        var unavailable = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val req = chain.request()
            assertEquals("GET", req.method)
            assertEquals("nobitex.ir", req.url.host)
            assertEquals("/announcement/", req.url.encodedPath)
            assertFalse(req.headers.names().any { it.equals("Authorization", ignoreCase = true) })
            val body = if (unavailable) "error" else
                "<a href='/announcement/news/abc123/'><h3>اطلاعیهٔ رسمی آزمون</h3><span>۱۴۰۵/۶/۲</span></a>"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
                .code(if (unavailable) 503 else 200).message("fixture")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(body.toResponseBody()).build()
        }.build()
        val repository = NobitexAnnouncementsRepository(CoroutineScope(SupervisorJob() + Dispatchers.Default),
            http, clock = { observedAt }, elapsed = { elapsed })
        repository.refresh()
        assertEquals(NoticesStatus.OBSERVED, repository.state.value.status)
        assertEquals(1, requests)
        repository.refresh() // still within the 15-minute per-source allowance
        assertEquals(1, requests)
        unavailable = true
        elapsed += 900_001L
        repository.refresh()
        assertEquals(2, requests)
        assertEquals(NoticesStatus.UNAVAILABLE, repository.state.value.status)
        assertTrue(repository.state.value.items.isEmpty())
    }
}
