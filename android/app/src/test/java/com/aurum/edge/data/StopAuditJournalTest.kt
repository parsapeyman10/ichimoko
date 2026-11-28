package com.aurum.edge.data

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StopAuditJournalTest {
    @Test fun `stop hit audit records actual price and pnl without changing settlement`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "stop_audit_test.json")
        file.delete(); File(file.path + ".bak").delete(); File(file.path + ".protection").delete()
        val journal = JournalStore(context, file)
        val opened = 1_000_000L
        val trade = PaperTrade(id = "audit", symbol = "BTC/USDT", interval = Interval.M5,
            action = SignalAction.BUY, entry = 100.0, stopLoss = 90.0, initialStopLoss = 90.0,
            takeProfit = 130.0, confidence = 80.0, riskReward = 3.0, openedAt = opened,
            positionOz = 1.0, fixedRiskUsd = 10.0, netSettlementEnabled = true, settlementFeeUsd = 0.0)
        journal.addTrade(trade)
        val at = opened + 20_000L
        val result = journal.onLiveTick("BTC/USDT", PriceTick(88.0, at, provider = "test"), nowMillis = at + 100)
        assertEquals(1, result.size)
        val saved = journal.trades.value.first { it.id == trade.id }
        assertEquals(-12.0, saved.pnlUsd!!, 0.001)
        val event = saved.stopObservations.single { it.kind == "STOP_HIT" }
        assertEquals(90.0, event.actualStop!!, 0.001)
        assertEquals(-10.0, event.pnlAtStopUsd!!, 0.001)
        assertEquals(-12.0, event.actualPnlUsd!!, 0.001)
        assertEquals(at, event.quoteAt)
        assertEquals(at + 100, event.recordedAt)
        val restored = JournalStore(context, file).trades.value.first { it.id == trade.id }
        assertEquals(event, restored.stopObservations.single())
    }
}
