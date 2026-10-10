package com.aurum.edge.core

/** One provenance gate shared by the selected market, the Home ticker and foreground service.
 * A historical candle is useful for chart analysis but must never impersonate a trade tick.
 */
object TradeQuotePolicy {
    fun accepts(tick: PriceTick, now: Long = System.currentTimeMillis()): Boolean =
        tick.isTradeTick && tick.price.isFinite() && tick.price > 0.0 &&
            tick.at in (now - 45_000L)..(now + 10_000L)
}
