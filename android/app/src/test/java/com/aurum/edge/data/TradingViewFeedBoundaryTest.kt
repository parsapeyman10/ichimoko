package com.aurum.edge.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The iframe is a display, not a market data entitlement or a trade-price transport. */
@RunWith(RobolectricTestRunner::class)
class TradingViewFeedBoundaryTest {
    @Test fun `unsupported scanner never becomes an automated quote source`() {
        assertFalse(SourceCatalog.all.any { it.id == "tradingview_scanner" })
        assertTrue(WatchCatalog.symbols.all { "tradingview_scanner" !in it.providerCodes })
        CryptoCatalog.watchlistSeed.forEach { coin ->
            val watch = WatchCatalog.find(coin.id)!!
            assertEquals(listOf("binance_spot_agg_trades"), watch.defaultSources)
            assertEquals(coin.id, watch.providerCodes["binance_spot_agg_trades"])
            assertEquals(SourceKind.CRYPTO_SPOT_TRADE, SourceCatalog.binanceTrades.kind)
        }
        // Chart keeps its TradingView visual mapping, distinct from execution venue.
        assertEquals("BINANCE:BTCUSDT", TradingViewSymbols.find("BTC/USDT"))
    }

    @Test fun `legacy TradingView-only preference migrates to documented exchange feed`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("watch_preferences", Context.MODE_PRIVATE).edit()
            .putString("sources_BTC/USDT", "tradingview_scanner").commit()
        val selection = WatchSettingsStore(context).selections.value["BTC/USDT"]!!
        assertEquals(listOf("binance_spot_agg_trades"), selection.enabledSources)
        assertEquals("binance_spot_agg_trades", selection.preferredSourceId)
    }
}
