package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.engine.MtfAnalyzer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream

/**
 * HistData's published M1 bid-bar CSV archives only (XAU/USD and the catalog FX majors).
 * No ticks, DST shift or live feed. Files may be imported ONE MONTH at a time (single CSV or
 * monthly ZIP) or as the site's YEARLY ZIP containing the monthly CSVs; several files are
 * merged into one continuous series and aggregated to a research timeframe (5m..1h) from the
 * real M1 bars — gaps stay gaps, nothing is ever synthesised or interpolated.
 */
object HistDataCsv {
    /** HistData code -> app catalog symbol. Anything not listed fails closed. */
    internal val symbolCodes: Map<String, String> = mapOf(
        "XAUUSD" to "XAU/USD", "EURUSD" to "EUR/USD", "GBPUSD" to "GBP/USD",
        "USDJPY" to "USD/JPY", "USDCHF" to "USD/CHF", "USDCAD" to "USD/CAD",
        "AUDUSD" to "AUD/USD", "NZDUSD" to "NZD/USD",
    )

    private val csvName = Regex("DAT_(ASCII|MT)_([A-Z]{6})_M1_(\\d{6})\\.CSV", RegexOption.IGNORE_CASE)
    private val monthlyZip = Regex("HISTDATA_COM_(ASCII|MT)_([A-Z]{6})_M1_(\\d{6})\\.ZIP", RegexOption.IGNORE_CASE)
    private val yearlyZip = Regex("HISTDATA_COM_(ASCII|MT)_([A-Z]{6})_M1_(\\d{4})\\.ZIP", RegexOption.IGNORE_CASE)
    private val asciiDate = DateTimeFormatter.ofPattern("uuuuMMdd HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val mtDate = DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val est = ZoneOffset.ofHours(-5) // HistData EST fixed all year, NOT America/New_York DST
    private const val MAX_CSV_BYTES = 12_000_000
    private const val MAX_ZIP_BYTES = 24_000_000
    private const val MAX_MONTHLY_ROWS = 60_000

    /** Honest ceiling for one research backtest (5 years of M5 is ~525k bars). */
    const val MAX_RESEARCH_CANDLES = 600_000

    /** One validated monthly M1 file: full candles, catalog symbol and the file's month. */
    internal data class MonthFile(val code: String, val symbol: String,
                                  val period: YearMonth, val candles: List<Candle>)

    /** A merged, timeframe-aggregated research series built ONLY from real M1 bars. */
    data class MergedHistory(
        val candles: List<Candle>,
        val interval: Interval,
        val symbol: String,
        val months: Int,
        val totalRows: Int,
        val firstTime: Long?,
        val lastTime: Long?,
        val timezone: String,
    )

    fun parse(raw: String, fileName: String, now: Long = System.currentTimeMillis()): ImportedHistory {
        val month = parseMonth(raw, fileName, now)
        return ImportedHistory(month.candles.takeLast(MAX_RESEARCH_CANDLES), month.candles.size, est.id)
    }

    /** Validate ONE monthly DAT file end-to-end (name, symbol, period, strict rows). */
    internal fun parseMonth(raw: String, fileName: String, now: Long): MonthFile {
        val match = csvName.matchEntire(fileName)
            ?: throw DataFeedException("فقط DAT_ASCII/MT_{نماد}_M1_YYYYMM.csv از HistData قابل پژوهش است")
        val code = match.groupValues[2].uppercase()
        val symbol = symbolCodes[code] ?: throw DataFeedException("نماد $code در فهرست نمادهای اپ نیست (فقط XAUUSD و ۷ جفت اصلی)")
        val period = runCatching { YearMonth.parse(match.groupValues[3], DateTimeFormatter.ofPattern("uuuuMM")) }
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
            if (parsed.size > MAX_MONTHLY_ROWS) throw DataFeedException("بیش از ۶۰هزار ردیف در یک ماه پشتیبانی نمی‌شود")
        }
        if (parsed.size < 220) throw DataFeedException("برای تحلیل دست‌کم ۲۲۰ کندل M1 تاریخی لازم است")
        // Gaps (weekends, maintenance) are possible; the median must still be genuine M1.
        val gaps = parsed.zipWithNext { first, second -> second.time - first.time }.sorted()
        if (gaps[gaps.size / 2] != Interval.M1.millis) throw DataFeedException("دادهٔ فایل کندل یک‌دقیقه‌ای نیست")
        return MonthFile(code, symbol, period, parsed)
    }

