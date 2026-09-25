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

/** One public CoinGecko source, read-only and NOT a trade candidate or a Binance-verified quote. */
data class PublicCoin(
    val id: String, val code: String, val name: String, val priceUsd: Double,
    val marketCapUsd: Double, val volume24hUsd: Double, val change24hPct: Double?,
    val providerAt: Long,
)

internal fun parsePublicCoins(root: JsonArray, now: Long): List<PublicCoin> {
    require(root.size in 1..20) { "شمار رمزارزهای عمومی معتبر نیست" }
    val seen = mutableSetOf<String>()
    val quotes = root.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        fun text(field: String) = (obj[field] as? JsonPrimitive)?.contentOrNull
        fun num(field: String) = text(field)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val id = text("id")?.takeIf { it.matches(Regex("[a-z0-9-]{2,90}")) } ?: return@mapNotNull null
        if (!seen.add(id)) return@mapNotNull null
        val code = text("symbol")?.uppercase(java.util.Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z0-9]{2,12}")) }
            ?: return@mapNotNull null
        val name = text("name")?.takeIf { it.length in 2..70 && '<' !in it } ?: return@mapNotNull null
        val at = text("last_updated")?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }?.takeIf { now - it in -60_000L..600_000L } ?: return@mapNotNull null
        val price = num("current_price")?.takeIf { it > 0 } ?: return@mapNotNull null
        val cap = num("market_cap")?.takeIf { it > 0 } ?: return@mapNotNull null
        val volume = num("total_volume")?.takeIf { it >= 0 } ?: return@mapNotNull null
        PublicCoin(id, code, name, price, cap, volume,
            num("price_change_percentage_24h"), at)
    }
    require(quotes.isNotEmpty()) { "قیمت زمان‌دار قابل‌استناد در این پاسخ پیدا نشد" }
    return quotes
}

enum class PublicCryptoStatus { IDLE, LOADING, OBSERVED, UNAVAILABLE }
data class PublicCryptoState(val status: PublicCryptoStatus = PublicCryptoStatus.IDLE,
                             val quotes: List<PublicCoin> = emptyList(),
                             val receivedAt: Long? = null, val error: String? = null) {
    fun recent(now: Long = System.currentTimeMillis()) = status == PublicCryptoStatus.OBSERVED &&
        receivedAt?.let { now - it in 0L..600_000L } == true
}

class PublicCryptoMarket(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private val _state = MutableStateFlow(PublicCryptoState())
    val state: StateFlow<PublicCryptoState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }
    private suspend fun refresh() = mutex.withLock {
        val elapsed = SystemClock.elapsedRealtime()
        if (attemptedAt != 0L && elapsed - attemptedAt in 0L until 60_000L) {
            // A just-opened screen and the foreground service may request the same snapshot.
            // Throttling must not erase a still-valid observation or call it an outage.
            if (!_state.value.recent()) _state.value = PublicCryptoState(PublicCryptoStatus.UNAVAILABLE,
                error = "برای سهمیهٔ عمومی، یک دقیقه بعد دوباره تلاش کنید")
            return@withLock
        }
        attemptedAt = elapsed
        _state.value = PublicCryptoState(PublicCryptoStatus.LOADING)
        try {
            val rows = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(
                    "https://api.coingecko.com/api/v3/coins/markets" +
                        "?vs_currency=usd&order=market_cap_desc&per_page=20&page=1&sparkline=false")
                    .get().header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 429) error("سهمیهٔ عمومی CoinGecko تمام شد (۴۲۹)")
                    require(response.isSuccessful) { "CoinGecko پاسخ معتبر نداد (HTTP ${response.code})" }
                    val text = response.peekBody(512_001L).string()
                    require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 512_000) {
                        "پاسخ عمومی خالی یا بزرگ است"
                    }
                    parsePublicCoins(json.parseToJsonElement(text) as? JsonArray
                        ?: error("پاسخ رمزارز فهرست JSON نیست"), System.currentTimeMillis())
                }
            }
            _state.value = PublicCryptoState(PublicCryptoStatus.OBSERVED, rows, System.currentTimeMillis())
        } catch (e: Exception) {
            _state.value = PublicCryptoState(PublicCryptoStatus.UNAVAILABLE,
                error = (e.message ?: "دادهٔ عمومی در دسترس نیست").take(125))
        }
    }
}
