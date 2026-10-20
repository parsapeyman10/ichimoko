package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.V1Universe
import com.aurum.edge.core.VenueSpecs
import com.aurum.edge.core.XagAresEngineReport
import com.aurum.edge.core.XagAresPolicy
import com.aurum.edge.core.XagAresRecord
import com.aurum.edge.core.XagGoldContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ALS-5S — Adaptive Liquidity, Structure & Silver Volatility System (XAG/USD, M5).
 *
 * Why this is not a gold model with the ticker replaced:
 *  - **Costs are a first-class input.** Silver's spread is large relative to its M5 ATR, so every
 *    candidate carries a per-ounce round-trip cost, the cost ÷ stop-distance fraction, and a *net*
 *    R:R. A setup whose 2R gross collapses to 0.4R net is refused with that number on screen.
 *  - **Volatility acceleration is measured, not assumed.** `rangeExpansion` (3-bar mean range ÷
 *    20-bar mean range) and the ATR percentile separate contraction from expansion; a breakout with
 *    no expansion is not treated as a breakout.
 *  - **The regime machine has hysteresis.** A label must survive `regimeConfirmBars` before it can
 *    switch, so a single noisy candle cannot flip the active engine set; protective states
 *    (HIGH_VOL) are entered immediately and left only with confirmation.
 *  - **Engine C is two engines.** Range reversion and volatility breakout have different
 *    invalidation, different stops and opposite bias, so they are reported and scored separately and
 *    a simultaneous conflict is a conflict, never an average.
 *  - **Silver/gold is a module, not an assumption.** `XagGoldContext` is computed from aligned
 *    XAU/USD bars; when they are missing the module is `UNAVAILABLE`, silver keeps working alone,
 *    and no "gold rose so buy silver" rule exists anywhere.
 *
 * Honesty rules kept from the rest of the app: closed bars only, confirmed pivots only (a pivot
 * exists once its right bars have closed), every refusal carries a reason, missing data is reported
 * instead of estimated, and the 0..10 score is a deterministic quality score — not a probability.
 */
object XagAresEngine {
    const val SYMBOL = XagAresPolicy.SYMBOL
    const val STRATEGY = XagAresPolicy.STRATEGY

    enum class Regime(val labelFa: String) {
        BULL_TREND("روند صعودی نقره"),
        BEAR_TREND("روند نزولی نقره"),
        RANGE("رنج / بازگشت به میانگین"),
        COMPRESSION("فشردگی نوسان"),
        HIGH_VOL("نوسان بالا / نامشخص"),
    }

    enum class Volatility(val labelFa: String) {
        LOW("کم"),
        NORMAL("عادی"),
        ELEVATED("بالا"),
        EXTREME("بحرانی"),
    }

    enum class State { IDLE, WATCH, FORMING, CONFIRMATION, READY, ACTIVE, INVALIDATED, EXPIRED, UNAVAILABLE }

    enum class Session(val labelFa: String) {
        ASIA("آسیا (نقدینگی کم)"),
        LONDON("لندن"),
        OVERLAP("هم‌پوشانی لندن/نیویورک"),
        NEW_YORK("نیویورک"),
        OFF("خارج از سشن اصلی"),
    }

    enum class EngineId(val id: String, val labelFa: String, val shortFa: String) {
        A("ENGINE_A_TREND", "موتور A · ادامهٔ روند", "A · روند"),
        B("ENGINE_B_SWEEP", "موتور B · سوییپ نقدینگی / شکست ناموفق", "B · سوییپ"),
        CR("ENGINE_C_RANGE", "موتور C · بازگشت به میانگین رنج", "C · رنج"),
        CB("ENGINE_C_BREAKOUT", "موتور C · شکست نوسان", "C · شکست"),
        X("ENGINE_X_CROSS", "ماژول X · زمینهٔ طلا/نقره (فقط اطلاع)", "X · طلا/نقره"),
    }

    data class Config(
        // Structure
        val pivotLeft: Int = 3,
        val pivotRight: Int = 3,
        val microLeft: Int = 2,
        val microRight: Int = 1,
        // Indicators
        val atrPeriod: Int = 14,
        val adxPeriod: Int = 14,
        val emaFast: Int = 21,
        val emaSlow: Int = 55,
        val htfEma: Int = 200,
        val rsiPeriod: Int = 14,
        // Volatility / regime
        val volMedianLookback: Int = 60,
        val atrPercentileLookback: Int = 240,
        val rangeExpansionFast: Int = 3,
        val rangeExpansionBaseline: Int = 20,
        val lowAtrRatio: Double = 0.65,
        val elevatedAtrRatio: Double = 1.45,
        val extremeAtrRatio: Double = 2.30,
        val trendAdxMin: Double = 22.0,
        val rangeAdxMax: Double = 20.0,
        val compressionMaxRangeAtr: Double = 0.85,
        val minRangeWidthAtr: Double = 1.4,
        val maxRangeWidthAtr: Double = 6.5,
        val regimeConfirmBars: Int = 2,
        // Engine A
        val minImpulseAtr: Double = 1.1,
        val minPullbackFrac: Double = 0.25,
        val maxPullbackFrac: Double = 0.90,
        val pullbackZoneAtr: Double = 0.65,
        val maxChaseAtr: Double = 1.15,
        val minBodyFrac: Double = 0.50,
        // Engine B
        val minSweepAtr: Double = 0.15,
        val maxSweepAtr: Double = 1.40,
        val sweepWindowBars: Int = 4,
        val requireRetest: Boolean = false,
        val retestToleranceAtr: Double = 0.25,
        // Engine C — shared
        val compressionWindowBars: Int = 24,
        val minBreakoutAtr: Double = 0.18,
        val minBreakoutRelVolume: Double = 1.20,
        val minExpansionForBreakout: Double = 1.15,
        val maxBreakoutChaseAtr: Double = 1.15,
        val followThroughFrac: Double = 0.55,
        val rangeTouchFrac: Double = 0.22,
        val minRejectionWickFrac: Double = 0.40,
        val rangeInvalidationAtr: Double = 0.25,
        val maxMeasuredMoveRr: Double = 3.0,
        // Risk / execution
        val stopBufferAtr: Double = 0.28,
        val minStopAtr: Double = 0.80,
        val maxStopAtr: Double = 5.00,
        val targetRr: Double = 2.0,
        val minRewardRisk: Double = 1.50,
        val maxCostToRiskFraction: Double = XagAresPolicy.MAX_COST_TO_RISK_FRACTION,
        val slippageBps: Double = 2.0,
        val qtyStepOz: Double = 0.01,
        val minNotionalUsd: Double = 5.0,
        val maxNotionalToEquity: Double = 5.0,
        val scoreThreshold10: Int = 8,
        val conflictMargin10: Int = 2,
        val riskPerTradePct: Double = 0.25,
        val maxDailyLossPct: Double = 1.00,
        val dailyRealizedLossPct: Double = 0.0,
        val accountEquityUsd: Double = 10_000.0,
        val maxSetupAgeBars: Int = 12,
        // Sessions are a feature by default, not a gate.
        val sessionGateEnabled: Boolean = false,
        val blockedSessions: Set<Session> = setOf(Session.OFF),
        val penaltiesLowLiquidity: Boolean = true,
        // Gold context module
        val goldContextEnabled: Boolean = true,
        val goldCorrelationWindow: Int = 60,
        val goldMomentumWindow: Int = 12,
        val minGoldCorrelation: Double = 0.35,
        /** Off by default: the silver-only baseline must be measurable on its own. */
        val requireGoldConfirmation: Boolean = false,
        val minBars: Int = HistoryPolicy.LIVE_MIN_CANDLES,
        val enforceFreshness: Boolean = true,
        val enableA: Boolean = true,
        val enableB: Boolean = true,
        val enableCRange: Boolean = true,
        val enableCBreakout: Boolean = true,
    )

    internal data class Features(
        val atr: Double,
        val atrRatio: Double,
        val atrPercentile: Double,
        val rangeExpansion: Double,
        val adx: Double,
        val adxM15: Double?,
        val emaFast: Double,
        val emaSlow: Double,
        val rsi: Double,
        val macdHist: Double,
        val relVolume: Double,
        val swingHigh: Double?,
        val swingLow: Double?,
        val hh: Boolean,
        val hl: Boolean,
        val lh: Boolean,
        val ll: Boolean,
        val bosUp: Boolean,
        val bosDown: Boolean,
        val rangeHigh: Double,
        val rangeLow: Double,
        val compressionRatio: Double,
        val htfBias: SignalAction?,
        val m15Available: Boolean,
        val h1Available: Boolean,
        val session: Session,
        val gold: XagGoldContext,
    )

