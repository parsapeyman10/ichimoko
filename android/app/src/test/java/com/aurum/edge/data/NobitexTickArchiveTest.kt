package com.aurum.edge.data

import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.domain.SignalAction
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NobitexTickArchiveTest {
    @Test fun `wrong venue duplicated sequence and missing gaps fail closed`() {
        val valid = """{"venue":"nobitex","symbol":"BTCUSDT","live":true,"head":15,"next":15,
            "trades":[{"seq":14,"time":1800000000000,"price":3000,"volume":1,"type":"buy"},
                      {"seq":15,"time":1799999999999,"price":3001,"volume":1,"type":"sell"}],
            "gaps":[{"at":1800000000000,"afterSeq":14,"reason":"upstream_recovery_failed"}]}"""
        val now = System.currentTimeMillis()
        val body = valid.replace("1800000000000", now.toString())
            .replace("1799999999999", (now - 1).toString())
        val page = NobitexTickArchiveClient.parse(body, "BTCUSDT", 0)
        assertEquals(listOf(14L, 15L), page.rows.map { it.seq })
        assertEquals(listOf(now, now - 1), page.rows.map { it.at })
        assertEquals(1, page.gaps.size)
        for (broken in listOf(
            body.replace("nobitex", "binance"), body.replace("BTCUSDT", "ETHUSDT"),
            body.replace("\"seq\":15", "\"seq\":14"),
            body.replace("\"gaps\":", "\"unknown\":"),
        )) assertTrue(runCatching { NobitexTickArchiveClient.parse(broken, "BTCUSDT", 0) }.isFailure)
    }

    @Test fun `archive cursor is persisted atomically with trailing and only archive ticks settle`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(ctx)
        val url = "https://example.org"
        assertFalse(settings.saveTickArchive("http://example.org", "x".repeat(40)))
        assertTrue(settings.saveTickArchive(url, "x".repeat(40)))
        val file = File(Files.createTempDirectory("tick-archive-test").toFile(), "journal.json")
        settings.update { it.copy(accountBalance = 50_000.0) }
        val store = JournalStore(ctx, file, settingsStore = settings)
        val at = 1_800_000_000_000L
        // Historical journal fixture: NEW crypto entries no longer use the old strategy,
        // but their previously recorded same-venue archive cursors still settle safely.
        val legacy = com.aurum.edge.core.PaperTrade("legacy-archive", "BTC/USDT",
            Interval.M5, SignalAction.BUY, 3000.0, 2960.0, 3100.0, 100.0, 2.5, at,
            initialStopLoss = 2960.0, trailExtreme = 3000.0, usesTickArchive = true,
            cryptoVenue = "NOBITEX")
        file.writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.aurum.edge.core.PaperTrade.serializer()),
            listOf(legacy)))
        store.load()
        val opened = store.trades.value.single()
        assertTrue(opened.usesTickArchive)
        // Direct local quotes/candles are not allowed to reorder an archived position.
        store.settleTick("BTC/USDT", 2950.0, at + 1)
        assertTrue(store.trades.value.single().isOpen)
        store.settleTick("BTC/USDT", 3101.0, at + 2, archiveSeq = 4)
        val trailed = store.trades.value.single().stopLoss
        val restored = JournalStore(ctx, file, settingsStore = settings).also { it.load() }
        assertEquals(4L, restored.trades.value.single().tickArchiveSeq)
        restored.settleTick("BTC/USDT", 2950.0, at + 3, archiveSeq = 4)
        assertTrue(restored.trades.value.single().isOpen)
        assertEquals(trailed, restored.trades.value.single().stopLoss, 0.001)
        assertEquals(1, restored.settleTick("BTC/USDT", trailed - 0.5,
            at + 4, archiveSeq = 5).size)
        assertEquals(5L, JournalStore(ctx, file, settingsStore = settings).also { it.load() }
            .trades.value.single().tickArchiveSeq)
        settings.saveTickArchive("", "")
        Unit
    }
}
