package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction

/**
 * Additive adapters: wrap each existing engine's [evaluate] so it produces an [EngineVerdict]
 * consumable by [StrategyEvaluator].
 *
 * These adapters do NOT modify any existing engine. They are a translation layer only.
 * Existing engines keep working unchanged through [SignalEngine].
 */
object StrategyAdapters {

    /** Wrap [Als5GoldEngine.evaluate] as an [EngineVerdict] producer. */
    fun als5Gold(
        bars: List<Candle>,
        external: Map<Interval, List<Candle>>,
        now: Long = System.currentTimeMillis(),
    ): EngineVerdict {
        val signal = Als5GoldEngine.evaluate(
            input = bars,
            interval = Interval.M5,
            symbol = "XAU/USD",
            threshold = 80.0,
            mode = CategoryStrategy.HYBRID,
            external = external,
            now = now,
        )
        val record = signal.als5Opportunity
        return EngineVerdict(
            engineId = "ENGINE_A_TREND",
            engineLabelFa = "موتور A · ادامهٔ روند (ALS-5 طلا)",
            state = record?.state ?: signal.action.name,
            direction = signal.action,
            score = record?.scoreOutOf10 ?: 0,
            structuralLevel = record?.structuralLevel,
            invalidation = record?.invalidationLevel,
            entry = record?.candidateEntry,
            stop = record?.estimatedStopLoss,
            target = record?.estimatedTakeProfit,
            rr = record?.rewardToRisk,
            netRewardRisk = record?.rewardToRisk,
            satisfied = record?.satisfiedConditions ?: emptyList(),
            missing = record?.missingConditions ?: if (signal.action == SignalAction.NO_TRADE) signal.blockers else emptyList(),
            detail = signal.reasons.joinToString(" · "),
            dataAvailable = signal.action != SignalAction.NO_TRADE || signal.blockers.isEmpty(),
        )
    }

    /** Wrap [BtcAresEngine.evaluate] as an [EngineVerdict] producer. */
    fun btcAres(
        bars: List<Candle>,
        external: Map<Interval, List<Candle>>,
        now: Long = System.currentTimeMillis(),
    ): EngineVerdict {
        val signal = BtcAresEngine.evaluate(
            input = bars,
            interval = Interval.M5,
            symbol = "BTC/USDT",
            threshold = 70.0,
            mode = CategoryStrategy.HYBRID,
            external = external,
            now = now,
        )
        val record = signal.btcAres
        val bestEngine = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }
        return EngineVerdict(
            engineId = record?.chosenEngine ?: "ENGINE_A_TREND",
            engineLabelFa = bestEngine?.labelFa ?: "BTC-ARES",
            state = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }?.state ?: "IDLE",
            direction = signal.action,
            score = record?.score ?: 0,
            structuralLevel = record?.structuralLevel,
            invalidation = record?.invalidationLevel,
            entry = record?.entry,
            stop = record?.stopLoss,
            target = record?.takeProfit,
            rr = record?.rewardToRisk,
            netRewardRisk = record?.netRewardRisk,
            satisfied = bestEngine?.satisfied ?: emptyList(),
            missing = bestEngine?.missing ?: if (signal.action == SignalAction.NO_TRADE) signal.blockers else emptyList(),
            detail = signal.reasons.joinToString(" · "),
            dataAvailable = record?.engines?.isNotEmpty() == true,
        )
    }

    /** Wrap [XagAresEngine.evaluate] as an [EngineVerdict] producer. */
    fun xagAres(
        bars: List<Candle>,
        external: Map<Interval, List<Candle>>,
        goldBars: List<Candle> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): EngineVerdict {
        val signal = XagAresEngine.evaluate(
            input = bars,
            interval = Interval.M5,
            symbol = "XAG/USD",
            threshold = 80.0,
            mode = CategoryStrategy.HYBRID,
            external = external,
            goldBars = goldBars,
            now = now,
        )
        val record = signal.als5s
        val bestEngine = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }
        return EngineVerdict(
            engineId = record?.chosenEngine ?: "ENGINE_A_TREND",
            engineLabelFa = bestEngine?.labelFa ?: "ALS-5S نقره",
            state = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }?.state ?: "IDLE",
            direction = signal.action,
            score = record?.scoreOutOf10 ?: 0,
            structuralLevel = record?.structuralLevel,
            invalidation = record?.invalidationLevel,
            entry = record?.entry,
            stop = record?.stopLoss,
            target = record?.takeProfit,
            rr = record?.rewardToRisk,
            netRewardRisk = record?.netRewardRisk,
            satisfied = bestEngine?.satisfied ?: emptyList(),
            missing = bestEngine?.missing ?: if (signal.action == SignalAction.NO_TRADE) signal.blockers else emptyList(),
            detail = signal.reasons.joinToString(" · "),
            dataAvailable = record?.engines?.isNotEmpty() == true,
        )
    }

    /** Wrap [EurAresEngine.evaluate] as an [EngineVerdict] producer. */
    fun eurAres(
        bars: List<Candle>,
        external: Map<Interval, List<Candle>>,
        now: Long = System.currentTimeMillis(),
    ): EngineVerdict {
        val signal = EurAresEngine.evaluate(
            input = bars,
            interval = Interval.M5,
            symbol = "EUR/USD",
            threshold = 70.0,
            mode = CategoryStrategy.HYBRID,
            external = external,
            now = now,
        )
        val record = signal.eurAres
        val bestEngine = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }
        return EngineVerdict(
            engineId = record?.chosenEngine ?: "ENGINE_A_TREND",
            engineLabelFa = bestEngine?.labelFa ?: "EUR-ARES",
            state = record?.engines?.firstOrNull { it.engine == record?.chosenEngine }?.state ?: "IDLE",
            direction = signal.action,
            score = record?.scoreOutOf100 ?: 0,
            structuralLevel = record?.structuralLevel,
            invalidation = record?.invalidationLevel,
            entry = record?.entry,
            stop = record?.stopLoss,
            target = record?.takeProfit,
            rr = record?.rewardToRisk,
            netRewardRisk = record?.netRewardRisk,
            satisfied = bestEngine?.satisfied ?: emptyList(),
            missing = bestEngine?.missing ?: if (signal.action == SignalAction.NO_TRADE) signal.blockers else emptyList(),
            detail = signal.reasons.joinToString(" · "),
            dataAvailable = record?.engines?.isNotEmpty() == true,
        )
    }

    /** Wrap [V1Scoring.evaluate] as an [EngineVerdict] producer. */
        fun v1FourLayer(
        bars: List<Candle>,
        external: Map<Interval, List<Candle>>,
    ): EngineVerdict {
        val signal = V1Scoring.evaluate(
            input = bars,
            interval = Interval.M5,
            symbol = "XAU/USD",
            threshold = 85.0,
            mode = CategoryStrategy.HYBRID,
            external = external,
        )
        return EngineVerdict(
            engineId = "V1_FOUR_LAYER",
            engineLabelFa = "چهارلایهٔ V1",
            state = signal.action.name,
            direction = signal.action,
            score = signal.confidence.toInt().coerceIn(0, 100),
            structuralLevel = null,
            invalidation = null,
            entry = signal.entry,
            stop = signal.stopLoss,
            target = signal.takeProfit,
            rr = signal.riskReward,
            netRewardRisk = signal.riskReward,
            satisfied = emptyList(),
            missing = signal.blockers,
            detail = signal.reasons.joinToString(" · "),
            dataAvailable = signal.action != SignalAction.NO_TRADE,
        )
    }
}