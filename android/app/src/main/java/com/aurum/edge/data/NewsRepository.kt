package com.aurum.edge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

enum class NewsGate { CLEAR, BLOCKED, UNKNOWN }

data class PersianHeadline(
    val headline: String,
    val summary: String,
    val source: String,
    val link: String?,
    val publishedAt: Long?,
    val impact: String,
    val direction: String,
    val analysisSource: String,
    val language: String = "fa",
)

data class NewsSourceStatus(
    val name: String,
    val state: String,
    val feed: String,
)

data class PersianNewsState(
    val articles: List<PersianHeadline> = emptyList(),
    val gate: NewsGate = NewsGate.UNKNOWN,
    val reason: String = "بدون اتصال به فید خبری مجاز، نبود خبر پراثر قابل تأیید نیست",
    val provider: String? = null,
    val lastCheckedAt: Long? = null,
    val cached: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val sources: List<NewsSourceStatus> = emptyList(),
)

/** HTTPS-only read-only bridge to /api/v1/news/web. No exchange keys are ever sent. */
class NewsRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PersianNewsState())
    val state: StateFlow<PersianNewsState> = _state.asStateFlow()

    fun resetAndRefresh() {
        _state.value = PersianNewsState()
        refreshNow()
    }

    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        val base = settings.read().newsBaseUrl
        val url = newsUrl(base)
        if (url == null) {
            _state.value = PersianNewsState(error = if (base.isBlank()) null else "فقط آدرس HTTPS سرور مجاز است (بدون مسیر/پورت غیر۴۴۳)")
            return@withLock
        }
        _state.value = _state.value.copy(loading = true)
        try {
            val root = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("سرور خبر پاسخ معتبر نداد (HTTP ${response.code})")
                    val body = response.peekBody(512_001L).string()
                    if (body.length > 512_000) throw IllegalStateException("پاسخ خبر بزرگ‌تر از حد مجاز است")
                    json.parseToJsonElement(body) as? JsonObject ?: error("پاسخ خبر نامعتبر است")
                }
            }
            val status = root["status"] as? JsonObject
            val guard = root["guard"] as? JsonObject
            val articles = (root["articles"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val headline = obj.string("headline")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val analysis = obj["analysis"] as? JsonObject
                PersianHeadline(
                    headline = headline, summary = obj.string("summary").orEmpty(),
                    source = obj.string("source").orEmpty(),
                    link = obj.string("url")?.takeIf { it.startsWith("https://") },
                    publishedAt = obj.string("published_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                    impact = analysis?.string("impact") ?: "UNKNOWN",
                    direction = analysis?.string("direction") ?: "NEUTRAL",
                    analysisSource = analysis?.string("source") ?: "نامشخص",
                    language = obj.string("language")?.takeIf { it in setOf("fa", "en") } ?: "fa",
                )
            }
            val sources = (status?.get("sources") as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                NewsSourceStatus(obj.string("name") ?: return@mapNotNull null,
                    obj.string("state") ?: "unavailable", obj.string("feed") ?: "")
            }
            val online = status?.string("state") == "online" && status?.string("configured") == "true"
            val checkedAt = root.string("checked_at")?.let {
                runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
            }
            val fresh = checkedAt != null && System.currentTimeMillis() - checkedAt in 0L..180_000L
            val gate = if (online && fresh && articles.isNotEmpty()) {
                runCatching { NewsGate.valueOf(guard?.string("state") ?: "UNKNOWN") }.getOrDefault(NewsGate.UNKNOWN)
            } else NewsGate.UNKNOWN
            // If preferences changed during an in-flight request, discard the old verdict.
            if (settings.read().newsBaseUrl != base) return@withLock
            _state.value = PersianNewsState(
                articles = articles, gate = gate,
                reason = guard?.string("reason") ?: "وضعیت توقف نامشخص است",
                provider = status?.string("provider"),
                lastCheckedAt = checkedAt,
                cached = status?.string("cached") == "true" || !online || !fresh,
                error = status?.string("error") ?: if (!fresh) "زمان بررسی خبر معتبر یا تازه نیست" else null,
                sources = sources,
            )
        } catch (e: Exception) {
            if (settings.read().newsBaseUrl == base) _state.value = _state.value.copy(
                loading = false, cached = true, gate = NewsGate.UNKNOWN,
                reason = "ارتباط با خبر قطع شد؛ ورود بر پایهٔ خبر قابل تأیید نیست",
                error = (e.message ?: "خطای شبکه").take(100),
            )
        }
    }

    companion object {
        /** Reject credentials, arbitrary paths/queries, cleartext or non-standard ports. */
        fun apiUrl(base: String, path: String): String? {
            if (path !in setOf("news/web", "crypto/candidates")) return null
            val uri = runCatching { URI(base.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.port !in listOf(-1, 443) || uri.path !in listOf("", "/") ||
                uri.rawQuery != null || uri.rawFragment != null) return null
            return "https://${uri.host}/api/v1/$path"
        }

        fun newsUrl(base: String): String? = apiUrl(base, "news/web")
        fun cryptoUrl(base: String): String? = apiUrl(base, "crypto/candidates")
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
