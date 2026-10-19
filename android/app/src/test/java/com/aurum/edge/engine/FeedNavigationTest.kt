package com.aurum.edge.engine

import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.AurumTab
import com.aurum.edge.ui.moreTabs
import com.aurum.edge.ui.primaryTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedNavigationTest {
    private val now = 1_800_000_000_000L

    @Test fun `quiet socket and stale REST downgrade without rewriting their original receipt`() {
        for (mode in listOf(FeedMode.LIVE, FeedMode.POLLING)) {
            val received = FeedStatus(mode, lastSuccessAt = now - 90_001L)
            val delayed = FeedLiveness.display(received, now)
            assertEquals(FeedMode.DELAYED, delayed.mode)
            assertEquals(received.lastSuccessAt, delayed.lastSuccessAt)
            assertFalse(FeedLiveness.hasRecentReceipt(delayed, now))
            assertEquals(mode, FeedLiveness.display(received.copy(lastSuccessAt = now - 1L), now).mode)
        }
        val freshDelayedTick = FeedLiveness.display(FeedStatus(FeedMode.DELAYED,
            detail = "بیش از ۹۰ ثانیه تیک تازه دریافت نشده؛ این قیمت آنلاین نیست",
            lastSuccessAt = now - 1_000L,
            provider = "فید زندهٔ جایگزین (Swissquote/Gold-API؛ WebSocket Twelve در دسترس نیست)"), now)
        assertEquals(FeedMode.LIVE, freshDelayedTick.mode)
        assertFalse(freshDelayedTick.detail.contains("۹۰"))
        val freshDelayedRest = FeedLiveness.display(FeedStatus(FeedMode.DELAYED,
            lastSuccessAt = now - 1_000L,
            provider = "Twelve Data"), now)
        assertEquals(FeedMode.POLLING, freshDelayedRest.mode)
        assertEquals(FeedMode.OFFLINE, FeedLiveness.display(FeedStatus(FeedMode.OFFLINE), now).mode)
        assertTrue(FeedLiveness.hasRecentReceipt(FeedStatus(FeedMode.LIVE, lastSuccessAt = now + 1), now))
        assertFalse(FeedLiveness.hasRecentReceipt(FeedStatus(FeedMode.LIVE, lastSuccessAt = now + 11_000L), now))
    }

    @Test fun `every destination is reachable from the forex navigation`() {
        assertEquals(AurumTab.entries.toSet(), (primaryTabs + moreTabs).toSet())
        assertEquals(AurumTab.Home, primaryTabs.first())
        assertTrue(AurumTab.Journal in primaryTabs) // News tab removed with the news layer
        assertFalse((primaryTabs + moreTabs).any { it.label == "یادگیری" })
        assertTrue(AurumTab.Update in moreTabs)
        assertTrue(AurumTab.Settings in moreTabs)
        assertTrue(AurumTab.Api in moreTabs)
        assertTrue(primaryTabs.size <= 5)
    }

    @Test fun `watch catalog keeps metals and Dow Jones first, then Ichimoku forex, then 5 crypto pairs`() {
        val nonCrypto = listOf(
            "XAU/USD", "XAG/USD", "DOW",
            "EUR/USD", "GBP/USD", "USD/JPY", "EUR/JPY", "GBP/JPY", "AUD/USD", "USD/CHF",
        )
        val ids = WatchCatalog.symbols.map { it.id }
        assertEquals(nonCrypto, ids.take(nonCrypto.size))
        assertEquals(com.aurum.edge.data.CryptoCatalog.watchlistSeed.map { it.id }, ids.drop(nonCrypto.size))
        assertEquals(ids, WatchCatalog.chartSymbols)
        assertEquals("gold_api_xau", WatchCatalog.find("XAU/USD")!!.defaultSources.single())
        assertEquals("gold_api_xag", WatchCatalog.find("XAG/USD")!!.defaultSources.single())
        assertEquals("yahoo_dow", WatchCatalog.find("DOW")!!.defaultSources.single())
        assertEquals("JPY", WatchCatalog.find("USD/JPY")!!.unit)
    }
}
