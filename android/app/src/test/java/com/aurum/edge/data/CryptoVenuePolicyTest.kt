package com.aurum.edge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoVenuePolicyTest {
    @Test fun `crypto entry needs both Binance Spot history and aggregate trade feed`() {
        assertTrue(CryptoVenuePolicy.compatible("BTC/USDT", BinanceSpotClient.PROVIDER,
            "Binance Spot / USDT aggTrade"))
        assertTrue(CryptoVenuePolicy.compatible("ETH/USDT", BinanceSpotClient.PROVIDER,
            "Binance Spot / USDT aggTrade"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", "Twelve Data · fallback", "Nobitex /v2/trades"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", NobitexHistoryClient.PROVIDER, "Binance Spot / USDT aggTrade"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", null, "Nobitex /v2/trades"))
        assertTrue(CryptoVenuePolicy.compatible("XAU/USD", "Yahoo", "Swissquote"))
    }
}
