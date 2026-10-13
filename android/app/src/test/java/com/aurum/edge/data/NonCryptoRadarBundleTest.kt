package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NonCryptoRadarBundleTest {
    private val now = System.currentTimeMillis()
    private fun bars(interval: Interval, stale: Boolean = false): List<Candle> {
        val last = now / interval.millis * interval.millis - interval.millis -
            if (stale) 4 * interval.millis else 0L
        return (0 until 320).map { i ->
            Candle(last - (319-i) * interval.millis, 100.0, 101.0, 99.0, 100.0, 10.0)
        }
    }

    @Test fun `stale Yahoo M15 rejects entire publisher and retries complete Dukascopy bundle`() = runBlocking {
        val requests = mutableListOf<Pair<SourceRouting.HistorySource, Interval>>()
        val bundle = NonCryptoRadarBundle { _, frame, source, _ ->
            requests += source to frame
            PublicCandleHistoryClient.Result(bars(frame,
                stale = source == SourceRouting.HistorySource.YAHOO && frame == Interval.M15),
                when (source) {
                    SourceRouting.HistorySource.YAHOO -> "Yahoo Finance عمومی · $frame"
                    SourceRouting.HistorySource.DUKASCOPY -> "Dukascopy عمومی · $frame فایل"
                    SourceRouting.HistorySource.TWELVE_DATA -> "Twelve Data · fallback"
                })
        }
        val result = bundle.fetch("EUR/USD", "", now)
        assertEquals(SourceRouting.HistorySource.DUKASCOPY, result.source)
        assertEquals(setOf(Interval.M5, Interval.M15, Interval.H1), result.frames.keys)
        assertTrue(result.priorFailures.single().contains("YAHOO"))
        assertEquals(3, requests.count { it.first == SourceRouting.HistorySource.YAHOO })
        assertEquals(3, requests.count { it.first == SourceRouting.HistorySource.DUKASCOPY })
        result.frames.forEach { (frame, rows) ->
            assertEquals(bars(frame).last().time, rows.last().time)
        }
    }

    @Test fun `silver uses exact Dukascopy instrument then keyed provider if needed`() = runBlocking {
        assertEquals("XAGUSD", DukascopyHistoryClient.instrument("XAG/USD"))
        assertEquals(1_000.0, DukascopyHistoryClient.pointScale("XAGUSD"), 0.0)
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY),
            SourceRouting.historySources("XAG/USD", false))
        assertEquals(listOf(SourceRouting.HistorySource.DUKASCOPY, SourceRouting.HistorySource.TWELVE_DATA),
            SourceRouting.historySources("XAG/USD", true))
        val bundle = NonCryptoRadarBundle { _, frame, source, _ ->
            PublicCandleHistoryClient.Result(bars(frame,
                stale = source == SourceRouting.HistorySource.DUKASCOPY),
                if (source == SourceRouting.HistorySource.DUKASCOPY) "Dukascopy عمومی" else "Twelve Data")
        }
        assertEquals(SourceRouting.HistorySource.TWELVE_DATA,
            bundle.fetch("XAG/USD", "dummy-key", now).source)
    }

    @Test fun `frames launch concurrently and wrong publisher never approves a signal`() = runBlocking {
        val started = mutableListOf<Interval>()
        val release = CompletableDeferred<Unit>()
        val bundle = NonCryptoRadarBundle { _, frame, _, _ ->
            started += frame
            release.await()
            PublicCandleHistoryClient.Result(bars(frame), "Yahoo Finance عمومی")
        }
        val job = async { bundle.fetch("AAPL", "", now) }
        // Each async read runs until it suspends on the same deferred (no wall-clock benchmark).
        kotlinx.coroutines.withTimeout(1_000) {
            while (started.size < 3) kotlinx.coroutines.yield()
        }
        assertEquals(setOf(Interval.M5, Interval.M15, Interval.H1), started.toSet())
        release.complete(Unit)
        assertEquals(SourceRouting.HistorySource.YAHOO, job.await().source)
        val broken = NonCryptoRadarBundle { _, frame, _, _ ->
            PublicCandleHistoryClient.Result(bars(frame),
                if (frame == Interval.H1) "Dukascopy عمومی" else "Yahoo Finance عمومی")
        }
        assertTrue(runCatching { broken.fetch("AAPL", "", now) }.exceptionOrNull() is DataFeedException)
    }
}
