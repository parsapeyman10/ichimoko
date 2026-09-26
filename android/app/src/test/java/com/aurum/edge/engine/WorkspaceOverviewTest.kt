package com.aurum.edge.engine

import com.aurum.edge.core.OverviewCheck
import com.aurum.edge.core.WorkspaceOverviewCoordinator
import com.aurum.edge.core.WorkspaceOverviewSchedule
import com.aurum.edge.data.ForexPreviewState
import com.aurum.edge.data.ForexPreviewStatus
import com.aurum.edge.data.NobitexNoticesState
import com.aurum.edge.data.NobitexNotice
import com.aurum.edge.data.NoticesStatus
import com.aurum.edge.data.PublicCryptoState
import com.aurum.edge.data.PublicCryptoStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WorkspaceOverviewTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val open = at("2026-09-28T07:00:00Z") // Mon 10:30 Tehran, 03:00 New York
    private val closed = at("2026-09-26T12:00:00Z") // Sat 15:30 Tehran / 08:00 New York

    @Test fun `each scheduled market has its own source, closed prices skip, crypto and notices continue`() {
        assertEquals(9, WorkspaceOverviewSchedule.checks(open).size)
        assertEquals(OverviewCheck.entries.toSet(), WorkspaceOverviewSchedule.checks(open))
        val weekend = WorkspaceOverviewSchedule.checks(closed)
        assertEquals(setOf(OverviewCheck.CRYPTO_PRICES, OverviewCheck.CRYPTO_PUBLISHERS,
            OverviewCheck.NOBITEX_STATS, OverviewCheck.NOBITEX_NOTICES, OverviewCheck.IRAN_PUBLISHERS), weekend)
        assertFalse(OverviewCheck.FOREX_CALENDAR in weekend)
        assertFalse(OverviewCheck.FOREX_SAMPLE in weekend)
        assertFalse(OverviewCheck.IRAN_BOARD in weekend)
    }

    @Test fun `nine read only checks launch concurrently without a selection and a failure stays isolated`() = runBlocking {
        val started = Channel<OverviewCheck>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val calls = OverviewCheck.entries.associateWith { check ->
            suspend {
                started.send(check)
                if (check == OverviewCheck.FOREX_PUBLISHERS) error("publisher offline")
                release.await()
            }
        }
        val coordinator = WorkspaceOverviewCoordinator(this, calls, elapsed = { 120_000L })
        val run = async { coordinator.refresh(open) }
        val observed = withTimeout(2_000L) { List(9) { started.receive() }.toSet() }
        // All launch before the delayed GETs can complete: no sequential 'select one space' loop.
        assertEquals(OverviewCheck.entries.toSet(), observed)
        release.complete(Unit)
        run.await()
    }

    @Test fun `foreground duplicate is throttled and resumed later checks again`() = runBlocking {
        var tick = 120_000L
        val calls = mutableListOf<OverviewCheck>()
        val coordinator = WorkspaceOverviewCoordinator(this,
            OverviewCheck.entries.associateWith { c -> suspend { calls.add(c); Unit } }, elapsed = { tick })
        coordinator.refresh(open)
        coordinator.refresh(open)
        assertEquals(9, calls.size)
        tick += 60_001L
        coordinator.refresh(closed)
        assertEquals(14, calls.size) // only the five eligible weekend sources
    }

    @Test fun `phone receipt and source time cannot independently turn stale prices into live quotes`() {
        val good = ForexPreviewState(ForexPreviewStatus.OBSERVED, 3030.0, open - 300_000L, open - 10_000L)
        assertTrue(good.recent(open))
        assertFalse(good.recent(open + 180_001L))
        assertFalse(good.copy(barAt = open - 900_001L).recent(open))
        assertFalse(good.copy(status = ForexPreviewStatus.LOADING).recent(open))
        assertFalse(good.copy(barAt = closed - 300_000L, receivedAt = closed - 10_000L).recent(closed))
        assertFalse(PublicCryptoState(PublicCryptoStatus.UNAVAILABLE, receivedAt = open).recent(open))
        val notice = NobitexNoticesState(NoticesStatus.OBSERVED, listOf(
            NobitexNotice("عنوان صفحهٔ رسمی", "https://nobitex.ir/announcement/news/test/", null)), open)
        assertTrue(notice.recentReceipt(open))
        assertFalse(notice.recentReceipt(open + 1_800_001L))
        assertFalse(notice.copy(status = NoticesStatus.UNAVAILABLE).recentReceipt(open))
    }
}
