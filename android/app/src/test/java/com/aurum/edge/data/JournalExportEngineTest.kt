package com.aurum.edge.data

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalExportEngineTest {

    private fun sampleTrades(): List<PaperTrade> = listOf(
        PaperTrade(
            id = "t1",
            symbol = "XAU/USD",
            interval = Interval.M15,
            action = SignalAction.BUY,
            entry = 2650.0,
            stopLoss = 2640.0,
            takeProfit = 2675.0,
            confidence = 92.0,
            riskReward = 2.50,
            openedAt = 1_700_000_000_000L,
            closedAt = 1_700_003_600_000L,
            exitPrice = 2675.0,
            exitReason = "برخورد به حد سود (TP)",
            pnlUsd = 12.50,
            positionOz = 0.5,
            fixedRiskUsd = 5.0,
            strategyId = "combo_1234",
            strategyTitleFa = "ترکیب ۴ کتاب (کتاب ۱+۲+۳+۴)",
            optimalRiskReward = 2.50,
        ),
        PaperTrade(
            id = "t2",
            symbol = "BTC/USDT",
            interval = Interval.M5,
            action = SignalAction.SELL,
            entry = 92000.0,
            stopLoss = 92500.0,
            takeProfit = 90750.0,
            confidence = 88.0,
            riskReward = 2.50,
            openedAt = 1_700_004_000_000L,
            closedAt = 1_700_007_600_000L,
            exitPrice = 92500.0,
            exitReason = "برخورد به حد ضرر (SL)",
            pnlUsd = -5.00,
            positionOz = 0.01,
            fixedRiskUsd = 5.0,
            strategyId = "combo_13",
            strategyTitleFa = "ترکیب دوگانه (کتاب ۱+۳)",
            optimalRiskReward = 2.50,
        ),
        PaperTrade(
            id = "t3",
            symbol = "XAG/USD",
            interval = Interval.M15,
            action = SignalAction.BUY,
            entry = 31.20,
            stopLoss = 31.00,
            takeProfit = 31.80,
            confidence = 95.0,
            riskReward = 3.00,
            openedAt = 1_700_008_000_000L,
            closedAt = 1_700_011_600_000L,
            exitPrice = 31.80,
            exitReason = "قفل سود تلاقی تنکان/کیجون پس از 3R",
            pnlUsd = 15.00,
            positionOz = 25.0,
            fixedRiskUsd = 5.0,
            strategyId = "combo_1234",
            strategyTitleFa = "ترکیب ۴ کتاب (کتاب ۱+۲+۳+۴)",
            optimalRiskReward = 3.00,
        ),
        PaperTrade(
            id = "t4",
            symbol = "ETH/USDT",
            interval = Interval.M5,
            action = SignalAction.BUY,
            entry = 3100.0,
            stopLoss = 3080.0,
            takeProfit = 3150.0,
            confidence = 90.0,
            riskReward = 2.50,
            openedAt = 1_700_012_000_000L,
            closedAt = null,
            exitPrice = null,
            exitReason = null,
            pnlUsd = null,
            positionOz = 0.25,
            fixedRiskUsd = 5.0,
            strategyId = "combo_24",
            strategyTitleFa = "ترکیب دوگانه (کتاب ۲+۴)",
            optimalRiskReward = 2.50,
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
        assertEquals(27.50 / 5.00, report.profitFactor, 1e-4)
        assertEquals(66.67, report.winRatePct, 0.05)
        assertTrue(report.sqnScore > 0.0)
        assertTrue(report.sharpeRatio > 0.0)
        assertTrue(report.kellyCriterionPct > 0.0)
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
        assertTrue(csv.contains("Row,TradeID,Symbol"))
        assertTrue(csv.contains("XAU/USD"))
        assertTrue(csv.contains("BTC/USDT"))
        assertTrue(csv.contains("XAG/USD"))

        val json = JournalExportEngine.generateJsonContent(report)
        assertTrue(json.contains("\"schemaVersion\": \"3.9.11-institutional-tearsheet\""))
        assertTrue(json.contains("\"strategyLeaderboard\""))
        assertTrue(json.contains("\"symbolLeaderboard\""))
        assertTrue(json.contains("\"equityCurve\""))

        val html = JournalExportEngine.generateInteractiveHtmlTearSheet(report)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("filterRows()"))
        assertTrue(html.contains("XAU/USD"))
        assertTrue(html.contains("SQN"))
    }
}
