package com.aurum.edge.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * # کارهای بک‌تستِ پس‌زمینه
 *
 * چرا لازم بود؟ تا پیش از این، بک‌تست فقط تا وقتی اجرا می‌شد که صفحهٔ بک‌تست باز بود و
 * کاربر همان‌جا نشسته بود. اگر از اپ بیرون می‌آمدی، کار متوقف می‌شد و نتیجه هم گم
 * می‌شد. حالا «کار» تعریف می‌شود، به `WorkManager` سپرده می‌شود، و **نتیجه‌اش روی
 * حافظهٔ داخلی می‌مانَد** تا هر وقت برگشتی ببینی، تغییرش بدهی و دوباره اجرا کنی.
 *
 * قواعد این ذخیره‌ساز:
 * 1. هیچ نتیجه‌ای بی‌صدا پاک نمی‌شود؛ کاربر خودش حذف می‌کند.
 * 2. تنظیماتِ هر کار **کامل** نگه داشته می‌شود تا «تغییر دادن و اجرای دوباره» دقیقاً
 *    همان اجرا را با یک پارامتر عوض‌شده تکرار کند.
 * 3. این ذخیره‌ساز به ژورنال/حس واقعی/تنظیمات زندهٔ اپ دست نمی‌زند.
 */
@Serializable
data class BacktestJobSpec(
    val id: String,
    val dataSetId: String,
    val dataSetName: String,
    val createdAt: Long,
    val strategyId: String,
    val symbol: String,
    val runAllStrategies: Boolean,
    val startEquityUsd: Double,
    val riskPercent: Double,
    val useSmartExit: Boolean,
    val usePreserveMode: Boolean,
    val useTimeStop: Boolean,
    val optimisticSameBarFill: Boolean,
    val maxBars: Int,
    val includeHiddenCrossLayer: Boolean,
    val compressionExpansion: Boolean,
    /** تا کدام کندل اجرا شود (۰ یا بیشتر از طول داده = همهٔ کندل‌ها). */
    val cursorIndex: Int,
    /**
     * هزینهٔ محافظه‌کارانه: کارمزد و اسپرد × ۳.
     *
     * چرا؟ میانهٔ اسپرد خودِ فایل XAU ۱۵دقیقه ۰٫۶۱۷ دلار است، در حالی که موتور محافظه‌کارانه‌ترین
     * حالت را با اسپرد کمتر حساب می‌کرد. با ×۳ همان استراتژی PF ۱٫۷۴ و +۰٫۳۳۰R می‌دهد
     * (به‌جای ۱٫۸۶ و +۰٫۳۸۰R) — یعنی سود از تخمینِ خوش‌بینانهٔ هزینه نیامده است.
     */
    val conservativeCosts: Boolean = true,
    /** یادداشت آزاد کاربر؛ در فهرست کارها نشان داده می‌شود. */
    val noteFa: String = "",
)

/** خلاصهٔ نتیجهٔ یک کار — همان چیزی که کاربر برای تصمیم‌گیری لازم دارد. */
@Serializable
data class BacktestJobResult(
    val finishedAt: Long,
    val trades: Int,
    val winRatePct: Double,
    val netPnlUsd: Double,
    val profitFactor: Double,
    val expectancyR: Double,
    val candlesUsed: Int,
    val elapsedMs: Long,
    val strategyLabel: String,
    val archiveCsvPath: String,
    val archiveJsonPath: String,
    val summaryFa: String,
)

@Serializable
data class BacktestJob(
    val spec: BacktestJobSpec,
    /** QUEUED | RUNNING | DONE | FAILED | CANCELLED */
    val status: String = STATUS_QUEUED,
    val messageFa: String = "",
    val result: BacktestJobResult? = null,
    val updatedAt: Long = spec.createdAt,
) {
    val isFinished: Boolean get() = status == STATUS_DONE || status == STATUS_FAILED || status == STATUS_CANCELLED

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

@Serializable
private data class JobFile(val jobs: List<BacktestJob> = emptyList())

class BacktestJobStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val file = File(File(appContext.filesDir, "backtest_jobs"), "jobs.json")

    fun list(): List<BacktestJob> = read().jobs.sortedByDescending { it.spec.createdAt }

    fun find(id: String): BacktestJob? = read().jobs.firstOrNull { it.spec.id == id }

    fun upsert(job: BacktestJob) {
        val current = read().jobs.toMutableList()
        val at = current.indexOfFirst { it.spec.id == job.spec.id }
        if (at >= 0) current[at] = job else current.add(job)
        write(JobFile(current))
    }

    fun update(id: String, block: (BacktestJob) -> BacktestJob) {
        val job = find(id) ?: return
        upsert(block(job).copy(updatedAt = System.currentTimeMillis()))
    }

    fun remove(id: String) {
        val current = read().jobs.filterNot { it.spec.id == id }
        write(JobFile(current))
    }

    /** فقط کارهای تمام‌شده را پاک می‌کند؛ کارِ در صف یا در حال اجرا دست‌نخورده می‌مانَد. */
    fun clearFinished() {
        write(JobFile(read().jobs.filterNot { it.isFinished }))
    }

    fun storagePathFa(): String = file.parentFile?.absolutePath.orEmpty()

    private fun read(): JobFile = runCatching {
        if (!file.isFile) JobFile() else json.decodeFromString(JobFile.serializer(), file.readText())
    }.getOrElse { JobFile() }

    private fun write(value: JobFile) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(JobFile.serializer(), value))
        }
    }
}
