package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningLedger
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.NeuralJournalCalibrationSnapshot
import com.aurum.edge.core.StrategyAuditReport
import com.aurum.edge.core.SessionPerformanceReport
import com.aurum.edge.core.PaperTrade
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit allowlist: never exports AppSettings API keys or arbitrary files. */
object StrategyReportExport {
    @Serializable
    data class Snapshot(
        val schema: String = "aurum.strategy.audit.v1",
        val createdAt: Long,
        val caveatFa: String,
        val gateActive: Boolean,
        val gateSummaryFa: String,
        val strategies: List<StrategyAuditReport.Strategy>,
        val performance: SessionPerformanceReport.Report,
        val knowledgeChangeLog: List<NeuralLearningLedger.KnowledgeChangeEntry>,
        val calibration: NeuralJournalCalibrationSnapshot,
        val jobs: List<BacktestJob>,
        val lastSession: BacktestSession?,
        val archivedFiles: List<String>,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun archives(context: Context): List<File> = File(context.filesDir, "backtest/archive")
        .listFiles()?.filter { it.isFile && (it.extension.equals("csv", true) || it.extension.equals("json", true)) && it.name != "ledger.json" }
        ?.sortedBy { it.name }.orEmpty()

    fun create(context: Context, settings: AppSettings, summary: NeuralLearningStateSummary, trades: List<PaperTrade> = emptyList()): File {
        val archives = archives(context)
        val jobs = BacktestJobStore(context).list()
        val snapshot = Snapshot(
            createdAt = System.currentTimeMillis(),
            caveatFa = "مقادیر پیش از تغییر به صورت تاریخی ذخیره نشده‌اند؛ مقادیر پیش‌فرض فقط مرجع نسخهٔ فعلی هستند. پروفایل عصبی ممکن است اعمال نشده باشد؛ حل عمومی بدون نماد است و موتور ممکن است بر اساس مدل یا نماد مقدار دیگری استفاده کند. تاریخچهٔ حذف‌شده قابل بازیابی نیست.",
            gateActive = summary.isGateActive,
            gateSummaryFa = summary.gatePolicySummaryFa,
            strategies = StrategyAuditReport.build(settings, summary),
            performance = SessionPerformanceReport.build(trades),
            knowledgeChangeLog = summary.knowledgeChangeLog,
            calibration = summary.lastJournalCalibration,
            jobs = jobs,
            lastSession = BacktestSessionStore(context).read(),
            archivedFiles = archives.map { it.name },
        )
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        val output = File(folder, "Aurum_Strategy_Experiments_${snapshot.createdAt}.zip")
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("strategy_experiment_report.json"))
                zip.write(json.encodeToString(Snapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                archives.forEach { file ->
                    zip.putNextEntry(ZipEntry("backtest_archives/${file.name}"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
        return output
    }
}
