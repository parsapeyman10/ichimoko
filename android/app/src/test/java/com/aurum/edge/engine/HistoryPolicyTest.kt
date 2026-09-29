package com.aurum.edge.engine

import com.aurum.edge.core.HistoryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPolicyTest {
    @Test fun `chart bootstrap requests more than one thousand without changing full history floor`() {
        assertEquals(1200, HistoryPolicy.providerRequestSize(
            HistoryPolicy.CHART_BOOTSTRAP_CANDLES,
            HistoryPolicy.CHART_BOOTSTRAP_CANDLES,
        ))
        assertTrue(HistoryPolicy.CHART_BOOTSTRAP_MINIMUM > 1000)
        assertEquals(HistoryPolicy.MAX_CACHED_CANDLES,
            HistoryPolicy.providerRequestSize(100))
        assertEquals(HistoryPolicy.MAX_CACHED_CANDLES,
            HistoryPolicy.providerRequestSize(HistoryPolicy.TARGET_CANDLES))
    }
}
