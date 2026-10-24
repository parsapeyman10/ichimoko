package com.aurum.edge.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `one widget adds the correct Ichimoku study on the chart for the exact symbol`() {
        val html = TradingViewEmbed.html("OANDA:XAUUSD")!!
        assertEquals(1, Regex("new TradingView\\.widget\\(").findAll(html).count())
        assertTrue(html.contains("https://s3.tradingview.com/tv.js"))
        val config = Json.parseToJsonElement(html.substringAfter("new TradingView.widget(").substringBefore(");</script>")).jsonObject
        assertEquals("OANDA:XAUUSD",config["symbol"]!!.jsonPrimitive.content)
        assertEquals("5",config["interval"]!!.jsonPrimitive.content)
        assertFalse(config["allow_symbol_change"]!!.jsonPrimitive.boolean)
        assertEquals("STD;Ichimoku%1Cloud",config["studies"]!!.jsonArray.single().jsonPrimitive.content)
        val overrides = config["studies_overrides"]!!.jsonObject
        assertEquals(8,overrides["ichimoku cloud.conversion line periods"]!!.jsonPrimitive.int)
        assertEquals(24,overrides["ichimoku cloud.base line periods"]!!.jsonPrimitive.int)
        assertEquals(72,overrides["ichimoku cloud.leading span b periods"]!!.jsonPrimitive.int)
        assertFalse(html.contains("Ichimoku%Cloud"))
    }
    @Test fun `invalid or injection ticker is rejected instead of substituting gold`() {
        listOf("BAD&symbol=OTHER","</script>","NASDAQ:AAPL").forEach { assertNull(TradingViewEmbed.html(it)) }
        assertTrue(TradingViewEmbed.html("BINANCE:ETHUSDT")!!.contains("BINANCE:ETHUSDT"))
        assertFalse(TradingViewEmbed.html("BINANCE:ETHUSDT")!!.contains("OANDA:XAUUSD"))
    }
}
