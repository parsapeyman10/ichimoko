package com.aurum.edge.data

/** Candles used to place levels and the executed entry/exit tick must belong to the
 * same market. Twelve Data fallback is permitted for visual history, never for a
 * Binance Spot paper entry. Other asset classes keep their existing feed policy.
 */
object CryptoVenuePolicy {
    fun compatible(symbol: String, historyProvider: String?, tickProvider: String?): Boolean =
        !CryptoCatalog.isCrypto(symbol) ||
            (historyProvider == BinanceSpotClient.PROVIDER &&
                tickProvider?.startsWith(BinanceSpotClient.PROVIDER) == true)
}
