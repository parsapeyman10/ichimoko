package com.aurum.edge.data

/**
 * Candles used to place levels and the executed entry/exit tick must belong to the
 * same dedicated crypto exchange venue.
 */
object CryptoVenuePolicy {
    fun compatible(symbol: String, historyProvider: String?, tickProvider: String?): Boolean {
        if (!CryptoCatalog.isCrypto(symbol)) return true
        if (historyProvider.isNullOrBlank() || tickProvider.isNullOrBlank()) return false
        if (historyProvider == BinanceSpotClient.PROVIDER &&
            tickProvider.startsWith(BinanceSpotClient.PROVIDER)) return true
        val dedicated = SourceRouting.cryptoVenueFor(symbol)?.provider ?: return false
        val prefix = dedicated.substringBefore(" (")
        return historyProvider.startsWith(prefix) && tickProvider.startsWith(prefix)
    }
}