    /** One engine's verdict before arbitration. */
    private data class Candidate(
        val engine: EngineId,
        val state: State,
        val direction: SignalAction,
        val regimeScore: Int,
        val structureScore: Int,
        val confirmationScore: Int,
        val rrScore: Int,
        val executionScore: Int,
        val structuralLevel: Double?,
        val invalidation: Double?,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val rr: Double?,
        val netRr: Double?,
        val costPerOz: Double,
        val costToRisk: Double,
        val setupType: String,
        val satisfied: List<String>,
        val missing: List<String>,
        val detail: String,
        val dataAvailable: Boolean = true,
    ) {
        val score: Int get() = (regimeScore + structureScore + confirmationScore + rrScore + executionScore)
            .coerceIn(0, 10)
    }

    // ---------------------------------------------------------------------------------------
    // Pivots — identical contract to the rest of the app: a pivot exists only after its right
    // bars have closed, so nothing here can see the future.
    // ---------------------------------------------------------------------------------------
    fun confirmedSwings(
        bars: List<Candle>, left: Int, right: Int, upToIndex: Int = bars.lastIndex,
    ): List<Pair<Int, Double>> {
        val last = min(upToIndex, bars.lastIndex)
        if (last - right < left) return emptyList()
        val out = mutableListOf<Pair<Int, Double>>()
        for (i in left..(last - right)) {
            val h = bars[i].high
            var ok = true
            for (j in (i - left)..(i + right)) {
                if (j == i) continue
                if (bars[j].high >= h) { ok = false; break }
            }
            if (ok) out += i to h
        }
        return out
    }

    fun confirmedLows(
        bars: List<Candle>, left: Int, right: Int, upToIndex: Int = bars.lastIndex,
    ): List<Pair<Int, Double>> {
        val last = min(upToIndex, bars.lastIndex)
        if (last - right < left) return emptyList()
        val out = mutableListOf<Pair<Int, Double>>()
        for (i in left..(last - right)) {
            val l = bars[i].low
            var ok = true
            for (j in (i - left)..(i + right)) {
                if (j == i) continue
                if (bars[j].low <= l) { ok = false; break }
            }
            if (ok) out += i to l
        }
        return out
    }

    /** Percentile rank of the latest ATR inside its own lookback; ties count as half. */
    private fun atrPercentile(series: List<Double?>, lookback: Int): Double {
        val window = series.takeLast(lookback).filterNotNull().filter { it.isFinite() && it > 0.0 }
        if (window.isEmpty()) return 0.0
        val last = window.last()
        val below = window.count { it < last }
        val equal = window.count { it == last }
        return (below + 0.5 * equal) / window.size
    }

    fun sessionOf(timeMs: Long): Session {
        val hour = ((timeMs / 3_600_000L) % 24L + 24L) % 24L
        return when (hour) {
            in 7L..11L -> Session.LONDON
            in 12L..15L -> Session.OVERLAP
            in 16L..20L -> Session.NEW_YORK
            in 0L..6L -> Session.ASIA
            else -> Session.OFF
        }
    }

