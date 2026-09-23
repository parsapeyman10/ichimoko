package com.aurum.edge.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** A SCREENING result, never an order or prediction of a future pump. */
data class CryptoCandidate(
    val id: String,
    val name: String,
    val symbol: String,
    val priceUsd: Double,
    val marketCapUsd: Double,
    val volume24hUsd: Double,
    val binanceVolumeUsdt: Double,
    val change1hPct: Double,
    val change24hPct: Double,
    val change7dPct: Double,
    val change3hPct: Double,
    val volumeRatio3h: Double,
    val takerBuyRatio3h: Double,
    val spreadPct: Double,
    val supplyRatio: Double,
    val coingeckoAt: Long,
    val coingeckoPairAt: Long,
    val binanceAt: Long,
    val candleAt: Long,
    val link: String?,
)

enum class CryptoScanStatus { UNCONFIGURED, LOADING, ONLINE, UNAVAILABLE }

data class CryptoScanState(
    val status: CryptoScanStatus = CryptoScanStatus.UNCONFIGURED,
    val candidates: List<CryptoCandidate> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
    val scanned: Int = 0,
    val preselected: Int = 0,
    val provider: String = "CoinGecko + Binance Spot",
    val checkedAt: Long? = null,
    val cached: Boolean = false,
    val error: String? = null,
)

