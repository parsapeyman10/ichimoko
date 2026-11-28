package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.PaperTrade
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit export, never includes API keys or the private neural weights. */
object SessionResearchExport {
    @Serializable
    data class Manifest(
        val schema: String = "aurum.session.research.v1",
        val createdAtUtcMs: Long,
        val timezone: String = "UTC",
        val timestampUnit: String = "unix milliseconds",
        val tradeCount: Int,
        val openCount: Int,
        val closedCount: Int,
        val backtestCount: Int,
        val withSignalBarTime: Int,
        val withNeuralEntryObservation: Int,
        val warningsFa: List<String>,
    )

    private val json = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }
    private fun iso(value: Long?): String = value?.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).toString() } ?: ""
    private fun cell(value: Any?): String = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
    private fun line(vararg values: Any?) = values.joinToString(",") { cell(it) } + "\r\n"

    internal fun writeBundle(output: File, trades: List<PaperTrade>, reportZip: File, createdAt: Long) {
        val manifest = Manifest(
            createdAtUtcMs = createdAt,
            tradeCount = trades.size,
            openCount = trades.count { it.isOpen },
            closedCount = trades.count { !it.isOpen },
            backtestCount = trades.count { it.isBacktest },
            withSignalBarTime = trades.count { (it.signalBarTime ?: 0L) > 0L },
            withNeuralEntryObservation = trades.count { it.neuralEntryObservation != null },
            warningsFa = listOf(
                "داده حساس و بدون رمزگذاری است؛ فقط با فرد مورد اعتماد به اشتراک بگذارید.",
                "معاملات باز، بسته و بک‌تست بدون فیلتر در journal_trades.jsonl آمده‌اند؛ origin و id حفظ می‌شوند و تکراری‌ها حذف نشده‌اند.",
                "زمان‌ها میلی‌ثانیه یونیکس و نمایش CSV در UTC است؛ signalBarTime زمان کندل سیگنال است، نه زمان صدور یا شناسهٔ یکتای سیگنال.",
                "مقادیر null، خالی یا sentinel ذخیره‌شده به معنی دادهٔ نامعلوم‌اند؛ ویژگی‌های ثبت‌نشده و نتایج قبلی بازسازی نمی‌شوند.",
                "CSV نمای خلاصه است؛ نسخه کامل هر معامله از جمله ویژگی‌ها، شروط ورود و اطلاعات عصبی در JSONL است. R در CSV نسبت PnL به ریسک فعلی محاسبه‌شدهٔ مدل است، نه الزاماً R ثبت‌شده در ورود.",
                "گزارش استراتژی و آرشیوها جدا از ژورنال تهیه شده‌اند؛ snapshot سراسری اتمیک نیست. آرشیوها ممکن است هم‌پوشانی یا خروجی چندباره داشته باشند.",
                "رویدادهای استاپ در stopObservations فقط از زمان ثبت در نسخهٔ جدید و حداکثر ۶۴ رویداد اخیر هستند؛ پیشنهاد سایه‌ای خروج فرضی را شبیه‌سازی نمی‌کند.",
                "کندل خام، ترتیب تیک، تنظیمات تاریخی کامل و تاریخچهٔ آموزش مستقل در این بسته تضمین یا بازسازی نمی‌شوند. API key و وزن کامل شبکه صادر نمی‌شود.",
            ),
        )
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun textEntry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                textEntry("manifest.json", json.encodeToString(Manifest.serializer(), manifest))
                zip.putNextEntry(ZipEntry("journal_trades.jsonl"))
                trades.forEach { trade ->
                    zip.write(json.encodeToString(PaperTrade.serializer(), trade).toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("journal_trades.csv"))
                zip.write(line("trade_id", "symbol", "timeframe", "strategy_id", "direction", "origin", "entry_time_utc", "entry_time_ms", "exit_time_utc", "exit_time_ms", "signal_bar_time_utc", "signal_bar_time_ms", "entry_price", "exit_price", "initial_stop_loss", "current_stop_loss", "take_profit", "pnl_usd", "risk_usd_model", "r_model", "exit_reason", "neural_entry_observation_present").toByteArray(Charsets.UTF_8))
                trades.forEach { t ->
                    zip.write(line(t.id, t.symbol, t.interval.label, t.strategyId, t.action.name, t.origin, iso(t.openedAt), t.openedAt, iso(t.closedAt), t.closedAt, iso(t.signalBarTime), t.signalBarTime, t.entry, t.exitPrice, t.initialStopLoss, t.stopLoss, t.takeProfit, t.pnlUsd, t.riskUsd, t.rMultiple, t.exitReason, t.neuralEntryObservation != null).toByteArray(Charsets.UTF_8))
                }
                zip.closeEntry()
                ZipFile(reportZip).use { source ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        // Source is our own allowlisted export; names cannot escape this directory.
                        require(entry.name == "strategy_experiment_report.json" ||
                            (entry.name.startsWith("backtest_archives/") && !entry.name.removePrefix("backtest_archives/").contains('/')))
                        zip.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    fun create(context: Context, settings: AppSettings, summary: NeuralLearningStateSummary, trades: List<PaperTrade>): File {
        val source = StrategyReportExport.create(context, settings, summary, trades)
        val output = File(context.cacheDir.resolve("exports").apply { mkdirs() }, "Aurum_Session_Research_${System.currentTimeMillis()}.zip")
        try { writeBundle(output, trades, source, System.currentTimeMillis()) } finally { source.delete() }
        return output
    }
}
