package com.aurum.edge.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Research-only historical import. An unverified user file can NEVER become a live price. */
data class ImportedHistory(
    val candles: List<Candle>,
    val totalRows: Int,
    val timezone: String,
    val formatLabel: String = "MT4/MT5 CSV",
    val volumeProvided: Boolean = true,
)

object MetaTraderCsv {
    private val dateTimes = listOf(
        "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyyMMdd HH:mm:ss", "yyyyMMdd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it) }

    private fun normalizeHeader(value: String): String = value
        .removePrefix("<")
        .removeSuffix(">")
        .trim()
        .lowercase()
        .replace(Regex("""[\s_./-]+"""), "")


    private fun timestampUsesEmbeddedZone(raw: String): Boolean {
        val text = raw.trim().trim('"')
        if (text.toLongOrNull() != null) return true
        if (text.endsWith("Z", ignoreCase = true)) return true
        return Regex(""".*[T ]\d{1,2}:\d{2}(:\d{2})?([+-]\d{2}:?\d{2})${'$'}""").matches(text)
    }

    private fun parseTimestamp(raw: String, offset: ZoneOffset): Long {
        val text = raw.trim().trim('"')
        text.toLongOrNull()?.let { epoch ->
            return if (epoch > 10_000_000_000L) epoch else epoch * 1000L
        }
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(text.replace('/', '-')).atStartOfDay().toInstant(offset).toEpochMilli() }
            .getOrNull()?.let { return it }
        val cleaned = text.replace('/', '-').replace('T', ' ')
        val local = dateTimes.firstNotNullOfOrNull { format ->
            runCatching { LocalDateTime.parse(cleaned, format) }.getOrNull()
        } ?: throw IllegalArgumentException("date")
        return local.toInstant(offset).toEpochMilli()
    }

    /**
     * Research-only CSV/TSV import. Accepts strict MT4/MT5 exports plus common educational
     * OHLC datasets: timestamp/datetime/date+time with OPEN/HIGH/LOW/CLOSE and optional volume.
     * Missing rows, malformed prices and timeframe mismatches fail closed; imported bars never
     * become the live chart/feed/cache.
     */
    fun parse(raw: String, interval: Interval, timezone: String, now: Long = System.currentTimeMillis()): ImportedHistory {
        val offset = try { ZoneOffset.of(timezone.trim()) } catch (_: Exception) {
            throw DataFeedException("منطقه زمانی دیتاست معتبر نیست؛ مثال +03:30 یا +00:00")
        }
        val rows = raw.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotBlank() }.iterator()
        if (!rows.hasNext()) throw DataFeedException("فایل CSV خالی است")
        val header = rows.next()
        val delimiter = when {
            '\t' in header -> '\t'
            header.count { it == ';' } > header.count { it == ',' } -> ';'
            else -> ','
        }
        fun columns(line: String): List<String> = line.split(delimiter).map { it.trim().trim('"').trim() }
        val fields = columns(header).map { normalizeHeader(it) }
        fun index(vararg names: String): Int {
            val wanted = names.map(::normalizeHeader).toSet()
            return fields.indexOfFirst { it in wanted }
        }
        val explicitDateIdx = index("date")
        val explicitTimeIdx = index("time")
        val dateIdx = index("date", "datetime", "timestamp", "gmt time", "local time", "date time", "date/time", "time")
        val timeIdx = if (explicitDateIdx >= 0 && explicitTimeIdx >= 0 && explicitTimeIdx != explicitDateIdx) explicitTimeIdx else -1
        val openIdx = index("open", "o")
        val highIdx = index("high", "h")
        val lowIdx = index("low", "l")
        val closeIdx = index("close", "c", "last")
        val volIdx = index("tickvol", "tick_volume", "tick volume", "volume", "vol", "real volume")
        if (dateIdx < 0 || listOf(openIdx, highIdx, lowIdx, closeIdx).any { it < 0 }) {
            throw DataFeedException("ستون‌های زمان/OPEN/HIGH/LOW/CLOSE در CSV پیدا نشد؛ CSV آموزشی باید هدر روشن OHLC داشته باشد")
        }
        val hasVolumeColumn = volIdx >= 0
        var volumeWasProvided = false
        var embeddedTimestampZone = false
        val parsed = ArrayList<Candle>()
        var lineNumber = 1
        while (rows.hasNext()) {
            lineNumber++
            val row = columns(rows.next())
            try {
                val timestamp = listOfNotNull(row[dateIdx], if (timeIdx >= 0) row[timeIdx] else null).joinToString(" ")
                if (timestampUsesEmbeddedZone(timestamp)) embeddedTimestampZone = true
                val time = parseTimestamp(timestamp, offset)
                fun price(index: Int): Double = row[index].toDouble().takeIf { it.isFinite() && it > 0 } ?: error("price")
                val o = price(openIdx)
                val h = price(highIdx)
                val l = price(lowIdx)
                val c = price(closeIdx)
                require(h >= maxOf(o, c) && l <= minOf(o, c) && l > 0 && time in 1L..now)
                val volume = if (hasVolumeColumn) {
                    val rawVolume = row.getOrNull(volIdx).orEmpty().trim()
                    if (rawVolume.isBlank()) 0.0 else {
                        volumeWasProvided = true
                        rawVolume.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: error("volume")
                    }
                } else 0.0
                parsed += Candle(time, o, h, l, c, volume, closed = true)
            } catch (_: Exception) {
                throw DataFeedException("ردیف $lineNumber فایل CSV تاریخ یا OHLC معتبر ندارد (بدون حذف پنهانی ردیف)")
            }
            if (parsed.size > 60_000) throw DataFeedException("بیش از ۶۰هزار کندل در یک فایل پشتیبانی نمی‌شود")
        }
        if (parsed.size < 220) throw DataFeedException("برای بک‌تست حداقل ۲۲۰ کندل بسته لازم است (${parsed.size} دریافت شد)")
        parsed.sortBy { it.time }
        if (parsed.zipWithNext().any { (first, second) -> first.time == second.time }) {
            throw DataFeedException("فایل چند کندل با زمان یکسان دارد؛ قبل از تحلیل اصلاح کنید")
        }
        val periods = parsed.zipWithNext().map { (first, second) -> second.time - first.time }.sorted()
        val median = periods[periods.size / 2]
        if (abs(median - interval.millis) > interval.millis / 5) {
            throw DataFeedException("تایم‌فریم انتخابی ${interval.label} با فاصلهٔ معمول کندل‌های فایل مطابقت ندارد")
        }
        val formatLabel = if (fields.any { it in setOf("tickvol", "tickvolume") }) "MT4/MT5 CSV" else "CSV آموزشی OHLC"
        val timezoneLabel = if (embeddedTimestampZone) "embedded timestamp / UTC" else offset.id
        return ImportedHistory(parsed.takeLast(HistDataCsv.MAX_RESEARCH_CANDLES), parsed.size, timezoneLabel,
            formatLabel = formatLabel, volumeProvided = volumeWasProvided)
    }
}

