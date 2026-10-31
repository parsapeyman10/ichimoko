package com.aurum.edge.core

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * دفتر یادگیری (Learning Ledger) و سیاست گیت شبکهٔ عصبی خودآموز.
 *
 * این فایل کاملاً مستقل از اندروید است تا بتوان منطق «یادگیری دقیقاً یک‌بار» (exactly-once)
 * و «انتقال دانش بین نسخه‌ها» را به‌صورت واحد تست کرد.
 *
 * سه مسئله‌ای که اینجا حل می‌شود:
 * ۱. **واگرایی عدد یادگیری با تعداد معامله‌های واقعی**: شمارندهٔ `totalLearnedTrades` قبلاً فقط
 *    یک عدد افزایشی بود و تکرارِ یادگیری یک معامله (چون حافظهٔ تکرار فقط ۳۲۰ نمونه را نگه می‌داشت)
 *    آن را متورم می‌کرد. از این نسخه، عدد یادگیری از روی یک دفتر با اثرانگشت یکتا (fingerprint)
 *    محاسبه می‌شود؛ هر معامله فقط یک‌بار می‌تواند آموزش بدهد و عدد نمایش‌داده‌شده دقیقاً برابر
 *    تعداد معامله‌های یکتای آموزش‌دیده است.
 * ۲. **دو شرط فعال‌سازی گیت**: (شرط ۱) رسیدن به ۱۰,۰۰۰ معاملهٔ یادگیری‌شده و (شرط ۲) فعال‌سازی
 *    صریح توسط کاربر. هیچ‌کدام به‌تنهایی گیت را فعال نمی‌کند.
 * ۳. **قابل‌فهم و قابل‌انتقال بودن اصلاحات شبکه**: هر تغییری که شبکه در پارامترها/قواعد انجام می‌دهد
 *    در `knowledgeChangeLog` با متن فارسی ثبت می‌شود و همراه «بستهٔ دانش» (JSON + منیفست فارسی)
 *    قابل ذخیره و بازخوانی در نسخهٔ بعدی اپ است.
 */
object NeuralLearningLedger {

    /** نسخهٔ طرح داده‌ای دفتر یادگیری؛ هر تغییر ساختاری باید این عدد را بالا ببرد. */
    const val LEDGER_SCHEMA_VERSION: Int = 2

    /** حداکثر ردیف نگه‌داری‌شده در دفتر (ردیف‌های قدیمی‌تر حذف می‌شوند ولی شمارش‌شان حفظ می‌شود). */
    const val MAX_LEDGER_ROWS: Int = 20_000

    /** حداکثر تعداد رویداد نگه‌داری‌شده در کارنامهٔ تغییرات. */
    const val MAX_CHANGE_LOG_ROWS: Int = 160

    /** شناسهٔ نوع بستهٔ دانش، برای اعتبارسنجی فایل ورودی. */
    const val BUNDLE_KIND: String = "AURUM_NEURAL_KNOWLEDGE"

    /** نسخهٔ طرح بستهٔ دانش. */
    const val BUNDLE_SCHEMA_VERSION: Int = 2

    /** نام فایل بستهٔ دانش در حافظهٔ گوشی. */
    fun bundleFileName(appVersionCode: Int): String =
        "AurumNeuralKnowledge_v${BUNDLE_SCHEMA_VERSION}_${appVersionCode.coerceAtLeast(1)}.json"

    /** نام منیفست خوانا (فارسی) کنار بستهٔ دانش. */
    fun manifestFileName(appVersionCode: Int): String =
        "AurumNeuralKnowledge_Manifest_FA_${appVersionCode.coerceAtLeast(1)}.md"

    // ─────────────────────────── ردیف دفتر یادگیری ───────────────────────────

    /**
     * اثر یک معاملهٔ بسته‌شده در دفتر یادگیری.
     *
     * [fingerprint] از شناسهٔ معامله و زمان بسته‌شدن ساخته می‌شود تا یادگیری همان معامله دوباره
     * تکرار نشود (حتی بین نسخه‌ها یا پس از بازخوانی پشتیبان).
     */
    @Serializable
    data class LearnedStamp(
        val fingerprint: String,
        val tradeId: String,
        val categoryCode: String,
        val won: Boolean,
        val achievedR: Double,
        val learnedAtMs: Long,
    )

    /** یک رویداد اصلاحی/یادگیری با متن فارسی، تا در نسخهٔ بعدی هم قابل خواندن باشد. */
    @Serializable
    data class KnowledgeChangeEntry(
        val id: String,
        val atMs: Long,
        val kindCode: String,
        val titleFa: String,
        val detailFa: String,
        val learnedCountAtChange: Int,
        val autoDetected: Boolean = true,
    )

