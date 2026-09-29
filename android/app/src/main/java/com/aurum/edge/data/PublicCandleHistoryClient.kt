package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keyless, public historical candles for the no-key mode.
 *
 * This adapter is deliberately narrow and honest: only fixed Yahoo Finance chart symbols for the
 * app's forex/gold workspace are accepted, the response identity/currency is checked, rows with
 * missing OHLC are skipped, and fewer than [HistoryPolicy.TARGET_CANDLES] real bars is reported as
 * a provider limitation instead of filling gaps with generated prices.
 */
class PublicCandleHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    data class Gap(val fromTime: Long, val toTime: Long, val missingBars: Long)
    data class Result(
        val candles: List<Candle>,
        val provider: String,
        /** Provider receipt time, not the last candle time. */
        val fetchedAt: Long = System.currentTimeMillis(),
        /** Observed gaps are retained as provenance; they are never padded or interpolated. */
        val gaps: List<Gap> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCandles(
        symbol: String,
        interval: Interval,
        minimumSize: Int = HistoryPolicy.TARGET_CANDLES,
        desiredSize: Int = HistoryPolicy.MAX_CACHED_CANDLES,
    ): Result = withContext(Dispatchers.IO) {
        val yahoo = yahooSymbol(symbol)
            ?: throw DataFeedException("برای این نماد تاریخچهٔ رایگانِ بدون کلید تعریف نشده است")
        val keepSize = desiredSize.coerceAtLeast(minimumSize).coerceAtMost(HistoryPolicy.MAX_PROVIDER_CANDLES)
        val requestInterval = requestInterval(interval)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            encodeYahooPath(yahoo) +
            "?interval=$requestInterval&range=${range(interval)}&includePrePost=false&events=history"
        val body = try {
            client.newCall(Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "AurumEdge/1 Android")
                .build()).execute().use { response ->
                    if (!response.isSuccessful) throw DataFeedException("تاریخچهٔ رایگان HTTP ${response.code}")
                    val bytes = response.peekBody(4_000_001L).bytes()
                    if (bytes.size > 4_000_000) throw DataFeedException("پاسخ تاریخچهٔ رایگان بیش از حد بزرگ است")
                    bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
                        ?: throw DataFeedException("پاسخ تاریخچهٔ رایگان خالی است")
                }
        } catch (e: DataFeedException) {
            throw e
        } catch (_: Exception) {
            throw DataFeedException("اتصال به تاریخچهٔ رایگان Yahoo Finance برقرار نشد")
        }
        val baseInterval = if (interval == Interval.H4) Interval.H1 else interval
        val parsed = parseYahooChart(body, symbol, yahoo, baseInterval,
            trimToCache = interval != Interval.H4, trimSize = keepSize)
        val finalBars = if (interval == Interval.H4) {
            MtfAnalyzer.resample(parsed, Interval.H4, Interval.H1)
        } else parsed
        val trimmed = finalBars.sortedBy { it.time }.takeLast(keepSize)
        if (trimmed.size < minimumSize) {
            throw DataFeedException("تاریخچهٔ رایگان فقط ${trimmed.size} کندل واقعی داد؛ حداقل ${HistoryPolicy.TARGET_CANDLES} لازم است")
        }
        val gaps = detectGaps(trimmed, interval)
        Result(
            candles = trimmed,
            provider = "Yahoo Finance عمومی (تاریخچهٔ بدون کلید)" +
                if (gaps.isEmpty()) " · بدون gap مشاهده‌شده" else " · ${gaps.size} gap واقعی بدون پرکردن",
            fetchedAt = System.currentTimeMillis(),
            gaps = gaps,
        )
    }

    internal fun detectGaps(candles: List<Candle>, interval: Interval): List<Gap> =
        candles.sortedBy { it.time }.zipWithNext().mapNotNull { (before, after) ->
            val steps = (after.time - before.time) / interval.millis
            if (steps > 1L) Gap(before.time, after.time, steps - 1L) else null
        }

    internal fun parseYahooChart(
        body: String,
        expectedSymbol: String,
        expectedYahooSymbol: String,
        interval: Interval,
        now: Long = System.currentTimeMillis(),
        trimToCache: Boolean = true,
        trimSize: Int = HistoryPolicy.MAX_CACHED_CANDLES,
    ): List<Candle> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ تاریخچهٔ رایگان نامعتبر است")
        val chart = root.obj("chart") ?: throw DataFeedException("پاسخ تاریخچهٔ رایگان فاقد chart است")
        if (chart["error"] != null && chart["error"].toString() != "null") {
            throw DataFeedException("Yahoo Finance برای این نماد خطا برگرداند")
        }
        val result = chart.array("result")?.firstOrNull() as? JsonObject
            ?: throw DataFeedException("تاریخچهٔ رایگان برای این نماد موجود نیست")
        val meta = result.obj("meta") ?: throw DataFeedException("هویت تاریخچهٔ رایگان مشخص نیست")
        val returnedSymbol = meta.text("symbol")
        if (!returnedSymbol.equals(expectedYahooSymbol, ignoreCase = true)) {
            throw DataFeedException("نماد تاریخچهٔ رایگان با درخواست یکسان نیست")
        }
        val expectedCurrency = expectedSymbol.substringAfter('/', "").uppercase(Locale.ROOT)
        if (expectedCurrency.isNotBlank() && meta.text("currency")?.uppercase(Locale.ROOT) != expectedCurrency) {
            throw DataFeedException("واحد قیمت تاریخچهٔ رایگان با نماد درخواست‌شده یکسان نیست")
        }
        val timestamps = result.array("timestamp") ?: throw DataFeedException("زمان کندل‌های رایگان موجود نیست")
        val quote = (result.obj("indicators")?.array("quote")?.firstOrNull() as? JsonObject)
            ?: throw DataFeedException("OHLC تاریخچهٔ رایگان موجود نیست")
        val opens = quote.array("open") ?: throw DataFeedException("open تاریخچهٔ رایگان موجود نیست")
        val highs = quote.array("high") ?: throw DataFeedException("high تاریخچهٔ رایگان موجود نیست")
        val lows = quote.array("low") ?: throw DataFeedException("low تاریخچهٔ رایگان موجود نیست")
        val closes = quote.array("close") ?: throw DataFeedException("close تاریخچهٔ رایگان موجود نیست")
        val volumes = quote.array("volume")
        val count = listOf(timestamps.size, opens.size, highs.size, lows.size, closes.size).minOrNull() ?: 0
        if (count <= 0) throw DataFeedException("کندل رایگان موجود نیست")
        val out = ArrayList<Candle>(count)
        val seen = HashSet<Long>()
        for (i in 0 until count) {
            val seconds = timestamps[i].num()?.toLong() ?: continue
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000L) continue
            val rawTime = seconds * 1000L
            if (rawTime > now + 60_000L) continue
            val time = rawTime - (rawTime % interval.millis)
            val open = opens[i].num()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val high = highs[i].num()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val low = lows[i].num()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            val close = closes[i].num()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            if (low > minOf(open, close) || high < maxOf(open, close) || low > high) continue
            val volume = volumes?.getOrNull(i)?.num()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            if (seen.add(time)) out += Candle(time, open, high, low, close, volume, closed = true)
        }
        val sorted = out.sortedBy { it.time }
        if (sorted.size < HistoryPolicy.TARGET_CANDLES) {
            // The caller decides whether cache+live bars can complete the window, but a too-small
            // fresh response is never silently presented as a complete 3000-bar history.
            return sorted
        }
        return if (trimToCache) sorted.takeLast(trimSize.coerceAtLeast(HistoryPolicy.TARGET_CANDLES)) else sorted
    }

    private fun yahooSymbol(symbol: String): String? = when (symbol.trim().uppercase(Locale.ROOT)) {
        "XAU/USD" -> "XAUUSD=X"
        "EUR/USD" -> "EURUSD=X"
        "GBP/USD" -> "GBPUSD=X"
        "AUD/USD" -> "AUDUSD=X"
        "NZD/USD" -> "NZDUSD=X"
        "USD/JPY" -> "JPY=X"
        "USD/CHF" -> "CHF=X"
        "USD/CAD" -> "CAD=X"
        else -> null
    }

    private fun encodeYahooPath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%3D", "=")

    private fun requestInterval(interval: Interval): String = when (interval) {
        Interval.M1 -> "1m"
        Interval.M5 -> "5m"
        Interval.M15 -> "15m"
        Interval.M30 -> "30m"
        Interval.H1, Interval.H4 -> "60m"
        Interval.D1 -> "1d"
    }

    private fun range(interval: Interval): String = when (interval) {
        Interval.M1 -> "7d"
        Interval.M5 -> "1mo"
        Interval.M15 -> "3mo"
        Interval.M30 -> "6mo"
        Interval.H1, Interval.H4 -> "2y"
        Interval.D1 -> "15y"
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.num(): Double? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}
