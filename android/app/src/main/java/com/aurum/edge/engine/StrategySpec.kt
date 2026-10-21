package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.V1Universe

/**
 * [StrategySpec] is a declarative description of one trading strategy: its symbol, interval,
 * risk parameters, the engine list and the arbitration rules. It contains no logic — only data.
 *
 * Why this exists: [Als5GoldEngine], [BtcAresEngine], [EurAresEngine] and [XagAresEngine] each
 * re-implement the same skeleton (feature extraction → regime → engine candidates → scoring →
 * arbitration → report) with slightly different field names. A spec lets a new strategy be added
 * as a data file instead of a 1,000-line engine object.
 *
 * This file is ADDITIVE: existing engines are not modified. The [StrategyEvaluator] below is a
 * framework that can run any [StrategySpec]; existing engines keep working unchanged until they
 * are migrated one by one (see phase-4.3 migration plan in the design critique).
 */
data class StrategySpec(
    /** Unique strategy id, e.g. "ALS5_GOLD", "BTC_ARES", "EUR_ARES", "ALS5S_SILVER". */
    val id: String,
    /** Human-readable label used in UI and journal. */
    val labelFa: String,
    /** Symbol this strategy owns (exactly one, enforced by [EngineRegistry]). */
    val symbol: String,
    /** Base timeframe for entry decisions. */
    val interval: Interval,
    /** Risk / execution parameters shared by all engines of this strategy. */
    val risk: RiskParams,
    /** Engines that participate in arbitration, in priority order. */
    val engines: List<EngineSpec>,
    /** How to choose among ready engine candidates. */
    val arbitrator: ArbitratorSpec,
    /** External (multi-timeframe) candles required by this strategy. May be empty. */
    val requiredTimeframes: List<Interval> = emptyList(),
    /** Minimum bars of the base interval required before evaluation. */
    val minBars: Int = 50,
    /** If true, stale bars block entry. */
    val enforceFreshness: Boolean = true,
    /** Maximum age of a setup in bars before it expires. */
    val maxSetupAgeBars: Int = 12,
) {
    /** Returns true if this strategy accepts [symbol]. */
    fun accepts(symbol: String): Boolean = V1Universe.normalize(symbol) == V1Universe.normalize(this.symbol)
}

data class RiskParams(
    /** Fraction of equity risked per trade (0.01 .. 0.25). */
    val riskPerTradePct: Double = 0.25,
    /** Account equity in USD, used for position sizing. */
    val accountEquityUsd: Double = 10_000.0,
    /** Minimum acceptable gross reward-to-risk. */
    val minRewardRisk: Double = 1.5,
    /** Daily realized loss fraction beyond which new entries are blocked (0.1 .. 1.0). */
    val maxDailyLossPct: Double = 1.0,
)

data class EngineSpec(
    /** Engine id, e.g. "ENGINE_A_TREND". */
    val id: String,
    /** Human-readable label. */
    val labelFa: String,
    /** Whether this engine is enabled. */
    val enabled: Boolean = true,
    /** Relative weight when scores are close (higher = preferred). */
    val weight: Double = 1.0,
)

data class ArbitratorSpec(
    /** Two ready candidates with opposite directions within this many score points = conflict. */
    val conflictMargin: Int = 15,
    /** Minimum score for a candidate to be considered actionable. */
    val minScore: Int = 60,
    /** If true, net (post-cost) reward-to-risk must also pass [RiskParams.minRewardRisk]. */
    val enforceNetRewardRisk: Boolean = true,
)

/**
 * One engine's verdict before arbitration. This is the unified type that all engine-specific
 * candidate types (CandidateSetup, BtcAresEngine.Candidate, EurAresEngine.Candidate,
 * XagAresEngine.Candidate) can be mapped to.
 */
