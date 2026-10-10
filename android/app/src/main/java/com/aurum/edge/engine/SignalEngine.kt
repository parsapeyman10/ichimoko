package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.StrategyKind
import com.aurum.edge.core.HistoryPolicy
import kotlin.math.max

/**
 * Single V1 closed-bar four-layer paper signal facade.
 *
 * Differences from the old web "demo" engine:
 *  - evaluation runs ONLY on real closed bars received from the provider;
 *  - every branch that used to synthesise a direction to keep the screen busy is gone;
 *  - when conditions are not met the engine returns NO_TRADE plus the exact blockers.
 *
 * Production live decisions all enter through [evaluate]. [Series]
 * remains an indicator cache for chart/MTF, not a second entry engine.
 */
object SignalEngine {

    data class IchiSetting(val tenkan: Int, val kijun: Int, val spanB: Int)

    /** Indicator cache for the chart/MTF analyzer, not a separate decision engine. */
    class Series(
        val bars: List<Candle>,
        val interval: Interval,
        val setting: IchiSetting,
        val ichimoku: Ichimoku,
        val ema200: List<Double?>,
        val rsi: List<Double?>,
        val macd: List<Double?>,
        val adx: List<Double?>,
    ) {
        val size: Int get() = bars.size
        val lastIndex: Int get() = bars.size - 1
    }

    /** One setting for the chart, MTF analyzer and current signal engine. */
    fun ichimokuSetting(interval: Interval): IchiSetting = IchiSetting(8, 24, 72)

    /** Bars a signal stays valid before it must be re-confirmed. */
    fun barsValid(interval: Interval): Int = when (interval) {
        Interval.M1 -> 8
        Interval.M5 -> 6
        else -> 4
    }

    fun minBars(interval: Interval): Int {
        val s = ichimokuSetting(interval)
        return max(s.spanB + s.kijun + 20, HistoryPolicy.LIVE_MIN_CANDLES)
    }

    fun series(candles: List<Candle>, interval: Interval): Series {
        val setting = ichimokuSetting(interval)
        val bars = candles.filter { it.closed }
        val closes = bars.map { it.close }
        return Series(
            bars = bars,
            interval = interval,
            setting = setting,
            ichimoku = Ichimoku.compute(bars, setting.tenkan, setting.kijun, setting.spanB, setting.kijun),
            ema200 = Indicators.ema(closes, 200),
            rsi = Indicators.rsi(bars, 7),
            macd = Indicators.macdHistogram(bars),
            adx = Indicators.adx(bars, 14),
        )
    }

    const val ENGINE_WINDOW = 400

    /** The only entry decision for live signals. Old profile/strategy
     * parameters remain for persisted settings compatibility; category mode selects V1 behaviour.
     */
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(candles: List<Candle>, interval: Interval, threshold: Double = 85.0,
                 spread: Double? = null, profile: SignalProfile = SignalProfile.BASE,
                 strategy: StrategyKind = StrategyKind.ICHIMOKU_PRICE_ACTION,
                 symbol: String = "XAU/USD", mode: com.aurum.edge.core.CategoryStrategy =
                     com.aurum.edge.core.CategoryStrategy.HYBRID,
                 timeframes: Map<Interval, List<Candle>> = emptyMap()): Signal =
        V1Scoring.evaluate(candles, interval, symbol, threshold, mode, timeframes)
}
