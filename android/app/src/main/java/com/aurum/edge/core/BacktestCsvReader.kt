package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval

/**
 * خوانندهٔ فایل CSV خروجی `dukascopy-node`.
 *
 * قالب رسمی آن ابزار دو شکل دارد و هر دو اینجا پذیرفته می‌شوند:
 *
 * ```
 * timestamp,open,high,low,close,volume        ← کندل (دقیقاً خروجی `-f csv`)
 * 1612137600000,1.21225,1.21363,1.2056,1.20676,165569.8187
 * ```
 *
 * ```
 * timestamp,askPrice,bidPrice,askVolume,bidVolume   ← tick (خروجی `-t tick -f csv`)
 * ```
 *
 * قواعد مهم که در کد اعمال شده‌اند:
 *  - `timestamp` همان **شروع** دوره است و بر حسب میلی‌ثانیهٔ UTC؛ نه زمان بسته‌شدن.
 *  - فایل dukascopy معمولاً ستون volume ندارد (`-v` داده نشده) و همان‌طور هم معتبر است.
 *  - کندل‌های تخت (حجم صفر، آخر هفته) به‌صورت پیش‌فرض دور ریخته می‌شوند، چون ماشه‌های
 *    ایچیموکو روی کندل مرده معنا ندارند — همان رفتار پیش‌فرض خود ابزار.
 *  - ترتیب زمانی تضمین می‌شود؛ ردیف تکراری (همان `timestamp`) فقط یک‌بار می‌ماند و
 *    اولین ردیف برنده است.
 */
object BacktestCsvReader {

    /** نتیجهٔ خوندن یک فایل، همراه با آماری که باید در UI دیده شود. */
    data class ParseResult(
        val candles: List<Candle>,
        val sourceKind: SourceKind,
        val totalRows: Int,
        val skippedRows: Int,
        val duplicateRows: Int,
        val flatRowsDropped: Int,
        val inferredInterval: Interval,
        /** فاصلهٔ زمانی غالب بین کندل‌ها (میلی‌ثانیه) — پایهٔ حدس تایم‌فریم. */
        val dominantStepMs: Long,
        val firstTime: Long,
        val lastTime: Long,
        /** پیشنهاد نماد بر اساس نام فایل؛ خالی = نام فایل چیزی نگفت. */
        val suggestedSymbol: String,
    ) {
        val spanDays: Double get() = if (firstTime > 0L && lastTime > firstTime) (lastTime - firstTime) / 86_400_000.0 else 0.0
    }

    enum class SourceKind {
        /** کندل OHLC استاندارد. */
        CANDLE,

        /** فایل tick؛ برای بک‌تست به کندل تبدیل شده است. */
        TICK,

        /** فایل قابل تشخیص نبود. */
        UNKNOWN,
    }

    /** تایم‌فریم‌هایی که dukascopy-node تولید می‌کند و برچسب فایلش با آن‌ها ساخته می‌شود. */
    private val FILE_TIMEFRAME_MS = mapOf(
        "s1" to 1_000L,
        "m1" to 60_000L,
        "m5" to 5 * 60_000L,
        "m15" to 15 * 60_000L,
        "m30" to 30 * 60_000L,
        "h1" to 60 * 60_000L,
        "h4" to 4 * 60 * 60_000L,
        "d1" to 24 * 60 * 60_000L,
        "mn1" to 30L * 24 * 60 * 60 * 1000L,
    )

    /** نگاشت میلی‌ثانیه → نزدیک‌ترین تایم‌فریم موتور (فقط موارد موجود در `Interval`). */
    private val INTERVAL_BY_MS: List<Pair<Long, Interval>> = listOf(
        60_000L to Interval.M1,
        3 * 60_000L to Interval.M3,
        5 * 60_000L to Interval.M5,
        15 * 60_000L to Interval.M15,
        30 * 60_000L to Interval.M30,
        60 * 60_000L to Interval.H1,
        4 * 60 * 60_000L to Interval.H4,
        24 * 60 * 60_000L to Interval.D1,
    )

    /**
     * حدس نماد از نام فایل dukascopy، مثل `eurusd-m15-bid-2024-01-01-2024-06-01.csv`.
     * اگر مطمئن نبود، رشتهٔ خالی برمی‌گرداند و کاربر خودش نماد را انتخاب می‌کند.
     */
    fun suggestSymbolFromFileName(fileName: String): String {
        val base = fileName.substringBeforeLast('.').lowercase()
        // بخش اول تا اولین «-» نام ابزار است؛ همیشه نه، ولی در خروجی خود dukascopy-node بله.
        val head = base.substringBefore('-')
        if (head.length !in 6..12) return ""
        if (!head.all { it.isLetter() }) return ""
        // جفت‌ارزها ۶ حرفی‌اند: eurusd → EUR/USD
        if (head.length == 6) {
            return head.take(3).uppercase() + "/" + head.drop(3).uppercase()
        }
        // نمادهای شاخص/کالا/کریپتو نام مستعار دارند و حدس زدنشان گمراه‌کننده است.
        return ""
    }

