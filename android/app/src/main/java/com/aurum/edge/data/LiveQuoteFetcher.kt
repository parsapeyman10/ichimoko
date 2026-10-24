package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.V1Universe
import java.util.Locale
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient
import okhttp3.Request

object LiveQuoteFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        AppNetwork.builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
            .header("User-Agent", "Mozilla/5.0 (Android; AurumEdge/3.2)")
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
}
