package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
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

    /** One settings-to-engine adapter for chart, scanner and paper-entry paths.
     * Category mode and threshold can never diverge between callers.
     */
    fun evaluateLive(candles: List<Candle>, interval: Interval, settings: AppSettings,
                     symbol: String, timeframes: Map<Interval, List<Candle>>,
                     goldBars: List<Candle> = emptyList()): Signal =
        when (EngineRegistry.engineFor(symbol)) {
            SymbolEngine.ALS5_GOLD -> Als5GoldEngine.evaluate(
                input = candles,
                interval = interval,
                symbol = symbol,
                threshold = settings.minConfidence,
                mode = settings.strategyFor(symbol),
                external = timeframes,
                config = Als5GoldEngine.Config(
                    accountEquityUsd = settings.accountBalance,
                    // ALS-5 starts at 0.25%; a lower user setting remains allowed, never a higher one.
                    riskPerTradePct = settings.riskPercent.coerceIn(0.01, 0.25),
                ),
            )
            SymbolEngine.BTC_ARES -> BtcAresEngine.evaluate(
                input = candles,
                interval = interval,
                symbol = symbol,
                threshold = settings.minConfidence,
                mode = settings.strategyFor(symbol),
                external = timeframes,
                config = BtcAresEngine.Config(
                    accountEquityUsd = settings.accountBalance,
                    riskPerTradePct = settings.riskPercent.coerceIn(0.01, 0.25),
                ),
            )
            SymbolEngine.ALS5S_SILVER -> XagAresEngine.evaluate(
                input = candles,
                interval = interval,
                symbol = symbol,
                threshold = settings.minConfidence,
                mode = settings.strategyFor(symbol),
                external = timeframes,
                goldBars = goldBars,
                config = XagAresEngine.Config(
                    accountEquityUsd = settings.accountBalance,
                    riskPerTradePct = settings.riskPercent.coerceIn(0.01, 0.25),
                ),
            )
            SymbolEngine.EUR_ARES -> EurAresEngine.evaluate(
                input = candles,
                interval = interval,
                symbol = symbol,
                threshold = settings.minConfidence,
                mode = settings.strategyFor(symbol),
                external = timeframes,
                config = EurAresEngine.Config(
                    accountEquityUsd = settings.accountBalance,
                    riskPerTradePct = settings.riskPercent.coerceIn(0.01, 0.25),
                ),
            )
            SymbolEngine.V1_FOUR_LAYER, SymbolEngine.CRYPTO_COMPRESSION ->
                evaluate(candles, interval, settings.minConfidence, symbol,
                    settings.strategyFor(symbol), timeframes)
        }

    /** Only V1 inputs are accepted. Legacy profile/strategy/spread settings do not masquerade
     * as scoring filters; execution costs are checked separately by PaperOrderRules.
     */
    fun evaluate(candles: List<Candle>, interval: Interval, threshold: Double = 85.0,
                 symbol: String = "XAU/USD", mode: com.aurum.edge.core.CategoryStrategy =
                     com.aurum.edge.core.CategoryStrategy.HYBRID,
                 timeframes: Map<Interval, List<Candle>> = emptyMap(),
                 goldBars: List<Candle> = emptyList()): Signal =
        when (EngineRegistry.engineFor(symbol)) {
            // One owner per symbol, decided in EngineRegistry — never by a second `if` here.
            SymbolEngine.ALS5_GOLD ->
                Als5GoldEngine.evaluate(candles, interval, symbol, threshold, mode, timeframes)
            SymbolEngine.BTC_ARES ->
                BtcAresEngine.evaluate(candles, interval, symbol, threshold, mode, timeframes)
            SymbolEngine.ALS5S_SILVER ->
                XagAresEngine.evaluate(candles, interval, symbol, threshold, mode, timeframes,
                    goldBars = goldBars)
            SymbolEngine.EUR_ARES ->
                EurAresEngine.evaluate(candles, interval, symbol, threshold, mode, timeframes)
            SymbolEngine.CRYPTO_COMPRESSION ->
                CryptoCompressionEngine.evaluate(candles, interval, symbol, timeframes)
            SymbolEngine.V1_FOUR_LAYER ->
                V1Scoring.evaluate(candles, interval, symbol, threshold, mode, timeframes)
        }
}
