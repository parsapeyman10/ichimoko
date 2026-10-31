package com.aurum.edge.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «فرماندار منابع» — بودجهٔ حافظه و پردازش را متناسب با خود دستگاه تعیین می‌کند.
 *
 * **مشکل واقعی:** آرشیو تصویری برای هر معامله دو تصویر WebP نگه می‌دارد و هر تصویر هنگام ساخت یک
 * `Bitmap` چند مگابایتی می‌خواهد. روی گوشی‌های کم‌حافظه (heap 128–192MB) این ترکیب با پردازش هم‌زمان
 * چند تصویر، مستقیماً به `OutOfMemoryError` می‌رسد.
 *
 * **راه‌حل استاندارد:** به‌جای چند عدد ثابتِ کدشده، بودجه را از `Runtime.maxMemory()` بگیرید و هر
 * عملیات سنگین را در چارچوب همان بودجه اجرا کنید (memory-aware budget، همان کاری که
 * Glide/Coil و پلتفرم‌های پردازش تصویر انجام می‌دهند). این کلاس کاملاً خالص است و تست می‌شود:
 *
 *  - سهم حافظهٔ آرشیو: ۶٪ از heap، بین ۱۲ و ۹۶ مگابایت
 *  - سقف رکورد نگه‌داری‌شده در حافظه: بین ۱۲۰ و ۸۰۰
 *  - تعداد رندر هم‌زمان تصویر: ۱ (گوشی‌های ≤ ۱۹۲MB) تا ۲
 *  - اندازهٔ بوم رندر: مقیاس‌شده با حافظه، تا هر تصویر بیش از بودجهٔ یک رندر نشود
 */
object AppResourceGovernor {

    data class Budget(
        /** حافظهٔ کل در دسترس برنامه (بایت) */
        val heapBytes: Long,
        /** بودجهٔ تخمینی فایل‌های تصویری آرشیو روی دیسک/حافظه */
        val archiveBudgetBytes: Long,
        /** حداکثر رکورد آرشیو که در حافظه نگه داشته می‌شود */
        val maxArchiveRecords: Int,
        /** حداکثر رندر هم‌زمان تصویر */
        val maxConcurrentRenders: Int,
        /** ضریب مقیاس اندازهٔ بوم رندر (۱.۰ = اندازهٔ کامل) */
        val renderScale: Float,
        /** حداکثر تعداد معامله‌ای که در یک درخواست آرشیو پردازش می‌شود */
        val archiveBatch: Int,
    ) {
        fun humanArchiveBudgetFa(): String =
            "${archiveBudgetBytes / (1024 * 1024)} مگابایت"
    }

    private const val MB = 1024L * 1024L

    fun budgetFor(heapBytes: Long): Budget {
        val heap = max(heapBytes, 64L * MB)
        val heapMb = heap / MB
        val archiveBudget = (heap * 0.06).toLong().coerceIn(12L * MB, 96L * MB)
        val maxRecords = (heapMb * 2).toInt().coerceIn(120, 800)
        val concurrentRenders = if (heapMb <= 192L) 1 else 2
        val renderScale = when {
            heapMb <= 128L -> 0.62f
            heapMb <= 192L -> 0.78f
            heapMb <= 320L -> 0.9f
            else -> 1.0f
        }
        val batch = when {
            heapMb <= 128L -> 6
            heapMb <= 256L -> 12
            else -> 24
        }
        return Budget(
            heapBytes = heap,
            archiveBudgetBytes = archiveBudget,
            maxArchiveRecords = maxRecords,
            maxConcurrentRenders = concurrentRenders,
            renderScale = renderScale,
            archiveBatch = batch,
        )
    }

    /** بودجهٔ دستگاه فعلی. */
    fun current(): Budget = budgetFor(Runtime.getRuntime().maxMemory())

    /**
     * تصمیم نگه‌داری/حذف برای آرشیو: با توجه به حجم فعلی و بودجه، چند رکورد باید نگه داشته شود.
     * رکوردها از قدیمی به جدید مرتب می‌شوند و قدیمی‌ترها حذف می‌شوند.
     */
    fun recordsToKeep(recordSizesBytes: List<Long>, budget: Budget): Int {
        var used = 0L
        var kept = 0
        for (size in recordSizesBytes.asReversed()) {
            if (used + size > budget.archiveBudgetBytes) break
            if (kept >= budget.maxArchiveRecords) break
            used += size
            kept++
        }
        return kept
    }

    /** چند رکورد را در هر دستهٔ پردازش برداریم تا حافظه بین دسته‌ها آزاد شود. */
    fun chunkSize(budget: Budget): Int = max(4, min(budget.archiveBatch, 32))

    /** درصد استفاده از بودجهٔ آرشیو (برای نمایش هشدار در UI). */
    fun usagePercent(usedBytes: Long, budget: Budget): Int =
        if (budget.archiveBudgetBytes <= 0L) 0
        else ((usedBytes.toDouble() / budget.archiveBudgetBytes) * 100).roundToInt().coerceIn(0, 999)
}
