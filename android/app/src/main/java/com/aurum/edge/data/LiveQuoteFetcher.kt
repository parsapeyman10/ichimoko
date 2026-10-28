package com.aurum.edge.data

import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class BandwidthSyncStats(
    val cacheHits: Int = 0,
    val deltaFetches: Int = 0,
    val fullFetches: Int = 0,
    val batchTickCalls: Int = 0,
    val skippedClosedMarketChecks: Int = 0,
    val lastFull15mSweepAt: Long = 0L,
    val nextScheduled15mSweepAt: Long = 0L,
) {
    val totalRequestsSaved: Int
        get() = cacheHits + skippedClosedMarketChecks + (batchTickCalls * 4)
}

private data class CandleCacheEntry(
    val bars: List<Candle>,
    val provider: String,
    val bucket: Long,
    val updatedAt: Long,
)

object LiveQuoteFetcher {

    private val json = Json { ignoreUnknownKeys = true }
    private val candleCache = ConcurrentHashMap<String, CandleCacheEntry>()

    private val _syncStats = MutableStateFlow(BandwidthSyncStats())
    val syncStats: StateFlow<BandwidthSyncStats> = _syncStats.asStateFlow()

    fun recordScheduled15mSweep(lastSweepAt: Long, nextSweepAt: Long) {
        _syncStats.update {
            it.copy(
                lastFull15mSweepAt = lastSweepAt,
                nextScheduled15mSweepAt = nextSweepAt,
            )
        }
    }

    fun recordSkippedClosedMarketCheck(count: Int = 1) {
        if (count <= 0) return
        _syncStats.update {
            it.copy(skippedClosedMarketChecks = it.skippedClosedMarketChecks + count)
        }
    }

