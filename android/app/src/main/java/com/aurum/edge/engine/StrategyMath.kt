package com.aurum.edge.engine

import com.aurum.edge.core.SignalAction
import kotlin.math.max

/**
 * Shared math helpers that were duplicated verbatim across [BtcAresEngine], [EurAresEngine]
 * and [XagAresEngine]. Each engine had its own private copy of these functions; now there is
 * one implementation in one place.
 */
object StrategyMath {

    /**
     * Target price from entry, stop and reward:risk ratio. Identical in all three engines:
     * BUY → entry + rr × risk, SELL → entry − rr × risk.
     */
    fun targetAt(direction: SignalAction, entry: Double, stop: Double, rr: Double): Double {
        val risk = abs(entry - stop)
        return if (direction == SignalAction.BUY) entry + rr * risk else entry - rr * risk
    }

    /**
     * Percentile rank of the latest value inside its own lookback; ties count as half.
     * Identical logic in all three engines — a flat series never looks extreme.
     */
    fun atrPercentile(series: List<Double?>, lookback: Int): Double {
        val values = series.takeLast(lookback).filterNotNull().filter { it > 0.0 && it.isFinite() }
        if (values.isEmpty()) return 0.0
        val last = values.last()
        val below = values.count { it < last }
        val equal = values.count { it == last }
        return (below + 0.5 * equal) / values.size
    }

    /** Stop distance clamped between min and max ATR multiples, then applied to price. */
    fun stopForAtr(
        direction: SignalAction,
        raw: Double,
        price: Double,
        atr: Double,
        bufferAtr: Double,
        minStopAtr: Double,
        maxStopAtr: Double,
    ): Double {
        val distance = max(abs(price - raw) + bufferAtr * atr, minStopAtr * atr)
            .coerceAtMost(maxStopAtr * atr)
        return if (direction == SignalAction.BUY) price - distance else price + distance
    }
}
