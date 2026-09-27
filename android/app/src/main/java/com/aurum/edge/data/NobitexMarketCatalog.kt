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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class NobitexCatalogStatus { IDLE, LOADING, DONE, FAILED }

data class NobitexCatalogEntry(val market: NobitexMarket, val volumeDst: Double?, val dayChangePct: Double?)

data class NobitexCatalogState(
    val status: NobitexCatalogStatus = NobitexCatalogStatus.IDLE,
    val markets: List<NobitexCatalogEntry> = emptyList(),
    val fetchedAt: Long? = null,
    val error: String? = null,
) {
    /** Never an empty/synthetic default: BTC/USDT until the real catalog loads at least once. */
    val marketsOrFallback: List<NobitexMarket> get() =
        markets.map { it.market }.ifEmpty { listOf(NobitexMarket.BTC_USDT) }
}

/**
 * Discovers ALL live Nobitex USDT spot markets from the exchange's OWN public endpoint — this is
 * what makes the app cover "همه" (all) coins Nobitex actually lists, not a hand-picked shortlist.
 * Per Nobitex's own docs: omitting srcCurrency returns stats for every base against the given
 * dstCurrency. Closed/invalid markets are dropped; nothing here is invented.
 */
class NobitexMarketCatalog(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(NobitexCatalogState())
    val state: StateFlow<NobitexCatalogState> = _state.asStateFlow()

    /** Listings barely change intra-day; a longer cooldown keeps this well under Nobitex's 20 req/min cap. */
    private val cooldownMillis = 10 * 60_000L

    fun refreshNow() {
        scope.launch { refresh(force = true) }
    }

    fun ensureLoaded() {
        if (_state.value.status == NobitexCatalogStatus.IDLE) scope.launch { refresh(force = false) }
    }

    private suspend fun refresh(force: Boolean) = mutex.withLock {
        val now = clock()
        val last = _state.value.fetchedAt
        if (!force && last != null && now - last < cooldownMillis && _state.value.status == NobitexCatalogStatus.DONE) return@withLock
        _state.value = _state.value.copy(status = NobitexCatalogStatus.LOADING)
        try {
            val root = withContext(Dispatchers.IO) {
                val request = Request.Builder().url("https://apiv2.nobitex.ir/market/stats?dstCurrency=usdt")
                    .get().header("User-Agent", "TraderBot/AurumEdge-1.0.0").header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429) error("سهمیهٔ API عمومی نوبیتکس تمام شده است (۴۲۹)")
                    require(response.isSuccessful) { "پاسخ فهرست بازارهای نوبیتکس نامعتبر است (HTTP ${response.code})" }
                    val body = response.peekBody(4_000_000L).string()
                    require(body.isNotBlank() && body.length <= 4_000_000) { "پاسخ فهرست بازارها خالی یا بیش‌ازحد بزرگ است" }
                    json.parseToJsonElement(body) as? JsonObject ?: error("ساختار JSON فهرست بازارها معتبر نیست")
                }
            }
            require((root["status"] as? JsonPrimitive)?.content == "ok") { "وضعیت پاسخ فهرست بازارها معتبر نیست" }
            val stats = root["stats"] as? JsonObject ?: error("فهرست بازارها در پاسخ نیست")
            fun JsonObject.raw(key: String) = (this[key] as? JsonPrimitive)?.content
            val entries = stats.entries.mapNotNull { (key, value) ->
                if (!key.endsWith("-usdt")) return@mapNotNull null
                val base = key.removeSuffix("-usdt").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val obj = value as? JsonObject ?: return@mapNotNull null
                val closed = obj.raw("isClosed")
                val bestBuy = obj.raw("bestBuy")?.toDoubleOrNull()
                val bestSell = obj.raw("bestSell")?.toDoubleOrNull()
                val latest = obj.raw("latest")?.toDoubleOrNull()
                val volume = obj.raw("volumeDst")?.toDoubleOrNull()
                val change = obj.raw("dayChange")?.toDoubleOrNull()
                val valid = closed == "false" && bestBuy != null && bestBuy.isFinite() && bestBuy > 0 &&
                    bestSell != null && bestSell.isFinite() && bestSell > 0 && latest != null && latest.isFinite() && latest > 0
                if (!valid) return@mapNotNull null
                NobitexCatalogEntry(NobitexMarket.forUsdtBase(base), volume, change)
            }.sortedByDescending { it.volumeDst ?: 0.0 }
            require(entries.isNotEmpty()) { "هیچ بازار تتری فعالی در پاسخ نوبیتکس پیدا نشد" }
            _state.value = NobitexCatalogState(NobitexCatalogStatus.DONE, entries, now)
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = NobitexCatalogStatus.FAILED,
                error = e.message?.take(160) ?: "دریافت فهرست بازارهای نوبیتکس ناموفق بود")
        }
    }
}
