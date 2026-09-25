package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.MtfFrameRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.parseWebNews
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
    private val signal = Signal(SignalAction.BUY, 89.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = 1_800_000_000_000L,
        confluence = (1..8).map { ConfluenceItem("فنی $it", true, "fixture") } +
            ConfluenceItem(NewsConfluence.NEWS_LABEL, true, "خبر مدل با شاهد"))
    private fun mtf(at: Long) = MtfSnapshotRecord("5m", "BUY", 1.0, 1, 0, 0, false, "fixture", at,
        frames = listOf(MtfFrameRecord("5m", "BUY", 80, "fixture", 1.0)))
    private fun ictFor(s: Signal, now: Long) = IctEntryRules.approvedEvidence(
        MarketState(symbol = "XAU/USD", interval = Interval.M5,
            candles = IctTestBars.readyAt(s.barTime), lastPrice = 3000.0,
            feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = s), now)!!
    private val news = PaperNewsRecord("test-model", "BUY", 90.0, 1_800_000_000_100L,
        listOf(PaperNewsEvidence("id", "Publisher", "Gold headline", "https://publisher.example/news", 1_800_000_000_000L)),
        calendarSource = "https://nfs.faireconomy.media/ff_calendar_thisweek.json", calendarCheckedAt = 1_800_000_000_100L)

    @Test fun backendJsonEightTechnicalChecksNinthAiPaperOpenTickCloseAndReload() = runBlocking {
        val now = 1_800_000_000_000L
        val observed = Instant.ofEpochMilli(now)
        val articleTime = Instant.ofEpochMilli(now - 60_000)
        val json = """{"status":{"configured":true,"state":"online","sources":[
            {"name":"Publisher","state":"online","feed":"https://publisher.example/rss"},
            {"name":"Forex Factory","state":"online","feed":"https://nfs.faireconomy.media/ff_calendar_thisweek.json"}]},
            "calendar":{"status":"online","source":"https://nfs.faireconomy.media/ff_calendar_thisweek.json",
                "checked_at":"$observed","events":[{"country":"USD"}],"guard":{"state":"CLEAR"}},
            "articles":[{"id":"verified","source":"Publisher","headline":"Gold dollar update",
                "published_at":"$articleTime","url":"https://publisher.example/news"}],
            "guard":{"state":"CLEAR"},"checked_at":"$observed",
            "ai_confluence":{"status":"AVAILABLE","symbol":"XAU/USD","direction":"BUY",
                "confidence":90,"model":"test-model","checked_at":"$observed",
                "evidence_ids":["verified"]}}"""
        val parsed = parseWebNews(Json.parseToJsonElement(json) as JsonObject, now)
        val bar = now - Interval.M5.millis
        val technical = signal.copy(barTime = bar, stopLoss = 2994.5, takeProfit = 3009.0,
            confluence = (1..8).map { ConfluenceItem("فنی $it", true, "fixture") })
        val verified = NewsConfluence.apply(technical, "XAU/USD", parsed, now)!!
        val current = MarketState(symbol = "XAU/USD", interval = Interval.M5,
            candles = IctTestBars.readyAt(bar),
            lastPrice = 3000.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now), signal = verified)
        val config = AppSettings(backgroundMonitor = true, autoPaperTrading = true, workspaceId = "forex")
        assertEquals(SignalAction.BUY, verified.action)
        assertEquals(9, verified.confluence.size)
        assertNull(PaperAutoRules.blocker(current, config, parsed, now))
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val evidence = IctEntryRules.approvedEvidence(current, now)!!
        val opened = store.open(verified, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, mtf = mtf(bar),
            newsEvidence = NewsConfluence.record(parsed), priceAction = evidence)
        val tick = opened.openedAt + 1000L
        store.settle(Candle(tick, 3010.0, 3011.0, 3010.0, 3011.0), "XAU/USD", tick)
        val restored = JournalStore(context, file).also { it.load() }
        assertEquals(1, restored.stats().total)
        assertEquals(opened.id, restored.trades.value.single().id)
        assertEquals("verified", restored.trades.value.single().newsEvidence!!.evidence.single().id)
        assertEquals(evidence, restored.trades.value.single().priceAction)
        assertFalse(restored.trades.value.single().isOpen)
    }

    @Test fun autoOpenSettlementAndReloadKeepSameTradeAndEvidence() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file)
        store.load()
        val evidence = ictFor(signal, signal.barTime + Interval.M5.millis)
        val opened = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, mtf = mtf(signal.barTime), newsEvidence = news, priceAction = evidence)
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
        assertEquals(evidence, closed.priceAction)
        assertTrue(closed.autoOpened)
        assertEquals(signal.barTime, closed.signalBarTime)
        // Even after SL/TP and a process restart the same candle cannot auto-open again.
        try {
            completed.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
                automatic = true, mtf = mtf(signal.barTime), newsEvidence = news, priceAction = evidence)
            throw AssertionError("double auto entry in one bar")
        } catch (_: IllegalArgumentException) { /* must reject */ }
        assertEquals(1, completed.trades.value.size)
    }

    @Test fun signalBasedEntryWithoutFreshIctEvidenceCannotWriteAndManualIsMarkedUnverified() = runBlocking {
        val file = journalFile()
        val store = JournalStore(context, file).also { it.load() }
        assertTrue(runCatching { store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5,
            automatic = true, mtf = mtf(signal.barTime), newsEvidence = news) }.isFailure)
        assertTrue(store.trades.value.isEmpty())
        assertFalse(file.exists())
        val manual = store.open(signal, "XAU/USD", 3000.0, 50_000.0, 0.5, manual = true)
        assertNull(manual.priceAction)
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
