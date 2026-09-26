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

    @Test fun `iran equities are not polled outside ordinary Tehran session`() {
        assertFalse(MarketHours.iranStockSessionScheduled(at("2026-09-25T07:00:00Z"))) // Friday
        assertFalse(MarketHours.iranStockSessionScheduled(at("2026-09-26T05:29:00Z"))) // Saturday 08:59
        assertTrue(MarketHours.iranStockSessionScheduled(at("2026-09-26T05:30:00Z"))) // Saturday 09:00
        assertTrue(MarketHours.iranStockSessionScheduled(at("2026-09-26T08:59:00Z"))) // 12:29
        assertFalse(MarketHours.iranStockSessionScheduled(at("2026-09-26T09:00:00Z"))) // 12:30
        assertTrue(MarketHours.labelForWorkspace("nobitex").contains("۲۴ساعته"))
    }
}
