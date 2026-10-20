package com.aurum.edge.engine

import com.aurum.edge.core.BtcAresEngineReport
import com.aurum.edge.core.BtcAresRecord
import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BTC-ARES — Bitcoin Adaptive Regime, Execution & Signal Intelligence System.
 *
 * Scope of THIS implementation (stated rather than implied):
 *  - Symbol: BTC/USDT only. Any other symbol is rejected before a single calculation.
 *  - Timeframes: closed M5 bars are the only entry series. M15 and H1 are independent context
 *    frames; when they are missing the engines that need them are disabled, never synthesised
 *    from M5 and never given a fake confirmation.
 *  - Data tier: Tier 1 only (OHLCV + confirmed structure + volatility + costs). The app has no
 *    funding, open-interest, liquidation, order-book or cross-venue depth feed, so Engine F is
 *    reported as UNAVAILABLE with its reason instead of inventing values.
 *  - No look-ahead: a pivot at bar p exists only when p + rightBars <= last closed bar; every
 *    feature is computed from bars whose close time is <= the evaluated bar's close.
 *  - No repainting: nothing is recomputed after the fact; the record is tied to one bar time.
 *  - Paper only: no exchange orders. Android's own installer/venue rules still gate real money.
 *
 * The five price engines are separate functions with their own conditions, their own invalidation
 * level and their own state; they never read each other's state. Arbitration happens once, at the
 * end, and disagreement is reported as a conflict instead of an averaged signal.
 */
object BtcAresEngine {
    const val SYMBOL = "BTC/USDT"
    const val STRATEGY = "BTC-ARES"

    enum class Regime(val labelFa: String) {
        TREND_UP("روند صعودی"),
        TREND_DOWN("روند نزولی"),
        RANGE("رنج"),
        COMPRESSION("فشردگی نوسان"),
        TRANSITION("گذار / شکست تازه"),
        EXTREME_VOL("نوسان شدید / آبشاری"),
        UNCERTAIN("نامشخص / متناقض"),
    }

    enum class Volatility(val labelFa: String) {
        LOW("کم"),
        NORMAL("عادی"),
        ELEVATED("بالا"),
        EXTREME("بحرانی"),
    }

    enum class State {
        IDLE, WATCH, FORMING, CONFIRMATION, READY, ACTIVE, INVALIDATED, EXPIRED, UNAVAILABLE
    }

    enum class EngineId(val id: String, val labelFa: String, val shortFa: String) {
        A("ENGINE_A_TREND", "موتور A · ادامهٔ روند", "A · روند"),
        B("ENGINE_B_SWEEP", "موتور B · شکست ناموفق / سوییپ نقدینگی", "B · سوییپ"),
        C("ENGINE_C_COMPRESSION", "موتور C · شکست فشردگی نوسان", "C · فشردگی"),
        D("ENGINE_D_RANGE", "موتور D · رنج و بازگشت به میانگین", "D · رنج"),
        E("ENGINE_E_EXHAUSTION", "موتور E · خستگی مومنتوم و برگشت", "E · خستگی"),
        F("ENGINE_F_DERIVATIVES", "موتور F · موقعیت‌یابی مشتقه", "F · مشتقه"),
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
        val emaFast: Int = 50,
        val emaSlow: Int = 200,
        val rsiPeriod: Int = 14,
        // Volatility / regime
        val volMedianLookback: Int = 60,
        val atrPercentileLookback: Int = 240,
        val lowAtrRatio: Double = 0.60,
        val elevatedAtrRatio: Double = 1.40,
        val extremeAtrRatio: Double = 2.20,
        val trendAdxMin: Double = 20.0,
        val rangeAdxMax: Double = 20.0,
        val compressionMaxRangeAtr: Double = 0.80,
        val minRangeWidthAtr: Double = 1.5,
        val maxRangeWidthAtr: Double = 6.0,
        // Engine A
        val minImpulseAtr: Double = 1.2,
        val minPullbackFrac: Double = 0.25,
        val maxPullbackFrac: Double = 0.90,
        val pullbackZoneAtr: Double = 0.60,
        val maxChaseAtr: Double = 1.25,
        // Engine B
        val minSweepAtr: Double = 0.12,
        val maxSweepAtr: Double = 1.30,
        val sweepWindowBars: Int = 4,
        val sweepStructureWindow: Int = 6,
        // Engine C
        val compressionWindowBars: Int = 24,
        val minBreakoutAtr: Double = 0.15,
        val minBreakoutRelVolume: Double = 1.20,
        // Engine D
        val rangeTouchFrac: Double = 0.22,
        val rangeMidRejectFrac: Double = 0.35,
        // Engine E
        val exhaustionStretchAtr: Double = 2.50,
        val exhaustionLookback: Int = 20,
        val minRejectionWickFrac: Double = 0.40,
        // Risk / execution
        val stopBufferAtr: Double = 0.25,
        val minStopAtr: Double = 0.35,
        val maxStopAtr: Double = 4.00,
        val minRewardRisk: Double = 1.50,
        val scoreThreshold: Int = 70,
        val conflictMargin: Int = 15,
        val riskPerTradePct: Double = 0.25,
        val maxDailyLossPct: Double = 1.00,
        val dailyRealizedLossPct: Double = 0.0,
        val accountEquityUsd: Double = 10_000.0,
        /** Binance Spot VIP0 maker/taker benchmark, same reference the app already documents. */
        val commissionBpsPerSide: Double = 10.0,
        val spreadBps: Double = 1.0,
        val slippageBps: Double = 2.0,
        val minNotionalUsd: Double = 5.0,
        val qtyStep: Double = 0.00001,
        // Setup lifetime
        val maxSetupAgeBars: Int = 12,
        // Enable switches (each engine is independent)
        val enableA: Boolean = true,
        val enableB: Boolean = true,
        val enableC: Boolean = true,
        val enableD: Boolean = true,
        val enableE: Boolean = true,
        val minBars: Int = HistoryPolicy.LIVE_MIN_CANDLES,
        val enforceFreshness: Boolean = true,
    )

    internal data class Features(
        val atr: Double,
        val atrRatio: Double,
        val atrPercentile: Double,
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
        val htfAvailable: Boolean,
    )

