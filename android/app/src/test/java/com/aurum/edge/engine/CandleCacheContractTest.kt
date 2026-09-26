package com.aurum.edge.engine

import android.content.ContextWrapper
import android.util.AtomicFile
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.data.CandleCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** App-private cache is offline display only; an interrupted write must not erase verified data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CandleCacheContractTest {
    private val now = Instant.parse("2026-09-24T15:16:00Z").toEpochMilli()
    private val valid = Candle(now - 60_000L, 3100.0, 3105.0, 3098.0, 3103.0, 0.0, closed = false)

    @Test fun invalidOhlcTimeDuplicatesAndVolumeNeverBecomeOfflineQuotes() {
        val cache = CandleCache(RuntimeEnvironment.getApplication())
        assertEquals(listOf(valid), cache.verify(listOf(valid), Interval.M5, now))
        for (bad in listOf(
            valid.copy(high = 3099.0), valid.copy(close = Double.NaN), valid.copy(volume = -1.0),
            valid.copy(time = now + 4 * 60_000L), valid.copy(time = valid.time + 60_000L),
        )) assertThrows(IllegalArgumentException::class.java) { cache.verify(listOf(valid, bad), Interval.M5, now) }
        assertThrows(IllegalArgumentException::class.java) { cache.verify(listOf(valid, valid), Interval.M5, now) }
    }

    @Test fun legacyFilesAreNotRelabeledAndAtomicWriteKeepsLastVerifiedBars() = runBlocking {
        val dir = Files.createTempDirectory("verified-candle-cache").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = dir
        }
        val cache = CandleCache(context)
        val market = File(dir, "market")
        File(market, "XAU_USD_5m.json").writeText("[{\"time\":${valid.time},\"open\":3000}]")
        assertTrue(cache.load("XAU/USD", Interval.M5).isEmpty()) // source was never verified
        val current = valid.copy(time = System.currentTimeMillis().let { it - it % Interval.M5.millis }, closed = false)
        cache.save("XAU/USD", Interval.M5, listOf(current))
        assertEquals(listOf(current), cache.load("XAU/USD", Interval.M5))
        assertTrue(cache.load("BTC/USD", Interval.M5).isEmpty())
        val file = File(market, "verified_v2_XAU_USD_5m.json")
        val atomic = AtomicFile(file)
        val interrupted = atomic.startWrite()
        interrupted.write("corrupt".toByteArray())
        // Simulate process death: no finishWrite/failWrite; AtomicFile.openRead restores .bak.
        assertEquals(listOf(current), cache.load("XAU/USD", Interval.M5))
        File(market, "verified_v2_XAU_USD_5m.json").writeText("corrupt")
        assertTrue(cache.load("XAU/USD", Interval.M5).isEmpty())
    }
}
