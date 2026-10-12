package com.aurum.edge.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoVenuePolicyTest {
    @Test fun `crypto entry needs both Nobitex history and executed trade feed`() {
        assertTrue(CryptoVenuePolicy.compatible("BTC/USDT", NobitexHistoryClient.PROVIDER,
            "Nobitex /v2/trades (معاملهٔ زمان‌دار)"))
        assertTrue(CryptoVenuePolicy.compatible("ETH/USDT", "${NobitexHistoryClient.PROVIDER} / سه کندل واقعی M1",
            "Nobitex public trades"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", "Twelve Data · fallback", "Nobitex /v2/trades"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", NobitexHistoryClient.PROVIDER, "Binance"))
        assertFalse(CryptoVenuePolicy.compatible("BTC/USDT", null, "Nobitex /v2/trades"))
        assertTrue(CryptoVenuePolicy.compatible("XAU/USD", "Yahoo", "Swissquote"))
    }
}
