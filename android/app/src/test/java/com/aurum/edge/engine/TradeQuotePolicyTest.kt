package com.aurum.edge.engine

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeQuotePolicyTest {
    @Test fun `a recent provider tick is usable but a candle or stale quote is not`() {
        val now = 1_800_000_000_000L
        assertTrue(TradeQuotePolicy.accepts(PriceTick(123.0, now - 2_000L), now))
        assertFalse(TradeQuotePolicy.accepts(PriceTick(123.0, now, isTradeTick = false), now))
        assertFalse(TradeQuotePolicy.accepts(PriceTick(123.0, now - 46_000L), now))
        assertFalse(TradeQuotePolicy.accepts(PriceTick(123.0, now + 11_000L), now))
        assertFalse(TradeQuotePolicy.accepts(PriceTick(Double.NaN, now), now))
    }
}
