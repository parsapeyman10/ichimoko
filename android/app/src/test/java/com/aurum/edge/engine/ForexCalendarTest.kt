package com.aurum.edge.engine

import com.aurum.edge.data.ForexCalendarState
import com.aurum.edge.data.parseForexCalendar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Real export *schema* only; no HTTP, fabricated event signal or mock entry. */
class ForexCalendarTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
    private val valid = """[
        {"title":"Non-Farm Payrolls","country":"USD","date":"2026-09-24T08:20:00-04:00","impact":"High"},
        {"title":"ECB Rate Decision","country":"EUR","date":"2026-09-24T14:00:00+02:00","impact":"High"}
    ]"""

    private fun parse(s: String, at: Long = now) = parseForexCalendar(Json.parseToJsonElement(s) as JsonArray, at)

    @Test fun timezonesAndHighImpactMarkerArePreservedWithoutClaimingResult() {
        val events = parse(valid)
        assertEquals(2, events.size)
        assertEquals(now + 20 * 60_000L, events.last().at)
        assertEquals("High", events.last().impact)
        assertEquals("USD", events.last().country)
        assertTrue(ForexCalendarState(events, now).online(now + 15 * 60_000L))
        assertTrue(ForexCalendarState(events, now).highImpactUsdWindow(now))
        assertFalse(ForexCalendarState(events, now).online(now + 21 * 60_000L))
        assertFalse(ForexCalendarState(events, now).highImpactUsdWindow(now + 21 * 60_000L))
        assertFalse(ForexCalendarState(events, now, error = "network").online(now))
    }

    @Test fun `forecast and prior are explicitly distinct from an actual report`() {
        val withNumbers = valid.replace("\"impact\":\"High\"}",
            "\"impact\":\"High\",\"forecast\":\"210K\",\"previous\":\"190K\"}")
        val first = parse(withNumbers).first { it.country == "USD" }
        assertEquals("210K", first.forecast)
        assertEquals("190K", first.previous)
        assertEquals(null, first.actual)
    }

    @Test fun staleOrTimezoneFreeWeekCannotMasqueradeAsCleanCalendar() {
        assertTrue(runCatching { parse(valid, now + 9 * 86_400_000L) }.isFailure)
        assertTrue(runCatching { parse(valid.replace("2026-09-24T08:20:00-04:00", "2026-09-24T08:20:00")) }.isFailure)
        assertTrue(runCatching { parse(valid.replace("High", "Unexpected")) }.isFailure)
        assertTrue(runCatching { parse(valid.replace("USD", "BTC")) }.isFailure)
    }
}
