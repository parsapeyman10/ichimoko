package com.aurum.edge.core

import com.aurum.edge.domain.SignalAction

/** Explicit fixed heuristic, not learned weights or a calibrated probability. */
object WeightedTechnicalRating {
    const val H1_WEIGHT = 0.4
    const val H4_WEIGHT = 0.6
    const val STRONG_OPPOSITION = -0.5
    fun shouldExit(score: Double?, netUsd: Double?, riskUsd: Double, freshQuote: Boolean, marketOpen: Boolean): Boolean =
        freshQuote && marketOpen && riskUsd.isFinite() && riskUsd > 0.0 && netUsd != null && netUsd.isFinite() &&
            netUsd >= 1.5 * riskUsd && score != null && score.isFinite() && score <= STRONG_OPPOSITION

    fun score(action: SignalAction, h1: TechnicalRating?, h4: TechnicalRating?): Double? {
        if (action != SignalAction.BUY && action != SignalAction.SELL) return null
        if (h1 == null || h4 == null || !h1.available || !h4.available) return null
        val sign = if (action == SignalAction.BUY) 1 else -1
        return sign * (H1_WEIGHT * h1.score.toDouble() / h1.total + H4_WEIGHT * h4.score.toDouble() / h4.total)
    }
}
