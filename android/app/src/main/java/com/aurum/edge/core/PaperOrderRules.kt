package com.aurum.edge.core

object PaperOrderRules {
    private val USD_CROSS_QUOTES = setOf("JPY", "CHF", "CAD")

    fun defaultLeverageFor(symbol: String): Int = VenueSpecs.of(symbol).leverageCap

    fun paperable(symbol: String): Boolean {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        if (s.isBlank() || s.contains("IRT") || s.contains("IRR")) return false
        return s.matches(Regex("[A-Z0-9]{1,12}(/[A-Z0-9]{2,12})?"))
    }

    fun unitFor(symbol: String): String {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        return when {
            s.startsWith("XAU") || s.startsWith("XAG") -> "oz"
            s.startsWith("BRENT") || s.startsWith("WTI") || s.startsWith("USOIL") || s.startsWith("UKOIL") -> "bbl"
            s.startsWith("NATGAS") -> "mmbtu"
            s.startsWith("COPPER") -> "lbs"
            s.endsWith("USDT") || s.startsWith("BTC") || s.startsWith("ETH") || s.startsWith("SOL") || s.startsWith("TON") -> "coins"
            s.contains("/") -> s.substringBefore('/').take(8).ifBlank { "units" }
            else -> "shares"
        }
    }

    fun quotePnlToUsd(symbol: String, pnlInQuote: Double, price: Double): Double {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        return if ((s.startsWith("USD/") || s.startsWith("USD")) && USD_CROSS_QUOTES.any { s.endsWith(it) }) {
            if (price > 0.0) pnlInQuote / price else pnlInQuote
        } else {
            pnlInQuote
        }
    }
}
