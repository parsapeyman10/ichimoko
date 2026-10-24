package com.aurum.edge.engine

import com.aurum.edge.data.TwelveDataClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Network-free tests: a WS event must not manufacture a fresh quote for another symbol. */
class LiveQuoteValidationTest {
    private val client = TwelveDataClient()
    private val now = 1_800_000_000_000L

    private fun event(symbol: String = "XAU/USD", timestamp: String = "1799999990", price: String = "3100.75",
                      bid: String? = null, ask: String? = null) =
        Json.parseToJsonElement(
            """{"event":"price","symbol":"$symbol","timestamp":$timestamp,"price":$price${bid?.let { ",\"bid\":$it" } ?: ""}${ask?.let { ",\"ask\":$it" } ?: ""}}"""
        ).jsonObject

    @Test fun exactSymbolAndRecentTimestampRequired() {
        val quote = client.parsePriceEvent(event(), "XAU/USD", now)
        assertEquals(3100.75, quote!!.price, 0.001)
        assertEquals(now - 10_000L, quote.at)
        val quoted = client.parsePriceEvent(event(bid = "3100.70", ask = "3100.90"), "XAU/USD", now)!!
        assertEquals(3100.70, quoted.bid!!, 0.001)
        assertEquals(3100.90, quoted.ask!!, 0.001)
        assertNull(client.parsePriceEvent(event(bid = "3100.90", ask = "3100.70"), "XAU/USD", now)?.spread)
        assertNull(client.parsePriceEvent(event(symbol = "BTC/USD"), "XAU/USD", now))
        assertNull(client.parsePriceEvent(event(timestamp = "1799999800"), "XAU/USD", now))
        assertNull(client.parsePriceEvent(event(timestamp = "1800000100"), "XAU/USD", now))
        assertNull(client.parsePriceEvent(event(timestamp = "null"), "XAU/USD", now))
        assertNull(client.parsePriceEvent(event(price = "-7"), "XAU/USD", now))
        assertNull(client.parsePriceEvent(event(price = "null"), "XAU/USD", now))
    }
}
