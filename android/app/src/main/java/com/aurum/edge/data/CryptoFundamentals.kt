package com.aurum.edge.data

import android.os.SystemClock
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.engine.SignalEngine
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
    val rows: List<CryptoFundamental> = emptyList(),
    val error: String? = null,
)

/**
 * Reads TWO independent, real, free/no-key providers per asset:
 *  - CoinGecko `/coins/{id}`: published community/developer/liquidity scores, supply, ATH.
 *  - Nobitex public OHLC (same endpoint the paper-trading journal uses): real Ichimoku signal.
 * Sequential with a short delay between assets to respect CoinGecko's free-tier rate limit;
 * a 3-minute cooldown mirrors the other public-data screens in this app. Never fabricates a
 * missing field: absent data stays null and is shown as "—", not zero or a guess.
 */
class CryptoFundamentalsRepository(private val nobitexPublic: NobitexPublicData, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var attemptedAt = 0L
    private val _state = MutableStateFlow(CryptoFundamentalState())
    val state: StateFlow<CryptoFundamentalState> = _state.asStateFlow()

    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() = mutex.withLock {
        val elapsed = SystemClock.elapsedRealtime()
        if (attemptedAt != 0L && elapsed - attemptedAt in 0L until 180_000L) {
            if (_state.value.status != CryptoFundamentalStatus.DONE) _state.value = CryptoFundamentalState(
                CryptoFundamentalStatus.FAILED, error = "برای سهمیهٔ CoinGecko، سه دقیقه بین بررسی‌ها صبر کنید")
            return@withLock
        }
        attemptedAt = elapsed
        _state.value = CryptoFundamentalState(CryptoFundamentalStatus.LOADING)
        val out = mutableListOf<CryptoFundamental>()
        var hardError: String? = null
        UNIVERSE.forEachIndexed { index, market ->
            if (hardError == null) {
                try {
                    val fundamentals = withContext(Dispatchers.IO) { fetchFundamentals(market) }
                    val technical = runCatching { fetchTechnical(market) }.getOrNull()
                    val techError = if (technical == null) "کندل نوبیتکس برای خواندن تکنیکال کافی/تازه نیست" else null
                    out += fundamentals.copy(technical = technical, technicalError = techError)
                } catch (e: Exception) {
                    hardError = (e.message ?: "دریافت CoinGecko ناموفق بود").take(140)
                }
                // NobitexPublicData.download() enforces >=20s between ANY two calls on this shared instance.
                if (index != UNIVERSE.lastIndex && hardError == null) delay(21_000L)
            }
        }
        _state.value = if (hardError != null) CryptoFundamentalState(CryptoFundamentalStatus.FAILED, error = hardError)
            else CryptoFundamentalState(CryptoFundamentalStatus.DONE, out)
    }

    private fun fetchFundamentals(market: NobitexMarket): CryptoFundamental {
        val id = COINGECKO_ID.getValue(market)
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
        val UNIVERSE = listOf(NobitexMarket.BTC_USDT, NobitexMarket.ETH_USDT, NobitexMarket.SOL_USDT,
            NobitexMarket.XRP_USDT, NobitexMarket.DOGE_USDT, NobitexMarket.ADA_USDT)
        val COINGECKO_ID = mapOf(
            NobitexMarket.BTC_USDT to "bitcoin", NobitexMarket.ETH_USDT to "ethereum",
            NobitexMarket.SOL_USDT to "solana", NobitexMarket.XRP_USDT to "ripple",
            NobitexMarket.DOGE_USDT to "dogecoin", NobitexMarket.ADA_USDT to "cardano",
        )

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        private fun String.toMillis(): Long? = runCatching { OffsetDateTime.parse(this).toInstant().toEpochMilli() }.getOrNull()
    }
}
