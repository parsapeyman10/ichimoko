package com.aurum.edge.data

import android.content.Context
import android.net.Uri
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Research-only historical import. An unverified user file can NEVER become a live price. */
data class ImportedHistory(val candles: List<Candle>, val totalRows: Int, val timezone: String)

object MetaTraderCsv {
    private val dateTimes = listOf(
        "yyyy.MM.dd HH:mm:ss", "yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyyMMdd HH:mm:ss", "yyyyMMdd HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it) }

    /** MT4/MT5 CSV/TSV with DATE+TIME or DATETIME columns; reject malformed/ambiguous bars. */
    fun parse(raw: String, interval: Interval, timezone: String, now: Long = System.currentTimeMillis()): ImportedHistory {
        val offset = try { ZoneOffset.of(timezone.trim()) } catch (_: Exception) {
            throw DataFeedException("منطقه زمانی سرور متاتریدر معتبر نیست؛ مثال +03:30 یا +00:00")
        }
        val rows = raw.lineSequence().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotBlank() }.iterator()
        if (!rows.hasNext()) throw DataFeedException("فایل متاتریدر خالی است")
        val header = rows.next()
        val delimiter = when {
            '\t' in header -> '\t'
            header.count { it == ';' } > header.count { it == ',' } -> ';'
            else -> ','
        }
        fun columns(line: String): List<String> = line.split(delimiter).map { it.trim().trim('"').trim() }
        val fields = columns(header).map { it.removePrefix("<").removeSuffix(">").lowercase() }
        fun index(vararg names: String): Int = fields.indexOfFirst { it in names }
        val dateIdx = index("date", "datetime", "time")
        val timeIdx = if (index("date") >= 0) index("time") else -1
        val openIdx = index("open")
        val highIdx = index("high")
        val lowIdx = index("low")
        val closeIdx = index("close")
        val volIdx = index("tickvol", "tick_volume", "volume", "vol")
        if (dateIdx < 0 || volIdx < 0 || listOf(openIdx, highIdx, lowIdx, closeIdx).any { it < 0 }) {
            throw DataFeedException("ستون‌های DATE/TIME/OPEN/HIGH/LOW/CLOSE/VOL در CSV پیدا نشد؛ خروجی MT4/MT5 را بررسی کنید")
        }
        val parsed = ArrayList<Candle>()
        var lineNumber = 1
        while (rows.hasNext()) {
            lineNumber++
            val row = columns(rows.next())
            try {
                val timestamp = listOfNotNull(row[dateIdx], if (timeIdx >= 0) row[timeIdx] else null).joinToString(" ")
                val local = dateTimes.firstNotNullOfOrNull { format ->
                    runCatching { LocalDateTime.parse(timestamp, format) }.getOrNull()
                } ?: error("date")
                val time = local.toInstant(offset).toEpochMilli()
                fun price(index: Int): Double = row[index].toDouble().takeIf { it.isFinite() && it > 0 } ?: error("price")
                val o = price(openIdx)
                val h = price(highIdx)
                val l = price(lowIdx)
                val c = price(closeIdx)
                require(h >= maxOf(o, c) && l <= minOf(o, c) && l > 0 && time in 1L..now)
                val volume = row[volIdx].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: error("volume")
                parsed += Candle(time, o, h, l, c, volume, closed = true)
            } catch (_: Exception) {
                throw DataFeedException("ردیف $lineNumber فایل متاتریدر تاریخ یا OHLC معتبر ندارد (بدون حذف پنهانی ردیف)")
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
        return ImportedHistory(parsed.takeLast(5000), parsed.size, offset.id)
    }
}

class MetaTraderImporter(private val context: Context) {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun fromFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw DataFeedException("فایل انتخاب‌شده قابل خواندن نیست")
        input.use { String(readLimited(it), Charsets.UTF_8) }
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

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > 4_000_000) throw DataFeedException("فایل بیش از ۴ مگابایت است")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
