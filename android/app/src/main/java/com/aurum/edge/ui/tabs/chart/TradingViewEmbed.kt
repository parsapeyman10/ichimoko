package com.aurum.edge.ui

import kotlinx.serialization.json.JsonPrimitive

/** One official TradingView advanced-chart embed, not the iframe-only widgetembed endpoint
 * loaded as a top-level page. The embed script creates its own iframe inside this container.
 */
internal object TradingViewEmbed {
    private val ticker = Regex("^[A-Za-z0-9:./_-]{1,64}$")
    private const val SCRIPT = "https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js"

    fun html(symbol: String): String? {
        if (!ticker.matches(symbol)) return null // no alternate instrument or script injection
        val jsonSymbol = JsonPrimitive(symbol).toString()
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>html,body,.tradingview-widget-container,.tradingview-widget-container__widget {margin:0;width:100%;height:100%;overflow:hidden;background:#0b0e13}</style></head>
<body><div class="tradingview-widget-container"><div class="tradingview-widget-container__widget"></div>
<script type="text/javascript" src="$SCRIPT" async>
{"autosize":true,"symbol":$jsonSymbol,"interval":"60","timezone":"Etc/UTC","theme":"dark","style":"1","locale":"en","allow_symbol_change":true,"hide_side_toolbar":false,"save_image":false,"calendar":false,"support_host":"https://www.tradingview.com"}
</script></div></body></html>""".trimIndent()
    }
}