class MetaTraderImporter(private val context: Context) {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun fromFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw DataFeedException("فایل انتخاب‌شده قابل خواندن نیست")
        input.use { String(readLimited(it), Charsets.UTF_8) }
    }

    /**
     * SAF-only ZIP/CSV, one or MANY files (yearly ZIPs hold 12 monthly CSVs). A filename is a
     * format hint, never proof of publisher authenticity. Order of the returned monthly CSVs
     * is whatever the picker gave; merging re-sorts by each file's own period.
     */
    suspend fun fromHistDataFiles(uris: List<Uri>): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw DataFeedException("ابتدا فایل(ها) ZIP/CSV ماهانه یا سالانهٔ HistData را انتخاب کنید")
        if (uris.size > 30) throw DataFeedException("حداکثر ۳۰ فایل در هر تحلیل پشتیبانی می‌شود")
        val out = ArrayList<Pair<String, String>>()
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.length <= 100 && '/' !in it && '\\' !in it }
                ?: throw DataFeedException("نام فایل انتخاب‌شده برای بررسی HistData در دسترس نیست")
            val input = context.contentResolver.openInputStream(uri) ?: throw DataFeedException("فایل HistData خوانده نشد")
            val bytes = input.use { readLimited(it, 24_000_000) }
            if (name.endsWith(".zip", ignoreCase = true)) out += HistDataCsv.unzip(bytes, name)
            else out += String(bytes, Charsets.UTF_8) to name
        }
        out
    }

    suspend fun fromHttps(raw: String): String = withContext(Dispatchers.IO) {
        val url = raw.trim().takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
            ?: throw DataFeedException("لینک CSV معتبر نیست")
        val host = url.host.lowercase()
        if (url.scheme != "https" || url.username.isNotBlank() || url.password.isNotBlank() ||
            host == "localhost" || host.endsWith(".local") || ':' in host ||
            host.matches(Regex("^(127|10|192\\.168|169\\.254|172\\.(1[6-9]|2[0-9]|3[0-1]))\\..*"))) {
            throw DataFeedException("لینک باید HTTPS عمومیِ بدون نام کاربری/رمز باشد")
        }
        val request = Request.Builder().url(url).header("Accept", "text/csv, text/plain").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DataFeedException("دریافت CSV ناموفق بود (HTTP ${response.code})")
                val input = response.body?.byteStream() ?: throw DataFeedException("پاسخ لینک خالی بود")
                String(readLimited(input), Charsets.UTF_8)
            }
        } catch (e: DataFeedException) {
            throw e
        } catch (_: Exception) {
            // URLs may contain private tokens: never include URL/exception body in UI or logs.
            throw DataFeedException("لینک CSV در دسترس نبود")
        }
    }

    private fun readLimited(input: InputStream, limit: Int = 4_000_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) throw DataFeedException("فایل بیش از سقف اندازهٔ مجاز است")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