    /** One engine's verdict before arbitration. */
    private data class Candidate(
        val engine: EngineId,
        val state: State,
        val direction: SignalAction,
        val score: Int,
        val structuralLevel: Double?,
        val invalidation: Double?,
        val entry: Double?,
        val stop: Double?,
        val target: Double?,
        val rr: Double?,
        val satisfied: List<String>,
        val missing: List<String>,
        val detail: String,
        val dataAvailable: Boolean = true,
    )

    private fun noTrade(
        engine: EngineId,
        state: State,
        detail: String,
        satisfied: List<String> = emptyList(),
        missing: List<String> = emptyList(),
        dataAvailable: Boolean = true,
    ) = Candidate(engine, state, SignalAction.NO_TRADE, 0, null, null, null, null, null, null,
        satisfied, missing, detail, dataAvailable)

    private fun pct(value: Double, digits: Int = 1): String = "%.${digits}f".format(java.util.Locale.US, value)

    /** Confirmed (non-repainting) pivots: visible only after `right` later bars have closed. */
    fun confirmedSwings(bars: List<Candle>, left: Int, right: Int, upToIndex: Int = bars.lastIndex):
        List<Pair<Int, Double>> {
        val out = mutableListOf<Pair<Int, Double>>()
        if (bars.isEmpty() || left < 1 || right < 1) return out
        val limit = min(upToIndex, bars.lastIndex)
        for (p in left..(limit - right)) {
            var isHigh = true
            for (k in (p - left)..(p + right)) {
                if (k == p) continue
                val other = bars[k].high
                if (other > bars[p].high || (k < p && other >= bars[p].high)) { isHigh = false; break }
            }
            if (isHigh) out += p to bars[p].high
        }
        return out
    }

    fun confirmedLows(bars: List<Candle>, left: Int, right: Int, upToIndex: Int = bars.lastIndex):
        List<Pair<Int, Double>> {
        val out = mutableListOf<Pair<Int, Double>>()
        if (bars.isEmpty() || left < 1 || right < 1) return out
        val limit = min(upToIndex, bars.lastIndex)
        for (p in left..(limit - right)) {
            var isLow = true
            for (k in (p - left)..(p + right)) {
                if (k == p) continue
                val other = bars[k].low
                if (other < bars[p].low || (k < p && other <= bars[p].low)) { isLow = false; break }
            }
            if (isLow) out += p to bars[p].low
        }
        return out
    }

    /**
     * Percentile rank of the latest ATR among its own history, with ties counted as half.
     * Counting ties as "below" would make a flat series look like an extreme (rank 1.0), and
     * calling a quiet market extreme is exactly the mistake that blocks valid setups.
     */
    private fun atrPercentile(series: List<Double?>, lookback: Int): Double {
        val values = series.takeLast(lookback).filterNotNull().filter { it > 0.0 && it.isFinite() }
        if (values.size < 20) return 0.5
        val current = values.last()
        val below = values.count { it < current }
        val equal = values.count { it == current }
        return (below + 0.5 * equal) / values.size
    }

    private fun features(bars: List<Candle>, m15: List<Candle>, h1: List<Candle>, cfg: Config): Features {
        val closes = bars.map { it.close }
        val atrSeries = Indicators.atr(bars, cfg.atrPeriod)
        val atr = atrSeries.lastOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val recent = atrSeries.takeLast(cfg.volMedianLookback).filterNotNull()
            .filter { it > 0.0 && it.isFinite() }.sorted()
        val median = recent.getOrNull(recent.size / 2)?.takeIf { it > 0.0 } ?: atr
        val adx = Indicators.adx(bars, cfg.adxPeriod).lastOrNull() ?: 0.0
        val adxM15 = m15.takeIf { it.size > cfg.adxPeriod * 2 }
            ?.let { Indicators.adx(it, cfg.adxPeriod).lastOrNull() }
        val emaFast = Indicators.ema(closes, cfg.emaFast).lastOrNull()
            ?: bars.last().close
        val emaSlow = Indicators.ema(closes, cfg.emaSlow).lastOrNull()
            ?: bars.last().close
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
        val width = (rangeHigh - rangeLow)
        val compressionRatio = if (atr > 0.0) width / (atr * sqrt(cfg.compressionWindowBars.toDouble())) else 1.0

        val htfAvailable = m15.size >= HistoryPolicy.minSize(Interval.M15) &&
            h1.size >= HistoryPolicy.minSize(Interval.H1)
        val htfBias = if (!htfAvailable) null else {
            val m15Close = m15.last().close
            val m15Ema = Indicators.ema(m15.map { it.close }, cfg.emaFast).lastOrNull() ?: m15Close
            val h1Close = h1.last().close
            val h1Ema = Indicators.ema(h1.map { it.close }, cfg.emaFast).lastOrNull() ?: h1Close
            when {
                m15Close > m15Ema && h1Close > h1Ema -> SignalAction.BUY
                m15Close < m15Ema && h1Close < h1Ema -> SignalAction.SELL
                else -> SignalAction.NO_TRADE
            }
        }

        return Features(
            atr = atr,
            atrRatio = atr / median,
            atrPercentile = atrPercentile(atrSeries, cfg.atrPercentileLookback),
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
            htfAvailable = htfAvailable,
        )
    }

    /** Volatility state uses percentiles, never one fixed ATR number for every market. */
    internal fun volatilityOf(f: Features, cfg: Config = Config()): Volatility = when {
        f.atrRatio >= cfg.extremeAtrRatio || f.atrPercentile >= 0.97 -> Volatility.EXTREME
        f.atrRatio >= cfg.elevatedAtrRatio || f.atrPercentile >= 0.85 -> Volatility.ELEVATED
        f.atrRatio <= cfg.lowAtrRatio && f.atrPercentile <= 0.25 -> Volatility.LOW
        else -> Volatility.NORMAL
    }

