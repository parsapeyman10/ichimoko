package com.aurum.edge.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ثبت و نگه‌داری گزارش کرش‌ها داخل خود اپ.
 *
 * **چرا لازم است:** وقتی برنامه «بسته می‌شود»، کاربر فقط می‌بیند اپ غیب شد و هیچ راهی برای رساندن
 * متن خطا به تیم توسعه ندارد. این شیء هندلر پیش‌فرض `UncaughtExceptionHandler` را می‌گیرد،
 * آخرین تب/صفحه (breadcrumb) و اطلاعات دستگاه را کنار stack trace می‌نویسد و بعد کنترل را به
 * هندلر قبلی (سیستم/Play) برمی‌گرداند؛ پس رفتار عادی اندروید عوض نمی‌شود.
 *
 * هر گزارش در `filesDir/crash_logs/crash_<timestamp>.txt` ذخیره می‌شود و حداکثر [MAX_FILES]
 * فایل نگه داشته می‌شود تا حافظه بی‌نهایت رشد نکند.
 */
object CrashReporter {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_FILES = 8

    @Volatile
    private var lastBreadcrumb: String = "start"

    @Volatile
    private var installed = false

    /** یک رکورد کرش برای نمایش در رابط کاربری. */
    data class CrashEntry(
        val fileName: String,
        val atMs: Long,
        val headlineFa: String,
        val detailFa: String,
    )

    /** نصب هندلر. هر بار فقط یک‌بار اثر می‌گذارد (idempotent). */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { captureFatal(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        runCatching { breadcrumb("app:onCreate") }
    }

    /** آخرین صفحه/تبی که کاربر دیده است؛ در متن کرش نوشته می‌شود. */
    fun breadcrumb(label: String) {
        lastBreadcrumb = label
    }

    /** ثبت خطای غیرکشنده (مثلاً خطای بازخوانی آرشیو) تا بعداً قابل بررسی باشد. */
    fun recordNonFatal(context: Context, where: String, throwable: Throwable) {
        runCatching {
            write(
                context = context.applicationContext,
                kind = "NON_FATAL",
                where = where,
                throwable = throwable,
            )
        }
    }

    /** فهرست گزارش‌ها از جدید به قدیم. */
    fun list(context: Context): List<CrashEntry> = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { toEntry(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun latest(context: Context): CrashEntry? = list(context).firstOrNull()

    fun readFull(context: Context, entry: CrashEntry): String = runCatching {
        File(File(context.filesDir, DIR_NAME), entry.fileName).readText(Charsets.UTF_8)
    }.getOrDefault("")

    /** پاک کردن همهٔ گزارش‌ها؛ تعداد فایل حذف‌شده را برمی‌گرداند. */
    fun clear(context: Context): Int = runCatching {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        files.forEach { it.delete() }
        files.size
    }.getOrDefault(0)

    // ───────────────────────────── داخل شیء ─────────────────────────────

    private fun captureFatal(context: Context, thread: Thread, throwable: Throwable) {
        write(
            context = context.applicationContext,
            kind = "FATAL",
            where = "thread=${thread.name}",
            throwable = throwable,
        )
    }

    private fun write(context: Context, kind: String, where: String, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        prune(dir)
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val body = buildString {
            appendLine("Aurum Edge — گزارش $kind")
            appendLine("زمان: $stamp")
            appendLine("نسخه: ${com.aurum.edge.BuildConfig.VERSION_NAME} (کد ${com.aurum.edge.BuildConfig.VERSION_CODE})")
            appendLine("آخرین صفحه: $lastBreadcrumb")
            appendLine("محل: $where")
            appendLine("دستگاه: ${Build.MANUFACTURER} ${Build.MODEL} · اندروید ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("حافظهٔ مجاز برنامه: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} مگابایت")
            appendLine("──────────────")
            appendLine(throwable.javaClass.name + ": " + (throwable.message ?: ""))
            appendLine("──────────────")
            appendLine(throwable.stackTraceToString())
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 4) {
                appendLine("── cause ${depth + 1} ──")
                appendLine(cause.javaClass.name + ": " + (cause.message ?: ""))
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }
        val target = File(dir, "crash_$fileStamp.txt")
        runCatching { target.writeText(body, Charsets.UTF_8) }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.size < MAX_FILES) return
        files.drop(MAX_FILES - 1).forEach { it.delete() }
    }

    private fun toEntry(file: File): CrashEntry? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val lines = text.lineSequence().toList()
        val kind = lines.firstOrNull()?.removePrefix("Aurum Edge — گزارش ")?.trim().orEmpty()
        val whenLine = lines.firstOrNull { it.startsWith("زمان: ") }.orEmpty()
        val screenLine = lines.firstOrNull { it.startsWith("آخرین صفحه: ") }.orEmpty()
        val errorLine = lines.firstOrNull { Regex("""^[\w.$]+(Error|Exception):""").containsMatchIn(it) }.orEmpty()
        val deviceLine = lines.firstOrNull { it.startsWith("دستگاه: ") }.orEmpty()
        val headline = buildString {
            append(if (kind == "FATAL") "💥 کرش" else "⚠️ خطای ثبت‌شده")
            append(" · ")
            append(whenLine.removePrefix("زمان: ").ifBlank { "—" })
            append(" · ")
            append(screenLine.removePrefix("آخرین صفحه: ").ifBlank { "نامشخص" })
        }
        val detail = listOf(errorLine, deviceLine).filter { it.isNotBlank() }.joinToString("\n")
        return CrashEntry(
            fileName = file.name,
            atMs = file.lastModified(),
            headlineFa = headline,
            detailFa = detail.ifBlank { text.take(280) },
        )
    }
}
