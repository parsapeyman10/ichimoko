package com.aurum.edge.data

import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.engine.SignalEngine
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

/**
 * CoinGecko's OWN published community/developer/liquidity scores (0-100, their methodology, not
 * ours) plus real supply/ATH figures from `/coins/{id}`. This is NOT a proprietary rating and
 * NOT investment advice; every field is exactly what the provider reports, with its own
 * timestamp. Paired with a REAL Ichimoku+confluence read (same [SignalEngine] gold/Nobitex paper
 * trading uses) computed from Nobitex's own candles for the SAME asset, never invented.
 */
data class CryptoTechnicalRead(val signal: Signal, val computedAt: Long, val interval: Interval)

data class CryptoFundamental(
    val market: NobitexMarket,
    val coingeckoId: String,
    val name: String,
    val marketCapRank: Int?,
    val developerScore: Double?,
    val communityScore: Double?,
    val liquidityScore: Double?,
    val publicInterestScore: Double?,
    val sentimentUpPct: Double?,
    val circulatingSupply: Double?,
    val totalSupply: Double?,
    val maxSupply: Double?,
    val athChangePct: Double?,
    val providerAt: Long?,
    val technical: CryptoTechnicalRead? = null,
    val technicalError: String? = null,
) {
    /** Disclosed plain average of CoinGecko's own 0-100 scores; needs at least two to avoid one score dominating. */
    val compositeScore: Double? get() {
        val parts = listOfNotNull(developerScore, communityScore, liquidityScore)
        return if (parts.size >= 2) parts.average() else null
    }

    /** Circulating/max(or total) supply: lower means more future dilution still ahead, all else equal. */
    val supplyRatio: Double? get() {
        val circ = circulatingSupply?.takeIf { it > 0 } ?: return null
        val max = (maxSupply?.takeIf { it > 0 }) ?: (totalSupply?.takeIf { it > 0 }) ?: return null
        return (circ / max).takeIf { it.isFinite() && it in 0.0..1.0001 }
    }
}

enum class CryptoFundamentalStatus { IDLE, LOADING, DONE, FAILED }

data class CryptoFundamentalState(
    val status: CryptoFundamentalStatus = CryptoFundamentalStatus.IDLE,
    /** Accumulates every asset analyzed so far this session; switching coins never drops earlier results. */
    val results: Map<NobitexMarket, CryptoFundamental> = emptyMap(),
    val busyMarket: NobitexMarket? = null,
    val lastMarket: NobitexMarket? = null,
    val error: String? = null,
)

/**
 * Resolves an arbitrary Nobitex base ticker (e.g. "shib") to a CoinGecko coin id by querying
 * CoinGecko's OWN `/coins/list` (ticker -> id, official CoinGecko data, cached once per process)
 * and, when a ticker is ambiguous (several unrelated coins share it), disambiguating with REAL
 * `/coins/markets` market-cap ranking rather than guessing. Never invents an id for an unmatched
 * ticker — those assets simply have no fundamentals card.
 */
object CoinGeckoIdResolver {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var bySymbol: Map<String, List<String>>? = null
    private val resolved = HashMap<String, String?>()

    suspend fun resolve(base: String): String? = mutex.withLock {
        val symbol = base.trim().lowercase()
        resolved[symbol]?.let { return@withLock it }
        if (resolved.containsKey(symbol)) return@withLock null
        val index = bySymbol ?: withContext(Dispatchers.IO) { fetchList() }.also { bySymbol = it }
        val candidates = index[symbol].orEmpty()
        val id = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first()
            else -> withContext(Dispatchers.IO) { pickByMarketCap(candidates) }
        }
        resolved[symbol] = id
        id
    }

    private fun fetchList(): Map<String, List<String>> {
        val request = Request.Builder().url("https://api.coingecko.com/api/v3/coins/list")
            .get().header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) error("سهمیهٔ CoinGecko تمام شد (۴۲۹)؛ چند دقیقه دیگر دوباره بررسی کنید")
            require(response.isSuccessful) { "فهرست ارزهای CoinGecko دریافت نشد (HTTP ${response.code})" }
            val body = response.peekBody(6_000_000L).string()
            require(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= 6_000_000) { "فهرست ارزهای CoinGecko خالی یا بیش‌ازحد بزرگ است" }
            val root = json.parseToJsonElement(body) as? JsonArray ?: error("ساختار فهرست ارزهای CoinGecko نامعتبر است")
            val map = HashMap<String, MutableList<String>>()
            root.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val symbol = (obj["symbol"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return@forEach
                map.getOrPut(symbol) { mutableListOf() }.add(id)
            }
            return map
        }
    }

    private fun pickByMarketCap(candidateIds: List<String>): String? {
        val ids = candidateIds.take(50).joinToString(",")
        val request = Request.Builder()
            .url("https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&ids=$ids&order=market_cap_desc&per_page=50&page=1")
            .get().header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return candidateIds.first()
            val body = response.peekBody(1_000_000L).string()
            val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return candidateIds.first()
            val first = root.firstOrNull() as? JsonObject ?: return candidateIds.first()
            return (first["id"] as? JsonPrimitive)?.contentOrNull ?: candidateIds.first()
        }
    }
}

