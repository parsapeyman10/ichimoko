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
        assertFalse(store.saveMarketCredentials("", "XAU/USD"))
        assertFalse(store.saveMarketCredentials("synthetic key", "XAU/USD"))
        assertFalse(store.read().hasKey)

        assertTrue(store.saveMarketCredentials(" synthetic-read-only-key ", " xau/usd "))
        assertEquals("synthetic-read-only-key", SettingsStore(context).read().apiKey)
        assertEquals("XAU/USD", SettingsStore(context).read().symbol)
        // Changing just the symbol with an empty field must retain the private key.
        assertTrue(store.saveMarketCredentials("", " XAG/USD "))
        store.update { it.copy(backgroundMonitor = true, notifyOnSignal = true) }
        val reopened = SettingsStore(context).read()
        assertEquals("synthetic-read-only-key", reopened.apiKey)
        assertEquals("XAG/USD", reopened.symbol)
        assertTrue(reopened.backgroundMonitor)
    }

    @Test fun `switching to non forex commits the fail closed flags and keeps only read only stock key`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertFalse(store.selectWorkspace("injected-destination"))
        assertFalse(store.saveStockDataKey("x y"))
        assertTrue(store.saveStockDataKey("synthetic-stock-read-only"))
        assertTrue(store.selectWorkspace("forex"))
        store.update { it.copy(backgroundMonitor = true, autoPaperTrading = true) }
        assertTrue(store.selectWorkspace("iran_stocks"))
        val reopened = SettingsStore(context).read()
        assertEquals("iran_stocks", reopened.workspaceId)
        assertFalse(reopened.backgroundMonitor)
        assertFalse(reopened.autoPaperTrading)
        assertEquals("synthetic-stock-read-only", reopened.stockDataKey)
        assertTrue(store.clearStockDataKey())
        assertEquals("", SettingsStore(context).read().stockDataKey)
    }

    @Test fun `research monitor opt in is per selected space and never enables forex auto paper`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.selectWorkspace("crypto"))
        store.update { it.copy(backgroundMonitor = true, autoPaperTrading = false) }
        assertTrue(store.selectWorkspace("crypto")) // process recreation of the same selected space
        assertTrue(SettingsStore(context).read().backgroundMonitor)
        assertTrue(store.selectWorkspace("nobitex"))
        assertFalse(store.read().backgroundMonitor)
        assertFalse(store.read().autoPaperTrading)
        store.update { it.copy(backgroundMonitor = true) }
        assertTrue(store.selectWorkspace("forex"))
        assertFalse(SettingsStore(context).read().backgroundMonitor)
        assertFalse(SettingsStore(context).read().autoPaperTrading)
        assertTrue(store.selectWorkspace(""))
        assertFalse(store.read().backgroundMonitor)
    }

    @Test fun `server URL save is independent of market key and survives a new store`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.saveNewsBaseUrl("https://news.example.org"))
        assertTrue(store.saveCryptoBaseUrl("https://crypto.example.org"))
        assertEquals("https://news.example.org", SettingsStore(context).read().newsBaseUrl)
        assertEquals("https://crypto.example.org", SettingsStore(context).read().cryptoBaseUrl)
        assertFalse(SettingsStore(context).read().hasKey)
        assertTrue(store.saveNewsBaseUrl(""))
        assertEquals("", SettingsStore(context).read().newsBaseUrl)
    }
}
