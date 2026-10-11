package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal

/** Compare one closed-bar reading from the single V1 engine for each eligible crypto base.
 * A non-actionable high score never displaces an actionable reading. Risk/quote/news/AI
 * remain downstream vetoes, not reasons to invent another signal or bypass safeguards.
 */
internal object CryptoIntervalChoice {
    data class Read(val interval: Interval, val bars: List<Candle>, val signal: Signal)

    fun pick(reads: List<Read>): Read? = reads.filter { it.signal.isActionable }
        .sortedWith(compareByDescending<Read> { it.signal.confidence }
            .thenByDescending { it.signal.riskReward ?: 0.0 }
            .thenBy { it.interval.minutes }).firstOrNull()
        ?: reads.firstOrNull { it.interval == Interval.M5 } ?: reads.firstOrNull()
}
