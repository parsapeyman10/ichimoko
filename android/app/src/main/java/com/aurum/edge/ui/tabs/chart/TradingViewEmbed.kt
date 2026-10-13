package com.aurum.edge.ui

import com.aurum.edge.core.Interval
import com.aurum.edge.engine.SignalEngine
import java.net.URLEncoder

/** One iframe using the chart-loading pattern of CI run #350 (source a04d600).
 * The older screen also had a symbol picker and extra cards; only its widget is restored here.
 * There is no full-site fallback or internally drawn chart.
 */
internal object TradingViewEmbed {
    private val ticker = Regex("^[A-Za-z0-9:./_-]{1,64}$")

    fun html(symbol: String): String? {
        if (!ticker.matches(symbol) || com.aurum.edge.data.ChartSymbolChoices.all.none { it.ticker == symbol }) return null
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val ichi = SignalEngine.ichimokuSetting(Interval.M5)
        // Public widgetembed accepts a studies_overrides JSON query in examples. Request
        // the same 8/24/72 and 24-bar displacement as the V1 engine, not TradingView's
        // usual 9/26/52 defaults. Third-party widget rendering must still be checked on
        // device: these cross-origin inputs cannot be read back or locked by the app.
        val overrides = "{" + listOf(
            "\"ichimoku cloud.conversion line periods\":${ichi.tenkan}",
            "\"ichimoku cloud.base line periods\":${ichi.kijun}",
            "\"ichimoku cloud.leading span b periods\":${ichi.spanB}",
            "\"ichimoku cloud.lagging span periods\":${ichi.kijun}",
        ).joinToString(",") + "}"
        val encodedOverrides = URLEncoder.encode(overrides, "UTF-8")
        val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart" +
            // A new chart (including a symbol change) always requests M5; the independent
            // signal/feed interval is NOT changed. The third-party iframe can still let a
            // person select another timeframe manually while it remains open.
            "&symbol=$encoded&interval=5&hidesidetoolbar=0&symboledit=0" +
            "&saveimage=0&toolbarbg=0b0e13" +
            "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
            "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D" +
            "&studies_overrides=$encodedOverrides"
        return """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>html,body {margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#0b0e13}
iframe {width:100%;height:100%;border:none}</style>
<script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script></head>
<body><iframe src="$iframeUrl" allowtransparency="true" frameborder="0"></iframe></body></html>""".trimIndent()
    }
}
