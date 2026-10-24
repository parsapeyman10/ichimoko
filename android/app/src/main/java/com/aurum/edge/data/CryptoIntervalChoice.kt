package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.Signal

/** Legacy crypto selector kept for journal/scanner compatibility. Only 5m can
 * become an entry base; M15/H1 remain independent confirmation, never entry choices.
 */
internal object CryptoIntervalChoice {
    data class Read(val interval: Interval, val bars: List<Candle>, val signal: Signal)

    fun pick(reads: List<Read>): Read? = reads.firstOrNull { read ->
        read.interval == Interval.M5 && read.signal.interval == Interval.M5
    }
}
