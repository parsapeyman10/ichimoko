package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.EurAresEngineReport
import com.aurum.edge.core.EurAresPolicy
import com.aurum.edge.core.EurAresRecord
import com.aurum.edge.core.EurAresSessionInfo
import com.aurum.edge.core.EurMacroContext
import com.aurum.edge.core.EurPips
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.V1Universe
import com.aurum.edge.core.VenueSpecs
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * EUR-ARES — Adaptive Euro-Dollar Regime, Execution & Signal Intelligence System.
 *
 * EUR/USD is not gold with a different ticker, and this engine is not a copy of ALS-5/ALS-5S:
 *
 *  - **Pips are the unit of everything.** ATR, stops, spread, commission and targets are converted
 *    to pips before any comparison happens. A 0.1-pip spread is 1 % of a 10-pip stop, so a
 *    "percentage of price" cost model would round the only decisive number of this pair to zero.
 *  - **Sessions are computed from local clocks** ([EurAresPolicy.sessionAt]) so the London/New York
 *    overlap does not drift by an hour at every DST change, and the 17:00-New-York rollover window
 *    is a first-class NO-TRADE window rather than an accident.
 *  - **Session-relative volatility** ([Features.sessionVolRatio]) asks "is this ATR normal *for this
 *    session*", because 2 pips is quiet in the overlap and normal in Asia.
 *  - **Engine D exists only here**: a session-range breakout (Asian range, or the London opening
 *    range) with a check for how much room is left before the next obstacle. A currency pair with a
 *    session clock has that setup; silver and gold do not.
 *  - **Engine E (news) is honest**: the app has no reliable timestamped economic calendar, so the
 *    engine reports UNAVAILABLE and never fabricates an event window.
 *  - **Macro is optional and off by default.** [EurMacroContext] is only used when the caller
 *    supplies measured values; a missing DXY/yield series shows as unavailable, not as zero.
 *
 * Closed bars only. Confirmed pivots only (a pivot exists once its right bars have closed). Every
 * refusal carries a reason. The 0–100 score is a deterministic quality score, never a probability.
 */
object EurAresEngine {
    const val SYMBOL = EurAresPolicy.SYMBOL
    const val STRATEGY = EurAresPolicy.STRATEGY

    enum class Regime(val labelFa: String) {
        BULL_TREND("روند صعودی یورو"),
        BEAR_TREND("روند نزولی یورو"),
        RANGE("رنج / بازگشت به میانگین"),
        COMPRESSION("فشردگی نوسان"),
        EVENT_UNSTABLE("نوسان رویدادی / نامشخص"),
    }

    enum class Volatility(val labelFa: String) {
        LOW("کم"),
        NORMAL("عادی"),
        TRANSITION("در گذار"),
        ELEVATED("بالا"),
        EXTREME("بحرانی"),
    }

    enum class State { IDLE, WATCH, FORMING, CONFIRMATION, READY, ACTIVE, INVALIDATED, EXPIRED, UNAVAILABLE }

    enum class EngineId(val id: String, val labelFa: String, val shortFa: String) {
        A("ENGINE_A_TREND", "موتور A · ادامهٔ روند و پولبک", "A · روند"),
        B("ENGINE_B_SWEEP", "موتور B · شکست ناموفق / سوییپ نقدینگی", "B · سوییپ"),
        C("ENGINE_C_RANGE", "موتور C · بازگشت به میانگین رنج", "C · رنج"),
        D("ENGINE_D_SESSION", "موتور D · شکست رنج سشن", "D · سشن"),
        E("ENGINE_E_NEWS", "موتور E · نوسان خبری (بدون منبع تقویم)", "E · خبر"),
        X("ENGINE_X_MACRO", "ماژول X · کلان/کراس‌مارکت (فقط زمینه)", "X · کلان"),
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
        val htfEma: Int = 50,
        val rsiPeriod: Int = 14,
        // Volatility / regime
        val volMedianLookback: Int = 60,
        val atrPercentileLookback: Int = 240,
        val sessionVolLookback: Int = 240,
        val rangeExpansionFast: Int = 3,
        val rangeExpansionBaseline: Int = 20,
        val lowAtrRatio: Double = 0.70,
        val elevatedAtrRatio: Double = 1.40,
        val extremeAtrRatio: Double = 2.20,
        val transitionExpansion: Double = 1.35,
        val eventExpansion: Double = 1.80,
        val trendAdxMin: Double = 22.0,
        val rangeAdxMax: Double = 18.0,
        val compressionMaxRangeAtr: Double = 0.80,
        val minRangeWidthAtr: Double = 1.2,
        val maxRangeWidthAtr: Double = 5.0,
        val regimeConfirmBars: Int = 2,
        // Engine A
        val minImpulseAtr: Double = 1.20,
        val minPullbackFrac: Double = 0.25,
        val maxPullbackFrac: Double = 0.85,
        val pullbackZoneAtr: Double = 0.55,
        val maxChaseAtr: Double = 1.20,
        val minBodyFrac: Double = 0.50,
        val minImpulsePips: Double = 6.0,
        // Engine B
        val sweepWindowBars: Int = 6,
        val minSweepAtr: Double = 0.15,
        val maxSweepAtr: Double = 1.20,
        val minSweepPips: Double = 0.8,
        val requireRetest: Boolean = false,
        val retestToleranceAtr: Double = 0.20,
        // Engine C
        val rangeTouchFrac: Double = 0.20,
        val minRejectionWickFrac: Double = 0.40,
        val rangeInvalidationAtr: Double = 0.25,
        // Engine D
        val sessionRangeMinBars: Int = 12,
        val minBreakoutAtr: Double = 0.15,
        val minExpansionForBreakout: Double = 1.15,
        val followThroughFrac: Double = 0.55,
        val maxBreakoutChaseAtr: Double = 1.00,
        val minRoomR: Double = 1.50,
        val roundNumberPips: Double = 50.0,
        val requireSessionRetest: Boolean = false,
        // Risk / execution
        val stopBufferAtr: Double = 0.30,
        val minStopPips: Double = EurAresPolicy.MIN_STOP_PIPS,
        val maxStopPips: Double = 30.0,
        val targetRr: Double = 2.0,
        val minRewardRisk: Double = EurAresPolicy.MIN_NET_REWARD_RISK,
        val maxCostToRiskFraction: Double = EurAresPolicy.MAX_COST_TO_RISK_FRACTION,
        val slippagePips: Double = 0.2,
        val spreadPipsOverride: Double? = null,
        val pipValueUsdPerLot: Double = EurAresPolicy.PIP_VALUE_USD_PER_LOT,
        val lotStep: Double = EurAresPolicy.LOT_STEP,
        val minUnits: Double = EurAresPolicy.MIN_UNITS,
        val maxLeverage: Int = 30,
        val riskPerTradePct: Double = 0.25,
        val maxDailyLossPct: Double = 1.00,
        val dailyRealizedLossPct: Double = 0.0,
        val accountEquityUsd: Double = 10_000.0,
        val scoreThreshold100: Int = 70,
        val conflictMargin100: Int = 10,
        val maxSetupAgeBars: Int = 12,
        // Sessions are a feature by default, not a hidden gate.
        val sessionGateEnabled: Boolean = false,
        val blockedSessions: Set<String> = setOf("ROLLOVER", "OFF"),
        val requireHtfAlignment: Boolean = true,
        val macroEnabled: Boolean = false,
        val requireMacroConfirmation: Boolean = false,
        val minBars: Int = HistoryPolicy.LIVE_MIN_CANDLES,
        val enforceFreshness: Boolean = true,
        val enableA: Boolean = true,
        val enableB: Boolean = true,
        val enableC: Boolean = true,
        val enableD: Boolean = true,
    )

    internal data class Features(
        val atr: Double,
        val atrPips: Double,
        val atrRatio: Double,
        val atrPercentile: Double,
        val sessionVolRatio: Double,
        val rangeExpansion: Double,
        val adx: Double,
        val emaFast: Double,
        val emaSlow: Double,
        val rsi: Double,
        val macdHist: Double,
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
        val htfAvailable: Boolean,
        val htfLabel: String,
        val d1Available: Boolean,
        val d1Bias: SignalAction?,
        val session: EurAresSessionInfo,
        val asianHigh: Double?,
        val asianLow: Double?,
        val asianBars: Int,
        val openingRangeHigh: Double?,
        val openingRangeLow: Double?,
        val openingRangeReady: Boolean,
        val prevDayHigh: Double?,
        val prevDayLow: Double?,
        val roundAbove: Double?,
        val roundBelow: Double?,
        val macro: EurMacroContext,
    ) {
        val sessionName: String get() = session.session
    }

