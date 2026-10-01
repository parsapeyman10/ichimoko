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

    @Test fun `symbol writes accept only catalog pairs and legacy symbols migrate to gold`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        // Non-catalog symbols (crypto/stocks of removed workspaces) are rejected, key untouched.
        assertFalse(store.saveMarketCredentials("another-synthetic-key", "BTC/USDT"))
        assertFalse(store.saveChartSymbol("AAPL"))
        assertFalse(store.saveChartSymbol("XAG/USD")) // silver is not in this forex-only catalog
        assertTrue(store.saveChartSymbol(" eur/usd "))
        assertEquals("EUR/USD", store.read().symbol)
        // Keyless quick switch: no key required, symbol persists for a fresh store.
        assertEquals("EUR/USD", SettingsStore(context).read().symbol)
        // A legacy stored symbol (old install) is coerced to the catalog default on read.
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit()
            .putString("symbol", "BTC/USDT").commit()
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
}
