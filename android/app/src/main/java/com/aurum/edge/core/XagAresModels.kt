package com.aurum.edge.core

import kotlin.math.abs

/**
 * ALS-5S — per-engine report and aggregate record for XAG/USD (silver).
 *
 * Silver is not gold with a different ticker. This record therefore carries numbers that gold's
 * record does not: the per-ounce execution cost, the cost-to-risk fraction, the liquidity session,
 * and the (optional) silver/gold context. Costs are first-class because silver's spread is wide
 * relative to its ATR, so a setup that "looks" like 2R gross can be a losing trade after the
 * round trip — exactly the failure a gold model transplanted to silver would hide.
 *
 * Engines are reported separately: A trend continuation, B liquidity sweep, C in its *two* modes
 * (range reversion and volatility breakout are different behaviours with different invalidation,
 * so they are never merged into one score), and X, the cross-market context, which is informational
 * and can never be the reason for an entry.
 */
data class XagAresEngineReport(
    /** ENGINE_A_TREND | ENGINE_B_SWEEP | ENGINE_C_RANGE | ENGINE_C_BREAKOUT | ENGINE_X_CROSS. */
    val engine: String,
    val labelFa: String,
    val state: String,
    val direction: SignalAction = SignalAction.NO_TRADE,
    /** Deterministic 0..10 quality score; never a probability of success. */
    val scoreOutOf10: Int = 0,
    val regimeScore: Int = 0,
    val structureScore: Int = 0,
    val confirmationScore: Int = 0,
    val rrScore: Int = 0,
    val executionScore: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val rewardToRisk: Double? = null,
    /** R:R after the modelled round trip; the number the entry gate actually tests. */
    val netRewardRisk: Double? = null,
    /** Commission + spread + slippage for one ounce, round trip, in USD. */
    val costPerOzUsd: Double = 0.0,
    /** Cost ÷ stop distance. Above the configured cap the setup is refused, not shrunk. */
    val costToRiskFraction: Double = 0.0,
    val setupType: String = "",
    val satisfied: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val detail: String = "",
    /** false when the inputs this engine needs do not exist in this app (never faked). */
    val dataAvailable: Boolean = true,
    val ageBars: Int = 0,
    val maxAgeBars: Int = 0,
)

/** Silver/gold context. Present only when XAU/USD bars were actually supplied for the same window. */
data class XagGoldContext(
    val available: Boolean,
    val correlation: Double? = null,
    val relativeMomentum: Double? = null,
    val ratio: Double? = null,
    val ratioChangePct: Double? = null,
    val bias: SignalAction = SignalAction.NO_TRADE,
    val noteFa: String = "",
) {
    companion object {
        fun unavailable(reason: String = "کندل XAU/USD برای همان بازه در دسترس نیست؛ ماژول طلا خاموش است") =
            XagGoldContext(available = false, noteFa = reason)
    }
}

/** Aggregated ALS-5S decision plus every engine's own report. Paper-only. */
data class XagAresRecord(
    val symbol: String = "XAG/USD",
    val strategyName: String = "ALS-5S",
    val regime: String,
    val regimeLabelFa: String,
    val volatilityState: String,
    val atr: Double? = null,
    val atrRatio: Double = 0.0,
    val atrPercentile: Double = 0.0,
    /** Recent 3-bar mean range ÷ 20-bar mean range; silver accelerates, so this is a first-class input. */
    val rangeExpansion: Double = 1.0,
    val session: String = "OFF",
    val sessionLabelFa: String = "",
    val goldContext: XagGoldContext = XagGoldContext.unavailable(),
    val higherTimeframeAvailable: Boolean = false,
    val engines: List<XagAresEngineReport> = emptyList(),
    val chosenEngine: String? = null,
    val action: SignalAction = SignalAction.NO_TRADE,
    val scoreOutOf10: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val rewardToRisk: Double? = null,
    val netRewardRisk: Double? = null,
    val costPerOzUsd: Double = 0.0,
    val costToRiskFraction: Double = 0.0,
    val positionOz: Double = 0.0,
    val riskUsd: Double = 0.0,
    val maxRiskPct: Double = 0.25,
    val conflicts: List<String> = emptyList(),
    val noTradeReasons: List<String> = emptyList(),
    val dataNotes: List<String> = emptyList(),
    val setupKey: String = "",
    val barTime: Long = 0L,
    /** Setup lifetime in bars; the journal/UI can show how stale a candidate is. */
    val expiresInBars: Int = 0,
) {
    val isActionable: Boolean
        get() = action == SignalAction.BUY || action == SignalAction.SELL

    /**
     * Authoritative evidence check used by the journal, the automatic trader and the UI.
     * Every number is re-derived from the chosen engine's own report, so a hand-built Signal cannot
     * borrow an action from one engine and a stop from another, and a gold/forex record can never
     * authorise a silver entry.
     */
    fun matches(signal: Signal): Boolean {
        if (strategyName != "ALS-5S" || symbol != "XAG/USD") return false
        if (!isActionable || signal.action != action) return false
        if (signal.interval != Interval.M5 || barTime <= 0L || signal.barTime != barTime) return false
        val engine = engines.firstOrNull { it.engine == chosenEngine } ?: return false
        if (engine.state != "READY" && engine.state != "ACTIVE") return false
        if (engine.direction != action || engine.scoreOutOf10 != scoreOutOf10) return false
        if (scoreOutOf10 !in 8..10) return false
        val e = entry ?: return false
        val s = stopLoss ?: return false
        val t = takeProfit ?: return false
        val rr = rewardToRisk ?: return false
        val net = netRewardRisk ?: return false
        if (!(e > 0.0 && s > 0.0 && t > 0.0)) return false
        if (!rr.isFinite() || rr < 1.5) return false
        if (!net.isFinite() || net < 1.5) return false
        if (!costToRiskFraction.isFinite() || costToRiskFraction < 0.0) return false
        val subscores = listOf(engine.regimeScore, engine.structureScore, engine.confirmationScore,
            engine.rrScore, engine.executionScore)
        if (subscores.any { it !in 0..2 }) return false
        if (subscores.sum() != scoreOutOf10) return false
        if (abs((engine.entry ?: return false) - e) > 1e-8) return false
        if (abs((engine.stopLoss ?: return false) - s) > 1e-8) return false
        if (abs((engine.takeProfit ?: return false) - t) > 1e-8) return false
        if (abs((engine.rewardToRisk ?: return false) - rr) > 1e-8) return false
        if (abs((engine.netRewardRisk ?: return false) - net) > 1e-8) return false
        if (abs((signal.entry ?: return false) - e) > 1e-8) return false
        if (abs((signal.stopLoss ?: return false) - s) > 1e-8) return false
        if (abs((signal.takeProfit ?: return false) - t) > 1e-8) return false
        if (abs((signal.riskReward ?: return false) - rr) > 1e-8) return false
        if (action == SignalAction.BUY && !(s < e && e < t)) return false
        if (action == SignalAction.SELL && !(t < e && e < s)) return false
        if (positionOz <= 0.0 || !positionOz.isFinite()) return false
        if (riskUsd <= 0.0 || !riskUsd.isFinite()) return false
        if (setupKey.isBlank()) return false
        return true
    }
}