/**
 * On-demand (one asset at a time, not a fixed 6-coin sweep) fundamentals + fresh technical read
 * for ANY live Nobitex USDT market (see [NobitexMarketCatalog]). Reads TWO independent, real,
 * free/no-key providers:
 *  - CoinGecko `/coins/{id}`: published community/developer/liquidity scores, supply, ATH.
 *  - Nobitex public OHLC (same endpoint the paper-trading journal uses): real Ichimoku signal.
 * Never fabricates a missing field: absent data stays null and is shown as "—", not zero or a guess.
 */
class CryptoFundamentalsRepository(private val nobitexPublic: NobitexPublicData, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val lastAttemptAt = HashMap<NobitexMarket, Long>()
    private val _state = MutableStateFlow(CryptoFundamentalState())
    val state: StateFlow<CryptoFundamentalState> = _state.asStateFlow()

    fun analyze(market: NobitexMarket) { scope.launch { run(market) } }

    private suspend fun run(market: NobitexMarket) {
        val now = System.currentTimeMillis()
        val last = mutex.withLock { lastAttemptAt[market] }
        if (last != null && now - last < 120_000L) {
            _state.value = _state.value.copy(status = CryptoFundamentalStatus.FAILED, lastMarket = market,
                error = "برای ${market.srcCurrency.uppercase()}، دو دقیقه بین بررسی‌های پیاپی صبر کنید")
            return
        }
        mutex.withLock { lastAttemptAt[market] = now }
        _state.value = _state.value.copy(status = CryptoFundamentalStatus.LOADING, busyMarket = market, lastMarket = market, error = null)
        try {
            val id = CoinGeckoIdResolver.resolve(market.srcCurrency)
                ?: error("نماد ${market.srcCurrency.uppercase()} در فهرست رسمی CoinGecko پیدا نشد؛ کارت بنیادی برای آن ساخته نمی‌شود")
            val fundamentals = withContext(Dispatchers.IO) { fetchFundamentals(market, id) }
            val technical = runCatching { fetchTechnical(market) }.getOrNull()
            val techError = if (technical == null) "کندل نوبیتکس برای خواندن تکنیکال کافی/تازه نیست" else null
            val row = fundamentals.copy(technical = technical, technicalError = techError)
            _state.value = _state.value.copy(status = CryptoFundamentalStatus.DONE, busyMarket = null,
                results = _state.value.results + (market to row))
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = CryptoFundamentalStatus.FAILED, busyMarket = null,
                error = (e.message ?: "بررسی بنیادی ناموفق بود").take(160))
        }
    }

    private fun fetchFundamentals(market: NobitexMarket, id: String): CryptoFundamental {
        val url = "https://api.coingecko.com/api/v3/coins/$id" +
            "?localization=false&tickers=false&market_data=true&community_data=true&developer_data=true&sparkline=false"
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) error("سهمیهٔ CoinGecko تمام شد (۴۲۹)؛ چند دقیقه دیگر دوباره بررسی کنید")
            require(response.isSuccessful) { "CoinGecko برای $id پاسخ معتبر نداد (HTTP ${response.code})" }
            val body = response.peekBody(700_001L).string()
            require(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= 700_000) { "پاسخ CoinGecko برای $id بیش از حد بزرگ یا خالی است" }
            val root = json.parseToJsonElement(body) as? JsonObject ?: error("پاسخ CoinGecko برای $id ساختار JSON ندارد")
            val name = root.text("name")?.take(60) ?: id
            val marketData = root["market_data"] as? JsonObject
            val at = (root.text("last_updated") ?: marketData?.text("last_updated"))?.toMillis()
            return CryptoFundamental(
                market = market, coingeckoId = id, name = name,
                marketCapRank = root.num("market_cap_rank")?.toInt(),
                developerScore = root.num("developer_score"), communityScore = root.num("community_score"),
                liquidityScore = root.num("liquidity_score"), publicInterestScore = root.num("public_interest_score"),
                sentimentUpPct = root.num("sentiment_votes_up_percentage"),
                circulatingSupply = marketData?.num("circulating_supply"),
                totalSupply = marketData?.num("total_supply"), maxSupply = marketData?.num("max_supply"),
                athChangePct = (marketData?.get("ath_change_percentage") as? JsonObject)?.num("usd"),
                providerAt = at,
            )
        }
    }

    /** Independent of whatever the Nobitex screen currently has loaded; fetches its OWN fresh candles. */
    private suspend fun fetchTechnical(market: NobitexMarket): CryptoTechnicalRead? {
        val interval = Interval.H1
        val snapshot = nobitexPublic.download(market, interval)
        val closed = snapshot.candles.count { it.closed }
        if (closed < SignalEngine.minBars(interval)) return null
        val signal = SignalEngine.evaluate(snapshot.candles, interval, 60.0)
        return CryptoTechnicalRead(signal, System.currentTimeMillis(), interval)
    }

    companion object {
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        private fun String.toMillis(): Long? = runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
    }
}
