package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
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

object LiveQuoteFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        AppNetwork.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLiveTick(symbolInput: String): PriceTick? = withContext(Dispatchers.IO) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return@withContext null

        val fetchers: List<() -> PriceTick?> = when (symbol) {
            "XAU/USD" -> listOf(
                { fetchGoldApi("XAU", "Gold-API") },
                { fetchSwissquoteBbo("XAU/USD") },
                { fetchYahooChart("GC=F") },
            )
            "XAG/USD" -> listOf(
                { fetchGoldApi("XAG", "Gold-API") },
                { fetchSwissquoteBbo("XAG/USD") },
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
                    { fetchOkxTicker("$base-USDT") },
                    { fetchGateTicker("${base}_USDT") },
                    { fetchMexcTicker("${base}USDT") },
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
                listOf(
                    { fetchBinanceKlines("${base}USDT", intervalCode, sinceMillis, nowMillis) },
                    { fetchOkxCandles("$base-USDT", intervalCode, sinceMillis) },
                    { fetchGateCandles("${base}_USDT", intervalCode, sinceMillis, nowMillis) },
                )
            }
            "XAU/USD" -> listOf(
                { fetchYahooGapCandles("GC=F", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("GC=F", intervalCode, gapMs, sinceMillis, "query2") },
            )
            "XAG/USD" -> listOf(
                { fetchYahooGapCandles("SI=F", intervalCode, gapMs, sinceMillis, "query1") },
                { fetchYahooGapCandles("SI=F", intervalCode, gapMs, sinceMillis, "query2") },
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
     * دریافت کندل‌های تایم‌فریم مشخص (مثلاً `4H`، `1H`، `15m`) برای اجرای استراتژی ایچیموکوی بیت‌کوین (`BTC/USDT`).
     * کندل در حال تشکیل (که هنوز بسته نشده است) با `closed = false` علامت‌گذاری می‌شود تا موتور ایچیموکو
     * طبق قانون کارن پلویل روی کندل‌های بسته‌شده تصمیم بگیرد.
     */
    suspend fun fetchTimeframeCandles(
        symbolInput: String = "BTC/USDT",
        interval: Interval,
        limit: Int = 120,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<List<Candle>, String>? = withContext(Dispatchers.IO) {
        val symbol = V1Universe.normalize(symbolInput)
        if (!V1Universe.valid(symbol)) return@withContext null
        val base = symbol.substringBefore('/')
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
        val sinceMillis = nowMillis - interval.millis * (limit + 5L)

        val attempts: List<() -> Pair<List<Candle>, String>?> = listOf(
            {
                fetchBinanceKlines("${base}USDT", binanceInterval, sinceMillis, nowMillis)?.let { (bars, prov) ->
                    markClosedFlags(bars, interval, nowMillis) to prov
                }
            },
            {
                fetchOkxCandles("$base-USDT", okxBar, sinceMillis)?.let { (bars, prov) ->
                    markClosedFlags(bars, interval, nowMillis) to prov
                }
            },
            {
                fetchGateCandles("${base}_USDT", gateInterval, sinceMillis, nowMillis)?.let { (bars, prov) ->
                    markClosedFlags(bars, interval, nowMillis) to prov
                }
            },
        )

        for (attempt in attempts) {
            val res = runCatching { attempt() }.getOrNull()
            if (res != null && res.first.size >= 35) {
                return@withContext res
            }
        }
        null
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
    ): Pair<List<Candle>, String>? {
        val url = "https://data-api.binance.vision/api/v3/klines?symbol=${pair.uppercase(Locale.ROOT)}&interval=$interval&startTime=$sinceMillis&endTime=$nowMillis&limit=1000"
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
    ): Pair<List<Candle>, String>? {
        val url = "https://www.okx.com/api/v5/market/candles?instId=$instId&bar=$interval&limit=300"
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
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$yahooTicker?interval=1m&range=1d&includePrePost=false"
        val body = get(url) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val result = (root["chart"] as? JsonObject)
            ?.get("result")
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject ?: return null
        val meta = result["meta"]?.jsonObject ?: return null
        val price = meta["regularMarketPrice"]?.asDouble() ?: return null
        if (!price.isFinite() || price <= 0.0) return null
        return PriceTick(
            price = price,
            at = System.currentTimeMillis(),
            isTradeTick = true,
            provider = "Yahoo Finance",
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
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; AurumEdge/3.3)")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun JsonElement.asDouble(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull
            ?: (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement.asLong(): Long? =
        (this as? JsonPrimitive)?.longOrNull
            ?: (this as? JsonPrimitive)?.content?.toLongOrNull()
}