    /**
     * تایم‌فریم را از «نام فایل» می‌خواند؛ این مطمئن‌ترین منبع است چون خود ابزار آن را در
     * نام فایل می‌گذارد (`...-m15-bid-...`). اگر پیدا نشد، `null` برمی‌گرداند.
     */
    fun intervalFromFileName(fileName: String): Interval? {
        val parts = fileName.lowercase().substringBeforeLast('.').split('-')
        val tag = parts.firstOrNull { it in FILE_TIMEFRAME_MS } ?: return null
        return nearestInterval(FILE_TIMEFRAME_MS.getValue(tag))
    }

    fun nearestInterval(stepMs: Long): Interval {
        if (stepMs <= 0L) return Interval.M15
        return INTERVAL_BY_MS.minByOrNull { kotlin.math.abs(it.first - stepMs) }?.second ?: Interval.M15
    }

    /**
     * تحلیل محتوای CSV.
     *
     * @param fileName نام فایل (برای حدس نماد و تایم‌فریم). می‌تواند خالی باشد.
     * @param timezoneOffsetMinutes اگر کاربر بداند فایلش با `-utc` چیزی جز صفر ساخته شده،
     *        این عدد زمان‌ها را به UTC برمی‌گرداند. پیش‌فرض صفر = همان UTC خود dukascopy.
     * @param dropFlatBars دور ریختن کندل‌های با حجم صفر (پیش‌فرض خود ابزار).
     */
    fun parse(
        raw: String,
        fileName: String = "",
        timezoneOffsetMinutes: Int = 0,
        dropFlatBars: Boolean = true,
    ): ParseResult {
        require(raw.isNotBlank()) { "فایل خالی است؛ چیزی برای بک‌تست وجود ندارد." }
        // ── پیمایش یک‌گذره ──────────────────────────────────────────────────────
        // قبلاً ابتدا همهٔ سطرها در یک List جمع می‌شد و بعد دوباره پیمایش می‌شد؛ روی فایل
        // سنگین این کار یک کپی کامل از فایل (به‌صورت String جدا) در heap نگه می‌داشت و
        // دقیقاً همان‌جا برنامه با OutOfMemory می‌مرد. حالا فقط سری کندل‌ها نگه داشته
        // می‌شود و حافظهٔ اضافی، مستقل از حجم فایل، ثابت است.
        // نکتهٔ ظریف: `Sequence` در کاتلین دوباره‌پیمایش‌شدنی است؛ اگر با `firstOrNull()`
        // سرسطر را برداریم و بعد خود Sequence را حلقه کنیم، سرسطر **دوباره** به‌عنوان ردیف
        // داده خوانده می‌شود (یک ردیف ردشده و یک ردیف اضافه در شمارش). پس یک iterator
        // صریح می‌گیریم و تا آخر همان را جلو می‌بریم.
        val rows = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .iterator()
        require(rows.hasNext()) { "فایل باید یک سطر عنوان و حداقل یک سطر داده داشته باشد." }
        val headerLine = rows.next()

        val header = splitCsvLine(headerLine).map { it.lowercase().trim() }
        val columnIndex = header.withIndex().associate { (i, name) -> name to i }
        val kind = when {
            columnIndex.containsKey("open") && columnIndex.containsKey("close") -> SourceKind.CANDLE
            columnIndex.containsKey("askprice") || columnIndex.containsKey("bidprice") -> SourceKind.TICK
            else -> SourceKind.UNKNOWN
        }
        require(kind != SourceKind.UNKNOWN) {
            "ستون‌های فایل با خروجی dukascopy-node نمی‌خواند. سرسطر باید timestamp,open,high,low,close[,volume] " +
                "یا timestamp,askPrice,bidPrice,askVolume,bidVolume باشد."
        }

        val tsCol = columnIndex["timestamp"] ?: columnIndex["time"] ?: 0
        val offsetMs = timezoneOffsetMinutes.toLong() * 60_000L

        var skipped = 0
        var duplicates = 0
        val byTime = LinkedHashMap<Long, Candle>()

        var dataRows = 0
        while (rows.hasNext()) {
            val row = rows.next()
            dataRows++
            val cells = splitCsvLine(row)
            val time = cells.getOrNull(tsCol)?.let { parseTimestamp(it) }
            if (time == null) {
                skipped++
                continue
            }
            val adjustedTime = time - offsetMs
            val candle = when (kind) {
                SourceKind.CANDLE -> parseCandleRow(cells, columnIndex, adjustedTime)
                SourceKind.TICK -> parseTickRow(cells, columnIndex, adjustedTime)
                SourceKind.UNKNOWN -> null
            }
            if (candle == null) {
                skipped++
                continue
            }
            if (byTime.putIfAbsent(adjustedTime, candle) != null) duplicates++
        }

        require(dataRows > 0) { "فایل باید یک سطر عنوان و حداقل یک سطر داده داشته باشد." }
        require(byTime.isNotEmpty()) { "هیچ ردیف معتبری خوانده نشد؛ فایل یا خراب است یا ستون‌ها جابه‌جا شده‌اند." }

        val ordered = byTime.values.sortedBy { it.time }
        // اگر فایل ستون volume ندارد (پیش‌فرض CLI داکاس‌کپی)، «حجم صفر» یعنی «نامعلوم» است،
        // نه «کندل مرده». دور ریختن چنین ردیف‌هایی کل فایل را پاک می‌کرد.
        val hasVolumeColumn = columnIndex.containsKey("volume")
        val canDetectFlats = dropFlatBars && hasVolumeColumn
        val flatCount = if (canDetectFlats) ordered.count { it.volume <= 0.0 } else 0
        val kept = if (canDetectFlats) ordered.filter { it.volume > 0.0 }.ifEmpty { ordered } else ordered

        val step = dominantStepMs(kept)
        val inferred = intervalFromFileName(fileName)
            ?: if (kind == SourceKind.TICK) nearestInterval(step) else nearestInterval(step)

        return ParseResult(
            candles = kept,
            sourceKind = kind,
            totalRows = dataRows,
            skippedRows = skipped,
            duplicateRows = duplicates,
            flatRowsDropped = flatCount,
            inferredInterval = inferred,
            dominantStepMs = step,
            firstTime = kept.first().time,
            lastTime = kept.last().time,
            suggestedSymbol = suggestSymbolFromFileName(fileName),
        )
    }

