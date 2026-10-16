package com.aurum.edge.data

import java.util.Locale

/**
 * Top 5 high-volume, most actively traded USDT spot crypto pairs.
 * Each pair is assigned to its own dedicated exchange source (1-to-1) and uses the
 * compression & breakout (فشردگی و انفجار) trading rules on 1500 M5 candles + resampled M15/H1.
 */
object CryptoCatalog {
    data class CryptoSymbol(
        val id: String,
        val binance: String,
        val label: String,
        val digits: Int,
        val maxReasonableSpreadPct: Double = 0.25,
    )

    val symbols: List<CryptoSymbol> = listOf(
        CryptoSymbol("BTC/USDT", "BTCUSDT", "بیت‌کوین / تتر", 2),
        CryptoSymbol("ETH/USDT", "ETHUSDT", "اتریوم / تتر", 2),
        CryptoSymbol("SOL/USDT", "SOLUSDT", "سولانا / تتر", 3),
        CryptoSymbol("BNB/USDT", "BNBUSDT", "بی‌ان‌بی / تتر", 2),
        CryptoSymbol("XRP/USDT", "XRPUSDT", "ریپل / تتر", 4),
    )

    /** All 5 top crypto pairs are in the default watchlist and scanner. */
    val watchlistSeed: List<CryptoSymbol> = symbols
    val ids: List<String> = symbols.map { it.id }

    private val byId = symbols.associateBy { it.id }

    fun find(symbol: String): CryptoSymbol? = byId[symbol.trim().uppercase(Locale.ROOT)]
    fun isCrypto(symbol: String): Boolean = find(symbol) != null

    fun digitsFor(symbol: String): Int {
        val clean = symbol.trim().uppercase(Locale.ROOT)
        find(clean)?.let { return it.digits }
        return when {
            clean.startsWith("XAU") || clean == "DOW" || clean == "US30" -> 2
            clean.startsWith("XAG") -> 3
            clean.contains("JPY") -> 3
            clean.contains("/") && !clean.endsWith("USDT") -> 5
            else -> 2
        }
    }
}
