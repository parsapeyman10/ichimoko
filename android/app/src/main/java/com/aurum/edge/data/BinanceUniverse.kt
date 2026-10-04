package com.aurum.edge.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * The COMPLETE Binance spot universe, discovered by the phone itself.
 *
 * A hand-written list of ten coins is not "crypto support". This pulls `exchangeInfo`
 * once, keeps every actively trading spot pair, and caches it on disk so the app still has
 * a symbol list when offline or when the first launch of the day has no network yet.
 *
 * Two things come from the exchange rather than being guessed:
 *  - the real tick size, which decides how many decimals a price must be shown with
 *    (BTC two, DOGE five, SHIB eight — a fixed number is wrong for almost everything);
 *  - which pairs are actually TRADING, so a delisted or halted symbol never appears.
 *
 * No key, no account, no backend. Market data only.
 */
object BinanceUniverse {

    data class Pair(
        /** App-facing id, e.g. "BTC/USDT". */
        val id: String,
        /** Binance ticker, e.g. "BTCUSDT". */
        val binance: String,
        val base: String,
        val quote: String,
        val digits: Int,
        /** 24h turnover in the quote asset. Drives the default order and tie-breaks. */
        val volume: Double = 0.0,
    )

    /** Quote assets worth charting. USDT first: it is where the liquidity is. */
    val QUOTES = listOf("USDT", "FDUSD", "USDC", "BTC", "ETH")

    private const val CACHE_FILE = "binance_universe.json"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    // Observable, NOT a plain field. The universe arrives a second or two after launch;
    // with a bare `var` Compose never recomposes, so the picker kept showing only the
    // hardcoded seed symbols and looked like crypto support had not shipped at all.
    private val _pairs = MutableStateFlow<List<Pair>>(emptyList())
    val pairs: StateFlow<List<Pair>> = _pairs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @Volatile private var loadedAt: Long = 0L

    private val cached: List<Pair> get() = _pairs.value

    /** Everything known right now, without touching the network. */
    fun snapshot(): List<Pair> = cached

    fun find(id: String): Pair? {
        val key = id.trim().uppercase()
        return cached.firstOrNull { it.id == key }
    }

    fun isCrypto(id: String): Boolean = find(id) != null

    /**
     * Decimals derived from the exchange's own tick size.
     *
     * `tickSize` arrives as "0.00001000"; the number of meaningful decimals is the
     * position of its last non-zero digit.
     */
    fun digitsFromTick(tick: String): Int {
        val trimmed = tick.trim().trimEnd('0')
        if (!trimmed.contains('.')) return 0
        val decimals = trimmed.substringAfter('.').length
        return decimals.coerceIn(0, 8)
    }

    /** Parse `exchangeInfo`. Pure, so the contract is testable without a network call. */
    fun parseExchangeInfo(body: String, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): List<Pair> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ exchangeInfo بایننس معتبر نیست")
        val symbols = root["symbols"] as? JsonArray
            ?: throw DataFeedException("فهرست نمادهای بایننس خالی است")

        val out = ArrayList<Pair>(symbols.size)
        for (element in symbols) {
            val row = element as? JsonObject ?: continue
            fun str(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
            if (str("status") != "TRADING") continue
            if ((row["isSpotTradingAllowed"] as? JsonPrimitive)?.contentOrNull != "true") continue
            val ticker = str("symbol")?.uppercase() ?: continue
            val base = str("baseAsset")?.uppercase() ?: continue
            val quote = str("quoteAsset")?.uppercase() ?: continue
            if (quote !in QUOTES) continue

            val tick = (row["filters"] as? JsonArray)?.firstNotNullOfOrNull { filter ->
                val obj = filter as? JsonObject ?: return@firstNotNullOfOrNull null
                if ((obj["filterType"] as? JsonPrimitive)?.contentOrNull != "PRICE_FILTER") null
                else (obj["tickSize"] as? JsonPrimitive)?.contentOrNull
            } ?: continue

            out.add(Pair(
                id = "$base/$quote",
                binance = ticker,
                base = base,
                quote = quote,
                digits = max(2, digitsFromTick(tick)).coerceAtMost(8),
            ))
        }
        if (out.isEmpty()) throw DataFeedException("هیچ نماد فعالی از بایننس دریافت نشد")
        // USDT first, then alphabetical: the order the user expects to scroll.
        return out.sortedWith(compareBy({ QUOTES.indexOf(it.quote) }, { it.id }))
    }