    private fun parseCandleRow(cells: List<String>, idx: Map<String, Int>, time: Long): Candle? {
        val open = cells.numberAt(idx["open"]) ?: return null
        val high = cells.numberAt(idx["high"]) ?: return null
        val low = cells.numberAt(idx["low"]) ?: return null
        val close = cells.numberAt(idx["close"]) ?: return null
        val volume = cells.numberAt(idx["volume"]) ?: 0.0
        return buildCandle(time, open, high, low, close, volume) ?: return null
    }

    private fun parseTickRow(cells: List<String>, idx: Map<String, Int>, time: Long): Candle? {
        val ask = cells.numberAt(idx["askprice"])
        val bid = cells.numberAt(idx["bidprice"])
        val mid = when {
            ask != null && bid != null && ask > 0.0 && bid > 0.0 -> (ask + bid) / 2.0
            ask != null && ask > 0.0 -> ask
            bid != null && bid > 0.0 -> bid
            else -> return null
        }
        val askVolume = cells.numberAt(idx["askvolume"]) ?: 0.0
        val bidVolume = cells.numberAt(idx["bidvolume"]) ?: 0.0
        return buildCandle(time, mid, mid, mid, mid, askVolume + bidVolume)
    }

    private fun buildCandle(time: Long, open: Double, high: Double, low: Double, close: Double, volume: Double): Candle? {
        if (time <= 0L) return null
        if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) return null
        if (open <= 0.0 || close <= 0.0) return null
        if (high < low) return null
        // قیمت باید داخل بازهٔ کندل باشد؛ فایل خراب یا ستون جابه‌جا همین‌جا گیر می‌افتد.
        if (close > high + 1e-9 || close < low - 1e-9 || open > high + 1e-9 || open < low - 1e-9) return null
        return Candle(
            time = time,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = if (volume.isFinite() && volume >= 0.0) volume else 0.0,
            closed = true,
        )
    }

    /** فاصلهٔ غالب بین کندل‌ها؛ میانه گرفته می‌شود تا تعطیلات آخر هفته عدد را خراب نکند. */
    private fun dominantStepMs(candles: List<Candle>): Long {
        if (candles.size < 3) return 0L
        val steps = candles.zipWithNext { a, b -> b.time - a.time }.filter { it > 0L }.sorted()
        if (steps.isEmpty()) return 0L
        return steps[steps.size / 2]
    }

    /**
     * dukascopy-node زمان را میلی‌ثانیه می‌دهد؛ ولی اگر کاربر فایل را از CSV دیگری گرفته باشد
     * ممکن است ثانیه یا ISO باشد. هر سه پذیرفته می‌شوند تا خطای «فایل من خوانده نشد» پیش نیاید.
     */
    internal fun parseTimestamp(raw: String): Long? {
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return null
        val asNumber = text.toDoubleOrNull()
        if (asNumber != null) {
            if (!asNumber.isFinite() || asNumber <= 0.0) return null
            // ثانیه در برابر میلی‌ثانیه: هر عددی کوچک‌تر از ۱۰^۱۱ ثانیه است.
            return if (asNumber < 100_000_000_000.0) (asNumber * 1000.0).toLong() else asNumber.toLong()
        }
        // ISO-8601 با هر آفستی
        val cleaned = text.replace(' ', 'T')
        return runCatching { java.time.Instant.parse(if (cleaned.endsWith("Z")) cleaned else cleaned + "Z").toEpochMilli() }
            .getOrNull()
    }

    /** CSV ساده؛ نقل‌قول هم پشتیبانی می‌شود تا نام ابزارهای حاوی کاما فایل را نشکند. */
    internal fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun List<String>.numberAt(index: Int?): Double? {
        if (index == null) return null
        val cell = getOrNull(index)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return null
        return cell.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
