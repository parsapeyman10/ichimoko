package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.BacktestTradeArchive
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * دفتر «اعمال سود بک‌تست» — قفل تکرار.
 *
 * ── قاعده، به زبان ساده ─────────────────────────────────────────────────
 * هر آرشیو بک‌تست دقیقاً **یک‌بار** می‌تواند سود/زیانش روی سرمایهٔ برنامه اعمال شود. کلید این
 * قفل، شناسهٔ محتوایی آرشیو است (هش خودِ معامله‌ها) نه یک شمارهٔ تصادفی؛ پس:
 *
 * - همان بک‌تست را دوباره اجرا کنید و دکمه را بزنید → شناسه همان است → **اعمال نمی‌شود** و
 *   پیام «قبلاً اعمال شده» می‌آید.
 * - CSV دیگری بار کنید یا تنظیم ریسک/بازه را عوض کنید → شناسهٔ تازه → اعمال مجاز است.
 *
 * این فایل هیچ‌جا `JournalStore` را نمی‌نویسد و هیچ معاملهٔ کاغذی نمی‌سازد؛ فقط موجودی حساب
 * (تنها یک عدد در `SettingsStore`) را جابه‌جا می‌کند. آرشیوها در `filesDir/backtest/archive/`
 * می‌مانند تا هر زمان قابل مشاهده/خروجی‌گرفتن باشند.
 */
class BacktestArchiveStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val root: File get() = File(context.filesDir, "backtest/archive").apply { mkdirs() }
    private val ledgerFile: File get() = File(root, "ledger.json")

    /** یک سطر دفتر: کی، چه آرشیوی، چقدر، و از کدام حساب به کدام. */
    @Serializable
    data class AppliedEntry(
        val contentHash: String,
        val fileNameBase: String,
        val symbol: String,
        val intervalLabel: String,
        val trades: Int,
        val netUsd: Double,
        val balanceBefore: Double,
        val balanceAfter: Double,
        val appliedAt: Long,
        val note: String = "",
    )

    @Serializable
    private data class Ledger(val entries: List<AppliedEntry> = emptyList())

    fun readLedger(): List<AppliedEntry> = runCatching {
        if (!ledgerFile.isFile) emptyList()
        else json.decodeFromString(Ledger.serializer(), ledgerFile.readText()).entries
    }.getOrDefault(emptyList())

    /** آیا این آرشیو قبلاً اعمال شده است؟ (کلید = شناسهٔ محتوایی) */
    fun findApplied(contentHash: String): AppliedEntry? =
        readLedger().firstOrNull { it.contentHash == contentHash }

    /**
     * ثبت اعمال. `null` یعنی قبلاً ثبت شده و چیزی نوشته نشد.
     * نوشتن اتمی: فایل موقت + rename تا قطع برق دفتر را نصفه نگذارد.
     */
    @Synchronized
    fun recordApplied(entry: AppliedEntry): AppliedEntry? {
        val current = readLedger()
        if (current.any { it.contentHash == entry.contentHash }) return null
        val next = current + entry
        root.mkdirs()
        val tmp = File(ledgerFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Ledger.serializer(), Ledger(next)))
        if (ledgerFile.exists()) ledgerFile.copyTo(File(ledgerFile.path + ".bak"), overwrite = true)
        if (!tmp.renameTo(ledgerFile)) {
            ledgerFile.writeText(json.encodeToString(Ledger.serializer(), Ledger(next)))
            tmp.delete()
        }
        return entry
    }

    /** مسیر پایهٔ فایل‌های یک آرشیو (بدون پسوند). */
    fun filesFor(archive: BacktestTradeArchive.Archive): ArchiveFiles {
        return ArchiveFiles(
            csv = File(root, archive.suggestedFileNameBase + ".csv"),
            json = File(root, archive.suggestedFileNameBase + ".json"),
        )
    }

    /**
     * نوشتن آرشیو روی حافظهٔ داخلی. اگر همان شناسه قبلاً نوشته شده باشد بازنویسی می‌شود
     * (محتوای یکسان است، پس بی‌خطر) و مسیرها برگردانده می‌شوند.
     */
    fun save(archive: BacktestTradeArchive.Archive, built: BacktestTradeArchive.Built): ArchiveFiles {
        root.mkdirs()
        val files = filesFor(archive)
        runCatching {
            files.csv.writeText(built.csv, Charsets.UTF_8)
            files.json.writeText(built.json, Charsets.UTF_8)
        }
        return files
    }

    /** فهرست آرشیوهای ذخیره‌شده روی حافظه، تازه‌ترین اول (برای نمایش/اشتراک‌گذاری). */
    fun listArchives(): List<ArchiveFiles> = runCatching {
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != "ledger.json" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { j ->
                val csv = File(j.path.removeSuffix(".json") + ".csv")
                if (csv.isFile) ArchiveFiles(csv = csv, json = j) else null
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun deleteArchive(files: ArchiveFiles) {
        runCatching { files.csv.delete() }
        runCatching { files.json.delete() }
    }

    fun storagePathFa(): String = root.path

    data class ArchiveFiles(val csv: File, val json: File) {
        val nameBase: String get() = csv.name.removeSuffix(".csv")
    }
}
