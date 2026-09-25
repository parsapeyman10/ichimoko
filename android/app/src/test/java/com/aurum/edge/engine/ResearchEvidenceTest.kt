package com.aurum.edge.engine

import com.aurum.edge.core.BacktestRecord
import com.aurum.edge.core.Candle
import com.aurum.edge.core.IctPriceActionRecord
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperConditionRecord
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.FOREX_CALENDAR_SOURCE_URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-only candles/trades never become Android data or a live AI verdict. */
class ResearchEvidenceTest {
    private val barTime = 1_700_000_000_000L
    private val news = PaperNewsRecord("test-model", "BUY", 91.0, barTime + 1_000L,
        listOf(PaperNewsEvidence("one", "Publisher", "fixture", "https://publisher.example/story", barTime)),
        FOREX_CALENDAR_SOURCE_URL, barTime + 1_000L)
    private val ict = IctPriceActionRecord(symbol = "XAU/USD", interval = Interval.M5,
        barTime = barTime, action = SignalAction.BUY, feedProvider = "test", checkedAt = barTime + 1_000L,
        nyDate = "2026-01-01", nySession = "LONDON", nyTime = "03:00", support = 95.0,
        resistance = 105.0, atr = 2.0, supportTouches = 2, resistanceTouches = 2,
        levelsConfirmedAt = barTime - 4 * Interval.M5.millis, sweepAt = barTime - 3 * Interval.M5.millis,
        mssAt = barTime - 2 * Interval.M5.millis, fvgAt = barTime - Interval.M5.millis,
        fvgLow = 98.0, fvgHigh = 99.0, orderBlockLow = null, orderBlockHigh = null,
        retestAt = barTime, quote = 100.0, stop = 95.0, target = 105.0,
        stopBoundary = 95.0, opposingLevel = 105.0, rewardRisk = 2.0)
    private val mtf = MtfSnapshotRecord("5m", "BUY", 0.8, 3, 0, 0, false, "fixture", barTime)
    private val conditions = (1..8).map { PaperConditionRecord("فنی $it", "CONFIRMED", "fixture") } +
        PaperConditionRecord(NewsConfluence.NEWS_LABEL, "CONFIRMED", "fixture")
    private val paper = PaperTrade("paper", "XAU/USD", Interval.M5, SignalAction.BUY,
        entry = 100.0, stopLoss = 95.0, takeProfit = 110.0, confidence = 91.0,
        riskReward = 2.0, openedAt = barTime + 1_000L, closedAt = barTime + 600_000L,
        exitPrice = 105.0, pnlUsd = 10.0, positionOz = 2.0, positionUnit = "oz",
        autoOpened = true, signalBarTime = barTime, mtf = mtf, newsEvidence = news,
        entryConditions = conditions, priceAction = ict)

