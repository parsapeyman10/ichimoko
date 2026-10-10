package com.aurum.edge.ui

import com.aurum.edge.core.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingViewEmbedTest {
    @Test fun `one official advanced chart fills the tab with native TradingView controls`() {
        val html = TradingViewEmbed.html("XAU/USD", Interval.M5)!!
        assertEquals(1, "embed-widget-advanced-chart.js".toRegex().findAll(html).count())
        assertTrue(html.contains("\"symbol\": \"OANDA:XAUUSD\""))
        assertTrue(html.contains("\"interval\": \"5\""))
        assertTrue(html.contains("\"allow_symbol_change\": true"))
        assertTrue(html.contains("\"hide_top_toolbar\": false"))
        assertTrue(html.contains("\"hide_side_toolbar\": false"))
        assertTrue(html.contains("\"save_image\": true"))
        assertTrue(html.contains("\"studies\": [\"STD;Ichimoku%Cloud\"]"))
        assertFalse(html.contains("<canvas")) // No app-drawn candles on the chart tab.
    }

    @Test fun `every selected interval and symbol keeps its own TradingView identity`() {
        val tvIntervals = listOf("1", "5", "15", "30", "60", "240", "D")
        Interval.entries.zip(tvIntervals).forEach { (interval, tv) ->
            val html = TradingViewEmbed.html("EUR/USD", interval)!!
            assertTrue(html.contains("\"interval\": \"$tv\""))
            assertTrue(html.contains("\"symbol\": \"OANDA:EURUSD\""))
        }
        assertTrue(TradingViewEmbed.html("AAPL", Interval.D1)!!.contains("\"symbol\": \"NASDAQ:AAPL\""))
        assertNull(TradingViewEmbed.html("NOT/REAL", Interval.M5))
    }
}
