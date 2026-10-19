package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keyless historical candles from Dukascopy's public datafeed.
 *
 * Two transports of the SAME publisher are used, because they fail independently:
 *  1. the public chart JSON feed (`freeserv.dukascopy.com/...json3`), one request per
 *     timeframe with real M1/M5/M15/H1/4H/D1 BID bars;
 *  2. the `.bi5` archive files (`datafeed.dukascopy.com`), which are deeper but have
 *     answered HTTP 503 for every request, so they cannot be the only path.
 *
 * Both transports are Dukascopy BID candles; values are real provider bars, never a proxy
 * instrument, interpolation or synthesized candle. Only bars whose full interval has
 * elapsed are returned as closed.
 */
class DukascopyHistoryClient(
    private val client: OkHttpClient = AppNetwork.builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val chartBaseUrl: String = CHART_BASE_URL,
) {
    data class Result(
        val candles: List<Candle>,
        val provider: String,
        val fetchedAt: Long = System.currentTimeMillis(),
        val filesRead: Int,
    )

    suspend fun fetchCandles(
        symbol: String,
        interval: Interval,
        minimumSize: Int = HistoryPolicy.CHART_BOOTSTRAP_MINIMUM,
        desiredSize: Int = HistoryPolicy.CHART_BOOTSTRAP_CANDLES,
    ): Result = withContext(Dispatchers.IO) {
        val instrument = instrument(symbol)
            ?: throw DataFeedException("برای این نماد تاریخچهٔ عمومی Dukascopy تعریف نشده است")
        val keepSize = HistoryPolicy.deepProviderRequestSize(desiredSize, minimumSize)
        val failures = mutableListOf<String>()

        // 1) The chart JSON transport answers in one request and serves each requested
        //    timeframe directly (no M5→M15/H1 derivation).
        val chartBars = try {
            fetchChartCandles(instrument, interval, keepSize)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failures += "نمودار JSON: ${(error.message ?: "خطای نامشخص").take(120)}"
            emptyList()
        }
        if (chartBars.size >= minimumSize) {
            return@withContext Result(chartBars, chartProvider(), filesRead = 0)
        }
        if (chartBars.isNotEmpty()) {
            failures += "نمودار JSON فقط ${chartBars.size} کندل واقعی داد"
        }

        // 2) The archive transport is deeper; use it when the JSON feed is short or down.
        val fileResult = try {
            fetchFileCandles(instrument, interval, keepSize)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failures += "فایل bi5: ${(error.message ?: "خطای نامشخص").take(120)}"
            null
        }
        val fileBars = fileResult?.candles ?: emptyList()
        val useFiles = fileBars.size > chartBars.size
        val best = if (useFiles) fileBars else chartBars
        if (best.size < minimumSize) {
            throw DataFeedException(
                "Dukascopy فقط ${best.size} کندل واقعی داد؛ حداقل $minimumSize لازم است" +
                    if (failures.isEmpty()) "" else " (${failures.joinToString("؛ ")})",
            )
        }
        Result(
            candles = best,
            provider = if (useFiles) fileProvider(fileResult!!.filesRead) else chartProvider(),
            filesRead = if (useFiles) fileResult!!.filesRead else 0,
        )
    }

    private data class FileCandles(val candles: List<Candle>, val filesRead: Int)

    /** Public chart JSON feed of the same publisher; one request per timeframe. */
    private suspend fun fetchChartCandles(
        instrument: String,
        interval: Interval,
        keepSize: Int,
    ): List<Candle> {
        val chartInterval = chartInterval(interval)
            ?: throw DataFeedException("بازهٔ ${interval.label} برای نمودار Dukascopy تعریف نشده است")
        val limit = keepSize.coerceAtLeast(1) + 2 // room for the forming candle
        val url = buildString {
            append(chartBaseUrl)
            append("?path=chart/json3&instrument=").append(chartInstrument(instrument))
            append("&offer_side=B&interval=").append(chartInterval)
            append("&splits=true&stocks=true&time_direction=P")
            append("&timestamp=").append(System.currentTimeMillis())
            append("&jsonp=aurum&limit=").append(limit)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "https://www.dukascopy.com/")
            .header("User-Agent", CHART_USER_AGENT)
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("Dukascopy chart HTTP ${response.code}")
            val declared = response.body?.contentLength() ?: -1L
            if (declared > MAX_CHART_BYTES) throw DataFeedException("پاسخ نمودار Dukascopy بیش از حد بزرگ است")
            val bytes = response.peekBody(MAX_CHART_BYTES + 1L).bytes()
            if (bytes.size.toLong() > MAX_CHART_BYTES) throw DataFeedException("پاسخ نمودار Dukascopy بیش از حد بزرگ است")
            bytes.toString(Charsets.UTF_8)
        }
        currentCoroutineContext().ensureActive()
        val receivedAt = System.currentTimeMillis()
        val base = parseChartPayload(body, if (interval == Interval.M3) Interval.M1 else interval, receivedAt)
        val bars = if (interval == Interval.M3) MtfAnalyzer.resample(base, Interval.M3, Interval.M1) else base
        if (bars.isEmpty()) throw DataFeedException("نمودار Dukascopy کندل بسته‌ای برنگرداند")
        return bars.sortedBy { it.time }.distinctBy { it.time }.takeLast(keepSize)
    }

    /** Deep `.bi5` archive transport. Kept intact; it is simply no longer the only path. */
    private suspend fun fetchFileCandles(
        instrument: String,
        interval: Interval,
        keepSize: Int,
    ): FileCandles {
        val pointScale = pointScale(instrument)
        val baseInterval = when (interval) {
            Interval.H1, Interval.H4 -> Interval.H1
            Interval.D1 -> Interval.D1
            else -> Interval.M1
        }
        val (baseCandles, filesRead) = when (baseInterval) {
            Interval.M1 -> fetchDailyMinuteCandles(instrument, pointScale, interval, keepSize)
            Interval.H1 -> fetchMonthlyHourCandles(instrument, pointScale, interval, keepSize)
            Interval.D1 -> fetchYearlyDayCandles(instrument, pointScale, keepSize)
            else -> emptyList<Candle>() to 0
        }
        if (baseCandles.isEmpty()) throw DataFeedException("Dukascopy تاریخچهٔ قابل خواندن برنگرداند")
        val finalBars = if (baseInterval == interval) baseCandles else MtfAnalyzer.resample(baseCandles, interval, baseInterval)
        val trimmed = finalBars.sortedBy { it.time }.distinctBy { it.time }.takeLast(keepSize)
        if (trimmed.size < HistoryPolicy.minSize(interval)) {
            throw DataFeedException("Dukascopy فقط ${trimmed.size} کندل واقعی داد؛ حداقل ${HistoryPolicy.minSize(interval)} لازم است")
        }
        return FileCandles(trimmed, filesRead)
    }

    private fun chartProvider(): String = "Dukascopy عمومی (BID واقعی نمودار JSON) · بدون ساخت کندل"

    private fun fileProvider(filesRead: Int): String =
        "Dukascopy عمومی (تاریخچهٔ BID واقعی) · $filesRead فایل · بدون ساخت کندل"

    private suspend fun fetchDailyMinuteCandles(
        instrument: String,
        pointScale: Double,
        targetInterval: Interval,
        keepSize: Int,
    ): Pair<List<Candle>, Int> {
        val estimatedDays = kotlin.math.ceil(keepSize * targetInterval.minutes / 1440.0 * 1.8).toInt()
        val days = estimatedDays.coerceAtLeast(12).coerceAtMost(420)
        val now = System.currentTimeMillis()
        val out = ArrayList<Candle>()
        var files = 0
        val today = LocalDate.now(ZoneOffset.UTC)
        var offset = 0
        while (offset < days) {
            currentCoroutineContext().ensureActive()
            val day = today.minusDays(offset.toLong())
            val url = "https://datafeed.dukascopy.com/datafeed/$instrument/${day.year}/${zeroMonth(day.monthValue)}/${two(day.dayOfMonth)}/BID_candles_min_1.bi5"
            val bytes = fetchBytesOrNull(url)
            if (bytes != null) {
                val parsed = parseCandlePayload(bytes, pointScale, day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), Interval.M1, now)
                if (parsed.isNotEmpty()) {
                    files++
                    out += parsed
                }
            }
            // M1/M5 chart should open quickly once enough raw minutes have been collected.
            if (out.size >= keepSize * targetInterval.minutes * 2) break
            offset++
        }
        return out.sortedBy { it.time }.takeLast(keepSize * targetInterval.minutes.coerceAtLeast(1) * 2) to files
    }

    private suspend fun fetchMonthlyHourCandles(
        instrument: String,
        pointScale: Double,
        targetInterval: Interval,
        keepSize: Int,
    ): Pair<List<Candle>, Int> {
        val estimatedMonths = kotlin.math.ceil(keepSize * targetInterval.minutes / (60.0 * 24.0 * 30.0) * 1.6).toInt()
        val months = estimatedMonths.coerceAtLeast(if (targetInterval == Interval.H4) 30 else 8).coerceAtMost(72)
        val now = System.currentTimeMillis()
        val out = ArrayList<Candle>()
        var files = 0
        var month = YearMonth.now(ZoneOffset.UTC)
        var offset = 0
        val rawNeed = keepSize * if (targetInterval == Interval.H4) 5 else 2
        while (offset < months) {
            currentCoroutineContext().ensureActive()
            val url = "https://datafeed.dukascopy.com/datafeed/$instrument/${month.year}/${zeroMonth(month.monthValue)}/BID_candles_hour_1.bi5"
            val bytes = fetchBytesOrNull(url)
            if (bytes != null) {
                val parsed = parseCandlePayload(bytes, pointScale, month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), Interval.H1, now)
                if (parsed.isNotEmpty()) {
                    files++
                    out += parsed
                }
            }
            if (out.size >= rawNeed) break
            month = month.minusMonths(1)
            offset++
        }
        return out.sortedBy { it.time }.takeLast(rawNeed) to files
    }

    private suspend fun fetchYearlyDayCandles(
        instrument: String,
        pointScale: Double,
        keepSize: Int,
    ): Pair<List<Candle>, Int> {
        val years = kotlin.math.ceil(keepSize / 250.0 * 1.2).toInt().coerceAtLeast(15).coerceAtMost(30)
        val now = System.currentTimeMillis()
        val out = ArrayList<Candle>()
        var files = 0
        var year = Year.now(ZoneOffset.UTC).value
        var offset = 0
        while (offset < years) {
            currentCoroutineContext().ensureActive()
            val url = "https://datafeed.dukascopy.com/datafeed/$instrument/$year/BID_candles_day_1.bi5"
            val bytes = fetchBytesOrNull(url)
            if (bytes != null) {
                val parsed = parseCandlePayload(bytes, pointScale, LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), Interval.D1, now)
                if (parsed.isNotEmpty()) {
                    files++
                    out += parsed
                }
            }
            if (out.size >= keepSize) break
            year -= 1
            offset++
        }
        return out.sortedBy { it.time }.takeLast(keepSize) to files
    }

    private fun fetchBytesOrNull(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "AurumEdge/1 Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 204) return null
            if (!response.isSuccessful) throw DataFeedException("Dukascopy HTTP ${response.code}")
            val declared = response.body?.contentLength() ?: -1L
            if (declared > MAX_COMPRESSED_BYTES) throw DataFeedException("فایل تاریخچهٔ Dukascopy بیش از حد بزرگ است")
            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            if (bytes.size.toLong() > MAX_COMPRESSED_BYTES) throw DataFeedException("فایل تاریخچهٔ Dukascopy بیش از حد بزرگ است")
            return bytes
        }
    }

    companion object {
        internal const val CHART_BASE_URL = "https://freeserv.dukascopy.com/2.0/index.php"
        private const val CHART_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"
        private const val MAX_CHART_BYTES = 2_000_000L
        private const val MAX_COMPRESSED_BYTES = 2_000_000L
        private fun two(value: Int): String = value.toString().padStart(2, '0')
        private fun zeroMonth(monthValue: Int): String = two(monthValue - 1)

        internal fun instrument(symbol: String): String? = when (symbol.trim().uppercase(Locale.ROOT)) {
            "XAU/USD" -> "XAUUSD"
            "XAG/USD" -> "XAGUSD"
            "DOW", "US30", "DJI" -> "USA30IDXUSD"
            "EUR/USD" -> "EURUSD"
            "GBP/USD" -> "GBPUSD"
            "AUD/USD" -> "AUDUSD"
            "NZD/USD" -> "NZDUSD"
            "USD/JPY" -> "USDJPY"
            "USD/CHF" -> "USDCHF"
            "USD/CAD" -> "USDCAD"
            // Same-pair FX crosses, not an index/ETF or converted proxy. Missing
            // instruments still fail at the actual publisher; never create bars.
            "EUR/GBP" -> "EURGBP"
            "EUR/JPY" -> "EURJPY"
            "GBP/JPY" -> "GBPJPY"
            "AUD/JPY" -> "AUDJPY"
            "CAD/JPY" -> "CADJPY"
            "CHF/JPY" -> "CHFJPY"
            "NZD/JPY" -> "NZDJPY"
            "EUR/AUD" -> "EURAUD"
            "EUR/CAD" -> "EURCAD"
            "EUR/CHF" -> "EURCHF"
            "GBP/AUD" -> "GBPAUD"
            "GBP/CAD" -> "GBPCAD"
            "GBP/CHF" -> "GBPCHF"
            "AUD/CAD" -> "AUDCAD"
            "AUD/CHF" -> "AUDCHF"
            "AUD/NZD" -> "AUDNZD"
            "CAD/CHF" -> "CADCHF"
            "NZD/CAD" -> "NZDCAD"
            else -> null
        }

        /** Dukascopy chart instruments are slash-separated; archives strip the slash. */
        internal fun chartInstrument(instrument: String): String = when (instrument) {
            "XAUUSD" -> "XAU/USD"
            "XAGUSD" -> "XAG/USD"
            "USA30IDXUSD" -> "USA30IDXUSD"
            else -> if (instrument.length == 6) "${instrument.take(3)}/${instrument.drop(3)}" else instrument
        }

        internal fun chartInterval(interval: Interval): String? = when (interval) {
            Interval.M1, Interval.M3 -> "1MIN"
            Interval.M5 -> "5MIN"
            Interval.M15 -> "15MIN"
            Interval.M30 -> "30MIN"
            Interval.H1 -> "1HOUR"
            Interval.H4 -> "4HOUR"
            Interval.D1 -> "1DAY"
        }

        internal fun pointScale(instrument: String): Double = when {
            instrument == "XAUUSD" || instrument == "XAGUSD" || instrument == "USA30IDXUSD" -> 1_000.0
            instrument.endsWith("JPY") -> 1_000.0
            else -> 100_000.0
        }

        internal fun parseCandlePayload(
            compressed: ByteArray,
            pointScale: Double,
            baseTime: Long,
            interval: Interval,
            now: Long = System.currentTimeMillis(),
        ): List<Candle> {
            val raw = runCatching {
                LZMAInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            }.getOrElse { throw DataFeedException("فایل Dukascopy قابل بازکردن نیست") }
            return parseRawCandlePayload(raw, pointScale, baseTime, interval, now)
        }

        internal fun parseRawCandlePayload(
            raw: ByteArray,
            pointScale: Double,
            baseTime: Long,
            interval: Interval,
            now: Long = System.currentTimeMillis(),
        ): List<Candle> {
            if (raw.isEmpty()) return emptyList()
            if (raw.size % RECORD_BYTES != 0) throw DataFeedException("ساختار باینری Dukascopy معتبر نیست")
            val out = ArrayList<Candle>(raw.size / RECORD_BYTES)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            var previous = 0L
            while (buffer.remaining() >= RECORD_BYTES) {
                val offsetSeconds = buffer.int.toLong()
                val first = buffer.int / pointScale
                val second = buffer.int / pointScale
                val third = buffer.int / pointScale
                val fourth = buffer.int / pointScale
                val volume = buffer.float.toDouble().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                val time = baseTime + offsetSeconds * 1000L
                if (time <= previous || time > now + 60_000L || time % interval.millis != 0L) continue
                previous = time
                val candidates = listOf(
                    Candle(time, first, second, third, fourth, volume, closed = true),
                    Candle(time, first, fourth, third, second, volume, closed = true),
                )
                val candle = candidates.firstOrNull { validOhlc(it) } ?: continue
                out += candle
            }
            return out
        }

        /**
         * Parses the JSONP chart body: `cb([[epochMillis,open,high,low,close,volume],…]);`.
         * Rows are returned newest-first by the publisher; the result is oldest-first and
         * never contains the still-forming candle.
         */
        internal fun parseChartPayload(
            body: String,
            interval: Interval,
            now: Long = System.currentTimeMillis(),
        ): List<Candle> {
            val open = body.indexOf('(')
            val close = body.lastIndexOf(')')
            if (open < 0 || close <= open) throw DataFeedException("پاسخ نمودار Dukascopy ساختار JSONP ندارد")
            val payload = body.substring(open + 1, close).trim()
            if (payload.isEmpty() || payload == "null") throw DataFeedException("نمودار Dukascopy داده‌ای برنگرداند")
            val rows = runCatching { Json.parseToJsonElement(payload) as? JsonArray }.getOrNull()
                ?: throw DataFeedException("پاسخ نمودار Dukascopy قابل خواندن نیست")
            val out = ArrayList<Candle>(rows.size)
            rows.forEach { row ->
                val cells = row as? JsonArray ?: return@forEach
                if (cells.size < 5) return@forEach
                val time = cells.number(0)?.toLong() ?: return@forEach
                val openPrice = cells.number(1) ?: return@forEach
                val high = cells.number(2) ?: return@forEach
                val low = cells.number(3) ?: return@forEach
                val closePrice = cells.number(4) ?: return@forEach
                val volume = cells.number(5)?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                if (time <= 0L || time > now) return@forEach
                if (time % interval.millis != 0L) return@forEach
                // The feed includes the currently forming bar; only elapsed bars are closed.
                if (time + interval.millis > now) return@forEach
                val candle = Candle(time, openPrice, high, low, closePrice, volume, closed = true)
                if (!validOhlc(candle)) return@forEach
                out += candle
            }
            return out.sortedBy { it.time }.distinctBy { it.time }
        }

        private fun JsonArray.number(index: Int): Double? =
            (getOrNull(index) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

        private fun validOhlc(candle: Candle): Boolean =
            listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0.0 } &&
                candle.low <= minOf(candle.open, candle.close) &&
                candle.high >= maxOf(candle.open, candle.close) &&
                candle.low <= candle.high

        private const val RECORD_BYTES = 24
    }
}