data class EngineVerdict(
    val engineId: String,
    val engineLabelFa: String,
    /** IDLE / WATCH / FORMING / CONFIRMATION / READY / ACTIVE / INACTIVE / EXPIRED / UNAVAILABLE */
    val state: String,
    val direction: SignalAction,
    /** 0..100 quality score. */
    val score: Int,
    /** Structural reference price (e.g. swing high/low, range boundary). */
    val structuralLevel: Double?,
    /** Invalidation price (stop reference). */
    val invalidation: Double?,
    val entry: Double?,
    val stop: Double?,
    val target: Double?,
    val rewardToRisk: Double?,
    /** Post-cost reward-to-risk, if the strategy prices costs. */
    val netRewardRisk: Double?,
    val satisfied: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val detail: String = "",
    /** False if required inputs (HTF, session data, etc.) are missing. */
    val dataAvailable: Boolean = true,
)

/**
 * Result of [StrategyEvaluator.evaluate]. The UI layer renders this; it does not re-derive
 * anything from raw candles.
 */
data class StrategyResult(
    val specId: String,
    val symbol: String,
    val barTime: Long,
    val action: SignalAction,
    val entry: Double?,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val rewardToRisk: Double?,
    val netRewardRisk: Double?,
    val confidence: Double,
    val reasons: List<String>,
    val blockers: List<String>,
    val engineVerdicts: List<EngineVerdict>,
    val conflicts: List<String> = emptyList(),
    /** The chosen engine id, or null if no trade. */
    val chosenEngine: String?,
    /** True if the result is actionable (entry/stop/target populated). */
    val isActionable: Boolean
        get() = action != SignalAction.NO_TRADE && entry != null && stopLoss != null && takeProfit != null,
)

/**
 * Framework evaluator: given a [StrategySpec], closed candles and external timeframes, it
 * produces a [StrategyResult]. The per-engine logic (feature extraction, regime classification,
 * candidate generation) is injected as [engineEvaluators] — one per engine id.
 *
 * Existing engines are NOT modified by this class. They can be wrapped as engineEvaluators and
 * migrated one at a time.
 */
