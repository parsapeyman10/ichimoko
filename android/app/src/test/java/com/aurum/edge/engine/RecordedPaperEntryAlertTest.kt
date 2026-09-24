package com.aurum.edge.engine

import android.app.Notification
import android.app.NotificationManager
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MtfFrameRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.MarketState
import com.aurum.edge.notify.Notifier
import java.io.File
import java.nio.file.Files
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

/** A notification claiming an opened position must be backed by an atomic paper-journal entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RecordedPaperEntryAlertTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val signal = Signal(SignalAction.BUY, 87.0, entry = 3000.0, stopLoss = 2994.5,
        takeProfit = 3009.0, interval = Interval.M5, barTime = 1_800_000_000_000L,
        confluence = (1..8).map { ConfluenceItem("فنی $it", true, "تأییدشده") } +
            ConfluenceItem(NewsConfluence.NEWS_LABEL, true, "خبر مدل با شاهد"))
    private val news = PaperNewsRecord("test-model", "BUY", 91.0, signal.barTime,
        listOf(PaperNewsEvidence("news-1", "Publisher", "Gold news",
            "https://publisher.example/news", signal.barTime)),
        calendarSource = "https://nfs.faireconomy.media/ff_calendar_thisweek.json",
        calendarCheckedAt = signal.barTime)
    private val mtf = MtfSnapshotRecord("5m", "BUY", 1.0, 1, 0, 0, false,
        "تأیید", signal.barTime,
        frames = listOf(MtfFrameRecord("5m", "BUY", 80, "تأیید", 1.0)))
    private val ict get() = IctEntryRules.approvedEvidence(MarketState(
        symbol = "XAU/USD", interval = Interval.M5, candles = IctTestBars.readyAt(signal.barTime),
        lastPrice = 3000.0,
        feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = signal.barTime + Interval.M5.millis),
        signal = signal), signal.barTime + Interval.M5.millis)!!

    @Test fun successfulJournalWriteThenOneEntryNotificationWithTheSameId() = runBlocking {
        val file = File(Files.createTempDirectory("recorded-entry-alert").toFile(), "journal.json")
        val manager = context.getSystemService(NotificationManager::class.java)!!
        manager.cancelAll()
        val journal = JournalStore(context, file).also { it.load() }
        assertTrue(manager.activeNotifications.isEmpty())

        val evidence = ict
        val entry = journal.open(signal, "XAU/USD", 3000.0, 100.0, 0.5,
            mtf = mtf, automatic = true, newsEvidence = news, priceAction = evidence)
        val restored = JournalStore(context, file).also { it.load() }.trades.value.single()
        assertEquals(entry, restored) // Disk record is committed before there is an entry event.
        assertTrue(Notifier.notifyRecordedAutoEntry(context, entry, ""))
        val posted = manager.activeNotifications.single { it.id == entry.id.hashCode() }.notification
        assertEquals(Notifier.CHANNEL_VERIFIED_DEFAULT, posted.channelId)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("ثبت شد"))
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(entry.id.take(8)))
        assertEquals(9, restored.entryConditions.size)
        assertEquals(evidence, restored.priceAction)
        assertEquals("news-1", restored.newsEvidence!!.evidence.single().id)

        // Replaying that bar cannot create a second journal entry (or a second entry event).
        assertTrue(runCatching { journal.open(signal, "XAU/USD", 3000.0, 100.0, 0.5,
            mtf = mtf, automatic = true, newsEvidence = news, priceAction = evidence) }.isFailure)
        assertEquals(1, manager.activeNotifications.size)
        assertFalse(Notifier.notifyRecordedAutoEntry(context, entry.copy(autoOpened = false), ""))
        assertFalse(Notifier.notifyRecordedAutoEntry(context, entry.copy(priceAction = null), ""))
        assertFalse(Notifier.notifyRecordedAutoEntry(context,
            entry.copy(entryConditions = entry.entryConditions.dropLast(1)), ""))
        assertFalse(Notifier.notifyRecordedAutoEntry(context,
            entry.copy(entryConditions = entry.entryConditions.mapIndexed { index, c ->
                if (index == 8) c.copy(status = "UNKNOWN") else c
            }), ""))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun damagedJournalNeverYieldsAnEntryEvent() = runBlocking {
        val file = File(Files.createTempDirectory("recorded-entry-corrupt").toFile(), "journal.json")
        file.writeText("{invalid}")
        val manager = context.getSystemService(NotificationManager::class.java)!!
        manager.cancelAll()
        val journal = JournalStore(context, file)
        assertTrue(runCatching { journal.load() }.isFailure)
        assertNull(runCatching { journal.open(signal, "XAU/USD", 3000.0, 100.0, 0.5,
            mtf = mtf, automatic = true, newsEvidence = news) }.getOrNull())
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals("{invalid}", file.readText())
    }
}
