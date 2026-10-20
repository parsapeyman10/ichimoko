package com.aurum.edge.core

import kotlin.math.abs

/**
 * BTC-ARES — per-engine report and aggregate record for BTC/USDT.
 *
 * One record per engine is kept *separately* on purpose: the trade tab shows Engine A/B/C/D/E as
 * their own rows, so a reader can see that, for example, the trend engine is waiting while the
 * sweep engine is ready, instead of receiving one collapsed score that hides which model spoke.
 *
 * [state] is the opportunity state machine, not an order: IDLE, WATCH, FORMING, CONFIRMATION,
 * READY, ACTIVE, INVALIDATED, EXPIRED, UNAVAILABLE. Only READY (and ACTIVE, for an already-open
 * position) can carry an action, and only through [BtcAresRecord.matches].
 */
data class BtcAresEngineReport(
    /** Stable id: ENGINE_A_TREND, ENGINE_B_SWEEP, ENGINE_C_COMPRESSION, ENGINE_D_RANGE,
     * ENGINE_E_EXHAUSTION, ENGINE_F_DERIVATIVES. */
    val engine: String,
    val labelFa: String,
    val state: String,
    val direction: SignalAction = SignalAction.NO_TRADE,
    /** Deterministic 0..100 quality score. Not a win probability. */
    val score: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val rewardToRisk: Double? = null,
    val satisfied: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val detail: String = "",
    /** false when the inputs this engine needs do not exist in this app (never faked). */
    val dataAvailable: Boolean = true,
    val ageBars: Int = 0,
    val maxAgeBars: Int = 0,
)

/** Aggregated BTC-ARES decision plus every engine's own report. Paper-only. */
data class BtcAresRecord(
    val symbol: String = "BTC/USDT",
    val strategyName: String = "BTC-ARES",
    val regime: String,
    val regimeLabelFa: String,
    val volatilityState: String,
    val atrRatio: Double = 0.0,
    val atrPercentile: Double = 0.0,
    /** M5 ATR of the evaluated bar; the entry policy needs it and must not recompute from M1. */
    val atr: Double? = null,
    val higherTimeframeAvailable: Boolean = false,
    val engines: List<BtcAresEngineReport> = emptyList(),
    val chosenEngine: String? = null,
    val action: SignalAction = SignalAction.NO_TRADE,
    val score: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val rewardToRisk: Double? = null,
    val positionQty: Double = 0.0,
    val riskUsd: Double = 0.0,
    val conflicts: List<String> = emptyList(),
    val noTradeReasons: List<String> = emptyList(),
    val dataNotes: List<String> = emptyList(),
    val setupKey: String = "",
    val barTime: Long = 0L,
) {
    val isActionable: Boolean
        get() = action == SignalAction.BUY || action == SignalAction.SELL

    /**
     * Authoritative evidence check used by the journal, the automatic trader and the UI.
     * It re-derives every number from the chosen engine's own report, so a hand-built Signal
     * cannot borrow an action from one engine and a stop from another.
     */
    fun matches(signal: Signal): Boolean {
        if (strategyName != "BTC-ARES" || symbol != "BTC/USDT") return false
        if (!isActionable || signal.action != action) return false
        if (signal.interval != Interval.M5 || barTime <= 0L || signal.barTime != barTime) return false
        val engine = engines.firstOrNull { it.engine == chosenEngine } ?: return false
        if (engine.state != "READY" && engine.state != "ACTIVE") return false
        if (engine.direction != action || engine.score != score) return false
        val rr = rewardToRisk ?: return false
        val e = entry ?: return false
        val s = stopLoss ?: return false
        val t = takeProfit ?: return false
        if (!(e > 0.0 && s > 0.0 && t > 0.0 && rr.isFinite() && rr >= 1.5)) return false
        // 60 is the floor of the engine's own configurable threshold range; the exact bar the user
        // chose is enforced inside the engine, not re-invented here.
        if (score !in 60..100) return false
        if (abs((engine.entry ?: return false) - e) > 1e-8) return false
        if (abs((engine.stopLoss ?: return false) - s) > 1e-8) return false
        if (abs((engine.takeProfit ?: return false) - t) > 1e-8) return false
        if (abs((engine.rewardToRisk ?: return false) - rr) > 1e-8) return false
        if (abs((signal.entry ?: return false) - e) > 1e-8) return false
        if (abs((signal.stopLoss ?: return false) - s) > 1e-8) return false
        if (abs((signal.takeProfit ?: return false) - t) > 1e-8) return false
        if (abs((signal.riskReward ?: return false) - rr) > 1e-8) return false
        if (action == SignalAction.BUY && !(s < e && e < t)) return false
        if (action == SignalAction.SELL && !(t < e && e < s)) return false
        if (positionQty <= 0.0 || !positionQty.isFinite()) return false
        if (riskUsd <= 0.0 || !riskUsd.isFinite()) return false
        if (setupKey.isBlank()) return false
        return true
    }
}
