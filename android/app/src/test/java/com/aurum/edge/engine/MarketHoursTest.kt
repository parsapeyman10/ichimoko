package com.aurum.edge.engine

import com.aurum.edge.core.MarketHours
import org.junit.Assert.assertEquals
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

    @Test fun `home schedule exposes next close or next open in UTC millis`() {
        val open = MarketHours.sessionWindow(at("2026-09-24T12:00:00Z")) // Thursday EDT
        assertFalse(open.closed)
        assertEquals("بسته‌شدن بازار", open.nextChangeLabel)
        assertEquals(at("2026-09-24T21:00:00Z"), open.nextChangeAt!!) // daily gold break

        val closed = MarketHours.sessionWindow(at("2026-09-26T12:00:00Z")) // Saturday EDT
        assertTrue(closed.closed)
        assertEquals("بازشدن بازار", closed.nextChangeLabel)
        assertEquals(at("2026-09-27T22:00:00Z"), closed.nextChangeAt!!)
    }

    @Test fun `weekend and weekday schedules differ for FX gold US shares and crypto`() {
        val sunday1730 = at("2026-09-27T21:30:00Z") // 17:30 EDT
        assertFalse(MarketHours.closedFor("EUR/USD", sunday1730))
        assertTrue(MarketHours.closedFor("XAU/USD", sunday1730))
        assertTrue(MarketHours.closedFor("AAPL", sunday1730))
        assertFalse(MarketHours.closedFor("BTC/USDT", sunday1730))
        assertEquals(at("2026-09-27T21:00:00Z"), MarketHours.sessionWindowFor(
            "EUR/USD", at("2026-09-26T12:00:00Z")).nextChangeAt!!)
        assertEquals(at("2026-09-27T22:00:00Z"), MarketHours.sessionWindowFor(
            "XAU/USD", at("2026-09-26T12:00:00Z")).nextChangeAt!!)

        val monday1730 = at("2026-09-28T21:30:00Z")
        assertTrue(MarketHours.closedFor("XAU/USD", monday1730))
        assertFalse(MarketHours.closedFor("EUR/USD", monday1730))
        assertEquals(at("2026-09-28T22:00:00Z"), MarketHours.sessionWindowFor(
            "XAU/USD", monday1730).nextChangeAt!!)
        val monday1530 = at("2026-09-28T19:30:00Z")
        assertFalse(MarketHours.closedFor("AAPL", monday1530))
        assertTrue(MarketHours.closedFor("AAPL", at("2026-09-28T20:00:00Z")))
        assertFalse(MarketHours.sessionWindowFor("BTC/USDT", sunday1730).closed)
        assertEquals(null, MarketHours.sessionWindowFor("BTC/USDT", sunday1730).nextChangeAt)
    }

}