class StrategyEvaluator(
    /** Maps engine id → function that produces an [EngineVerdict] from candles + external data. */
    private val engineEvaluators: Map<String, (List<Candle>, Map<Interval, List<Candle>>) -> EngineVerdict>,
) {

    fun evaluate(
        spec: StrategySpec,
        candles: List<Candle>,
        external: Map<Interval, List<Candle>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): StrategyResult {
        val closed = candles
            .filter { it.closed && it.time + spec.interval.millis <= now }
            .sortedBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)

        val barTime = closed.lastOrNull()?.time ?: 0L

        fun blocked(reason: String): StrategyResult = StrategyResult(
            specId = spec.id,
            symbol = spec.symbol,
            barTime = barTime,
            action = SignalAction.NO_TRADE,
            entry = null,
            stopLoss = null,
            takeProfit = null,
            rewardToRisk = null,
            netRewardRisk = null,
            confidence = 0.0,
            reasons = listOf("${spec.labelFa} · بدون دادهٔ کافی"),
            blockers = listOf(reason),
            engineVerdicts = emptyList(),
            chosenEngine = null,
        )

        // Basic gates — identical across all strategies.
        if (!spec.accepts(spec.symbol)) return blocked("نماد نامعتبر برای این استراتژی")
        if (closed.size < spec.minBars) return blocked("کندل‌های کافی نیستند (${closed.size}/${spec.minBars})")
        if (closed.zipWithNext().any { (a, b) -> b.time <= a.time }) return blocked("ترتیب زمانی کندل‌ها نامعتبر است")

        // Run each enabled engine.
        val verdicts = spec.engines
            .filter { it.enabled }
            .mapNotNull { engineSpec ->
                val evaluator = engineEvaluators[engineSpec.id] ?: return@mapNotNull null
                evaluator(closed, external)
            }

        // Arbitration: pick best ready candidate, check direction conflicts.
        val ready = verdicts
            .filter { it.state == "READY" && it.direction != SignalAction.NO_TRADE }
            .sortedByDescending { it.score }

        val conflicts = mutableListOf<String>()
        if (ready.size >= 2) {
            val top = ready[0]
            val opposite = ready.firstOrNull { it.direction != top.direction }
            if (opposite != null && Math.abs(top.score - opposite.score) < spec.arbitrator.conflictMargin) {
                conflicts += "تعارض ${top.engineLabelFa} و ${opposite.engineLabelFa} " +
                    "(اختلاف امتیاز ${Math.abs(top.score - opposite.score)} < ${spec.arbitrator.conflictMargin})"
            }
        }

        val best = when {
            conflicts.isNotEmpty() -> null
            ready.isEmpty() -> null
            else -> ready.first()
        }

        if (best == null && conflicts.isEmpty()) {
            return StrategyResult(
                specId = spec.id,
                symbol = spec.symbol,
                barTime = barTime,
                action = SignalAction.NO_TRADE,
                entry = null,
                stopLoss = null,
                takeProfit = null,
                rewardToRisk = null,
                netRewardRisk = null,
                confidence = 0.0,
                reasons = listOf("${spec.labelFa} · هر موتوری به وضعیت READY نرسید"),
                blockers = verdicts
                    .filter { it.state != "UNAVAILABLE" && it.state != "IDLE" }
                    .map { "${it.engineLabelFa}=${it.state}" },
                engineVerdicts = verdicts,
                chosenEngine = null,
            )
        }

        // Score gate.
        if (best != null && best.score < spec.arbitrator.minScore) {
            return StrategyResult(
                specId = spec.id,
                symbol = spec.symbol,
                barTime = barTime,
                action = SignalAction.NO_TRADE,
                entry = null,
                stopLoss = null,
                takeProfit = null,
                rewardToRisk = null,
                netRewardRisk = null,
                confidence = 0.0,
                reasons = listOf("${spec.labelFa} · امتیاز ${best.score} کمتر از حد نصاب ${spec.arbitrator.minScore}"),
                blockers = listOf("امتیاز ستاپ کمتر از حد نصاب"),
                engineVerdicts = verdicts,
                chosenEngine = null,
            )
        }

        val netRr = best?.netRewardRisk
        if (spec.arbitrator.enforceNetRewardRisk && netRr != null && netRr < spec.risk.minRewardRisk) {
            return StrategyResult(
                specId = spec.id,
                symbol = spec.symbol,
                barTime = barTime,
                action = SignalAction.NO_TRADE,
                entry = null,
                stopLoss = null,
                takeProfit = null,
                rewardToRisk = best?.rewardToRisk,
                netRewardRisk = netRr,
                confidence = 0.0,
                reasons = listOf("${spec.labelFa} · R:R خالص کمتر از حد نصاب"),
                blockers = listOf("R:R خالص زیر حد نصاب"),
                engineVerdicts = verdicts,
                chosenEngine = null,
            )
        }

        val actionable = best
        val finalAction = if (actionable != null) actionable.direction else SignalAction.NO_TRADE

        val avgConfidence = if (verdicts.isEmpty()) 0.0 else verdicts.sumOf { it.score }.toDouble() / verdicts.size

        StrategyResult(
            specId = spec.id,
            symbol = spec.symbol,
            barTime = barTime,
            action = finalAction,
            entry = actionable?.entry,
            stopLoss = actionable?.stop,
            takeProfit = actionable?.target,
            rewardToRisk = actionable?.rewardToRisk,
            netRewardRisk = actionable?.netRewardRisk,
            confidence = avgConfidence,
            reasons = if (finalAction == SignalAction.NO_TRADE)
                listOf("${spec.labelFa} · بدون ورود")
            else listOf("${spec.labelFa} · ${actionable!!.engineLabelFa} · امتیاز ${actionable.score}"),
            blockers = if (finalAction == SignalAction.NO_TRADE) conflicts else emptyList(),
            engineVerdicts = verdicts,
            conflicts = conflicts,
            chosenEngine = actionable?.engineId,
        )
    }
}