    /** نتیجهٔ تطبیق (reconciliation) دفتر با ژورنال واقعی. */
    data class Reconciliation(
        val journalClosedTrades: Int,
        val distinctJournalTrades: Int,
        val ledgerRows: Int,
        val ledgerPrunedCount: Int,
        val learnedTotal: Int,
        val legacyStoredTotal: Int,
        val newlyLearnedThisRun: Int,
        val duplicatesRepaired: Int,
        val categoryTally: Map<String, Int>,
        val isBalanced: Boolean,
        val summaryFa: String,
    )

    /** سیاست دوشرطی فعال‌سازی گیت. */
    data class GateState(
        val learnedTotal: Int,
        val target: Int,
        val volumeConditionMet: Boolean,
        val userActivationEnabled: Boolean,
        val isGateActive: Boolean,
        val summaryFa: String,
    )

    // ─────────────────────────── اثرانگشت و امضا ───────────────────────────

    /**
     * اثرانگشت پایدار یک معاملهٔ بسته‌شده.
     *
     * فقط «هویت» معامله در آن نقش دارد (شناسه + زمان بسته‌شدن)، نه سود/زیان؛ بنابراین ویرایش
     * کوچک روی نتیجهٔ معامله باعث یادگیری دوباره نمی‌شود.
     */
    fun tradeFingerprint(tradeId: String, closedAtMs: Long?): String {
        val normalizedId = tradeId.trim()
        val stamp = closedAtMs ?: 0L
        return hex64(fnv1a64("$normalizedId|$stamp"))
    }

    /** اثرانگشت یک بستهٔ دانش (برای پیام‌های فارسی و شناسهٔ رویداد). */
    fun shortId(prefix: String, atMs: Long, salt: String = ""): String =
        prefix + "-" + hex64(fnv1a64("$prefix|$atMs|$salt")).take(10)

    /** چک‌سام SHA-256 برای اعتبارسنجی محتوای بستهٔ دانش. */
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    private fun hex64(value: Long): String = String.format(Locale.US, "%016x", value)

    // ─────────────────────────── عملیات دفتر ───────────────────────────

    /** مجموع معامله‌های یادگیری‌شده = ردیف‌های زندهٔ دفتر + ردیف‌های هرس‌شده. */
    fun learnedTotal(ledgerRows: Int, prunedCount: Int): Int =
        ledgerRows.coerceAtLeast(0) + prunedCount.coerceAtLeast(0)

    fun contains(ledger: List<LearnedStamp>, fingerprint: String): Boolean =
        ledger.any { it.fingerprint == fingerprint }

    /**
     * افزودن یک اثر به دفتر با هرس خودکار.
     *
     * @return جفتِ (دفتر جدید، تعداد ردیف‌های هرس‌شدهٔ جدید)
     */
    fun append(
        ledger: List<LearnedStamp>,
        stamp: LearnedStamp,
        maxRows: Int = MAX_LEDGER_ROWS,
    ): Pair<List<LearnedStamp>, Int> {
        val withoutDuplicate = ledger.filterNot { it.fingerprint == stamp.fingerprint }
        val merged = withoutDuplicate + stamp
        val cap = maxRows.coerceAtLeast(1)
        if (merged.size <= cap) return merged to 0
        val sorted = merged.sortedByDescending { it.learnedAtMs }
        val kept = sorted.take(cap)
        val pruned = merged.size - kept.size
        return kept to pruned
    }

    /** شمارش یادگیری به تفکیک دستهٔ بازار از روی دفتر. */
    fun categoryTally(ledger: List<LearnedStamp>): Map<String, Int> =
        ledger.groupingBy { it.categoryCode }.eachCount()

    /** شمارش برد/باخت به تفکیک دسته از روی دفتر. */
    fun categoryWinsLosses(ledger: List<LearnedStamp>): Map<String, Pair<Int, Int>> {
        val result = LinkedHashMap<String, Pair<Int, Int>>()
        for (stamp in ledger) {
            val (w, l) = result[stamp.categoryCode] ?: (0 to 0)
            result[stamp.categoryCode] = if (stamp.won) (w + 1) to l else w to (l + 1)
        }
        return result
    }

