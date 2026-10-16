package com.aurum.edge.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.aurum.edge.BuildConfig
import com.aurum.edge.core.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * In-app updating is enabled by default from 2.0.2 (owner request), including for an install that
 * upgraded from a build where the switch did not exist or was stored as off. Turning it off must
 * stick, and the one-time migration must not override that later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AutoUpdatePreferenceTest {

    private val settingsName = "aurum_settings"
    private val keyAutoDownload = "auto_download_updates"

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun clear() {
        context().getSharedPreferences(settingsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store(): SettingsStore = SettingsStore(context())

    private fun modelDefault(): Boolean = AppSettings().autoDownloadUpdates

    @Test fun `a fresh install has automatic updating enabled`() {
        clear()
        assertTrue(modelDefault())
        assertTrue("a new install must not need a manual switch", store().read().autoDownloadUpdates)
    }

    @Test fun `an install upgraded from a build with the switch off is migrated to enabled once`() {
        clear()
        context().getSharedPreferences(settingsName, Context.MODE_PRIVATE)
            .edit().putBoolean(keyAutoDownload, false).commit()
        val first = store()
        assertTrue("the upgrade must enable in-app updating", first.read().autoDownloadUpdates)
        // The migration is recorded, so a deliberate later opt-out is respected by the next start.
        first.update { it.copy(autoDownloadUpdates = false) }
        val second = store()
        assertFalse("turning the switch off must stick across restarts", second.read().autoDownloadUpdates)
    }

    @Test fun `the switch is still writable and readable`() {
        clear()
        val store = store()
        store.update { it.copy(autoDownloadUpdates = false) }
        assertFalse(store.read().autoDownloadUpdates)
        store.update { it.copy(autoDownloadUpdates = true) }
        assertTrue(store.read().autoDownloadUpdates)
    }
}
