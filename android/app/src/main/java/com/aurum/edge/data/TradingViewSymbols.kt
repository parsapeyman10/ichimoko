package com.aurum.edge.data

/**
 * The one place that maps an app symbol to a TradingView ticker.
 *
 * It used to live inline in the chart with a `else -> OANDA:XAUUSD` fallback, so any
 * symbol the list forgot silently rendered GOLD instead — the chart showed a different
 * instrument from the one selected, with no error. A total mapping plus a null for the
 * unknown case makes that impossible.
 *
 * The same string is used for the chart and for the TradingView quote in the watchlist,
 * so a price and a chart can never refer to different venues.
 */
object TradingViewSymbols {

    /** Spot FX and metals quoted by OANDA, which TradingView carries for every pair here. */
    private val FOREX = mapOf(
        "XAU/USD" to "OANDA:XAUUSD",
        "XAG/USD" to "OANDA:XAGUSD",
        "EUR/USD" to "OANDA:EURUSD",
        "GBP/USD" to "OANDA:GBPUSD",
        "AUD/USD" to "OANDA:AUDUSD",
        "NZD/USD" to "OANDA:NZDUSD",
        "USD/JPY" to "OANDA:USDJPY",
        "USD/CHF" to "OANDA:USDCHF",
        "USD/CAD" to "OANDA:USDCAD",
        "EUR/JPY" to "OANDA:EURJPY",
        "GBP/JPY" to "OANDA:GBPJPY",
        "EUR/GBP" to "OANDA:EURGBP",
    )

    /** Null when the symbol is not chartable, so a caller must handle it explicitly. */
    fun find(symbol: String): String? {
        val key = symbol.trim().uppercase()
        FOREX[key]?.let { return it }
        CryptoCatalog.find(key)?.let { return "BINANCE:${it.binance}" }
        return null
    }

    /**
     * Chart-safe resolution. Gold is the documented default for an unknown symbol, but
     * unlike the old inline fallback the caller can tell the difference via [find].
     */
    fun of(symbol: String): String = find(symbol) ?: "OANDA:XAUUSD"

    fun isChartable(symbol: String): Boolean = find(symbol) != null
}
