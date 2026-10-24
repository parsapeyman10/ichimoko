package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCadenceTest {
    @Test fun `quiet market sweeps once a minute`() {
        assertEquals(60_000L, MonitorCadence.sweepDelayMs(emptyList()))
        assertEquals(60_000L, MonitorCadence.sweepDelayMs(
            listOf("watch" to 40.0, "no_signal" to null, "blocked" to 55.0)))
    }

    @Test fun `a live candidate or near-entry confidence tightens the sweep`() {
        assertEquals(20_000L, MonitorCadence.sweepDelayMs(listOf("candidate" to null)))
        assertEquals(20_000L, MonitorCadence.sweepDelayMs(
            listOf("watch" to 40.0, "observed" to 85.0)))
        assertTrue(MonitorCadence.isHot("opened", null))
        assertFalse(MonitorCadence.isHot("error", null))
        assertFalse(MonitorCadence.isHot("watch", 69.9))
    }
}