    /**
     * ادغام دو دفتر (برای بازخوانی بستهٔ دانش از گوشی یا دستگاه دیگر).
     * اتحاد بر اساس اثرانگشت انجام می‌شود تا هیچ یادگیری‌ای تکرار یا حذف نشود.
     */
    fun merge(
        current: List<LearnedStamp>,
        incoming: List<LearnedStamp>,
        maxRows: Int = MAX_LEDGER_ROWS,
    ): List<LearnedStamp> {
        if (incoming.isEmpty()) return current
        val byFingerprint = LinkedHashMap<String, LearnedStamp>()
        for (stamp in current) byFingerprint[stamp.fingerprint] = stamp
        var added = 0
        for (stamp in incoming) {
            if (byFingerprint.containsKey(stamp.fingerprint)) continue
            byFingerprint[stamp.fingerprint] = stamp
            added++
        }
        if (added == 0) return current
        return byFingerprint.values
            .sortedByDescending { it.learnedAtMs }
            .take(maxRows.coerceAtLeast(1))
    }

    /** اضافه‌کردن یک رویداد به کارنامهٔ تغییرات با سقف نگه‌داری. */
    fun appendChange(
        log: List<KnowledgeChangeEntry>,
        entry: KnowledgeChangeEntry,
        maxRows: Int = MAX_CHANGE_LOG_ROWS,
    ): List<KnowledgeChangeEntry> = (listOf(entry) + log.filterNot { it.id == entry.id })
        .sortedByDescending { it.atMs }
        .take(maxRows.coerceAtLeast(1))

    // ─────────────────────────── تطبیق با ژورنال ───────────────────────────

    /**
     * تطبیق دفتر با معامله‌های بسته‌شدهٔ واقعی ژورنال و ساخت گزارش فارسی.
     *
     * @param legacyStoredTotal عدد ذخیره‌شدهٔ نسخهٔ قبل (که ممکن است متورم باشد)
     * @param newlyLearnedThisRun تعداد معامله‌هایی که در همین اجرا تازه یاد گرفته شدند
     */
    fun reconcile(
        journalClosedTrades: Int,
        distinctJournalTrades: Int,
        ledger: List<LearnedStamp>,
        prunedCount: Int,
        legacyStoredTotal: Int,
        newlyLearnedThisRun: Int = 0,
    ): Reconciliation {
        val total = learnedTotal(ledger.size, prunedCount)
        val duplicates = (legacyStoredTotal - total).coerceAtLeast(0)
        val isBalanced = total <= distinctJournalTrades
        val summaryFa = buildString {
            append("🧾 تطبیق دفتر یادگیری با ژورنال: ")
            append("$journalClosedTrades معاملهٔ بسته‌شده ($distinctJournalTrades یکتا) در ژورنال · ")
            append("$total معاملهٔ آموخته‌شده در دفتر")
            if (prunedCount > 0) append(" (${ledger.size} ردیف زنده + $prunedCount هرس‌شده)")
            if (newlyLearnedThisRun > 0) append(" · $newlyLearnedThisRun مورد تازه یاد گرفته شد")
            if (duplicates > 0) append(" · $duplicates یادگیری تکراری از شمارنده حذف شد")
            append(if (isBalanced) "" else " · ⚠️ تعداد آموخته‌شده از معامله‌های ژورنال بیشتر است")
        }
        return Reconciliation(
            journalClosedTrades = journalClosedTrades,
            distinctJournalTrades = distinctJournalTrades,
            ledgerRows = ledger.size,
            ledgerPrunedCount = prunedCount,
            learnedTotal = total,
            legacyStoredTotal = legacyStoredTotal,
            newlyLearnedThisRun = newlyLearnedThisRun,
            duplicatesRepaired = duplicates,
            categoryTally = categoryTally(ledger),
            isBalanced = isBalanced,
            summaryFa = summaryFa,
        )
    }

    // ─────────────────────────── سیاست گیت ───────────────────────────

