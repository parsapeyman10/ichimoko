package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AiTraderEngine
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * # حافظهٔ معامله‌گر هوشمند — همیشه روی گوشی
 *
 * کاربر صریحاً خواست: **«خلاصهٔ آموزش‌ها و برداشت شبکه همیشه باید سیو شود در گوشی»**.
 * شبکهٔ عصبی از قبل `AurumNeuralBrain_Backup.json` را می‌نوشت، ولی دو چیز جا می‌ماند:
 *
 * ۱. **اقدام‌های خودترمیمی** ([AiTraderEngine.SelfHealingAction]) — اگر ذخیره نشوند، هر بار
 *    برنامه باز شود سیستم دوباره از صفر همان اصلاح‌ها را کشف می‌کند و «یاد نمی‌گیرد».
 * ۲. **خلاصهٔ درس‌های معامله‌گر** — متن خوانا از آنچه شبکه و موتور به آن رسیده‌اند.
 *
 * این فایل هر دو را در `filesDir/ai_trader/trader_memory.json` نگه می‌دارد؛ **خصوصی اپ**،
 * بدون مجوز، بدون شبکه، و کوچک (چند کیلوبایت). نوشتن اتمی است (فایل موقت + rename) تا قطع
 * برق وسط کار، حافظه را خراب نکند.
 */
@Serializable
data class TraderMemorySnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val updatedAtMs: Long = 0L,
    /** همهٔ اصلاح‌هایی که سیستم خودش اعمال کرده است (تاریخچهٔ کامل، قدیمی‌ترین اول). */
    val appliedActions: List<HealingRecord> = emptyList(),
    /** خلاصهٔ خوانای درس‌ها؛ هر خط یک برداشت. */
    val lessonLinesFa: List<String> = emptyList(),
    /** امضای اقدام‌های فعال تا از اعمال دوبارهٔ همان اصلاح جلوگیری شود. */
    val activeSignatures: List<String> = emptyList(),
    val totalHealCount: Int = 0,
    val lastSummaryFa: String = "",
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** نسخهٔ قابل‌سریال‌سازی از [AiTraderEngine.SelfHealingAction] برای ذخیره روی دیسک. */
@Serializable
data class HealingRecord(
    val id: String,
    val kindCode: String,
    val strategyId: String,
    val targetFa: String,
    val conditionIndex: Int,
    val conditionTitleFa: String,
    val problemFa: String,
    val reasonFa: String,
    val samples: Int,
    val previousValue: Double,
    val newValue: Double,
    val createdAtMs: Long,
) {
    fun toAction(): AiTraderEngine.SelfHealingAction? {
        val kind = runCatching { AiTraderEngine.HealingKind.valueOf(kindCode) }.getOrNull() ?: return null
        return AiTraderEngine.SelfHealingAction(
            id = id,
            kind = kind,
            strategyId = strategyId,
            targetFa = targetFa,
            conditionIndex = conditionIndex,
            conditionTitleFa = conditionTitleFa,
            problemFa = problemFa,
            reasonFa = reasonFa,
            samples = samples,
            previousValue = previousValue,
            newValue = newValue,
            createdAtMs = createdAtMs,
        )
    }

    companion object {
        fun from(action: AiTraderEngine.SelfHealingAction): HealingRecord = HealingRecord(
            id = action.id,
            kindCode = action.kind.name,
            strategyId = action.strategyId,
            targetFa = action.targetFa,
            conditionIndex = action.conditionIndex,
            conditionTitleFa = action.conditionTitleFa,
            problemFa = action.problemFa,
            reasonFa = action.reasonFa,
            samples = action.samples,
            previousValue = action.previousValue,
            newValue = action.newValue,
            createdAtMs = action.createdAtMs,
        )
    }
}

object TraderMemoryStore {

