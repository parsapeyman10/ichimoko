package com.aurum.edge.data

import android.content.Context
import android.os.Environment
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.PaperTrade
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly approved analysis sources only; never walks the application data directory. */
object ComprehensiveResearchExport {
    private val settingKeys = setOf(
        "symbol", "interval", "risk_percent", "balance", "initial_capital", "target_capital_usd",
        "daily_loss_limit_percent", "max_drawdown_limit_percent", "max_open_trades", "max_open_trades_per_symbol",
        "min_confidence", "spread_price", "commission_per_oz", "max_leverage_cap_v3913",
        "auto_paper_nine_conditions", "use_fixed_dollar_risk", "fixed_risk_usd", "active_strategy",
        "btc_exclusive_strategy_id", "btc_run_all_strategies_simultaneously", "btc_enabled_strategy_ids_csv",
        "btc_base_strategy_config_json", "btc_custom_strategies_json", "signal_profile", "signal_momentum_volume",
        "signal_flat_span_b", "signal_range_chop_filter", "signal_higher_timeframe_filter", "signal_fake_breakout_filter",
        "signal_dynamic_spread_filter", "signal_risky_timing_filter", "signal_structure_risk_filter",
        "signal_cooldown_filter", "signal_chikou_confirmation", "pause_on_news", "background_monitor",
    )

    private fun settings(context: Context): String {
        val result = JSONObject()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).all
            .filterKeys { it in settingKeys }.forEach { (key, value) ->
                result.put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            }
        return result.toString()
    }

    /** Require immediate children of an explicitly selected directory and reject symlinks. */
    private fun files(dir: File, extension: String): List<File> {
        if (!dir.isDirectory) return emptyList()
        val root = dir.canonicalFile
        return dir.listFiles()?.filter {
            it.isFile && it.extension.equals(extension, true) && it.canonicalFile.parentFile == root && it.absoluteFile == it.canonicalFile
        }?.sortedBy { it.name }.orEmpty()
    }

    fun create(context: Context, settings: AppSettings, summary: NeuralLearningStateSummary,
               trades: List<PaperTrade>, neuralJson: String): File {
        // Validate caller-provided JSON before writing; never scrape the neural file while it is mutating.
        JSONObject(neuralJson)
        val source = SessionResearchExport.create(context, settings, summary, trades)
        val output = File(context.cacheDir.resolve("exports").apply { mkdirs() }, "Aurum_Comprehensive_Analysis_${System.currentTimeMillis()}.zip")
        try {
            val inventory = JSONArray()
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun add(name: String, input: java.io.InputStream, sourceLabel: String) {
                    require(!name.startsWith('/') && name.split('/').none { it == ".." || it.isEmpty() })
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(ZipEntry(name))
                    input.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n == -1) break
                            zip.write(buffer, 0, n); hash.update(buffer, 0, n); size += n
                        }
                    }
                    zip.closeEntry()
                    inventory.put(JSONObject().put("path", name).put("source", sourceLabel).put("bytes", size)
                        .put("sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }))
                }
                fun addText(name: String, text: String, label: String) = add(name, text.byteInputStream(Charsets.UTF_8), label)
                ZipFile(source).use { old ->
                    val entries = old.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory) add("journal_and_experiments/${entry.name}", old.getInputStream(entry), "session_research_export")
                    }
                }
                val profiles = com.aurum.edge.core.SessionStrategyEvidence.profiles(trades)
                val profileJson = kotlinx.serialization.json.Json { encodeDefaults = true }
                addText("analysis/session_strategy_profiles.jsonl", profiles.joinToString("\n", postfix = if (profiles.isEmpty()) "" else "\n") {
                    profileJson.encodeToString(com.aurum.edge.core.SessionStrategyEvidence.Profile.serializer(), it)
                }, "computed_live_journal_descriptive")
                addText("neural/live_state.json", neuralJson, "synchronized_neural_state")
                addText("settings/trading_preferences.json", settings(context), "allowlisted_preferences")
                addText("assistant/prospective.json", AssistantProspectiveStore.export(context), "assistant_prospective")
                addText("assistant/review_history.json", AssistantReviewStore.history(context), "assistant_review")
                for ((path, name) in listOf(
                    "ai_trader/trader_memory.json" to "assistant/trader_memory.json",
                    "session_exit_learning_v1.json" to "assistant/session_exit_learning.json",
                    "backtest/backtest_neural_v1.json" to "neural/backtest_state.json",
                    "backtest/index.json" to "backtest_data/index.json",
                )) {
                    val file = File(context.filesDir, path)
                    if (file.isFile && file.absoluteFile == file.canonicalFile) add(name, file.inputStream(), path)
                }
                files(File(context.filesDir, "backtest/csv"), "csv").forEach {
                    add("backtest_data/csv/${it.name}", it.inputStream(), "imported_backtest_csv")
                }
                val roots = listOf(
                    "internal" to File(context.filesDir, "phone_vault_archive/CandleVault"),
                    "app_external" to context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.resolve("AurumEdge/Archive/CandleVault"),
                    "downloads" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.resolve("AurumEdge/Archive/CandleVault"),
                )
                for ((label, dir) in roots) if (dir != null) files(dir, "tsv").forEach {
                    add("candle_vault/$label/${it.name}", it.inputStream(), "candle_vault_$label")
                }
                val manifest = JSONObject().put("schema", "aurum.comprehensive.analysis.v1")
                    .put("createdAtUtcMs", System.currentTimeMillis()).put("timezone", "UTC")
                    .put("tradeCount", trades.size).put("inventory", inventory)
                    .put("warningsFa", JSONArray(listOf(
                        "محتوای فایل بدون رمز است و شامل معاملات، اطلاعات حساب، وزن‌ها و حافظه شبکه است؛ فقط با شخص مورد اعتماد به اشتراک بگذارید.",
                        "کلیدها، رمزها و توکن‌ها از تنظیمات صریحاً حذف‌اند؛ متن آزاد معاملات و فایل‌های بازار انتخاب‌شده ممکن است اطلاعات شخصی داشته باشند.",
                        "عکس‌های اجزا اتمیک سراسری نیست؛ فایل‌های خام هنگام خروجی ممکن است در حال تغییر باشند. checksum برای بایت‌های بسته‌شده است.",
                        "فایل‌های بازار شامل داده‌های واردشده بک‌تست و خزانهٔ موجود است؛ کامل بودن، منشأ قیمت و ترتیب تیک قابل تضمین نیست. کپی‌های خزانه با مسیر مبدأ حفظ شده‌اند.",
                        "اطلاعات حذف‌شده یا ثبت‌نشده، سیگنال یکتا، تنظیمات گذشته، داده آموزش کور و تیک‌ها قابل بازسازی نیستند. این بسته تضمین سود یا آزمون مستقل نیست.",
                    )))
                // The manifest inventories every other entry, not itself.
                zip.putNextEntry(ZipEntry("comprehensive_manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        } catch (e: Exception) {
            output.delete(); throw e
        } finally { source.delete() }
        return output
    }
}
