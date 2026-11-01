package com.aurum.edge.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * نگهبان رشتهٔ اصلی (Main-Thread Watchdog).
 *
 * **چرا لازم است:** گزارش «یا ببند یا صبر کن» (ANR) هیچ stack‌تریسی به ما نمی‌دهد؛ فقط می‌گوید
 * رشتهٔ اصلی بیش از ۵ ثانیه پاسخ نداده است. تشخیص اینکه *کدام* کار رشتهٔ اصلی را قفل کرده،
 * بدون شاهد غیرممکن است.
 *
 * **روش:** یک «ضربان» هر ۵۰۰ میلی‌ثانیه روی رشتهٔ اصلی اجرا می‌شود و زمان آخرین پاسخ را
 * ثبت می‌کند. یک رشتهٔ پس‌زمینه سبک (هر ۵۰۰ میلی‌ثانیه) تأخیر را می‌سنجد؛ اگر تأخیر از
 * [STALL_THRESHOLD_MS] بیشتر شد، **stack کامل رشتهٔ اصلی** را در `crash_logs/` می‌نویسد.
 *
 * هزینه: هر نیم‌ثانیه یک `post` ساده؛ بار محسوسی روی UI ندارد. برای هر توقف فقط یک گزارش
 * (حداکثر هر [MIN_REPORT_INTERVAL_MS]) نوشته می‌شود تا فایل‌ها پر نشوند.
 */
object MainThreadWatchdog {

    /** آستانهٔ «قفل شدن» رشتهٔ اصلی. */
    private const val STALL_THRESHOLD_MS = 2_500L

    /** فاصلهٔ حداقلی بین دو گزارش توقف. */
    private const val MIN_REPORT_INTERVAL_MS = 20_000L

    @Volatile
    private var installed = false

    @Volatile
    private var lastHeartbeat = 0L

    @Volatile
    private var lastReportAt = 0L

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val heartbeat = object : Runnable {
            override fun run() {
                lastHeartbeat = SystemClock.uptimeMillis()
                handler.postDelayed(this, 500L)
            }
        }
        lastHeartbeat = SystemClock.uptimeMillis()
        handler.post(heartbeat)

        Thread({
            while (true) {
                try {
                    Thread.sleep(500L)
                    val blockedFor = SystemClock.uptimeMillis() - lastHeartbeat
                    if (blockedFor < STALL_THRESHOLD_MS) continue
                    val now = SystemClock.uptimeMillis()
                    if (now - lastReportAt < MIN_REPORT_INTERVAL_MS) continue
                    lastReportAt = now
                    val main = Looper.getMainLooper().thread
                    val stack = main.stackTrace
                    val text = buildString {
                        appendLine("رشتهٔ اصلی «${main.name}» بیش از ${blockedFor / 1000} ثانیه پاسخ نداده است.")
                        appendLine("وضعیت: ${main.state}")
                        appendLine("── stack رشتهٔ اصلی (لحظهٔ قفل) ──")
                        stack.forEach { appendLine("    at $it") }
                    }
                    CrashReporter.recordMainThreadStall(app, blockedFor, text)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // هرگز نباید خودِ نگهبان باعث خطا شود.
                }
            }
        }, "aurum-main-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }
}
