package com.aurum.edge.engine

import android.app.Notification
import android.app.NotificationManager
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MtfFrameRecord
import com.aurum.edge.core.MtfSnapshotRecord
import com.aurum.edge.core.PaperNewsEvidence
import com.aurum.edge.core.PaperNewsRecord
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.JournalStore
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
    private val signal = Signal(SignalAction.BUY, 87.0, entry = 3000.0, stopLoss = 2995.0,
        takeProfit = 3010.0, interval = Interval.M5, barTime = 1_800_000_000_000L,
        confluence = (1..8).map { ConfluenceItem("فنی $it", true, "تأییدشده") } +
            ConfluenceItem(NewsConfluence.NEWS_LABEL, true, "خبر مدل با شاهد"))
    private val news = PaperNewsRecord("test-model", "BUY", 91.0, signal.barTime,
        listOf(PaperNewsEvidence("news-1", "Publisher", "Gold news",
            "https://publisher.example/news", signal.barTime)))
    private val mtf = MtfSnapshotRecord("5m", "BUY", 1.0, 1, 0, 0, false,
        "تأیید", signal.barTime,
        frames = listOf(MtfFrameRecord("5m", "BUY", 80, "تأیید", 1.0)))

    @Test fun successfulJournalWriteThenOneEntryNotificationWithTheSameId() = runBlocking {
        val file = File(Files.createTempDirectory("recorded-entry-alert").toFile(), "journal.json")
        val manager = context.getSystemService(NotificationManager::class.java)!!
        manager.cancelAll()
        val journal = JournalStore(context, file).also { it.load() }
        assertTrue(manager.activeNotifications.isEmpty())

        val entry = journal.open(signal, "XAU/USD", 3000.0, 100.0, 0.5,
            mtf = mtf, automatic = true, newsEvidence = news)
        val restored = JournalStore(context, file).also { it.load() }.trades.value.single()
        assertEquals(entry, restored) // Disk record is committed before there is an entry event.
        assertTrue(Notifier.notifyRecordedAutoEntry(context, entry, ""))
        val posted = manager.activeNotifications.single { it.id == entry.id.hashCode() }.notification
        assertEquals(Notifier.CHANNEL_VERIFIED_DEFAULT, posted.channelId)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("ثبت شد"))
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(entry.id.take(8)))
        assertEquals(9, restored.entryConditions.size)
        assertEquals("news-1", restored.newsEvidence!!.evidence.single().id)

        // Replaying that bar cannot create a second journal entry (or a second entry event).
        assertTrue(runCatching { journal.open(signal, "XAU/USD", 3000.0, 100.0, 0.5,
            mtf = mtf, automatic = true, newsEvidence = news) }.isFailure)
        assertEquals(1, manager.activeNotifications.size)
        assertFalse(Notifier.notifyRecordedAutoEntry(context, entry.copy(autoOpened = false), ""))
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
