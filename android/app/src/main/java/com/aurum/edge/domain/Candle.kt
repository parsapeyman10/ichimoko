package com.aurum.edge.domain

import kotlinx.serialization.Serializable

/**
 * Real market data contract: one closed (or forming) OHLC bar.
 * Lives in [domain] so both [core] (indicators, rules) and [data] (repository, clients)
 * can depend on it without creating a core ↔ data cycle.
 */
@Serializable
data class Candle(
    /** Bar open time, epoch millis (UTC). */
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
    /** false only for the bar that is still forming right now. */
    val closed: Boolean = true,
) {
}
