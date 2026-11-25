package com.aurum.edge.core

import org.junit.Assert.*
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Test

class TrendStructureEvidenceTest {
    @Test fun `text reports numeric line lag and does not invent compression`() {
        val v = TrendStructureEvidence.Values(3.0, 1.0, 2.0, 0.1, 0.0, false, true, 0.5, 1.2, null)
        val text = v.textFa()
        assertTrue(text.contains("کیجون فلت"))
        assertTrue(text.contains("2.00"))
        assertTrue(text.contains("فشردگی نامعلوم"))
        assertTrue(text.contains("ثابت نمی‌کنند"))
    }
    @Test fun `recorded compression distance is explicitly included`() {
        val v = TrendStructureEvidence.Values(0.0, 0.0, 0.0, 0.0, 0.0, true, true, 0.2, 0.3, 1.75)
        assertTrue(v.textFa().contains("1.75 ATR از سطح شکست فشردگی"))
    }
    @Test fun `extraction is directional and refuses mismatched snapshot`() {
        val snap = IchimokuBarSnapshot(
            time = 1000, open = 110.0, high = 112.0, low = 108.0, close = 110.0,
            tenkan = 108.0, kijun = 106.0, prevTenkan = 107.0, prevKijun = 106.0,
            currentSpanA = 104.0, currentSpanB = 102.0, futureSpanA = 105.0, futureSpanB = 101.0,
            prevFutureSpanA = 104.0, prevFutureSpanB = 101.0, atr14 = 2.0,
            chikouBullishOpenSpace = true, chikouBearishOpenSpace = false, chikouResistancePeak = 110.0,
            chikouSupportTrough = 100.0, isCurrentSpanBFlat = true, isKijunFlat = true,
            isTenkanFlat = false, flatEquilibriumLevels = emptyList(), closeChange52Atr = 5.0,
            tenkanSlopeAtrPerBar = 0.5, kijunSlopeAtrPerBar = 0.0,
        )
        val report = StrategyEvaluationReport(strategyId = "x", strategyTitleFa = "x", strategyBadgeFa = "x",
            macroInterval = Interval.H4, strategyInterval = Interval.H1, triggerInterval = Interval.M15,
            readiness = StrategyReadinessState.SIGNAL_BUY, action = SignalAction.BUY, triggerType = StrategyTriggerType.NONE,
            confidence = 70.0, livePrice = 110.0, suggestedEntry = null, suggestedStopLoss = null,
            targetV = null, targetN = null, targetE = null, primaryTakeProfit = null, riskReward = null,
            macroSnapshot = null, strategySnapshot = null, triggerSnapshot = snap, waveProjection = null,
            conditions = emptyList(), summaryFa = "", triggerBarTime = 1000)
        val buy = TrendStructureEvidence.extract(report)!!
        assertEquals(5.0, buy.directional52Atr, 0.0)
        assertEquals(2.0, buy.kijunBehindAtr, 0.0)
        assertEquals(2.0, buy.cloudProjectedAtr, 0.0)
        assertNull(buy.distanceFromCompressionAtr)
        val sell = TrendStructureEvidence.extract(report.copy(action = SignalAction.SELL))!!
        assertEquals(-5.0, sell.directional52Atr, 0.0)
        assertEquals(-2.0, sell.kijunBehindAtr, 0.0)
        assertNull(TrendStructureEvidence.extract(report.copy(triggerBarTime = 1001)))
        assertNull(TrendStructureEvidence.extract(report.copy(triggerSnapshot = snap.copy(atr14 = 0.0))))
    }

}
