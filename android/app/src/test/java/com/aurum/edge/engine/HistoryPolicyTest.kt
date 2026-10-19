package com.aurum.edge.engine

import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun `per-frame independent fetch and minimum sizes`() {
        assertEquals(280, HistoryPolicy.LIVE_MIN_CANDLES)
        assertEquals(400, HistoryPolicy.LIVE_REQUEST_CANDLES)
        assertEquals(400, HistoryPolicy.LIVE_FETCH_CANDLES)
        assertEquals(500, HistoryPolicy.TARGET_CANDLES)
        // Independent per-frame sizes (frames are fetched, never resampled from M5)
        assertEquals(400, HistoryPolicy.fetchSize(Interval.M5))
        assertEquals(300, HistoryPolicy.fetchSize(Interval.M15))
        assertEquals(250, HistoryPolicy.fetchSize(Interval.H1))
        assertEquals(280, HistoryPolicy.minSize(Interval.M5))
        assertEquals(240, HistoryPolicy.minSize(Interval.M15))
        assertEquals(200, HistoryPolicy.minSize(Interval.H1))
        // Every healthy minimum clears the deepest engine lookback (EMA-200 warm-up)
        assertTrue(listOf(Interval.M5, Interval.M15, Interval.H1)
            .all { HistoryPolicy.minSize(it) >= 200 })
        assertEquals(500, HistoryPolicy.providerRequestSize(100))
        assertEquals(HistoryPolicy.fetchSize(Interval.M5),
            HistoryPolicy.chartTargetCandles("XAU/USD", Interval.M5))
    }
}
