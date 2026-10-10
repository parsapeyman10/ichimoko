package com.aurum.edge.ui

import com.aurum.edge.core.Interval
import com.aurum.edge.data.TradingViewSymbols

/** Official TradingView Advanced Chart embed. This is visual only, never the app's trade feed. */
internal object TradingViewEmbed {
    fun html(symbol: String, interval: Interval): String? {
        val ticker = TradingViewSymbols.find(symbol) ?: return null
        // All known tickers have a provider prefix. Refuse unexpected markup from any future
        // watchlist source instead of interpolating untrusted text into an executable script.
        if (!TICKER.matches(ticker)) return null
        val tvInterval = when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                html, body, .tradingview-widget-container, .tradingview-widget-container__widget {
                  width: 100%; height: 100%; margin: 0; padding: 0; overflow: hidden;
                  background: #0b0e13;
                }
              </style>
            </head>
            <body>
              <div class="tradingview-widget-container">
                <div class="tradingview-widget-container__widget"></div>
                <script type="text/javascript"
                  src="https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js" async>
                  {
                    "autosize": true,
                    "symbol": "$ticker",
                    "interval": "$tvInterval",
                    "timezone": "Etc/UTC",
                    "theme": "dark",
                    "style": "1",
                    "locale": "en",
                    "allow_symbol_change": true,
                    "hide_top_toolbar": false,
                    "hide_side_toolbar": false,
                    "withdateranges": true,
                    "save_image": true,
                    "studies": ["STD;Ichimoku%Cloud"],
                    "support_host": "https://www.tradingview.com"
                  }
                </script>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private val TICKER = Regex("[A-Za-z0-9._-]+:[A-Za-z0-9._-]+")
}