/** Reads only the fixed HTTPS endpoint on the same configured backend as web news. */
class CryptoRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CryptoScanState())
    val state: StateFlow<CryptoScanState> = _state.asStateFlow()

    fun resetAndRefresh() {
        _state.value = CryptoScanState()
        refreshNow()
    }

    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        val base = settings.read().newsBaseUrl
        val url = NewsRepository.cryptoUrl(base)
        if (url == null) {
            _state.value = CryptoScanState(error = if (base.isBlank()) null else "نشانی HTTPS سرور نامعتبر است")
            return@withLock
        }
        _state.value = CryptoScanState(status = CryptoScanStatus.LOADING) // hide old candidates immediately
        try {
            val root = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("سرور غربالگری پاسخ معتبر نداد (HTTP ${response.code})")
                    val body = response.peekBody(512_001L).string()
                    if (body.length > 512_000) error("پاسخ غربالگری بیش از حد بزرگ است")
                    json.parseToJsonElement(body) as? JsonObject ?: error("پاسخ غربالگری نامعتبر است")
                }
            }
            if (settings.read().newsBaseUrl != base) return@withLock // discard in-flight old server
            val status = root["status"] as? JsonObject ?: error("وضعیت منبع مشخص نیست")
            val provider = status.text("provider") ?: "CoinGecko + Binance Spot"
            val checkedAt = root.text("checked_at").toMillis()
            if (status.text("state") != "online") {
                _state.value = CryptoScanState(status = CryptoScanStatus.UNAVAILABLE,
                    provider = provider, checkedAt = checkedAt,
                    error = status.text("error") ?: "دادهٔ دو منبع در دسترس نیست؛ نتیجهٔ قبلی نمایش داده نمی‌شود")
                return@withLock
            }
            val now = System.currentTimeMillis()
            val verifiedAt = checkedAt ?: error("زمان بررسی سرور نامشخص است")
            if (!fresh(verifiedAt, now, 180_000L)) error("زمان بررسی سرور قدیمی یا نامعتبر است")
            val filters = (root["filters"] as? JsonObject)?.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            }.orEmpty()
            val raw = root["candidates"] as? JsonArray ?: error("فهرست غربالگری نامعتبر است")
            val candidates = raw.map { item ->
                val obj = item as? JsonObject ?: error("مدرک نامزد نامعتبر است")
                parseCandidate(obj, now)
            }
            val scanned = root.text("scanned")?.toIntOrNull() ?: error("دامنهٔ اسکن نامشخص است")
            val preselected = root.text("preselected")?.toIntOrNull() ?: error("دامنهٔ بررسی نامشخص است")
            require(scanned in 1..200 && preselected in 0..12 && candidates.size <= preselected) {
                "دامنهٔ پاسخ با غربالگر ثابت سازگار نیست"
            }
            _state.value = CryptoScanState(CryptoScanStatus.ONLINE, candidates, filters,
                scanned, preselected, provider, verifiedAt, cached = status.text("cached") == "true")
            // A page left open must not keep displaying once-fresh evidence indefinitely.
            scope.launch {
                delay((verifiedAt + 180_001L - System.currentTimeMillis()).coerceAtLeast(0L))
                mutex.withLock {
                    val current = _state.value
                    if (current.status == CryptoScanStatus.ONLINE && current.checkedAt == verifiedAt &&
                        !fresh(verifiedAt, System.currentTimeMillis(), 180_000L)) {
                        _state.value = CryptoScanState(status = CryptoScanStatus.UNAVAILABLE,
                            error = "اعتبار زمانی غربالگری تمام شد؛ برای بررسی دوباره بزنید")
                    }
                }
            }
        } catch (e: Exception) {
            if (settings.read().newsBaseUrl == base) _state.value = CryptoScanState(
                status = CryptoScanStatus.UNAVAILABLE,
                error = (e.message ?: "خطای شبکه یا اعتبارسنجی").take(120),
            ) // never retain a previously displayed candidate after a failed refresh
        }
    }

    companion object {
        private fun fresh(at: Long?, now: Long, maxAge: Long): Boolean =
            at != null && now - at in -60_000L..maxAge

        private fun String?.toMillis(): Long? = this?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun parseCandidate(obj: JsonObject, now: Long): CryptoCandidate {
            val id = obj.text("id")?.takeIf { it.matches(Regex("[a-z0-9-]{2,90}")) } ?: error("شناسه نامعتبر است")
            val symbol = obj.text("symbol")?.takeIf { it.matches(Regex("[A-Z0-9]{2,12}USDT")) } ?: error("نماد نامعتبر است")
            val cg = obj.text("coingecko_at").toMillis()
            val pair = obj.text("coingecko_pair_at").toMillis()
            val bn = obj.text("binance_at").toMillis()
            val candle = obj.text("last_closed_candle_at").toMillis()
            if (!fresh(cg, now, 6 * 60_000L) || !fresh(pair, now, 11 * 60_000L) ||
                !fresh(bn, now, 3 * 60_000L) || !fresh(candle, now, 76 * 60_000L)) {
                error("زمان شواهد نماد یا جفت بازار قدیمی است")
            }
            val link = obj.text("link")?.takeIf { it == "https://www.coingecko.com/en/coins/$id" }
            require(obj.positive("market_cap_usd") in 50_000_000.0..5_000_000_000.0 &&
                obj.positive("volume_24h_usd") >= 15_000_000.0 &&
                obj.positive("binance_volume_24h_usdt") >= 5_000_000.0 &&
                obj.num("change_1h_pct") in 0.6..4.0 && obj.num("change_24h_pct") in 2.0..14.0 &&
                obj.num("change_7d_pct") in -10.0..35.0 &&
                obj.num("volume_ratio_3h") in 1.8..8.0 &&
                obj.num("taker_buy_ratio_3h") in 0.54..0.78 &&
                obj.num("spread_pct") in 0.0..0.3 &&
                obj.num("supply_ratio") in 0.5..1.01) { "نامزد از شروط ثابت عبور نکرده است" }
            return CryptoCandidate(
                id = id, name = obj.text("name")?.take(80) ?: symbol, symbol = symbol,
                priceUsd = obj.positive("price_usd"),
                marketCapUsd = obj.positive("market_cap_usd"),
                volume24hUsd = obj.positive("volume_24h_usd"),
                binanceVolumeUsdt = obj.positive("binance_volume_24h_usdt"),
                change1hPct = obj.num("change_1h_pct"),
                change24hPct = obj.num("change_24h_pct"),
                change7dPct = obj.num("change_7d_pct"),
                change3hPct = obj.num("change_3h_pct"),
                volumeRatio3h = obj.positive("volume_ratio_3h"),
                takerBuyRatio3h = obj.positive("taker_buy_ratio_3h"),
                spreadPct = obj.num("spread_pct"),
                supplyRatio = obj.positive("supply_ratio"),
                coingeckoAt = cg!!, coingeckoPairAt = pair!!, binanceAt = bn!!,
                candleAt = candle!!, link = link,
            )
        }

        private fun JsonObject.positive(key: String): Double = num(key).also { require(it > 0) { "$key نامعتبر است" } }
        private fun JsonObject.num(key: String): Double = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: error("$key نامعتبر است")
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