    /** One engine's verdict before arbitration. The eight components sum to [score]. */
    private data class Candidate(
        val engine: EngineId,
        val state: State,
        val direction: SignalAction,
        val regimeScore: Int,
        val structureScore: Int,
        val entryScore: Int,
        val confirmationScore: Int,
        val volatilityScore: Int,
        val sessionScore: Int,
        val rrScore: Int,
        val costScore: Int,
        val structuralLevel: Double?,
        val invalidation: Double?,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val rr: Double?,
        val netRr: Double?,
        val costPips: Double,
        val costToRisk: Double,
        val setupType: String,
        val satisfied: List<String>,
        val missing: List<String>,
        val detail: String,
        val dataAvailable: Boolean = true,
    ) {
        val stopPips: Double?
            get() = if (entry != null && stop != null) abs(entry - stop) / EurPips.PIP else null

        val score: Int
            get() = (regimeScore + structureScore + entryScore + confirmationScore +
                volatilityScore + sessionScore + rrScore + costScore).coerceIn(0, 100)
    }

    // -------------------------------------------------------------------------------------
    // Structure — identical contract to the rest of the app: a pivot exists only after its
    // right bars have closed, so nothing here can see the future.
    // -------------------------------------------------------------------------------------
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

    internal fun atrPercentile(series: List<Double?>, lookback: Int): Double =
        StrategyMath.atrPercentile(series, lookback)

    /** Session of a bar, DST-aware. */
    fun sessionOf(timeMs: Long): EurAresSessionInfo = EurAresPolicy.sessionAt(timeMs)

    /**
     * ATR of the current session ÷ the median ATR of that same session over [lookback] bars.
     * Answered per session because 2 pips is quiet in the overlap and ordinary in Asia.
     */
    internal fun sessionVolRatio(
        bars: List<Candle>, atrSeries: List<Double?>, session: String, lookback: Int,
    ): Double {
        if (bars.isEmpty()) return 1.0
        val current = atrSeries.lastOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return 1.0
        val start = max(0, bars.size - lookback)
        val peers = (start until bars.size).mapNotNull { i ->
            if (sessionOf(bars[i].time).session != session) return@mapNotNull null
            atrSeries.getOrNull(i)?.takeIf { it.isFinite() && it > 0.0 }
        }.sorted()
        if (peers.size < 10) return 1.0
        val median = peers[peers.size / 2]
        return if (median > 0.0) current / median else 1.0
    }

    // -------------------------------------------------------------------------------------
    // Structure helpers: Asian range, London opening range, previous day, round numbers.
    // Each is computed from closed bars only, so an incomplete range can never leak its future.
    // -------------------------------------------------------------------------------------
    internal fun asianRange(bars: List<Candle>): Triple<Double?, Double?, Int> {
        if (bars.isEmpty()) return Triple(null, null, 0)
        val day = utcDay(bars.last().time)
        val today = bars.filter { utcDay(it.time) == day && sessionOf(it.time).session == "ASIA" }
        if (today.isEmpty()) return Triple(null, null, 0)
        return Triple(today.maxOf { it.high }, today.minOf { it.low }, today.size)
    }

    /** London 08:00–09:00 local, only while that hour is in the past. */
    internal fun londonOpeningRange(bars: List<Candle>): Triple<Double?, Double?, Boolean> {
        if (bars.isEmpty()) return Triple(null, null, false)
        val last = bars.last()
        val (londonNow, _, _) = EurAresPolicy.clocks(last.time)
        val range = bars.filter { bar ->
            val (local, _, _) = EurAresPolicy.clocks(bar.time)
            local.toLocalDate() == londonNow.toLocalDate() && local.hour == 8
        }
        if (range.isEmpty()) return Triple(null, null, false)
        val ready = londonNow.hour >= 9
        return Triple(range.maxOf { it.high }, range.minOf { it.low }, ready)
    }

    internal fun previousDayRange(bars: List<Candle>): Pair<Double?, Double?> {
        if (bars.isEmpty()) return null to null
        val day = utcDay(bars.last().time)
        val priorDays = bars.map { utcDay(it.time) }.filter { it < day }.distinct().sorted()
        val prev = priorDays.lastOrNull() ?: return null to null
        val slice = bars.filter { utcDay(it.time) == prev }
        if (slice.size < 24) return null to null
        return slice.maxOf { it.high } to slice.minOf { it.low }
    }

    /** Nearest 50-pip grid level above and below price; a documented round-number obstacle. */
    internal fun roundNumbers(price: Double, stepPips: Double): Pair<Double?, Double?> {
        if (!price.isFinite() || price <= 0.0 || stepPips <= 0.0) return null to null
        val step = stepPips * EurPips.PIP
        val below = kotlin.math.floor(price / step) * step
        val above = below + step
        return kotlin.math.round(above * 100_000.0) / 100_000.0 to
            kotlin.math.round(below * 100_000.0) / 100_000.0
    }

    private fun utcDay(ms: Long): Long = Math.floorDiv(ms, 86_400_000L)

    // -------------------------------------------------------------------------------------
    // Regime
    // -------------------------------------------------------------------------------------
    internal fun volatilityOf(f: Features, cfg: Config = Config()): Volatility = when {
        f.atrRatio >= cfg.extremeAtrRatio || f.atrPercentile >= 0.97 -> Volatility.EXTREME
        f.atrRatio >= cfg.elevatedAtrRatio || f.atrPercentile >= 0.85 -> Volatility.ELEVATED
        f.atrRatio <= cfg.lowAtrRatio && f.atrPercentile <= 0.25 -> Volatility.LOW
        f.rangeExpansion >= cfg.transitionExpansion &&
            f.atrRatio in 0.85..cfg.elevatedAtrRatio -> Volatility.TRANSITION
        else -> Volatility.NORMAL
    }

    /** One-bar classification; hysteresis is applied in [classifyRegime]. */
    internal fun rawRegime(f: Features, cfg: Config = Config()): Regime {
        val vol = volatilityOf(f, cfg)
        val bullish = (f.hh && f.hl) || f.bosUp
        val bearish = (f.lh && f.ll) || f.bosDown
        if (vol == Volatility.EXTREME) return Regime.EVENT_UNSTABLE
        if (vol == Volatility.ELEVATED && f.rangeExpansion >= cfg.eventExpansion)
            return Regime.EVENT_UNSTABLE
        if (bullish && bearish) return Regime.EVENT_UNSTABLE
        if (f.compressionRatio <= cfg.compressionMaxRangeAtr && f.adx < cfg.trendAdxMin)
            return Regime.COMPRESSION
        val widthAtr = (f.rangeHigh - f.rangeLow) / f.atr
        if (bullish && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.SELL)
            return Regime.BULL_TREND
        if (bearish && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.BUY)
            return Regime.BEAR_TREND
        if (widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr && f.adx <= cfg.rangeAdxMax)
            return Regime.RANGE
        return Regime.EVENT_UNSTABLE
    }

    /**
     * Hysteresis: a new label must survive a confirmation bar so one noisy candle cannot swap the
     * active engine set. The protective state (EVENT_UNSTABLE) is entered immediately and left only
     * with confirmation. [previous] is the previous bar's **raw** label.
     */
    internal fun classifyRegime(
        now: Regime, previous: Regime?, cfg: Config = Config(),
    ): Pair<Regime, String> {
        if (previous == null || now == previous) return now to ""
        if (now == Regime.EVENT_UNSTABLE)
            return now to "ورود فوری به حالت محافظتی نوسان رویدادی"
        if (previous == Regime.EVENT_UNSTABLE)
            return previous to "خروج از حالت محافظتی فقط با تأیید یک کندل دیگر"
        if (cfg.regimeConfirmBars <= 1) return now to ""
        return previous to "هیسترزیس: ${previous.labelFa} تا تأیید ${cfg.regimeConfirmBars} کندل حفظ شد"
    }

