package com.aurum.edge.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * # نشستِ بک‌تست که با رفتن و برگشتن گم نمی‌شود
 *
 * شکایت کاربر دقیق و درست بود: «من از صفحهٔ بک‌تست می‌روم برمی‌گردم، دوباره همه‌چیز از اول.»
 * علتش این است که ViewModel و فایلِ سنگینِ بارشده هر دو در حافظه‌اند: با پایین‌آمدن حافظه یا
 * بسته‌شدن اپ، اندروید صفحه را می‌کُشد و کندل‌ها/تنظیمات/آخرین نتیجه می‌روند.
 *
 * چیزی که این‌جا نگه داشته می‌شود:
 *  • شناسهٔ فایل داده و نقطهٔ چارت (کندل‌ها از حافظهٔ داخلی دوباره خوانده می‌شوند، نه آپلود مجدد)
 *  • همهٔ کلیدهای اجرا (استراتژی، ریسک، سرمایه، هیدن کراس، هزینهٔ محافظه‌کارانه…)
 *  • خلاصهٔ متنیِ آخرین اجرا؛ فقط متن، چون ذخیرهٔ ۹۶۷ معامله در JSON هم کند است هم بی‌فایده —
 *    آرشیو کامل همان معامله‌ها از قبل در `BacktestArchiveStore` هست.
 *
 * قواعد: هیچ‌چیز ژورنال/حساب/تنظیمات زندهٔ اپ را تغییر نمی‌دهد؛ این فایل فقط برای همین صفحه است.
 * خرابی فایل هم صفحه را نمی‌شکند: `read()` در بدترین حالت `null` می‌دهد.
 */
@Serializable
data class BacktestSession(
    val dataSetId: String = "",
    val cursorIndex: Int = 0,
    val strategyId: String = "",
    val symbol: String = "",
    val runAllStrategies: Boolean = false,
    val startEquityUsd: Double = 1_000.0,
    val riskPercent: Double = 0.5,
    val useSmartExit: Boolean = true,
    val usePreserveMode: Boolean = true,
    val useTimeStop: Boolean = true,
    val optimisticSameBarFill: Boolean = false,
    val maxBars: Int = 0,
    val includeHiddenCrossLayer: Boolean = true,
    val compressionExpansion: Boolean = false,
    val peymanCryptoPaper: Boolean = false,
    /** هزینهٔ محافظه‌کارانه (کارمزد و اسپرد ×۳). */
    val conservativeCosts: Boolean = false,
    /** خلاصهٔ خط‌به‌خط آخرین اجرا: خلاصهٔ کلی + گزارش ماه‌به‌ماه. */
    val lastRunLinesFa: List<String> = emptyList(),
    val lastRunAtMs: Long = 0,
    val savedAtMs: Long = 0,
)

class BacktestSessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(File(appContext.filesDir, "backtest_session"), "session.json")

    fun read(): BacktestSession? = runCatching {
        if (!file.isFile) null else json.decodeFromString(BacktestSession.serializer(), file.readText())
    }.getOrNull()

    fun save(session: BacktestSession) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                json.encodeToString(
                    BacktestSession.serializer(),
                    session.copy(savedAtMs = System.currentTimeMillis()),
                ),
            )
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun storagePathFa(): String = file.parentFile?.absolutePath.orEmpty()
}
