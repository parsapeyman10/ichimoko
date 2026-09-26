package com.aurum.edge.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

const val NOBITEX_NOTICES_URL = "https://nobitex.ir/announcement/"

/** HTML from the public, official page. No documented notices API/RSS, publication clock or alert permission. */
data class NobitexNotice(val title: String, val url: String, val dateLabel: String?)
enum class NoticesStatus { IDLE, LOADING, OBSERVED, UNAVAILABLE }
data class NobitexNoticesState(val status: NoticesStatus = NoticesStatus.IDLE,
                               val items: List<NobitexNotice> = emptyList(),
                               val receivedAt: Long? = null, val error: String? = null) {
    fun recentReceipt(now: Long = System.currentTimeMillis()): Boolean =
        status == NoticesStatus.OBSERVED && receivedAt?.let { now - it in 0L..1_800_000L } == true
}

private val jalaliDate = Regex("[۰-۹٠-٩0-9]{4}\\s*/\\s*[۰-۹٠-٩0-9]{1,2}\\s*/\\s*[۰-۹٠-٩0-9]{1,2}")
private val noticePath = Regex("^/announcement/[a-z0-9-]+/[a-z0-9-]+/?$", RegexOption.IGNORE_CASE)

/** Parse titles ONLY from safe links to individual items. Dates are literal page labels: never
 * turn an undated or old item into a fresh release, breaking alert, AI evidence or market status.
 */
internal fun parseNobitexNotices(html: String): List<NobitexNotice> {
    val document = Jsoup.parse(html, NOBITEX_NOTICES_URL)
    val seen = mutableSetOf<String>()
    return document.select("a[href]").mapNotNull { anchor ->
        val raw = anchor.attr("href")
        val uri = runCatching { URI(NOBITEX_NOTICES_URL).resolve(raw) }.getOrNull() ?: return@mapNotNull null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
            uri.host?.lowercase(Locale.ROOT) !in setOf("nobitex.ir", "www.nobitex.ir") ||
            !noticePath.matches(uri.path.orEmpty()) || uri.rawQuery != null) return@mapNotNull null
        val canonical = "https://nobitex.ir${uri.path}"
        if (!seen.add(canonical)) return@mapNotNull null
        val heading = anchor.selectFirst("h2, h3, h4")?.text()?.trim()
        val title = (heading ?: anchor.text().replace(jalaliDate, "").trim())
            .replace(Regex("\\s+"), " ").take(180)
        if (title.length !in 5..180 || title.any { Character.isISOControl(it) } || '<' in title) return@mapNotNull null
        NobitexNotice(title, canonical, jalaliDate.find(anchor.text())?.value?.replace(Regex("\\s+"), ""))
    }.take(8)
}

/** One bounded GET every 15 minutes, only while the app is visible. HTML can change without
 * warning; parsing failure is UNKNOWN and the official page remains available via its link.
 */
class NobitexAnnouncementsRepository(private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private val _state = MutableStateFlow(NobitexNoticesState())
    val state: StateFlow<NobitexNoticesState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    internal suspend fun refresh() = mutex.withLock {
        val tick = elapsed()
        if (attemptedAt != 0L && tick - attemptedAt in 0L until 900_000L) {
            if (!_state.value.recentReceipt(clock())) _state.value = NobitexNoticesState(NoticesStatus.UNAVAILABLE,
                error = "برای صفحهٔ اطلاعیه‌ها تا نوبت بررسی بعدی صبر کنید")
            return@withLock
        }
        attemptedAt = tick
        _state.value = NobitexNoticesState(NoticesStatus.LOADING)
        try {
            val items = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(NOBITEX_NOTICES_URL).get()
                    .header("Accept", "text/html")
                    .header("User-Agent", "TraderBot/AurumEdge-1.0.0 (public notices reader)").build()
                http.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "صفحهٔ رسمی پاسخ نداد (HTTP ${response.code})" }
                    require(response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true) {
                        "پاسخ صفحه HTML نیست"
                    }
                    val bytes = response.peekBody(2_000_001L).bytes()
                    require(bytes.isNotEmpty() && bytes.size <= 2_000_000) { "صفحهٔ اطلاعیه خالی یا بزرگ است" }
                    parseNobitexNotices(bytes.toString(Charsets.UTF_8))
                }
            }
            require(items.isNotEmpty()) { "عنوان معتبر از صفحهٔ رسمی خوانده نشد؛ ساختار آن ممکن است تغییر کرده باشد" }
            _state.value = NobitexNoticesState(NoticesStatus.OBSERVED, items, clock())
        } catch (_: Exception) {
            _state.value = NobitexNoticesState(NoticesStatus.UNAVAILABLE,
                error = "دریافت/قالب HTML اطلاعیه‌های نوبیتکس در دسترس نیست؛ لینک رسمی را بررسی کنید")
        }
    }
}
