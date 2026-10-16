package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fake credentials only; tests a fresh read after a synchronous SharedPreferences commit. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SettingsStoreTest {
    @Test fun `one confirmed save keeps key and symbol across new store instances and unrelated updates`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertFalse(store.read().hasKey)
        // Empty key is valid keyless mode: it saves/reconnects the symbol without creating a key.
        assertTrue(store.saveMarketCredentials("", "XAU/USD"))
        assertFalse(store.saveMarketCredentials("synthetic key", "XAU/USD"))
        assertFalse(store.read().hasKey)

        assertTrue(store.saveMarketCredentials(" synthetic-read-only-key ", " xau/usd "))
        assertEquals("synthetic-read-only-key", SettingsStore(context).read().apiKey)
        assertEquals("XAU/USD", SettingsStore(context).read().symbol)
        // Changing just the symbol with an empty field must retain the private key.
        assertTrue(store.saveMarketCredentials("", " EUR/USD "))
        store.update { it.copy(backgroundMonitor = true, notifyOnSignal = true) }
        val reopened = SettingsStore(context).read()
        assertEquals("synthetic-read-only-key", reopened.apiKey)
        assertEquals("EUR/USD", reopened.symbol)
        assertTrue(reopened.backgroundMonitor)
    }

    @Test fun `symbol writes accept catalog pairs including crypto, and removed symbols migrate to gold`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        // Symbols outside the 50+ catalog are rejected, key untouched.
        assertFalse(store.saveChartSymbol("TOTALLY_INVALID_XYZ"))
        assertFalse(store.saveChartSymbol("AAPL"))
        assertTrue(store.saveChartSymbol("XAG/USD"))
        assertTrue(store.saveChartSymbol(" eur/usd "))
        assertEquals("EUR/USD", store.read().symbol)
        // Keyless quick switch: no key required, symbol persists for a fresh store.
        assertEquals("EUR/USD", SettingsStore(context).read().symbol)
        // Crypto is a first-class catalog symbol now and must be accepted and persisted.
        assertTrue(store.saveChartSymbol("BTC/USDT"))
        assertEquals("BTC/USDT", SettingsStore(context).read().symbol)
        assertTrue(store.saveMarketCredentials("another-synthetic-key", "ETH/USDT"))
        assertEquals("ETH/USDT", SettingsStore(context).read().symbol)
        // An unknown invalid symbol is coerced to the default on read.
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit()
            .putString("symbol", "INVALID_XYZ_PAIR").commit()
        assertEquals("XAU/USD", SettingsStore(context).read().symbol)
    }

    @Test fun `server URL save is independent of market key and survives a new store`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.saveNewsBaseUrl("https://news.example.org"))
        assertEquals("https://news.example.org", SettingsStore(context).read().newsBaseUrl)
        assertFalse(SettingsStore(context).read().hasKey)
        assertTrue(store.saveNewsBaseUrl(""))
        assertEquals("", SettingsStore(context).read().newsBaseUrl)
    }
    @Test fun `legacy stocks migrate without deleting journal or optional credentials`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("symbol", "NVDA")
            .putString("v1_watchlist", "AAPL,BTC/USDT,EUR/USD,SPY")
            .putString("td_api_key", "synthetic-key").commit()
        val store = SettingsStore(context)
        assertEquals("XAU/USD", store.read().symbol)
        assertEquals(com.aurum.edge.data.WatchCatalog.scannerSymbols, store.read().activeSymbols)
        assertEquals("synthetic-key", store.read().apiKey)
        assertFalse(store.saveMarketCredentials("", "AAPL"))
        store.update { it.copy(symbol = "MSFT") }
        assertEquals("XAU/USD", SettingsStore(context).read().symbol)
    }

    @Test fun `upgrade retires enabled proxy flag without clearing market credentials or account settings`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("embedded_tor_enabled", true)
            .putString("td_api_key", "synthetic-key").putFloat("balance", 775f).commit()
        val store = SettingsStore(context)
        assertFalse(prefs.contains("embedded_tor_enabled"))
        assertEquals("synthetic-key", store.read().apiKey)
        assertEquals(775.0, store.read().accountBalance, 0.0)
        store.update { it.copy(notifyOnSignal = false) }
        assertEquals(775.0, SettingsStore(context).read().accountBalance, 0.0)
        assertFalse(prefs.contains("embedded_tor_enabled"))
    }

    @Test fun `old empty or narrow watchlist cannot restrict category strategies or active symbols`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)
        for (legacy in listOf("", "EUR/USD", "AAPL")) {
            prefs.edit().clear().putString("v1_watchlist", legacy).commit()
            val store = SettingsStore(context)
            for (mode in com.aurum.edge.core.CategoryStrategy.entries) {
                store.update { it.copy(categoryStrategies = com.aurum.edge.core.AssetClass.entries.associateWith { mode }) }
                val config = SettingsStore(context).read()
                assertEquals(15, config.activeSymbols.size)
                assertEquals(5, config.activeSymbols.count(com.aurum.edge.data.CryptoCatalog::isCrypto))
                config.activeSymbols.forEach { symbol -> assertEquals(mode, config.strategyFor(symbol)) }
                assertFalse(config.autoPaperTrading) // expanding coverage is not trading consent
                assertEquals(0.5, config.riskPercent, 0.0)
            }
            assertEquals(legacy, prefs.getString("v1_watchlist", null)) // ignored, not destructive
        }
    }

}
