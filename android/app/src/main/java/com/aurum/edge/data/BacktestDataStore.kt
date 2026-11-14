package com.aurum.edge.data

import android.content.Context
import android.net.Uri
import com.aurum.edge.core.BacktestCsvLimits
import com.aurum.edge.core.BacktestCsvReader
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * انبار دادهٔ بک‌تست.
 *
 * **مرز مهم:** این فایل هیچ‌جا به `JournalStore`، حساب کاغذی، پشتیبان‌ها، آرشیو یا
 * `SharedPreferences` تنظیمات زندهٔ برنامه دست نمی‌زند. همه‌چیز داخل
 * `filesDir/backtest/` است: یک پوشهٔ جدا که پاک‌کردنش اثری روی برنامه ندارد و
 * پاک‌کردن ژورنال هم اثری روی آن ندارد.
 *
 * هر فایل CSV یک «مجموعهٔ داده» با شناسهٔ فایل است؛ نام، نماد، تایم‌فریم و بازهٔ زمانی
 * یک‌بار خوانده و در کنار خود CSV در `index.json` نگه داشته می‌شود تا هر بار بازکردن
 * صفحه دوباره کل فایل تجزیه نشود.
 */
class BacktestDataStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val root: File get() = File(context.filesDir, "backtest").apply { mkdirs() }
    private val indexFile: File get() = File(root, "index.json")
    private val csvDir: File get() = File(root, "csv").apply { mkdirs() }

    @Serializable
    data class DataSet(
        /** شناسهٔ پایدار = نام فایل ذخیره‌شده. */
        val id: String,
        val displayName: String,
        val symbol: String,
        val intervalLabel: String,
        val candleCount: Int,
        val firstTime: Long,
        val lastTime: Long,
        val importedAt: Long,
        val sourceKind: String,
        val bytes: Long,
        val skippedRows: Int,
        val flatRowsDropped: Int,
        /** مسیر اصلی فایل روی گوشی؛ فقط برای نمایش. */
        val originLabel: String = "",
    ) {
        val interval: Interval get() = Interval.fromLabel(intervalLabel)
        val spanDays: Double get() = if (lastTime > firstTime) (lastTime - firstTime) / 86_400_000.0 else 0.0
    }

    @Serializable
    private data class Index(val sets: List<DataSet> = emptyList())

    /** فهرست مجموعه‌های داده؛ تازه‌ترین اول. */
    fun list(): List<DataSet> {
        val idx = readIndex()
        // هر مجموعه‌ای که فایل CSV‌اش دستی پاک شده، از فهرست هم می‌رود.
        val alive = idx.sets.filter { csvFile(it.id).isFile }
        if (alive.size != idx.sets.size) writeIndex(Index(alive))
        return alive.sortedByDescending { it.importedAt }
    }

    /**
     * وارد کردن یک فایل CSV انتخاب‌شده با «انتخاب فایل» اندروید.
     *
     * @param symbolOverride نمادی که کاربر انتخاب کرده؛ اگر خالی باشد از نام فایل حدس زده می‌شود.
     * @param dropFlatBars دور ریختن کندل‌های تخت (پیش‌فرض موتور dukascopy).
     * @throws IllegalArgumentException اگر فایل با قالب dukascopy-node نخواند.
     */
    fun import(
        uri: Uri,
        symbolOverride: String = "",
        dropFlatBars: Boolean = true,
    ): DataSet {
        val resolver = context.contentResolver
        val displayName = resolver.displayNameOf(uri) ?: "dukascopy.csv"
        // ── بررسی حجم پیش از خواندن ─────────────────────────────────────────────
        // قبلاً اول کل فایل خوانده و String می‌شد و بعد حجمش سنجیده می‌شد؛ یعنی برای رد
        // کردن یک فایل ۲۰۰ مگابایتی، همان ۲۰۰ مگابایت (و دو برابر با تبدیل به String) در
        // حافظه سوار می‌شد و برنامه پیش از رسیدن به پیام خطا با OutOfMemory می‌مرد.
        // حالا اندازه پیش از خواندن پرسیده می‌شود؛ فایل بزرگ هرگز وارد حافظه نمی‌شود.
        val declaredBytes = byteSizeOf(uri)
        if (declaredBytes > MAX_CSV_BYTES) {
            throw IllegalArgumentException(
                heavyFileMessage(declaredBytes),
            )
        }
        // سقف دوم: فایل‌هایی که اندازه‌شان اعلام نمی‌شود (برخی providerها صفر برمی‌گردانند)
        // باید حین خواندن هم مهار شوند تا یک فایل بی‌نهایت باعث OOM نشود.
        val raw = resolver.openInputStream(uri)?.use { stream ->
            val bytes = readCapped(stream, MAX_CSV_BYTES)
                ?: throw IllegalArgumentException(
                    BacktestCsvLimits.oversizedWhileReadingMessage(),
                )
            bytes.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("فایل باز نشد؛ دسترسی به این مسیر ممکن نیست.")
        val parsed = BacktestCsvReader.parse(raw, fileName = displayName, dropFlatBars = dropFlatBars)
        val symbol = symbolOverride.trim().uppercase().ifBlank {
            parsed.suggestedSymbol.ifBlank { "XAU/USD" }
        }
        val id = safeId(displayName)
        val target = csvFile(id)
        target.writeText(raw, Charsets.UTF_8)
        val set = DataSet(
            id = id,
            displayName = displayName,
            symbol = symbol,
            intervalLabel = parsed.inferredInterval.label,
            candleCount = parsed.candles.size,
            firstTime = parsed.firstTime,
            lastTime = parsed.lastTime,
            importedAt = System.currentTimeMillis(),
            sourceKind = parsed.sourceKind.name,
            bytes = target.length(),
            skippedRows = parsed.skippedRows,
            flatRowsDropped = parsed.flatRowsDropped,
            originLabel = displayName,
        )
        val idx = readIndex()
        writeIndex(Index(listOf(set) + idx.sets.filterNot { it.id == set.id }))
        return set
    }

    /** کندل‌های یک مجموعه با فیلتر بازهٔ زمانی اختیاری (برای چارت و اجرای بک‌تست). */
    fun loadCandles(dataSetId: String, fromTime: Long? = null, toTime: Long? = null): List<Candle> {
        val file = csvFile(dataSetId)
        require(file.isFile) { "فایل این مجموعه پیدا نشد؛ دوباره واردش کنید." }
        val set = list().firstOrNull { it.id == dataSetId }
        val parsed = BacktestCsvReader.parse(
            raw = file.readText(Charsets.UTF_8),
            fileName = set?.displayName ?: dataSetId,
        )
        val from = fromTime ?: return if (toTime == null) parsed.candles else parsed.candles.filter { it.time <= toTime }
        return parsed.candles.filter { it.time in from..(toTime ?: Long.MAX_VALUE) }
    }

    fun delete(dataSetId: String) {
        csvFile(dataSetId).delete()
        writeIndex(Index(readIndex().sets.filterNot { it.id == dataSetId }))
    }

    fun clearAll() {
        csvDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
    }

    fun storagePathFa(): String = root.absolutePath

    private fun csvFile(id: String): File = File(csvDir, id)

    private fun readIndex(): Index = runCatching {
        if (indexFile.isFile) json.decodeFromString(Index.serializer(), indexFile.readText()) else Index()
    }.getOrDefault(Index())

    private fun writeIndex(index: Index) {
        runCatching {
            root.mkdirs()
            val tmp = File(root, "index.json.tmp")
            tmp.writeText(json.encodeToString(Index.serializer(), index))
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    private fun safeId(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
        val normalized = cleaned.takeIf { it.isNotBlank() } ?: "dukascopy.csv"
        // اگر همین نام قبلاً هست، نسخهٔ جدید جایگزینش می‌شود (رفتار «وارد کردن دوباره»).
        return normalized
    }

    private fun android.content.ContentResolver.displayNameOf(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        return runCatching {
            query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    /** اندازهٔ اعلام‌شدهٔ فایل به بایت؛ ۰ یعنی provider نمی‌گوید. */
    private fun byteSizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    /**
     * خواندن با سقف بایت. اگر از سقف بگذرد `null` برمی‌گرداند — به‌جای این‌که اول همه را
     * بخواند و بعد بگوید زیاد است.
     */
    private fun readCapped(stream: java.io.InputStream, maxBytes: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(chunk)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** پیام صریح برای فایل سنگین — متن در `BacktestCsvLimits` است تا آزمون‌پذیر بماند. */
    fun heavyFileMessage(bytes: Long): String = BacktestCsvLimits.heavyFileMessage(bytes)

    companion object {
        /** سقف حجم متن؛ منبع حقیقت `BacktestCsvLimits` است. */
        const val MAX_CSV_CHARS: Int = BacktestCsvLimits.MAX_CSV_CHARS

        /** سقف بایت هنگام ورود فایل؛ منبع حقیقت `BacktestCsvLimits` است. */
        const val MAX_CSV_BYTES: Long = BacktestCsvLimits.MAX_CSV_BYTES

        private const val INITIAL_BUFFER_BYTES = 1 shl 16
    }
}
