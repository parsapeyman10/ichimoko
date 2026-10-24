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
    @Test fun `unsupported scanner never becomes an automated quote source and every symbol has 1-to-1 source`() {
        assertFalse(SourceCatalog.all.any { it.id == "tradingview_scanner" })
        assertTrue(WatchCatalog.symbols.all { "tradingview_scanner" !in it.providerCodes })
        assertEquals(listOf("binance_btc_spot"), WatchCatalog.find("BTC/USDT")!!.defaultSources)
        assertEquals(listOf("okx_eth_spot"), WatchCatalog.find("ETH/USDT")!!.defaultSources)
        assertEquals(listOf("gate_sol_spot"), WatchCatalog.find("SOL/USDT")!!.defaultSources)
        assertEquals(listOf("mexc_bnb_spot"), WatchCatalog.find("BNB/USDT")!!.defaultSources)
        assertEquals(listOf("kraken_xrp_spot"), WatchCatalog.find("XRP/USDT")!!.defaultSources)
        assertEquals("BINANCE:BTCUSDT", TradingViewSymbols.find("BTC/USDT"))
    }

    @Test fun `metals index and forex each use a distinct dedicated source`() {
        assertEquals(listOf("gold_api_xau"), WatchCatalog.find("XAU/USD")!!.defaultSources)
        assertEquals(listOf("gold_api_xag"), WatchCatalog.find("XAG/USD")!!.defaultSources)
        assertEquals(listOf("yahoo_dow"), WatchCatalog.find("DOW")!!.defaultSources)
        assertEquals(listOf("yahoo_eurusd"), WatchCatalog.find("EUR/USD")!!.defaultSources)
        assertEquals(listOf("swissquote_gbpusd"), WatchCatalog.find("GBP/USD")!!.defaultSources)
        assertEquals(listOf("yahoo_usdjpy"), WatchCatalog.find("USD/JPY")!!.defaultSources)
        assertEquals(listOf("swissquote_eurjpy"), WatchCatalog.find("EUR/JPY")!!.defaultSources)
        assertEquals(listOf("yahoo_gbpjpy"), WatchCatalog.find("GBP/JPY")!!.defaultSources)
        assertEquals(listOf("swissquote_audusd"), WatchCatalog.find("AUD/USD")!!.defaultSources)
        assertEquals(listOf("swissquote_usdchf"), WatchCatalog.find("USD/CHF")!!.defaultSources)
    }

    @Test fun `legacy preference migrates to the symbol's single dedicated source`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("watch_preferences", Context.MODE_PRIVATE).edit()
            .putString("sources_BTC/USDT", "tradingview_scanner").commit()
        val selection = WatchSettingsStore(context).selections.value["BTC/USDT"]!!
        assertEquals(listOf("binance_btc_spot"), selection.enabledSources)
        assertEquals("binance_btc_spot", selection.preferredSourceId)
    }

    @Test fun `old Nobitex watch preference migrates to dedicated exchange source`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("watch_preferences", Context.MODE_PRIVATE).edit()
            .putString("sources_ETH/USDT", "nobitex_public_trades")
            .putString("preferred_ETH/USDT", "nobitex_public_trades").commit()
        val selection = WatchSettingsStore(context).selections.value["ETH/USDT"]!!
        assertEquals(listOf("okx_eth_spot"), selection.enabledSources)
        assertEquals("okx_eth_spot", selection.preferredSourceId)
        assertFalse(SourceCatalog.all.any { it.id == "nobitex_public_trades" })
    }
}
