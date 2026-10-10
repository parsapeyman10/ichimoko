package com.aurum.edge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `direct widget and browser fallback retain the requested exact ticker`() {
        val widget = TradingViewEmbed.widgetUrl("OANDA:XAUUSD")
        assertTrue(widget.startsWith("https://s.tradingview.com/widgetembed/?"))
        assertTrue(widget.contains("symbol=OANDA%3AXAUUSD"))
        assertTrue(widget.contains("hidesidetoolbar=0"))
        assertTrue(widget.contains("symboledit=1"))
        val browser = TradingViewEmbed.browserUrl("NASDAQ:AAPL")
        assertTrue(browser.startsWith("https://www.tradingview.com/chart/?"))
        assertTrue(browser.contains("symbol=NASDAQ%3AAAPL"))
        assertFalse(TradingViewEmbed.widgetUrl("BAD&symbol=OTHER").contains("&symbol=OTHER"))
    }
}