    /**
     * به‌روزرسانی کندل در حال تشکیل در کش حافظه با استفاده از تیک قیمت زنده بدون نیاز به دانلود مجدد تاریخچه کندل.
     */
    fun applyLiveTickToCachedCandles(symbolInput: String, tick: PriceTick) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!tick.price.isFinite() || tick.price <= 0.0) return
        for (interval in listOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4)) {
            val key = "$symbol|${interval.label}"
            val current = candleCache[key] ?: continue
            if (current.bars.isEmpty()) continue
            val last = current.bars.last()
            val barEnd = last.time + interval.millis
            if (tick.at in last.time..(barEnd + 120_000L)) {
                val updatedLast = last.copy(
                    high = max(last.high, tick.price),
                    low = min(last.low, tick.price),
                    close = tick.price,
                )
                val nextBars = current.bars.dropLast(1) + updatedLast
                candleCache[key] = current.copy(bars = nextBars, updatedAt = tick.at)
            }
        }
    }

    /**
     * دریافت یکپارچه تیک قیمت هر ۵ رمزارز (`BTC/USDT, ETH/USDT, SOL/USDT, BNB/USDT, XRP/USDT`)
     * تنها در **۱ درخواست سبک ۲۰۰ بایتی** از Binance برای صرفه‌جویی حداکثری در مصرف اینترنت.
     */
    suspend fun fetchCryptoBatchTicks(
        symbolsInput: List<String> = V1Universe.cryptoSymbols,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, PriceTick> = withContext(Dispatchers.IO) {
        val targets = symbolsInput.map(V1Universe::normalize).filter { it in V1Universe.cryptoSymbols }.distinct()
        if (targets.isEmpty()) return@withContext emptyMap()

        val result = LinkedHashMap<String, PriceTick>()
        if (targets.size >= 2) {
            val batchPairs = targets.map { "${it.substringBefore('/')}USDT" }
            val encodedJson = batchPairs.joinToString(prefix = "%5B", postfix = "%5D", separator = ",") { "%22$it%22" }
            val url = "https://data-api.binance.vision/api/v3/ticker/price?symbols=$encodedJson"
            runCatching {
                val body = get(url)
                if (body != null) {
                    val arr = json.parseToJsonElement(body).jsonArray
                    for (elem in arr) {
                        val obj = elem as? JsonObject ?: continue
                        val rawSym = obj["symbol"]?.jsonPrimitive?.content ?: continue
                        val price = obj["price"]?.asDouble() ?: continue
                        if (rawSym.endsWith("USDT") && price.isFinite() && price > 0.0) {
                            val norm = "${rawSym.removeSuffix("USDT")}/USDT"
                            if (norm in targets) {
                                val tick = PriceTick(
                                    price = price,
                                    at = nowMillis,
                                    isTradeTick = true,
                                    provider = "Binance Batch",
                                )
                                result[norm] = tick
                                applyLiveTickToCachedCandles(norm, tick)
                            }
                        }
                    }
                    if (result.isNotEmpty()) {
                        _syncStats.update { it.copy(batchTickCalls = it.batchTickCalls + 1) }
                    }
                }
            }
        }

        // Fallback for any symbol not returned by batch
        for (sym in targets) {
            if (sym !in result) {
                val single = fetchLiveTick(sym)
                if (single != null) {
                    result[sym] = single
                    applyLiveTickToCachedCandles(sym, single)
                }
            }
        }
        result
    }

    private val hostCooldownUntilMs = ConcurrentHashMap<String, Long>()

    private val client: OkHttpClient by lazy {
        AppNetwork.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLiveTick(symbolInput: String): PriceTick? = withContext(Dispatchers.IO) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return@withContext null

        val fetchers: List<() -> PriceTick?> = when (symbol) {
            "XAU/USD" -> listOf(
                { fetchSwissquoteBbo("XAU/USD") },
                { fetchGoldApi("XAU", "Gold-API") },
                { fetchYahooChart("XAUUSD=X") },
                { fetchYahooChart("GC=F") },
                { fetchKucoinTicker("PAXG-USDT") },
                { fetchYahooChart("PAXG-USD") },
            )
            "XAG/USD" -> listOf(
                { fetchSwissquoteBbo("XAG/USD") },
                { fetchGoldApi("XAG", "Gold-API") },
                { fetchYahooChart("XAGUSD=X") },
                { fetchYahooChart("SI=F") },
            )
            "DOW" -> listOf(
                { fetchYahooChart("%5EDJI") },
                { fetchYahooChart("YM=F") },
            )
            "EUR/USD" -> listOf(
                { fetchSwissquoteBbo("EUR/USD") },
                { fetchYahooChart("EURUSD=X") },
            )
            "GBP/USD" -> listOf(
                { fetchSwissquoteBbo("GBP/USD") },
                { fetchYahooChart("GBPUSD=X") },
            )
            "USD/JPY" -> listOf(
                { fetchSwissquoteBbo("USD/JPY") },
                { fetchYahooChart("JPY=X") },
            )
            "EUR/JPY" -> listOf(
                { fetchSwissquoteBbo("EUR/JPY") },
                { fetchYahooChart("EURJPY=X") },
            )
            "GBP/JPY" -> listOf(
                { fetchSwissquoteBbo("GBP/JPY") },
                { fetchYahooChart("GBPJPY=X") },
            )
            "AUD/USD" -> listOf(
                { fetchSwissquoteBbo("AUD/USD") },
                { fetchYahooChart("AUDUSD=X") },
            )
            "USD/CHF" -> listOf(
                { fetchSwissquoteBbo("USD/CHF") },
                { fetchYahooChart("CHF=X") },
            )
            "BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT" -> {
                val base = symbol.substringBefore('/')
                listOf(
                    { fetchBinanceTicker("${base}USDT") },
                    { fetchKucoinTicker("$base-USDT") },
                    { fetchOkxTicker("$base-USDT") },
                    { fetchGateTicker("${base}_USDT") },
                    { fetchMexcTicker("${base}USDT") },
                    { fetchCoinbaseSpot("$base-USD") },
                    { fetchYahooChart("$base-USD") },
                )
            }
            else -> emptyList()
        }

        for (attempt in fetchers) {
            val tick = runCatching { attempt() }.getOrNull()
            if (tick != null && tick.price.isFinite() && tick.price > 0.0) {
                return@withContext tick
            }
        }
        null
    }

    /**
     * Fetches historical OHLC candles covering the offline gap `[sinceMillis .. nowMillis]`
     * so that any missed hours (e.g. 6h or 24h offline) are backfilled in one request and
     * replayed chronologically against open positions.
     */
    suspend fun fetchGapCandles(
        symbolInput: String,
        sinceMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<List<Candle>, String>? = withContext(Dispatchers.IO) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return@withContext null

        val gapMs = max(5 * 60_000L, nowMillis - sinceMillis)
        val intervalCode = when {
            gapMs <= 16L * 3600_000L -> "1m"
            gapMs <= 5L * 24L * 3600_000L -> "5m"
            else -> "15m"
        }

        val attempts: List<() -> Pair<List<Candle>, String>?> = when (symbol) {
            "BTC/USDT", "ETH/USDT", "SOL/USDT", "BNB/USDT", "XRP/USDT" -> {
                val base = symbol.substringBefore('/')
                val kucoinType = when (intervalCode) {
                    "1m" -> "1min"
                    "5m" -> "5min"
                    else -> "15min"
                }
                listOf(
                    { fetchYahooGapCandles("$base-USD", intervalCode, gapMs, sinceMillis, "query1") },
                    { fetchYahooGapCandles("$base-USD", intervalCode, gapMs, sinceMillis, "query2") },
                    { fetchKucoinCandles("$base-USDT", kucoinType, sinceMillis, nowMillis) },
                    { fetchBinanceKlines("${base}USDT", intervalCode, sinceMillis, nowMillis) },
                    { fetchOkxCandles("$base-USDT", intervalCode, sinceMillis) },
                    { fetchGateCandles("${base}_USDT", intervalCode, sinceMillis, nowMillis) },
                )
            }
            "XAU/USD" -> listOf(
                { fetchYahooGapCandles("GC=F", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("GC=F", intervalCode, gapMs, sinceMillis, "query2") },
                { fetchYahooGapCandles("XAUUSD=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("PAXG-USD", intervalCode, gapMs, sinceMillis, "query1") },
            )
            "XAG/USD" -> listOf(
                { fetchYahooGapCandles("SI=F", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("SI=F", intervalCode, gapMs, sinceMillis, "query2") },
                { fetchYahooGapCandles("XAGUSD=X", intervalCode, gapMs, sinceMillis, "query1") },
            )
            "DOW" -> listOf(
                { fetchYahooGapCandles("%5EDJI", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("YM=F", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "EUR/USD" -> listOf(
                { fetchYahooGapCandles("EURUSD=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("EURUSD=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "GBP/USD" -> listOf(
                { fetchYahooGapCandles("GBPUSD=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("GBPUSD=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "USD/JPY" -> listOf(
                { fetchYahooGapCandles("JPY=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("JPY=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "EUR/JPY" -> listOf(
                { fetchYahooGapCandles("EURJPY=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("EURJPY=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "GBP/JPY" -> listOf(
                { fetchYahooGapCandles("GBPJPY=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("GBPJPY=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "AUD/USD" -> listOf(
                { fetchYahooGapCandles("AUDUSD=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("AUDUSD=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "USD/CHF" -> listOf(
                { fetchYahooGapCandles("CHF=X", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("CHF=X", intervalCode, gapMs, sinceMillis, "query2") },
            )
            else -> emptyList()
        }

        for (attempt in attempts) {
            val res = runCatching { attempt() }.getOrNull()
            if (res != null && res.first.isNotEmpty()) {
                return@withContext res
            }
        }
        null
    }

    /**
     * دریافت هوشمند کندل‌های تایم‌فریم مشخص (`4H`، `1H`، `15m`) برای هر ۱۵ نماد داخل اپ (`V1Universe.allSymbols`)
     * با کش همگام با بسته شدن کندل و دریافت فقط کندل‌های جدید (Delta Sync) جهت جلوگیری از هدررفت اینترنت.
     */
    suspend fun fetchTimeframeCandles(
        symbolInput: String = "BTC/USDT",
        interval: Interval,
        limit: Int = 110,
        nowMillis: Long = System.currentTimeMillis(),
        forceNetwork: Boolean = false,
    ): Pair<List<Candle>, String>? = withContext(Dispatchers.IO) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return@withContext null

        val cacheKey = "$symbol|${interval.label}"
        val cached = candleCache[cacheKey]
        val currentBucket = Smart15mSyncPlanner.currentCandleBucket(nowMillis, interval)
        val marketClosed = MarketHours.closedFor(symbol, nowMillis)

        // ۱. اگر کندل جدیدی در این تایم‌فریم بسته نشده (یا سشن بازار بسته است و کش کامل داریم)، از کش برگردان (۰ کیلوبایت مصرف اینترنت)
        if (!forceNetwork && cached != null && cached.bars.size >= 75) {
            val needNetwork = if (marketClosed) {
                false
            } else {
                Smart15mSyncPlanner.shouldFetchCandlesFromNetwork(
                    lastCachedBucket = cached.bucket,
                    cachedBarCount = cached.bars.size,
                    nowMillis = nowMillis,
                    interval = interval,
                )
            }
            if (!needNetwork) {
                _syncStats.update { it.copy(cacheHits = it.cacheHits + 1) }
                return@withContext markClosedFlags(cached.bars, interval, nowMillis) to "${cached.provider} (Cached)"
            }
        }

        val lastClosedBarTime = cached?.bars?.lastOrNull { it.closed }?.time
        val optimalLimit = if (forceNetwork) {
            limit
        } else {
            Smart15mSyncPlanner.computeOptimalFetchLimit(
                lastClosedBarTimeMs = lastClosedBarTime,
                cachedBarCount = cached?.bars?.size ?: 0,
                nowMillis = nowMillis,
                interval = interval,
                fullLimit = limit,
            )
        }
        val isDelta = cached != null && cached.bars.size >= 75 && optimalLimit < limit

        val fetched: Pair<List<Candle>, String>? = if (symbol in V1Universe.cryptoSymbols) {
            fetchCryptoTimeframeCandles(symbol, interval, optimalLimit, nowMillis)
        } else {
            fetchTradFiTimeframeCandles(symbol, interval, optimalLimit, isDelta, nowMillis)
        }

        if (fetched != null && fetched.first.isNotEmpty()) {
            val mergedRaw = if (cached != null && cached.bars.isNotEmpty()) {
                val map = LinkedHashMap<Long, Candle>()
                cached.bars.forEach { map[it.time] = it }
                fetched.first.forEach { map[it.time] = it }
                map.values.sortedBy { it.time }.takeLast(max(limit, 110))
            } else {
                fetched.first.sortedBy { it.time }.takeLast(max(limit, 110))
            }
            val marked = markClosedFlags(mergedRaw, interval, nowMillis)
            if (marked.size >= 35) {
                candleCache[cacheKey] = CandleCacheEntry(
                    bars = marked,
                    provider = fetched.second,
                    bucket = currentBucket,
                    updatedAt = nowMillis,
                )
                _syncStats.update {
                    if (isDelta) it.copy(deltaFetches = it.deltaFetches + 1)
                    else it.copy(fullFetches = it.fullFetches + 1)
                }
                return@withContext marked to fetched.second
            }
        }

        // اگر شبکه موقتاً در دسترس نبود، آخرین کش معتبر را برگردان تا دیتا از دست نرود
        if (cached != null && cached.bars.size >= 35) {
            return@withContext markClosedFlags(cached.bars, interval, nowMillis) to "${cached.provider} (Fallback Cache)"
        }
        null
    }

    private fun fetchCryptoTimeframeCandles(
        symbol: String,
        interval: Interval,
        fetchLimit: Int,
        nowMillis: Long,
    ): Pair<List<Candle>, String>? {
        val base = symbol.substringBefore('/')
        // اگر تایم‌فریم 4H خواسته شده و کش 1H تازه داریم، مستقیماً از 1H تجمیع کن (۰ درخواست اضافه)
        if (interval == Interval.H4) {
            val h1Cache = candleCache["$symbol|${Interval.H1.label}"]
            if (h1Cache != null && h1Cache.bars.size >= 160) {
                val agg = aggregateHourlyTo4H(h1Cache.bars)
                if (agg.size >= 40) {
                    _syncStats.update { it.copy(cacheHits = it.cacheHits + 1) }
                    return agg to "${h1Cache.provider} (1H->4H)"
                }
            }
        }

        val binanceInterval = when (interval) {
            Interval.M1 -> "1m"
            Interval.M3 -> "3m"
            Interval.M5 -> "5m"
            Interval.M15 -> "15m"
            Interval.M30 -> "30m"
            Interval.H1 -> "1h"
            Interval.H4 -> "4h"
            Interval.D1 -> "1d"
        }
        val kucoinType = when (interval) {
            Interval.M1 -> "1min"
            Interval.M3 -> "3min"
            Interval.M5 -> "5min"
            Interval.M15 -> "15min"
            Interval.M30 -> "30min"
            Interval.H1 -> "1hour"
            Interval.H4 -> "4hour"
            Interval.D1 -> "1day"
        }
        val okxBar = when (interval) {
            Interval.M1 -> "1m"
            Interval.M3 -> "3m"
            Interval.M5 -> "5m"
            Interval.M15 -> "15m"
            Interval.M30 -> "30m"
            Interval.H1 -> "1H"
            Interval.H4 -> "4H"
            Interval.D1 -> "1D"
        }
        val gateInterval = when (interval) {
            Interval.M1 -> "1m"
            Interval.M3 -> "5m"
            Interval.M5 -> "5m"
            Interval.M15 -> "15m"
            Interval.M30 -> "30m"
            Interval.H1 -> "1h"
            Interval.H4 -> "4h"
            Interval.D1 -> "1d"
        }
        val sinceMillis = nowMillis - interval.millis * (fetchLimit + 3L)
        val isDelta = fetchLimit < 80

        val attempts: List<() -> Pair<List<Candle>, String>?> = listOf(
            { fetchYahooTickersTimeframeCandles(symbol, listOf("$base-USD"), interval, fetchLimit, isDelta, nowMillis) },
            { fetchKucoinCandles("$base-USDT", kucoinType, sinceMillis, nowMillis) },
            { fetchBinanceKlines("${base}USDT", binanceInterval, sinceMillis, nowMillis, fetchLimit.coerceIn(5, 500)) },
            { fetchOkxCandles("$base-USDT", okxBar, sinceMillis, fetchLimit.coerceIn(5, 300)) },
            { fetchGateCandles("${base}_USDT", gateInterval, sinceMillis, nowMillis) },
        )

        for (attempt in attempts) {
            val res = runCatching { attempt() }.getOrNull()
            if (res != null && res.first.isNotEmpty()) {
                return res
            }
        }
        return null
    }

    /**
     * دریافت کندل‌های ۱۰ نماد غیررمزارزی (`XAU/USD`, `XAG/USD`, `DOW`, و ۷ جفت‌ارز فارکس)
     * با تجمیع هوشمند `1H -> 4H` (به‌طوری که دریافت `1H` هم‌زمان کش `4H` را هم بدون درخواست اضافه پر می‌کند).
     */
    private fun fetchTradFiTimeframeCandles(
        symbol: String,
        interval: Interval,
        fetchLimit: Int,
        isDelta: Boolean,
        nowMillis: Long,
    ): Pair<List<Candle>, String>? {
        val tickers = when (symbol) {
            "XAU/USD" -> listOf("GC=F", "XAUUSD=X", "PAXG-USD")
            "XAG/USD" -> listOf("SI=F", "XAGUSD=X")
            "DOW" -> listOf("YM=F", "%5EDJI")
            "EUR/USD" -> listOf("EURUSD=X")
            "GBP/USD" -> listOf("GBPUSD=X")
            "USD/JPY" -> listOf("JPY=X")
            "EUR/JPY" -> listOf("EURJPY=X")
            "GBP/JPY" -> listOf("GBPJPY=X")
            "AUD/USD" -> listOf("AUDUSD=X")
            "USD/CHF" -> listOf("CHF=X")
            else -> return null
        }
        return fetchYahooTickersTimeframeCandles(symbol, tickers, interval, fetchLimit, isDelta, nowMillis)
    }

    private fun fetchYahooTickersTimeframeCandles(
        symbol: String,
        tickers: List<String>,
        interval: Interval,
        fetchLimit: Int,
        isDelta: Boolean,
        nowMillis: Long,
    ): Pair<List<Candle>, String>? {

        // اگر تایم‌فریم 4H خواسته شده و کش 1H تازه داریم، مستقیماً از 1H تجمیع کن (۰ درخواست اضافه)
        if (interval == Interval.H4) {
            val h1Cache = candleCache["$symbol|${Interval.H1.label}"]
            if (h1Cache != null && h1Cache.bars.size >= 160) {
                val agg = aggregateHourlyTo4H(h1Cache.bars)
                if (agg.size >= 40) {
                    _syncStats.update { it.copy(cacheHits = it.cacheHits + 1) }
                    return agg to "${h1Cache.provider} (1H->4H)"
                }
            }
        }

        val (yahooInterval, yahooRange) = when (interval) {
            Interval.M1 -> "1m" to if (isDelta) "1d" else "5d"
            Interval.M3, Interval.M5 -> "5m" to if (isDelta) "1d" else "5d"
            Interval.M15 -> "15m" to if (isDelta) "1d" else "5d"
            Interval.M30 -> "30m" to if (isDelta) "5d" else "1mo"
            Interval.H1 -> "60m" to if (isDelta) "5d" else "3mo"
            Interval.H4 -> "60m" to if (isDelta) "5d" else "3mo"
            Interval.D1 -> "1d" to if (isDelta) "1mo" else "1y"
        }

        val sinceMillis = nowMillis - interval.millis * (fetchLimit + 10L)
        for (ticker in tickers) {
            for (host in listOf("query1", "query2")) {
                val raw = runCatching {
                    fetchYahooTimeframeRaw(ticker, yahooInterval, yahooRange, host)
                }.getOrNull()
                if (raw != null && raw.first.isNotEmpty()) {
                    val (bars, prov) = raw
                    if (interval == Interval.H1 || interval == Interval.H4) {
                        // بهینه‌سازی دوگانه: با یک درخواست 60m، هم کش 1H و هم کش 4H پر می‌شود
                        val h1Marked = markClosedFlags(bars.takeLast(450), Interval.H1, nowMillis)
                        val h1Bucket = Smart15mSyncPlanner.currentCandleBucket(nowMillis, Interval.H1)
                        candleCache["$symbol|${Interval.H1.label}"] = CandleCacheEntry(
                            bars = h1Marked,
                            provider = prov,
                            bucket = h1Bucket,
                            updatedAt = nowMillis,
                        )
                        val h4Agg = aggregateHourlyTo4H(bars)
                        val h4Marked = markClosedFlags(h4Agg.takeLast(120), Interval.H4, nowMillis)
                        val h4Bucket = Smart15mSyncPlanner.currentCandleBucket(nowMillis, Interval.H4)
                        if (h4Marked.size >= 35) {
                            candleCache["$symbol|${Interval.H4.label}"] = CandleCacheEntry(
                                bars = h4Marked,
                                provider = "$prov (4H)",
                                bucket = h4Bucket,
                                updatedAt = nowMillis,
                            )
                        }
                        return if (interval == Interval.H4) {
                            h4Marked to "$prov (4H)"
                        } else {
                            h1Marked.takeLast(120) to prov
                        }
                    }
                    val filtered = if (isDelta) {
                        bars.filter { it.time >= sinceMillis }
                    } else {
                        bars.takeLast(max(fetchLimit, 110))
                    }
                    return (if (filtered.isNotEmpty()) filtered else bars.takeLast(110)) to prov
                }
            }
        }
        return null
    }

    private fun aggregateHourlyTo4H(hourlyBars: List<Candle>): List<Candle> {
        if (hourlyBars.isEmpty()) return emptyList()
        val sorted = hourlyBars.sortedBy { it.time }
        val fourHoursMs = 4L * 3600_000L
        val byBucket = sorted.groupBy { (it.time / fourHoursMs) * fourHoursMs }
        val calendarAgg = byBucket.entries.sortedBy { it.key }.map { (bucketTime, group) ->
            Candle(
                time = bucketTime,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                closed = group.last().closed,
            )
        }
        if (calendarAgg.size >= 80) return calendarAgg
        // Fallback for shorter-session cash indices: group every 4 consecutive hourly bars
        return sorted.chunked(4).map { group ->
            Candle(
                time = group.first().time,
                open = group.first().open,
                high = group.maxOf { it.high },
                low = group.minOf { it.low },
                close = group.last().close,
                volume = group.sumOf { it.volume },
                closed = group.last().closed,
            )
        }
    }

    private fun fetchYahooTimeframeRaw(
        yahooTicker: String,
        interval: String,
        range: String,
        hostSubdomain: String,
    ): Pair<List<Candle>, String>? {
        val url = "https://$hostSubdomain.finance.yahoo.com/v8/finance/chart/$yahooTicker?interval=$interval&range=$range&includePrePost=false"
        val body = get(url) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val result = (root["chart"] as? JsonObject)
            ?.get("result")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject ?: return null
        val timestamps = (result["timestamp"] as? JsonArray)?.map { it.asLong() } ?: return null
        val quote = (result["indicators"] as? JsonObject)
            ?.get("quote")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject ?: return null
        val opens = (quote["open"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val highs = (quote["high"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val lows = (quote["low"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val closes = (quote["close"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val volumes = (quote["volume"] as? JsonArray)?.map { it.asDouble() }

        val n = minOf(timestamps.size, opens.size, highs.size, lows.size, closes.size)
        val allCandles = ArrayList<Candle>(n)
        for (i in 0 until n) {
            val tsSec = timestamps[i] ?: continue
            val o = opens[i] ?: continue
            val h = highs[i] ?: continue
            val l = lows[i] ?: continue
            val c = closes[i] ?: continue
            val v = volumes?.getOrNull(i) ?: 0.0
            if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) continue
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) continue
            allCandles += Candle(
                time = tsSec * 1000L,
                open = o,
                high = h,
                low = l,
                close = c,
                volume = v,
                closed = true,
            )
        }
        if (allCandles.isEmpty()) return null
        return allCandles.sortedBy { it.time } to "Yahoo Finance ($interval)"
    }

    private fun markClosedFlags(
        bars: List<Candle>,
        interval: Interval,
        nowMillis: Long,
    ): List<Candle> {
        if (bars.isEmpty()) return bars
        val sorted = bars.sortedBy { it.time }
        return sorted.mapIndexed { idx, bar ->
            val isLast = idx == sorted.lastIndex
            val isClosed = !isLast || (bar.time + interval.millis <= nowMillis + 2_000L)
            bar.copy(closed = isClosed)
        }
    }

    private fun fetchBinanceKlines(
        pair: String,
        interval: String,
        sinceMillis: Long,
        nowMillis: Long,
        limit: Int = 500,
    ): Pair<List<Candle>, String>? {
        val url = "https://data-api.binance.vision/api/v3/klines?symbol=${pair.uppercase(Locale.ROOT)}&interval=$interval&startTime=$sinceMillis&endTime=$nowMillis&limit=$limit"
        val body = get(url) ?: return null
        val arr = json.parseToJsonElement(body).jsonArray
        val candles = arr.mapNotNull { row ->
            val items = row as? JsonArray ?: return@mapNotNull null
            if (items.size < 6) return@mapNotNull null
            val openTime = items[0].asLong() ?: return@mapNotNull null
            val o = items[1].asDouble() ?: return@mapNotNull null
            val h = items[2].asDouble() ?: return@mapNotNull null
            val l = items[3].asDouble() ?: return@mapNotNull null
            val c = items[4].asDouble() ?: return@mapNotNull null
            val v = items[5].asDouble() ?: 0.0
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) return@mapNotNull null
            Candle(time = openTime, open = o, high = h, low = l, close = c, volume = v, closed = true)
        }.sortedBy { it.time }
        if (candles.isEmpty()) return null
        return candles to "Binance ($interval)"
    }

    private fun fetchOkxCandles(
        instId: String,
        interval: String,
        sinceMillis: Long,
        limit: Int = 300,
    ): Pair<List<Candle>, String>? {
        val url = "https://www.okx.com/api/v5/market/candles?instId=$instId&bar=$interval&limit=$limit"
        val body = get(url) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonArray ?: return null
        val candles = data.mapNotNull { row ->
            val items = row as? JsonArray ?: return@mapNotNull null
            if (items.size < 6) return@mapNotNull null
            val ts = items[0].asLong() ?: return@mapNotNull null
            if (ts < sinceMillis - 15 * 60_000L) return@mapNotNull null
            val o = items[1].asDouble() ?: return@mapNotNull null
            val h = items[2].asDouble() ?: return@mapNotNull null
            val l = items[3].asDouble() ?: return@mapNotNull null
            val c = items[4].asDouble() ?: return@mapNotNull null
            val v = items[5].asDouble() ?: 0.0
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) return@mapNotNull null
            Candle(time = ts, open = o, high = h, low = l, close = c, volume = v, closed = true)
        }.sortedBy { it.time }
        if (candles.isEmpty()) return null
        return candles to "OKX ($interval)"
    }

    private fun fetchGateCandles(
        currencyPair: String,
        interval: String,
        sinceMillis: Long,
        nowMillis: Long,
    ): Pair<List<Candle>, String>? {
        val fromSec = max(1L, sinceMillis / 1000L)
        val toSec = max(fromSec + 60L, nowMillis / 1000L)
        val url = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=$currencyPair&interval=$interval&from=$fromSec&to=$toSec"
        val body = get(url) ?: return null
        val arr = json.parseToJsonElement(body).jsonArray
        val candles = arr.mapNotNull { row ->
            val items = row as? JsonArray ?: return@mapNotNull null
            if (items.size < 6) return@mapNotNull null
            val tsSec = items[0].asLong() ?: return@mapNotNull null
            val v = items[1].asDouble() ?: 0.0
            val c = items[2].asDouble() ?: return@mapNotNull null
            val h = items[3].asDouble() ?: return@mapNotNull null
            val l = items[4].asDouble() ?: return@mapNotNull null
            val o = items[5].asDouble() ?: return@mapNotNull null
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) return@mapNotNull null
            Candle(time = tsSec * 1000L, open = o, high = h, low = l, close = c, volume = v, closed = true)
        }.sortedBy { it.time }
        if (candles.isEmpty()) return null
        return candles to "Gate.io ($interval)"
    }

    private fun fetchYahooGapCandles(
        yahooTicker: String,
        interval: String,
        gapMs: Long,
        sinceMillis: Long,
        hostSubdomain: String,
    ): Pair<List<Candle>, String>? {
        val range = when {
            gapMs <= 22L * 3600_000L -> "1d"
            gapMs <= 5L * 24L * 3600_000L -> "5d"
            else -> "1mo"
        }
        val url = "https://$hostSubdomain.finance.yahoo.com/v8/finance/chart/$yahooTicker?interval=$interval&range=$range&includePrePost=false"
        val body = get(url) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val result = (root["chart"] as? JsonObject)
            ?.get("result")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject ?: return null
        val timestamps = (result["timestamp"] as? JsonArray)?.map { it.asLong() } ?: return null
        val quote = (result["indicators"] as? JsonObject)
            ?.get("quote")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject ?: return null
        val opens = (quote["open"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val highs = (quote["high"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val lows = (quote["low"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val closes = (quote["close"] as? JsonArray)?.map { it.asDouble() } ?: return null
        val volumes = (quote["volume"] as? JsonArray)?.map { it.asDouble() }

        val n = minOf(timestamps.size, opens.size, highs.size, lows.size, closes.size)
        val allCandles = mutableListOf<Candle>()
        for (i in 0 until n) {
            val tsSec = timestamps[i] ?: continue
            val o = opens[i] ?: continue
            val h = highs[i] ?: continue
            val l = lows[i] ?: continue
            val c = closes[i] ?: continue
            val v = volumes?.getOrNull(i) ?: 0.0
            if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) continue
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) continue
            allCandles += Candle(
                time = tsSec * 1000L,
                open = o,
                high = h,
                low = l,
                close = c,
                volume = v,
                closed = true,
            )
        }
        if (allCandles.isEmpty()) return null
        val filtered = allCandles.filter { it.time >= sinceMillis - 10 * 60_000L }.sortedBy { it.time }
        val finalBars = if (filtered.isNotEmpty()) filtered else allCandles.takeLast(60)
        return finalBars to "Yahoo Finance ($interval)"
    }

    private fun fetchGoldApi(metalCode: String, provider: String): PriceTick? {
        val body = get("https://api.gold-api.com/price/$metalCode") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["price"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = provider,
        )
    }

    private fun fetchSwissquoteBbo(pair: String): PriceTick? {
        val body = get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/$pair")
            ?: return null
        val arr = json.parseToJsonElement(body).jsonArray
        for (platformElem in arr) {
            val profiles = platformElem.jsonObject["spreadProfilePrices"] as? JsonArray ?: continue
            for (profElem in profiles) {
                val prof = profElem.jsonObject
                val bid = prof["bid"]?.asDouble()
                val ask = prof["ask"]?.asDouble()
                if (bid != null && ask != null && bid > 0.0 && ask >= bid) {
                    val mid = (bid + ask) / 2.0
                    return PriceTick(
                        price = mid,
                        at = System.currentTimeMillis(),
                        bid = bid,
                        ask = ask,
                        isTradeTick = true,
                        provider = "Swissquote",
                    )
                }
            }
        }
        return null
    }

    private fun fetchYahooChart(yahooTicker: String): PriceTick? {
        for (host in listOf("query1", "query2")) {
            val url = "https://$host.finance.yahoo.com/v8/finance/chart/$yahooTicker?interval=1m&range=1d&includePrePost=false"
            val body = get(url) ?: continue
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            val result = (root["chart"] as? JsonObject)
                ?.get("result")
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.jsonObject ?: continue
            val meta = result["meta"]?.jsonObject ?: continue
            val price = meta["regularMarketPrice"]?.asDouble() ?: continue
            if (price.isFinite() && price > 0.0) {
                return PriceTick(
                    price = price,
                    at = System.currentTimeMillis(),
                    isTradeTick = true,
                    provider = "Yahoo Finance",
                )
            }
        }
        return null
    }

    private fun fetchKucoinCandles(
        symbol: String,
        kucoinType: String,
        sinceMillis: Long,
        nowMillis: Long,
    ): Pair<List<Candle>, String>? {
        val startAtSec = max(1L, sinceMillis / 1000L)
        val endAtSec = max(startAtSec + 60L, nowMillis / 1000L)
        val url = "https://api.kucoin.com/api/v1/market/candles?type=$kucoinType&symbol=$symbol&startAt=$startAtSec&endAt=$endAtSec"
        val body = get(url) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonArray ?: return null
        val candles = data.mapNotNull { row ->
            val items = row as? JsonArray ?: return@mapNotNull null
            if (items.size < 6) return@mapNotNull null
            val tsSec = items[0].asLong() ?: return@mapNotNull null
            val o = items[1].asDouble() ?: return@mapNotNull null
            val c = items[2].asDouble() ?: return@mapNotNull null
            val h = items[3].asDouble() ?: return@mapNotNull null
            val l = items[4].asDouble() ?: return@mapNotNull null
            val v = items[5].asDouble() ?: 0.0
            if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0 || h < l) return@mapNotNull null
            Candle(time = tsSec * 1000L, open = o, high = h, low = l, close = c, volume = v, closed = true)
        }.sortedBy { it.time }
        if (candles.isEmpty()) return null
        return candles to "KuCoin ($kucoinType)"
    }

    private fun fetchKucoinTicker(symbol: String): PriceTick? {
        val body = get("https://api.kucoin.com/api/v1/market/orderbook/level1?symbol=$symbol") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return null
        val price = data["price"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        val bid = data["bestBid"]?.asDouble()
        val ask = data["bestAsk"]?.asDouble()
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            bid = bid,
            ask = ask,
            isTradeTick = true,
            provider = "KuCoin",
        )
    }

    private fun fetchCoinbaseSpot(pair: String): PriceTick? {
        val body = get("https://api.coinbase.com/v2/prices/$pair/spot") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return null
        val price = data["amount"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = "Coinbase",
        )
    }

    private fun fetchBinanceTicker(pair: String): PriceTick? {
        val body = get("https://data-api.binance.vision/api/v3/ticker/price?symbol=${pair.uppercase(Locale.ROOT)}")
            ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["price"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = "Binance",
        )
    }

    private fun fetchOkxTicker(instId: String): PriceTick? {
        val body = get("https://www.okx.com/api/v5/market/ticker?instId=$instId") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val data = (root["data"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        val last = data["last"]?.asDouble() ?: return null
        if (!last.isFinite() || last <= 0.0) return null
        val bid = data["bidPx"]?.asDouble()
        val ask = data["askPx"]?.asDouble()
        return PriceTick(
            price = last,
            at = System.currentTimeMillis(),
            bid = bid,
            ask = ask,
            isTradeTick = true,
            provider = "OKX",
        )
    }

    private fun fetchGateTicker(currencyPair: String): PriceTick? {
        val body = get("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=$currencyPair") ?: return null
        val first = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
        val last = first["last"]?.asDouble() ?: return null
        if (!last.isFinite() || last <= 0.0) return null
        return PriceTick(
            price = last,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = "Gate.io",
        )
    }

    private fun fetchMexcTicker(symbol: String): PriceTick? {
        val body = get("https://api.mexc.com/api/v3/ticker/price?symbol=$symbol") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val price = root["price"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = "MEXC",
        )
    }

    private fun get(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase(Locale.ROOT)
        val isYahoo = host.contains("yahoo.com")
        if (!isYahoo && host.isNotBlank()) {
            val cooldownUntil = hostCooldownUntilMs[host] ?: 0L
            if (System.currentTimeMillis() < cooldownUntil) {
                return null
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; AurumEdge/3.9)")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (!isYahoo && host.isNotBlank() && (resp.code == 403 || resp.code == 451 || resp.code >= 500)) {
                        hostCooldownUntilMs[host] = System.currentTimeMillis() + 180_000L
                    }
                    return null
                }
                hostCooldownUntilMs.remove(host)
                resp.body?.string()
            }
        } catch (t: Throwable) {
            if (!isYahoo && host.isNotBlank()) {
                hostCooldownUntilMs[host] = System.currentTimeMillis() + 180_000L
            }
            null
        }
    }

    private fun JsonElement.asDouble(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull
            ?: (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement.asLong(): Long? =
        (this as? JsonPrimitive)?.longOrNull
            ?: (this as? JsonPrimitive)?.content?.toLongOrNull()
}