    // -------------------------------------------------------------------------------------
    // Features
    // -------------------------------------------------------------------------------------
    internal fun features(
        bars: List<Candle>,
        h1: List<Candle>,
        h4: List<Candle>,
        d1: List<Candle>,
        macro: EurMacroContext,
        cfg: Config,
    ): Features {
        val closes = bars.map { it.close }
        val atrSeries = Indicators.atr(bars, cfg.atrPeriod)
        val atr = atrSeries.lastOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val recent = atrSeries.takeLast(cfg.volMedianLookback).filterNotNull()
            .filter { it > 0.0 && it.isFinite() }.sorted()
        val median = recent.getOrNull(recent.size / 2)?.takeIf { it > 0.0 } ?: atr
        val ranges = bars.map { it.high - it.low }
        val fastRange = ranges.takeLast(cfg.rangeExpansionFast).average()
        val baseRange = ranges.takeLast(cfg.rangeExpansionBaseline).average()
        val expansion = if (baseRange > 0.0) fastRange / baseRange else 1.0
        val adx = Indicators.adx(bars, cfg.adxPeriod).lastOrNull() ?: 0.0
        val emaFast = Indicators.ema(closes, cfg.emaFast).lastOrNull() ?: bars.last().close
        val emaSlow = Indicators.ema(closes, cfg.emaSlow).lastOrNull() ?: bars.last().close
        val rsi = Indicators.rsi(bars, cfg.rsiPeriod).lastOrNull() ?: 50.0
        val macd = Indicators.macdHistogram(bars).lastOrNull() ?: 0.0

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

        val window = bars.takeLast(24 + 1).dropLast(1)
        val rangeHigh = window.maxOfOrNull { it.high } ?: bars.last().high
        val rangeLow = window.minOfOrNull { it.low } ?: bars.last().low
        val compressionRatio = if (atr > 0.0) (rangeHigh - rangeLow) / (atr * sqrt(24.0)) else 1.0

        val h1Available = h1.size >= HistoryPolicy.minSize(Interval.H1)
        val h1Bias = if (!h1Available) null else {
            val c = h1.last().close
            val e = Indicators.ema(h1.map { it.close }, cfg.htfEma).lastOrNull() ?: c
            when {
                c > e -> SignalAction.BUY
                c < e -> SignalAction.SELL
                else -> SignalAction.NO_TRADE
            }
        }
        val d1Available = d1.size >= 20
        val d1Bias = if (!d1Available) null else {
            val c = d1.last().close
            val e = Indicators.ema(d1.map { it.close }, 20).lastOrNull() ?: c
            when {
                c > e -> SignalAction.BUY
                c < e -> SignalAction.SELL
                else -> SignalAction.NO_TRADE
            }
        }
        val htfLabel = buildString {
            append(if (h1Available) "H1 ${if (h1Bias == SignalAction.BUY) "↑" else if (h1Bias == SignalAction.SELL) "↓" else "—"}" else "H1 بدون داده")
            append(" · ")
            append(if (h4.size >= 20) "H4 موجود" else "H4 بدون داده")
            append(" · ")
            append(if (d1Available) "D1 ${if (d1Bias == SignalAction.BUY) "↑" else if (d1Bias == SignalAction.SELL) "↓" else "—"}" else "D1 بدون داده")
        }

        val session = sessionOf(bars.last().time)
        val asian = asianRange(bars)
        val opening = londonOpeningRange(bars)
        val prevDay = previousDayRange(bars)
        val round = roundNumbers(close, cfg.roundNumberPips)

        return Features(
            atr = atr,
            atrPips = atr / EurPips.PIP,
            atrRatio = if (median > 0.0) atr / median else 1.0,
            atrPercentile = atrPercentile(atrSeries, cfg.atrPercentileLookback),
            sessionVolRatio = sessionVolRatio(bars, atrSeries, session.session, cfg.sessionVolLookback),
            rangeExpansion = expansion,
            adx = adx,
            emaFast = emaFast,
            emaSlow = emaSlow,
            rsi = rsi,
            macdHist = macd,
            swingHigh = lastHighs.lastOrNull(),
            swingLow = lastLows.lastOrNull(),
            hh = hh, hl = hl, lh = lh, ll = ll,
            bosUp = bosUp,
            bosDown = bosDown,
            rangeHigh = rangeHigh,
            rangeLow = rangeLow,
            compressionRatio = compressionRatio,
            htfBias = h1Bias,
            htfAvailable = h1Available,
            htfLabel = htfLabel,
            d1Available = d1Available,
            d1Bias = d1Bias,
            session = session,
            asianHigh = asian.first,
            asianLow = asian.second,
            asianBars = asian.third,
            openingRangeHigh = opening.first,
            openingRangeLow = opening.second,
            openingRangeReady = opening.third,
            prevDayHigh = prevDay.first,
            prevDayLow = prevDay.second,
            roundAbove = round.first,
            roundBelow = round.second,
            macro = macro,
        )
    }

    // -------------------------------------------------------------------------------------
    // Scoring helpers — deterministic, additive, and capped per component.
    // -------------------------------------------------------------------------------------
    private fun sessionPoints(session: EurAresSessionInfo): Int = when (session.session) {
        "OVERLAP" -> 10
        "LONDON" -> 9
        "NEW_YORK" -> 7
        "ASIA" -> 3
        else -> 0
    }

    private fun rrPoints(netRr: Double?, cfg: Config): Int {
        val net = netRr?.takeIf { it.isFinite() } ?: return 0
        return when {
            net >= 2.0 -> 15
            net >= 1.75 -> 12
            net >= cfg.minRewardRisk -> 9
            else -> 0
        }
    }

    private fun costPoints(costToRisk: Double, cfg: Config): Int = when {
        costToRisk <= 0.10 -> 10
        costToRisk <= 0.20 -> 7
        costToRisk <= cfg.maxCostToRiskFraction -> 4
        else -> 0
    }

    private fun volatilityPoints(vol: Volatility, engine: EngineId): Int = when (engine) {
        EngineId.A -> when (vol) {
            Volatility.NORMAL -> 10
            Volatility.TRANSITION -> 8
            Volatility.LOW -> 6
            Volatility.ELEVATED -> 3
            Volatility.EXTREME -> 0
        }
        EngineId.B, EngineId.C -> when (vol) {
            Volatility.NORMAL -> 10
            Volatility.LOW -> 9
            Volatility.TRANSITION -> 6
            Volatility.ELEVATED -> 4
            Volatility.EXTREME -> 0
        }
        EngineId.D -> when (vol) {
            Volatility.TRANSITION -> 10
            Volatility.NORMAL -> 8
            Volatility.LOW -> 5
            Volatility.ELEVATED -> 3
            Volatility.EXTREME -> 0
        }
        else -> 0
    }

    private fun stopFor(direction: SignalAction, raw: Double, price: Double, atr: Double, cfg: Config): Double {
        val buffer = cfg.stopBufferAtr * atr
        val distance = (abs(price - raw) + buffer)
            .coerceAtLeast(cfg.minStopPips * EurPips.PIP)
            .coerceAtMost(cfg.maxStopPips * EurPips.PIP)
        return if (direction == SignalAction.BUY) price - distance else price + distance
    }

    private fun targetAt(direction: SignalAction, entry: Double, stop: Double, rr: Double): Double =
        StrategyMath.targetAt(direction, entry, stop, rr)

    private fun noTrade(
        id: EngineId,
        state: State = State.IDLE,
        detail: String,
        missing: List<String> = emptyList(),
        satisfied: List<String> = emptyList(),
        costPips: Double = 0.0,
        dataAvailable: Boolean = true,
    ): Candidate = Candidate(
        engine = id, state = state, direction = SignalAction.NO_TRADE,
        regimeScore = 0, structureScore = 0, entryScore = 0, confirmationScore = 0,
        volatilityScore = 0, sessionScore = 0, rrScore = 0, costScore = 0,
        structuralLevel = null, invalidation = null, entry = null, stop = null, target = null,
        rr = null, netRr = null, costPips = costPips, costToRisk = 0.0,
        setupType = "", satisfied = satisfied, missing = missing, detail = detail,
        dataAvailable = dataAvailable,
    )

