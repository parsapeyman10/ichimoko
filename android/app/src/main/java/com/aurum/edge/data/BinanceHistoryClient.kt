package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Real crypto candles and quotes, fetched by the phone itself.
 *
 * Uses Binance's public market host, which serves market data with no key, no account and
 * no signing — the trading endpoints are a different host entirely and are never touched
 * here. That is what lets crypto work with no backend, exactly like the keyless forex path.
 *
 * Same honesty rules as the rest of the app: only catalog symbols are accepted, every row
 * is validated, the still-forming bar is discarded because the engine is closed-bar only,
 * and a short history is reported as a provider limitation rather than padded.
 */
class BinanceHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Quote(val bid: Double, val ask: Double, val at: Long)

    companion object {
        const val HOST = "https://data-api.binance.vision/api/v3"
        const val PROVIDER = "Binance عمومی (بدون کلید)"
        /** Binance's hard cap for one klines request. */
        const val MAX_LIMIT = 1000

        /**
         * Map on the enum, not on [Interval.label]: the labels are "1H"/"4H"/"1D" in this
         * app while Binance expects "1h"/"4h"/"1d", so a string match would quietly fail
         * for every timeframe above 30 minutes.
         */
        fun interval(interval: Interval): String = when (interval) {
            Interval.M1 -> "1m"
            Interval.M5 -> "5m"
            Interval.M15 -> "15m"
            Interval.M30 -> "30m"
            Interval.H1 -> "1h"
            Interval.H4 -> "4h"
            Interval.D1 -> "1d"
        }

        /**
         * Parse Binance's kline array form. Kept separate from the network call so the
         * contract is unit-testable without a server.
         */
        fun parseKlines(
            body: String,
            symbol: String,
            interval: Interval,
            now: Long = System.currentTimeMillis(),
            json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
        ): List<Candle> {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (root is JsonObject) {
                val message = (root["msg"] as? JsonPrimitive)?.contentOrNull
                throw DataFeedException("بایننس خطا داد: ${message ?: "پاسخ نامعتبر"}")
            }
            val rows = root as? JsonArray ?: throw DataFeedException("پاسخ کندل بایننس آرایه نیست")
            val out = ArrayList<Candle>(rows.size)
            for (element in rows) {
                val row = element as? JsonArray ?: continue
                if (row.size < 7) continue
                fun num(index: Int): Double? =
                    (row[index] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val openTime = num(0)?.toLong() ?: continue
                val closeTime = num(6)?.toLong() ?: continue
                val open = num(1); val high = num(2); val low = num(3); val close = num(4)
                val volume = num(5) ?: 0.0
                if (open == null || high == null || low == null || close == null) continue
                if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0) continue
                if (high < maxOf(open, close) || low > minOf(open, close)) continue
                // Closed bars only: a forming candle would make the engine act on a price
                // that can still move before the bar is final.
                if (closeTime > now) continue
                out.add(Candle(time = openTime, open = open, high = high, low = low,
                    close = close, volume = volume))
            }
            if (out.isEmpty()) throw DataFeedException("هیچ کندل بستهٔ معتبری از بایننس دریافت نشد")
            return out.sortedBy { it.time }.distinctBy { it.time }
        }

        fun parseBookTicker(body: String, json: Json = Json { ignoreUnknownKeys = true }): Quote? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            fun num(key: String): Double? =
                (root[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            val bid = num("bidPrice") ?: return null
            val ask = num("askPrice") ?: return null
            if (bid <= 0 || ask <= 0 || ask < bid) return null
            return Quote(bid, ask, System.currentTimeMillis())
        }
    }

    /** Real closed candles for a catalog crypto symbol. */
    suspend fun fetchCandles(
        symbol: String,
        interval: Interval,
        desiredSize: Int,
        minimumSize: Int,
    ): PublicCandleHistoryClient.Result = withContext(Dispatchers.IO) {
        val spec = CryptoCatalog.find(symbol)
            ?: throw DataFeedException("نماد $symbol در فهرست کریپتوی اپ نیست")
        val code = interval(interval)

        val want = desiredSize.coerceIn(minimumSize, 5 * MAX_LIMIT)
        val candles = LinkedHashMap<Long, Candle>()
        var endTime: Long? = null

        // Binance caps a request at 1000 bars; page backwards until we have enough.
        while (candles.size < want) {
            val url = StringBuilder("$HOST/klines?symbol=${spec.binance}&interval=$code&limit=$MAX_LIMIT")
            endTime?.let { url.append("&endTime=").append(it) }
            val request = Request.Builder().url(url.toString())
                .header("Accept", "application/json").build()
            val body = runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw DataFeedException("بایننس پاسخ نداد (HTTP ${response.code})")
                    }
                    text
                }
            }.getOrElse { error ->
                if (error is DataFeedException) throw error
                throw DataFeedException("اتصال به بایننس برقرار نشد: ${error::class.simpleName}")
            }

            val page = parseKlines(body, symbol, interval)
            val before = candles.size
            page.forEach { candles[it.time] = it }
            val oldest = page.minOfOrNull { it.time }
            // No progress means the provider has no more history; stop rather than loop.
            if (candles.size == before || oldest == null) break
            endTime = oldest - 1
            if (page.size < MAX_LIMIT) break
        }

        val ordered = candles.values.sortedBy { it.time }
        if (ordered.size < minimumSize) {
            throw DataFeedException(
                "بایننس فقط ${ordered.size} کندل واقعی داد؛ حداقل $minimumSize کندل لازم است " +
                    "(کندل ساختگی ساخته نمی‌شود)"
            )
        }
        PublicCandleHistoryClient.Result(
            candles = ordered.takeLast(want),
            provider = PROVIDER,
        )
    }

    /** Real best bid/ask, used for the live price and the spread readout. */
    suspend fun fetchQuote(symbol: String): Quote? = withContext(Dispatchers.IO) {
        val spec = CryptoCatalog.find(symbol) ?: return@withContext null
        val request = Request.Builder()
            .url("$HOST/ticker/bookTicker?symbol=${spec.binance}")
            .header("Accept", "application/json").build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseBookTicker(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}
