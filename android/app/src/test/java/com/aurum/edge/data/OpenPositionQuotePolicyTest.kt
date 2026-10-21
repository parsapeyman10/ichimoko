package com.aurum.edge.data

import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.FeedStatus
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class OpenPositionQuotePolicyTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    private fun trade(symbol: String, venue: String = "NOBITEX") = PaperTrade(
        id = "paper", symbol = symbol, interval = Interval.M5, action = SignalAction.BUY,
        entry = 100.0, stopLoss = 90.0, takeProfit = 120.0, confidence = 85.0,
        riskReward = 2.0, openedAt = now - 60_000L, cryptoVenue = venue,
    )
    private fun market(symbol: String, provider: String = "Swissquote") = MarketState(
        symbol = symbol, lastPrice = 105.0, lastTradeAt = now - 1_000L,
        feed = FeedStatus(FeedMode.LIVE, "", now, provider = provider),
    )

    @Test fun `selected Binance display tick cannot value crypto position without ordered replay`() {
        val btc = trade("BTC/USDT", BinanceSpotClient.VENUE)
        assertNull(OpenPositionQuotePolicy.selectedFallback(btc,
            market("BTC/USDT", "${BinanceSpotClient.PROVIDER} aggTrade"), now))
        assertNull(OpenPositionQuotePolicy.selectedFallback(trade("BTC/USDT"),
            market("BTC/USDT", "Nobitex"), now))
    }

    @Test fun `a pre-entry tick cannot value a newly opened trade even when still fresh`() {
        val newer = trade("EUR/USD").copy(openedAt = now - 500L)
        val beforeEntry = PriceTick(105.0, now - 1_000L)
        assertNull(OpenPositionQuotePolicy.monitored(newer, beforeEntry, now))
        assertNull(OpenPositionQuotePolicy.selectedFallback(newer, market("EUR/USD"), now))
        assertEquals(emptyMap<String, Double>(), OpenPositionQuotePolicy.prices(
            listOf(newer), mapOf(newer.symbol to beforeEntry), now))
        assertEquals(105.0, OpenPositionQuotePolicy.monitored(newer,
            PriceTick(105.0, now - 100L), now)!!.price, 0.0)
    }

    @Test fun `fresh same-symbol FX tick remains available but stale and cached ticks are not`() {
        val eur = trade("EUR/USD")
        val current = market("EUR/USD")
        assertEquals(105.0, OpenPositionQuotePolicy.selectedFallback(eur, current, now)!!.price, 0.0)
        assertNull(OpenPositionQuotePolicy.selectedFallback(eur, market("GBP/USD"), now))
        assertNull(OpenPositionQuotePolicy.selectedFallback(eur, current.copy(showingCachedData = true), now))
        assertNull(OpenPositionQuotePolicy.selectedFallback(eur,
            current.copy(lastTradeAt = now - 50_000L), now))
        assertNull(OpenPositionQuotePolicy.selectedFallback(eur,
            current.copy(feed = FeedStatus(FeedMode.OFFLINE, "", now)), now))
    }
}
