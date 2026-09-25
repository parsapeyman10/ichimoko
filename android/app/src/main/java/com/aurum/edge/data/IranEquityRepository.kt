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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class EquityBoardStatus { UNCONFIGURED, LOADING, OBSERVED, UNAVAILABLE }

data class EquityBoardState(
    val status: EquityBoardStatus = EquityBoardStatus.UNCONFIGURED,
    val rows: List<EquityRow> = emptyList(),
    /** HTTP receipt time only; provider rows have clock HH:mm:ss WITHOUT a date. */
    val receivedAt: Long? = null,
    val error: String? = null,
) {
    fun recentReceipt(now: Long = System.currentTimeMillis()): Boolean =
        status == EquityBoardStatus.OBSERVED && receivedAt?.let { now - it in 0L..600_000L } == true
}

/** Third-party read-only BrsApi mirror of TSETMC. No broker authentication and no orders. */
class IranEquityRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().followRedirects(false)
        .callTimeout(35, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private val _state = MutableStateFlow(EquityBoardState())
    val state: StateFlow<EquityBoardState> = _state.asStateFlow()

    fun clear() { _state.value = EquityBoardState() }
    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        val configured = settings.read()
        val key = configured.stockDataKey
        if (configured.workspaceId != "iran_stocks" || !key.matches(Regex("[A-Za-z0-9_-]{10,80}"))) {
            _state.value = EquityBoardState(error = if (key.isBlank()) null else "کلید دادهٔ خواندنی نامعتبر است")
            return@withLock
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (attemptedAt != 0L && elapsed - attemptedAt in 0L until 180_000L) {
            _state.value = EquityBoardState(EquityBoardStatus.UNAVAILABLE,
                error = "برای رعایت سهمیه، سه دقیقه بین دریافت‌ها صبر کنید؛ مشاهدهٔ پیشین برای غربال تازه معتبر نیست")
            return@withLock
        }
        attemptedAt = elapsed
        _state.value = EquityBoardState(EquityBoardStatus.LOADING) // never pass old rows off as a fresh scan
        try {
            val data = withContext(Dispatchers.IO) {
                val url = HttpUrl.Builder().scheme("https").host("api.brsapi.ir")
                    .addPathSegments("Tsetmc/AllSymbols.php")
                    .addQueryParameter("key", key).addQueryParameter("type", "1").build()
                val request = Request.Builder().url(url).get().header("Accept", "application/json")
                    .header("User-Agent", "AurumEdge/1.0 (read-only stock research)").build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429) error("سهمیهٔ منبع داده تمام شد (۴۲۹)")
                    require(response.isSuccessful) { "پاسخ سرویس تابلو نامعتبر است (HTTP ${response.code})" }
                    // The provider's AllSymbols list is large; bound both bytes and number of rows.
                    val body = response.peekBody(8_000_001L).string()
                    require(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= 8_000_000) {
                        "پاسخ تابلو تهی یا بزرگ است"
                    }
                    val root = json.parseToJsonElement(body) as? JsonArray
                        ?: error("فهرست سهام JSON معتبر نیست")
                    parseEquityRows(root)
                }
            }
            if (settings.read().let { it.workspaceId == "iran_stocks" && it.stockDataKey == key }) {
                _state.value = EquityBoardState(EquityBoardStatus.OBSERVED, data, System.currentTimeMillis())
            } else _state.value = EquityBoardState()
        } catch (e: Exception) {
            _state.value = EquityBoardState(EquityBoardStatus.UNAVAILABLE,
                error = when (e) {
                    is IllegalArgumentException -> e.message?.take(120)
                    else -> "دریافت یا ساختار تابلو نامعتبر است؛ هیچ ردیفی برای غربال فعال نگه نداشتیم"
                })
            // Never log/echo the request URL: provider keys are sent as query parameters.
        }
    }
}
