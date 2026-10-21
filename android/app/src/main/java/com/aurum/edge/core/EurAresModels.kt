package com.aurum.edge.core

import kotlin.math.abs

/**
 * EUR-ARES — Adaptive Euro-Dollar Regime, Execution & Signal Intelligence System (EUR/USD, M5).
 *
 * Why EUR/USD gets its own record instead of a reskinned gold/silver one:
 *  - **Everything is measured in pips.** A euro-dollar stop is 6–14 pips, the spread is ~0.1 pip
 *    and the commission is 0.7 pip round trip, so a "percentage of price" model rounds every cost
 *    to zero and silently ignores the only number that decides whether a 2R setup is tradeable.
 *  - **Sessions are DST-aware.** London and New York move by an hour twice a year; a fixed UTC hour
 *    mislabels the overlap for months at a time, and the overlap is where this pair actually moves.
 *    [EurAresSessionInfo] therefore carries the local clocks, the session, its own volatility
 *    ratio and the rollover window.
 *  - **The rolling cost is explicit.** Swap is *not* invented: when it is unknown it stays 0.0 and
 *    the record says so, instead of pretending a carry estimate exists.
 *
 * The 0–100 score is a deterministic quality score built from eight weighted components. It is not
 * a probability, and it can never override a veto (spread, rollover, session, RR, cost, daily loss).
 */
