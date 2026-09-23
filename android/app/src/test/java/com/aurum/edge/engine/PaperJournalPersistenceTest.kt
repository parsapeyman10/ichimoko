package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.JournalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** File-backed Android integration: open -> real-price stop/target -> restart -> saved journal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaperJournalPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun journalFile() = File(Files.createTempDirectory("journal-test").toFile(), "paper_journal.json")
    private val signal = Signal(SignalAction.BUY, 89.0, entry = 3000.0, stopLoss = 2995.0,
        takeProfit = 3010.0, interval = Interval.M5, barTime = 1_800_000_000_000L)
    private val news = PaperNewsRecord("test-model", "BUY", 90.0, 1_800_000_000_100L,
        listOf(PaperNewsEvidence("id", "Publisher", "Gold headline", "https://publisher.example/news", 1_800_000_000_000L)))

    @Test fun autoOpenSettlementAndReloadKeepSameTradeAndEvidence() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, newsEvidence = news)
        assertEquals(1, store.stats().open)
        assertTrue(file.exists())
        val restarted = JournalStore(context, file)
        restarted.load()
        assertEquals(opened.id, restarted.trades.value.single().id)
        // A bar before the paper entry or for another market cannot settle this position.
        val after = opened.openedAt + 1_000
        val touchedTarget = Candle(after, 3000.0, 3011.0, 2999.0, 3009.0)
        restarted.settle(touchedTarget.copy(time = opened.openedAt - 1), "XAU/USD", after)
        restarted.settle(touchedTarget, "AAPL", after + 1_000)
        assertTrue(restarted.trades.value.single().isOpen)
        restarted.settle(touchedTarget, "XAU/USD", after + 1_000)
        assertEquals(0, restarted.stats().open)
        assertEquals(1, restarted.stats().total)
        val completed = JournalStore(context, file)
        completed.load()
        val closed = completed.trades.value.single()
        assertEquals(opened.id, closed.id)
        assertFalse(closed.isOpen)
        assertEquals(opened.positionOz * (signal.takeProfit!! - opened.entry), closed.pnlUsd!!, 0.01)
        assertEquals(news, closed.newsEvidence)
        assertTrue(closed.autoOpened)
        assertEquals(signal.barTime, closed.signalBarTime)
        // Even after SL/TP and a process restart the same candle cannot auto-open again.
        try {
            completed.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
                automatic = true, newsEvidence = news)
            throw AssertionError("double auto entry in one bar")
        } catch (_: IllegalArgumentException) { /* must reject */ }
        assertEquals(1, completed.trades.value.size)
    }

    @Test fun rejectedDuplicateCloseNeverClaimsSuccessOrErasesRecord() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5, manual = true)
        val closed = store.close(opened.id, 2999.0, "بستن دستی")
        assertEquals(1, store.stats().total)
        assertEquals(opened.id, closed.id)
        try {
            store.close(opened.id, 2999.0, "تکراری")
            throw AssertionError("closing an already closed position must fail")
        } catch (_: IllegalArgumentException) { /* must reject */ }
        assertEquals(closed, JournalStore(context, file).also { it.load() }.trades.value.single())
    }

    @Test fun damagedOnDiskJournalIsKeptAndCannotBeOverwrittenByAnEmptyList() = runBlocking {
        val file = journalFile()
        val corrupt = "{invalid,do not erase}"
        file.writeText(corrupt)
        val store = JournalStore(context, file)
        try {
            store.load()
            throw AssertionError("corrupt journal was silently loaded as empty")
        } catch (_: IllegalStateException) { /* surface failure */ }
        assertTrue(store.loadError.value != null)
        try {
            store.clear()
            throw AssertionError("corrupt file was silently cleared")
        } catch (_: IllegalStateException) { /* preserve copy */ }
        assertEquals(corrupt, file.readText())
    }
}
