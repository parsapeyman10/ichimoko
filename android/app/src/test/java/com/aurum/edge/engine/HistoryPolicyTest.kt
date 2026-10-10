package com.aurum.edge.engine

import com.aurum.edge.core.HistoryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun `live chart uses a bounded history while research limits stay independent`() {
        assertEquals(1200, HistoryPolicy.providerRequestSize(
            HistoryPolicy.CHART_BOOTSTRAP_CANDLES,
            HistoryPolicy.CHART_BOOTSTRAP_CANDLES,
        ))
        assertTrue(HistoryPolicy.CHART_BOOTSTRAP_MINIMUM > 1000)
        assertEquals(HistoryPolicy.TARGET_CANDLES, HistoryPolicy.providerRequestSize(100))
        assertEquals(HistoryPolicy.TARGET_CANDLES,
            HistoryPolicy.providerRequestSize(HistoryPolicy.TARGET_CANDLES))
        assertEquals(HistoryPolicy.MAX_TWELVE_CANDLES,
            HistoryPolicy.providerRequestSize(HistoryPolicy.DEEP_CHART_CANDLES))
        assertEquals(HistoryPolicy.DEEP_CHART_CANDLES,
            HistoryPolicy.deepProviderRequestSize(HistoryPolicy.DEEP_CHART_CANDLES))
        assertEquals(210, HistoryPolicy.LIVE_MIN_CANDLES)
        assertEquals(320, HistoryPolicy.LIVE_REQUEST_CANDLES)
        assertEquals(HistoryPolicy.LIVE_REQUEST_CANDLES,
            HistoryPolicy.chartTargetCandles("XAU/USD", com.aurum.edge.core.Interval.M5))
    }
}
