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
        assertTrue(html.contains("studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"))
        assertFalse(html.contains("external-embedding/embed-widget-advanced-chart.js"))
    }

    @Test fun `invalid or injection ticker is rejected instead of substituting gold`() {
        assertNull(TradingViewEmbed.html("BAD&symbol=OTHER"))
        assertNull(TradingViewEmbed.html("</script>"))
        assertTrue(TradingViewEmbed.html("NASDAQ:AAPL")!!.contains("symbol=NASDAQ%3AAAPL"))
        assertFalse(TradingViewEmbed.html("NASDAQ:AAPL")!!.contains("OANDA%3AXAUUSD"))
    }
}