    /** Explicit state machine with entry/exit conditions, tested in BtcAresEngineTest. */
    internal fun classifyRegime(f: Features, cfg: Config = Config()): Regime {
        val vol = volatilityOf(f, cfg)
        val bullishStructure = (f.hh && f.hl) || f.bosUp
        val bearishStructure = (f.lh && f.ll) || f.bosDown
        if (vol == Volatility.EXTREME) return Regime.EXTREME_VOL
        if (bullishStructure && bearishStructure) return Regime.UNCERTAIN
        if (f.compressionRatio <= cfg.compressionMaxRangeAtr && f.adx < cfg.trendAdxMin &&
            vol != Volatility.ELEVATED
        ) return Regime.COMPRESSION
        if ((f.bosUp || f.bosDown) && vol == Volatility.ELEVATED) return Regime.TRANSITION
        val widthAtr = (f.rangeHigh - f.rangeLow) / f.atr
        if (bullishStructure && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.SELL)
            return Regime.TREND_UP
        if (bearishStructure && f.adx >= cfg.trendAdxMin && f.htfBias != SignalAction.BUY)
            return Regime.TREND_DOWN
        if (widthAtr in cfg.minRangeWidthAtr..cfg.maxRangeWidthAtr && f.adx <= cfg.rangeAdxMax)
            return Regime.RANGE
        return Regime.UNCERTAIN
    }

    private fun stopFor(direction: SignalAction, raw: Double, price: Double, atr: Double, cfg: Config): Double {
        val distance = max(abs(price - raw) + cfg.stopBufferAtr * atr, cfg.minStopAtr * atr)
            .coerceAtMost(cfg.maxStopAtr * atr)
        return if (direction == SignalAction.BUY) price - distance else price + distance
    }

    private fun targetAt(direction: SignalAction, entry: Double, stop: Double, rr: Double): Double =
        if (direction == SignalAction.BUY) entry + rr * (entry - stop) else entry - rr * (entry - stop)

