package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy

/** One provider completion changes only its own open symbol; unknown/stale is not a price. */
object OpenTickSnapshot {
    fun updated(previous: Map<String, PriceTick>, symbol: String, quote: PriceTick?,
                open: Set<String>, now: Long = System.currentTimeMillis()): Map<String, PriceTick> {
        val current = previous.filter { (id, tick) -> id in open && TradeQuotePolicy.accepts(tick, now) }
        return if (symbol in open && quote != null && TradeQuotePolicy.accepts(quote, now))
            current + (symbol to quote)
        else current - symbol
    }
}
