package com.aurum.edge.data

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningStateSummary
import com.aurum.edge.core.NeuralEntryObservation
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SessionResearchExportTest {
    @Test fun `complete journal export keeps open closed origins and features without secrets`() {
        val context = RuntimeEnvironment.getApplication()
        val entry = 1_700_000_000_000L
        val observation = NeuralEntryObservation(evaluatedAt = entry - 1000, signalBarTime = entry - 60000,
            features = List(24) { 0.0 }, conditionKey = "test", predictedWinProbability = 0.6, expectedR = 0.3)
        val closed = PaperTrade(id = "abc", symbol = "XAU/USD", interval = Interval.M5, action = SignalAction.BUY,
            entry = 100.0, stopLoss = 99.0, initialStopLoss = 98.0, takeProfit = 104.0, confidence = 70.0,
            riskReward = 2.0, openedAt = entry, closedAt = entry + 60_000, exitPrice = 104.0, pnlUsd = 10.0,
            fixedRiskUsd = 5.0, signalBarTime = entry - 60_000, neuralEntryObservation = observation)
        val open = closed.copy(id = "backtest2", symbol = "BTC/USD", origin = "BACKTEST", closedAt = null,
            exitPrice = null, pnlUsd = null, initialStopLoss = null, neuralEntryObservation = null, signalBarTime = null)
        val file = SessionResearchExport.create(context, AppSettings(apiKey = "secret-api-key"), NeuralLearningStateSummary(), listOf(closed, open))
        ZipFile(file).use { zip ->
            fun read(n: String) = zip.getInputStream(zip.getEntry(n)).bufferedReader().readText()
            val manifest = Json.parseToJsonElement(read("manifest.json")).jsonObject
            assertEquals("2", manifest.getValue("tradeCount").jsonPrimitive.content)
            assertEquals("1", manifest.getValue("openCount").jsonPrimitive.content)
            assertEquals("1", manifest.getValue("backtestCount").jsonPrimitive.content)
            val journal = read("journal_trades.jsonl").lines().filter { it.isNotBlank() }
            assertEquals(2, journal.size)
            assertTrue(journal[0].contains("neuralEntryObservation"))
            assertTrue(journal[0].contains("\"features\""))
            assertTrue(journal[1].contains("\"initialStopLoss\":null"))
            assertTrue(journal[1].contains("\"origin\":\"BACKTEST\""))
            val csv = read("journal_trades.csv")
            assertTrue(csv.contains("2023-11-14T22:13:20Z"))
            assertTrue(csv.contains("\"signal_bar_time_ms\""))
            assertTrue(csv.contains("\"2.0\""))
            assertFalse(read("strategy_experiment_report.json").contains("secret-api-key"))
            assertFalse(read("manifest.json").contains("secret-api-key"))
        }
    }
}
