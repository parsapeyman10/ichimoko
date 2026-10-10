package com.aurum.edge.ui

import java.net.URLEncoder

/** Load the TradingView chart directly: no HTML wrapper, third-party script or iframe bootstrap. */
internal object TradingViewEmbed {
    private fun encoded(symbol: String): String = URLEncoder.encode(symbol, "UTF-8")

    fun widgetUrl(symbol: String): String =
        "https://s.tradingview.com/widgetembed/?frameElementId=aurum_chart" +
            "&symbol=${encoded(symbol)}&interval=60&theme=dark&style=1" +
            "&hidesidetoolbar=0&symboledit=1&withdateranges=1&saveimage=1"

    /** Fallback is the full provider site, opened by the user's browser (never a made-up chart). */
    fun browserUrl(symbol: String): String =
        "https://www.tradingview.com/chart/?symbol=${encoded(symbol)}"
}
