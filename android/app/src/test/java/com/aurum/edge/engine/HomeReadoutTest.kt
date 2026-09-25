package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.AurumTab
import com.aurum.edge.ui.Workspace
import com.aurum.edge.ui.moreTabsFor
import com.aurum.edge.ui.primaryTabsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReadoutTest {
    private val now = 1_800_000_000_000L
    private val historical = Candle(now - 300_000L, 3000.0, 3002.0, 2998.0, 3001.0)
    private val fresh = MarketState(candles = listOf(historical), lastPrice = 3001.0,
        feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now - 10_000L))

    @Test fun `live and REST are described differently and stale or cached quotes never say current`() {
        val live = HomeReadout.from(fresh, now)
        assertTrue(live.current)
        assertEquals(now - 10_000L, live.observedAt)
        assertTrue(live.label.contains("WebSocket"))
        val rest = HomeReadout.from(fresh.copy(feed = FeedStatus(FeedMode.POLLING,
            lastSuccessAt = now - 20_000L)), now)
        assertTrue(rest.current)
        assertTrue(rest.label.contains("REST"))
        assertFalse(rest.label.contains("WebSocket"))

        for (stale in listOf(
            fresh.copy(feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now - 90_001L)),
            fresh.copy(showingCachedData = true),
            fresh.copy(feed = FeedStatus(FeedMode.OFFLINE, lastSuccessAt = now - 10_000L)),
        )) {
            val readout = HomeReadout.from(stale, now)
            assertFalse(readout.current)
            assertEquals(historical.time, readout.observedAt)
            assertFalse(readout.label.contains("WebSocket"))
        }
        val noBars = HomeReadout.from(MarketState(lastPrice = 3001.0,
            feed = FeedStatus(FeedMode.OFFLINE)), now)
        assertNull(noBars.value) // a bare number without a timestamp is not a displayable quote
    }

    @Test fun `four workspaces expose only their own destinations`() {
        assertEquals(4, Workspace.entries.size)
        val tabs = Workspace.entries.associateWith { primaryTabsFor(it) + moreTabsFor(it) }
        assertEquals(AurumTab.entries.toSet(), tabs.values.flatten().toSet())
        assertEquals(AurumTab.Home, primaryTabsFor(Workspace.FOREX).first())
        assertTrue(AurumTab.News in tabs.getValue(Workspace.FOREX))
        assertTrue(AurumTab.Learn in moreTabsFor(Workspace.FOREX))
        assertTrue(AurumTab.Settings in moreTabsFor(Workspace.FOREX))
        assertEquals(listOf(AurumTab.Crypto, AurumTab.CryptoNews), tabs.getValue(Workspace.CRYPTO))
        assertEquals(listOf(AurumTab.Nobitex), tabs.getValue(Workspace.NOBITEX))
        assertEquals(listOf(AurumTab.Stocks, AurumTab.Agah), tabs.getValue(Workspace.IRAN_STOCKS))
        assertTrue(Workspace.entries.map { it.id }.distinct().size == 4)
    }
}
