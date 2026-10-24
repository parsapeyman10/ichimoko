package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.domain.Interval
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

    /**
     * ONE dispatch block for every engine. Both public entry points (`evaluate` and
     * `evaluateLive`) route through here, so adding or removing an engine changes exactly
     * one `when` arm instead of two parallel copies.
     *
     * When [als5Config]/[btcConfig]/[xagConfig]/[eurConfig] is null the engine's default
     * `Config` is used (the `evaluate` path). When non-null the caller-supplied config is
     * used (the `evaluateLive` path, which injects user risk settings).
     */
    private fun dispatch(
        engine: SymbolEngine,
        candles: List<Candle>,
        interval: Interval,
        symbol: String,
        threshold: Double,
        mode: com.aurum.edge.core.CategoryStrategy,
        timeframes: Map<Interval, List<Candle>>,
        goldBars: List<Candle>,
        als5Config: Als5GoldEngine.Config? = null,
        btcConfig: BtcAresEngine.Config? = null,
        xagConfig: XagAresEngine.Config? = null,
        eurConfig: EurAresEngine.Config? = null,
    ): Signal = when (engine) {
        SymbolEngine.ALS5_GOLD -> Als5GoldEngine.evaluate(
            input = candles,
            interval = interval,
            symbol = symbol,
            threshold = threshold,
            mode = mode,
            external = timeframes,
            config = als5Config ?: Als5GoldEngine.Config(),
        )
        SymbolEngine.BTC_ARES -> BtcAresEngine.evaluate(
            input = candles,
            interval = interval,
            symbol = symbol,
            threshold = threshold,
            mode = mode,
            external = timeframes,
            config = btcConfig ?: BtcAresEngine.Config(),
        )
        SymbolEngine.ALS5S_SILVER -> XagAresEngine.evaluate(
            input = candles,
            interval = interval,
            symbol = symbol,
            threshold = threshold,
            mode = mode,
            external = timeframes,
            goldBars = goldBars,
            config = xagConfig ?: XagAresEngine.Config(),
        )
        SymbolEngine.EUR_ARES -> EurAresEngine.evaluate(
            input = candles,
            interval = interval,
            symbol = symbol,
            threshold = threshold,
            mode = mode,
            external = timeframes,
            config = eurConfig ?: EurAresEngine.Config(),
        )
        SymbolEngine.CRYPTO_COMPRESSION ->
            CryptoCompressionEngine.evaluate(candles, interval, symbol, timeframes)
        SymbolEngine.V1_FOUR_LAYER ->
            V1Scoring.evaluate(candles, interval, symbol, threshold, mode, timeframes)
    }

    /** One settings-to-engine adapter for chart, scanner and paper-entry paths.
     * Category mode and threshold can never diverge between callers. Injects user risk
     * settings into each engine's [Config].
     */
    fun evaluateLive(candles: List<Candle>, interval: Interval, settings: AppSettings,
                     symbol: String, timeframes: Map<Interval, List<Candle>>,
                     goldBars: List<Candle> = emptyList()): Signal {
        val risk = settings.riskPercent.coerceIn(0.01, 0.25)
        val equity = settings.accountBalance
        return dispatch(
            engine = EngineRegistry.engineFor(symbol),
            candles = candles,
            interval = interval,
            symbol = symbol,
            threshold = settings.minConfidence,
            mode = settings.strategyFor(symbol),
            timeframes = timeframes,
            goldBars = goldBars,
            als5Config = Als5GoldEngine.Config(
                accountEquityUsd = equity,
                // ALS-5 starts at 0.25%; a lower user setting remains allowed, never a higher one.
                riskPerTradePct = risk,
            ),
            btcConfig = BtcAresEngine.Config(
                accountEquityUsd = equity,
                riskPerTradePct = risk,
            ),
            xagConfig = XagAresEngine.Config(
                accountEquityUsd = equity,
                riskPerTradePct = risk,
            ),
            eurConfig = EurAresEngine.Config(
                accountEquityUsd = equity,
                riskPerTradePct = risk,
            ),
        )
    }

    /** Only V1 inputs are accepted. Legacy profile/strategy/spread settings do not masquerade
     * as scoring filters; execution costs are checked separately by PaperOrderRules.
     * Uses each engine's default [Config] — test-friendly and callable without [AppSettings].
     */
    fun evaluate(candles: List<Candle>, interval: Interval, threshold: Double = 85.0,
                 symbol: String = "XAU/USD", mode: com.aurum.edge.core.CategoryStrategy =
                     com.aurum.edge.core.CategoryStrategy.HYBRID,
                 timeframes: Map<Interval, List<Candle>> = emptyMap(),
                 goldBars: List<Candle> = emptyList()): Signal =
        dispatch(
            engine = EngineRegistry.engineFor(symbol),
            candles = candles,
            interval = interval,
            symbol = symbol,
            threshold = threshold,
            mode = mode,
            timeframes = timeframes,
            goldBars = goldBars,
        )
}
