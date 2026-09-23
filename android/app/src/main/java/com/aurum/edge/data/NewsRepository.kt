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
    val id: String,
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

/** Verdict is only model-backed when status=AVAILABLE. Keyword labels never count as AI. */
data class AiNewsVerdict(
    val status: String = "UNKNOWN",
    val symbol: String = "",
    val direction: String = "NEUTRAL",
    val confidence: Double = 0.0,
    val model: String? = null,
    val reason: String = "تحلیل هوش مصنوعی روی سرور در دسترس نیست",
    val checkedAt: Long? = null,
    val evidenceIds: List<String> = emptyList(),
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
    val ai: AiNewsVerdict = AiNewsVerdict(),
)

/** HTTPS-only read-only bridge to /api/v1/news/web. No exchange keys are ever sent. */
class NewsRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()
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
        // Never keep a prior AI confirmation while a new publisher/model check is in flight.
        _state.value = _state.value.copy(loading = true, ai = AiNewsVerdict())
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
            if (settings.read().newsBaseUrl != base) return@withLock
            _state.value = parseWebNews(root, System.currentTimeMillis())
        } catch (e: Exception) {
            if (settings.read().newsBaseUrl == base) _state.value = _state.value.copy(
                loading = false, cached = true, gate = NewsGate.UNKNOWN,
                reason = "ارتباط با خبر قطع شد؛ ورود بر پایهٔ خبر قابل تأیید نیست",
                ai = AiNewsVerdict(), error = (e.message ?: "خطای شبکه").take(100),
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

/** Parses backend contract without a network call, so nine-way tests exercise the same payload as the APK. */
internal fun parseWebNews(root: JsonObject, now: Long): PersianNewsState {
    val status = root["status"] as? JsonObject
    val guard = root["guard"] as? JsonObject
    val articles = (root["articles"] as? JsonArray).orEmpty().mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val headline = obj.string("headline")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val analysis = obj["analysis"] as? JsonObject
        PersianHeadline(
            id = id, headline = headline, summary = obj.string("summary").orEmpty(),
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
    val fresh = checkedAt != null && now - checkedAt in 0L..180_000L
    val gate = if (online && fresh && articles.isNotEmpty()) {
        runCatching { NewsGate.valueOf(guard?.string("state") ?: "UNKNOWN") }.getOrDefault(NewsGate.UNKNOWN)
    } else NewsGate.UNKNOWN
    val aiRoot = root["ai_confluence"] as? JsonObject
    val aiIds = (aiRoot?.get("evidence_ids") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }
    val ai = AiNewsVerdict(
        status = aiRoot?.string("status") ?: "UNKNOWN",
        symbol = aiRoot?.string("symbol").orEmpty(),
        direction = aiRoot?.string("direction") ?: "NEUTRAL",
        confidence = aiRoot?.string("confidence")?.toDoubleOrNull() ?: 0.0,
        model = aiRoot?.string("model"),
        reason = aiRoot?.string("reason")?.take(180) ?: "تحلیل مدل روی سرور موجود نیست",
        checkedAt = aiRoot?.string("checked_at")?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        },
        evidenceIds = aiIds,
    )
    return PersianNewsState(
        articles = articles, gate = gate,
        reason = guard?.string("reason") ?: "وضعیت توقف نامشخص است",
        provider = status?.string("provider"),
        lastCheckedAt = checkedAt,
        cached = status?.string("cached") == "true" || !online || !fresh,
        error = status?.string("error") ?: if (!fresh) "زمان بررسی خبر معتبر یا تازه نیست" else null,
        sources = sources, ai = ai,
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
