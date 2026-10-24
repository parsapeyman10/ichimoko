package com.aurum.edge.data

import org.junit.Assert.assertEquals
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

    @Test fun `nobitex reference history and executed trade are the normal valid pair`() {
        assertTrue(CryptoVenuePolicy.compatible("BTC/USDT", NobitexHistoryClient.PROVIDER,
            NobitexReference.TICK_PROVIDER))
        assertTrue(CryptoVenuePolicy.compatible("SOL/USDT",
            "${NobitexHistoryClient.PROVIDER} / سه کندل واقعی M1", NobitexReference.TICK_PROVIDER))
        // Cross-venue pairs stay invalid in both directions.
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", NobitexHistoryClient.PROVIDER,
            "Binance Spot / USDT aggTrade"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", "Binance Spot / USDT",
            NobitexReference.TICK_PROVIDER))
        // A read-only radar venue is never a Nobitex substitute.
        assertFalse(CryptoVenuePolicy.compatible("ETH/USDT", "OKX Spot / USDT (radar only)",
            NobitexReference.TICK_PROVIDER))
    }

    @Test fun `paper entry venue follows the venue of the candles`() {
        assertEquals("NOBITEX", CryptoVenuePolicy.entryVenue(NobitexHistoryClient.PROVIDER))
        assertEquals(BinanceSpotClient.VENUE, CryptoVenuePolicy.entryVenue(BinanceSpotClient.PROVIDER))
        // Unknown / read-only frames never silently become Binance.
        assertEquals("NOBITEX", CryptoVenuePolicy.entryVenue(null))
        // A read-only radar frame or an unknown label is not a licence to record Binance.
        assertEquals("NOBITEX", CryptoVenuePolicy.entryVenue("OKX Spot / USDT (radar only)"))
        assertTrue(CryptoVenuePolicy.tickVenueAvailable(NobitexHistoryClient.PROVIDER))
        assertTrue(CryptoVenuePolicy.tickVenueAvailable(BinanceSpotClient.PROVIDER))
        assertFalse(CryptoVenuePolicy.tickVenueAvailable("Gate Spot / USDT (radar only)"))
        assertFalse(CryptoVenuePolicy.tickVenueAvailable(null))
    }
}
