package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.AurumTab
import com.aurum.edge.ui.moreTabs
import com.aurum.edge.ui.primaryTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReadoutTest {
    private val now = 1_800_000_000_000L
    private val historical = Candle(now - 300_000L, 3000.0, 3002.0, 2998.0, 3001.0)
    private val fresh = MarketState(candles = listOf(historical), lastPrice = 3001.0,
        feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = now - 10_000L, provider = "Twelve Data WebSocket"))

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
        val fallback = HomeReadout.from(fresh.copy(feed = FeedStatus(FeedMode.LIVE,
            lastSuccessAt = now - 15_000L, provider = "فید زندهٔ جایگزین")), now)
        assertTrue(fallback.current)
        assertTrue(fallback.label.contains("جایگزین"))
        assertFalse(fallback.label.contains("WebSocket"))

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
        val closed = HomeReadout.from(fresh.copy(feed = FeedStatus(FeedMode.MARKET_CLOSED,
            lastSuccessAt = now - 10_000L)), now)
        assertFalse(closed.current)
        assertTrue(closed.label.contains("بسته"))
        val noBars = HomeReadout.from(MarketState(lastPrice = 3001.0,
            feed = FeedStatus(FeedMode.OFFLINE)), now)
        assertNull(noBars.value) // a bare number without a timestamp is not a displayable quote
    }

    @Test fun `quiet socket and stale REST downgrade without rewriting their original receipt`() {
        for (mode in listOf(FeedMode.LIVE, FeedMode.POLLING)) {
            val received = FeedStatus(mode, lastSuccessAt = now - 90_001L)
            val delayed = FeedLiveness.display(received, now)
            assertEquals(FeedMode.DELAYED, delayed.mode)
            assertEquals(received.lastSuccessAt, delayed.lastSuccessAt)
            assertFalse(HomeReadout.from(fresh.copy(feed = delayed), now).current)
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
        assertTrue(AurumTab.Learn in moreTabs)
        assertTrue(AurumTab.Update in moreTabs)
        assertTrue(AurumTab.Settings in moreTabs)
        assertTrue(AurumTab.Api in moreTabs)
        assertTrue(primaryTabs.size <= 5) // bottom bar stays usable on small screens
    }

    @Test fun `watch catalog puts Iran gold and dollar first while chart stays forex`() {
        assertEquals(listOf("IR_GOLD18", "USD/IRR", "QUICK_GXRL", "PEUGEOT_207_TU5P", "XAU/USD", "EUR/USD", "GBP/USD", "AUD/USD", "NZD/USD",
            "USD/JPY", "USD/CHF", "USD/CAD"), WatchCatalog.symbols.map { it.id })
        assertEquals(listOf("XAU/USD", "EUR/USD", "GBP/USD", "AUD/USD", "NZD/USD",
            "USD/JPY", "USD/CHF", "USD/CAD"), WatchCatalog.chartSymbols)
        assertEquals("tgju_public", WatchCatalog.find("IR_GOLD18")!!.defaultSources.single())
        assertEquals("تومان", WatchCatalog.find("USD/IRR")!!.unit)
        assertEquals("bama_car_price", WatchCatalog.find("QUICK_GXRL")!!.defaultSources.single())
        assertEquals("XAUUSD=X", WatchCatalog.find("XAU/USD")!!.providerCodes["stocks_yahoo"])
        assertEquals("JPY", WatchCatalog.find("USD/JPY")!!.unit)
    }
}
