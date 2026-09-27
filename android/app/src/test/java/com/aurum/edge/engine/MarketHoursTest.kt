package com.aurum.edge.engine

import com.aurum.edge.core.MarketHours
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarketHoursTest {
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test fun `gold conservative weekend respects New York DST and resumes Sunday evening`() {
        assertFalse(MarketHours.forexWeekendClosed(at("2026-09-25T20:59:00Z"))) // Friday 16:59 EDT
        assertTrue(MarketHours.forexWeekendClosed(at("2026-09-25T21:00:00Z")))  // Friday 17:00 EDT
        assertTrue(MarketHours.forexWeekendClosed(at("2026-09-26T12:00:00Z")))
        assertTrue(MarketHours.forexWeekendClosed(at("2026-09-27T21:59:00Z"))) // Sunday 17:59 EDT
        assertFalse(MarketHours.forexWeekendClosed(at("2026-09-27T22:00:00Z")))
        assertTrue(MarketHours.forexWeekendClosed(at("2026-12-25T22:00:00Z"))) // Friday 17:00 EST
        assertFalse(MarketHours.forexWeekendClosed(at("2026-12-28T15:00:00Z"))) // weekday schedule, holiday unknown
    }

}
