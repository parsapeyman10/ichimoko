package com.aurum.edge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Publisher RSS/Atom headlines on the phone. Display ONLY: never a NewsGate, AI verdict or trade input. */
enum class PublicNewsCategory { IRAN, MARKETS, CRYPTO, ECONOMY }

data class PublicFeed(
    val id: String,
    val title: String,
    val url: String,
    val language: String,
    val category: PublicNewsCategory,
    val maxAgeHours: Int,
    val maxItems: Int = 8,
)

object PublicNewsFeeds {
    // Fixed, publicly advertised publisher feeds. Never accept arbitrary RSS URLs or credentials.
    val all = listOf(
        PublicFeed("irib", "خبرگزاری صدا و سیما · اقتصاد", "https://www.irib-news.ir/fa/rss/6", "fa", PublicNewsCategory.IRAN, 48),
        PublicFeed("yjc", "باشگاه خبرنگاران جوان · اقتصاد", "https://www.yjc.ir/fa/rss/6", "fa", PublicNewsCategory.IRAN, 48),
        PublicFeed("eghtesaad24", "اقتصاد۲۴ · ارز", "https://eghtesaad24.ir/fa/rss/12", "fa", PublicNewsCategory.IRAN, 72),
        PublicFeed("fxstreet", "FXStreet · فارکس و طلا", "https://www.fxstreet.com/rss/news", "en", PublicNewsCategory.MARKETS, 48),
        PublicFeed("coindesk", "CoinDesk · رمزارز", "https://www.coindesk.com/arc/outboundfeeds/rss", "en", PublicNewsCategory.CRYPTO, 48),
        PublicFeed("bls_cpi", "BLS · تورم آمریکا", "https://www.bls.gov/feed/cpi.rss", "en", PublicNewsCategory.ECONOMY, 45 * 24, 2),
        PublicFeed("bls_jobs", "BLS · اشتغال آمریکا", "https://www.bls.gov/feed/empsit.rss", "en", PublicNewsCategory.ECONOMY, 45 * 24, 2),
    )
}

data class PublicHeadline(
    val title: String,
    val excerpt: String,
    val url: String,
    val publishedAt: Long,
    val receivedAt: Long,
    val feed: PublicFeed,
)

enum class PublicFeedState { PENDING, ONLINE, OUTDATED, FAILED }

data class PublicFeedHealth(
    val feed: PublicFeed,
    val state: PublicFeedState = PublicFeedState.PENDING,
    val checkedAt: Long? = null,
    val count: Int = 0,
    val detail: String = "هنوز بررسی نشده",
) {
    fun online(now: Long): Boolean = state == PublicFeedState.ONLINE &&
        checkedAt?.let { now - it in 0L..1_200_000L } == true
}

data class PublicWebNewsState(
    val headlines: List<PublicHeadline> = emptyList(),
    val feeds: List<PublicFeedHealth> = PublicNewsFeeds.all.map { PublicFeedHealth(it) },
    val lastAttemptAt: Long? = null,
    val loading: Boolean = false,
)

private const val MAX_RSS_BYTES = 1_000_000L
private const val NEWS_CACHE_MILLIS = 24 * 3_600_000L
private val unsafeXml = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
private val whitespace = Regex("\\s+")

/** Parse only feed metadata, not article pages. Missing/bad time or an off-domain link is NOT a headline. */
internal fun parsePublicFeed(xml: String, feed: PublicFeed, receivedAt: Long): List<PublicHeadline> {
    require(xml.length <= MAX_RSS_BYTES && !unsafeXml.containsMatchIn(xml)) { "خوراک بزرگ یا ناامن است" }
    val document = Jsoup.parse(xml, feed.url, Parser.xmlParser())
    val root = document.children().firstOrNull { it.localTag() in setOf("rss", "feed") }
        ?: error("این پاسخ RSS/Atom نیست")
    val entries = (if (root.localTag() == "rss") {
        root.children().firstOrNull { it.localTag() == "channel" }?.children()?.filter { it.localTag() == "item" }
    } else root.children().filter { it.localTag() == "entry" }).orEmpty()
    require(entries.isNotEmpty()) { "خوراک بدون ردیف خبری است" }
    val seen = mutableSetOf<String>()
    return entries.take(100).mapNotNull { entry ->
        val title = cleanFeedText(entry.field("title")?.text().orEmpty(), 220)
        if (title.length < 3 || (feed.language == "fa" && title.none { it in '\u0600'..'\u06ff' })) return@mapNotNull null
        val linkNode = entry.field("link") ?: return@mapNotNull null
        val link = safePublisherLink(
            if (root.localTag() == "rss") linkNode.text() else
                entry.children().firstOrNull { it.localTag() == "link" && it.attr("rel") in setOf("", "alternate") }
                    ?.attr("href").orEmpty(), feed,
        ) ?: return@mapNotNull null
        if (!seen.add(link)) return@mapNotNull null
        val dateText = entry.field("published", "pubdate", "updated", "date")?.text().orEmpty()
        val at = parseFeedDate(dateText) ?: return@mapNotNull null
        if (receivedAt - at !in -15 * 60_000L..feed.maxAgeHours * 3_600_000L) return@mapNotNull null
        val excerpt = cleanFeedText(entry.field("description", "summary")?.text().orEmpty(), 260)
        PublicHeadline(title, excerpt, link, at, receivedAt, feed)
    }.sortedByDescending { it.publishedAt }.take(feed.maxItems)
}

