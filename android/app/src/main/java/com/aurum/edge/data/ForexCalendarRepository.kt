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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Weekly public calendar export: research/display only. Not AI evidence or a price source. */
data class ForexEvent(val title: String, val country: String, val impact: String, val at: Long,
                      val forecast: String? = null, val previous: String? = null, val actual: String? = null)
data class ForexCalendarState(
    val events: List<ForexEvent> = emptyList(),
    val checkedAt: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun online(now: Long = System.currentTimeMillis()): Boolean =
        error == null && !loading && events.isNotEmpty() && checkedAt?.let { now - it in 0L..1_200_000L } == true

    /** Display-only risk window. It does NOT grant AI CLEAR or permission to trade. */
    fun highImpactUsdWindow(now: Long = System.currentTimeMillis()): Boolean = online(now) &&
        events.any { it.country == "USD" && it.impact == "High" &&
            it.at in (now - 45 * 60_000L)..(now + 30 * 60_000L) }
}

/** No clock-less dates: reject partial/invalid/stale weeks instead of falsely declaring CLEAR. */
internal fun parseForexCalendar(root: JsonArray, now: Long): List<ForexEvent> {
    require(root.size in 1..300) { "پاسخ تقویم تهی یا بزرگ است" }
    val events = root.map { element ->
        val obj = element as? JsonObject ?: error("ردیف رویداد معتبر نیست")
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val title = str("title")?.trim()?.takeIf { it.length in 3..160 && '<' !in it }
            ?: error("عنوان رویداد معتبر نیست")
        val country = str("country")?.takeIf { it.matches(Regex("[A-Z]{3}")) }
            ?: error("کشور/ارز رویداد معتبر نیست")
        val impact = str("impact")?.takeIf { it in setOf("High", "Medium", "Low", "Holiday", "Non-Economic") }
            ?: error("درجهٔ اثر رویداد معتبر نیست")
        val time = str("date")?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        } ?: error("زمان منطقه‌دار رویداد معتبر نیست")
        require(abs(time - now) <= 8 * 86_400_000L) { "هفتهٔ تقویم کهنه/نامعتبر است" }
        fun metric(field: String) = str(field)?.trim()?.takeIf { it.isNotBlank() && it.length <= 48 &&
            it.lowercase() !in setOf("n/a", "tba", "—", "-", "?") &&
            '<' !in it && it.none { ch -> Character.isISOControl(ch) } }
        ForexEvent(title, country, impact, time,
            forecast = metric("forecast"), previous = metric("previous"), actual = metric("actual"))
    }
    require(events.any { it.country == "USD" }) { "برنامهٔ رویداد USD برای طلا در دسترس نیست" }
    return events.sortedBy { it.at }
}

/** Independent direct HTTPS display even when the optional backend news URL is not configured. */
class ForexCalendarRepository(private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private val _state = MutableStateFlow(ForexCalendarState())
    val state: StateFlow<ForexCalendarState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        val elapsed = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val nearbyRelease = _state.value.events.any { it.country == "USD" && it.impact == "High" &&
            it.at in (now - 75 * 60_000L)..(now + 40 * 60_000L) }
        val interval = if (_state.value.online(now) && !nearbyRelease) 900_000L else 60_000L
        if (attemptedAt != 0L && elapsed - attemptedAt in 0L until interval) return@withLock
        attemptedAt = elapsed
        _state.value = _state.value.copy(loading = true)
        try {
            val root = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(FOREX_CALENDAR_SOURCE_URL).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("تقویم HTTP ${response.code}")
                    val text = response.peekBody(512_001L).string()
                    require(text.length <= 512_000) { "پاسخ تقویم بزرگ است" }
                    json.parseToJsonElement(text) as? JsonArray ?: error("قالب تقویم نامعتبر است")
                }
            }
            val received = System.currentTimeMillis()
            _state.value = ForexCalendarState(parseForexCalendar(root, received), received)
        } catch (e: Exception) {
            _state.value = ForexCalendarState(error = (e.message ?: "اتصال تقویم قطع است").take(110))
        }
    }
}
