package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.data.SourceCatalog
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.data.WatchSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WatchKeyStoreTest {
    @Test fun `per-symbol read-only key is disk-committed and never overwrites the chart key`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("watch_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val id = WatchCatalog.symbols.first { SourceCatalog.twelveData.id in it.providerCodes }.id
        val store = WatchSettingsStore(context)
        assertTrue(store.setKeyOverride(id, " synthetic-watch-only "))
        assertEquals("synthetic-watch-only", WatchSettingsStore(context).keyOverride(id))
        assertFalse(store.setKeyOverride(id, "synthetic bad key"))
        assertEquals("synthetic-watch-only", WatchSettingsStore(context).keyOverride(id))
        assertTrue(store.setKeyOverride(id, ""))
        assertEquals("chart-only", WatchSettingsStore(context).apiKeyFor(id, "chart-only"))
        assertFalse(store.setKeyOverride("unsupported-symbol", "synthetic"))
    }
}