    private const val FILE_NAME = "trader_memory.json"
    private const val DIR_NAME = "ai_trader"
    private const val MAX_RECORDS = 200

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Volatile
    private var cached: TraderMemorySnapshot? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        cached = null
    }

    private fun file(context: Context?): File? {
        val ctx = context ?: appContext ?: return null
        val dir = File(ctx.filesDir, DIR_NAME)
        return File(dir, FILE_NAME)
    }

    fun read(context: Context? = null): TraderMemorySnapshot {
        cached?.let { return it }
        val target = file(context) ?: return TraderMemorySnapshot()
        val loaded = runCatching {
            if (!target.exists()) TraderMemorySnapshot() else json.decodeFromString<TraderMemorySnapshot>(target.readText())
        }.getOrElse { TraderMemorySnapshot() }
        cached = loaded
        return loaded
    }

    /**
     * نوشتن اتمی.
     *
     * «اتمی» یعنی اول در فایل موقت نوشته می‌شود و بعد rename می‌شود؛ اگر اپ وسط نوشتن بسته شود،
     * فایل قبلی سالم می‌ماند و حافظهٔ یادگیری هیچ‌وقت نیمه‌کارهٔ خراب نمی‌شود.
     */
    fun write(context: Context? = null, mutate: (TraderMemorySnapshot) -> TraderMemorySnapshot): TraderMemorySnapshot {
        val current = read(context)
        // فقط یک‌بار mutate؛ فراخوانی دوباره باعث می‌شد فهرست اقدام‌ها از نسخهٔ دیگری خوانده شود.
        val mutated = mutate(current)
        val next = mutated.copy(
            updatedAtMs = System.currentTimeMillis(),
            appliedActions = mutated.appliedActions.takeLast(MAX_RECORDS),
        )
        cached = next
        val target = file(context)
        if (target != null) {
            runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeText(json.encodeToString(next))
                if (target.exists() && !target.delete()) {
                    // اگر حذف ممکن نشد، نوشتن مستقیم به‌عنوان آخرین راه
                    target.writeText(json.encodeToString(next))
                } else if (!temp.renameTo(target)) {
                    target.writeText(json.encodeToString(next))
                    temp.delete()
                }
            }
        }
        return next
    }

    /**
     * ثبت اقدام‌های تازه. تکراری‌ها (همان امضا) دوباره ثبت نمی‌شوند تا حافظه با تکرار پر نشود.
     *
     * امضا = نوع + استراتژی + مقدار جدید؛ اگر همان اصلاح دوباره پیشنهاد شود، یعنی از قبل
     * اعمال شده و نباید دوباره اعمال شود.
     */
    fun recordActions(
        actions: List<AiTraderEngine.SelfHealingAction>,
        context: Context? = null,
    ): TraderMemorySnapshot = write(context) { snap ->
        val known = snap.appliedActions.map { it.id + "@" + it.newValue }.toSet()
        val fresh = actions.filter { (it.id + "@" + it.newValue) !in known }
        snap.copy(
            appliedActions = snap.appliedActions + fresh.map(HealingRecord::from),
            activeSignatures = (snap.activeSignatures + fresh.map { it.kind.name }).distinct(),
            totalHealCount = snap.totalHealCount + fresh.size,
        )
    }

    /** ذخیرهٔ خلاصهٔ درس‌ها (متن خوانا). همیشه آخرین نسخه نگه داشته می‌شود. */
    fun recordLessons(linesFa: List<String>, summaryFa: String, context: Context? = null): TraderMemorySnapshot =
        write(context) { snap ->
            snap.copy(
                lessonLinesFa = linesFa.take(60),
                lastSummaryFa = summaryFa,
            )
        }

    /** آیا این نوع اصلاح قبلاً اعمال شده است؟ (برای جلوگیری از حلقهٔ اصلاح تکراری) */
    fun wasAlreadyHealed(kind: AiTraderEngine.HealingKind): Boolean =
        read().activeSignatures.contains(kind.name)

    /** پاک‌کردن حافظه (فقط با تصمیم صریح کاربر). */
    fun clear(context: Context? = null): TraderMemorySnapshot {
        cached = TraderMemorySnapshot()
        runCatching { file(context)?.delete() }
        return cached!!
    }

    /** مسیر فایل برای نمایش در UI (تشخیص اینکه حافظه کجا ذخیره می‌شود). */
    fun storagePathFa(context: Context? = null): String =
        file(context)?.absolutePath ?: "حافظهٔ داخلی اپ"
}
