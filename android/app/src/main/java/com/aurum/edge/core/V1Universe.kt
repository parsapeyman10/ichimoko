package com.aurum.edge.core

import java.util.Locale

/** Six editable defaults for the three active risk buckets. This is a *watchlist*, not a closed catalog.
 * Additional symbols still need a real independently timestamped feed: a symbol string alone
 * never manufactures prices or authorizes a paper trade.
 */
object V1Universe {
    val defaults = listOf("EUR/USD", "GBP/USD", "XAU/USD", "XAG/USD",
        "BTC/USDT", "ETH/USDT")

    private val fiat = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD",
        "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "TRY", "ZAR", "MXN", "SGD", "HKD")

    fun normalize(raw: String): String = raw.trim().uppercase(Locale.ROOT)
    fun valid(raw: String): Boolean {
        val symbol = normalize(raw)
        return symbol.matches(Regex("[A-Z0-9]{1,12}(/[A-Z0-9]{2,12})?")) &&
            (symbol.contains("/") || symbol in setOf("USOIL", "UKOIL", "COPPER", "WTI", "BRENT", "NATGAS") ||
                (symbol.length == 6 && AssetClass.of(symbol) == AssetClass.FOREX &&
                    symbol.take(3) in fiat && symbol.takeLast(3) in fiat)) &&
            AssetClass.of(symbol) != AssetClass.STOCK &&
            !symbol.contains("IRT") && !symbol.contains("IRR") && symbol !in setOf("GOLD", "SILVER")
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
