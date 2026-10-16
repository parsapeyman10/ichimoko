package com.aurum.edge.core

import kotlin.math.abs

/** DISPLAY-ONLY valuation from a freshly validated same-symbol quote.
 * An entry is a saved fill, NEVER a replacement for a missing current quote.
 * Source freshness/venue are checked by the producer of livePrices before calling this.
 */
object LiveTradeValuation {
    data class Snapshot(
        val price: Double,
        val netPnlUsd: Double,
        val pnlPercent: Double,
        val progress: Float,
    )

    fun from(trade: PaperTrade, freshPrice: Double?): Snapshot? {
        val price = freshPrice?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val pnlPerUnit = if (trade.action == SignalAction.BUY) price - trade.entry
            else trade.entry - price
        val gross = PaperOrderRules.quotePnlToUsd(trade.symbol, pnlPerUnit * trade.positionOz, price)
        val net = gross - trade.effectiveCommissionUsd - trade.effectiveSpreadCostUsd
        if (!net.isFinite()) return null
        val range = abs(trade.takeProfit - trade.stopLoss).coerceAtLeast(0.000001)
        val progress = (if (trade.action == SignalAction.BUY)
            (price - trade.stopLoss) / range else (trade.stopLoss - price) / range)
            .toFloat().coerceIn(0f, 1f)
        return Snapshot(price, net,
            if (trade.entry > 0.0) pnlPerUnit / trade.entry * 100.0 else 0.0,
            progress)
    }

    /** The account's equity is unknown if even one open position has no fresh price. */
    fun total(openTrades: List<PaperTrade>, livePrices: Map<String, Double>): Double? {
        val values = openTrades.map { trade -> from(trade, livePrices[trade.symbol])?.netPnlUsd }
        return if (values.any { it == null }) null else values.filterNotNull().sum()
    }
}