    /**
     * قانون دوشرطی کاربر:
     * شرط ۱ = رسیدن به [target] معاملهٔ یادگیری‌شده، شرط ۲ = فعال‌سازی صریح خود کاربر.
     * گیت فقط وقتی فعال است که **هر دو** شرط با هم برقرار باشند.
     */
    fun gateState(
        learnedTotal: Int,
        target: Int,
        userActivationEnabled: Boolean,
    ): GateState {
        val safeTarget = target.coerceAtLeast(1)
        val volumeMet = learnedTotal >= safeTarget
        val active = volumeMet && userActivationEnabled
        val summaryFa = when {
            active ->
                "🟢 گیت شبکه عصبی فعال است (شرط ۱ ✅ ${learnedTotal}/$safeTarget · شرط ۲ ✅ فعال‌سازی شما). " +
                    "ورود در شرایطی که شبکه پرخطر تشخیص داده مسدود می‌شود."
            volumeMet && !userActivationEnabled ->
                "🟡 شرط ۱ برقرار شد (${learnedTotal}/$safeTarget) ولی گیت غیرفعال است. " +
                    "برای فعال‌سازی باید خودتان کلید «فعال‌سازی گیت» را روشن کنید."
            !volumeMet && userActivationEnabled ->
                "🟠 فعال‌سازی شما ثبت شد، اما شرط ۱ کامل نیست (${learnedTotal}/$safeTarget). " +
                    "شبکه فقط یاد می‌گیرد و پس از رسیدن به $safeTarget معامله، گیت خودکار وارد مدار می‌شود."
            else ->
                "⚪ گیت غیرفعال است (فاز یادگیری ${learnedTotal}/$safeTarget، بدون فعال‌سازی دستی). " +
                    "شبکه فقط یاد می‌گیرد و هشدار می‌دهد."
        }
        return GateState(
            learnedTotal = learnedTotal,
            target = safeTarget,
            volumeConditionMet = volumeMet,
            userActivationEnabled = userActivationEnabled,
            isGateActive = active,
            summaryFa = summaryFa,
        )
    }

    // ─────────────────────────── بستهٔ دانش ───────────────────────────

    /**
     * بستهٔ کامل و خودتوصیف دانش شبکه عصبی؛ برای انتقال بین نسخه‌ها و دستگاه‌ها.
     *
     * `payloadJson` همان وضعیت کامل شبکه (وزن‌ها، توجه، خوشه‌ها، پارامترهای تیون‌شده، دفتر یادگیری)
     * است و `payloadSha256` صحت آن را تضمین می‌کند.
     */
    @Serializable
    data class KnowledgeBundle(
        val bundleKind: String = BUNDLE_KIND,
        val bundleSchemaVersion: Int = BUNDLE_SCHEMA_VERSION,
        val stateSchemaVersion: Int = LEDGER_SCHEMA_VERSION,
        val appVersionName: String = "",
        val appVersionCode: Int = 0,
        val createdAtMs: Long = 0L,
        val learnedTotal: Int = 0,
        val learnedStampRows: Int = 0,
        val learnedPrunedCount: Int = 0,
        val adamStepCount: Int = 0,
        val tuningGeneration: Int = 1,
        val gateUserActivationEnabled: Boolean = false,
        val summaryFa: String = "",
        val payloadSha256: String = "",
        val payloadJson: String = "",
        val changeLog: List<KnowledgeChangeEntry> = emptyList(),
    )

    /** نتیجهٔ بازخوانی بستهٔ دانش. */
    data class BundleImportResult(
        val isAccepted: Boolean,
        val importedLearnedTotal: Int,
        val mergedLearnedTotal: Int,
        val addedRows: Int,
        val bundleCreatedAtMs: Long,
        val bundleAppVersion: String,
        val summaryFa: String,
    )

    private val bundleJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encodeBundle(bundle: KnowledgeBundle): String = bundleJson.encodeToString(bundle)

    /** تلاش برای خواندن بستهٔ دانش با اعتبارسنجی کامل؛ در شکست `null` برمی‌گردد. */
    fun decodeBundle(raw: String): KnowledgeBundle? {
        val parsed = runCatching { bundleJson.decodeFromString(KnowledgeBundle.serializer(), raw) }
            .getOrNull() ?: return null
        val problems = validateBundle(parsed)
        return if (problems.isEmpty()) parsed else null
    }

    /** فهرست اشکال‌های یک بستهٔ دانش (خالی = سالم). */
    fun validateBundle(bundle: KnowledgeBundle): List<String> {
        val problems = mutableListOf<String>()
        if (bundle.bundleKind != BUNDLE_KIND) problems += "نوع فایل بستهٔ دانش Aurum نیست (${bundle.bundleKind})"
        if (bundle.bundleSchemaVersion > BUNDLE_SCHEMA_VERSION) {
            problems += "نسخهٔ بسته (${bundle.bundleSchemaVersion}) از این نسخهٔ اپ جدیدتر است"
        }
        if (bundle.payloadJson.isBlank()) problems += "محتوای دانش (payload) خالی است"
        if (bundle.payloadSha256.isBlank() || sha256(bundle.payloadJson) != bundle.payloadSha256) {
            problems += "چک‌سام محتوا مطابقت ندارد (فایل ناقص یا دست‌کاری‌شده)"
        }
        return problems
    }

