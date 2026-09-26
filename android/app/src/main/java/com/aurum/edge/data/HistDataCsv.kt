package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream

/** HistData's published XAUUSD M1 bid-bar CSV formats only. No ticks, DST shift or live feed. */
object HistDataCsv {
    private val name = Regex("DAT_(ASCII|MT)_XAUUSD_M1_(\\d{6})\\.CSV", RegexOption.IGNORE_CASE)
    private val zipName = Regex("HISTDATA_COM_(ASCII|MT)_XAUUSD_M1_(\\d{6})\\.ZIP", RegexOption.IGNORE_CASE)
    private val asciiDate = DateTimeFormatter.ofPattern("uuuuMMdd HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val mtDate = DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val est = ZoneOffset.ofHours(-5) // HistData EST fixed all year, NOT America/New_York DST
    private const val MAX_CSV_BYTES = 12_000_000

    fun parse(raw: String, fileName: String, now: Long = System.currentTimeMillis()): ImportedHistory {
        val match = name.matchEntire(fileName) ?: throw DataFeedException("فقط DAT_ASCII/MT_XAUUSD_M1_YYYYMM.csv از HistData قابل پژوهش است")
        val period = runCatching { YearMonth.parse(match.groupValues[2], DateTimeFormatter.ofPattern("uuuuMM")) }
            .getOrNull() ?: throw DataFeedException("ماه فایل HistData معتبر نیست")
        val ascii = match.groupValues[1].equals("ASCII", ignoreCase = true)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CSV_BYTES) { "CSV بزرگ‌تر از حد مجاز است" }
        val rows = raw.lineSequence().filter { it.isNotBlank() }
        val parsed = ArrayList<Candle>()
        var previous = 0L
        rows.forEachIndexed { index, line ->
            val cols = line.trimEnd('\r').split(if (ascii) ';' else ',')
            if (cols.size != (if (ascii) 6 else 7)) throw DataFeedException("ردیف ${index + 1} HistData ستون معتبر ندارد؛ فایل تیک/MT دیگر نیست")
            try {
                val timestamp = if (ascii) cols[0] else cols[0] + " " + cols[1]
                val local = LocalDateTime.parse(timestamp, if (ascii) asciiDate else mtDate)
                require(local.second == 0 && YearMonth.from(local) == period)
                val time = local.toInstant(est).toEpochMilli()
                require(time > previous && time + Interval.M1.millis <= now) // no duplicates, reordering or unfinished bar
                previous = time
                val shift = if (ascii) 1 else 2
                fun price(column: Int) = cols[column].toDouble().also { require(it.isFinite() && it > 0.0) }
                val o = price(shift)
                val h = price(shift + 1)
                val l = price(shift + 2)
                val c = price(shift + 3)
                require(l <= minOf(o, c) && h >= maxOf(o, c) && h >= l)
                val v = cols[shift + 4].toDouble().also { require(it.isFinite() && it >= 0.0) }
                parsed += Candle(time, o, h, l, c, v, closed = true)
            } catch (_: Exception) {
                throw DataFeedException("ردیف ${index + 1} HistData زمان EST یا BID OHLC معتبر ندارد (ردیفی حذف نشد)")
            }
            if (parsed.size > 60_000) throw DataFeedException("بیش از ۶۰هزار ردیف در یک ماه پشتیبانی نمی‌شود")
        }
        if (parsed.size < 220) throw DataFeedException("برای تحلیل دست‌کم ۲۲۰ کندل M1 تاریخی لازم است")
        // Gaps (weekends, maintenance) are possible; the median must still be genuine M1.
        val gaps = parsed.zipWithNext { first, second -> second.time - first.time }.sorted()
        if (gaps[gaps.size / 2] != Interval.M1.millis) throw DataFeedException("دادهٔ فایل کندل یک‌دقیقه‌ای نیست")
        return ImportedHistory(parsed.takeLast(5000), parsed.size, est.id)
    }

    /** Decompress in memory with an exact monthly filename, max 12MB and no path traversal. */
    internal fun unzip(bytes: ByteArray, archive: String): Pair<String, String> {
        val expected = zipName.matchEntire(archive) ?: throw DataFeedException("نام ZIP ماهانهٔ HistData XAUUSD M1 معتبر نیست")
        if (bytes.size > MAX_CSV_BYTES) throw DataFeedException("ZIP HistData بزرگ‌تر از حد مجاز است")
        var csv: Pair<String, String>? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            repeat(5) {
                val entry = input.nextEntry ?: return@use
                val path = entry.name
                if ('/' in path || '\\' in path || path.contains("..")) throw DataFeedException("مسیر ZIP غیرمجاز است")
                if (path.endsWith(".txt", ignoreCase = true)) { input.closeEntry(); return@repeat }
                val inner = name.matchEntire(path) ?: throw DataFeedException("فایل در ZIP متعلق به HistData XAUUSD M1 نیست")
                if (!inner.groupValues[1].equals(expected.groupValues[1], true) ||
                    inner.groupValues[2] != expected.groupValues[2] || csv != null)
                    throw DataFeedException("ماه/فرمت ZIP با CSV درون آن یکسان نیست")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_CSV_BYTES) throw DataFeedException("CSV در ZIP بیش از ۱۲ مگابایت است")
                    output.write(buffer, 0, size)
                }
                csv = String(output.toByteArray(), Charsets.UTF_8) to path
                input.closeEntry()
            }
            if (input.nextEntry != null) throw DataFeedException("ZIP بیش از ۵ فایل دارد")
        }
        return csv ?: throw DataFeedException("CSV ماهانه در ZIP پیدا نشد")
    }
}