    // ---------------------------------------------------------------------------------------
    // Silver/gold context — aligned on bar time, never forward-filled from another window.
    // ---------------------------------------------------------------------------------------
    internal fun goldContext(
        bars: List<Candle>, goldBars: List<Candle>, cfg: Config = Config(),
    ): XagGoldContext {
        if (!cfg.goldContextEnabled) return XagGoldContext.unavailable("ماژول طلا در تنظیمات خاموش است")
        val needed = max(cfg.goldCorrelationWindow, cfg.goldMomentumWindow) + 1
        if (goldBars.isEmpty()) return XagGoldContext.unavailable()
        val goldByTime = goldBars.associateBy { it.time }
        val pairs = bars.takeLast(cfg.goldCorrelationWindow + 1)
            .mapNotNull { bar -> goldByTime[bar.time]?.let { bar to it } }
        if (pairs.size < needed) {
            return XagGoldContext.unavailable(
                "کندل‌های XAU/USD هم‌زمان کافی نیست (${pairs.size} از $needed)؛ ماژول طلا خاموش است",
            )
        }
        fun returns(values: List<Double>): List<Double> =
            values.zipWithNext { a, b -> if (a > 0.0) (b - a) / a else 0.0 }
        val silverRet = returns(pairs.map { it.first.close })
        val goldRet = returns(pairs.map { it.second.close })
        val n = min(silverRet.size, goldRet.size)
        if (n < 5) return XagGoldContext.unavailable("بازدهٔ هم‌زمان کافی برای همبستگی نیست")
        val x = silverRet.takeLast(n)
        val y = goldRet.takeLast(n)
        val mx = x.average()
        val my = y.average()
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / n
        val sx = sqrt(x.indices.sumOf { (x[it] - mx) * (x[it] - mx) } / n)
        val sy = sqrt(y.indices.sumOf { (y[it] - my) * (y[it] - my) } / n)
        val corr = if (sx > 0.0 && sy > 0.0) cov / (sx * sy) else 0.0
        val window = cfg.goldMomentumWindow
        val silverMom = silverRet.takeLast(window).sum()
        val goldMom = goldRet.takeLast(window).sum()
        val relative = silverMom - goldMom
        val ratio = pairs.last().first.close / pairs.last().second.close
        val ratioThen = pairs[max(0, pairs.size - 1 - window)].let { it.first.close / it.second.close }
        val ratioChange = if (ratioThen > 0.0) (ratio - ratioThen) / ratioThen * 100.0 else 0.0
        val bias = when {
            !corr.isFinite() || abs(corr) < cfg.minGoldCorrelation -> SignalAction.NO_TRADE
            relative > 0.0 -> SignalAction.BUY
            relative < 0.0 -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        val note = when {
            bias == SignalAction.NO_TRADE ->
                "همبستگی ${"%.2f".format(java.util.Locale.US, corr)} ضعیف است؛ طلا جهت نمی‌دهد"
            else -> "همبستگی ${"%.2f".format(java.util.Locale.US, corr)} · " +
                "مومنتوم نسبی ${"%.3f".format(java.util.Locale.US, relative * 100.0)}٪"
        }
        return XagGoldContext(true, corr, relative, ratio, ratioChange, bias, note)
    }

    // ---------------------------------------------------------------------------------------
    // Features
    // ---------------------------------------------------------------------------------------
    private fun features(
        bars: List<Candle>, m15: List<Candle>, h1: List<Candle>,
        goldBars: List<Candle>, cfg: Config,
    ): Features {
        val closes = bars.map { it.close }
        val atrSeries = Indicators.atr(bars, cfg.atrPeriod)
        val atr = atrSeries.lastOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val recent = atrSeries.takeLast(cfg.volMedianLookback).filterNotNull()
            .filter { it > 0.0 && it.isFinite() }.sorted()
        val median = recent.getOrNull(recent.size / 2)?.takeIf { it > 0.0 } ?: atr
        val ranges = bars.map { it.high - it.low }
        val fastRange = ranges.takeLast(cfg.rangeExpansionFast).average()
        val baseRange = ranges.takeLast(cfg.rangeExpansionBaseline).average()
        val expansion = if (baseRange > 0.0) fastRange / baseRange else 1.0
        val adx = Indicators.adx(bars, cfg.adxPeriod).lastOrNull() ?: 0.0
        val adxM15 = m15.takeIf { it.size > cfg.adxPeriod * 2 }
            ?.let { Indicators.adx(it, cfg.adxPeriod).lastOrNull() }
        val emaFast = Indicators.ema(closes, cfg.emaFast).lastOrNull() ?: bars.last().close
        val emaSlow = Indicators.ema(closes, cfg.emaSlow).lastOrNull() ?: bars.last().close
        val rsi = Indicators.rsi(bars, cfg.rsiPeriod).lastOrNull() ?: 50.0
        val macd = Indicators.macdHistogram(bars).lastOrNull() ?: 0.0
        val relVolume = Indicators.relativeVolume(bars, 30).lastOrNull() ?: 1.0

        val highs = confirmedSwings(bars, cfg.pivotLeft, cfg.pivotRight)
        val lows = confirmedLows(bars, cfg.pivotLeft, cfg.pivotRight)
        val lastHighs = highs.takeLast(3).map { it.second }
        val lastLows = lows.takeLast(3).map { it.second }
        val hh = lastHighs.size >= 2 && lastHighs.last() > lastHighs[lastHighs.lastIndex - 1]
        val lh = lastHighs.size >= 2 && lastHighs.last() < lastHighs[lastHighs.lastIndex - 1]
        val hl = lastLows.size >= 2 && lastLows.last() > lastLows[lastLows.lastIndex - 1]
        val ll = lastLows.size >= 2 && lastLows.last() < lastLows[lastLows.lastIndex - 1]
        val close = bars.last().close
        val bosUp = lastHighs.isNotEmpty() && close > lastHighs.last()
        val bosDown = lastLows.isNotEmpty() && close < lastLows.last()

        val window = bars.takeLast(cfg.compressionWindowBars + 1).dropLast(1)
        val rangeHigh = window.maxOfOrNull { it.high } ?: bars.last().high
        val rangeLow = window.minOfOrNull { it.low } ?: bars.last().low
        val width = rangeHigh - rangeLow
        val compressionRatio =
            if (atr > 0.0) width / (atr * sqrt(cfg.compressionWindowBars.toDouble())) else 1.0

        val m15Available = m15.size >= HistoryPolicy.minSize(Interval.M15)
        val h1Available = h1.size >= HistoryPolicy.minSize(Interval.H1)
        val htfBias = if (!m15Available) null else {
            val m15Close = m15.last().close
            val m15Ema = Indicators.ema(m15.map { it.close }, cfg.emaFast).lastOrNull() ?: m15Close
            val h1Close = h1.lastOrNull()?.close
            val h1Ema = if (h1Available)
                Indicators.ema(h1.map { it.close }, cfg.htfEma).lastOrNull() ?: h1Close else null
            when {
                h1Close != null && h1Ema != null && m15Close > m15Ema && h1Close > h1Ema -> SignalAction.BUY
                h1Close != null && h1Ema != null && m15Close < m15Ema && h1Close < h1Ema -> SignalAction.SELL
                m15Close > m15Ema -> SignalAction.BUY
                m15Close < m15Ema -> SignalAction.SELL
                else -> SignalAction.NO_TRADE
            }
        }

        return Features(
            atr = atr,
            atrRatio = atr / median,
            atrPercentile = atrPercentile(atrSeries, cfg.atrPercentileLookback),
            rangeExpansion = expansion,
            adx = adx,
            adxM15 = adxM15,
            emaFast = emaFast,
            emaSlow = emaSlow,
            rsi = rsi,
            macdHist = macd,
            relVolume = relVolume,
            swingHigh = lastHighs.lastOrNull(),
            swingLow = lastLows.lastOrNull(),
            hh = hh, hl = hl, lh = lh, ll = ll,
            bosUp = bosUp,
            bosDown = bosDown,
            rangeHigh = rangeHigh,
            rangeLow = rangeLow,
            compressionRatio = compressionRatio,
            htfBias = htfBias,
            m15Available = m15Available,
            h1Available = h1Available,
            session = sessionOf(bars.last().time),
            gold = goldContext(bars, goldBars, cfg),
        )
    }

    /** Volatility state from its own distribution; one fixed ATR number is never used. */
    internal fun volatilityOf(f: Features, cfg: Config = Config()): Volatility = when {
        f.atrRatio >= cfg.extremeAtrRatio || f.atrPercentile >= 0.97 -> Volatility.EXTREME
        f.atrRatio >= cfg.elevatedAtrRatio || f.atrPercentile >= 0.85 -> Volatility.ELEVATED
        f.atrRatio <= cfg.lowAtrRatio && f.atrPercentile <= 0.25 -> Volatility.LOW
        else -> Volatility.NORMAL
    }

    /** One-bar classification; hysteresis is applied in [classifyRegime]. */
    internal fun rawRegime(f: Features, cfg: Config = Config()): Regime {
        val vol = volatilityOf(f, cfg)
        val bullish = (f.hh && f.hl) || f.bosUp
        val bearish = (f.lh && f.ll) || f.bosDown
        if (vol == Volatility.EXTREME) return Regime.HIGH_VOL
        if (bullish && bearish) return Regime.HIGH_VOL
        if (f.compressionRatio <= cfg.compressionMaxRangeAtr && f.adx < cfg.trendAdxMin)
            return Regime.COMPRESSION
        val widthAtr = (f.rangeHigh - f.rangeLow) / f.atr
        if (bullish && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.SELL)
            return Regime.BULL_TREND
        if (bearish && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.BUY)
            return Regime.BEAR_TREND
        if (widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr && f.adx <= cfg.rangeAdxMax)
            return Regime.RANGE
        return Regime.HIGH_VOL
    }

    /**
     * Hysteresis: a new label must survive a confirmation bar, so one noisy candle cannot swap the
     * active engine set. Protective HIGH_VOL is entered immediately and left only with confirmation.
     */
    internal fun classifyRegime(
        now: Regime, previous: Regime?, cfg: Config = Config(),
    ): Pair<Regime, String> {
        if (previous == null || now == previous) return now to ""
        if (now == Regime.HIGH_VOL) return now to "ورود فوری به حالت محافظتی نوسان بالا"
        if (previous == Regime.HIGH_VOL)
            return now to "خروج از حالت محافظتی با تأیید کندل بعدی"
        if (cfg.regimeConfirmBars <= 1) return now to ""
        return previous to "هیسترزیس: ${previous.labelFa} تا تأیید ${cfg.regimeConfirmBars} کندل حفظ شد"
    }

    private fun stopFor(direction: SignalAction, raw: Double, price: Double, atr: Double, cfg: Config): Double {
        val distance = max(abs(price - raw) + cfg.stopBufferAtr * atr, cfg.minStopAtr * atr)
            .coerceAtMost(cfg.maxStopAtr * atr)
        return if (direction == SignalAction.BUY) price - distance else price + distance
    }

    private fun targetAt(direction: SignalAction, entry: Double, stop: Double, rr: Double): Double {
        val risk = abs(entry - stop)
        return if (direction == SignalAction.BUY) entry + rr * risk else entry - rr * risk
    }

    private fun noTrade(
        id: EngineId,
        state: State = State.IDLE,
        detail: String,
        missing: List<String> = emptyList(),
        satisfied: List<String> = emptyList(),
        costPerOz: Double = 0.0,
        dataAvailable: Boolean = true,
    ): Candidate = Candidate(
        engine = id, state = state, direction = SignalAction.NO_TRADE,
        regimeScore = 0, structureScore = 0, confirmationScore = 0, rrScore = 0, executionScore = 0,
        structuralLevel = null, invalidation = null, entry = null, stop = null, target = null,
        rr = null, netRr = null, costPerOz = costPerOz, costToRisk = 0.0,
        setupType = "", satisfied = satisfied, missing = missing, detail = detail,
        dataAvailable = dataAvailable,
    )

    private fun sessionPoints(session: Session, cfg: Config): Int = when (session) {
        Session.OVERLAP -> 2
        Session.LONDON, Session.NEW_YORK -> 2
        Session.ASIA -> if (cfg.penaltiesLowLiquidity) 1 else 2
        Session.OFF -> if (cfg.penaltiesLowLiquidity) 0 else 1
    }

    /** 0..2 for the execution block: net R:R and cost ÷ risk are what actually decide. */
    private fun executionPoints(netRr: Double, costToRisk: Double, session: Session, cfg: Config): Int {
        val rrPoint = when {
            !netRr.isFinite() -> 0
            netRr >= cfg.targetRr -> 1
            netRr >= cfg.minRewardRisk -> 1
            else -> 0
        }
        val costPoint = when {
            costToRisk <= 0.15 -> 2
            costToRisk <= cfg.maxCostToRiskFraction -> 1
            else -> 0
        }
        val sessionPoint = sessionPoints(session, cfg)
        // Five sub-signals folded into two points: rr (0..1) + the best of cost/session (0..1).
        return min(2, rrPoint + (if (costPoint == 2 && sessionPoint == 2) 2
        else if (costPoint >= 1 && sessionPoint >= 1) 1 else 0))
    }

    private fun rrPoints(netRr: Double, cfg: Config): Int = when {
        !netRr.isFinite() -> 0
        netRr >= 2.0 -> 2
        netRr >= cfg.minRewardRisk -> 1
        else -> 0
    }

    // ---------------------------------------------------------------------------------------
    // Engine A — trend continuation with a measured pullback.
    // ---------------------------------------------------------------------------------------
    private fun engineA(bars: List<Candle>, f: Features, regime: Regime, cfg: Config, cost: Double): Candidate {
        val id = EngineId.A
        if (!cfg.enableA) return noTrade(id, State.IDLE, "موتور A خاموش است", costPerOz = cost)
        if (regime != Regime.BULL_TREND && regime != Regime.BEAR_TREND)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} ادامهٔ روند را مجاز نمی‌کند", costPerOz = cost)
        if (!f.m15Available)
            return noTrade(id, State.IDLE, "قاب M15 برای تأیید ساختار کافی نیست", costPerOz = cost)
        val up = regime == Regime.BULL_TREND
        val direction = if (up) SignalAction.BUY else SignalAction.SELL
        val price = bars.last().close
        val atr = f.atr
        val impulseLevel = (if (up) f.swingLow else f.swingHigh)
            ?: return noTrade(id, State.IDLE, "سوئینگ تأییدشده برای اندازه‌گیری ایمپالس نیست", costPerOz = cost)
        val otherSwing = if (up) f.swingHigh else f.swingLow
        val impulse = abs(price - impulseLevel)
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (impulse >= cfg.minImpulseAtr * atr) satisfied += "ایمپالس ${"%.2f".format(java.util.Locale.US, impulse / atr)} ATR"
        else missing += "ایمپالس ${"%.2f".format(java.util.Locale.US, impulse / atr)} ATR کمتر از ${cfg.minImpulseAtr} ATR است"
        val zone = if (up) max(impulseLevel, f.emaFast) else min(impulseLevel, f.emaFast)
        val distance = abs(price - zone)
        val inZone = distance <= cfg.pullbackZoneAtr * atr
        if (inZone) satisfied += "قیمت در ناحیهٔ پولبک (${"%.2f".format(java.util.Locale.US, distance / atr)} ATR)"
        else missing += "قیمت ${"%.2f".format(java.util.Locale.US, distance / atr)} ATR از ناحیهٔ پولبک دور است"
        val depth = if (otherSwing != null && abs(otherSwing - impulseLevel) > 0.0)
            abs(otherSwing - price) / abs(otherSwing - impulseLevel) else Double.NaN
        val depthOk = depth.isFinite() && depth in cfg.minPullbackFrac..cfg.maxPullbackFrac
        if (depthOk) satisfied += "عمق پولبک ${"%.0f".format(java.util.Locale.US, depth * 100)}٪"
        else missing += "عمق پولبک خارج از بازهٔ ${(cfg.minPullbackFrac * 100).toInt()}–${(cfg.maxPullbackFrac * 100).toInt()}٪"
        val last = bars.last()
        val span = last.high - last.low
        val bodyFrac = if (span > 0.0) abs(last.close - last.open) / span else 0.0
        val bodyOk = bodyFrac >= cfg.minBodyFrac &&
            ((up && last.close > last.open) || (!up && last.close < last.open))
        val wickFrac = if (span > 0.0 && up) (min(last.open, last.close) - last.low) / span
        else if (span > 0.0) (last.high - max(last.open, last.close)) / span else 0.0
        val wickOk = wickFrac >= 0.30
        val rejection = bodyOk || wickOk
        if (bodyOk) satisfied += "کندل جهت‌دار ${"%.0f".format(java.util.Locale.US, bodyFrac * 100)}٪"
        if (wickOk) satisfied += "ویک رد ${"%.0f".format(java.util.Locale.US, wickFrac * 100)}٪"
        if (!rejection) missing += "کندل تأیید جهت‌دار دیده نشد"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight).takeLast(1)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight).takeLast(1)
        val microBreak = if (up) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (microBreak) satisfied += "شکست ریزساختار هم‌جهت"
        else missing += "شکست ریزساختار تأیید نشده"
        val distEma = abs(price - f.emaFast) / atr
        val notChasing = distEma <= cfg.maxChaseAtr * 2.0
        if (notChasing) satisfied += "ورود دیرهنگام نیست (${"%.2f".format(java.util.Locale.US, distEma)} ATR از EMA${cfg.emaFast})"
        else missing += "قیمت ${"%.2f".format(java.util.Locale.US, distEma)} ATR از EMA${cfg.emaFast} دور است"
        val momentumOk = if (up) f.macdHist > 0.0 && f.rsi >= 45.0 else f.macdHist < 0.0 && f.rsi <= 55.0
        if (momentumOk) satisfied += "مومنتوم هم‌جهت" else missing += "مومنتوم هم‌جهت نیست"

        val ready = impulse >= cfg.minImpulseAtr * atr && inZone && depthOk && rejection && notChasing &&
            (microBreak || bodyOk)
        val state = when {
            ready -> State.READY
            satisfied.size >= 4 -> State.CONFIRMATION
            inZone || microBreak -> State.FORMING
            else -> State.WATCH
        }
        val level = if (up) min(impulseLevel, zone) else max(impulseLevel, zone)
        val rawStop = if (up) level - cfg.stopBufferAtr * atr * 0.5 else level + cfg.stopBufferAtr * atr * 0.5
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val target = targetAt(direction, price, stop, max(cfg.minRewardRisk, cfg.targetRr))
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val net = XagAresPolicy.netRewardRisk(price, stop, target, cost)
        val costToRisk = if (risk > 0.0) cost / risk else Double.MAX_VALUE
        val regimeScore = if (cfg.requireGoldConfirmation && f.gold.available &&
            f.gold.bias != SignalAction.NO_TRADE && f.gold.bias != direction) 1 else 2
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = regimeScore,
            structureScore = if (impulse >= cfg.minImpulseAtr * atr && depthOk) 2 else if (inZone) 1 else 0,
            confirmationScore = if (microBreak && rejection) 2 else if (rejection || microBreak) 1 else 0,
            rrScore = rrPoints(net, cfg),
            executionScore = executionPoints(net, costToRisk, f.session, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop, target = target,
            rr = rr, netRr = net, costPerOz = cost, costToRisk = costToRisk,
            setupType = "TREND_CONTINUATION",
            satisfied = satisfied, missing = missing,
            detail = "${regime.labelFa} · پولبک ${"%.2f".format(java.util.Locale.US, distance / atr)} ATR · " +
                "RR خالص ${"%.2f".format(java.util.Locale.US, net)}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Engine B — liquidity sweep / failed breakout with a reclaim and a micro shift.
    // ---------------------------------------------------------------------------------------
    private fun engineB(bars: List<Candle>, f: Features, regime: Regime, cfg: Config, cost: Double): Candidate {
        val id = EngineId.B
        if (!cfg.enableB) return noTrade(id, State.IDLE, "موتور B خاموش است", costPerOz = cost)
        if (regime == Regime.HIGH_VOL)
            return noTrade(id, State.IDLE, "در نوسان بالا ورود سوییپ مجاز نیست", costPerOz = cost)
        val atr = f.atr
        val price = bars.last().close
        val window = bars.takeLast(cfg.sweepWindowBars)
        val highs = confirmedSwings(bars, cfg.pivotLeft, cfg.pivotRight)
        val lows = confirmedLows(bars, cfg.pivotLeft, cfg.pivotRight)
        val priorLow = lows.lastOrNull()?.second
        val priorHigh = highs.lastOrNull()?.second
        val sweptLow = priorLow != null && window.any {
            it.low < priorLow && it.low >= priorLow - cfg.maxSweepAtr * atr
        }
        val sweptHigh = priorHigh != null && window.any {
            it.high > priorHigh && it.high <= priorHigh + cfg.maxSweepAtr * atr
        }
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val setup: Pair<SignalAction, Double>? = when {
            sweptLow && priorLow != null && price > priorLow -> SignalAction.BUY to priorLow
            sweptHigh && priorHigh != null && price < priorHigh -> SignalAction.SELL to priorHigh
            else -> null
        }
        if (setup == null) {
            missing += "سوییپ تأییدشدهٔ نقدینگی دیده نشد"
            return noTrade(id, State.WATCH, "بدون سوییپ تأییدشده", missing = missing, costPerOz = cost)
        }
        val direction = setup.first
        val level = setup.second
        satisfied += if (direction == SignalAction.BUY)
            "زیر کف تأییدشده فرو رفت و بالای آن بست"
        else "بالای سقف تأییدشده رفت و زیر آن بست"
        val sweepBar = window.lastOrNull { bar ->
            if (direction == SignalAction.BUY) bar.low < level else bar.high > level
        }
        val penetration = sweepBar?.let {
            if (direction == SignalAction.BUY) (level - it.low) / atr else (it.high - level) / atr
        } ?: 0.0
        if (penetration >= cfg.minSweepAtr) satisfied += "عمق نفوذ ${"%.2f".format(java.util.Locale.US, penetration)} ATR"
        else missing += "نفوذ ${"%.2f".format(java.util.Locale.US, penetration)} ATR کمتر از ${cfg.minSweepAtr} ATR است"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val shift = if (direction == SignalAction.BUY) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (shift) satisfied += "تغییر ساختار ریز تأیید شد" else missing += "تغییر ساختار ریز تأیید نشده"
        val last = bars.last()
        val span = last.high - last.low
        val closeLocation = if (span > 0.0) (last.close - last.low) / span else 0.5
        val closeOk = if (direction == SignalAction.BUY) closeLocation >= 0.55 else closeLocation <= 0.45
        if (closeOk) satisfied += "بست در نیمهٔ درست کندل" else missing += "بست در نیمهٔ درست کندل نیست"
        val retestOk = !cfg.requireRetest || (if (direction == SignalAction.BUY)
            last.low <= level + cfg.retestToleranceAtr * atr && price > level
        else last.high >= level - cfg.retestToleranceAtr * atr && price < level)
        if (cfg.requireRetest) {
            if (retestOk) satisfied += "ریتست سطح بازیابی‌شده"
            else missing += "ریتست سطح بازیابی‌شده دیده نشد"
        }
        val ready = penetration >= cfg.minSweepAtr && shift && closeOk && retestOk
        val state = when {
            ready -> State.READY
            satisfied.size >= 3 -> State.CONFIRMATION
            else -> State.FORMING
        }
        val rawStop = if (direction == SignalAction.BUY) (sweepBar?.low ?: (level - atr * 0.5)) - cfg.stopBufferAtr * atr
        else (sweepBar?.high ?: (level + atr * 0.5)) + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val opposite = if (direction == SignalAction.BUY)
            (f.rangeHigh.takeIf { it > price } ?: (price + 2.0 * risk))
        else (f.rangeLow.takeIf { it < price } ?: (price - 2.0 * risk))
        val target = if (direction == SignalAction.BUY) max(opposite, price + cfg.minRewardRisk * risk)
        else min(opposite, price - cfg.minRewardRisk * risk)
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val net = XagAresPolicy.netRewardRisk(price, stop, target, cost)
        val costToRisk = if (risk > 0.0) cost / risk else Double.MAX_VALUE
        val regimeScore = if (regime == Regime.RANGE || regime == Regime.COMPRESSION) 2 else 1
        val structureScore = if (penetration >= cfg.minSweepAtr && abs(level - f.rangeLow) < atr * 0.5 ||
            penetration >= cfg.minSweepAtr && abs(level - f.rangeHigh) < atr * 0.5) 2
        else if (penetration >= cfg.minSweepAtr) 1 else 0
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = regimeScore, structureScore = structureScore,
            confirmationScore = if (shift && closeOk) 2 else if (shift || closeOk) 1 else 0,
            rrScore = rrPoints(net, cfg),
            executionScore = executionPoints(net, costToRisk, f.session, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop, target = target,
            rr = rr, netRr = net, costPerOz = cost, costToRisk = costToRisk,
            setupType = "LIQUIDITY_SWEEP",
            satisfied = satisfied, missing = missing,
            detail = "سوییپ ${"%.2f".format(java.util.Locale.US, penetration)} ATR · " +
                "RR خالص ${"%.2f".format(java.util.Locale.US, net)}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Engine C-range — mean reversion at a confirmed boundary, invalidated by a real breakout.
    // ---------------------------------------------------------------------------------------
    private fun engineCRange(bars: List<Candle>, f: Features, regime: Regime, cfg: Config, cost: Double): Candidate {
        val id = EngineId.CR
        if (!cfg.enableCRange) return noTrade(id, State.IDLE, "موتور C (رنج) خاموش است", costPerOz = cost)
        if (regime != Regime.RANGE)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} بازگشت به میانگین را مجاز نمی‌کند", costPerOz = cost)
        val atr = f.atr
        val price = bars.last().close
        val width = f.rangeHigh - f.rangeLow
        if (width <= 0.0) return noTrade(id, State.WATCH, "عرض رنج نامعتبر است", costPerOz = cost)
        val brokenUp = price > f.rangeHigh + cfg.rangeInvalidationAtr * atr
        val brokenDown = price < f.rangeLow - cfg.rangeInvalidationAtr * atr
        if (brokenUp || brokenDown)
            return noTrade(id, State.INVALIDATED, "رنج با شکست معتبر باطل شد؛ بازگشت به میانگین ممنوع", costPerOz = cost)
        val position = (price - f.rangeLow) / width
        val nearLow = position <= cfg.rangeTouchFrac
        val nearHigh = position >= 1.0 - cfg.rangeTouchFrac
        if (!nearLow && !nearHigh) {
            return noTrade(id, State.WATCH,
                "قیمت در ${"%.0f".format(java.util.Locale.US, position * 100)}٪ رنج است؛ ورود وسط رنج ممنوع",
                missing = listOf("قیمت در میانهٔ رنج است (${"%.0f".format(java.util.Locale.US, position * 100)}٪)"),
                costPerOz = cost)
        }
        val direction = if (nearLow) SignalAction.BUY else SignalAction.SELL
        val satisfied = mutableListOf("قیمت در ${"%.0f".format(java.util.Locale.US, position * 100)}٪ رنج")
        val missing = mutableListOf<String>()
        val last = bars.last()
        val span = last.high - last.low
        val wick = if (span > 0.0 && direction == SignalAction.BUY)
            (min(last.open, last.close) - last.low) / span
        else if (span > 0.0) (last.high - max(last.open, last.close)) / span else 0.0
        if (wick >= cfg.minRejectionWickFrac) satisfied += "ویک رد ${"%.0f".format(java.util.Locale.US, wick * 100)}٪"
        else missing += "ویک رد ${"%.0f".format(java.util.Locale.US, wick * 100)}٪ کم است"
        val rsiOk = if (direction == SignalAction.BUY) f.rsi <= 40.0 else f.rsi >= 60.0
        if (rsiOk) satisfied += "RSI ${"%.0f".format(java.util.Locale.US, f.rsi)} در ناحیه" else missing += "RSI اشباع نیست"
        val adxOk = f.adx <= cfg.rangeAdxMax + 2.0
        if (adxOk) satisfied += "ADX ${"%.1f".format(java.util.Locale.US, f.adx)} رونددار نیست" else missing += "ADX بالا است"
        val level = if (direction == SignalAction.BUY) f.rangeLow else f.rangeHigh
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val mid = (f.rangeHigh + f.rangeLow) / 2.0
        val structuralTarget = if (direction == SignalAction.BUY) max(mid, price + cfg.minRewardRisk * risk)
        else min(mid, price - cfg.minRewardRisk * risk)
        val rr = if (risk > 0.0) abs(structuralTarget - price) / risk else 0.0
        val net = XagAresPolicy.netRewardRisk(price, stop, structuralTarget, cost)
        val costToRisk = if (risk > 0.0) cost / risk else Double.MAX_VALUE
        val ready = satisfied.size >= 4 && wick >= cfg.minRejectionWickFrac && rsiOk && adxOk &&
            net.isFinite() && net >= cfg.minRewardRisk
        val state = when {
            ready -> State.READY
            satisfied.size >= 2 -> State.CONFIRMATION
            else -> State.FORMING
        }
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = 2,
            structureScore = if (nearLow || nearHigh) 2 else 1,
            confirmationScore = if (wick >= cfg.minRejectionWickFrac && rsiOk) 2 else if (wick >= cfg.minRejectionWickFrac) 1 else 0,
            rrScore = rrPoints(net, cfg),
            executionScore = executionPoints(net, costToRisk, f.session, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop,
            target = structuralTarget, rr = rr, netRr = net,
            costPerOz = cost, costToRisk = costToRisk,
            setupType = "RANGE_REVERSION",
            satisfied = satisfied, missing = missing,
            detail = "موقعیت ${"%.0f".format(java.util.Locale.US, position * 100)}٪ رنج · " +
                "عرض ${"%.2f".format(java.util.Locale.US, width / atr)} ATR · " +
                "RR خالص ${"%.2f".format(java.util.Locale.US, net)}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Engine C-breakout — compression breakout with measured expansion and follow-through.
    // ---------------------------------------------------------------------------------------
    private fun engineCBreakout(bars: List<Candle>, f: Features, regime: Regime, cfg: Config, cost: Double): Candidate {
        val id = EngineId.CB
        if (!cfg.enableCBreakout) return noTrade(id, State.IDLE, "موتور C (شکست) خاموش است", costPerOz = cost)
        if (regime == Regime.HIGH_VOL)
            return noTrade(id, State.IDLE, "در نوسان بالا شکست فشردگی قابل اتکا نیست", costPerOz = cost)
        val atr = f.atr
        val price = bars.last().close
        val compressed = f.compressionRatio <= cfg.compressionMaxRangeAtr || regime == Regime.COMPRESSION
        val upBreak = price > f.rangeHigh + cfg.minBreakoutAtr * atr
        val downBreak = price < f.rangeLow - cfg.minBreakoutAtr * atr
        val direction = when {
            upBreak -> SignalAction.BUY
            downBreak -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (compressed) satisfied += "فشردگی ${"%.2f".format(java.util.Locale.US, f.compressionRatio)}"
        else missing += "نسبت دامنه ${"%.2f".format(java.util.Locale.US, f.compressionRatio)} فشرده نیست"
        if (direction == SignalAction.NO_TRADE) missing += "بست بیرون از مرز رنج تأیید نشد"
        else satisfied += "بست ${cfg.minBreakoutAtr} ATR بیرون مرز"
        val expansionOk = f.rangeExpansion >= cfg.minExpansionForBreakout
        if (expansionOk) satisfied += "گسترش دامنه ${"%.2f".format(java.util.Locale.US, f.rangeExpansion)}×"
        else missing += "گسترش دامنه ${"%.2f".format(java.util.Locale.US, f.rangeExpansion)}× کم است"
        val volumeOk = f.relVolume >= cfg.minBreakoutRelVolume
        if (volumeOk) satisfied += "حجم نسبی ${"%.2f".format(java.util.Locale.US, f.relVolume)}×"
        else missing += "حجم نسبی ${"%.2f".format(java.util.Locale.US, f.relVolume)}× کم است"
        val last = bars.last()
        val span = last.high - last.low
        val closeFrac = if (span > 0.0) (last.close - last.low) / span else 0.5
        val followThrough = if (direction == SignalAction.BUY) closeFrac >= cfg.followThroughFrac
        else closeFrac <= 1.0 - cfg.followThroughFrac
        if (direction != SignalAction.NO_TRADE) {
            if (followThrough) satisfied += "بست در جهت شکست (${"%.0f".format(java.util.Locale.US, closeFrac * 100)}٪ کندل)"
            else missing += "بست در جهت شکست نیست"
        }
        val level = if (direction == SignalAction.BUY) f.rangeHigh else f.rangeLow
        val lateDistance = when (direction) {
            SignalAction.BUY -> (price - f.rangeHigh) / atr
            SignalAction.SELL -> (f.rangeLow - price) / atr
            else -> 0.0
        }
        val notLate = lateDistance <= cfg.minBreakoutAtr + cfg.maxBreakoutChaseAtr
        if (direction != SignalAction.NO_TRADE) {
            if (notLate) satisfied += "ورود دیرهنگام نیست (${"%.2f".format(java.util.Locale.US, lateDistance)} ATR)"
            else missing += "حرکت ${"%.2f".format(java.util.Locale.US, lateDistance)} ATR از مرز گذشته؛ دیر است"
        }
        val ready = direction != SignalAction.NO_TRADE && compressed && expansionOk && volumeOk &&
            followThrough && notLate
        val state = when {
            ready -> State.READY
            direction != SignalAction.NO_TRADE -> State.CONFIRMATION
            compressed -> State.FORMING
            else -> State.WATCH
        }
        val measuredMove = f.rangeHigh - f.rangeLow
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = if (direction == SignalAction.NO_TRADE) null else stopFor(direction, rawStop, price, atr, cfg)
        val risk = stop?.let { abs(price - it) } ?: 0.0
        val fixedTarget = if (stop != null) targetAt(direction, price, stop, max(cfg.minRewardRisk, cfg.targetRr)) else null
        val measuredTarget = if (direction == SignalAction.BUY) price + measuredMove
        else price - measuredMove
        val cappedMeasured: Double? = when {
            stop == null || risk <= 0.0 -> null
            direction == SignalAction.BUY -> min(measuredTarget, price + cfg.maxMeasuredMoveRr * risk)
            else -> max(measuredTarget, price - cfg.maxMeasuredMoveRr * risk)
        }
        val target: Double? = when {
            fixedTarget == null || cappedMeasured == null -> null
            direction == SignalAction.BUY -> max(fixedTarget, cappedMeasured)
            else -> min(fixedTarget, cappedMeasured)
        }
        val rr = if (risk > 0.0 && target != null) abs(target - price) / risk else 0.0
        val net = if (target != null) XagAresPolicy.netRewardRisk(price, stop!!, target, cost) else Double.NaN
        val costToRisk = if (risk > 0.0) cost / risk else Double.MAX_VALUE
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = if (compressed) 2 else 1,
            structureScore = if (compressed && direction != SignalAction.NO_TRADE) 2 else if (compressed) 1 else 0,
            confirmationScore = if (expansionOk && volumeOk && followThrough) 2
            else if (expansionOk || volumeOk) 1 else 0,
            rrScore = rrPoints(net, cfg),
            executionScore = executionPoints(net, costToRisk, f.session, cfg),
            structuralLevel = level.takeIf { direction != SignalAction.NO_TRADE },
            invalidation = stop, entry = if (direction != SignalAction.NO_TRADE) price else null,
            stop = stop, target = target, rr = rr, netRr = net,
            costPerOz = cost, costToRisk = costToRisk,
            setupType = "VOLATILITY_BREAKOUT",
            satisfied = satisfied, missing = missing,
            detail = "فشردگی ${"%.2f".format(java.util.Locale.US, f.compressionRatio)} · " +
                "گسترش ${"%.2f".format(java.util.Locale.US, f.rangeExpansion)}× · " +
                "RR خالص ${"%.2f".format(java.util.Locale.US, net)}",
        )
    }

    /** Cross-market context. Reported, never actionable — the module cannot open a trade. */
    private fun engineX(f: Features): Candidate {
        val gold = f.gold
        if (!gold.available) {
            return noTrade(
                EngineId.X, State.UNAVAILABLE,
                "کندل XAU/USD هم‌زمان در دسترس نیست؛ ماژول طلا/نقره خاموش است و حدس زده نمی‌شود",
                dataAvailable = false,
            )
        }
        val biasText = when (gold.bias) {
            SignalAction.BUY -> "نقره نسبت به طلا قوی‌تر است"
            SignalAction.SELL -> "نقره نسبت به طلا ضعیف‌تر است"
            else -> "جهت نسبی مشخص نیست"
        }
        return Candidate(
            engine = EngineId.X, state = State.WATCH, direction = SignalAction.NO_TRADE,
            regimeScore = 0, structureScore = 0,
            confirmationScore = if (gold.bias != SignalAction.NO_TRADE) 1 else 0,
            rrScore = 0,
            executionScore = if (gold.available) 1 else 0,
            structuralLevel = null, invalidation = null, entry = null, stop = null, target = null,
            rr = null, netRr = null, costPerOz = 0.0, costToRisk = 0.0,
            setupType = "CROSS_CONTEXT",
            satisfied = listOfNotNull(
                gold.correlation?.let { "همبستگی ${"%.2f".format(java.util.Locale.US, it)}" },
                gold.relativeMomentum?.let { "مومنتوم نسبی ${"%.3f".format(java.util.Locale.US, it * 100.0)}٪" },
            ),
            missing = emptyList(),
            detail = "$biasText · ${gold.noteFa}",
        )
    }

    private fun report(c: Candidate, cfg: Config): XagAresEngineReport = XagAresEngineReport(
        engine = c.engine.id,
        labelFa = c.engine.labelFa,
        state = c.state.name,
        direction = c.direction,
        scoreOutOf10 = c.score,
        regimeScore = c.regimeScore,
        structureScore = c.structureScore,
        confirmationScore = c.confirmationScore,
        rrScore = c.rrScore,
        executionScore = c.executionScore,
        structuralLevel = c.structuralLevel,
        invalidationLevel = c.invalidation,
        entry = c.entry,
        stopLoss = c.stop,
        takeProfit = c.target,
        rewardToRisk = c.rr,
        netRewardRisk = c.netRr,
        costPerOzUsd = c.costPerOz,
        costToRiskFraction = c.costToRisk,
        setupType = c.setupType,
        satisfied = c.satisfied,
        missing = c.missing,
        detail = c.detail,
        dataAvailable = c.dataAvailable,
        ageBars = 0,
        maxAgeBars = cfg.maxSetupAgeBars,
    )

    private fun confluenceItems(
        regime: Regime,
        best: Candidate?,
        actionable: Boolean,
        cfg: Config,
    ): List<ConfluenceItem> {
        fun item(name: String, points: Int, detail: String): ConfluenceItem {
            val clamped = points.coerceIn(0, 25)
            return ConfluenceItem(name, clamped == 25, detail,
                when {
                    clamped == 25 -> ConfluenceStatus.CONFIRMED
                    clamped > 0 -> ConfluenceStatus.PARTIAL
                    else -> ConfluenceStatus.CONFLICT
                }, clamped)
        }
        if (best == null) {
            return listOf(
                item("رژیم و نوسان · ALS-5S", if (regime != Regime.HIGH_VOL) 12 else 0, "رژیم ${regime.labelFa}"),
                item("ساختار و سطح · ALS-5S", 0, "بدون سطح فعال"),
                item("تأیید و موتور · ALS-5S", 0, "بدون موتور آماده"),
                item("هزینه و اجرا · ALS-5S", 0, "بدون معامله"),
            )
        }
        val s1 = if (actionable && regime != Regime.HIGH_VOL) 25 else if (regime != Regime.HIGH_VOL) 12 else 0
        val s2 = if (actionable && best.structuralLevel != null) 25 else if (best.structuralLevel != null) 12 else 0
        val s3 = if (actionable) 25 else if (best.state != State.IDLE) 12 else 0
        val s4 = if (actionable && (best.netRr ?: 0.0) >= cfg.minRewardRisk &&
            best.costToRisk <= cfg.maxCostToRiskFraction) 25
        else if ((best.netRr ?: 0.0) >= cfg.minRewardRisk) 12 else 0
        return listOf(
            item("رژیم و نوسان · ALS-5S", s1, "رژیم ${regime.labelFa}"),
            item("ساختار و سطح · ALS-5S", s2,
                "${best.engine.shortFa} · سطح ${best.structuralLevel?.let { "%.3f".format(java.util.Locale.US, it) } ?: "—"}"),
            item("تأیید و موتور · ALS-5S", s3,
                "وضعیت ${best.state.name} · ${best.engine.labelFa} · امتیاز ${best.score}/10"),
            item("هزینه و اجرا · ALS-5S", s4,
                "RR خالص ${best.netRr?.let { "%.2f".format(java.util.Locale.US, it) } ?: "—"} · " +
                    "هزینه ${"%.3f".format(java.util.Locale.US, best.costPerOz)} دلار/انس · " +
                    "هزینه÷ریسک ${"%.0f".format(java.util.Locale.US, best.costToRisk * 100)}٪"),
        )
    }

    /**
     * Evaluate XAG/USD. The returned Signal always carries the full [XagAresRecord], including the
     * NO_TRADE case, so the trade tab can show what each engine is waiting for.
     */
    fun evaluate(
        input: List<Candle>,
        interval: Interval = Interval.M5,
        symbol: String = SYMBOL,
        threshold: Double = 80.0,
        @Suppress("UNUSED_PARAMETER") mode: CategoryStrategy = CategoryStrategy.HYBRID,
        external: Map<Interval, List<Candle>> = emptyMap(),
        goldBars: List<Candle> = emptyList(),
        config: Config = Config(),
        now: Long = System.currentTimeMillis(),
    ): Signal {
        val closed = input.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        val last = closed.lastOrNull()
        val barTime = last?.time ?: 0L

        fun blocked(reason: String): Signal {
            val record = XagAresRecord(
                regime = Regime.HIGH_VOL.name,
                regimeLabelFa = Regime.HIGH_VOL.labelFa,
                volatilityState = Volatility.NORMAL.name,
                engines = emptyList(),
                noTradeReasons = listOf(reason),
                barTime = barTime,
            )
            return Signal(
                action = SignalAction.NO_TRADE, confidence = 0.0,
                blockers = listOf(reason), reasons = listOf("ALS-5S · بدون دادهٔ کافی"),
                interval = interval, barTime = barTime, als5s = record,
            )
        }

        if (V1Universe.normalize(symbol) != SYMBOL)
            return blocked("ALS-5S فقط برای XAG/USD است؛ نماد $symbol رد شد")
        if (interval != Interval.M5)
            return blocked("ورود ALS-5S فقط روی کندل بستهٔ ۵ دقیقه (M5) مجاز است")
        if (closed.size < config.minBars)
            return blocked("ALS-5S به ${config.minBars} کندل بستهٔ M5 نیاز دارد (فعلی ${closed.size})")
        if (closed.zipWithNext().any { (a, b) -> b.time - a.time != interval.millis })
            return blocked("کندل‌های M5 پیوسته نیستند؛ شکاف داده بررسی شود")
        if (config.enforceFreshness &&
            now - (last!!.time + interval.millis) !in 0L..(interval.millis + 90_000L)
        ) return blocked("آخرین کندل بستهٔ M5 تازه نیست")

        val m15 = external[Interval.M15].orEmpty().filter { it.closed && it.time + Interval.M15.millis <= now }
        val h1 = external[Interval.H1].orEmpty().filter { it.closed && it.time + Interval.H1.millis <= now }
        val f = features(closed, m15, h1, goldBars, config)
        if (!f.atr.isFinite() || f.atr <= 0.0) return blocked("ATR معتبر نیست")
        val vol = volatilityOf(f, config)

        // Hysteresis needs one earlier classification; it is built from a shorter, still-closed slice.
        val previousRaw = if (closed.size > config.minBars) {
            val prevBars = closed.dropLast(1)
            val prevM15 = m15.filter { it.time + Interval.M15.millis <= prevBars.last().time + interval.millis }
            val prevH1 = h1.filter { it.time + Interval.H1.millis <= prevBars.last().time + interval.millis }
            rawRegime(features(prevBars, prevM15, prevH1, goldBars, config), config)
        } else null
        val (regime, hysteresisNote) = classifyRegime(rawRegime(f, config), previousRaw, config)

        val price = closed.last().close
        val costPerOz = XagAresPolicy.roundTripCostPerOz(price, null, config.slippageBps)
        val candidates = buildList {
            add(engineA(closed, f, regime, config, costPerOz))
            add(engineB(closed, f, regime, config, costPerOz))
            add(engineCRange(closed, f, regime, config, costPerOz))
            add(engineCBreakout(closed, f, regime, config, costPerOz))
            add(engineX(f))
        }
        val reports = candidates.map { report(it, config) }
        val ready = candidates
            .filter { it.state == State.READY && it.direction != SignalAction.NO_TRADE }
            .sortedByDescending { it.score }
        val conflicts = mutableListOf<String>()
        if (ready.size >= 2) {
            val top = ready[0]
            val opposite = ready.firstOrNull { it.direction != top.direction }
            if (opposite != null && abs(top.score - opposite.score) < config.conflictMargin10) {
                conflicts += "تعارض ${top.engine.shortFa} و ${opposite.engine.shortFa} " +
                    "(اختلاف امتیاز ${abs(top.score - opposite.score)} < ${config.conflictMargin10})"
            }
        }
        val noTradeReasons = mutableListOf<String>()
        val best = when {
            conflicts.isNotEmpty() -> null
            ready.isEmpty() -> null
            else -> ready.first()
        }
        if (best == null && conflicts.isEmpty()) {
            noTradeReasons += "هیچ موتوری به READY نرسید؛ " +
                candidates.filter { it.state != State.UNAVAILABLE && it.state != State.IDLE && it.state != State.WATCH }
                    .joinToString("، ") { "${it.engine.shortFa}=${it.state.name}" }
        }
        val required = max(config.scoreThreshold10, (threshold / 10.0).roundToInt().coerceIn(6, 10))
        if (best != null && best.score < required)
            noTradeReasons += "امتیاز ${best.score}/10 کمتر از حد نصاب $required است"
        if (best != null && (best.netRr ?: 0.0) < config.minRewardRisk)
            noTradeReasons += "R:R خالص ${"%.2f".format(java.util.Locale.US, best.netRr ?: 0.0)} " +
                "کمتر از ${config.minRewardRisk} است"
        if (best != null && best.costToRisk > config.maxCostToRiskFraction)
            noTradeReasons += "هزینهٔ رفت‌وبرگشت ${"%.2f".format(java.util.Locale.US, best.costPerOz)} دلار/انس " +
                "معادل ${"%.0f".format(java.util.Locale.US, best.costToRisk * 100)}٪ حد ضرر است " +
                "(سقف ${"%.0f".format(java.util.Locale.US, config.maxCostToRiskFraction * 100)}٪)"
        if (config.dailyRealizedLossPct >= config.maxDailyLossPct)
            noTradeReasons += "سقف ضرر روزانه (${config.maxDailyLossPct}٪) پر شده است"
        if (regime == Regime.HIGH_VOL)
            noTradeReasons += "نوسان بالا/نامشخص: ورود تازه مجاز نیست"
        if (config.sessionGateEnabled && f.session in config.blockedSessions)
            noTradeReasons += "سشن ${f.session.labelFa} برای ورود مسدود است"
        if (config.requireGoldConfirmation) {
            if (!f.gold.available)
                noTradeReasons += "تأیید طلا لازم است ولی کندل XAU/USD در دسترس نیست"
            else if (best != null && f.gold.bias != SignalAction.NO_TRADE && f.gold.bias != best.direction)
                noTradeReasons += "طلا جهت مخالف را نشان می‌دهد و فیلتر تأیید روشن است"
        }

        val actionableCandidate = best?.takeIf { noTradeReasons.isEmpty() }
        val (oz, riskUsd) = if (actionableCandidate?.entry != null && actionableCandidate.stop != null)
            XagAresPolicy.positionOz(
                actionableCandidate.entry, actionableCandidate.stop, config.accountEquityUsd,
                config.riskPerTradePct, actionableCandidate.costPerOz,
                config.qtyStepOz, config.minNotionalUsd, config.maxNotionalToEquity,
            ) else 0.0 to 0.0
        if (actionableCandidate != null && oz <= 0.0)
            noTradeReasons += "حجم محاسبه‌شده کمتر از حداقل سفارش (${config.minNotionalUsd} دلار) است"

        val finalAction = if (actionableCandidate != null && oz > 0.0) actionableCandidate.direction
        else SignalAction.NO_TRADE
        val setupKey = if (finalAction != SignalAction.NO_TRADE && actionableCandidate != null)
            listOf(STRATEGY, SYMBOL, actionableCandidate.engine.id, actionableCandidate.setupType,
                finalAction.name, "%.3f".format(java.util.Locale.US, actionableCandidate.structuralLevel ?: 0.0),
                barTime.toString()).joinToString("|")
        else ""
        val confluence = confluenceItems(regime, best, finalAction != SignalAction.NO_TRADE, config)
        val confidence = confluence.sumOf { it.scorePercent ?: 0 }.toDouble()
        val sessionLabel = f.session.labelFa
        val record = XagAresRecord(
            regime = regime.name,
            regimeLabelFa = regime.labelFa,
            volatilityState = vol.name,
            atr = f.atr,
            atrRatio = f.atrRatio,
            atrPercentile = f.atrPercentile,
            rangeExpansion = f.rangeExpansion,
            session = f.session.name,
            sessionLabelFa = sessionLabel,
            goldContext = f.gold,
            higherTimeframeAvailable = f.m15Available,
            engines = reports,
            chosenEngine = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.engine?.id else null,
            action = finalAction,
            scoreOutOf10 = if (finalAction != SignalAction.NO_TRADE) (actionableCandidate?.score ?: 0)
            else (best?.score ?: 0),
            structuralLevel = actionableCandidate?.structuralLevel,
            invalidationLevel = actionableCandidate?.invalidation,
            entry = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.entry else null,
            stopLoss = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.stop else null,
            takeProfit = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.target else null,
            rewardToRisk = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.rr else null,
            netRewardRisk = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.netRr else best?.netRr,
            costPerOzUsd = actionableCandidate?.costPerOz ?: costPerOz,
            costToRiskFraction = actionableCandidate?.costToRisk ?: 0.0,
            positionOz = oz,
            riskUsd = riskUsd,
            maxRiskPct = config.riskPerTradePct.coerceAtMost(0.25),
            conflicts = conflicts,
            noTradeReasons = noTradeReasons + listOfNotNull(hysteresisNote.takeIf { it.isNotBlank() }),
            dataNotes = buildList {
                add("قرارداد نقره از VenueSpecs: تیک ${VenueSpecs.of(SYMBOL).tickSize} · " +
                    "کمیسیون ${VenueSpecs.of(SYMBOL).commissionPerUnit} دلار/انس · " +
                    "اسپرد اسمی ${VenueSpecs.of(SYMBOL).spreadPrice}")
                add("هزینهٔ نمایش‌داده‌شده با اسپرد اسمی محاسبه شده؛ گیت ورود از اسپرد زندهٔ همان ناشر استفاده می‌کند")
                if (!f.gold.available) add("ماژول طلا/نقره خاموش است؛ سیستم بدون آن هم کار می‌کند")
                if (!f.h1Available) add("قاب H1 کامل نیست؛ جهت اصلی از M15 گرفته می‌شود")
            },
            setupKey = setupKey,
            barTime = barTime,
            expiresInBars = config.maxSetupAgeBars,
        )
        return Signal(
            action = finalAction,
            confidence = confidence,
            entry = record.entry,
            stopLoss = record.stopLoss,
            takeProfit = record.takeProfit,
            riskReward = record.rewardToRisk,
            reasons = listOf(
                if (finalAction == SignalAction.NO_TRADE)
                    "ALS-5S · ${regime.labelFa} · نوسان ${vol.labelFa} · بدون ورود: " +
                        (noTradeReasons.firstOrNull() ?: "شرط‌ها کامل نیست")
                else "ALS-5S · ${actionableCandidate!!.engine.labelFa} · امتیاز ${actionableCandidate.score}/10 · " +
                    "RR خالص ${"%.2f".format(java.util.Locale.US, actionableCandidate.netRr ?: 0.0)} · " +
                    "هزینه ${"%.3f".format(java.util.Locale.US, actionableCandidate.costPerOz)} دلار/انس · " +
                    "حجم ${"%.2f".format(java.util.Locale.US, oz)} اونس"
            ),
            blockers = if (finalAction == SignalAction.NO_TRADE) noTradeReasons + conflicts else emptyList(),
            confluence = confluence,
            interval = interval,
            barTime = barTime,
            als5s = record,
        )
    }

    /** Exposed for tests and diagnostics; never a shortcut around [evaluate]. */
    internal fun analyze(
        bars: List<Candle>,
        m15: List<Candle> = emptyList(),
        h1: List<Candle> = emptyList(),
        goldBars: List<Candle> = emptyList(),
        config: Config = Config(),
    ): Pair<Regime, Volatility> {
        val f = features(bars, m15, h1, goldBars, config)
        return rawRegime(f, config) to volatilityOf(f, config)
    }
}