    // -------------------------------------------------------------------------------------
    // Engine A — trend continuation after a pullback
    // -------------------------------------------------------------------------------------
    private fun engineA(
        bars: List<Candle>, f: Features, regime: Regime, vol: Volatility,
        cfg: Config, costPips: Double,
    ): Candidate {
        val id = EngineId.A
        if (!cfg.enableA) return noTrade(id, State.IDLE, "موتور A خاموش است", costPips = costPips)
        if (regime != Regime.BULL_TREND && regime != Regime.BEAR_TREND)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} ادامهٔ روند را مجاز نمی‌کند", costPips = costPips)
        if (!f.htfAvailable)
            return noTrade(id, State.IDLE, "قاب H1 برای تأیید جهت کافی نیست", costPips = costPips)
        val up = regime == Regime.BULL_TREND
        val direction = if (up) SignalAction.BUY else SignalAction.SELL
        if (cfg.requireHtfAlignment && f.htfBias != direction)
            return noTrade(id, State.IDLE,
                "H1 هم‌جهت نیست (${f.htfLabel})؛ ادامهٔ روند تأیید نشد", costPips = costPips)
        val price = bars.last().close
        val atr = f.atr
        val impulseLevel: Double = (if (up) f.swingLow else f.swingHigh)
            ?: return noTrade(id, State.IDLE, "سوئینگ تأییدشده برای اندازه‌گیری ایمپالس نیست", costPips = costPips)
        val otherSwing = if (up) f.swingHigh else f.swingLow
        val impulse = abs(price - impulseLevel)
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (impulse >= cfg.minImpulseAtr * atr && impulse >= cfg.minImpulsePips * EurPips.PIP)
            satisfied += "ایمپالس ${EurPips.format(impulse / EurPips.PIP)} پیپ"
        else missing += "ایمپالس ${EurPips.format(impulse / EurPips.PIP)} پیپ کمتر از " +
            "${EurPips.format(cfg.minImpulsePips)} پیپ است"
        val zone = if (up) max(impulseLevel, f.emaFast) else min(impulseLevel, f.emaFast)
        val distance = abs(price - zone)
        val inZone = distance <= cfg.pullbackZoneAtr * atr
        if (inZone) satisfied += "قیمت در ناحیهٔ پولبک (${EurPips.format(distance / EurPips.PIP)} پیپ)"
        else missing += "قیمت ${EurPips.format(distance / EurPips.PIP)} پیپ از ناحیهٔ پولبک دور است"
        val depth = if (otherSwing != null && otherSwing != impulseLevel)
            abs(otherSwing - price) / abs(otherSwing - impulseLevel) else Double.NaN
        val depthOk = depth.isFinite() && depth in cfg.minPullbackFrac..cfg.maxPullbackFrac
        if (depthOk) satisfied += "عمق پولبک ${"%.0f".format(depth * 100)}٪"
        else if (depth.isFinite()) missing += "عمق پولبک ${"%.0f".format(depth * 100)}٪ بیرون از بازهٔ مجاز"
        else missing += "عمق پولبک قابل اندازه‌گیری نیست"
        val last = bars.last()
        val span = last.high - last.low
        val body = if (span > 0.0) abs(last.close - last.open) / span else 0.0
        val directional = up == (last.close > last.open) && body >= cfg.minBodyFrac
        val wick = if (span <= 0.0) 0.0 else if (up)
            (min(last.open, last.close) - last.low) / span
        else (last.high - max(last.open, last.close)) / span
        val rejection = directional || wick >= 0.30
        if (directional) satisfied += "کندل تأیید جهت‌دار (بدنهٔ ${"%.0f".format(body * 100)}٪)"
        else if (rejection) satisfied += "سایهٔ رد شدن ${"%.0f".format(wick * 100)}٪"
        else missing += "کندل تأیید (بدنه/سایه) نیست"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight)
        val microBreak = if (up) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (microBreak) satisfied += "شکست ریزساختار"
        else missing += "شکست ریزساختار تأیید نشده"
        val distEma = abs(price - f.emaFast) / atr
        val notChasing = distEma <= cfg.maxChaseAtr * 2.0
        if (notChasing) satisfied += "فاصله از EMA ${"%.2f".format(distEma)} ATR"
        else missing += "ورود دیر است: ${"%.2f".format(distEma)} ATR از EMA"
        val momentum = if (up) f.macdHist > 0.0 && f.rsi >= 45.0
        else f.macdHist < 0.0 && f.rsi <= 55.0
        if (momentum) satisfied += "مومنتوم هم‌جهت (RSI ${"%.0f".format(f.rsi)})"
        else missing += "مومنتوم هم‌جهت نیست (RSI ${"%.0f".format(f.rsi)})"
        val ready = impulse >= cfg.minImpulseAtr * atr && impulse >= cfg.minImpulsePips * EurPips.PIP &&
            inZone && depthOk && rejection && notChasing && momentum && (microBreak || directional)
        val state = when {
            ready -> State.READY
            satisfied.size >= 5 -> State.CONFIRMATION
            inZone || microBreak -> State.FORMING
            else -> State.WATCH
        }
        val level = if (up) min(impulseLevel, zone) else max(impulseLevel, zone)
        val rawStop = if (up) level - cfg.stopBufferAtr * atr * 0.5 else level + cfg.stopBufferAtr * atr * 0.5
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val fixed = targetAt(direction, price, stop, max(cfg.minRewardRisk, cfg.targetRr))
        val obstacle = if (up) {
            listOfNotNull(f.prevDayHigh, f.roundAbove, f.rangeHigh)
                .filter { it > price + 1e-9 }.minOrNull() ?: fixed
        } else {
            listOfNotNull(f.prevDayLow, f.roundBelow, f.rangeLow)
                .filter { it < price - 1e-9 }.maxOrNull() ?: fixed
        }
        // A nearer structural obstacle wins only when it still pays; otherwise the 2R target stands.
        val target = if (up) max(fixed, min(obstacle, price + 3.0 * risk))
        else min(fixed, max(obstacle, price - 3.0 * risk))
        val rr = abs(target - price) / risk
        val net = EurAresPolicy.netRewardRisk(price, stop, target, costPips)
        val ctr = if (risk > 0.0) costPips / (risk / EurPips.PIP) else Double.POSITIVE_INFINITY
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = 15,
            structureScore = if (impulse >= cfg.minImpulseAtr * atr && depthOk) 15 else if (inZone) 9 else 4,
            entryScore = if (inZone && depthOk) 10 else if (inZone) 6 else 2,
            confirmationScore = if (microBreak && rejection) 15 else if (rejection || microBreak) 9 else 3,
            volatilityScore = volatilityPoints(vol, id),
            sessionScore = sessionPoints(f.session),
            rrScore = rrPoints(net, cfg),
            costScore = costPoints(ctr, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop, target = target,
            rr = rr, netRr = net, costPips = costPips, costToRisk = ctr,
            setupType = "TREND_PULLBACK",
            satisfied = satisfied, missing = missing,
            detail = "${regime.labelFa} · ${f.htfLabel} · RR خالص ${"%.2f".format(net)}",
        )
    }

    // -------------------------------------------------------------------------------------
    // Engine B — failed breakout / liquidity sweep
    // -------------------------------------------------------------------------------------
    private fun engineB(
        bars: List<Candle>, f: Features, regime: Regime, vol: Volatility,
        cfg: Config, costPips: Double,
    ): Candidate {
        val id = EngineId.B
        if (!cfg.enableB) return noTrade(id, State.IDLE, "موتور B خاموش است", costPips = costPips)
        if (regime == Regime.EVENT_UNSTABLE)
            return noTrade(id, State.IDLE, "در نوسان رویدادی سوییپ دنبال نمی‌شود", costPips = costPips)
        val atr = f.atr
        val price = bars.last().close
        val window = bars.takeLast(cfg.sweepWindowBars)
        val highs = confirmedSwings(bars, cfg.pivotLeft, cfg.pivotRight)
        val lows = confirmedLows(bars, cfg.pivotLeft, cfg.pivotRight)
        val priorHigh = highs.lastOrNull()?.second
        val priorLow = lows.lastOrNull()?.second
        val sessionLow = f.asianLow
        val sessionHigh = f.asianHigh

        val sweptLowLevel = listOfNotNull(priorLow, sessionLow)
            .filter { level -> window.any { it.low < level && it.low >= level - cfg.maxSweepAtr * atr } }
            .maxOrNull()
        val sweptHighLevel = listOfNotNull(priorHigh, sessionHigh)
            .filter { level -> window.any { it.high > level && it.high <= level + cfg.maxSweepAtr * atr } }
            .minOrNull()
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val direction: SignalAction
        val level: Double
        if (sweptLowLevel != null && price > sweptLowLevel) {
            direction = SignalAction.BUY
            level = sweptLowLevel
        } else if (sweptHighLevel != null && price < sweptHighLevel) {
            direction = SignalAction.SELL
            level = sweptHighLevel
        } else {
            return noTrade(id, State.WATCH, "سوییپ تأییدشده‌ای روی سطح معنادار نیست",
                missing = listOf("شکست سطح و بسته‌شدن به داخل رخ نداده"), costPips = costPips)
        }
        satisfied += "سطح ${"%.5f".format(level)} شکسته و قیمت به داخل برگشت"
        val extremeBar = window.lastOrNull {
            if (direction == SignalAction.BUY) it.low < level else it.high > level
        }
        val penetration = when {
            extremeBar == null -> 0.0
            direction == SignalAction.BUY -> (level - extremeBar.low) / atr
            else -> (extremeBar.high - level) / atr
        }
        val penetrationPips = if (extremeBar == null) 0.0 else if (direction == SignalAction.BUY)
            (level - extremeBar.low) / EurPips.PIP else (extremeBar.high - level) / EurPips.PIP
        if (penetration >= cfg.minSweepAtr && penetrationPips >= cfg.minSweepPips)
            satisfied += "نفوذ ${EurPips.format(penetrationPips)} پیپ"
        else missing += "نفوذ ${EurPips.format(penetrationPips)} پیپ کمتر از ${EurPips.format(cfg.minSweepPips)} پیپ است"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1)
        val shift = if (direction == SignalAction.BUY) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (shift) satisfied += "شیفت ریزساختار پس از سوییپ"
        else missing += "شیفت ریزساختار تأیید نشده"
        val last = bars.last()
        val span = last.high - last.low
        val closeLocation = if (span > 0.0) (last.close - last.low) / span else 0.5
        val closeOk = if (direction == SignalAction.BUY) closeLocation >= 0.55 else closeLocation <= 0.45
        if (closeOk) satisfied += "بسته‌شدن در نیمهٔ درست کندل"
        else missing += "بسته‌شدن در نیمهٔ درست کندل نیست"
        val retestOk = if (!cfg.requireRetest) true else if (direction == SignalAction.BUY)
            last.low <= level + cfg.retestToleranceAtr * atr && price > level
        else last.high >= level - cfg.retestToleranceAtr * atr && price < level
        if (cfg.requireRetest) {
            if (retestOk) satisfied += "ری‌تست سطح بازپس‌گرفته‌شده"
            else missing += "ری‌تست سطح انجام نشده"
        }
        val ready = penetration >= cfg.minSweepAtr && penetrationPips >= cfg.minSweepPips &&
            shift && closeOk && retestOk
        val state = when {
            ready -> State.READY
            satisfied.size >= 4 -> State.CONFIRMATION
            shift || closeOk -> State.FORMING
            else -> State.WATCH
        }
        val rawStop = if (direction == SignalAction.BUY)
            (extremeBar?.low ?: (level - 0.5 * atr)) - cfg.stopBufferAtr * atr
        else (extremeBar?.high ?: (level + 0.5 * atr)) + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val mid = (f.rangeHigh + f.rangeLow) / 2.0
        val opposite = if (direction == SignalAction.BUY) f.rangeHigh else f.rangeLow
        val fixed = targetAt(direction, price, stop, max(cfg.minRewardRisk, cfg.targetRr))
        val target = if (direction == SignalAction.BUY)
            max(min(mid, opposite), fixed) else min(max(mid, opposite), fixed)
        val rr = abs(target - price) / risk
        val net = EurAresPolicy.netRewardRisk(price, stop, target, costPips)
        val ctr = if (risk > 0.0) costPips / (risk / EurPips.PIP) else Double.POSITIVE_INFINITY
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = if (regime == Regime.RANGE || regime == Regime.COMPRESSION) 15 else 9,
            structureScore = if (penetration >= cfg.minSweepAtr &&
                (abs(level - f.rangeLow) < 0.5 * atr || abs(level - f.rangeHigh) < 0.5 * atr)) 15
            else if (penetration >= cfg.minSweepAtr) 10 else 4,
            entryScore = if (closeOk) 10 else 5,
            confirmationScore = if (shift && closeOk) 15 else if (shift || closeOk) 9 else 3,
            volatilityScore = volatilityPoints(vol, id),
            sessionScore = sessionPoints(f.session),
            rrScore = rrPoints(net, cfg),
            costScore = costPoints(ctr, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop, target = target,
            rr = rr, netRr = net, costPips = costPips, costToRisk = ctr,
            setupType = "LIQUIDITY_SWEEP",
            satisfied = satisfied, missing = missing,
            detail = "سوییپ ${EurPips.format(penetrationPips)} پیپ · RR خالص ${"%.2f".format(net)}",
        )
    }

    // -------------------------------------------------------------------------------------
    // Engine C — range mean reversion
    // -------------------------------------------------------------------------------------
    private fun engineC(
        bars: List<Candle>, f: Features, regime: Regime, vol: Volatility,
        cfg: Config, costPips: Double,
    ): Candidate {
        val id = EngineId.C
        if (!cfg.enableC) return noTrade(id, State.IDLE, "موتور C خاموش است", costPips = costPips)
        if (regime != Regime.RANGE)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} بازگشت به میانگین را مجاز نمی‌کند", costPips = costPips)
        val atr = f.atr
        val price = bars.last().close
        val width = f.rangeHigh - f.rangeLow
        if (width <= 0.0) return noTrade(id, State.WATCH, "عرض رنج نامعتبر است", costPips = costPips)
        if (price > f.rangeHigh + cfg.rangeInvalidationAtr * atr ||
            price < f.rangeLow - cfg.rangeInvalidationAtr * atr)
            return noTrade(id, State.INVALIDATED, "رنج با شکست واقعی باطل شد", costPips = costPips)
        val position = (price - f.rangeLow) / width
        val nearLow = position <= cfg.rangeTouchFrac
        val nearHigh = position >= 1.0 - cfg.rangeTouchFrac
        if (!nearLow && !nearHigh)
            return noTrade(id, State.WATCH,
                "قیمت در میانهٔ رنج است (${"%.0f".format(position * 100)}٪)",
                missing = listOf("قیمت به لبهٔ رنج نرسیده"), costPips = costPips)
        val direction = if (nearLow) SignalAction.BUY else SignalAction.SELL
        val satisfied = mutableListOf("قیمت در ${"%.0f".format(position * 100)}٪ رنج")
        val missing = mutableListOf<String>()
        val last = bars.last()
        val span = last.high - last.low
        val wick = if (span <= 0.0) 0.0 else if (direction == SignalAction.BUY)
            (min(last.open, last.close) - last.low) / span
        else (last.high - max(last.open, last.close)) / span
        if (wick >= cfg.minRejectionWickFrac) satisfied += "سایهٔ رد شدن ${"%.0f".format(wick * 100)}٪"
        else missing += "سایهٔ رد شدن کمتر از ${"%.0f".format(cfg.minRejectionWickFrac * 100)}٪ است"
        val rsiOk = if (direction == SignalAction.BUY) f.rsi <= 40.0 else f.rsi >= 60.0
        if (rsiOk) satisfied += "RSI ${"%.0f".format(f.rsi)} در ناحیهٔ اشباع"
        else missing += "RSI ${"%.0f".format(f.rsi)} اشباع نیست"
        val adxOk = f.adx <= cfg.rangeAdxMax + 4.0
        if (adxOk) satisfied += "ADX ${"%.1f".format(f.adx)} رنج را تأیید می‌کند"
        else missing += "ADX ${"%.1f".format(f.adx)} برای رنج زیاد است"
        val level = if (direction == SignalAction.BUY) f.rangeLow else f.rangeHigh
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val mid = (f.rangeHigh + f.rangeLow) / 2.0
        val fixed = targetAt(direction, price, stop, max(cfg.minRewardRisk, cfg.targetRr))
        val target = if (direction == SignalAction.BUY) max(mid, fixed) else min(mid, fixed)
        val rr = abs(target - price) / risk
        val net = EurAresPolicy.netRewardRisk(price, stop, target, costPips)
        val ctr = if (risk > 0.0) costPips / (risk / EurPips.PIP) else Double.POSITIVE_INFINITY
        val ready = wick >= cfg.minRejectionWickFrac && rsiOk && adxOk &&
            net.isFinite() && net >= cfg.minRewardRisk
        val state = when {
            ready -> State.READY
            satisfied.size >= 3 -> State.CONFIRMATION
            else -> State.FORMING
        }
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = 15,
            structureScore = 12,
            entryScore = if (nearLow || nearHigh) 10 else 5,
            confirmationScore = if (wick >= cfg.minRejectionWickFrac && rsiOk) 15
            else if (wick >= cfg.minRejectionWickFrac) 9 else 3,
            volatilityScore = volatilityPoints(vol, id),
            sessionScore = sessionPoints(f.session),
            rrScore = rrPoints(net, cfg),
            costScore = costPoints(ctr, cfg),
            structuralLevel = level, invalidation = stop, entry = price, stop = stop, target = target,
            rr = rr, netRr = net, costPips = costPips, costToRisk = ctr,
            setupType = "RANGE_REVERSION",
            satisfied = satisfied, missing = missing,
            detail = "موقعیت ${"%.0f".format(position * 100)}٪ رنج · RR خالص ${"%.2f".format(net)}",
        )
    }

    // -------------------------------------------------------------------------------------
    // Engine D — session range breakout (EUR-specific)
    // -------------------------------------------------------------------------------------
    private fun engineD(
        bars: List<Candle>, f: Features, regime: Regime, vol: Volatility,
        cfg: Config, costPips: Double,
    ): Candidate {
        val id = EngineId.D
        if (!cfg.enableD) return noTrade(id, State.IDLE, "موتور D خاموش است", costPips = costPips)
        if (regime == Regime.EVENT_UNSTABLE)
            return noTrade(id, State.IDLE, "در نوسان رویدادی شکست سشن دنبال نمی‌شود", costPips = costPips)
        val atr = f.atr
        val price = bars.last().close
        val session = f.session.session
        // Pick the range that is actually complete at decision time.
        val useAsian = session != "ASIA" && f.asianBars >= cfg.sessionRangeMinBars &&
            f.asianHigh != null && f.asianLow != null
        val useOpening = f.openingRangeReady && f.openingRangeHigh != null && f.openingRangeLow != null
        val rangeHigh: Double
        val rangeLow: Double
        val rangeName: String
        when {
            useOpening -> {
                rangeHigh = f.openingRangeHigh
                rangeLow = f.openingRangeLow
                rangeName = "رنج ساعت اول لندن"
            }
            useAsian -> {
                rangeHigh = f.asianHigh
                rangeLow = f.asianLow
                rangeName = "رنج آسیا"
            }
            else -> return noTrade(id, State.WATCH,
                "رنج سشن کامل نیست (آسیا ${f.asianBars} کندل · رنج بازگشایی ${if (f.openingRangeReady) "آماده" else "ناقص"})",
                missing = listOf("رنج سشن کامل نشده"), costPips = costPips)
        }
        val width = rangeHigh - rangeLow
        if (width <= 0.0) return noTrade(id, State.WATCH, "عرض رنج سشن نامعتبر است", costPips = costPips)
        val widthAtr = width / atr
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr)
            satisfied += "$rangeName با عرض ${"%.2f".format(widthAtr)} ATR"
        else missing += "$rangeName عرض ${"%.2f".format(widthAtr)} ATR دارد (بازهٔ مجاز ${cfg.minRangeWidthAtr}–${cfg.maxRangeWidthAtr})"
        val upBreak = price > rangeHigh + cfg.minBreakoutAtr * atr
        val downBreak = price < rangeLow - cfg.minBreakoutAtr * atr
        val direction = when {
            upBreak && !downBreak -> SignalAction.BUY
            downBreak && !upBreak -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        if (direction == SignalAction.NO_TRADE)
            missing += "بسته‌شدن بیرون مرز رنج با فاصلهٔ ${cfg.minBreakoutAtr} ATR رخ نداده"
        else satisfied += "بسته‌شدن بیرون مرز رنج (${if (upBreak) "بالا" else "پایین"})"
        val expansionOk = f.rangeExpansion >= cfg.minExpansionForBreakout
        if (expansionOk) satisfied += "گسترش دامنه ${"%.2f".format(f.rangeExpansion)}×"
        else missing += "گسترش دامنه ${"%.2f".format(f.rangeExpansion)}× کمتر از ${cfg.minExpansionForBreakout} است"
        val last = bars.last()
        val span = last.high - last.low
        val closeFrac = if (span > 0.0) (last.close - last.low) / span else 0.5
        val follow = when (direction) {
            SignalAction.BUY -> closeFrac >= cfg.followThroughFrac
            SignalAction.SELL -> closeFrac <= 1.0 - cfg.followThroughFrac
            else -> false
        }
        if (follow) satisfied += "بسته‌شدن در جهت شکست (${"%.0f".format(closeFrac * 100)}٪ دامنه)"
        else if (direction != SignalAction.NO_TRADE) missing += "بسته‌شدن در جهت شکست تأیید نشد"
        val lateDistance = when (direction) {
            SignalAction.BUY -> (price - rangeHigh) / atr
            SignalAction.SELL -> (rangeLow - price) / atr
            else -> 0.0
        }
        val notLate = lateDistance <= cfg.minBreakoutAtr + cfg.maxBreakoutChaseAtr
        if (direction != SignalAction.NO_TRADE) {
            if (notLate) satisfied += "فاصله از مرز ${"%.2f".format(lateDistance)} ATR (دیر نیست)"
            else missing += "حرکت بیشترِ سشن مصرف شده: ${"%.2f".format(lateDistance)} ATR از مرز"
        }
        val level = if (direction == SignalAction.SELL) rangeLow else rangeHigh
        val rawStop = if (direction == SignalAction.SELL) rangeHigh else rangeLow
        val stop = if (direction == SignalAction.NO_TRADE) null
        else stopFor(direction, rawStop, price, atr, cfg)
        val risk = if (stop != null) abs(price - stop) else 0.0
        // Room before the next obstacle: previous day's extreme, the 50-pip grid, or the range width.
        val obstacle = when (direction) {
            SignalAction.BUY -> listOfNotNull(f.prevDayHigh, f.roundAbove).filter { it > price + 1e-9 }.minOrNull()
            SignalAction.SELL -> listOfNotNull(f.prevDayLow, f.roundBelow).filter { it < price - 1e-9 }.maxOrNull()
            else -> null
        }
        val measured = if (direction == SignalAction.BUY) price + width else price - width
        val capped = if (direction == SignalAction.BUY) price + 3.0 * risk else price - 3.0 * risk
        val obstacleTarget = obstacle ?: measured
        val target = when (direction) {
            SignalAction.BUY -> min(max(obstacleTarget, price + cfg.minRewardRisk * risk), max(measured, capped))
            SignalAction.SELL -> max(min(obstacleTarget, price - cfg.minRewardRisk * risk), min(measured, capped))
            else -> null
        }
        val roomR = if (risk > 0.0 && obstacle != null) abs(obstacle - price) / risk else Double.NaN
        val roomOk = !roomR.isFinite() || roomR >= cfg.minRoomR
        if (roomR.isFinite()) {
            if (roomOk) satisfied += "فضای مانده تا مانع ${"%.2f".format(roomR)}R"
            else missing += "فضای مانده تا مانع ${"%.2f".format(roomR)}R کمتر از ${cfg.minRoomR}R است"
        }
        val retestOk = if (!cfg.requireSessionRetest || direction == SignalAction.NO_TRADE) true
        else if (direction == SignalAction.BUY) last.low <= level + 0.25 * atr && price > level
        else last.high >= level - 0.25 * atr && price < level
        if (cfg.requireSessionRetest && direction != SignalAction.NO_TRADE) {
            if (retestOk) satisfied += "ری‌تست مرز شکسته‌شده"
            else missing += "ری‌تست مرز انجام نشده"
        }
        val rr = if (target != null && risk > 0.0) abs(target - price) / risk else 0.0
        val net = if (target != null) EurAresPolicy.netRewardRisk(price, stop!!, target, costPips) else Double.NaN
        val ctr = if (risk > 0.0) costPips / (risk / EurPips.PIP) else Double.POSITIVE_INFINITY
        val ready = direction != SignalAction.NO_TRADE && widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr &&
            expansionOk && follow && notLate && roomOk && retestOk &&
            net.isFinite() && net >= cfg.minRewardRisk
        val state = when {
            ready -> State.READY
            direction != SignalAction.NO_TRADE && satisfied.size >= 4 -> State.CONFIRMATION
            direction != SignalAction.NO_TRADE -> State.FORMING
            else -> State.WATCH
        }
        return Candidate(
            engine = id, state = state,
            direction = if (ready) direction else SignalAction.NO_TRADE,
            regimeScore = if (vol == Volatility.TRANSITION || vol == Volatility.NORMAL) 15 else 9,
            structureScore = if (widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr) 15 else 6,
            entryScore = if (notLate) 10 else 4,
            confirmationScore = if (expansionOk && follow) 15 else if (expansionOk || follow) 8 else 2,
            volatilityScore = volatilityPoints(vol, id),
            sessionScore = sessionPoints(f.session),
            rrScore = rrPoints(net, cfg),
            costScore = costPoints(ctr, cfg),
            structuralLevel = if (direction == SignalAction.NO_TRADE) null else level,
            invalidation = stop,
            entry = if (direction == SignalAction.NO_TRADE) null else price,
            stop = stop,
            target = target,
            rr = rr, netRr = net, costPips = costPips, costToRisk = ctr,
            setupType = "SESSION_BREAKOUT",
            satisfied = satisfied, missing = missing,
            detail = "$rangeName · عرض ${"%.2f".format(widthAtr)} ATR · RR خالص ${"%.2f".format(net)}",
        )
    }

    // -------------------------------------------------------------------------------------
    // Engine E (news) and module X (macro): both honest about missing inputs.
    // -------------------------------------------------------------------------------------
    private fun engineE(costPips: Double): Candidate = noTrade(
        EngineId.E, State.UNAVAILABLE,
        "تقویم اقتصادی زمان‌دار قابل اتکا در اپ نیست؛ موتور خبر غیرفعال است و رویدادی جعل نمی‌شود",
        missing = listOf("منبع خبری با زمان انتشار و تأخیر مشخص موجود نیست"),
        costPips = costPips, dataAvailable = false,
    )

    private fun engineX(macro: EurMacroContext, cfg: Config): Candidate {
        if (!cfg.macroEnabled) return noTrade(
            EngineId.X, State.UNAVAILABLE,
            "ماژول کلان/کراس‌مارکت در تنظیمات خاموش است (پیش‌فرض)", dataAvailable = false,
        )
        if (!macro.available) return noTrade(
            EngineId.X, State.UNAVAILABLE, macro.noteFa, dataAvailable = false,
        )
        val direction = macro.bias
        return Candidate(
            engine = EngineId.X, state = State.WATCH, direction = SignalAction.NO_TRADE,
            regimeScore = 0, structureScore = 0, entryScore = 0,
            confirmationScore = if (direction != SignalAction.NO_TRADE) 15 else 0,
            volatilityScore = 0, sessionScore = 0, rrScore = 0, costScore = 0,
            structuralLevel = null, invalidation = null, entry = null, stop = null, target = null,
            rr = null, netRr = null, costPips = 0.0, costToRisk = 0.0,
            setupType = "MACRO_CONTEXT",
            satisfied = listOfNotNull(
                macro.dollarCorrelation?.let { "همبستگی دلار ${"%.2f".format(it)}" },
                macro.relativeMomentumPct?.let { "مومنتوم نسبی ${"%.3f".format(it)}٪" },
                macro.yieldSpreadBps?.let { "اختلاف بازده ${"%.0f".format(it)} نقطهٔ پایه" },
            ),
            missing = emptyList(),
            detail = macro.noteFa,
        )
    }

    // -------------------------------------------------------------------------------------
    // Report / arbitration
    // -------------------------------------------------------------------------------------
    private fun report(c: Candidate, cfg: Config): EurAresEngineReport {
        val stopPips = c.stopPips
        return EurAresEngineReport(
            engine = c.engine.id,
            labelFa = c.engine.labelFa,
            state = c.state.name,
            direction = c.direction,
            scoreOutOf100 = c.score,
            regimeScore = c.regimeScore,
            structureScore = c.structureScore,
            entryScore = c.entryScore,
            confirmationScore = c.confirmationScore,
            volatilityScore = c.volatilityScore,
            sessionScore = c.sessionScore,
            rrScore = c.rrScore,
            costScore = c.costScore,
            structuralLevel = c.structuralLevel,
            invalidationLevel = c.invalidation,
            entry = c.entry,
            stopLoss = c.stop,
            takeProfit = c.target,
            stopPips = stopPips,
            rewardToRisk = c.rr,
            netRewardRisk = c.netRr,
            costPips = c.costPips,
            costToRiskFraction = c.costToRisk,
            setupType = c.setupType,
            satisfied = c.satisfied,
            missing = c.missing,
            detail = c.detail,
            dataAvailable = c.dataAvailable,
            ageBars = 0,
            maxAgeBars = cfg.maxSetupAgeBars,
        )
    }

    private fun confluenceItems(
        regime: Regime,
        vol: Volatility,
        best: Candidate?,
        actionable: Boolean,
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
                item("رژیم و نوسان · EUR-ARES", if (regime != Regime.EVENT_UNSTABLE) 12 else 0,
                    "رژیم ${regime.labelFa} · نوسان ${vol.labelFa}"),
                item("ساختار و سطح · EUR-ARES", 0, "بدون سطح فعال"),
                item("تأیید و موتور · EUR-ARES", 0, "بدون موتور آماده"),
                item("هزینه، پیپ و اجرا · EUR-ARES", 0, "بدون معامله"),
            )
        }
        val s1 = if (actionable && regime != Regime.EVENT_UNSTABLE) 25
        else if (regime != Regime.EVENT_UNSTABLE) 12 else 0
        val s2 = if (actionable && best.structuralLevel != null) 25
        else if (best.structuralLevel != null) 12 else 0
        val s3 = if (actionable) 25 else if (best.state != State.IDLE) 12 else 0
        val s4 = if (actionable && (best.netRr ?: 0.0) >= EurAresPolicy.MIN_NET_REWARD_RISK &&
            best.costToRisk <= EurAresPolicy.MAX_COST_TO_RISK_FRACTION) 25
        else if ((best.netRr ?: 0.0) >= EurAresPolicy.MIN_NET_REWARD_RISK) 12 else 0
        return listOf(
            item("رژیم و نوسان · EUR-ARES", s1,
                "رژیم ${regime.labelFa} · نوسان ${vol.labelFa} · ATR ${EurPips.format(best.costPips)}p"),
            item("ساختار و سطح · EUR-ARES", s2,
                "${best.engine.shortFa} · سطح ${best.structuralLevel?.let { "%.5f".format(it) } ?: "—"}"),
            item("تأیید و موتور · EUR-ARES", s3,
                "وضعیت ${best.state.name} · ${best.engine.labelFa} · امتیاز ${best.score}/100"),
            item("هزینه، پیپ و اجرا · EUR-ARES", s4,
                "هزینه ${EurPips.format(best.costPips)} پیپ · " +
                    "هزینه÷ریسک ${"%.0f".format(best.costToRisk * 100)}٪ · " +
                    "RR خالص ${best.netRr?.let { "%.2f".format(it) } ?: "—"}"),
        )
    }

    /**
     * Evaluate EUR/USD. The returned Signal always carries the full [EurAresRecord], including the
     * NO_TRADE case, so the trade tab can show what each engine is waiting for.
     */
    fun evaluate(
        input: List<Candle>,
        interval: Interval = Interval.M5,
        symbol: String = SYMBOL,
        threshold: Double = 70.0,
        @Suppress("UNUSED_PARAMETER") mode: CategoryStrategy = CategoryStrategy.HYBRID,
        external: Map<Interval, List<Candle>> = emptyMap(),
        macro: EurMacroContext = EurMacroContext(),
        config: Config = Config(),
        now: Long = System.currentTimeMillis(),
    ): Signal {
        val closed = input.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        val last = closed.lastOrNull()
        val barTime = last?.time ?: 0L

        fun blocked(reason: String): Signal {
            val record = EurAresRecord(
                regime = Regime.EVENT_UNSTABLE.name,
                regimeLabelFa = Regime.EVENT_UNSTABLE.labelFa,
                volatilityState = Volatility.NORMAL.name,
                session = "OFF", sessionLabelFa = EurAresPolicy.sessionLabel("OFF"),
                engines = emptyList(),
                noTradeReasons = listOf(reason),
                barTime = barTime,
            )
            return Signal(
                action = SignalAction.NO_TRADE, confidence = 0.0,
                blockers = listOf(reason), reasons = listOf("EUR-ARES · بدون دادهٔ کافی"),
                interval = interval, barTime = barTime, eurAres = record,
            )
        }

        if (V1Universe.normalize(symbol) != SYMBOL)
            return blocked("EUR-ARES فقط برای EUR/USD است؛ نماد $symbol رد شد")
        if (interval != Interval.M5)
            return blocked("ورود EUR-ARES فقط روی کندل بستهٔ ۵ دقیقه (M5) مجاز است")
        if (closed.size < config.minBars)
            return blocked("EUR-ARES به ${config.minBars} کندل بستهٔ M5 نیاز دارد (فعلی ${closed.size})")
        if (closed.zipWithNext().any { (a, b) -> b.time - a.time != interval.millis })
            return blocked("کندل‌های M5 پیوسته نیستند؛ شکاف داده بررسی شود")
        if (config.enforceFreshness &&
            now - (last!!.time + interval.millis) !in 0L..(interval.millis + 90_000L)
        ) return blocked("آخرین کندل بستهٔ M5 تازه نیست")

        val h1 = external[Interval.H1].orEmpty().filter { it.closed && it.time + Interval.H1.millis <= now }
        val h4 = external[Interval.H4].orEmpty().filter { it.closed && it.time + Interval.H4.millis <= now }
        val d1 = external[Interval.D1].orEmpty().filter { it.closed && it.time + Interval.D1.millis <= now }
        val f = features(closed, h1, h4, d1, macro, config)
        if (!f.atr.isFinite() || f.atr <= 0.0) return blocked("ATR معتبر نیست")
        val vol = volatilityOf(f, config)

        // Hysteresis needs one earlier classification; it is built from a shorter, still-closed slice.
        val previousRaw = if (closed.size > config.minBars) {
            val prevBars = closed.dropLast(1)
            val prevH1 = h1.filter { it.time + Interval.H1.millis <= prevBars.last().time + interval.millis }
            val prevH4 = h4.filter { it.time + Interval.H4.millis <= prevBars.last().time + interval.millis }
            val prevD1 = d1.filter { it.time + Interval.D1.millis <= prevBars.last().time + interval.millis }
            rawRegime(features(prevBars, prevH1, prevH4, prevD1, macro, config), config)
        } else null
        val (regime, hysteresisNote) = classifyRegime(rawRegime(f, config), previousRaw, config)

        val price = closed.last().close
        val costPips = EurAresPolicy.roundTripCostPips(
            price, config.spreadPipsOverride, slippagePips = config.slippagePips)
        val candidates = buildList {
            add(engineA(closed, f, regime, vol, config, costPips))
            add(engineB(closed, f, regime, vol, config, costPips))
            add(engineC(closed, f, regime, vol, config, costPips))
            add(engineD(closed, f, regime, vol, config, costPips))
            add(engineE(costPips))
            add(engineX(f.macro, config))
        }
        val reports = candidates.map { report(it, config) }
        val ready = candidates
            .filter { it.state == State.READY && it.direction != SignalAction.NO_TRADE }
            .sortedByDescending { it.score }
        val conflicts = mutableListOf<String>()
        if (ready.size >= 2) {
            val top = ready[0]
            val opposite = ready.firstOrNull { it.direction != top.direction }
            if (opposite != null && abs(top.score - opposite.score) < config.conflictMargin100) {
                conflicts += "تعارض ${top.engine.shortFa} و ${opposite.engine.shortFa} " +
                    "(اختلاف امتیاز ${abs(top.score - opposite.score)} < ${config.conflictMargin100})"
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
                candidates.filter {
                    it.state != State.UNAVAILABLE && it.state != State.IDLE && it.state != State.WATCH
                }.joinToString("، ") { "${it.engine.shortFa}=${it.state.name}" }
        }
        val required = max(config.scoreThreshold100, threshold.roundToInt().coerceIn(50, 100))
        if (best != null && best.score < required)
            noTradeReasons += "امتیاز ${best.score}/100 کمتر از حد نصاب $required است"
        if (best != null && (best.netRr ?: 0.0) < config.minRewardRisk)
            noTradeReasons += "R:R خالص ${"%.2f".format(best.netRr ?: 0.0)} کمتر از ${config.minRewardRisk} است"
        if (best != null && best.costToRisk > config.maxCostToRiskFraction)
            noTradeReasons += "هزینهٔ رفت‌وبرگشت ${EurPips.format(best.costPips)} پیپ معادل " +
                "${"%.0f".format(best.costToRisk * 100)}٪ حد ضرر است " +
                "(سقف ${"%.0f".format(config.maxCostToRiskFraction * 100)}٪)"
        if (config.dailyRealizedLossPct >= config.maxDailyLossPct)
            noTradeReasons += "سقف ضرر روزانه (${config.maxDailyLossPct}٪) پر شده است"
        if (regime == Regime.EVENT_UNSTABLE)
            noTradeReasons += "نوسان رویدادی/نامشخص: ورود تازه مجاز نیست"
        if (f.session.rolloverWindow)
            noTradeReasons += "پنجرهٔ رول‌اور ۱۷:۰۰ نیویورک: اسپرد و سواپ در حال تسویه‌اند"
        if (config.sessionGateEnabled && f.sessionName in config.blockedSessions)
            noTradeReasons += "سشن ${f.session.sessionLabelFa} برای ورود مسدود است"
        if (config.requireMacroConfirmation) {
            if (!f.macro.available)
                noTradeReasons += "تأیید کلان لازم است ولی سری کلان در دسترس نیست"
            else if (best != null && f.macro.bias != SignalAction.NO_TRADE && f.macro.bias != best.direction)
                noTradeReasons += "زمینهٔ کلان جهت مخالف را نشان می‌دهد و فیلتر تأیید روشن است"
        }

        val actionableCandidate = best?.takeIf { noTradeReasons.isEmpty() }
        val stopPips = actionableCandidate?.stopPips ?: 0.0
        val (lots, units, riskUsd) = if (actionableCandidate?.entry != null && stopPips > 0.0)
            EurAresPolicy.positionLots(
                actionableCandidate.entry, stopPips, config.accountEquityUsd,
                config.riskPerTradePct, actionableCandidate.costPips,
                config.pipValueUsdPerLot, config.lotStep, config.minUnits, config.maxLeverage,
            ) else Triple(0.0, 0.0, 0.0)
        if (actionableCandidate != null && lots <= 0.0)
            noTradeReasons += "حجم محاسبه‌شده کمتر از حداقل سفارش (${config.minUnits.toInt()} واحد) است"

        val finalAction = if (actionableCandidate != null && lots > 0.0) actionableCandidate.direction
        else SignalAction.NO_TRADE
        val setupKey = if (finalAction != SignalAction.NO_TRADE && actionableCandidate != null)
            listOf(STRATEGY, SYMBOL, actionableCandidate.engine.id, actionableCandidate.setupType,
                finalAction.name,
                "%.5f".format(java.util.Locale.US, actionableCandidate.structuralLevel ?: 0.0),
                barTime.toString()).joinToString("|")
        else ""
        val confluence = confluenceItems(regime, vol, best, finalAction != SignalAction.NO_TRADE)
        val confidence = confluence.sumOf { it.scorePercent ?: 0 }.toDouble()
        val record = EurAresRecord(
            regime = regime.name,
            regimeLabelFa = regime.labelFa,
            volatilityState = vol.name,
            atr = f.atr,
            atrPips = f.atrPips,
            atrRatio = f.atrRatio,
            atrPercentile = f.atrPercentile,
            rangeExpansion = f.rangeExpansion,
            session = f.session.session,
            sessionLabelFa = f.session.sessionLabelFa,
            sessionInfo = f.session.copy(sessionVolRatio = f.sessionVolRatio),
            htfAvailable = f.htfAvailable,
            htfBias = when (f.htfBias) {
                SignalAction.BUY -> "BUY"
                SignalAction.SELL -> "SELL"
                else -> "NONE"
            },
            d1Available = f.d1Available,
            d1Bias = when (f.d1Bias) {
                SignalAction.BUY -> "BUY"
                SignalAction.SELL -> "SELL"
                else -> "NONE"
            },
            macro = f.macro,
            engines = reports,
            chosenEngine = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.engine?.id else null,
            action = finalAction,
            scoreOutOf100 = if (finalAction != SignalAction.NO_TRADE) (actionableCandidate?.score ?: 0)
            else (best?.score ?: 0),
            structuralLevel = actionableCandidate?.structuralLevel,
            invalidationLevel = actionableCandidate?.invalidation,
            entry = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.entry else null,
            stopLoss = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.stop else null,
            takeProfit = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.target else null,
            stopPips = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.stopPips else null,
            rewardToRisk = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.rr else null,
            netRewardRisk = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.netRr else best?.netRr,
            costPips = actionableCandidate?.costPips ?: costPips,
            costToRiskFraction = actionableCandidate?.costToRisk ?: 0.0,
            positionLots = lots,
            positionUnits = units,
            pipValueUsd = config.pipValueUsdPerLot,
            riskUsd = riskUsd,
            maxRiskPct = config.riskPerTradePct.coerceAtMost(0.25),
            conflicts = conflicts,
            noTradeReasons = noTradeReasons + listOfNotNull(hysteresisNote.takeIf { it.isNotBlank() }),
            dataNotes = buildList {
                add("قرارداد یورو/دلار از VenueSpecs: تیک ${VenueSpecs.of(SYMBOL).tickSize} · " +
                    "کمیسیون ${VenueSpecs.of(SYMBOL).commissionPerUnit} دلار/واحد در هر سمت · " +
                    "اسپرد اسمی ${VenueSpecs.of(SYMBOL).spreadPrice}")
                add("همهٔ فاصله‌ها و هزینه‌ها پیپ‌محور است؛ ۱ پیپ = ${EurPips.PIP} و ۱ پیپت = ${EurPips.PIPETTE}")
                add("هزینهٔ نمایش‌داده‌شده با اسپرد اسمی محاسبه شده؛ گیت ورود از اسپرد زندهٔ همان ناشر استفاده می‌کند")
                add("سواپ/بهرهٔ شبانه در اپ منبع ندارد و صفر گزارش می‌شود؛ عدد ساختگی ساخته نمی‌شود")
                if (!f.macro.available) add("ماژول کلان/کراس‌مارکت خاموش است؛ سیستم بدون آن هم کار می‌کند")
                if (!f.d1Available) add("قاب D1 کامل نیست؛ جهت کلان از H1 گرفته می‌شود")
                if (f.session.rolloverWindow) add("پنجرهٔ رول‌اور فعال است؛ ورود مجاز نیست")
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
                    "EUR-ARES · ${regime.labelFa} · نوسان ${vol.labelFa} · بدون ورود: " +
                        (noTradeReasons.firstOrNull() ?: "شرط‌ها کامل نیست")
                else "EUR-ARES · ${actionableCandidate!!.engine.labelFa} · " +
                    "امتیاز ${actionableCandidate.score}/100 · " +
                    "RR خالص ${"%.2f".format(java.util.Locale.US, actionableCandidate.netRr ?: 0.0)} · " +
                    "هزینه ${EurPips.format(actionableCandidate.costPips)} پیپ · " +
                    "حجم ${"%.2f".format(java.util.Locale.US, lots)} لات"
            ),
            blockers = if (finalAction == SignalAction.NO_TRADE) noTradeReasons + conflicts else emptyList(),
            confluence = confluence,
            interval = interval,
            barTime = barTime,
            eurAres = record,
        )
    }

    /** Exposed for tests and diagnostics; never a shortcut around [evaluate]. */
    internal fun analyze(
        bars: List<Candle>,
        h1: List<Candle> = emptyList(),
        config: Config = Config(),
    ): Pair<Regime, Volatility> {
        val f = features(bars, h1, emptyList(), emptyList(), EurMacroContext(), config)
        return rawRegime(f, config) to volatilityOf(f, config)
    }
}
