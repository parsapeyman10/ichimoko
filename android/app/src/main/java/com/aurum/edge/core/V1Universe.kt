package com.aurum.edge.core

import java.util.Locale

/**
 * Focused 15-symbol trading universe:
 * - 5 high-volume crypto pairs: BTC/USDT, ETH/USDT, SOL/USDT, BNB/USDT, XRP/USDT
 * - 2 precious metals: XAU/USD (Gold), XAG/USD (Silver)
 * - 7 Ichimoku-suitable forex pairs: EUR/USD, GBP/USD, USD/JPY, EUR/JPY, GBP/JPY, AUD/USD, USD/CHF
 * - 1 index: DOW (Dow Jones / US30)
 */
object V1Universe {
    val cryptoSymbols = listOf(
        "BTC/USDT",
        "ETH/USDT",
        "SOL/USDT",
        "BNB/USDT",
        "XRP/USDT",
    )

    val metalSymbols = listOf(
        "XAU/USD",
        "XAG/USD",
    )

    val forexSymbols = listOf(
        "EUR/USD",
        "GBP/USD",
        "USD/JPY",
        "EUR/JPY",
        "GBP/JPY",
        "AUD/USD",
        "USD/CHF",
    )

    val indexSymbols = listOf(
        "DOW",
    )

    val allSymbols: List<String> = metalSymbols + indexSymbols + forexSymbols + cryptoSymbols
    val defaults: List<String> = allSymbols
    private val allowedSet: Set<String> = allSymbols.toSet()

    fun normalize(raw: String): String {
        val cleaned = raw.trim().uppercase(Locale.ROOT)
        return when (cleaned) {
            "US30", "DJI", "^DJI", "DJIA", "DOWJONES", "DOW_JONES" -> "DOW"
            else -> cleaned
        }
    }

    fun valid(raw: String): Boolean {
        val symbol = normalize(raw)
        return symbol in allowedSet
    }

    fun watchlist(symbols: List<String>): List<String> =
        symbols.map(::normalize).filter(::valid).distinct()
}

enum class CategoryStrategy(val label: String) {
    TREND("روندگیر"), RANGE("رنج‌گیر"), HYBRID("ترکیبی/خودکار");

    companion object {
        fun fromStored(raw: String?): CategoryStrategy = entries.firstOrNull { it.name == raw } ?: HYBRID
    }
}
