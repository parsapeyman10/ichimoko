package com.aurum.edge.engine

import com.aurum.edge.data.PublicCryptoState
import com.aurum.edge.data.PublicCryptoStatus
import com.aurum.edge.data.parsePublicCoins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PublicCryptoMarketTest {
    private val at = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val row = """{"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":80000.0,
        "market_cap":1600000000000,"total_volume":30000000000,"price_change_percentage_24h":-2.5,
        "last_updated":"2026-09-25T11:58:00Z"}"""
    private fun parse(s: String) = parsePublicCoins(Json.parseToJsonElement(s) as JsonArray, at)

    @Test fun `dated coingecko public row is a single source observation not a candidate`() {
        val result = parse("[$row]").single()
        assertEquals("BTC", result.code)
        assertEquals(-2.5, result.change24hPct!!, 0.001)
        assertEquals(at - 120_000L, result.providerAt)
        val snapshot = PublicCryptoState(PublicCryptoStatus.OBSERVED, listOf(result), at)
        assertTrue(snapshot.recent(at + 30_000L))
        assertFalse(snapshot.recent(at + 610_000L))
        assertFalse(snapshot.copy(status = PublicCryptoStatus.UNAVAILABLE).recent(at))
    }

    @Test fun `old source timestamp or missing price fails instead of fabricating quote`() {
        assertTrue(runCatching { parse("[${row.replace("11:58:00", "11:30:00") }]") }.isFailure)
        assertTrue(runCatching { parse("[${row.replace("\"current_price\":80000.0", "\"current_price\":null")}]") }.isFailure)
    }
}
