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
 * Crypto candles from Nobitex, for networks where Binance is unreachable.
 *
 * Binance geo-blocks a number of regions and answers HTTP 451 ("unavailable for legal
 * reasons"). From such a connection the app's crypto charts are simply empty, which is
 * indistinguishable from a bug. Nobitex is an Iranian exchange that serves the same
 * instruments and is reachable from exactly the networks Binance refuses.
 *
 * Its endpoint speaks the TradingView UDF format — parallel arrays of time/open/high/
 * low/close/volume — rather than Binance's array-of-arrays, so it needs its own parser.
 *
 * Market data only: no key, no account, nothing that can place an order.
 */
class NobitexHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    companion object {
        const val HOST = "https://apiv2.nobitex.ir"
        const val PROVIDER = "Nobitex عمومی (بدون کلید)"

        /** UDF resolutions, in minutes except the daily bucket. */
        fun resolution(interval: Interval): String = when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }

        /**
         * Nobitex quotes against USDT and IRT. The app's ids are "BASE/USDT", so the
         * ticker is the two halves joined — the same shape Binance uses.
         */
        fun ticker(symbol: String): String? {
            val parts = symbol.trim().uppercase().split('/')
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            return parts[0] + parts[1]
        }

        /**
         * Parse the UDF payload. Kept pure so the contract is testable without a server.
         *
         * `s` carries the status: "ok" has data, "no_data" is an empty but valid window,
         * anything else is an error that must surface rather than look like a flat market.
         */
        fun parseUdf(
            body: String,
            json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
        ): List<Candle> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw DataFeedException("پاسخ نوبیتکس معتبر نیست")
            when ((root["s"] as? JsonPrimitive)?.contentOrNull) {
                "ok" -> Unit
                "no_data" -> throw DataFeedException("نوبیتکس برای این بازه کندلی ندارد")
                else -> throw DataFeedException(
                    (root["errmsg"] as? JsonPrimitive)?.contentOrNull ?: "نوبیتکس خطا داد"
                )
            }

            fun column(key: String): List<Double> =
                (root[key] as? JsonArray).orEmpty()
                    .map { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: Double.NaN }

            val times = column("t")
            val opens = column("o")
            val highs = column("h")
            val lows = column("l")
            val closes = column("c")
            val volumes = column("v")
            // Parallel arrays only line up if every column has the same length; a short
            // column would silently shift prices onto the wrong bars.
            val size = times.size
            if (size == 0) throw DataFeedException("نوبیتکس کندلی برنگرداند")
            if (listOf(opens, highs, lows, closes).any { it.size != size }) {
                throw DataFeedException("ستون‌های کندل نوبیتکس هم‌اندازه نیستند")
            }

            val out = ArrayList<Candle>(size)
            for (i in 0 until size) {
                val time = times[i]
                val open = opens[i]; val high = highs[i]; val low = lows[i]; val close = closes[i]
                val volume = volumes.getOrNull(i)?.takeIf { !it.isNaN() } ?: 0.0
                if (listOf(time, open, high, low, close).any { it.isNaN() }) continue
                if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0) continue
                if (high < maxOf(open, close) || low > minOf(open, close)) continue
                out.add(Candle(
                    time = (time * 1000).toLong(),   // UDF reports seconds
                    open = open, high = high, low = low, close = close, volume = volume,
                ))
            }
            if (out.isEmpty()) throw DataFeedException("هیچ کندل معتبری از نوبیتکس دریافت نشد")
            return out.sortedBy { it.time }.distinctBy { it.time }
        }
    }

    suspend fun fetchCandles(
        symbol: String,
        interval: Interval,
        desiredSize: Int,
        minimumSize: Int,
        now: Long = System.currentTimeMillis(),
    ): PublicCandleHistoryClient.Result = withContext(Dispatchers.IO) {
        val code = ticker(symbol) ?: throw DataFeedException("نماد $symbol برای نوبیتکس معتبر نیست")
        val span = interval.millis / 1000L
        val to = now / 1000L
        // Ask for extra room: exchanges skip empty buckets, so a bar count is not a time span.
        val from = to - span * (desiredSize + 50).coerceAtMost(5000)

        val url = "$HOST/market/udf/history?symbol=$code&resolution=${resolution(interval)}" +
            "&from=$from&to=$to"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DataFeedException("نوبیتکس پاسخ نداد (HTTP ${response.code})")
                }
                response.body?.string().orEmpty()
            }
        }.getOrElse { error ->
            if (error is DataFeedException) throw error
            throw DataFeedException("اتصال به نوبیتکس برقرار نشد: ${error::class.simpleName}")
        }

        val candles = parseUdf(body)
        if (candles.size < minimumSize) {
            throw DataFeedException(
                "نوبیتکس فقط ${candles.size} کندل داد؛ حداقل $minimumSize کندل لازم است"
            )
        }
        PublicCandleHistoryClient.Result(candles = candles.takeLast(desiredSize), provider = PROVIDER)
    }
}