    /**
     * 24h turnover per symbol, so the default list is ordered by what people actually
     * trade. Alphabetical order puts 1INCH above BTC, which is not a usable list.
     */
    fun parseVolumes(body: String, json: Json = Json { ignoreUnknownKeys = true; isLenient = true }): Map<String, Double> {
        val rows = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Double>(rows.size)
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val symbol = (row["symbol"] as? JsonPrimitive)?.contentOrNull ?: continue
            val turnover = (row["quoteVolume"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
            out[symbol.uppercase()] = turnover
        }
        return out
    }

    /**
     * Ensure a universe is available. Disk cache first so the UI is never empty, then a
     * refresh when the cache is stale. A network failure keeps whatever we already had.
     */
    suspend fun ensureLoaded(context: Context, force: Boolean = false) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && cached.isNotEmpty() && now - loadedAt < MAX_AGE_MS) return@withLock

        if (cached.isEmpty()) {
            readCache(context)?.let { _pairs.value = it }
        }
        if (!force && cached.isNotEmpty() && now - loadedAt < MAX_AGE_MS) return@withLock

        _loading.value = true
        val attempt = runCatching { fetch() }
        val fetched = attempt.getOrNull()?.let { pairs ->
            // Volume is a best-effort enrichment: if it fails the universe is still usable,
            // just alphabetical, so a ranking outage must not empty the picker.
            val volumes = runCatching { fetchVolumes() }.getOrNull().orEmpty()
            if (volumes.isEmpty()) pairs
            else pairs.map { it.copy(volume = volumes[it.binance] ?: 0.0) }
                .sortedWith(compareBy<Pair> { QUOTES.indexOf(it.quote) }.thenByDescending { it.volume })
        }
        _loading.value = false
        if (fetched != null && fetched.isNotEmpty()) {
            _pairs.value = fetched
            loadedAt = now
            _error.value = null
            writeCache(context, fetched)
        } else {
            // Never fail silently: an empty picker with no explanation is indistinguishable
            // from a missing feature.
            _error.value = attempt.exceptionOrNull()?.message
                ?: "فهرست نمادهای بایننس دریافت نشد"
        }
    }

    private suspend fun fetch(): List<Pair> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BinanceHistoryClient.HOST}/exchangeInfo?permissions=SPOT")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DataFeedException("فهرست نمادهای بایننس دریافت نشد (HTTP ${response.code})")
            }
            parseExchangeInfo(response.body?.string().orEmpty(), json)
        }
    }

    private suspend fun fetchVolumes(): Map<String, Double> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BinanceHistoryClient.HOST}/ticker/24hr")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) emptyMap()
            else parseVolumes(response.body?.string().orEmpty(), json)
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun readCache(context: Context): List<Pair>? = runCatching {
        val file = cacheFile(context)
        if (!file.exists()) return null
        val rows = json.parseToJsonElement(file.readText()) as? JsonArray ?: return null
        rows.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            val id = str("id") ?: return@mapNotNull null
            Pair(
                id = id,
                binance = str("b") ?: return@mapNotNull null,
                base = str("base") ?: id.substringBefore('/'),
                quote = str("quote") ?: id.substringAfter('/'),
                digits = str("d")?.toIntOrNull() ?: 2,
                volume = str("v")?.toDoubleOrNull() ?: 0.0,
            )
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun writeCache(context: Context, pairs: List<Pair>) {
        runCatching {
            val text = pairs.joinToString(",", "[", "]") { pair ->
                """{"id":"${pair.id}","b":"${pair.binance}","base":"${pair.base}",""" +
                    """"quote":"${pair.quote}","d":"${pair.digits}","v":"${pair.volume}"}"""
            }
            cacheFile(context).writeText(text)
        }
    }

    /** Relevance-ranked search, including written names like "bitcoin" or "بیت‌کوین". */
    fun search(query: String, limit: Int = 60): List<Pair> = SymbolSearch.rank(query, cached, limit)

    /** Nearest tickers for a query that matched nothing, for a "did you mean" row. */
    fun suggest(query: String, limit: Int = 5): List<Pair> = SymbolSearch.suggest(query, cached, limit)
}
