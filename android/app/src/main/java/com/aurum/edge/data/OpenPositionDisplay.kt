package com.aurum.edge.data

import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy

/** A UI-only last-known observation. Never feed this into journal settlement, risk, or live equity. */
data class DisplayMark(
    val tradeId: String,
    val symbol: String,
    val openedAt: Long,
    val price: Double,
    /** The publisher's original tick time, never the time the UI was redrawn. */
    val tickAt: Long,
)

object OpenPositionDisplay {
    fun record(previous: Map<String, DisplayMark>, trade: PaperTrade, tick: PriceTick,
               now: Long = System.currentTimeMillis()): Map<String, DisplayMark> {
        if (!trade.isOpen || tick.at < trade.openedAt || !TradeQuotePolicy.accepts(tick, now))
            return previous
        val existing = forTrade(trade, previous)
        if (existing != null && tick.at < existing.tickAt) return previous
        return previous + (trade.id to DisplayMark(trade.id, trade.symbol, trade.openedAt, tick.price, tick.at))
    }

    fun forTrade(trade: PaperTrade, marks: Map<String, DisplayMark>): DisplayMark? =
        marks[trade.id]?.takeIf { trade.isOpen && it.symbol == trade.symbol &&
            it.openedAt == trade.openedAt && it.tickAt >= trade.openedAt &&
            it.price.isFinite() && it.price > 0.0 }

    fun onlyOpen(previous: Map<String, DisplayMark>, trades: List<PaperTrade>): Map<String, DisplayMark> =
        trades.mapNotNull { trade -> forTrade(trade, previous)?.let { trade.id to it } }.toMap()
}
