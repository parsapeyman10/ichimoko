package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperAutoRules
import com.aurum.edge.core.PriceTick
import com.aurum.edge.data.CatalogQuotePolicy
import com.aurum.edge.core.MarketState
import com.aurum.edge.data.WatchCatalog
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAutoPolicyTest {
    private val weekday = Instant.parse("2026-10-06T15:00:00Z").toEpochMilli()

    @Test fun `catalog entry needs a fresh actual tick and a supported exit monitor`() {
        assertTrue(CatalogQuotePolicy.tickMonitored("EUR/USD"))
        assertTrue(CatalogQuotePolicy.tickMonitored("XAU/USD"))
        assertTrue(CatalogQuotePolicy.tickMonitored("BTC/USDT"))
        assertNull(CatalogQuotePolicy.rejection("BTC/USDT", PriceTick(100.0, weekday), weekday))
        assertNotNull(CatalogQuotePolicy.rejection("BTC/USDT",
            PriceTick(100.0, weekday, isTradeTick = false), weekday))
        listOf("AAPL", "USOIL", "XAGUSD", "INVALID").forEach {
            assertFalse(CatalogQuotePolicy.tickMonitored(it))
            assertNotNull(CatalogQuotePolicy.rejection(it, PriceTick(100.0, weekday), weekday))
        }
        assertNotNull(CatalogQuotePolicy.rejection("EUR/USD", null, weekday))
        assertNotNull(CatalogQuotePolicy.rejection("EUR/USD", PriceTick(100.0, weekday, isTradeTick = false), weekday))
        assertNotNull(CatalogQuotePolicy.rejection("EUR/USD", PriceTick(100.0, weekday - 46_000L), weekday))
        assertNull(CatalogQuotePolicy.rejection("EUR/USD", PriceTick(100.0, weekday), weekday))
    }

    @Test fun `crypto entry requires the new breakout proof before any venue or quote`() {
        val config = AppSettings(autoPaperTrading = true)
        // ETH/USDT, not BTC: the compression gate now protects the non-BTC crypto family only.
        val observed = MarketState(symbol = "ETH/USDT", interval = Interval.M5,
            lastPrice = 100.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = weekday),
            historyProvider = "Twelve Data")
        assertFalse(com.aurum.edge.data.CryptoVenuePolicy.compatible("ETH/USDT",
            observed.historyProvider, observed.feed.provider))
        assertTrue(PaperAutoRules.blocker(observed, config, now = weekday,
            allowedSymbols = WatchCatalog.scannerSymbols)!!.contains("فشردگی"))
        val matched = observed.copy(historyProvider = com.aurum.edge.data.BinanceSpotClient.PROVIDER,
            feed = observed.feed.copy(provider = "Binance Spot / USDT aggTrade"),
            lastTradeAt = weekday, lastTradePrice = 100.0)
        assertTrue(PaperAutoRules.blocker(matched.copy(lastTradeAt = weekday - 46_000L), config,
            now = weekday,
            allowedSymbols = WatchCatalog.scannerSymbols)!!.contains("فشردگی"))
        assertEquals("سیگنال فشردگی/انفجار موجود نیست",
            PaperAutoRules.blocker(matched, config, now = weekday,
                allowedSymbols = WatchCatalog.scannerSymbols))
    }

    @Test fun `full catalog is active by default but never invents a signal`() {
        val config = AppSettings(autoPaperTrading = true)
        val state = MarketState(symbol = "USD/JPY", interval = Interval.M5,
            lastPrice = 150.0, feed = FeedStatus(FeedMode.LIVE, lastSuccessAt = weekday))
        assertEquals("سیگنال محاسبه نشده است", PaperAutoRules.blocker(state, config, now = weekday))
        assertTrue(PaperAutoRules.blocker(state, config, now = weekday,
            allowedSymbols = listOf("EUR/USD"))!!.contains("لیست"))
        assertEquals("سیگنال محاسبه نشده است",
            PaperAutoRules.blocker(state, config, now = weekday,
                allowedSymbols = WatchCatalog.scannerSymbols))
    }
}
