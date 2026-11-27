package com.aurum.edge.data

import android.content.Context
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ComprehensiveResearchExportTest {
    @Test fun `comprehensive export includes listed sources and never exports credentials`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit()
            .putString("td_api_key", "secret-api-key").putString("td_symbol_keys_json", "secret-map")
            .putFloat("risk_percent", 0.5f).putString("unknown_secret", "secret-value").commit()
        val csv = File(context.filesDir, "backtest/csv").apply { mkdirs() }
        File(csv, "market.csv").writeText("time,open\n1,5")
        File(csv, "private.txt").writeText("should-not-be-included")
        val candles = File(context.filesDir, "phone_vault_archive/CandleVault").apply { mkdirs() }
        File(candles, "XAU_5m.tsv").writeText("1\t1\t2\t1\t2")
        val trade = PaperTrade(id = "abc", symbol = "XAU/USD", interval = Interval.M5, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, takeProfit = 102.0, confidence = 80.0,
            riskReward = 2.0, openedAt = 1700000000000L)
        val file = ComprehensiveResearchExport.create(context, AppSettings(apiKey = "secret-api-key"),
            NeuralLearningStateSummary(), listOf(trade), "{\"weights\":[1,2]}")
        ZipFile(file).use { zip ->
            fun bytes(name: String) = zip.getInputStream(zip.getEntry(name)).readBytes()
            val manifest = JSONObject(bytes("comprehensive_manifest.json").toString(Charsets.UTF_8))
            assertEquals(1, manifest.getInt("tradeCount"))
            assertTrue(bytes("neural/live_state.json").toString(Charsets.UTF_8).contains("weights"))
            assertNotNull(zip.getEntry("journal_and_experiments/journal_trades.jsonl"))
            assertNotNull(zip.getEntry("journal_and_experiments/strategy_experiment_report.json"))
            assertNotNull(zip.getEntry("backtest_data/csv/market.csv"))
            assertNotNull(zip.getEntry("candle_vault/internal/XAU_5m.tsv"))
            assertNull(zip.getEntry("backtest_data/csv/private.txt"))
            val pref = bytes("settings/trading_preferences.json").toString(Charsets.UTF_8)
            assertTrue(pref.contains("risk_percent")); assertFalse(pref.contains("secret"))
            assertFalse(bytes("journal_and_experiments/strategy_experiment_report.json").toString(Charsets.UTF_8).contains("secret-api-key"))
            val entries = manifest.getJSONArray("inventory")
            val csvItem = (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("path") == "backtest_data/csv/market.csv" }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes("backtest_data/csv/market.csv"))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(hash, csvItem.getString("sha256"))
            assertEquals(bytes("backtest_data/csv/market.csv").size.toLong(), csvItem.getLong("bytes"))
        }
    }
}
