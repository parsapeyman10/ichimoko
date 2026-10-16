package com.aurum.edge.engine

import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun `M5 candle history policy enforces 1500 candles and resampled M15 H1 sizes`() {
        assertEquals(1500, HistoryPolicy.LIVE_MIN_CANDLES)
        assertEquals(1500, HistoryPolicy.LIVE_REQUEST_CANDLES)
        assertEquals(1500, HistoryPolicy.LIVE_FETCH_CANDLES)
        assertEquals(1500, HistoryPolicy.TARGET_CANDLES)
        assertEquals(500, HistoryPolicy.RESAMPLED_M15_CANDLES)
        assertEquals(125, HistoryPolicy.RESAMPLED_H1_CANDLES)
        assertEquals(513, HistoryPolicy.ICHIMOKU_HTF_513)
        assertEquals(1024, HistoryPolicy.ICHIMOKU_HTF_1024)
        assertTrue(HistoryPolicy.CHART_BOOTSTRAP_MINIMUM >= 1500)
        assertEquals(1500, HistoryPolicy.providerRequestSize(100))
        assertEquals(1500, HistoryPolicy.providerRequestSize(HistoryPolicy.TARGET_CANDLES))
        assertEquals(HistoryPolicy.LIVE_REQUEST_CANDLES,
            HistoryPolicy.chartTargetCandles("XAU/USD", Interval.M5))
    }
}
