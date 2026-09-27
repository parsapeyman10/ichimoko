package com.aurum.edge.engine

import com.aurum.edge.core.BacktestRecord
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.WalkForwardRecord
import com.aurum.edge.data.JournalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportPersistenceTest {
    @Test fun `report is written before showing stored and a damaged copy is never erased`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val reportsFile = File(context.filesDir, "walk_forward_reports.json")
        reportsFile.delete()
        File(reportsFile.path + ".bak").delete()
        val candles = (0..211).map { i -> Candle(1_700_000_000_000L + i * Interval.M5.millis,
            100.0, 100.4, 99.6, 100.0) }
        val result = Backtester.runWithDecisions(candles, Interval.M5,
            { Signal(SignalAction.NO_TRADE, 0.0) })
        val base = BacktestRecord.from(result)
        val stress = BacktestRecord.from(result.copy(spreadPrice = result.spreadPrice * 2,
            commissionPerOz = result.commissionPerOz * 2))
        val record = WalkForwardRecord(Interval.M5.label, result.bars, result.fromTime, 210,
            "کم‌نمونه", System.currentTimeMillis(), base, base, stress)
        val store = JournalStore(context)
        store.loadReports()
        store.saveReport(record)
        assertEquals(listOf(record), store.reports.value)
        assertTrue(reportsFile.exists())
        val restarted = JournalStore(context)
        restarted.loadReports()
        assertEquals(listOf(record), restarted.reports.value)
        assertEquals(EvidenceGrade.NO_DATA, ResearchEvidence.stored(record).grade) // zero closed trades

        reportsFile.writeText("{damaged; retain original}")
        val damaged = JournalStore(context)
        assertTrue(runCatching { damaged.loadReports() }.isFailure)
        assertNotNull(damaged.reportError.value)
        assertTrue(runCatching { damaged.saveReport(record) }.isFailure)
        assertEquals("{damaged; retain original}", reportsFile.readText())
        assertTrue(damaged.reports.value.isEmpty()) // error, NOT 'zero recorded reports' in UI
    }
}
