package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.data.ForexPreviewRepository
import com.aurum.edge.data.ForexPreviewStatus
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.data.TwelveDataClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ForexOverviewKeyTest {
    @Test fun `chooser needs a local read only key, skips closed sessions and never echoes a key on provider error`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsStore(context)
        var checkedAt = Instant.parse("2026-09-28T07:00:00Z").toEpochMilli()
        var tick = 60_000L
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("GET", chain.request().method)
            assertEquals("api.twelvedata.com", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("no key").body("".toResponseBody()).build()
        }.build()
        val repository = ForexPreviewRepository(settings, TwelveDataClient(http),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { checkedAt }, elapsed = { tick })
        repository.refresh()
        assertEquals(ForexPreviewStatus.NEEDS_KEY, repository.state.value.status)
        assertEquals(0, requests)
        assertTrue(settings.saveMarketCredentials("synthetic-do-not-echo", "XAU/USD"))
        repository.refresh()
        assertEquals(1, requests)
        assertEquals(ForexPreviewStatus.UNAVAILABLE, repository.state.value.status)
        assertFalse(repository.state.value.error.orEmpty().contains("synthetic-do-not-echo"))
        repository.refresh()
        assertEquals(1, requests) // per-key throttle
        assertTrue(settings.saveMarketCredentials("second-synthetic-key", "XAU/USD"))
        repository.refresh() // a new key may be checked without waiting for the old key's quota window
        assertEquals(2, requests)
        checkedAt = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli() // Sat NY weekend
        tick += 180_001L
        repository.refresh()
        assertEquals(2, requests)
        assertFalse(repository.state.value.recent(checkedAt))
    }
}
