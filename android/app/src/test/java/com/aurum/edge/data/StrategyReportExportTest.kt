package com.aurum.edge.data

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.NeuralLearningStateSummary
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StrategyReportExportTest {
    @Test fun archiveAndJobsAreIncludedWithoutSettingsSecrets() {
        val context = RuntimeEnvironment.getApplication()
        val archive = File(context.filesDir, "backtest/archive").apply { mkdirs() }
        File(archive, "report.csv").writeText("trade,pnl\n1,5")
        File(archive, "report.json").writeText("{\"trades\":1}")
        val spec = BacktestJobSpec(id="id1", dataSetId="d1", dataSetName="example", createdAt=1234L,
            strategyId="BASE_ICHIMOKU", symbol="BTC/USDT", runAllStrategies=false, startEquityUsd=100000.0,
            riskPercent=0.5, useSmartExit=true, usePreserveMode=true, useTimeStop=false,
            optimisticSameBarFill=false, maxBars=0, includeHiddenCrossLayer=false,
            compressionExpansion=false, cursorIndex=0)
        BacktestJobStore(context).upsert(BacktestJob(spec = spec, status = BacktestJob.STATUS_FAILED, messageFa = "failed"))
        val result = StrategyReportExport.create(context, AppSettings(apiKey = "secret-api-key"), NeuralLearningStateSummary())
        ZipFile(result).use { zip ->
            assertNotNull(zip.getEntry("backtest_archives/report.csv"))
            assertNotNull(zip.getEntry("backtest_archives/report.json"))
            val report = zip.getInputStream(zip.getEntry("strategy_experiment_report.json")).bufferedReader().readText()
            assertTrue(report.contains("BASE_ICHIMOKU"))
            assertTrue(report.contains("id1"))
            assertFalse(report.contains("secret-api-key"))
            assertFalse(report.contains("\"apiKey\""))
        }
    }
}
