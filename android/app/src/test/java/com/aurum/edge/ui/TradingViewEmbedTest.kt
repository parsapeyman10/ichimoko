package com.aurum.edge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `single direct widget retains the requested exact ticker`() {
        val widget = TradingViewEmbed.widgetUrl("OANDA:XAUUSD")
        assertTrue(widget.startsWith("https://s.tradingview.com/widgetembed/?"))
        assertTrue(widget.contains("symbol=OANDA%3AXAUUSD"))
        assertTrue(widget.contains("hidesidetoolbar=0"))
        assertTrue(widget.contains("symboledit=1"))
        assertFalse(TradingViewEmbed.widgetUrl("BAD&symbol=OTHER").contains("&symbol=OTHER"))
    }
}
