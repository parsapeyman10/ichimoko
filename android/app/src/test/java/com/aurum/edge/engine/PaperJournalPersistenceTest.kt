package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.domain.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.MtfFrameRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.JournalStore
import com.aurum.edge.domain.MarketState
import com.aurum.edge.data.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val signal = Signal(SignalAction.BUY, 100.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = 1_800_000_000_000L - Interval.M5.millis,
        ichimokuEntry = com.aurum.edge.core.IchimokuEntryEvidence(SignalAction.BUY, 1_800_000_000_000L - Interval.M5.millis, 1_800_000_000_000L - Interval.M5.millis, Interval.M5, 3),
        confluence = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD", "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی").map { ConfluenceItem(it, true, "fixture", scorePercent = 25) } +
            ConfluenceItem("خبر/تقویم · داده‌کاوی ژورنال", true, "خبر مدل با شاهد"))
    private val alsSignal by lazy { asConfirmedAls5TestSignal(signal) }
    private fun mtf(at: Long) = MtfSnapshotRecord("5m", "BUY", 1.0, 1, 0, 0, false, "fixture", at,
        frames = listOf(MtfFrameRecord("5m", "BUY", 80, "fixture", 1.0)))
    private fun ictFor(s: Signal, now: Long) = IctEntryRules.approvedEvidence(
        MarketState(symbol = "XAU/USD", interval = Interval.M5,
            candles = IctTestBars.readyAt(s.barTime), lastPrice = 3000.0,
            feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = s), now)!!
    private val news = PaperNewsRecord("test-model", "BUY", 90.0, 1_800_000_000_100L,
        listOf(PaperNewsEvidence("id", "Publisher", "Gold headline", "https://publisher.example/news", 1_800_000_000_000L)),
        calendarSource = "https://nfs.faireconomy.media/ff_calendar_thisweek.json", calendarCheckedAt = 1_800_000_000_100L)

    @Test fun sourceDisagreementBlocksNewEntriesButNeverBlocksManagementOfOpenPositions() = runBlocking {
        val gate = com.aurum.edge.data.SourceIntegrity.shared
        val channel = com.aurum.edge.data.SourceIntegrity.Channel.HISTORY
        gate.resetForTests()
        try {
            val at = 1_800_000_000_000L
            val store = JournalStore(context, journalFile()).also { it.load() }
            store.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5, automatic = true, observedAt = at)
            gate.reject("XAU/USD", channel, "conflicting sources")
            for (manual in listOf(false, true)) {
                val failure = runCatching { store.open(if (manual) signal else alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
                    manual = manual, automatic = !manual, observedAt = at) }.exceptionOrNull()
                assertEquals("conflicting sources", failure?.message)
            }
            store.settleTick("XAU/USD", 2990.0, at + 1_000)
            assertFalse(store.trades.value.single().isOpen)
            val recovered = gate.accept("XAU/USD", channel, gate.begin("XAU/USD", channel), 2)!!
            val fresh = JournalStore(context, journalFile()).also { it.load() }
            val oldPreview = runCatching { fresh.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
                automatic = true, observedAt = at, sourceIntegrityRevision = 0) }.exceptionOrNull()
            assertTrue(oldPreview?.message.orEmpty().contains("دوباره"))
            assertTrue(fresh.trades.value.isEmpty())
            fresh.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5, automatic = true,
                observedAt = at, sourceIntegrityRevision = recovered)
            assertEquals(1, fresh.trades.value.size)
        } finally { gate.resetForTests() }
    }

    @Test fun onlyM5MayOpenNewManualOrAutomaticPaperPositions() = runBlocking {
        val store = JournalStore(context, journalFile()).also { it.load() }
        for (interval in listOf(Interval.M1, Interval.M3, Interval.M15, Interval.H1)) {
            // Even a forged matching setup and full four-layer evidence cannot bypass
            // the authoritative journal constraint. Historical trades are not rewritten.
            val otherwiseValid = signal.copy(interval = interval,
                ichimokuEntry = signal.ichimokuEntry!!.copy(interval = interval))
            for (automatic in listOf(false, true)) {
                val failure = runCatching { store.open(otherwiseValid, "XAU/USD", 3000.0,
                    50_000.0, 0.5, manual = !automatic, automatic = automatic,
                    observedAt = 1_800_000_000_000L) }.exceptionOrNull()
                assertTrue("$interval: ${failure?.message}", failure is IllegalArgumentException &&
                    failure.message.orEmpty().contains("۵ دقیقه"))
            }
        }
        assertTrue(store.trades.value.isEmpty())
    }

    @Test fun quoteObservedBeforeEntryCannotSettleNewPosition() = runBlocking {
        val at = 1_800_000_000_000L
        val store = JournalStore(context, journalFile()).also { it.load() }
        val opened = store.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, observedAt = at)
        // A cached exchange tick seen before the paper fill may still be fresh. It is
        // not a post-entry price event, even if the polling loop repeats it later.
        store.settleTick("XAU/USD", 2990.0, at - 1_000L)
        assertTrue(store.trades.value.single().isOpen)
        store.settleTick("XAU/USD", 2990.0, at + 1_000L)
        assertFalse(store.trades.value.single().isOpen)
        assertEquals(opened.id, store.trades.value.single().id)
    }

    @Test fun delayedWorkerCannotSettleOrFlagANewerPositionOnTheSameSymbol() = runBlocking {
        val at = 1_800_000_000_000L
        val store = JournalStore(context, journalFile()).also { it.load() }
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            manual = true, observedAt = at)
        assertTrue(store.settleTick("XAU/USD", 2990.0, at + 1_000L,
            expectedOpenedAt = at - 1_000L).isEmpty())
        store.noteTickCoverageGap("XAU/USD", at + 2_000L, expectedOpenedAt = at - 1_000L)
        assertTrue(store.trades.value.single().isOpen)
        assertNull(store.trades.value.single().tickCoverageGapAt)
        store.noteTickCoverageGap("XAU/USD", at + 3_000L, expectedOpenedAt = at)
        assertEquals(at + 3_000L, store.trades.value.single().tickCoverageGapAt)
        assertEquals(opened.id, store.trades.value.single().id)
        assertEquals(1, store.settleTick("XAU/USD", 2990.0, at + 4_000L,
            expectedOpenedAt = at).size)
    }

    @Test fun usdJpyPaperSettlesInDollarsThroughItsOwnExitPrice() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        // Manual long USD/JPY: entry 150, stop 148, target 153 -> 750 units, 10 USD risk.
        val manual = signal.copy(action = SignalAction.BUY, entry = 150.0,
            stopLoss = 148.0, takeProfit = 153.0, interval = Interval.M5)
        val opened = store.open(manual, "USD/JPY", 150.0, 1000.0, 0.5, manual = true,
            observedAt = 1_800_000_000_000L)
        assertTrue(opened.positionOz > 0.0)
        assertEquals("USD", opened.unit)
        assertTrue(opened.riskUsd + opened.effectiveCommissionUsd + opened.effectiveSpreadCostUsd <= 5.001)
        val tick = opened.openedAt + 1000L
        // Reference target does not auto-close; explicit manual exit converts quote P/L to USD.
        store.settle(Candle(tick, 152.9, 153.5, 152.8, 153.2), "USD/JPY", tick)
        assertTrue(store.trades.value.single().isOpen)
        store.close(opened.id, 153.0, "test exit")
        val closed = store.trades.value.single()
        assertFalse(closed.isOpen)
        assertEquals(opened.positionOz * 3.0 / 153.0 - opened.effectiveCommissionUsd -
            opened.effectiveSpreadCostUsd, closed.pnlUsd!!, 0.01)
    }

    @Test fun autoOpenSettlementAndReloadKeepSameTradeAndEvidence() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val evidence = ictFor(signal, signal.barTime + Interval.M5.millis)
        val opened = store.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, mtf = mtf(signal.barTime), priceAction = evidence,
            observedAt = 1_800_000_000_000L)
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
        assertTrue(restarted.trades.value.single().isOpen)
        val trailed = restarted.trades.value.single().stopLoss
        restarted.settleTick("XAU/USD", trailed - 0.1, after + 2_000)
        assertEquals(0, restarted.stats().open)
        assertEquals(1, restarted.stats().total)
        val completed = JournalStore(context, file)
        completed.load()
        val closed = completed.trades.value.single()
        assertEquals(opened.id, closed.id)
        assertFalse(closed.isOpen)
        assertEquals(opened.positionOz * (closed.exitPrice!! - opened.entry) -
            opened.effectiveCommissionUsd - opened.effectiveSpreadCostUsd, closed.pnlUsd!!, 0.01)
        assertNull(closed.newsEvidence) // news layer removed: new entries store none
        assertEquals(evidence, closed.priceAction)
        assertTrue(closed.autoOpened)
        assertEquals(signal.barTime, closed.signalBarTime)
        // Even after SL/TP and a process restart the same candle cannot auto-open again.
        try {
            completed.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
                automatic = true, mtf = mtf(signal.barTime), priceAction = evidence,
            observedAt = 1_800_000_000_000L)
            throw AssertionError("double auto entry in one bar")
        } catch (_: IllegalArgumentException) { /* must reject */ }
        assertEquals(1, completed.trades.value.size)
    }

    @Test fun optionalIctSnapshotCanBeAbsentButFourLayersPersist() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file).also { it.load() }
        val opened = store.open(alsSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, mtf = mtf(signal.barTime),
            observedAt = 1_800_000_000_000L)
        assertTrue(opened.autoOpened)
        assertNull(opened.priceAction)
        assertEquals(4, opened.entryConditions.size)
        // A pre-upgrade JSON without the new field must still open, without invented evidence.
        val encoded = file.readText()
        assertTrue(encoded.contains(",\"priceAction\":null"))
        val legacy = encoded.replace(",\"priceAction\":null", "")
        file.writeText(legacy)
        assertNull(JournalStore(context, file).also { it.load() }.trades.value.single().priceAction)
    }

    @Test fun rejectedDuplicateCloseNeverClaimsSuccessOrErasesRecord() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5, manual = true,
            observedAt = 1_800_000_000_000L)
        val closed = store.close(opened.id, 2999.0, "بستن دستی")
        assertEquals(1, store.stats().total)
        assertEquals(opened.id, closed.id)
        try {
            store.close(opened.id, 2999.0, "تکراری")
            throw AssertionError("closing an already closed position must fail")
        } catch (_: IllegalArgumentException) { /* must reject */ }
        assertEquals(closed, JournalStore(context, file).also { it.load() }.trades.value.single())
    }

    @Test fun journalSettlementAdjustsSettingsBalanceContinuously() = runBlocking {
        val file = journalFile()
        val settingsStore = SettingsStore(context)
        settingsStore.update { it.copy(accountBalance = 50_000.0) }
        val store = JournalStore(context, file, settingsStore = settingsStore)
        store.load()
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5, manual = true,
            observedAt = 1_800_000_000_000L)
        // Close with a loss
        store.close(opened.id, 2990.0, "بستن دستی با ضرر")
        val currentBal = settingsStore.read().accountBalance
        assertTrue("balance should decrease after a loss (was 50000, now $currentBal)", currentBal < 50_000.0)
    }

    @Test fun liveTickSettlementClosesTradeAtTakeProfitAndStopLoss() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()

        // 1. Long position: Entry 3000, SL 2990, TP 3020
        val longSignal = signal.copy(action = SignalAction.BUY, entry = 3000.0, stopLoss = 2990.0, takeProfit = 3020.0)
        val longTrade = store.open(longSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            manual = true, observedAt = 1_800_000_000_000L)
        assertTrue(longTrade.isOpen)

        // The 2R reference target raises the trailing stop; it does not close the trade.
        assertTrue(store.settleTick("XAU/USD", 3021.0, longTrade.openedAt + 1000L).isEmpty())
        assertEquals(3011.0, store.trades.value.single().stopLoss, 1e-6)
        val closedList = store.settleTick("XAU/USD", 3010.0, longTrade.openedAt + 2000L)
        assertEquals(1, closedList.size)
        val closedTrade = closedList.single()
        assertFalse(closedTrade.isOpen)
        assertEquals(3011.0, closedTrade.exitPrice!!, 1e-6)
        assertTrue(closedTrade.exitReason?.contains("حد ضرر") == true)
        assertTrue(closedTrade.pnlUsd!! > 0.0)

        // 2. Short position: Entry 3000, SL 3010, TP 2980
        val shortSignal = signal.copy(action = SignalAction.SELL, entry = 3000.0,
            stopLoss = 3010.0, takeProfit = 2980.0, barTime = signal.barTime + Interval.M5.millis,
            ichimokuEntry = signal.ichimokuEntry!!.copy(side = SignalAction.SELL,
                barTime = signal.barTime + Interval.M5.millis, crossTime = signal.barTime + Interval.M5.millis))
        val shortTrade = store.open(shortSignal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            manual = true, observedAt = 1_800_000_000_000L + Interval.M5.millis)
        assertTrue(shortTrade.isOpen)

        // Price rises to 3011 (SL hit!)
        val slClosedList = store.settleTick("XAU/USD", 3011.0, shortTrade.openedAt + 1000L)
        assertEquals(1, slClosedList.size)
        val slClosedTrade = slClosedList.single()
        assertFalse(slClosedTrade.isOpen)
        assertEquals(3010.0, slClosedTrade.exitPrice!!, 1e-6)
        assertTrue(slClosedTrade.exitReason?.contains("حد ضرر") == true)
        assertTrue(slClosedTrade.pnlUsd!! < 0.0)
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
