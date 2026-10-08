package com.aurum.edge.data

import java.util.Locale

/**
 * Universal resolution of any app symbol (Forex, Metals, Commodities, Global Stocks, Cryptos)
 * to its exact TradingView chart ticker.
 */
object TradingViewSymbols {

    private val FIXED_SYMBOLS = mapOf(
        // Commodities & Metals
        "XAU/USD" to "OANDA:XAUUSD",
        "XAG/USD" to "OANDA:XAGUSD",
        "USOIL" to "TVC:USOIL",
        "UKOIL" to "TVC:UKOIL",
        "COPPER" to "CAPITALCOM:COPPER",

        // Forex Major & Cross Pairs
        "EUR/USD" to "OANDA:EURUSD",
        "GBP/USD" to "OANDA:GBPUSD",
        "AUD/USD" to "OANDA:AUDUSD",
        "NZD/USD" to "OANDA:NZDUSD",
        "USD/JPY" to "OANDA:USDJPY",
        "USD/CHF" to "OANDA:USDCHF",
        "USD/CAD" to "OANDA:USDCAD",
        "EUR/GBP" to "OANDA:EURGBP",
        "EUR/JPY" to "OANDA:EURJPY",
        "GBP/JPY" to "OANDA:GBPJPY",
        "AUD/JPY" to "OANDA:AUDJPY",
        "CAD/JPY" to "OANDA:CADJPY",
        "CHF/JPY" to "OANDA:CHFJPY",
        "NZD/JPY" to "OANDA:NZDJPY",
        "EUR/AUD" to "OANDA:EURAUD",
        "EUR/CAD" to "OANDA:EURCAD",
        "EUR/CHF" to "OANDA:EURCHF",
        "GBP/AUD" to "OANDA:GBPAUD",
        "GBP/CAD" to "OANDA:GBPCAD",
        "GBP/CHF" to "OANDA:GBPCHF",
        "AUD/CAD" to "OANDA:AUDCAD",
        "AUD/CHF" to "OANDA:AUDCHF",
        "AUD/NZD" to "OANDA:AUDNZD",
        "CAD/CHF" to "OANDA:CADCHF",
        "NZD/CAD" to "OANDA:NZDCAD",

        // Top Global Stocks & ETFs
        "AAPL" to "NASDAQ:AAPL",
        "TSLA" to "NASDAQ:TSLA",
        "NVDA" to "NASDAQ:NVDA",
        "MSFT" to "NASDAQ:MSFT",
        "AMZN" to "NASDAQ:AMZN",
        "GOOGL" to "NASDAQ:GOOGL",
        "META" to "NASDAQ:META",
        "AMD" to "NASDAQ:AMD",
        "NFLX" to "NASDAQ:NFLX",
        "INTC" to "NASDAQ:INTC",
        "SPY" to "AMEX:SPY",
        "QQQ" to "NASDAQ:QQQ",
        "PLTR" to "NYSE:PLTR",
        "COIN" to "NASDAQ:COIN",
        "BABA" to "NYSE:BABA",
    )

    fun find(symbol: String): String? {
        val key = symbol.trim().uppercase(Locale.ROOT)
        if (key.isBlank()) return null
        FIXED_SYMBOLS[key]?.let { return it }
        CryptoCatalog.find(key)?.let { return "BINANCE:${it.binance}" }
        val watch = WatchCatalog.find(key)
        if (watch != null) {
            val tv = watch.providerCodes["tradingview_scanner"]
            if (tv != null) return tv
        }
        return null
    }

    /**
     * قبلاً `of()` برای نمادِ نگاشت‌نشده بی‌صدا «OANDA:XAUUSD» برمی‌گرداند؛ یعنی چارت یک نماد
     * دیگر (طلا) به‌جای نماد انتخابی کاربر نمایش داده می‌شد. این تابع حذف شد: تنها راه، [find]
     * است که `null` می‌دهد و فراخوان باید صریحاً بگوید «این نماد نگاشت ندارد».
     */
    fun isChartable(symbol: String): Boolean = find(symbol) != null
}
