package com.aurum.edge.ui

import com.aurum.edge.domain.Interval
import com.aurum.edge.engine.SignalEngine
import kotlinx.serialization.json.*

/** Official widget constructor owns ONE chart. Ichimoku is a study on that chart, not an
 * app label. Its ID contains the literal `%1`, not the old malformed `%Cloud` string. */
internal object TradingViewEmbed {
    private val ticker = Regex("^[A-Za-z0-9:./_-]{1,64}$")
    fun html(symbol: String): String? {
        if (!ticker.matches(symbol) || com.aurum.edge.data.ChartSymbolChoices.all.none { it.ticker == symbol }) return null
        val ichi = SignalEngine.ichimokuSetting(Interval.M5)
        val config = buildJsonObject {
            put("container_id", "tradingview_chart")
            put("autosize", true); put("symbol", symbol); put("interval", "5")
            put("timezone", "Etc/UTC"); put("theme", "dark"); put("style", "1"); put("locale", "en")
            put("allow_symbol_change", false); put("hide_side_toolbar", false)
            put("withdateranges", true); put("save_image", false)
            put("studies", buildJsonArray { add("STD;Ichimoku%1Cloud") })
            put("studies_overrides", buildJsonObject {
                put("ichimoku cloud.conversion line periods", ichi.tenkan)
                put("ichimoku cloud.base line periods", ichi.kijun)
                put("ichimoku cloud.leading span b periods", ichi.spanB)
                put("ichimoku cloud.lagging span periods", ichi.kijun)
            })
        }
        return """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>html,body,#tradingview_chart {margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#0b0e13}
iframe {width:100%;height:100%;border:none}</style></head>
<body><div id="tradingview_chart"></div>
<script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
<script>new TradingView.widget($config);</script></body></html>""".trimIndent()
    }
}
