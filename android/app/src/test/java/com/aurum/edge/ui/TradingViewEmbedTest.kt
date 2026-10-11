package com.aurum.edge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `one official embed script with the exact requested symbol`() {
        val html = TradingViewEmbed.html("OANDA:XAUUSD")
        assertNotNull(html)
        assertTrue(html!!.contains("external-embedding/embed-widget-advanced-chart.js"))
        assertTrue(html.contains("\"symbol\":\"OANDA:XAUUSD\""))
        assertTrue(html.contains("tradingview-widget-container__widget"))
        assertFalse(html.contains("widgetembed/?"))
        assertFalse(html.contains("<iframe")) // official script creates exactly one chart iframe
    }

    @Test fun `invalid or injection ticker is rejected instead of substituting gold`() {
        assertNull(TradingViewEmbed.html("BAD&symbol=OTHER"))
        assertNull(TradingViewEmbed.html("</script>"))
        assertTrue(TradingViewEmbed.html("NASDAQ:AAPL")!!.contains("\"symbol\":\"NASDAQ:AAPL\""))
    }
}
