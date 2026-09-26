package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.data.EquityBoardStatus
import com.aurum.edge.data.IranEquityRepository
import com.aurum.edge.data.SettingsStore
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
class IranOverviewBoardTest {
    @Test fun `board requires a read only key and scheduled session, never uses broker credentials`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsStore(context)
        var current = Instant.parse("2026-09-28T07:00:00Z").toEpochMilli() // Mon 10:30 Tehran
        var tick = 60_000L
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("GET", chain.request().method)
            assertEquals("api.brsapi.ir", chain.request().url.host)
            assertEquals("/Tsetmc/AllSymbols.php", chain.request().url.encodedPath)
            assertFalse(chain.request().headers.names().any { it.equals("Authorization", ignoreCase = true) })
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("not authorized").body("".toResponseBody()).build()
        }.build()
        val repository = IranEquityRepository(settings, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            http, clock = { current }, elapsed = { tick })
        repository.refresh()
        assertEquals(EquityBoardStatus.UNCONFIGURED, repository.state.value.status)
        assertEquals(0, requests)
        assertTrue(settings.saveStockDataKey("synthetic-board-key"))
        current = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli() // Sat 15:30 Tehran
        repository.refresh()
        assertEquals(0, requests)
        current = Instant.parse("2026-09-28T07:00:00Z").toEpochMilli()
        repository.refresh() // still no workspace selected, but GET is read-only
        assertEquals(1, requests)
        assertEquals(EquityBoardStatus.UNAVAILABLE, repository.state.value.status)
        assertFalse(repository.state.value.error.orEmpty().contains("synthetic-board-key"))
        tick += 180_001L
        assertTrue(settings.clearStockDataKey())
        repository.refresh()
        assertEquals(1, requests)
        assertEquals(EquityBoardStatus.UNCONFIGURED, repository.state.value.status)
    }
}
