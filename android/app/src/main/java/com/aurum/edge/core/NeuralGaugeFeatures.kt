package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction

/** Two directional vote balances plus explicit presence bits, from entry-time data only. */
object NeuralGaugeFeatures {
    const val COUNT = 4

    private fun pair(action: SignalAction, buy: Int, neutral: Int, sell: Int): List<Double> {
        val sign = when (action) {
            SignalAction.BUY -> 1.0
            SignalAction.SELL -> -1.0
            else -> return listOf(0.0, 0.0)
        }
        if (buy < 0 || neutral < 0 || sell < 0) return listOf(0.0, 0.0)
        val total = buy.toLong() + neutral.toLong() + sell.toLong()
        if (total <= 0L) return listOf(0.0, 0.0)
        val balance = (buy.toLong() - sell.toLong()).toDouble() / total.toDouble()
        return listOf(sign * balance, 1.0)
    }

    fun fromRatings(action: SignalAction, h1: TechnicalRating?, h4: TechnicalRating?): List<Double> =
        pair(action, h1?.buy ?: -1, h1?.neutral ?: -1, h1?.sell ?: -1) +
            pair(action, h4?.buy ?: -1, h4?.neutral ?: -1, h4?.sell ?: -1)

    fun fromTrade(trade: PaperTrade): List<Double> = if (trade.techSnapshotRebuilt) List(COUNT) { 0.0 } else
        pair(trade.action, trade.tech1hBuy, trade.tech1hNeutral, trade.tech1hSell) +
            pair(trade.action, trade.tech4hBuy, trade.tech4hNeutral, trade.tech4hSell)
}