private fun Element.localTag(): String = tagName().substringAfterLast(':').lowercase(Locale.ROOT)
private fun Element.field(vararg names: String): Element? =
    children().firstOrNull { it.localTag() in names }

private fun cleanFeedText(text: String, limit: Int): String =
    whitespace.replace(Jsoup.parseBodyFragment(text).text(), " ").trim().take(limit)

internal fun safePublisherLink(link: String, feed: PublicFeed): String? {
    val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
    val expected = URI(feed.url).host.lowercase(Locale.ROOT).removePrefix("www.")
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
        uri.host?.lowercase(Locale.ROOT) !in setOf(expected, "www.$expected") ||
        uri.path.isNullOrBlank()) return null
    return uri.toString()
}

private fun parseFeedDate(raw: String): Long? =
    runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()

/** Independent from NewsRepository.state: headlines here cannot approve AI/news confluence. */
class PublicWebNewsRepository(
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).build(),
    private val feeds: List<PublicFeed> = PublicNewsFeeds.all,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val mutex = Mutex()
    private var lastAttemptMonotonic = 0L
    private val _state = MutableStateFlow(PublicWebNewsState(feeds = feeds.map { PublicFeedHealth(it) }))
    val state: StateFlow<PublicWebNewsState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    /** At most one request/source/minute even if the user repeatedly presses refresh. */
    internal suspend fun refresh() = mutex.withLock {
        val clock = monotonicMillis()
        if (lastAttemptMonotonic != 0L && clock - lastAttemptMonotonic in 0L until 60_000L) return@withLock
        lastAttemptMonotonic = clock
        _state.value = _state.value.copy(loading = true)
        try {
            val results = withContext(Dispatchers.IO) {
                coroutineScope { feeds.map { feed -> async { fetchOne(feed) } }.awaitAll() }
            }
            val now = System.currentTimeMillis()
            val old = _state.value.headlines
            val updates = results.map { (feed, articles, status, detail) ->
                val retained = if (status == PublicFeedState.ONLINE) articles else old.filter {
                    it.feed.id == feed.id && now - it.receivedAt in 0L..NEWS_CACHE_MILLIS &&
                        now - it.publishedAt in -15 * 60_000L..feed.maxAgeHours * 3_600_000L
                }
                retained to PublicFeedHealth(feed, status, now, if (status == PublicFeedState.ONLINE) articles.size else 0, detail)
            }
            _state.value = PublicWebNewsState(
                headlines = updates.flatMap { it.first }.sortedByDescending { it.publishedAt },
                feeds = updates.map { it.second }, lastAttemptAt = now,
            )
        } catch (_: Exception) {
            // Unexpected failure leaves previous observations visible but never reported as online.
            _state.value = _state.value.copy(loading = false, lastAttemptAt = System.currentTimeMillis(),
                feeds = feeds.map { PublicFeedHealth(it, PublicFeedState.FAILED, System.currentTimeMillis(),
                    detail = "دریافت ناموفق؛ تیترهای قبلی فقط کش نمایشی‌اند") })
        }
    }

    private fun fetchOne(feed: PublicFeed): FeedResult {
        return try {
            val request = Request.Builder().url(feed.url)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                .header("User-Agent", "AurumEdge/1.0 (publisher RSS reader)").build()
            val xml = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return FeedResult(feed, emptyList(), PublicFeedState.FAILED,
                    "ناشر پاسخ HTTP ${response.code} داد؛ تغییر مسیر/محدودیت دنبال نشد")
                val body = response.peekBody(MAX_RSS_BYTES + 1).bytes()
                require(body.size <= MAX_RSS_BYTES) { "حجم خوراک بیش از حد است" }
                body.toString(Charsets.UTF_8)
            }
            val articles = parsePublicFeed(xml, feed, System.currentTimeMillis())
            if (articles.isEmpty()) FeedResult(feed, emptyList(), PublicFeedState.OUTDATED,
                "خوراک پاسخ داد، ولی تیتر با زمان انتشار و لینک معتبر در بازهٔ مجاز یافت نشد")
            else FeedResult(feed, articles, PublicFeedState.ONLINE, "${articles.size} تیتر تاریخ‌دار دریافت شد")
        } catch (e: Exception) {
            // Only fixed categories: never show arbitrary exception text, network URLs or secrets.
            val detail = when (e) {
                is UnknownHostException -> "DNS ناشر پاسخ نداد؛ اینترنت/DNS گوشی را بررسی کنید"
                is SocketTimeoutException -> "دریافت خوراک به مهلت خورد؛ اینترنت گوشی یا ناشر کند است"
                is SSLException -> "اتصال امن HTTPS برقرار نشد؛ ساعت گوشی/شبکه/گواهی ناشر را بررسی کنید"
                is IllegalArgumentException -> "خوراک بزرگ یا ناامن است؛ نمایش داده نشد"
                is IllegalStateException -> "پاسخ ناشر قالب RSS/Atom معتبر ندارد"
                else -> "اتصال یا قالب خوراک ناشر در دسترس نیست"
            }
            FeedResult(feed, emptyList(), PublicFeedState.FAILED, detail)
        }
    }

    private data class FeedResult(
        val feed: PublicFeed, val articles: List<PublicHeadline>,
        val status: PublicFeedState, val detail: String,
    )
}
