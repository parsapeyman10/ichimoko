package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import java.util.Locale

/**
 * Resolution of the 15 supported app symbols (2 Metals, 1 Dow Jones Index,
 * 7 Ichimoku Forex pairs, and 5 high-volume Cryptos) to their TradingView chart tickers.
 */
object TradingViewSymbols {

    private val FIXED_SYMBOLS = mapOf(
        // Metals (Gold & Silver)
        "XAU/USD" to "OANDA:XAUUSD",
        "XAG/USD" to "OANDA:XAGUSD",

        // Index (Dow Jones)
        "DOW" to "BLACKBULL:US30",

        // Forex Pairs suitable for Ichimoku
        "EUR/USD" to "OANDA:EURUSD",
        "GBP/USD" to "OANDA:GBPUSD",
        "USD/JPY" to "OANDA:USDJPY",
        "EUR/JPY" to "OANDA:EURJPY",
        "GBP/JPY" to "OANDA:GBPJPY",
        "AUD/USD" to "OANDA:AUDUSD",
        "USD/CHF" to "OANDA:USDCHF",
    )

    fun find(symbol: String): String? {
        val raw = symbol.trim().uppercase(Locale.ROOT)
        if (raw.isBlank()) return null
        val key = V1Universe.normalize(raw)
        FIXED_SYMBOLS[key]?.let { return it }
        CryptoCatalog.find(key)?.let { return "BINANCE:${it.binance}" }
        return null
    }

    fun isChartable(symbol: String): Boolean = find(symbol) != null
}
