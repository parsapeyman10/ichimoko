package com.aurum.edge.data

import com.aurum.edge.domain.Interval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MexcRadarTest {
    private val client = CryptoRadarCandleClient()
    @Test fun `only active exact spot USDT listing is eligible`() {
        val valid = """{"symbols":[{"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","status":"1","isSpotTradingAllowed":true}]}"""
        fun accepted(body: String) = client.validMexcListing(Json.parseToJsonElement(body).jsonObject, "BTCUSDT", "BTC")
        assertTrue(accepted(valid))
        assertFalse(accepted(valid.replace("\"status\":\"1\"", "\"status\":\"2\"")))
        assertFalse(accepted(valid.replace("true", "false")))
        assertFalse(accepted(valid.replace("USDT", "USDC")))
    }
    @Test fun `closed bars retain actual timestamps and volume and reject wrong timeframe`() {
        val t = 1_800_000_000_000L / Interval.M5.millis * Interval.M5.millis
        val data = """[[$t,"100","102","99","101","12",${t+Interval.M5.millis-1},"1200"]]"""
        val c = client.parseMexc(data, Interval.M5, t + Interval.M5.millis).single()
        assertEquals(t, c.time); assertEquals(12.0, c.volume, 0.0)
        assertTrue(client.parseMexc(data, Interval.M5, t+100).isEmpty())
        assertThrows(DataFeedException::class.java) { client.parseMexc(data, Interval.M15, t+Interval.M15.millis) }
        assertThrows(DataFeedException::class.java) { client.parseMexc(data.replace("\"12\"", "\"-1\""), Interval.M5, t+Interval.M5.millis) }
    }
}
