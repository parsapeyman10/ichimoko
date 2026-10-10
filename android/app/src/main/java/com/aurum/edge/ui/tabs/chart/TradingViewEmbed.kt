package com.aurum.edge.ui

/** TradingView's official Advanced Chart embed. The widget owns symbols, intervals and studies. */
internal object TradingViewEmbed {
    fun html(initialTicker: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            html, body, .tradingview-widget-container, .tradingview-widget-container__widget {
              width: 100%; height: 100%; margin: 0; padding: 0; overflow: hidden;
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
                "symbol": ${jsonString(initialTicker)},
                "interval": "60",
                "theme": "dark",
                "style": "1",
                "locale": "en",
                "allow_symbol_change": true,
                "hide_top_toolbar": false,
                "hide_side_toolbar": false,
                "withdateranges": true,
                "save_image": true,
                "support_host": "https://www.tradingview.com"
              }
            </script>
          </div>
        </body>
        </html>
    """.trimIndent()

    // Do not let a symbol from a saved watchlist escape the embed's JSON or HTML script tag.
    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '<' -> append("\\u003c")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
