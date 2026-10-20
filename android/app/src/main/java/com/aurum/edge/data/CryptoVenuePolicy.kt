package com.aurum.edge.data

/**
 * Crypto venue identity: candles used to place levels and the executed entry/exit tick must
 * belong to ONE exchange.
 *
 * Nobitex is the reference venue: `Nobitex history + Nobitex executed trade` is the normal,
 * allowed pair. Legacy positions on the per-symbol exchange (Binance Spot, historically
 * OKX/Gate/MEXC/Kraken read-only radar frames) remain readable and settle only against their
 * own venue — labels are never swapped to make a cross-venue pair look valid.
 */
object CryptoVenuePolicy {
    fun compatible(symbol: String, historyProvider: String?, tickProvider: String?): Boolean {
        if (!CryptoCatalog.isCrypto(symbol)) return true
        return sameVenue(historyProvider, tickProvider)
    }

    /** True only when both providers resolve to the SAME live venue. */
    fun sameVenue(historyProvider: String?, tickProvider: String?): Boolean {
        if (historyProvider.isNullOrBlank() || tickProvider.isNullOrBlank()) return false
        val historyVenue = venueOf(historyProvider) ?: return false
        return historyVenue == (venueOf(tickProvider) ?: return false)
    }

    /** Canonical venue id of a provider label; null = unknown, never guessed. */
    fun venueOf(provider: String?): String? {
        if (provider == null) return null
        return when {
            NobitexReference.isHistoryProvider(provider) -> NobitexReference.VENUE
            provider.startsWith(BinanceSpotClient.PROVIDER) -> BinanceSpotClient.VENUE
            provider.startsWith(CryptoRadarCandleClient.Venue.OKX.provider) -> "OKX_SPOT"
            provider.startsWith(CryptoRadarCandleClient.Venue.GATE.provider) -> "GATE_SPOT"
            provider.startsWith(CryptoRadarCandleClient.Venue.MEXC.provider) -> "MEXC_SPOT"
            provider.startsWith(CryptoRadarCandleClient.Venue.KRAKEN.provider) -> "KRAKEN_SPOT"
            else -> null
        }
    }

    /**
     * Venue a paper position must be recorded with, given the bars it was built on.
     *
     * Only two venues are writable: the Nobitex reference and the legacy Binance Spot venue.
     * Anything else (unknown label, read-only radar frame) falls back to the reference venue
     * instead of silently inheriting Binance — an unknown frame can never open on Binance.
     */
    fun entryVenue(historyProvider: String?): String =
        when (venueOf(historyProvider)) {
            BinanceSpotClient.VENUE -> BinanceSpotClient.VENUE
            else -> NobitexReference.VENUE
        }

    /** Venues whose live trade feed this app can actually stream for the selected market. */
    fun tickVenueAvailable(historyProvider: String?): Boolean {
        val venue = venueOf(historyProvider) ?: return false
        return venue == NobitexReference.VENUE || venue == BinanceSpotClient.VENUE
    }
}