    /**
     * Merge validated months into ONE series aggregated to [target] (M1 keeps raw bars).
     * Aggregation reuses the live MTF resampler: epoch-aligned buckets of real M1 bars only,
     * buckets less than half full (weekend/holiday edges) are dropped, gaps stay gaps.
     */
    fun parseMerged(files: List<Pair<String, String>>, target: Interval,
                    now: Long = System.currentTimeMillis()): MergedHistory {
        if (files.isEmpty()) throw DataFeedException("هیچ فایل HistData انتخاب نشده است")
        val months = files.map { (csv, fileName) -> parseMonth(csv, fileName, now) }
        val first = months.first()
        if (months.any { it.code != first.code }) throw DataFeedException("همهٔ فایل‌های انتخابی باید نماد یکسان داشته باشند")
        months.groupBy { it.period }.filterValues { it.size > 1 }.keys.firstOrNull()
            ?.let { throw DataFeedException("ماه $it بین فایل‌ها تکراری است") }
        val ordered = months.sortedBy { it.period }
        val merged = ArrayList<Candle>()
        ordered.forEachIndexed { index, month ->
            val aggregated = if (target == Interval.M1) month.candles
                else MtfAnalyzer.resample(month.candles, target, Interval.M1)
            if (aggregated.isEmpty()) return@forEachIndexed
            // Only the archive's NEWEST bucket may stay open; earlier month-end buckets are
            // final (a month boundary is aligned to every supported interval), so they close.
            merged += if (index == ordered.lastIndex || target == Interval.M1) aggregated
                else aggregated.dropLast(1) + aggregated.last().copy(closed = true)
        }
        if (merged.size > MAX_RESEARCH_CANDLES)
            throw DataFeedException("دادهٔ انتخابی ${merged.size} کندل شد؛ سقف پژوهشی ${MAX_RESEARCH_CANDLES} کندل است — تایم‌فریم بالاتر (۵ دقیقه تا ۱ ساعت) انتخاب کنید")
        merged.zipWithNext { a, b -> require(b.time > a.time) { "ترتیب زمانی کندل‌های تجمیعی نامعتبر است" } }
        if (merged.size < 220) throw DataFeedException("پس از تجمیع دست‌کم ۲۲۰ کندل لازم است")
        return MergedHistory(merged, target, first.symbol, ordered.size,
            months.sumOf { it.candles.size }, merged.firstOrNull()?.time, merged.lastOrNull()?.time, est.id)
    }

    /**
     * Decompress in memory with exact monthly/yearly HistData names, per-CSV size caps and no
     * path traversal. A yearly ZIP holds up to 12 monthly CSVs (plus its .txt), a monthly ZIP
     * exactly one; every inner file must match the archive's format, symbol and period.
     */
    internal fun unzip(bytes: ByteArray, archive: String): List<Pair<String, String>> {
        val monthly = monthlyZip.matchEntire(archive)
        val yearly = monthly == null && yearlyZip.matchEntire(archive) != null
        val expected = monthly ?: yearlyZip.matchEntire(archive)
            ?: throw DataFeedException("نام ZIP ماهانه/سالانهٔ HistData M1 معتبر نیست")
        if (bytes.size > MAX_ZIP_BYTES) throw DataFeedException("ZIP HistData بزرگ‌تر از حد مجاز است")
        val format = expected.groupValues[1]
        val code = expected.groupValues[2].uppercase()
        val period = expected.groupValues[3]
        val out = ArrayList<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val path = entry.name
                if ('/' in path || '\\' in path || path.contains("..")) throw DataFeedException("مسیر ZIP غیرمجاز است")
                if (path.endsWith(".txt", ignoreCase = true)) { input.closeEntry(); continue }
                val inner = csvName.matchEntire(path) ?: throw DataFeedException("فایل در ZIP متعلق به HistData M1 نیست")
                if (!inner.groupValues[1].equals(format, true) || inner.groupValues[2].uppercase() != code)
                    throw DataFeedException("فرمت/نماد ZIP با CSV درون آن یکسان نیست")
                val innerPeriod = inner.groupValues[3]
                val periodMatches = if (yearly) innerPeriod.startsWith(period) else innerPeriod == period
                if (!periodMatches || out.any { it.second.equals(path, ignoreCase = true) })
                    throw DataFeedException("ماه/سال ZIP با CSV درون آن یکسان نیست")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_CSV_BYTES) throw DataFeedException("CSV در ZIP بیش از ۱۲ مگابایت است")
                    output.write(buffer, 0, size)
                }
                out += String(output.toByteArray(), Charsets.UTF_8) to path
                input.closeEntry()
                if (out.size > 12) throw DataFeedException("ZIP بیش از ۱۲ فایل CSV دارد")
            }
        }
        return out.ifEmpty { throw DataFeedException("CSV ماهانه در ZIP پیدا نشد") }
    }
}
