package com.aurum.edge.engine

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

    @Test fun `current seven-condition record is recognized without inventing an eighth`() {
        val current = paper.copy(entryConditions = conditions.take(7))
        assertTrue(ResearchEvidence.hasRecordedSignalEvidence(current))
        assertFalse(ResearchEvidence.hasRecordedSignalEvidence(current.copy(
            entryConditions = current.entryConditions.dropLast(1))))
    }

    @Test fun `manual older unverified and invalid news records never enter recorded signal group`() {
        assertTrue(ResearchEvidence.hasRecordedNineWay(paper))
        val manual = paper.copy(id = "manual", autoOpened = false, signalBarTime = null,
            newsEvidence = null, entryConditions = emptyList(), priceAction = null)
        assertFalse(ResearchEvidence.hasRecordedNineWay(manual))
        assertTrue(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = null)))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = news.copy(model = "deterministic-fallback"))))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = news.copy(calendarSource = null))))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(newsEvidence = news.copy(checkedAt = paper.openedAt + 1))))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(priceAction = null)))
        assertFalse(ResearchEvidence.hasRecordedNineWay(paper.copy(entryConditions = conditions.drop(3))))
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
}