    /** Engine A — trend continuation after a measured pullback and a renewed micro break. */
    private fun engineA(bars: List<Candle>, f: Features, regime: Regime, cfg: Config): Candidate {
        val id = EngineId.A
        if (!cfg.enableA) return noTrade(id, State.IDLE, "موتور A خاموش است")
        if (regime != Regime.TREND_UP && regime != Regime.TREND_DOWN)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} اجازهٔ ادامهٔ روند نمی‌دهد")
        if (!f.htfAvailable)
            return noTrade(id, State.IDLE, "قاب M15/H1 برای تأیید روند کامل نیست", dataAvailable = true)
        val up = regime == Regime.TREND_UP
        val direction = if (up) SignalAction.BUY else SignalAction.SELL
        val last = bars.last()
        val price = last.close
        val atr = f.atr
        val impulseLevel = (if (up) f.swingLow else f.swingHigh)
            ?: return noTrade(id, State.IDLE, "سوئینگ تأییدشده برای اندازه‌گیری ایمپالس نیست")
        val impulse = abs(price - impulseLevel)
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (impulse >= cfg.minImpulseAtr * atr) satisfied += "ایمپالس ≥ ${pct(cfg.minImpulseAtr, 2)} ATR"
        else missing += "ایمپالس هنوز ${pct(impulse / atr, 2)} ATR است"
        val zone = when {
            up -> max(f.swingLow ?: price, f.emaFast)
            else -> min(f.swingHigh ?: price, f.emaFast)
        }
        val distanceToZone = abs(price - zone)
        val inZone = distanceToZone <= cfg.pullbackZoneAtr * atr
        if (inZone) satisfied += "قیمت در ناحیهٔ پولبک (${pct(distanceToZone / atr, 2)} ATR)"
        else missing += "قیمت ${pct(distanceToZone / atr, 2)} ATR از ناحیهٔ پولبک دور است"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight).takeLast(2)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight).takeLast(2)
        val microBreak = if (up) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        val microLevel = if (up) microHighs.lastOrNull()?.second else microLows.lastOrNull()?.second
        if (microBreak) satisfied += "شکست ریزساختار هم‌جهت"
        else missing += "شکست ریزساختار تأیید نشده"
        val distFromEma = abs(price - f.emaFast) / atr
        val notChasing = distFromEma <= cfg.maxChaseAtr * 2.0
        if (notChasing) satisfied += "ورود دیرهنگام نیست (${pct(distFromEma, 2)} ATR از EMA${cfg.emaFast})"
        else missing += "قیمت ${pct(distFromEma, 2)} ATR از EMA${cfg.emaFast} دور شده؛ دیر است"
        val momentumOk = if (up) f.macdHist > 0.0 && f.rsi >= 50.0 else f.macdHist < 0.0 && f.rsi <= 50.0
        if (momentumOk) satisfied += "مومنتوم هم‌جهت"
        else missing += "مومنتوم هم‌جهت نیست"

        val ready = satisfied.size >= 5 && microBreak && inZone && notChasing
        val state = when {
            ready -> State.READY
            satisfied.size >= 3 -> State.CONFIRMATION
            inZone || microBreak -> State.FORMING
            else -> State.WATCH
        }
        val level = microLevel ?: zone
        val rawStop = if (up) min(impulseLevel, level) - cfg.stopBufferAtr * atr * 0.5
        else max(impulseLevel, level) + cfg.stopBufferAtr * atr * 0.5
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val target = targetAt(direction, price, stop, max(cfg.minRewardRisk, 2.0))
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val score = scoreOf(20, if (satisfied.size >= 5) 20 else satisfied.size * 4,
            if (inZone) 15 else 5, if (microBreak) 15 else 5, volatilityOf(f, cfg),
            f.relVolume, rr, cfg)
        return Candidate(id, state, if (ready) direction else SignalAction.NO_TRADE, score,
            level, stop, price, stop, target, rr, satisfied, missing,
            "${regime.labelFa} · پولبک ${pct(distanceToZone / atr, 2)} ATR · شکست ریزساختار ${if (microBreak) "دارد" else "ندارد"}")
    }

    /** Engine B — failed breakout / liquidity sweep with a close back inside and a shift. */
    private fun engineB(bars: List<Candle>, f: Features, regime: Regime, cfg: Config): Candidate {
        val id = EngineId.B
        if (!cfg.enableB) return noTrade(id, State.IDLE, "موتور B خاموش است")
        if (regime == Regime.EXTREME_VOL)
            return noTrade(id, State.IDLE, "در نوسان بحرانی ورود سوییپ مجاز نیست")
        val atr = f.atr
        val price = bars.last().close
        val window = bars.takeLast(cfg.sweepWindowBars)
        val lows = confirmedLows(bars, cfg.pivotLeft, cfg.pivotRight)
        val highs = confirmedSwings(bars, cfg.pivotLeft, cfg.pivotRight)
        val priorLow = lows.lastOrNull()?.second
        val priorHigh = highs.lastOrNull()?.second
        val sweptLow = priorLow != null && window.any { it.low < priorLow && it.low >= priorLow - cfg.maxSweepAtr * atr }
        val sweptHigh = priorHigh != null && window.any { it.high > priorHigh && it.high <= priorHigh + cfg.maxSweepAtr * atr }
        val reclaimedLow = priorLow != null && price > priorLow
        val reclaimedHigh = priorHigh != null && price < priorHigh
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var direction = SignalAction.NO_TRADE
        var level: Double? = null
        when {
            sweptLow && reclaimedLow -> {
                direction = SignalAction.BUY
                level = priorLow
                satisfied += "فرورفت زیر سوئینگ تأییدشده و بست بالای آن"
            }
            sweptHigh && reclaimedHigh -> {
                direction = SignalAction.SELL
                level = priorHigh
                satisfied += "فرارفت بالای سوئینگ تأییدشده و بست زیر آن"
            }
            else -> {
                missing += "سوییپ تأییدشدهٔ نقدینگی دیده نشد"
                return noTrade(id, State.WATCH, "بدون سوییپ تأییدشده", missing = missing)
            }
        }
        val sweepBar = window.lastOrNull { bar ->
            if (direction == SignalAction.BUY) bar.low < (level ?: 0.0)
            else bar.high > (level ?: 0.0)
        }
        val penetration = sweepBar?.let {
            if (direction == SignalAction.BUY) (level!! - it.low) / atr else (it.high - level!!) / atr
        } ?: 0.0
        if (penetration >= cfg.minSweepAtr) satisfied += "عمق نفوذ ${pct(penetration, 2)} ATR"
        else missing += "نفوذ ${pct(penetration, 2)} ATR کمتر از ${pct(cfg.minSweepAtr, 2)} ATR است"
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val shift = if (direction == SignalAction.BUY) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (shift) satisfied += "تغییر ساختار تأیید شد"
        else missing += "تغییر ساختار ریز تأیید نشده"
        val closeLocation = if (bars.last().high - bars.last().low > 0.0)
            (bars.last().close - bars.last().low) / (bars.last().high - bars.last().low) else 0.5
        val closeOk = if (direction == SignalAction.BUY) closeLocation >= 0.5 else closeLocation <= 0.5
        if (closeOk) satisfied += "بست در نیمهٔ درست کندل"
        else missing += "بست در نیمهٔ درست کندل نیست"
        val ready = satisfied.size >= 3 && shift
        val state = when {
            ready -> State.READY
            satisfied.size >= 2 -> State.CONFIRMATION
            else -> State.FORMING
        }
        val rawStop = if (direction == SignalAction.BUY) sweepBar?.low?.minus(cfg.stopBufferAtr * atr) ?: (level!! - atr * 0.5)
        else sweepBar?.high?.plus(cfg.stopBufferAtr * atr) ?: (level!! + atr * 0.5)
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val opposite = if (direction == SignalAction.BUY)
            (f.rangeHigh.takeIf { it > price } ?: (price + 2.0 * risk)) else
            (f.rangeLow.takeIf { it < price } ?: (price - 2.0 * risk))
        val target = if (direction == SignalAction.BUY) max(opposite, price + cfg.minRewardRisk * risk)
        else min(opposite, price - cfg.minRewardRisk * risk)
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val score = scoreOf(if (regime == Regime.TRANSITION) 18 else 14, 18,
            if (penetration >= cfg.minSweepAtr) 15 else 8, if (shift) 15 else 5,
            volatilityOf(f, cfg), f.relVolume, rr, cfg)
        return Candidate(id, state, if (ready) direction else SignalAction.NO_TRADE, score,
            level, stop, price, stop, target, rr, satisfied, missing,
            "سوییپ ${pct(penetration, 2)} ATR · تغییر ساختار ${if (shift) "دارد" else "ندارد"}")
    }

    /** Engine C — compression breakout with volume confirmation and a late-entry guard. */
    private fun engineC(bars: List<Candle>, f: Features, regime: Regime, cfg: Config): Candidate {
        val id = EngineId.C
        if (!cfg.enableC) return noTrade(id, State.IDLE, "موتور C خاموش است")
        if (regime == Regime.EXTREME_VOL)
            return noTrade(id, State.IDLE, "در نوسان بحرانی شکست فشردگی معنا ندارد")
        val atr = f.atr
        val price = bars.last().close
        val compressed = f.compressionRatio <= cfg.compressionMaxRangeAtr ||
            regime == Regime.COMPRESSION || regime == Regime.TRANSITION
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (f.compressionRatio <= cfg.compressionMaxRangeAtr)
            satisfied += "فشردگی ${pct(f.compressionRatio, 2)} (آستانه ${pct(cfg.compressionMaxRangeAtr, 2)})"
        else missing += "نسبت دامنه ${pct(f.compressionRatio, 2)} فشرده نیست"
        val upBreak = price > f.rangeHigh + cfg.minBreakoutAtr * atr
        val downBreak = price < f.rangeLow - cfg.minBreakoutAtr * atr
        val direction = when {
            upBreak -> SignalAction.BUY
            downBreak -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        if (direction == SignalAction.NO_TRADE) missing += "بست بیرون از مرز رنج تأیید نشد"
        else satisfied += "بست ${pct(cfg.minBreakoutAtr, 2)} ATR بیرون مرز"
        val volumeOk = f.relVolume >= cfg.minBreakoutRelVolume
        if (volumeOk) satisfied += "حجم نسبی ${pct(f.relVolume, 2)}×"
        else missing += "حجم نسبی ${pct(f.relVolume, 2)}× کمتر از ${pct(cfg.minBreakoutRelVolume, 2)}×"
        val level = if (direction == SignalAction.BUY) f.rangeHigh else f.rangeLow
        val lateDistance = if (direction == SignalAction.NO_TRADE) 0.0 else
            (if (direction == SignalAction.BUY) price - f.rangeHigh else f.rangeLow - price) / atr
        val notLate = lateDistance <= cfg.minBreakoutAtr + cfg.maxChaseAtr
        if (direction != SignalAction.NO_TRADE) {
            if (notLate) satisfied += "ورود دیرهنگام نیست (${pct(lateDistance, 2)} ATR)"
            else missing += "حرکت ${pct(lateDistance, 2)} ATR از مرز گذشته؛ دیر است"
        }
        val ready = direction != SignalAction.NO_TRADE && volumeOk && notLate &&
            f.compressionRatio <= cfg.compressionMaxRangeAtr
        val state = when {
            ready -> State.READY
            direction != SignalAction.NO_TRADE -> State.CONFIRMATION
            compressed -> State.FORMING
            else -> State.WATCH
        }
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = if (direction == SignalAction.NO_TRADE) null else stopFor(direction, rawStop, price, atr, cfg)
        val risk = stop?.let { abs(price - it) } ?: 0.0
        val target = if (stop != null && direction != SignalAction.NO_TRADE)
            targetAt(direction, price, stop, max(cfg.minRewardRisk, 2.0)) else null
        val rr = if (risk > 0.0 && target != null) abs(target - price) / risk else 0.0
        val score = scoreOf(if (compressed) 18 else 8, if (compressed) 16 else 6,
            if (direction != SignalAction.NO_TRADE) 15 else 4,
            if (volumeOk) 15 else 4, volatilityOf(f, cfg), f.relVolume, rr, cfg)
        return Candidate(id, state, if (ready) direction else SignalAction.NO_TRADE, score,
            level.takeIf { direction != SignalAction.NO_TRADE }, stop, price.takeIf { direction != SignalAction.NO_TRADE },
            stop, target, rr, satisfied, missing,
            "فشردگی ${pct(f.compressionRatio, 2)} · شکست ${if (direction == SignalAction.NO_TRADE) "ندارد" else direction.name}")
    }

    /** Engine D — range mean reversion at a confirmed boundary, disabled after a real breakout. */
    private fun engineD(bars: List<Candle>, f: Features, regime: Regime, cfg: Config): Candidate {
        val id = EngineId.D
        if (!cfg.enableD) return noTrade(id, State.IDLE, "موتور D خاموش است")
        if (regime != Regime.RANGE)
            return noTrade(id, State.IDLE, "رژیم ${regime.labelFa} برای بازگشت به میانگین مجاز نیست")
        val atr = f.atr
        val price = bars.last().close
        val width = (f.rangeHigh - f.rangeLow).takeIf { it > 0.0 }
            ?: return noTrade(id, State.WATCH, "عرض رنج نامعتبر است")
        val positionInRange = (price - f.rangeLow) / width
        val nearLow = positionInRange <= cfg.rangeTouchFrac
        val nearHigh = positionInRange >= 1.0 - cfg.rangeTouchFrac
        val direction = when {
            nearLow -> SignalAction.BUY
            nearHigh -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (direction == SignalAction.NO_TRADE) {
            missing += "قیمت در میانهٔ رنج است (${pct(positionInRange * 100, 0)}٪)؛ ورود وسط رنج ممنوع"
            return noTrade(id, State.WATCH, "میانهٔ رنج", missing = missing)
        }
        satisfied += "قیمت در ${pct(positionInRange * 100, 0)}٪ رنج"
        val last = bars.last()
        val rangeSize = last.high - last.low
        val rejection = when (direction) {
            SignalAction.BUY -> rangeSize > 0.0 && (min(last.open, last.close) - last.low) / rangeSize >= cfg.minRejectionWickFrac
            else -> rangeSize > 0.0 && (last.high - max(last.open, last.close)) / rangeSize >= cfg.minRejectionWickFrac
        }
        if (rejection) satisfied += "ویک رد سطح"
        else missing += "ویک رد سطح تأیید نشد"
        val rsiOk = if (direction == SignalAction.BUY) f.rsi <= 40.0 else f.rsi >= 60.0
        if (rsiOk) satisfied += "RSI در ناحیهٔ ${pct(f.rsi, 0)}"
        else missing += "RSI (${pct(f.rsi, 0)}) در ناحیهٔ اشباع نیست"
        val adxOk = f.adx <= cfg.rangeAdxMax + 2.0
        if (adxOk) satisfied += "ADX ${pct(f.adx, 1)} رونددار نیست"
        else missing += "ADX ${pct(f.adx, 1)} بالا است"
        val ready = satisfied.size >= 4
        val state = when {
            ready -> State.READY
            satisfied.size >= 2 -> State.CONFIRMATION
            else -> State.FORMING
        }
        val level = if (direction == SignalAction.BUY) f.rangeLow else f.rangeHigh
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val mid = (f.rangeHigh + f.rangeLow) / 2.0
        val target = if (direction == SignalAction.BUY) max(mid, price + cfg.minRewardRisk * risk)
        else min(mid, price - cfg.minRewardRisk * risk)
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val score = scoreOf(18, 16, if ((nearLow && direction == SignalAction.BUY) || (nearHigh && direction == SignalAction.SELL)) 15 else 8,
            if (rejection) 15 else 5, volatilityOf(f, cfg), f.relVolume, rr, cfg)
        return Candidate(id, state, if (ready) direction else SignalAction.NO_TRADE, score,
            level, stop, price, stop, target, rr, satisfied, missing,
            "موقعیت در رنج ${pct(positionInRange * 100, 0)}٪ · عرض ${pct(width / atr, 2)} ATR")
    }

    /** Engine E — exhaustion after an extended move, with divergence and a structure shift. */
    private fun engineE(bars: List<Candle>, f: Features, regime: Regime, cfg: Config): Candidate {
        val id = EngineId.E
        if (!cfg.enableE) return noTrade(id, State.IDLE, "موتور E خاموش است")
        if (regime == Regime.EXTREME_VOL)
            return noTrade(id, State.IDLE, "در نوسان بحرانی برگشت خستگی قابل اتکا نیست")
        val atr = f.atr
        val price = bars.last().close
        val stretch = (price - f.emaFast) / atr
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (abs(stretch) >= cfg.exhaustionStretchAtr)
            satisfied += "فاصله از EMA${cfg.emaFast} ${pct(abs(stretch), 2)} ATR"
        else missing += "کشیدگی ${pct(abs(stretch), 2)} ATR کمتر از ${pct(cfg.exhaustionStretchAtr, 2)} ATR است"
        val look = bars.takeLast(cfg.exhaustionLookback + 1).dropLast(1)
        val prevHigh = look.maxOfOrNull { it.high } ?: price
        val prevLow = look.minOfOrNull { it.low } ?: price
        val rsiSeries = Indicators.rsi(bars, cfg.rsiPeriod).filterNotNull()
        val rsiPrev = rsiSeries.dropLast(1).takeLast(1).lastOrNull() ?: f.rsi
        val bearishDivergence = price >= prevHigh && f.rsi < rsiPrev
        val bullishDivergence = price <= prevLow && f.rsi > rsiPrev
        val direction = when {
            stretch >= cfg.exhaustionStretchAtr && bearishDivergence -> SignalAction.SELL
            stretch <= -cfg.exhaustionStretchAtr && bullishDivergence -> SignalAction.BUY
            else -> SignalAction.NO_TRADE
        }
        if (direction == SignalAction.NO_TRADE) {
            missing += "واگرایی RSI هم‌جهت با کشیدگی دیده نشد"
            return noTrade(id, if (abs(stretch) >= cfg.exhaustionStretchAtr) State.FORMING else State.WATCH,
                "بدون واگرایی تأییدشده", missing = missing, satisfied = satisfied)
        }
        satisfied += "واگرایی RSI ${if (direction == SignalAction.BUY) "صعودی" else "نزولی"}"
        val last = bars.last()
        val rangeSize = last.high - last.low
        val wick = when (direction) {
            SignalAction.BUY -> if (rangeSize > 0.0) (min(last.open, last.close) - last.low) / rangeSize else 0.0
            else -> if (rangeSize > 0.0) (last.high - max(last.open, last.close)) / rangeSize else 0.0
        }
        if (wick >= cfg.minRejectionWickFrac) satisfied += "ویک رد ${pct(wick * 100, 0)}٪"
        else missing += "ویک رد ${pct(wick * 100, 0)}٪ کم است"
        val microLows = confirmedLows(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val microHighs = confirmedSwings(bars, cfg.microLeft, cfg.microRight, bars.lastIndex - 1).takeLast(1)
        val shift = if (direction == SignalAction.BUY) microHighs.isNotEmpty() && price > microHighs.last().second
        else microLows.isNotEmpty() && price < microLows.last().second
        if (shift) satisfied += "تغییر ساختار ریز"
        else missing += "تغییر ساختار ریز تأیید نشده"
        val ready = wick >= cfg.minRejectionWickFrac && shift
        val state = when {
            ready -> State.READY
            satisfied.size >= 3 -> State.CONFIRMATION
            else -> State.FORMING
        }
        val level = if (direction == SignalAction.BUY) prevLow else prevHigh
        val rawStop = if (direction == SignalAction.BUY) level - cfg.stopBufferAtr * atr
        else level + cfg.stopBufferAtr * atr
        val stop = stopFor(direction, rawStop, price, atr, cfg)
        val risk = abs(price - stop)
        val target = if (direction == SignalAction.BUY)
            max(f.emaFast, price + cfg.minRewardRisk * risk)
        else min(f.emaFast, price - cfg.minRewardRisk * risk)
        val rr = if (risk > 0.0) abs(target - price) / risk else 0.0
        val score = scoreOf(14, 16, 12, if (wick >= cfg.minRejectionWickFrac) 15 else 5,
            volatilityOf(f, cfg), f.relVolume, rr, cfg)
        return Candidate(id, state, if (ready) direction else SignalAction.NO_TRADE, score,
            level, stop, price, stop, target, rr, satisfied, missing,
            "کشیدگی ${pct(stretch, 2)} ATR · ویک ${pct(wick * 100, 0)}٪")
    }

    /**
     * Deterministic 0..100 score. Availability is explicit: engines whose required inputs are
     * missing are never scored as if the input were a neutral zero.
     */
    private fun scoreOf(
        regimeFit: Int,
        structure: Int,
        location: Int,
        confirmation: Int,
        volatility: Volatility,
        relVolume: Double,
        rr: Double,
        cfg: Config,
    ): Int {
        val volPoints = when (volatility) {
            Volatility.NORMAL -> 10
            Volatility.LOW -> 8
            Volatility.ELEVATED -> 5
            Volatility.EXTREME -> 0
        }
        val volumePoints = when {
            relVolume >= 1.3 -> 10
            relVolume >= 1.0 -> 7
            relVolume > 0.0 -> 3
            else -> 0
        }
        val totalCostFraction = (2 * cfg.commissionBpsPerSide + cfg.spreadBps + cfg.slippageBps) / 10_000.0
        val executionPoints = when {
            rr < cfg.minRewardRisk -> 0
            rr >= 2.0 + totalCostFraction * 100 -> 10
            rr >= cfg.minRewardRisk + 0.5 -> 7
            else -> 5
        }
        return (regimeFit.coerceIn(0, 20) + structure.coerceIn(0, 20) + location.coerceIn(0, 15) +
            confirmation.coerceIn(0, 15) + volPoints + volumePoints + executionPoints).coerceIn(0, 100)
    }

    private fun report(c: Candidate, cfg: Config): BtcAresEngineReport = BtcAresEngineReport(
        engine = c.engine.id,
        labelFa = c.engine.labelFa,
        state = c.state.name,
        direction = c.direction,
        score = c.score,
        structuralLevel = c.structuralLevel,
        invalidationLevel = c.invalidation,
        entry = c.entry,
        stopLoss = c.stop,
        takeProfit = c.target,
        rewardToRisk = c.rr,
        satisfied = c.satisfied,
        missing = c.missing,
        detail = c.detail,
        dataAvailable = c.dataAvailable,
        ageBars = 0,
        maxAgeBars = cfg.maxSetupAgeBars,
    )

    /** Position size from equity, permitted risk and the entry-to-stop distance plus costs. */
    fun positionSize(entry: Double, stop: Double, cfg: Config): Pair<Double, Double> {
        if (!entry.isFinite() || !stop.isFinite() || entry <= 0.0 || stop <= 0.0 || entry == stop) return 0.0 to 0.0
        val costPerUnit = entry * (2 * cfg.commissionBpsPerSide + cfg.spreadBps + cfg.slippageBps) / 10_000.0
        val riskPerUnit = abs(entry - stop) + costPerUnit
        if (riskPerUnit <= 0.0) return 0.0 to 0.0
        val riskUsd = cfg.accountEquityUsd * cfg.riskPerTradePct / 100.0
        val raw = riskUsd / riskPerUnit
        val step = cfg.qtyStep
        val qty = floor(raw / step) * step
        val notional = qty * entry
        return if (qty <= 0.0 || notional < cfg.minNotionalUsd) 0.0 to riskUsd else qty to riskUsd
    }

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
                item("رژیم و نوسان · BTC-ARES", if (regime != Regime.EXTREME_VOL) 12 else 0, "رژیم ${regime.labelFa}"),
                item("ساختار و سطح · BTC-ARES", 0, "بدون سطح فعال"),
                item("تأیید و موتور · BTC-ARES", 0, "بدون موتور آماده"),
                item("ریسک، هزینه و اجرا · BTC-ARES", 0, "بدون معامله"),
            )
        }
        val s1 = if (actionable && regime != Regime.EXTREME_VOL) 25 else if (regime != Regime.EXTREME_VOL) 12 else 0
        val s2 = if (actionable && best.structuralLevel != null) 25 else if (best.structuralLevel != null) 12 else 0
        val s3 = if (actionable) 25 else if (best.state != State.IDLE) 12 else 0
        val s4 = if (actionable && (best.rr ?: 0.0) >= cfg.minRewardRisk) 25
        else if ((best.rr ?: 0.0) >= cfg.minRewardRisk) 12 else 0
        return listOf(
            item("رژیم و نوسان · BTC-ARES", s1, "رژیم ${regime.labelFa}"),
            item("ساختار و سطح · BTC-ARES", s2,
                "${best.engine.shortFa} · سطح ${best.structuralLevel?.let { "%.2f".format(java.util.Locale.US, it) } ?: "—"}"),
            item("تأیید و موتور · BTC-ARES", s3, "وضعیت ${best.state.name} · ${best.engine.labelFa}"),
            item("ریسک، هزینه و اجرا · BTC-ARES", s4,
                "RR=${best.rr?.let { pct(it, 2) } ?: "—"} · هزینه≈${pct((2 * cfg.commissionBpsPerSide + cfg.spreadBps + cfg.slippageBps), 1)}bps"),
        )
    }

    /**
     * Evaluate BTC/USDT. Returns a Signal whose `btcAres` record carries every engine's own state.
     * The function never builds a signal from a different symbol and never invents missing inputs.
     */
    fun evaluate(
        input: List<Candle>,
        interval: Interval = Interval.M5,
        symbol: String = SYMBOL,
        threshold: Double = 70.0,
        @Suppress("UNUSED_PARAMETER") mode: CategoryStrategy = CategoryStrategy.HYBRID,
        external: Map<Interval, List<Candle>> = emptyMap(),
        config: Config = Config(),
        now: Long = System.currentTimeMillis(),
    ): Signal {
        val blocks = mutableListOf<String>()
        val closed = input.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        val last = closed.lastOrNull()
        val barTime = last?.time ?: 0L

        fun blocked(reason: String): Signal {
            val record = BtcAresRecord(
                regime = Regime.UNCERTAIN.name,
                regimeLabelFa = Regime.UNCERTAIN.labelFa,
                volatilityState = Volatility.NORMAL.name,
                engines = emptyList(),
                noTradeReasons = listOf(reason),
                barTime = barTime,
            )
            return Signal(
                action = SignalAction.NO_TRADE, confidence = 0.0,
                blockers = listOf(reason), reasons = listOf("BTC-ARES · بدون داده کافی"),
                interval = interval, barTime = barTime, btcAres = record,
            )
        }

        if (com.aurum.edge.core.V1Universe.normalize(symbol) != SYMBOL)
            return blocked("BTC-ARES فقط برای BTC/USDT است؛ نماد ${symbol} رد شد")
        if (interval != Interval.M5)
            return blocked("ورود BTC-ARES فقط روی کندل بستهٔ ۵ دقیقه (M5) مجاز است")
        if (closed.size < config.minBars)
            return blocked("BTC-ARES به ${config.minBars} کندل بستهٔ M5 نیاز دارد (فعلی ${closed.size})")
        if (closed.zipWithNext().any { (a, b) -> b.time - a.time != interval.millis })
            return blocked("کندل‌های M5 پیوسته نیستند؛ شکاف داده بررسی شود")
        if (config.enforceFreshness &&
            now - (last!!.time + interval.millis) !in 0L..(interval.millis + 90_000L)
        ) return blocked("آخرین کندل بستهٔ M5 تازه نیست")

        val m15 = external[Interval.M15].orEmpty().filter { it.closed && it.time + Interval.M15.millis <= now }
        val h1 = external[Interval.H1].orEmpty().filter { it.closed && it.time + Interval.H1.millis <= now }
        val f = features(closed, m15, h1, config)
        if (f.atr <= 0.0 || !f.atr.isFinite()) return blocked("ATR معتبر نیست")
        val vol = volatilityOf(f, config)
        val regime = classifyRegime(f, config)

        val candidates = buildList {
            add(engineA(closed, f, regime, config))
            add(engineB(closed, f, regime, config))
            add(engineC(closed, f, regime, config))
            add(engineD(closed, f, regime, config))
            add(engineE(closed, f, regime, config))
            add(noTrade(EngineId.F, State.UNAVAILABLE,
                "فید سرمایه‌گذاری/موقعیت مشتقه، نرخ بهرهٔ دائمی و لیکوییدیشن در این اپ وجود ندارد؛ " +
                    "مقدار ساختگی ساخته نمی‌شود", dataAvailable = false))
        }
        val reports = candidates.map { report(it, config) }
        val ready = candidates.filter { it.state == State.READY && it.direction != SignalAction.NO_TRADE }
            .sortedByDescending { it.score }
        val conflicts = mutableListOf<String>()
        if (ready.size >= 2) {
            val top = ready[0]
            val opposite = ready.firstOrNull { it.direction != top.direction }
            if (opposite != null && abs(top.score - opposite.score) < config.conflictMargin) {
                conflicts += "تعارض ${top.engine.shortFa} و ${opposite.engine.shortFa} " +
                    "(اختلاف امتیاز ${abs(top.score - opposite.score)} < ${config.conflictMargin})"
            }
        }
        val noTradeReasons = mutableListOf<String>()
        val best = when {
            conflicts.isNotEmpty() -> null
            ready.isEmpty() -> null
            else -> ready.first()
        }
        if (best == null && conflicts.isEmpty()) {
            noTradeReasons += "هیچ موتوری به وضعیت READY نرسید؛ " +
                candidates.filter { it.state != State.UNAVAILABLE && it.state != State.IDLE }
                    .joinToString("، ") { "${it.engine.shortFa}=${it.state.name}" }
        }
        val requiredScore = max(config.scoreThreshold, (threshold / 1.0).toInt().coerceIn(60, 90))
        if (best != null && best.score < requiredScore)
            noTradeReasons += "امتیاز ${best.score} کمتر از حد نصاب $requiredScore است"
        if (best != null && (best.rr ?: 0.0) < config.minRewardRisk)
            noTradeReasons += "R:R ${pct(best.rr ?: 0.0, 2)} کمتر از ${pct(config.minRewardRisk, 2)} است"
        if (config.dailyRealizedLossPct >= config.maxDailyLossPct)
            noTradeReasons += "سقف ضرر روزانه (${pct(config.maxDailyLossPct, 2)}٪) پر شده است"
        if (vol == Volatility.EXTREME && regime == Regime.EXTREME_VOL)
            noTradeReasons += "نوسان بحرانی: ورود تازه مجاز نیست"

        val actionableCandidate = best?.takeIf { noTradeReasons.isEmpty() }
        val (qty, riskUsd) = if (actionableCandidate?.entry != null && actionableCandidate.stop != null)
            positionSize(actionableCandidate.entry, actionableCandidate.stop, config) else 0.0 to 0.0
        if (actionableCandidate != null && qty <= 0.0)
            noTradeReasons += "حجم محاسبه‌شده کمتر از حداقل سفارش (${pct(config.minNotionalUsd, 0)} USDT) است"

        val finalAction = if (actionableCandidate != null && qty > 0.0) actionableCandidate.direction
        else SignalAction.NO_TRADE
        val setupKey = if (finalAction != SignalAction.NO_TRADE && actionableCandidate != null)
            listOf(STRATEGY, SYMBOL, actionableCandidate.engine.id, finalAction.name,
                "%.2f".format(java.util.Locale.US, actionableCandidate.structuralLevel ?: 0.0),
                barTime.toString()).joinToString("|") else ""
        val confluence = confluenceItems(regime, best, finalAction != SignalAction.NO_TRADE, config)
        val confidence = confluence.sumOf { it.scorePercent ?: 0 }.toDouble()
        val record = BtcAresRecord(
            regime = regime.name,
            regimeLabelFa = regime.labelFa,
            volatilityState = vol.name,
            atrRatio = f.atrRatio,
            atrPercentile = f.atrPercentile,
            atr = f.atr,
            higherTimeframeAvailable = f.htfAvailable,
            engines = reports,
            chosenEngine = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.engine?.id else null,
            action = finalAction,
            score = if (finalAction != SignalAction.NO_TRADE) (actionableCandidate?.score ?: 0) else (best?.score ?: 0),
            structuralLevel = actionableCandidate?.structuralLevel,
            invalidationLevel = actionableCandidate?.invalidation,
            entry = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.entry else null,
            stopLoss = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.stop else null,
            takeProfit = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.target else null,
            rewardToRisk = if (finalAction != SignalAction.NO_TRADE) actionableCandidate?.rr else null,
            positionQty = qty,
            riskUsd = riskUsd,
            conflicts = conflicts,
            noTradeReasons = noTradeReasons,
            dataNotes = listOf(
                "دادهٔ سطح ۱ (OHLCV + ساختار + نوسان + هزینه)؛ مشتقه/اوردر‌بوک در دسترس نیست",
                "نشست معاملاتی فیچر است، نه دروازه؛ BTC ۲۴/۷ معامله می‌شود",
            ),
            setupKey = setupKey,
            barTime = barTime,
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
                    "BTC-ARES · ${regime.labelFa} · نوسان ${vol.labelFa} · بدون ورود: " +
                        (noTradeReasons.firstOrNull() ?: "شرط‌ها کامل نیست")
                else "BTC-ARES · ${actionableCandidate!!.engine.labelFa} · امتیاز ${actionableCandidate.score}/100 · " +
                    "RR=${pct(actionableCandidate.rr ?: 0.0, 2)} · حجم=${"%.5f".format(java.util.Locale.US, qty)} BTC"
            ),
            blockers = if (finalAction == SignalAction.NO_TRADE) noTradeReasons + conflicts else emptyList(),
            confluence = confluence,
            interval = interval,
            barTime = barTime,
            btcAres = record,
        )
    }

    /** Exposed for tests and for the UI's "why" line; never used to bypass `evaluate`. */
    internal fun analyze(
        bars: List<Candle>,
        m15: List<Candle> = emptyList(),
        h1: List<Candle> = emptyList(),
        config: Config = Config(),
    ): Pair<Regime, Volatility> {
        val f = features(bars, m15, h1, config)
        return classifyRegime(f, config) to volatilityOf(f, config)
    }
}
