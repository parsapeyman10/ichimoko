package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenTickSnapshotTest {
    private val now = 1_800_000_000_000L

    @Test fun `a failed market only clears its own value`() {
        val old = mapOf("EUR/USD" to PriceTick(1.1, now - 1_000),
            "XAU/USD" to PriceTick(2600.0, now - 1_000))
        val next = OpenTickSnapshot.updated(old, "EUR/USD", null, old.keys, now)
        assertFalse("EUR/USD" in next)
        assertEquals(old.getValue("XAU/USD"), next.getValue("XAU/USD"))
    }

    @Test fun `a closed or stale trade tick cannot be carried forward`() {
        val old = mapOf("EUR/USD" to PriceTick(1.1, now - 50_000),
            "XAU/USD" to PriceTick(2600.0, now - 1_000))
        val next = OpenTickSnapshot.updated(old, "XAU/USD", PriceTick(2601.0, now),
            setOf("XAU/USD"), now)
        assertFalse("EUR/USD" in next)
        assertEquals(2601.0, next.getValue("XAU/USD").price, 0.0)
    }
}