    /**
     * ساخت منیفست فارسی خوانا از بستهٔ دانش.
     *
     * این فایل همان چیزی است که در نسخهٔ بعدی اپ — و برای هر بازبین انسانی — نشان می‌دهد شبکه
     * دقیقاً چه چیزی را اصلاح یا یاد گرفته است.
     */
    fun buildManifestFa(bundle: KnowledgeBundle): String {
        val sb = StringBuilder()
        sb.appendLine("# منیفست دانش شبکهٔ عصبی خودآموز Aurum Edge")
        sb.appendLine()
        sb.appendLine("این فایل خلاصهٔ خوانا (فارسی) از «بستهٔ دانش» شبکهٔ عصبی است و همراه فایل")
        sb.appendLine("`${bundleFileName(bundle.appVersionCode)}` ذخیره می‌شود تا در نسخه‌های بعدی اپ قابل")
        sb.appendLine("بازخوانی و مقایسه باشد.")
        sb.appendLine()
        sb.appendLine("| مورد | مقدار |")
        sb.appendLine("|---|---|")
        sb.appendLine("| نسخهٔ اپ سازندهٔ بسته | ${bundle.appVersionName.ifBlank { "—" }} (کد ${bundle.appVersionCode}) |")
        sb.appendLine("| زمان ساخت بسته | ${formatJalaliLike(bundle.createdAtMs)} |")
        sb.appendLine("| نسخهٔ طرح دفتر یادگیری | ${bundle.stateSchemaVersion} |")
        sb.appendLine("| نسخهٔ طرح بستهٔ دانش | ${bundle.bundleSchemaVersion} |")
        sb.appendLine("| معامله‌های یادگیری‌شده | ${bundle.learnedTotal} |")
        sb.appendLine("| ردیف‌های دفتر (زنده) | ${bundle.learnedStampRows} |")
        sb.appendLine("| ردیف‌های هرس‌شده | ${bundle.learnedPrunedCount} |")
        sb.appendLine("| گام‌های بهینه‌سازی AdamW | ${bundle.adamStepCount} |")
        sb.appendLine("| نسل تیونینگ خودکار | ${bundle.tuningGeneration} |")
        sb.appendLine("| فعال‌سازی گیت توسط کاربر | ${if (bundle.gateUserActivationEnabled) "بله" else "خیر"} |")
        sb.appendLine("| چک‌سام محتوا (SHA-256) | `${bundle.payloadSha256}` |")
        sb.appendLine()
        if (bundle.summaryFa.isNotBlank()) {
            sb.appendLine("## خلاصهٔ وضعیت")
            sb.appendLine()
            sb.appendLine(bundle.summaryFa)
            sb.appendLine()
        }
        sb.appendLine("## کارنامهٔ اصلاحات شبکه (${bundle.changeLog.size} رویداد آخر)")
        sb.appendLine()
        if (bundle.changeLog.isEmpty()) {
            sb.appendLine("هنوز رویداد اصلاحی ثبت نشده است.")
        } else {
            sb.appendLine("| زمان | نوع | عنوان | توضیح | معامله‌های آموخته‌شده |")
            sb.appendLine("|---|---|---|---|---|")
            for (row in bundle.changeLog) {
                sb.appendLine(
                    "| ${formatJalaliLike(row.atMs)} | ${row.kindCode} | ${row.titleFa.replace("|", "/")} | " +
                        "${row.detailFa.replace("|", "/").replace("\n", " ")} | ${row.learnedCountAtChange} |",
                )
            }
        }
        sb.appendLine()
        sb.appendLine("> برای بازگرداندن این دانش در نسخهٔ بعدی: فایل بستهٔ دانش را در")
        sb.appendLine("> `Download/AurumEdge/Archive/` نگه دارید و در تب «هوش عصبی» دکمهٔ «بازخوانی بستهٔ دانش» را بزنید.")
        return sb.toString()
    }

    /** ساخت خلاصهٔ یک‌خطی فارسی از یک تحویل (import/export). */
    fun describeBundleFa(bundle: KnowledgeBundle): String =
        "بستهٔ دانش ${bundle.appVersionName.ifBlank { "?" }} (کد ${bundle.appVersionCode}) · " +
            "${bundle.learnedTotal} معاملهٔ آموخته‌شده · نسل تیونینگ ${bundle.tuningGeneration} · " +
            "${bundle.changeLog.size} رویداد اصلاحی"

    private fun formatJalaliLike(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .format(java.util.Date(epochMs)) + " (UTC)"
    }
}
