package com.aurum.edge.engine

import com.aurum.edge.core.Interval
import com.aurum.edge.data.ScanFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFreshnessTest {
    @Test fun `radar candidate expires and future checks are never considered fresh`() {
        val now = 1_800_000_000_000L
        assertTrue(ScanFreshness.current(now - 30_000L, Interval.M1, now))
        assertFalse(ScanFreshness.current(now - 4 * 60_000L, Interval.M1, now))
        assertFalse(ScanFreshness.current(now + 1L, Interval.M5, now))
        assertFalse(ScanFreshness.current(null, Interval.D1, now))
    }
}
