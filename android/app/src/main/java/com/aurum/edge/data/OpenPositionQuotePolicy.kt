package com.aurum.edge.data

import com.aurum.edge.core.FeedLiveness
import com.aurum.edge.core.FeedMode
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy

/** The selected chart's latest quote is not evidence that an OPEN crypto position's
 * ordered, same-venue execution feed has been replayed. Crypto valuations must come
 * exclusively from OpenTradeTickMonitor; missing coverage is UNKNOWN, not a chart fill.
 */
object OpenPositionQuotePolicy {
    fun selectedFallback(trade: PaperTrade, market: MarketState,
                         now: Long = System.currentTimeMillis()): PriceTick? {
        if (!trade.isOpen || CryptoCatalog.isCrypto(trade.symbol) || trade.symbol != market.symbol ||
            MarketHours.closedFor(trade.symbol, now) || market.showingCachedData ||
            market.feed.mode != FeedMode.LIVE || !FeedLiveness.hasRecentReceipt(market.feed, now)) return null
        val price = market.lastPrice ?: return null
        val at = market.lastTradeAt ?: return null
        return PriceTick(price, at).takeIf { TradeQuotePolicy.accepts(it, now) }
    }
}
