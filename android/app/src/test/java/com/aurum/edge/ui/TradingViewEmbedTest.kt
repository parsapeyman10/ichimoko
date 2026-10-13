package com.aurum.edge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `one historical TradingView iframe for the exact requested symbol`() {
        val html = TradingViewEmbed.html("OANDA:XAUUSD")
        assertNotNull(html)
        assertEquals(1, Regex("<iframe\\b").findAll(html!!).count())
        assertTrue(html.contains("https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart"))
        assertTrue(html.contains("https://s3.tradingview.com/tv.js"))
        assertTrue(html.contains("symbol=OANDA%3AXAUUSD"))
        assertTrue(html.contains("&interval=5&"))
        assertTrue(html.contains("&symboledit=0"))
        assertFalse(html.contains("&interval=60&"))
        assertTrue(html.contains("studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"))
        val encoded = html.substringAfter("studies_overrides=").substringBefore('"')
        val overrides = java.net.URLDecoder.decode(encoded, "UTF-8")
        assertTrue(overrides.contains("\"ichimoku cloud.conversion line periods\":8"))
        assertTrue(overrides.contains("\"ichimoku cloud.base line periods\":24"))
        assertTrue(overrides.contains("\"ichimoku cloud.leading span b periods\":72"))
        assertTrue(overrides.contains("\"ichimoku cloud.lagging span periods\":24"))
        assertFalse(html.contains("external-embedding/embed-widget-advanced-chart.js"))
    }

    @Test fun `invalid or injection ticker is rejected instead of substituting gold`() {
        assertNull(TradingViewEmbed.html("BAD&symbol=OTHER"))
        assertNull(TradingViewEmbed.html("</script>"))
        assertNull(TradingViewEmbed.html("NASDAQ:AAPL"))
        assertTrue(TradingViewEmbed.html("BINANCE:ETHUSDT")!!.contains("symbol=BINANCE%3AETHUSDT"))
        assertFalse(TradingViewEmbed.html("BINANCE:ETHUSDT")!!.contains("OANDA%3AXAUUSD"))
        assertTrue(TradingViewEmbed.html("BINANCE:ATOMUSDT")!!.contains("&interval=5&"))
        assertTrue(TradingViewEmbed.html("BINANCE:ETHUSDT")!!.contains("&interval=5&"))
    }
}