    @Test fun `manual older unverified and RSS-only records never enter recorded nine-way group`() {
        assertTrue(ResearchEvidence.hasRecordedNineWay(paper))
        val manual = paper.copy(id = "manual", autoOpened = false, signalBarTime = null,
            newsEvidence = null, entryConditions = emptyList(), priceAction = null)
        assertFalse(ResearchEvidence.hasRecordedNineWay(manual))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = null)))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = news.copy(model = "deterministic-fallback"))))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = news.copy(calendarSource = null))))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(priceAction = null)))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(entryConditions = conditions.dropLast(1))))
        assertNull(ResearchEvidence.paperCostWhatIf(listOf(manual), 0.30, 0.05))
    }

    @Test fun `paper cost stress is hypothetical excludes manual and never changes stored pnl`() {
        val manual = paper.copy(id = "manual", signalBarTime = null, newsEvidence = null,
            entryConditions = emptyList(), priceAction = null, pnlUsd = 10_000.0)
        val preview = ResearchEvidence.paperCostWhatIf(listOf(paper, manual, paper.copy(closedAt = null)),
            spread = 0.30, commissionPerOz = 0.05)!!
        assertEquals(1, preview.trades)
        assertEquals(10.0, preview.rawPnlUsd, 1e-9)
        assertEquals(0.8, preview.estimatedCostUsd, 1e-9)
        assertEquals(9.2, preview.afterAssumedCostUsd, 1e-9)
        assertEquals(8.4, preview.afterDoubleCostUsd, 1e-9)
        assertEquals(10.0, paper.pnlUsd!!, 1e-9)
        assertNull(ResearchEvidence.paperCostWhatIf(listOf(paper), Double.NaN, 0.05))
    }

    private fun baseline(): Backtester.Result {
        val candles = (0..212).map { i -> Candle(barTime + i * Interval.M5.millis,
            100.0, 100.4, 99.6, 100.0) }
        val empty = Backtester.runWithDecisions(candles, Interval.M5,
            { Signal(SignalAction.NO_TRADE, 0.0) })
        val win = Backtester.Trade(SignalAction.BUY, barTime, 100.0, barTime + Interval.M5.millis,
            101.0, 95.0, 105.0, "fixture", 1.0, 1.0, 0.2, 0.1)
        val loss = win.copy(exit = 99.0, pnlUsd = -1.0, rMultiple = -0.2)
        return empty.copy(trades = List(20) { win } + List(10) { loss },
            wins = 20, losses = 10, netPnl = 10.0, finalBalance = empty.initialBalance + 10.0,
            profitFactor = 2.0)
    }

    @Test fun `short sample bad out of sample fees and zero fees cannot imply profitable`() {
        val base = baseline()
        val stressed = base.copy(spreadPrice = base.spreadPrice * 2,
            commissionPerOz = base.commissionPerOz * 2)
        assertEquals(EvidenceGrade.LIMITED,
            ResearchEvidence.outOfSample(base.copy(trades = base.trades.take(2)), stressed).grade)
        assertEquals(EvidenceGrade.UNFAVORABLE,
            ResearchEvidence.outOfSample(base.copy(netPnl = -1.0, profitFactor = 0.8), stressed).grade)
        val lossHeavy = stressed.copy(trades = List(10) { stressed.trades[0] } +
            List(20) { stressed.trades.last() }, netPnl = -10.0, profitFactor = 0.5)
        assertEquals(EvidenceGrade.COST_SENSITIVE,
            ResearchEvidence.outOfSample(base, lossHeavy).grade)
        assertEquals(EvidenceGrade.LIMITED,
            ResearchEvidence.outOfSample(base.copy(spreadPrice = 0.0, commissionPerOz = 0.0),
                stressed.copy(spreadPrice = 0.0, commissionPerOz = 0.0)).grade)
        assertEquals(EvidenceGrade.NO_DATA,
            ResearchEvidence.outOfSample(base, stressed.copy(fromTime = stressed.fromTime + 1)).grade)
        assertEquals(EvidenceGrade.LIMITED,
            ResearchEvidence.outOfSample(base.copy(unresolvedGap = 1), stressed).grade)
        assertEquals(EvidenceGrade.PRELIMINARY, ResearchEvidence.outOfSample(base, stressed).grade)
        assertEquals(EvidenceGrade.LIMITED, ResearchEvidence.inSample(base).grade)
        assertTrue(ResearchEvidence.outOfSample(base, stressed).title.contains("تاریخی"))
    }

    @Test fun `legacy JSON defaults cannot resurrect old optimistic verdict and new record retains stress`() {
        val base = baseline()
        val stressed = base.copy(spreadPrice = base.spreadPrice * 2,
            commissionPerOz = base.commissionPerOz * 2)
        val current = WalkForwardRecord("5m", base.bars, base.fromTime, 210, "ثبت قدیمی سودده است",
            barTime, BacktestRecord.from(base), BacktestRecord.from(base), BacktestRecord.from(stressed))
        val json = Json { encodeDefaults = false }
        val saved = json.decodeFromString<WalkForwardRecord>(json.encodeToString(current))
        assertEquals(Backtester.EXECUTION_MODEL, saved.outOfSample.executionModel)
        assertEquals(2 * base.spreadPrice, saved.costStressOutOfSample!!.spreadPrice, 1e-9)
        assertEquals(EvidenceGrade.PRELIMINARY, ResearchEvidence.stored(saved).grade)

        val legacy = current.copy(inSample = current.inSample.copy(executionModel = "LEGACY_CLOSE_FILL"),
            outOfSample = current.outOfSample.copy(executionModel = "LEGACY_CLOSE_FILL"),
            costStressOutOfSample = null)
        val legacyJson = json.encodeToString(legacy)
        assertFalse(legacyJson.contains("costStressOutOfSample"))
        assertFalse(legacyJson.contains("executionModel")) // default is old execution model
        val loaded = json.decodeFromString<WalkForwardRecord>(legacyJson)
        assertEquals(EvidenceGrade.NO_DATA, ResearchEvidence.stored(loaded).grade)
        assertFalse(ResearchEvidence.stored(loaded).title.contains("سودده"))
        assertEquals(EvidenceGrade.NO_DATA,
            ResearchEvidence.stored(current.copy(outOfSample = current.outOfSample.copy(netPnl = 999.0))).grade)
    }
}