data class EurAresEngineReport(
    /** ENGINE_A_TREND | ENGINE_B_SWEEP | ENGINE_C_RANGE | ENGINE_D_SESSION | ENGINE_E_NEWS | ENGINE_X_MACRO. */
    val engine: String,
    val labelFa: String,
    val state: String,
    val direction: SignalAction = SignalAction.NO_TRADE,
    /** Deterministic 0..100 quality score; never a probability of success. */
    val scoreOutOf100: Int = 0,
    val regimeScore: Int = 0,
    val structureScore: Int = 0,
    val entryScore: Int = 0,
    val confirmationScore: Int = 0,
    val volatilityScore: Int = 0,
    val sessionScore: Int = 0,
    val rrScore: Int = 0,
    val costScore: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val stopPips: Double? = null,
    val rewardToRisk: Double? = null,
    /** R:R after the round trip; the number the entry gate actually tests. */
    val netRewardRisk: Double? = null,
    /** Spread + commission + slippage for the round trip, in pips. */
    val costPips: Double = 0.0,
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

/** Local-clock session facts. DST is handled by the zone, not by a fixed UTC offset. */
data class EurAresSessionInfo(
    /** ASIA | LONDON | OVERLAP | NEW_YORK | ROLLOVER | OFF. */
    val session: String = "OFF",
    val sessionLabelFa: String = "",
    /** Local London / New York clocks at decision time, for the UI and for reproducibility. */
    val londonClock: String = "",
    val newYorkClock: String = "",
    /** true inside the 17:00 New York rollover window where spreads widen and swaps are booked. */
    val rolloverWindow: Boolean = false,
    /** Today's ATR ÷ the median ATR of the same session over the lookback. 1.0 = normal for it. */
    val sessionVolRatio: Double = 1.0,
)

/**
 * Optional macro / cross-market layer. It is `available = false` unless the caller *actually*
 * supplies measured values (API series, computed correlation). Nothing here is ever synthesised:
 * a missing DXY or yield differential must show as unavailable, not as zero or as a guess.
 */
data class EurMacroContext(
    val available: Boolean = false,
    /** Measured rolling correlation of EUR/USD returns with a dollar-strength proxy. */
    val dollarCorrelation: Double? = null,
    /** EUR/USD momentum minus the dollar-index momentum over the same window (in %). */
    val relativeMomentumPct: Double? = null,
    /** 10-year yield spread (euro area − US) in basis points, only when a real series was supplied. */
    val yieldSpreadBps: Double? = null,
    val bias: SignalAction = SignalAction.NO_TRADE,
    val noteFa: String = "سری کلان/کراس‌مارکت در دسترس نیست؛ ماژول خاموش است و عددی ساخته نمی‌شود",
)

/** Aggregated EUR-ARES decision plus every engine's own report. Paper-only. */
data class EurAresRecord(
    val symbol: String = "EUR/USD",
    val strategyName: String = "EUR-ARES",
    val regime: String,
    val regimeLabelFa: String,
    val volatilityState: String,
    val atr: Double? = null,
    val atrPips: Double = 0.0,
    val atrRatio: Double = 1.0,
    val atrPercentile: Double = 0.0,
    val rangeExpansion: Double = 1.0,
    val session: String = "OFF",
    val sessionLabelFa: String = "",
    val sessionInfo: EurAresSessionInfo = EurAresSessionInfo(),
    val htfAvailable: Boolean = false,
    val htfBias: String = "NONE",
    val d1Available: Boolean = false,
    val d1Bias: String = "NONE",
    val macro: EurMacroContext = EurMacroContext(),
    val engines: List<EurAresEngineReport> = emptyList(),
    val chosenEngine: String? = null,
    val action: SignalAction = SignalAction.NO_TRADE,
    val scoreOutOf100: Int = 0,
    val structuralLevel: Double? = null,
    val invalidationLevel: Double? = null,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val stopPips: Double? = null,
    val rewardToRisk: Double? = null,
    val netRewardRisk: Double? = null,
    val costPips: Double = 0.0,
    val costToRiskFraction: Double = 0.0,
    val positionLots: Double = 0.0,
    val positionUnits: Double = 0.0,
    val pipValueUsd: Double = 0.0,
    val riskUsd: Double = 0.0,
    val maxRiskPct: Double = 0.25,
    val conflicts: List<String> = emptyList(),
    val noTradeReasons: List<String> = emptyList(),
    val dataNotes: List<String> = emptyList(),
    val setupKey: String = "",
    val barTime: Long = 0L,
    val expiresInBars: Int = 0,
) {
    val isActionable: Boolean
        get() = action == SignalAction.BUY || action == SignalAction.SELL

    /**
     * Authoritative evidence check used by the journal, the automatic trader and the UI.
     * Every number is re-derived from the chosen engine's own report, so a hand-built Signal cannot
     * borrow an action from one engine and a stop from another, and a gold/silver/BTC record can
     * never authorise a euro-dollar entry (or the reverse).
     */
    fun matches(signal: Signal): Boolean {
        if (strategyName != "EUR-ARES" || symbol != "EUR/USD") return false
        if (!isActionable || signal.action != action) return false
        if (signal.interval != Interval.M5 || barTime <= 0L || signal.barTime != barTime) return false
        val engine = engines.firstOrNull { it.engine == chosenEngine } ?: return false
        if (engine.state != "READY" && engine.state != "ACTIVE") return false
        if (engine.direction != action) return false
        if (engine.scoreOutOf100 != scoreOutOf100) return false
        if (scoreOutOf100 !in 60..100) return false
        val parts = listOf(engine.regimeScore, engine.structureScore, engine.entryScore,
            engine.confirmationScore, engine.volatilityScore, engine.sessionScore,
            engine.rrScore, engine.costScore)
        if (parts.any { it < 0 }) return false
        if (parts.sum() != scoreOutOf100) return false
        val e = entry ?: return false
        val s = stopLoss ?: return false
        val t = takeProfit ?: return false
        val rr = rewardToRisk ?: return false
        val net = netRewardRisk ?: return false
        if (!(e > 0.0 && s > 0.0 && t > 0.0)) return false
        if (!rr.isFinite() || rr < EurAresPolicy.MIN_NET_REWARD_RISK) return false
        if (!net.isFinite() || net < EurAresPolicy.MIN_NET_REWARD_RISK) return false
        if (!costToRiskFraction.isFinite() || costToRiskFraction < 0.0 ||
            costToRiskFraction > EurAresPolicy.MAX_COST_TO_RISK_FRACTION + 1e-9) return false
        val stopInPips = stopPips ?: return false
        if (!stopInPips.isFinite() || stopInPips < EurAresPolicy.MIN_STOP_PIPS - 1e-9) return false
        if (!costPips.isFinite() || costPips < 0.0) return false
        if (abs((engine.entry ?: return false) - e) > 1e-9) return false
        if (abs((engine.stopLoss ?: return false) - s) > 1e-9) return false
        if (abs((engine.takeProfit ?: return false) - t) > 1e-9) return false
        if (abs((engine.rewardToRisk ?: return false) - rr) > 1e-9) return false
        if (abs((engine.netRewardRisk ?: return false) - net) > 1e-9) return false
        if (abs((signal.entry ?: return false) - e) > 1e-9) return false
        if (abs((signal.stopLoss ?: return false) - s) > 1e-9) return false
        if (abs((signal.takeProfit ?: return false) - t) > 1e-9) return false
        if (abs((signal.riskReward ?: return false) - rr) > 1e-9) return false
        if (action == SignalAction.BUY && !(s < e && e < t)) return false
        if (action == SignalAction.SELL && !(t < e && e < s)) return false
        if (positionLots <= 0.0 || !positionLots.isFinite()) return false
        if (positionUnits <= 0.0 || !positionUnits.isFinite()) return false
        if (riskUsd <= 0.0 || !riskUsd.isFinite()) return false
        if (setupKey.isBlank()) return false
        return true
    }
}

/** One pip for a 4-decimal pair. A pipette is a tenth of a pip; the two are never mixed. */
object EurPips {
    const val PIP = 0.0001
    const val PIPETTE = 0.00001

    fun of(distance: Double): Double = distance / PIP
    fun format(pips: Double): String = String.format(java.util.Locale.US, "%.1f", pips)
}
