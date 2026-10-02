package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Keyless deep historical candles from Dukascopy's public datafeed.
 *
 * This is used as the second public history path when Yahoo has no/too little XAU/USD history.
 * Values are real provider BID candles, decompressed from Dukascopy .bi5 files. No placeholder,
 * interpolation or GC futures proxy is used: XAU/USD history remains spot-gold history.
 */
class DukascopyHistoryClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
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
        if (trimmed.size < minimumSize) {
            throw DataFeedException("Dukascopy فقط ${trimmed.size} کندل واقعی داد؛ حداقل $minimumSize لازم است")
        }
        Result(
            candles = trimmed,
            provider = "Dukascopy عمومی (تاریخچهٔ BID واقعی) · $filesRead فایل · بدون ساخت کندل",
            filesRead = filesRead,
        )
    }

    private fun fetchDailyMinuteCandles(
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

    private fun fetchMonthlyHourCandles(
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

    private fun fetchYearlyDayCandles(
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
        private const val MAX_COMPRESSED_BYTES = 2_000_000L
        private fun two(value: Int): String = value.toString().padStart(2, '0')
        private fun zeroMonth(monthValue: Int): String = two(monthValue - 1)

        internal fun instrument(symbol: String): String? = when (symbol.trim().uppercase(Locale.ROOT)) {
            "XAU/USD" -> "XAUUSD"
            "EUR/USD" -> "EURUSD"
            "GBP/USD" -> "GBPUSD"
            "AUD/USD" -> "AUDUSD"
            "NZD/USD" -> "NZDUSD"
            "USD/JPY" -> "USDJPY"
            "USD/CHF" -> "USDCHF"
            "USD/CAD" -> "USDCAD"
            else -> null
        }

        internal fun pointScale(instrument: String): Double = when {
            instrument == "XAUUSD" -> 1_000.0
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

        private fun validOhlc(candle: Candle): Boolean =
            listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0.0 } &&
                candle.low <= minOf(candle.open, candle.close) &&
                candle.high >= maxOf(candle.open, candle.close) &&
                candle.low <= candle.high

        private const val RECORD_BYTES = 24
    }
}
