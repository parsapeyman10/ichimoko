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
        assertTrue(AurumTab.News in primaryTabs)
        assertFalse((primaryTabs + moreTabs).any { it.label == "یادگیری" })
        assertTrue(AurumTab.Update in moreTabs)
        assertTrue(AurumTab.Settings in moreTabs)
        assertTrue(AurumTab.Api in moreTabs)
        assertTrue(primaryTabs.size <= 5) // bottom bar stays usable on small screens
    }

    @Test fun `watch catalog keeps Iran cash rows first, then forex, then crypto`() {
        // Order matters: the Iran cash board is what the user opens the app for, the forex
        // workspace follows, and crypto is appended so existing positions in the list do
        // not shift. Crypto is chartable like forex; the Iran rows stay watch-only.
        val forexFirst = listOf("IR_GOLD18", "USD/IRR", "QUICK_GXRL", "PEUGEOT_207_TU5P",
            "XAU/USD", "EUR/USD", "GBP/USD", "AUD/USD", "NZD/USD", "USD/JPY", "USD/CHF", "USD/CAD")
        val ids = WatchCatalog.symbols.map { it.id }
        assertEquals(forexFirst, ids.take(forexFirst.size))
        assertEquals(com.aurum.edge.data.CryptoCatalog.watchlistSeed.map { it.id }, ids.drop(forexFirst.size))

        assertEquals(listOf("XAU/USD", "EUR/USD", "GBP/USD", "AUD/USD", "NZD/USD",
            "USD/JPY", "USD/CHF", "USD/CAD") +
            com.aurum.edge.data.CryptoCatalog.watchlistSeed.map { it.id },
            WatchCatalog.chartSymbols)
        assertEquals("tgju_public", WatchCatalog.find("IR_GOLD18")!!.defaultSources.single())
        assertEquals("تومان", WatchCatalog.find("USD/IRR")!!.unit)
        assertEquals("bama_car_price", WatchCatalog.find("QUICK_GXRL")!!.defaultSources.single())
        assertEquals("XAUUSD=X", WatchCatalog.find("XAU/USD")!!.providerCodes["stocks_yahoo"])
        assertEquals("JPY", WatchCatalog.find("USD/JPY")!!.unit)
    }
}
