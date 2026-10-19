package com.aurum.edge.core

/**
 * Adaptive monitoring cadence, proportional to the traded timeframe:
 *
 *  - Base universe sweep every [BASE_SWEEP_MS] (one minute — the floor for M5 work:
 *    a fresh closed-bar evaluation at least once per minute).
 *  - When any scanned symbol is a live candidate or its confidence is already
 *    near entry quality, the sweep tightens to [HOT_SWEEP_MS] so a forming
 *    high-score entry is re-evaluated several times inside one M5 bar.
 *  - Open positions are already polled in near-real-time by the service
 *    (1s non-crypto / 3s crypto trade ticks) and stay that way.
 *
 * Pure and deterministic so the cadence rules are unit-testable offline.
 */
object MonitorCadence {
    const val BASE_SWEEP_MS = 60_000L
    const val HOT_SWEEP_MS = 20_000L

    /** Confidence (0..100) at/above which a symbol counts as "near entry". */
    const val HOT_CONFIDENCE = 70.0

    private val HOT_STATES = setOf("candidate", "opened")

    fun isHot(state: String?, confidence: Double?): Boolean =
        state in HOT_STATES || (confidence != null && confidence >= HOT_CONFIDENCE)

    /** Next sweep delay given the latest scanner statuses (state, confidence) pairs. */
    fun sweepDelayMs(statuses: List<Pair<String?, Double?>>): Long =
        if (statuses.any { (state, confidence) -> isHot(state, confidence) }) HOT_SWEEP_MS
        else BASE_SWEEP_MS
}

// CI build trigger — no behavior change.
