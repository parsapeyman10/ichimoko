package com.aurum.edge.data

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalExportEngineTest {

    private fun sampleTrades(): List<PaperTrade> = listOf(
        PaperTrade(
            id = "t1",
            symbol = "XAUUSD",
            action = SignalAction.BUY,
            entry = 2650.0,
            stopLoss = 2640.0,
            takeProfit = 2675.0,
            quantity = 0.5,
            openedAt = 1_700_000_000_000L,
            closedAt = 1_700_003_600_000L,
            exitPrice = 2675.0,
            exitReason = "برخورد به حد سود (TP)",
            pnlUsd = 12.50,
            rMultiple = 2.50,
            fixedRiskUsd = 5.0,
            strategyId = "combo_1234",
            strategyTitleFa = "ترکیب ۴ کتاب (کتاب ۱+۲+۳+۴)",
            entryTimeframe = "15m",
            plannedRiskReward = 2.50,
            optimalRiskReward = 2.50,
            estimatedRoundTripFeeUsd = 0.35,
        ),
        PaperTrade(
            id = "t2",
            symbol = "BTCUSDT",
            action = SignalAction.SELL,
            entry = 92000.0,
            stopLoss = 92500.0,
            takeProfit = 90750.0,
            quantity = 0.01,
            openedAt = 1_700_004_000_000L,
            closedAt = 1_700_007_600_000L,
            exitPrice = 92500.0,
            exitReason = "برخورد به حد ضرر (SL)",
            pnlUsd = -5.00,
            rMultiple = -1.00,
            fixedRiskUsd = 5.0,
            strategyId = "combo_13",
            strategyTitleFa = "ترکیب دوگانه (کتاب ۱+۳)",
            entryTimeframe = "5m",
            plannedRiskReward = 2.50,
            optimalRiskReward = 2.50,
            estimatedRoundTripFeeUsd = 0.45,
        ),
        PaperTrade(
            id = "t3",
            symbol = "XAGUSD",
            action = SignalAction.BUY,
            entry = 31.20,
            stopLoss = 31.00,
            takeProfit = 31.80,
            quantity = 25.0,
            openedAt = 1_700_008_000_000L,
            closedAt = 1_700_011_600_000L,
            exitPrice = 31.80,
            exitReason = "قفل سود تلاقی تنکان/کیجون پس از 3R",
            pnlUsd = 15.00,
            rMultiple = 3.00,
            fixedRiskUsd = 5.0,
            strategyId = "combo_1234",
            strategyTitleFa = "ترکیب ۴ کتاب (کتاب ۱+۲+۳+۴)",
            entryTimeframe = "15m",
            plannedRiskReward = 3.00,
            optimalRiskReward = 3.00,
            estimatedRoundTripFeeUsd = 0.25,
        ),
        PaperTrade(
            id = "t4",
            symbol = "ETHUSDT",
            action = SignalAction.BUY,
            entry = 3100.0,
            stopLoss = 3080.0,
            takeProfit = 3150.0,
            quantity = 0.25,
            openedAt = 1_700_012_000_000L,
            closedAt = null,
            exitPrice = null,
            exitReason = null,
            pnlUsd = null,
            rMultiple = null,
            fixedRiskUsd = 5.0,
            strategyId = "combo_24",
            strategyTitleFa = "ترکیب دوگانه (کتاب ۲+۴)",
            entryTimeframe = "5m",
            plannedRiskReward = 2.50,
            optimalRiskReward = 2.50,
            estimatedRoundTripFeeUsd = 0.30,
        ),
    )

    @Test
    fun buildInstitutionalTearSheet_computesQuantMetricsAccurately() {
        val report = JournalExportEngine.buildInstitutionalTearSheet(
            trades = sampleTrades(),
            initialCapital = 100_000.0,
            currentBalance = 100_022.5,
            nowEpochMs = 1_700_020_000_000L,
        )

        assertEquals(4, report.totalTradesCount)
        assertEquals(3, report.closedTradesCount)
        assertEquals(1, report.openTradesCount)
        assertEquals(2, report.winsCount)
        assertEquals(1, report.lossesCount)
        assertEquals(22.50, report.netPnlUsd, 1e-4)
        assertEquals(27.50, report.grossProfitUsd, 1e-4)
        assertEquals(5.00, report.grossLossUsd, 1e-4)
        assertEquals(27.50 / 5.00, report.profitFactor ?: 0.0, 1e-4)
        assertEquals(66.6666, report.winRatePct ?: 0.0, 0.01)
        assertNotNull(report.sqnScore)
        assertNotNull(report.sharpeRatio)
        assertNotNull(report.kellyCriterionPct)
        assertEquals(5.00, report.maxDrawdownUsd, 1e-4)
        assertEquals(2, report.tf15mCount)
        assertEquals(1, report.tf5mCount)
        assertEquals(2, report.longTradesCount)
        assertEquals(1, report.shortTradesCount)
        assertEquals(4, report.equityCurve.size) // baseline + 3 closed trades
        assertTrue(report.strategyLeaderboard.size >= 15)
        assertEquals(15, report.symbolLeaderboard.size)
    }

    @Test
    fun generateCsvAndJsonAndHtml_produceValidInstitutionalArtifacts() {
        val report = JournalExportEngine.buildInstitutionalTearSheet(
            trades = sampleTrades(),
            initialCapital = 100_000.0,
            currentBalance = 100_022.5,
            nowEpochMs = 1_700_020_000_000L,
        )

        val csv = JournalExportEngine.generateCsvContent(report)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("Trade_ID,Status,Symbol"))
        assertTrue(csv.contains("XAUUSD"))
        assertTrue(csv.contains("BTCUSDT"))
        assertTrue(csv.contains("XAGUSD"))

        val json = JournalExportEngine.generateJsonContent(report)
        assertTrue(json.contains("\"schemaVersion\": \"3.9.11-institutional-tearsheet\""))
        assertTrue(json.contains("\"strategyLeaderboard\""))
        assertTrue(json.contains("\"symbolLeaderboard\""))
        assertTrue(json.contains("\"equityCurve\""))

        val html = JournalExportEngine.generateInteractiveHtmlTearSheet(report)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("filterTrades()"))
        assertTrue(html.contains("XAUUSD"))
        assertTrue(html.contains("SQN"))
    }
}
