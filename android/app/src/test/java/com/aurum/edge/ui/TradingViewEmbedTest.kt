package com.aurum.edge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `chart has one widget with TradingView tools and no app studies or interval limits`() {
        val html = TradingViewEmbed.html("OANDA:XAUUSD")
        assertEquals(1, "embed-widget-advanced-chart.js".toRegex().findAll(html).count())
        assertEquals(1, "tradingview-widget-container__widget\"></div>".toRegex().findAll(html).count())
        assertTrue(html.contains("\"symbol\": \"OANDA:XAUUSD\""))
        assertTrue(html.contains("\"allow_symbol_change\": true"))
        assertTrue(html.contains("\"hide_top_toolbar\": false"))
        assertTrue(html.contains("\"hide_side_toolbar\": false"))
        assertTrue(html.contains("\"withdateranges\": true"))
        assertTrue(html.contains("\"save_image\": true"))
        assertTrue(html.contains("\"style\": \"1\"")) // TradingView candlesticks.
        assertFalse(html.contains("\"studies\"")) // Widget user chooses indicators.
        assertFalse(html.contains("<canvas")) // No app-drawn candles.
    }

    @Test fun `unknown symbols are handed to TradingView not blocked or changed into gold`() {
        assertTrue(TradingViewEmbed.html("NOT/REAL").contains("\"symbol\": \"NOT/REAL\""))
        val untrusted = TradingViewEmbed.html("</script><script>alert(1)</script>")
        assertFalse(untrusted.contains("</script><script>alert(1)</script>"))
        assertTrue(untrusted.contains("\\u003c/script>"))
    }
}
