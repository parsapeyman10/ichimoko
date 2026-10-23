package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.domain.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAlertRules
import com.aurum.edge.core.PaperOpportunity
import com.aurum.edge.core.Signal
import com.aurum.edge.core.MarketState
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.PaperOpportunityStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaperOpportunityTest {
    private val now = 1_800_000_000_000L
    private val bar = now - Interval.M5.millis
    private val config = AppSettings(symbol = "GBP/USD", backgroundMonitor = true, notifyOnSignal = true,
        autoPaperTrading = false, accountBalance = 100.0)
    private val raw = Signal(SignalAction.BUY, 100.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = bar,
        ichimokuEntry = com.aurum.edge.core.IchimokuEntryEvidence(SignalAction.BUY, bar, bar, Interval.M5, 3),
        confluence = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD", "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی").map { ConfluenceItem(it, true, "fixture", scorePercent = 25) })
    private val signal get() = raw
    private val snapshot = MtfAnalyzer.Snapshot(Interval.M5,
        frames = listOf(MtfAnalyzer.FrameBias(Interval.M5, SignalAction.BUY, 80,
            3000.0, 2950.0, 2940.0, 2980.0, 60.0, 25.0, 0.2, "fixture")),
        bias = SignalAction.BUY, score = 1.0, alignment = 1.0,
        buyCount = 1, sellCount = 0, neutralCount = 0, veto = false,
        vetoReason = null, advisory = "fixture", barTime = bar, skippedFrames = emptyList())
    private val market get() = MarketState(symbol = "GBP/USD", interval = Interval.M5,
        candles = IctTestBars.readyAt(bar),
        lastPrice = 3000.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = signal)
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun qualifiedAlertIsIndependentOfAutoEntryButFailsClosedForEveryCheck() {
        assertNull(PaperAlertRules.blocker(market, config, emptyList(), snapshot, now))
        assertNotNull(PaperAlertRules.blocker(market, config.copy(notifyOnSignal = false), emptyList(), snapshot, now))
        assertNotNull(PaperAlertRules.blocker(market, config.copy(backgroundMonitor = false), emptyList(), snapshot, now))
        assertNull(PaperAlertRules.blocker(market, config, emptyList(), null, now))
        assertNull(PaperAlertRules.blocker(market, config, emptyList(), snapshot.copy(veto = true), now))
        assertNull(PaperAlertRules.blocker(market, config, emptyList(), snapshot.copy(barTime = bar - 1), now))
        assertNotNull(PaperAlertRules.blocker(market.copy(showingCachedData = true), config, emptyList(), snapshot, now))
        assertNotNull(PaperAlertRules.blocker(market.copy(feed = FeedStatus(FeedMode.OFFLINE, lastSuccessAt = now)),
            config, emptyList(), snapshot, now))
        assertNotNull(PaperAlertRules.blocker(market, config.copy(accountBalance = 5.0), emptyList(), snapshot, now))
        for (i in 0..3) {
            val fail = raw.copy(confluence = raw.confluence.mapIndexed { idx, item ->
                if (idx == i) item.copy(ok = false, status = com.aurum.edge.core.ConfluenceStatus.CONFLICT, scorePercent = 0) else item
            })
            assertNotNull("tech $i", PaperAlertRules.blocker(market.copy(signal = fail),
                config, emptyList(), snapshot, now))
        }
        assertNotNull(PaperAlertRules.blocker(market.copy(symbol = "XAG/USD"), config, emptyList(), snapshot, now))
    }

    @Test fun candidateDedupsAcrossRestartAndKeepsItsOwnHistoryOutOfTradeStatistics() = runBlocking {
        val root = Files.createTempDirectory("opportunities").toFile()
        val file = File(root, "opportunities.json")
        val journalFile = File(root, "journal.json")
        val ict = IctEntryRules.approvedEvidence(market, now)!!
        val opportunity = PaperOpportunity.from(signal, "GBP/USD", 3000.0,
            MtfSnapshotRecord.from(snapshot), ict, now)
        val store = PaperOpportunityStore(context, file)
        store.load()
        assertTrue(store.record(opportunity))
        assertFalse(store.record(opportunity))
        assertEquals(4, store.items.value.single().conditions.size)
        assertEquals(ict, store.items.value.single().priceAction)
        val journal = JournalStore(context, journalFile)
        journal.load()
        assertEquals(0, journal.stats().open)
        assertEquals(0, journal.stats().total)
        val reopened = PaperOpportunityStore(context, file).also { it.load() }
        assertFalse(reopened.record(opportunity))
        val trade = journal.open(signal, "GBP/USD", 3000.0, 100.0, 0.5,
            automatic = true, mtf = MtfSnapshotRecord.from(snapshot),
            priceAction = ict, observedAt = now)
        reopened.linkTrade(trade)
        assertEquals(trade.id, PaperOpportunityStore(context, file).also { it.load() }.items.value.single().paperTradeId)
        val persisted = JournalStore(context, journalFile).also { it.load() }.trades.value.single()
        assertEquals(4, persisted.entryConditions.size)
        assertEquals(ict, persisted.priceAction)
        assertTrue(trade.entryConditions.none { it.name == "خبر/تقویم · داده‌کاوی ژورنال" })
        assertEquals(1, journal.stats().open)
        assertEquals(0, journal.stats().total)
    }

    @Test fun damagedOpportunityFileIsNeverSilentlyOverwritten() = runBlocking {
        val file = File(Files.createTempDirectory("opportunity-corrupt").toFile(), "broken.json")
        file.writeText("{not-json}")
        val store = PaperOpportunityStore(context, file)
        try { store.load(); throw AssertionError("corrupt store loaded") } catch (_: IllegalStateException) { }
        assertNotNull(store.loadError.value)
        try { store.clear(); throw AssertionError("corrupt store overwritten") } catch (_: IllegalStateException) { }
        assertEquals("{not-json}", file.readText())
    }
}
