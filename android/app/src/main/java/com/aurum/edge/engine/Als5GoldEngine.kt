package com.aurum.edge.engine

import com.aurum.edge.core.Als5OpportunityRecord
import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * ALS-5 — Adaptive Liquidity & Structure Trading System for Gold (XAU/USD) on M5.
 *
 * Implements:
 *  - Confirmed right-bar pivot detection with zero repainting and zero look-ahead bias
 *  - 4-state Adaptive Market-Regime Classifier on confirmed M15 candles + M5 structure
 *  - Engine A: Trend Continuation (BOS + Pullback + Displacement + Micro-BOS)
 *  - Engine B: Liquidity Sweep & Reversal (Volatility-adjusted sweep + Close inside + MSS)
 *  - Engine C: Range & Mean-Reversion (Confirmed range boundaries + No middle-of-range + Rejection)
 *  - Pre-confirmation Opportunity Monitor (BUY, SELL, WAIT, NO_TRADE)
 *  - Transparent 0..10 Quality Score (5 categories x 0..2) with hard-requirement overrides
 *  - Broker-aware position sizing (0.25% risk, min 1.5R, 1.0% daily loss cap, transaction costs)
 */
object Als5GoldEngine {

    enum class Regime(val labelFa: String) {
        BULLISH_TREND("روند صعودی (Bullish Trend)"),
        BEARISH_TREND("روند نزولی (Bearish Trend)"),
        RANGE("رنج / کم‌جهت (Range)"),
        UNCERTAIN_HIGH_VOL("نامشخص / نوسان غیرعادی (Uncertain / High-Vol)"),
    }

    enum class DecisionState { BUY, SELL, WAIT, NO_TRADE }

    enum class EngineType(val id: String, val labelFa: String) {
        ENGINE_A_TREND("ENGINE_A_TREND", "موتور A · ادامهٔ روند (Trend Continuation)"),
        ENGINE_B_SWEEP("ENGINE_B_SWEEP", "موتور B · جمع‌آوری نقدینگی و برگشت (Liquidity Sweep)"),
        ENGINE_C_RANGE("ENGINE_C_RANGE", "موتور C · بازگشت به میانگین در رنج (Range Mean-Reversion)"),
        NONE("NONE", "بدون ستاپ"),
    }

    enum class ExitModel { FIXED_RR, STRUCTURAL_LEVEL, PARTIAL_AND_TRAIL }

    data class Config(
        val pivotLeft: Int = 3,
        val pivotRight: Int = 3,
        val microPivotLeft: Int = 2,
        val microPivotRight: Int = 1,
        val microLookbackBars: Int = 3,
        val atrPeriod: Int = 14,
        val adxPeriod: Int = 14,
        val emaPeriod: Int = 50,
        val volMedianLookback: Int = 60,
        val trendAdxMin: Double = 18.0,
        val rangeAdxMax: Double = 20.0,
        val highVolAtrRatio: Double = 2.2,
        val lowVolAtrRatio: Double = 0.55,
        val minImpulseAtr: Double = 1.1,
        val minPullbackFib: Double = 0.28,
        val maxPullbackFib: Double = 0.85,
        val minSwingProgressAtr: Double = 0.05,
        val pullbackZoneAtrTolerance: Double = 0.60,
        val maxChaseAtr: Double = 1.25,
        val maxSpreadAtrRatio: Double = 0.25,
        val minSweepAtr: Double = 0.12,
        val maxSweepAtr: Double = 1.35,
        val mssWindowBars: Int = 6,
        val minDisplacementRatio: Double = 0.45,
        val minRejectionWickRatio: Double = 0.40,
        val minRangeWidthAtr: Double = 1.8,
        val maxRangeWidthAtr: Double = 6.0,
        val rangeBoundaryZoneFrac: Double = 0.22,
        val rangeMidLowFrac: Double = 0.25,
        val rangeBreakoutAtrBuffer: Double = 0.40,
        val rangeWickEntryAtr: Double = 0.15,
        val rangeTargetAtrBuffer: Double = 0.15,
        val rangeMidHighFrac: Double = 0.75,
        val scoreThreshold10: Int = 8,
        val riskPerTradePct: Double = 0.25,
        val minRewardRisk: Double = 1.5,
        val maxDailyLossPct: Double = 1.0,
        val maxConsecutiveLosses: Int = 3,
        val stopAtrBuffer: Double = 0.25,
        val minStopAtr: Double = 0.35,
        val maxStopAtr: Double = 3.5,
        val accountEquityUsd: Double = 10_000.0,
        val dailyRealizedLossPct: Double = 0.0,
        val consecutiveLosses: Int = 0,
        val activeSetupKeys: Set<String> = emptySet(),
        val contractSizeOz: Double = 100.0,
        val minLot: Double = 0.01,
        val lotStep: Double = 0.01,
        val spreadUsdPerOz: Double = 0.16,
        val commissionPerLotSideUsd: Double = 3.50,
        val slippageUsdPerOz: Double = 0.05,
        val allowAsianSession: Boolean = true,
        val asianStartHourUtc: Int = 0,
        val asianEndHourUtc: Int = 7,
        val allowLondonSession: Boolean = true,
        val londonZoneId: String = "Europe/London",
        val londonStartHourLocal: Int = 7,
        val londonEndHourLocal: Int = 13,
        val allowNewYorkSession: Boolean = true,
        val newYorkZoneId: String = "America/New_York",
        val newYorkStartHourLocal: Int = 8,
        val newYorkEndHourLocal: Int = 16,
        val newsBlackoutMinutes: Int = 30,
        val exitModel: ExitModel = ExitModel.FIXED_RR,
        val enableEngineA: Boolean = true,
        val enableEngineB: Boolean = true,
        val enableEngineC: Boolean = true,
        val enforceMarketHours: Boolean = true,
        val enforceFreshness: Boolean = true,
        val minClosedM5Bars: Int = HistoryPolicy.LIVE_MIN_CANDLES,
    )

    data class SwingPoint(
        val index: Int,
        val time: Long,
        val price: Double,
        val isHigh: Boolean,
        val confirmedAtIndex: Int,
    )

    data class RegimeAssessment(
        val regime: Regime,
        val m15Adx: Double,
        val m15Atr: Double,
        val m5Atr: Double,
        val m5AtrRatio: Double,
        val htfBias: SignalAction,
        val rangeLow: Double?,
        val rangeHigh: Double?,
        val detail: String,
    )

    data class CandidateSetup(
        val engine: EngineType,
        val state: DecisionState,
        val direction: SignalAction,
        val structuralLevel: Double,
        val invalidationLevel: Double,
        val candidateEntry: Double,
        val stopLoss: Double,
        val takeProfit: Double,
        val rewardToRisk: Double,
        val netRewardToRisk: Double,
        val satisfied: List<String>,
        val missing: List<String>,
        val expirationBarsRemaining: Int,
        val structureScore: Int,
        val setupLevelScore: Int,
        val confirmationScore: Int,
        val rrScore: Int,
        val executionScore: Int,
        val setupKey: String,
        val candidateSinceBarTime: Long,
    ) {
        val totalScore10: Int
            get() = (structureScore + setupLevelScore + confirmationScore + rrScore + executionScore).coerceIn(0, 10)
    }

    /**
     * Confirmed pivot extraction with zero look-ahead bias:
     * A pivot at index [p] is only returned when [p + rightBars <= upToIndex].
     */
    fun confirmedSwings(
        bars: List<Candle>,
        leftBars: Int,
        rightBars: Int,
        upToIndex: Int = bars.lastIndex,
    ): List<SwingPoint> {
        if (bars.isEmpty() || leftBars < 1 || rightBars < 1) return emptyList()
        val limit = min(upToIndex, bars.lastIndex)
        val maxCandidate = limit - rightBars
        if (maxCandidate < leftBars) return emptyList()
        val points = mutableListOf<SwingPoint>()
        for (p in leftBars..maxCandidate) {
            val pivotHigh = bars[p].high
            val pivotLow = bars[p].low
            var isSwingHigh = true
            var isSwingLow = true
            for (k in (p - leftBars)..(p + rightBars)) {
                if (k == p) continue
                if (bars[k].high > pivotHigh || (k < p && bars[k].high >= pivotHigh)) {
                    isSwingHigh = false
                }
                if (bars[k].low < pivotLow || (k < p && bars[k].low <= pivotLow)) {
                    isSwingLow = false
                }
            }
            if (isSwingHigh) {
                points += SwingPoint(p, bars[p].time, pivotHigh, isHigh = true, confirmedAtIndex = p + rightBars)
            }
            if (isSwingLow) {
                points += SwingPoint(p, bars[p].time, pivotLow, isHigh = false, confirmedAtIndex = p + rightBars)
            }
        }
        return points
    }

    /**
     * Adaptive 4-state Market-Regime Classifier using confirmed M15 structure primarily,
     * with ATR/ADX/EMA and M5 513/1024 Ichimoku lines as secondary filters.
     */
    fun classifyRegime(
        m5Bars: List<Candle>,
        m15Bars: List<Candle>,
        config: Config = Config(),
    ): RegimeAssessment {
        val m5AtrSeries = Indicators.atr(m5Bars, config.atrPeriod)
        val m5Atr = m5AtrSeries.lastOrNull() ?: 1.0
        val recentAtr = m5AtrSeries.takeLast(config.volMedianLookback).filterNotNull().sorted()
        val medianM5Atr = recentAtr.getOrNull(recentAtr.size / 2)?.takeIf { it > 0.0 } ?: m5Atr
        val atrRatio = if (medianM5Atr > 0.0) m5Atr / medianM5Atr else 1.0

        val m15Atr = Indicators.atr(m15Bars, config.atrPeriod).lastOrNull() ?: (m5Atr * 1.7)
        val m15Adx = Indicators.adx(m15Bars, config.adxPeriod).lastOrNull() ?: 15.0
        val m15Ema = Indicators.ema(m15Bars.map { it.close }, config.emaPeriod).lastOrNull()
            ?: m15Bars.lastOrNull()?.close ?: 0.0
        val m15Close = m15Bars.lastOrNull()?.close ?: m5Bars.last().close

        // M5 513 & 1024 higher-timeframe Ichimoku lines
        val ichiM5 = Ichimoku.compute(m5Bars, 8, 24, 72, 24)
        val htfIchiBias = ichiM5.higherTimeframeBiasAt(m5Bars.lastIndex, m5Bars.last().close)

        val swings15 = confirmedSwings(m15Bars, config.pivotLeft, config.pivotRight)
        val highs15 = swings15.filter { it.isHigh }.takeLast(3)
        val lows15 = swings15.filter { !it.isHigh }.takeLast(3)

        val hh = highs15.size >= 2 && highs15.last().price > highs15[highs15.lastIndex - 1].price
        val hl = lows15.size >= 2 && lows15.last().price > lows15[lows15.lastIndex - 1].price
        val lh = highs15.size >= 2 && highs15.last().price < highs15[highs15.lastIndex - 1].price
        val ll = lows15.size >= 2 && lows15.last().price < lows15[lows15.lastIndex - 1].price

        val bosUp15 = highs15.isNotEmpty() && m15Close > highs15.last().price &&
            (lows15.size < 2 || lows15.last().price >= lows15[lows15.lastIndex - 1].price - 0.15 * m15Atr)
        val bosDown15 = lows15.isNotEmpty() && m15Close < lows15.last().price &&
            (highs15.size < 2 || highs15.last().price <= highs15[highs15.lastIndex - 1].price + 0.15 * m15Atr)

        // Range boundary calculation over recent 40 M5 bars / 16 M15 bars
        val recentM5 = m5Bars.takeLast(48)
        val highSwingsM5 = confirmedSwings(recentM5, 2, 2).filter { it.isHigh }
        val lowSwingsM5 = confirmedSwings(recentM5, 2, 2).filter { !it.isHigh }
        val rHigh = highSwingsM5.takeLast(3).maxOfOrNull { it.price } ?: recentM5.maxOf { it.high }
        val rLow = lowSwingsM5.takeLast(3).minOfOrNull { it.price } ?: recentM5.minOf { it.low }
        val rWidthAtr = if (m5Atr > 0.0) (rHigh - rLow) / m5Atr else 0.0

        // Abnormal volatility or expanding megaphone conflict -> UNCERTAIN_HIGH_VOL
        if (atrRatio > config.highVolAtrRatio || atrRatio < config.lowVolAtrRatio || (hh && ll)) {
            return RegimeAssessment(
                regime = Regime.UNCERTAIN_HIGH_VOL,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.NO_TRADE,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "نوسان غیرعادی یا ساختار متناقض (ATR Ratio=${"%.2f".format(atrRatio)})",
            )
        }

        val bullishStructure = (hh && hl) || bosUp15
        val bearishStructure = (lh && ll) || bosDown15

        if (bullishStructure && !bearishStructure && m15Close >= m15Ema && m15Adx >= config.trendAdxMin &&
            htfIchiBias != SignalAction.SELL
        ) {
            return RegimeAssessment(
                regime = Regime.BULLISH_TREND,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.BUY,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "ساختار M15 صعودی (HH/HL + ADX=${"%.1f".format(m15Adx)})",
            )
        }

        if (bearishStructure && !bullishStructure && m15Close <= m15Ema && m15Adx >= config.trendAdxMin &&
            htfIchiBias != SignalAction.BUY
        ) {
            return RegimeAssessment(
                regime = Regime.BEARISH_TREND,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.SELL,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "ساختار M15 نزولی (LH/LL + ADX=${"%.1f".format(m15Adx)})",
            )
        }

        if (rWidthAtr in config.minRangeWidthAtr..config.maxRangeWidthAtr && m15Adx <= config.rangeAdxMax + 4.0) {
            return RegimeAssessment(
                regime = Regime.RANGE,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.NO_TRADE,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "رنج تأییدشده [${"%.2f".format(rLow)}..${"%.2f".format(rHigh)}] (${"%.1f".format(rWidthAtr)} ATR)",
            )
        }

        // Transitional directional bias if structure leans one way without full ADX/EMA alignment
        if (bullishStructure && m15Close >= m15Ema) {
            return RegimeAssessment(
                regime = Regime.BULLISH_TREND,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.BUY,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "روند صعودی ملایم M15 (HH/HL)",
            )
        }
        if (bearishStructure && m15Close <= m15Ema) {
            return RegimeAssessment(
                regime = Regime.BEARISH_TREND,
                m15Adx = m15Adx,
                m15Atr = m15Atr,
                m5Atr = m5Atr,
                m5AtrRatio = atrRatio,
                htfBias = SignalAction.SELL,
                rangeLow = rLow,
                rangeHigh = rHigh,
                detail = "روند نزولی ملایم M15 (LH/LL)",
            )
        }

        return RegimeAssessment(
            regime = if (m15Adx < config.rangeAdxMax) Regime.RANGE else Regime.UNCERTAIN_HIGH_VOL,
            m15Adx = m15Adx,
            m15Atr = m15Atr,
            m5Atr = m5Atr,
            m5AtrRatio = atrRatio,
            htfBias = SignalAction.NO_TRADE,
            rangeLow = rLow,
            rangeHigh = rHigh,
            detail = "گذار ساختار یا عدم قطعیت جهت (ADX=${"%.1f".format(m15Adx)})",
        )
    }

    /**
     * Engine A — Trend Continuation on closed M5 bars.
     */
    private fun evaluateEngineA(
        m5Bars: List<Candle>,
        regime: RegimeAssessment,
        config: Config,
        execScore: Int,
    ): CandidateSetup? {
        if (!config.enableEngineA) return null
        val side = when (regime.regime) {
            Regime.BULLISH_TREND -> SignalAction.BUY
            Regime.BEARISH_TREND -> SignalAction.SELL
            else -> return null
        }
        val atr = regime.m5Atr
        if (atr <= 0.0 || !atr.isFinite()) return null
        val swings = confirmedSwings(m5Bars, config.pivotLeft, config.pivotRight)
        val highs = swings.filter { it.isHigh }
        val lows = swings.filter { !it.isHigh }
        if (highs.size < 2 || lows.size < 2) return null

        val lastBar = m5Bars.last()
        val prevBar = m5Bars[m5Bars.lastIndex - 1]
        val satisfied = mutableListOf<String>()
        val missing = mutableListOf<String>()

        if (side == SignalAction.BUY) {
            val prevHigh = highs[highs.lastIndex - 1]
            val latestHigh = highs.last()
            val protectedLow = lows.last()
            val priorLow = lows[lows.lastIndex - 1]
            val impulseTop = max(latestHigh.price, m5Bars.takeLast(12).maxOf { it.high })
            val impulseRange = impulseTop - protectedLow.price
            val bosConfirmed = lastBar.close > prevHigh.price
            val hlPreserved = protectedLow.price >= priorLow.price + config.minSwingProgressAtr * atr &&
                lastBar.low > protectedLow.price
            val impulseStrong = impulseRange >= config.minImpulseAtr * atr

            satisfied += "رژیم M15 صعودی"
            if (bosConfirmed) satisfied += "شکست سقف ساختاری (BOS)" else missing += "شکست سقف ساختاری (BOS)"
            if (hlPreserved) satisfied += "حفظ کف بالاتر (HL=${"%.2f".format(protectedLow.price)})"
            else return null // Hard invalidation if HL is violated
            if (impulseStrong) satisfied += "قدرت موج صعودی (${"%.1f".format(impulseRange / atr)} ATR)"
            else missing += "قدرت موج صعودی کافی نیست"

            // Pullback into Fib 30%..85% of impulse leg or near broken swing high
            val recentPullbackLow = m5Bars.takeLast(6).minOf { it.low }
            val retracement = if (impulseRange > 0.0) (impulseTop - recentPullbackLow) / impulseRange else 0.0
            val nearBrokenStructure = abs(recentPullbackLow - prevHigh.price) <= config.pullbackZoneAtrTolerance * atr
            val inPullbackZone = retracement in config.minPullbackFib..config.maxPullbackFib || nearBrokenStructure
            if (inPullbackZone) satisfied += "پولبک به ناحیهٔ اصلاحی/ساختار شکسته‌شده"
            else missing += "انتظار پولبک به ناحیهٔ اصلاحی"

            // Rejection or displacement candle + microstructure break
            val candleRange = (lastBar.high - lastBar.low).coerceAtLeast(1e-6)
            val lowerWick = min(lastBar.open, lastBar.close) - lastBar.low
            val body = lastBar.close - lastBar.open
            val rejectionOrDisplacement = lastBar.close > lastBar.open &&
                (body / candleRange >= config.minDisplacementRatio || lowerWick / candleRange >= config.minRejectionWickRatio)
            val microConfirmLevel = prevBar.high
            val microStructureBroken = lastBar.close > microConfirmLevel
            val notOverextended = (lastBar.close - microConfirmLevel) <= config.maxChaseAtr * atr &&
                (lastBar.close - recentPullbackLow) <= (config.maxChaseAtr + 0.8) * atr

            if (rejectionOrDisplacement) satisfied += "کندل ریجکشن/دیسپلیسمنت صعودی"
            else missing += "کندل تأیید ریجکشن/دیسپلیسمنت"
            if (microStructureBroken) satisfied += "شکست ریزساختار ۵ دقیقه بالای ${"%.2f".format(microConfirmLevel)}"
            else missing += "شکست سقف کندل پولبک (${"%.2f".format(microConfirmLevel)})"
            if (notOverextended) satisfied += "فاصلهٔ مناسب از سطح تأیید"
            else missing += "قیمت بیش از حد از سطح تأیید فاصله گرفته"

            val rawStop = min(recentPullbackLow, protectedLow.price) - config.stopAtrBuffer * atr
            val stopDist = lastBar.close - rawStop
            if (stopDist <= 0.0 || stopDist < config.minStopAtr * atr || stopDist > config.maxStopAtr * atr) return null
            val target = computeTarget(SignalAction.BUY, lastBar.close, stopDist, impulseTop + 0.5 * impulseRange, config)
            val rr = (target - lastBar.close) / stopDist
            val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
            if (netRr < config.minRewardRisk) missing += "نسبت خالص پس از هزینه حداقل ${config.minRewardRisk}:1 نیست"
            else satisfied += "نسبت خالص پس از هزینه قابل قبول است (خالص=${"%.2f".format(netRr)})"

            val structPts = if (bosConfirmed && hlPreserved && impulseStrong) 2 else if (hlPreserved) 1 else 0
            val levelPts = if (inPullbackZone && nearBrokenStructure) 2 else if (inPullbackZone) 1 else 0
            val confPts = if (rejectionOrDisplacement && microStructureBroken && notOverextended) 2
                          else if (rejectionOrDisplacement || microStructureBroken) 1 else 0
            val rrPts = if (netRr >= 2.0) 2 else if (netRr >= config.minRewardRisk) 1 else 0

            val allMandatory = bosConfirmed && hlPreserved && impulseStrong && inPullbackZone &&
                rejectionOrDisplacement && microStructureBroken && notOverextended &&
                rr >= config.minRewardRisk && netRr >= config.minRewardRisk
            val state = if (allMandatory) DecisionState.BUY else DecisionState.WAIT
            return CandidateSetup(
                engine = EngineType.ENGINE_A_TREND,
                state = state,
                direction = SignalAction.BUY,
                structuralLevel = prevHigh.price,
                invalidationLevel = protectedLow.price,
                candidateEntry = lastBar.close,
                stopLoss = rawStop,
                takeProfit = target,
                rewardToRisk = rr,
                netRewardToRisk = netRr,
                satisfied = satisfied,
                missing = missing,
                expirationBarsRemaining = if (state == DecisionState.WAIT) 6 else 0,
                structureScore = structPts,
                setupLevelScore = levelPts,
                confirmationScore = confPts,
                rrScore = rrPts,
                executionScore = execScore,
                setupKey = "A|BUY|${protectedLow.time}",
                candidateSinceBarTime = m5Bars[protectedLow.index].time,
            )
        } else {
            val prevLow = lows[lows.lastIndex - 1]
            val latestLow = lows.last()
            val protectedHigh = highs.last()
            val priorHigh = highs[highs.lastIndex - 1]
            val impulseBottom = min(latestLow.price, m5Bars.takeLast(12).minOf { it.low })
            val impulseRange = protectedHigh.price - impulseBottom
            val bosConfirmed = lastBar.close < prevLow.price
            val lhPreserved = protectedHigh.price <= priorHigh.price - config.minSwingProgressAtr * atr &&
                lastBar.high < protectedHigh.price
            val impulseStrong = impulseRange >= config.minImpulseAtr * atr

            satisfied += "رژیم M15 نزولی"
            if (bosConfirmed) satisfied += "شکست کف ساختاری (BOS)" else missing += "شکست کف ساختاری (BOS)"
            if (lhPreserved) satisfied += "حفظ سقف پایین‌تر (LH=${"%.2f".format(protectedHigh.price)})"
            else return null
            if (impulseStrong) satisfied += "قدرت موج نزولی (${"%.1f".format(impulseRange / atr)} ATR)"
            else missing += "قدرت موج نزولی کافی نیست"

            val recentPullbackHigh = m5Bars.takeLast(6).maxOf { it.high }
            val retracement = if (impulseRange > 0.0) (recentPullbackHigh - impulseBottom) / impulseRange else 0.0
            val nearBrokenStructure = abs(recentPullbackHigh - prevLow.price) <= config.pullbackZoneAtrTolerance * atr
            val inPullbackZone = retracement in config.minPullbackFib..config.maxPullbackFib || nearBrokenStructure
            if (inPullbackZone) satisfied += "پولبک به ناحیهٔ اصلاحی/ساختار شکسته‌شده"
            else missing += "انتظار پولبک به ناحیهٔ اصلاحی"

            val candleRange = (lastBar.high - lastBar.low).coerceAtLeast(1e-6)
            val upperWick = lastBar.high - max(lastBar.open, lastBar.close)
            val body = lastBar.open - lastBar.close
            val rejectionOrDisplacement = lastBar.close < lastBar.open &&
                (body / candleRange >= config.minDisplacementRatio || upperWick / candleRange >= config.minRejectionWickRatio)
            val microConfirmLevel = prevBar.low
            val microStructureBroken = lastBar.close < microConfirmLevel
            val notOverextended = (microConfirmLevel - lastBar.close) <= config.maxChaseAtr * atr &&
                (recentPullbackHigh - lastBar.close) <= (config.maxChaseAtr + 0.8) * atr

            if (rejectionOrDisplacement) satisfied += "کندل ریجکشن/دیسپلیسمنت نزولی"
            else missing += "کندل تأیید ریجکشن/دیسپلیسمنت"
            if (microStructureBroken) satisfied += "شکست ریزساختار ۵ دقیقه زیر ${"%.2f".format(microConfirmLevel)}"
            else missing += "شکست کف کندل پولبک (${"%.2f".format(microConfirmLevel)})"
            if (notOverextended) satisfied += "فاصلهٔ مناسب از سطح تأیید"
            else missing += "قیمت بیش از حد از سطح تأیید فاصله گرفته"

            val rawStop = max(recentPullbackHigh, protectedHigh.price) + config.stopAtrBuffer * atr
            val stopDist = rawStop - lastBar.close
            if (stopDist <= 0.0 || stopDist < config.minStopAtr * atr || stopDist > config.maxStopAtr * atr) return null
            val target = computeTarget(SignalAction.SELL, lastBar.close, stopDist, impulseBottom - 0.5 * impulseRange, config)
            val rr = (lastBar.close - target) / stopDist
            val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
            if (netRr < config.minRewardRisk) missing += "نسبت خالص پس از هزینه حداقل ${config.minRewardRisk}:1 نیست"
            else satisfied += "نسبت خالص پس از هزینه قابل قبول است (خالص=${"%.2f".format(netRr)})"

            val structPts = if (bosConfirmed && lhPreserved && impulseStrong) 2 else if (lhPreserved) 1 else 0
            val levelPts = if (inPullbackZone && nearBrokenStructure) 2 else if (inPullbackZone) 1 else 0
            val confPts = if (rejectionOrDisplacement && microStructureBroken && notOverextended) 2
                          else if (rejectionOrDisplacement || microStructureBroken) 1 else 0
            val rrPts = if (netRr >= 2.0) 2 else if (netRr >= config.minRewardRisk) 1 else 0

            val allMandatory = bosConfirmed && lhPreserved && impulseStrong && inPullbackZone &&
                rejectionOrDisplacement && microStructureBroken && notOverextended &&
                rr >= config.minRewardRisk && netRr >= config.minRewardRisk
            val state = if (allMandatory) DecisionState.SELL else DecisionState.WAIT
            return CandidateSetup(
                engine = EngineType.ENGINE_A_TREND,
                state = state,
                direction = SignalAction.SELL,
                structuralLevel = prevLow.price,
                invalidationLevel = protectedHigh.price,
                candidateEntry = lastBar.close,
                stopLoss = rawStop,
                takeProfit = target,
                rewardToRisk = rr,
                netRewardToRisk = netRr,
                satisfied = satisfied,
                missing = missing,
                expirationBarsRemaining = if (state == DecisionState.WAIT) 6 else 0,
                structureScore = structPts,
                setupLevelScore = levelPts,
                confirmationScore = confPts,
                rrScore = rrPts,
                executionScore = execScore,
                setupKey = "A|SELL|${protectedHigh.time}",
                candidateSinceBarTime = m5Bars[protectedHigh.index].time,
            )
        }
    }

    /**
     * Engine B — Liquidity Sweep and Reversal on closed M5 bars.
     */
    private fun evaluateEngineB(
        m5Bars: List<Candle>,
        regime: RegimeAssessment,
        config: Config,
        execScore: Int,
    ): CandidateSetup? {
        if (!config.enableEngineB || regime.regime == Regime.UNCERTAIN_HIGH_VOL) return null
        val atr = regime.m5Atr
        if (atr <= 0.0 || !atr.isFinite()) return null
        val lastIdx = m5Bars.lastIndex
        val windowStart = max(config.pivotLeft + config.pivotRight + 2, lastIdx - config.mssWindowBars)
        val priorSwings = confirmedSwings(m5Bars, config.pivotLeft, config.pivotRight, upToIndex = windowStart - 1)
        val swingLows = priorSwings.filter { !it.isHigh }.takeLast(3)
        val swingHighs = priorSwings.filter { it.isHigh }.takeLast(3)
        val lastBar = m5Bars.last()

        // Check Bullish Liquidity Sweep of a prior confirmed swing low
        val sweptLow = swingLows.lastOrNull()
        if (sweptLow != null && regime.regime != Regime.BEARISH_TREND) {
            var sweepIdx = -1
            var sweepExtreme = Double.POSITIVE_INFINITY
            for (i in windowStart..lastIdx) {
                val depth = (sweptLow.price - m5Bars[i].low) / atr
                if (depth in config.minSweepAtr..config.maxSweepAtr && m5Bars[i].close > sweptLow.price) {
                    // Only the latest qualifying sweep defines the live candidate and its stop.
                    sweepIdx = i
                    sweepExtreme = m5Bars[i].low
                }
            }
            if (sweepIdx != -1 && lastBar.close > sweptLow.price && lastBar.low >= sweepExtreme) {
                val satisfied = mutableListOf(
                    "سوییپ نقدینگی کف تأییدشده (${"%.2f".format(sweptLow.price)})",
                    "بسته‌شدن کندل بالای سطح سوییپ‌شده",
                )
                val missing = mutableListOf<String>()
                // MSS must break microstructure that existed BEFORE the sweep bar.
                val microHigh = m5Bars.subList(max(0, sweepIdx - config.microLookbackBars), sweepIdx).maxOfOrNull { it.high } ?: return null
                val candleRange = (lastBar.high - lastBar.low).coerceAtLeast(1e-6)
                val displacement = lastBar.close > lastBar.open &&
                    (lastBar.close - lastBar.open) / candleRange >= config.minDisplacementRatio
                val mssConfirmed = lastBar.close > microHigh
                if (mssConfirmed) satisfied += "تغییر ساختار صعودی (MSS بالای ${"%.2f".format(microHigh)})"
                else missing += "انتظار شکست ریزساختار بالای ${"%.2f".format(microHigh)}"
                if (displacement) satisfied += "کندل دیسپلیسمنت صعودی"
                else missing += "کندل دیسپلیسمنت قوی"

                val rawStop = sweepExtreme - config.stopAtrBuffer * atr
                val stopDist = lastBar.close - rawStop
                if (stopDist > 0.0 && stopDist in (config.minStopAtr * atr)..(config.maxStopAtr * atr)) {
                    val opposingHigh = swingHighs.lastOrNull()?.price ?: (lastBar.close + 2.2 * stopDist)
                    val target = computeTarget(SignalAction.BUY, lastBar.close, stopDist, opposingHigh, config)
                    val rr = (target - lastBar.close) / stopDist
                    val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
                    if (netRr < config.minRewardRisk) missing += "نسبت خالص پس از هزینه حداقل ${config.minRewardRisk}:1 نیست"
                    else satisfied += "نسبت خالص پس از هزینه قابل قبول است (خالص=${"%.2f".format(netRr)})"
                    val structPts = if (regime.regime == Regime.BULLISH_TREND || regime.regime == Regime.RANGE) 2 else 1
                    val levelPts = 2
                    val confPts = if (mssConfirmed && displacement) 2 else if (mssConfirmed || displacement) 1 else 0
                    val rrPts = if (netRr >= 2.0) 2 else if (netRr >= config.minRewardRisk) 1 else 0
                    val state = if (mssConfirmed && displacement && rr >= config.minRewardRisk &&
                        netRr >= config.minRewardRisk) DecisionState.BUY else DecisionState.WAIT
                    return CandidateSetup(
                        engine = EngineType.ENGINE_B_SWEEP,
                        state = state,
                        direction = SignalAction.BUY,
                        structuralLevel = sweptLow.price,
                        invalidationLevel = sweepExtreme,
                        candidateEntry = lastBar.close,
                        stopLoss = rawStop,
                        takeProfit = target,
                        rewardToRisk = rr,
                        netRewardToRisk = netRr,
                        satisfied = satisfied,
                        missing = missing,
                        expirationBarsRemaining = (config.mssWindowBars - (lastIdx - sweepIdx)).coerceAtLeast(1),
                        structureScore = structPts,
                        setupLevelScore = levelPts,
                        confirmationScore = confPts,
                        rrScore = rrPts,
                        executionScore = execScore,
                        setupKey = "B|BUY|${sweptLow.time}",
                        candidateSinceBarTime = m5Bars[sweepIdx].time,
                    )
                }
            }
        }

        // Check Bearish Liquidity Sweep of a prior confirmed swing high
        val sweptHigh = swingHighs.lastOrNull()
        if (sweptHigh != null && regime.regime != Regime.BULLISH_TREND) {
            var sweepIdx = -1
            var sweepExtreme = Double.NEGATIVE_INFINITY
            for (i in windowStart..lastIdx) {
                val depth = (m5Bars[i].high - sweptHigh.price) / atr
                if (depth in config.minSweepAtr..config.maxSweepAtr && m5Bars[i].close < sweptHigh.price) {
                    // Only the latest qualifying sweep defines the live candidate and its stop.
                    sweepIdx = i
                    sweepExtreme = m5Bars[i].high
                }
            }
            if (sweepIdx != -1 && lastBar.close < sweptHigh.price && lastBar.high <= sweepExtreme) {
                val satisfied = mutableListOf(
                    "سوییپ نقدینگی سقف تأییدشده (${"%.2f".format(sweptHigh.price)})",
                    "بسته‌شدن کندل زیر سطح سوییپ‌شده",
                )
                val missing = mutableListOf<String>()
                // MSS must break microstructure that existed BEFORE the sweep bar.
                val microLow = m5Bars.subList(max(0, sweepIdx - config.microLookbackBars), sweepIdx).minOfOrNull { it.low } ?: return null
                val candleRange = (lastBar.high - lastBar.low).coerceAtLeast(1e-6)
                val displacement = lastBar.close < lastBar.open &&
                    (lastBar.open - lastBar.close) / candleRange >= config.minDisplacementRatio
                val mssConfirmed = lastBar.close < microLow
                if (mssConfirmed) satisfied += "تغییر ساختار نزولی (MSS زیر ${"%.2f".format(microLow)})"
                else missing += "انتظار شکست ریزساختار زیر ${"%.2f".format(microLow)}"
                if (displacement) satisfied += "کندل دیسپلیسمنت نزولی"
                else missing += "کندل دیسپلیسمنت قوی"

                val rawStop = sweepExtreme + config.stopAtrBuffer * atr
                val stopDist = rawStop - lastBar.close
                if (stopDist > 0.0 && stopDist in (config.minStopAtr * atr)..(config.maxStopAtr * atr)) {
                    val opposingLow = swingLows.lastOrNull()?.price ?: (lastBar.close - 2.2 * stopDist)
                    val target = computeTarget(SignalAction.SELL, lastBar.close, stopDist, opposingLow, config)
                    val rr = (lastBar.close - target) / stopDist
                    val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
                    if (netRr < config.minRewardRisk) missing += "نسبت خالص پس از هزینه حداقل ${config.minRewardRisk}:1 نیست"
                    else satisfied += "نسبت خالص پس از هزینه قابل قبول است (خالص=${"%.2f".format(netRr)})"
                    val structPts = if (regime.regime == Regime.BEARISH_TREND || regime.regime == Regime.RANGE) 2 else 1
                    val levelPts = 2
                    val confPts = if (mssConfirmed && displacement) 2 else if (mssConfirmed || displacement) 1 else 0
                    val rrPts = if (netRr >= 2.0) 2 else if (netRr >= config.minRewardRisk) 1 else 0
                    val state = if (mssConfirmed && displacement && rr >= config.minRewardRisk &&
                        netRr >= config.minRewardRisk) DecisionState.SELL else DecisionState.WAIT
                    return CandidateSetup(
                        engine = EngineType.ENGINE_B_SWEEP,
                        state = state,
                        direction = SignalAction.SELL,
                        structuralLevel = sweptHigh.price,
                        invalidationLevel = sweepExtreme,
                        candidateEntry = lastBar.close,
                        stopLoss = rawStop,
                        takeProfit = target,
                        rewardToRisk = rr,
                        netRewardToRisk = netRr,
                        satisfied = satisfied,
                        missing = missing,
                        expirationBarsRemaining = (config.mssWindowBars - (lastIdx - sweepIdx)).coerceAtLeast(1),
                        structureScore = structPts,
                        setupLevelScore = levelPts,
                        confirmationScore = confPts,
                        rrScore = rrPts,
                        executionScore = execScore,
                        setupKey = "B|SELL|${sweptHigh.time}",
                        candidateSinceBarTime = m5Bars[sweepIdx].time,
                    )
                }
            }
        }
        return null
    }

    /**
     * Engine C — Range and Mean-Reversion Opportunities on closed M5 bars.
     * Strictly forbids entries in the middle of the range ([rangeMidLowFrac, rangeMidHighFrac]).
     */
    private fun evaluateEngineC(
        m5Bars: List<Candle>,
        regime: RegimeAssessment,
        config: Config,
        execScore: Int,
    ): CandidateSetup? {
        if (!config.enableEngineC || regime.regime != Regime.RANGE) return null
        val rLow = regime.rangeLow ?: return null
        val rHigh = regime.rangeHigh ?: return null
        val atr = regime.m5Atr
        val width = rHigh - rLow
        if (atr <= 0.0 || width < config.minRangeWidthAtr * atr || width > config.maxRangeWidthAtr * atr) return null

        val lastBar = m5Bars.last()
        val prevBar = m5Bars[m5Bars.lastIndex - 1]
        // Strong breakout invalidates the range
        if (lastBar.close > rHigh + config.rangeBreakoutAtrBuffer * atr ||
            lastBar.close < rLow - config.rangeBreakoutAtrBuffer * atr) return null

        val location = (lastBar.close - rLow) / width
        // Never trade in the middle of the range
        if (location in config.rangeMidLowFrac..config.rangeMidHighFrac) return null

        if (location <= config.rangeBoundaryZoneFrac || (lastBar.low <= rLow + config.rangeWickEntryAtr * atr && lastBar.close > rLow)) {
            val satisfied = mutableListOf(
                "رژیم رنج تأییدشده [${"%.2f".format(rLow)}..${"%.2f".format(rHigh)}]",
                "حضور قیمت در مرز پایینی رنج (${"%.0f".format(location * 100)}%)",
            )
            val missing = mutableListOf<String>()
            val bullishRej = lastBar.close > lastBar.open && lastBar.close > prevBar.high
            if (bullishRej) satisfied += "ریجکشن صعودی و شکست سقف کندل قبل در کف رنج"
            else missing += "انتظار کندل برگشتی صعودی بالای ${"%.2f".format(prevBar.high)}"

            val rawStop = min(rLow, lastBar.low) - config.stopAtrBuffer * atr
            val stopDist = lastBar.close - rawStop
            if (stopDist < config.minStopAtr * atr || stopDist > config.maxStopAtr * atr) return null
            val target = rHigh - config.rangeTargetAtrBuffer * atr
            val rr = (target - lastBar.close) / stopDist
            val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
            val rrFeasible = rr >= config.minRewardRisk && netRr >= config.minRewardRisk
            if (rrFeasible) satisfied += "هدف مرز مخالف رنج نسبت خالص کافی دارد"
            else missing += "هدف ساختاری در مرز مقابل به RR خالص ${config.minRewardRisk}:1 نمی‌رسد"
            val confPts = if (bullishRej) 2 else 0
            val rrPts = if (netRr >= 2.0) 2 else if (rrFeasible) 1 else 0
            val state = if (bullishRej && rrFeasible) DecisionState.BUY else DecisionState.WAIT
            return CandidateSetup(
                engine = EngineType.ENGINE_C_RANGE,
                state = state,
                direction = SignalAction.BUY,
                structuralLevel = rLow,
                invalidationLevel = rawStop,
                candidateEntry = lastBar.close,
                stopLoss = rawStop,
                takeProfit = target,
                rewardToRisk = rr,
                netRewardToRisk = netRr,
                satisfied = satisfied,
                missing = missing,
                expirationBarsRemaining = if (state == DecisionState.WAIT) 4 else 0,
                structureScore = 2,
                setupLevelScore = 2,
                confirmationScore = confPts,
                rrScore = rrPts,
                executionScore = execScore,
                setupKey = "C|BUY|${(rLow * 10).toLong()}",
                candidateSinceBarTime = m5Bars.last().time,
            )
        }

        if (location >= 1.0 - config.rangeBoundaryZoneFrac || (lastBar.high >= rHigh - 0.15 * atr && lastBar.close < rHigh)) {
            val satisfied = mutableListOf(
                "رژیم رنج تأییدشده [${"%.2f".format(rLow)}..${"%.2f".format(rHigh)}]",
                "حضور قیمت در مرز بالایی رنج (${"%.0f".format(location * 100)}%)",
            )
            val missing = mutableListOf<String>()
            val bearishRej = lastBar.close < lastBar.open && lastBar.close < prevBar.low
            if (bearishRej) satisfied += "ریجکشن نزولی و شکست کف کندل قبل در سقف رنج"
            else missing += "انتظار کندل برگشتی نزولی زیر ${"%.2f".format(prevBar.low)}"

            val rawStop = max(rHigh, lastBar.high) + config.stopAtrBuffer * atr
            val stopDist = rawStop - lastBar.close
            if (stopDist < config.minStopAtr * atr || stopDist > config.maxStopAtr * atr) return null
            val target = rLow + config.rangeTargetAtrBuffer * atr
            val rr = (lastBar.close - target) / stopDist
            val netRr = netRewardRisk(lastBar.close, rawStop, target, config)
            val rrFeasible = rr >= config.minRewardRisk && netRr >= config.minRewardRisk
            if (rrFeasible) satisfied += "هدف مرز مخالف رنج نسبت خالص کافی دارد"
            else missing += "هدف ساختاری در مرز مقابل به RR خالص ${config.minRewardRisk}:1 نمی‌رسد"
            val confPts = if (bearishRej) 2 else 0
            val rrPts = if (netRr >= 2.0) 2 else if (rrFeasible) 1 else 0
            val state = if (bearishRej && rrFeasible) DecisionState.SELL else DecisionState.WAIT
            return CandidateSetup(
                engine = EngineType.ENGINE_C_RANGE,
                state = state,
                direction = SignalAction.SELL,
                structuralLevel = rHigh,
                invalidationLevel = rawStop,
                candidateEntry = lastBar.close,
                stopLoss = rawStop,
                takeProfit = target,
                rewardToRisk = rr,
                netRewardToRisk = netRr,
                satisfied = satisfied,
                missing = missing,
                expirationBarsRemaining = if (state == DecisionState.WAIT) 4 else 0,
                structureScore = 2,
                setupLevelScore = 2,
                confirmationScore = confPts,
                rrScore = rrPts,
                executionScore = execScore,
                setupKey = "C|SELL|${(rHigh * 10).toLong()}",
                candidateSinceBarTime = m5Bars.last().time,
            )
        }
        return null
    }

    private fun computeTarget(
        side: SignalAction,
        entry: Double,
        stopDist: Double,
        structuralOpposing: Double,
        config: Config,
    ): Double {
        val fixedTarget = if (side == SignalAction.BUY) entry + max(config.minRewardRisk, 1.8) * stopDist
        else entry - max(config.minRewardRisk, 1.8) * stopDist
        return when (config.exitModel) {
            ExitModel.FIXED_RR -> fixedTarget
            ExitModel.STRUCTURAL_LEVEL -> {
                if (side == SignalAction.BUY && structuralOpposing >= entry + config.minRewardRisk * stopDist)
                    structuralOpposing
                else if (side == SignalAction.SELL && structuralOpposing <= entry - config.minRewardRisk * stopDist)
                    structuralOpposing
                else fixedTarget
            }
            ExitModel.PARTIAL_AND_TRAIL -> {
                if (side == SignalAction.BUY) entry + max(config.minRewardRisk, 2.0) * stopDist
                else entry - max(config.minRewardRisk, 2.0) * stopDist
            }
        }
    }

    private fun netRewardRisk(entry: Double, stop: Double, target: Double, config: Config): Double {
        val grossRisk = abs(entry - stop)
        val grossReward = abs(target - entry)
        val roundTripCostPerOz = config.spreadUsdPerOz + config.slippageUsdPerOz +
            (2.0 * config.commissionPerLotSideUsd / config.contractSizeOz)
        val netRisk = (grossRisk + roundTripCostPerOz).coerceAtLeast(1e-6)
        val netReward = (grossReward - roundTripCostPerOz).coerceAtLeast(0.0)
        return netReward / netRisk
    }

    /**
     * Calculates position size in lots and ounces from equity, 0.25% risk, stop distance,
     * and broker contract specifications. Never uses martingale or increases risk after losses.
     */
    fun calculatePositionSize(
        entryPrice: Double,
        stopLoss: Double,
        config: Config = Config(),
    ): Pair<Double, Double> {
        val stopDistPerOz = abs(entryPrice - stopLoss)
        val roundTripCostPerOz = config.spreadUsdPerOz + config.slippageUsdPerOz +
            (2.0 * config.commissionPerLotSideUsd / config.contractSizeOz)
        val effectiveLossPerOz = stopDistPerOz + roundTripCostPerOz
        if (effectiveLossPerOz <= 0.0 || !effectiveLossPerOz.isFinite()) return 0.0 to 0.0
        val riskBudgetUsd = config.accountEquityUsd * (config.riskPerTradePct / 100.0)
        val rawOz = riskBudgetUsd / effectiveLossPerOz
        val rawLots = rawOz / config.contractSizeOz
        if (rawLots < config.minLot) return 0.0 to 0.0
        val steppedLots = floor(rawLots / config.lotStep + 1e-9) * config.lotStep
        val finalLots = if (steppedLots < config.minLot) 0.0 else steppedLots
        return finalLots to (finalLots * config.contractSizeOz)
    }

    fun sessionAllowed(barCloseTimeMs: Long, config: Config): Boolean {
        val instant = Instant.ofEpochMilli(barCloseTimeMs)
        val utcHour = instant.atZone(ZoneId.of("UTC")).hour
        val londonHour = instant.atZone(ZoneId.of(config.londonZoneId)).hour
        val newYorkHour = instant.atZone(ZoneId.of(config.newYorkZoneId)).hour
        val inAsian = config.asianStartHourUtc <= utcHour && utcHour < config.asianEndHourUtc
        val inLondon = config.londonStartHourLocal <= londonHour && londonHour < config.londonEndHourLocal
        val inNewYork = config.newYorkStartHourLocal <= newYorkHour && newYorkHour < config.newYorkEndHourLocal
        // Europe/London and America/New_York are IANA zones; java.time applies DST changes.
        return (inAsian && config.allowAsianSession) ||
            (inLondon && config.allowLondonSession) ||
            (inNewYork && config.allowNewYorkSession)
    }


    fun evaluate(
        input: List<Candle>,
        interval: Interval = Interval.M5,
        symbol: String = "XAU/USD",
        threshold: Double = 80.0,
        @Suppress("UNUSED_PARAMETER") mode: CategoryStrategy = CategoryStrategy.HYBRID,
        external: Map<Interval, List<Candle>> = emptyMap(),
        config: Config = Config(),
        now: Long = System.currentTimeMillis(),
    ): Signal {
        val closed = input.filter { it.closed && it.time + interval.millis <= now }
            .sortedBy { it.time }
            .takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        val last = closed.lastOrNull()
        val barCloseTime = last?.time?.plus(interval.millis) ?: 0L
        val blockers = mutableListOf<String>()

        fun blocked(msg: String): Signal = Signal(
            action = SignalAction.NO_TRADE,
            confidence = 0.0,
            blockers = listOf(msg),
            interval = interval,
            barTime = last?.time ?: 0L,
        )

        if (com.aurum.edge.core.V1Universe.normalize(symbol) != "XAU/USD" ||
            !threshold.isFinite() || threshold !in 60.0..95.0) {
            return blocked("ALS-5 فقط برای XAU/USD است و آستانه باید بین ۶۰ تا ۹۵ باشد")
        }
        if (config.accountEquityUsd <= 0.0 || !config.accountEquityUsd.isFinite() ||
            config.riskPerTradePct !in 0.01..0.25 || config.minRewardRisk < 1.5 ||
            config.maxDailyLossPct !in 0.1..1.0 || config.contractSizeOz <= 0.0 ||
            config.minLot <= 0.0 || config.lotStep <= 0.0 || config.maxSpreadAtrRatio <= 0.0 ||
            config.newsBlackoutMinutes < 0 || config.mssWindowBars < 1) {
            return blocked("ALS-5: پارامترهای ریسک، قرارداد یا حدآستانه نامعتبر هستند")
        }
        if (interval != Interval.M5) {
            return blocked("ALS-5: تحلیل و ورود طلا فقط روی کندل بستهٔ ۵ دقیقه (M5) مجاز است")
        }
        if (closed.size < config.minClosedM5Bars || last == null) {
            return blocked("ALS-5: حداقل ${config.minClosedM5Bars} کندل بستهٔ ۵ دقیقه برای طلا لازم است (${closed.size}/${config.minClosedM5Bars})")
        }
        if (closed.zipWithNext().any { (a, b) -> b.time <= a.time } ||
            closed.any {
                it.time <= 0L || !listOf(it.open, it.high, it.low, it.close).all { v -> v.isFinite() && v > 0.0 } ||
                    it.high < max(it.open, it.close) || it.low > min(it.open, it.close)
            }
        ) {
            return blocked("ALS-5: ترتیب زمانی یا OHLC کندل‌های طلا نامعتبر است")
        }

        if (config.enforceMarketHours && (MarketHours.closedFor(symbol, barCloseTime) || MarketHours.closedFor(symbol, now))) {
            blockers += "ALS-5: بازار طلا بسته است"
        }
        if (config.enforceFreshness && now - barCloseTime !in 0L..(interval.millis + 90_000L)) {
            blockers += "ALS-5: کندل ۵ دقیقهٔ طلا برای ورود تازه نیست"
        }
        if (config.dailyRealizedLossPct >= config.maxDailyLossPct) {
            blockers += "ALS-5: سقف ضرر روزانه (${config.maxDailyLossPct}%) پر شده است؛ معاملهٔ جدید قفل است"
        }
        if (config.consecutiveLosses >= config.maxConsecutiveLosses) {
            blockers += "ALS-5: سقف ضرر متوالی (${config.maxConsecutiveLosses}) فعال شده است"
        }
        if (!sessionAllowed(barCloseTime, config)) {
            blockers += "ALS-5: خارج از سشن معاملاتی مجاز طلا"
        }

        val m15Bars = external[Interval.M15]?.filter { it.closed && it.time + Interval.M15.millis <= barCloseTime }
            ?.sortedBy { it.time }
            ?.takeIf { it.isNotEmpty() }
            ?: emptyList() // M15 must be independently fetched; never resampled from M5

        if (m15Bars.size < 30) {
            return blocked("ALS-5: کندل‌های بستهٔ ۱۵ دقیقه برای تشخیص رژیم بازار کافی نیستند")
        }

        val regime = classifyRegime(closed, m15Bars, config)
        if (regime.regime == Regime.UNCERTAIN_HIGH_VOL) {
            blockers += "ALS-5: رژیم بازار نامشخص یا نوسان غیرعادی است (${regime.detail})"
        }

        val spreadAtrRatio = if (regime.m5Atr > 0.0) config.spreadUsdPerOz / regime.m5Atr else 1.0
        if (!spreadAtrRatio.isFinite() || spreadAtrRatio > config.maxSpreadAtrRatio) {
            blockers += "ALS-5: اسپرد/ATR=${"%.2f".format(spreadAtrRatio)} از سقف ${config.maxSpreadAtrRatio} بیشتر است"
        }
        val execScore = when {
            blockers.isEmpty() && spreadAtrRatio <= 0.12 -> 2
            blockers.isEmpty() && spreadAtrRatio <= 0.25 -> 1
            else -> 0
        }

        val candidates = listOfNotNull(
            evaluateEngineA(closed, regime, config, execScore),
            evaluateEngineB(closed, regime, config, execScore),
            evaluateEngineC(closed, regime, config, execScore),
        )

        // Prioritize confirmed BUY/SELL over WAIT, then highest totalScore10, then netRewardToRisk
        val best = candidates.maxWithOrNull(
            compareBy<CandidateSetup> { it.state == DecisionState.BUY || it.state == DecisionState.SELL }
                .thenBy { it.totalScore10 }
                .thenBy { it.netRewardToRisk }
        )

        if (best == null) {
            val noTradeRecord = Als5OpportunityRecord(
                state = DecisionState.NO_TRADE.name,
                setupType = EngineType.NONE.id,
                direction = SignalAction.NO_TRADE,
                structuralLevel = null,
                regime = regime.regime.name,
                satisfiedConditions = emptyList(),
                missingConditions = listOf("ستاپ معتبری در موتورهای A، B یا C شکل نگرفته است"),
                invalidationLevel = null,
                candidateEntry = null,
                estimatedStopLoss = null,
                estimatedTakeProfit = null,
                rewardToRisk = null,
                candidateSinceBarTime = null,
                expirationBarsRemaining = 0,
                scoreOutOf10 = 0,
                structureScore = 0,
                setupLevelScore = 0,
                confirmationScore = 0,
                rrScore = 0,
                executionScore = execScore,
                barTime = last.time,
            )
            return Signal(
                action = SignalAction.NO_TRADE,
                confidence = 0.0,
                reasons = listOf("ALS-5 · ${regime.regime.labelFa} · بدون ستاپ فعال"),
                blockers = blockers + "ALS-5: هیچ‌یک از سه موتور (روند، سوییپ نقدینگی، رنج) ستاپ فعالی ندارند",
                confluence = buildConfluenceItems(regime, null),
                interval = interval,
                barTime = last.time,
                als5Opportunity = noTradeRecord,
            )
        }

        val (lots, oz) = calculatePositionSize(best.candidateEntry, best.stopLoss, config)
        if (lots <= 0.0) {
            blockers += "ALS-5: حجم محاسبه‌شده کمتر از حداقل لات بروکر (${config.minLot}) است"
        }
        if (best.setupKey in config.activeSetupKeys) {
            blockers += "ALS-5: ورود تکراری روی همین ستاپ ساختاری (${best.setupKey}) ممنوع است"
        }

        val requiredScore10 = max(config.scoreThreshold10, (threshold / 10.0).toInt().coerceIn(6, 9))
        if (best.totalScore10 < requiredScore10) {
            blockers += "ALS-5: امتیاز کیفیت ستاپ ${best.totalScore10}/10 کمتر از حد نصاب $requiredScore10/10 است"
        }
        if (best.state == DecisionState.WAIT) {
            blockers += "ALS-5 [WAIT]: ستاپ ${best.engine.labelFa} در حال تکمیل است — نواقص: ${best.missing.joinToString("، ")}"
        }

        val finalState = when {
            blockers.isEmpty() && (best.state == DecisionState.BUY || best.state == DecisionState.SELL) -> best.state
            best.state == DecisionState.WAIT || best.missing.isNotEmpty() -> DecisionState.WAIT
            else -> DecisionState.NO_TRADE
        }
        val actionable = finalState == DecisionState.BUY || finalState == DecisionState.SELL
        val confluence = buildConfluenceItems(regime, best, actionable)
        val totalConfidence = confluence.sumOf { it.scorePercent ?: 0 }.toDouble()

        val record = Als5OpportunityRecord(
            state = finalState.name,
            setupType = best.engine.id,
            direction = best.direction,
            structuralLevel = best.structuralLevel,
            regime = regime.regime.name,
            satisfiedConditions = best.satisfied,
            missingConditions = best.missing,
            invalidationLevel = best.invalidationLevel,
            candidateEntry = best.candidateEntry,
            estimatedStopLoss = best.stopLoss,
            estimatedTakeProfit = best.takeProfit,
            rewardToRisk = best.rewardToRisk,
            candidateSinceBarTime = best.candidateSinceBarTime.takeIf { it > 0L },
            expirationBarsRemaining = best.expirationBarsRemaining,
            scoreOutOf10 = best.totalScore10,
            structureScore = best.structureScore,
            setupLevelScore = best.setupLevelScore,
            confirmationScore = best.confirmationScore,
            rrScore = best.rrScore,
            executionScore = best.executionScore,
            positionLots = lots,
            positionOz = oz,
            exitModel = config.exitModel.name,
            setupKey = best.setupKey,
            barTime = last.time,
        )

        return Signal(
            action = if (actionable) best.direction else SignalAction.NO_TRADE,
            confidence = totalConfidence,
            entry = if (actionable) best.candidateEntry else null,
            stopLoss = if (actionable) best.stopLoss else null,
            takeProfit = if (actionable) best.takeProfit else null,
            riskReward = if (actionable) best.rewardToRisk else null,
            reasons = listOf(
                "ALS-5 [${finalState.name}] · ${best.engine.labelFa} · امتیاز ${best.totalScore10}/10 · RR=${"%.2f".format(best.rewardToRisk)} · لات=${"%.2f".format(lots)}"
            ),
            blockers = blockers,
            confluence = confluence,
            interval = interval,
            barTime = last.time,
            als5Opportunity = record,
        )
    }

    private fun buildConfluenceItems(
        regime: RegimeAssessment,
        candidate: CandidateSetup?,
        actionable: Boolean = false,
    ): List<ConfluenceItem> {
        fun item(name: String, pts25: Int, detail: String): ConfluenceItem {
            val clamped = pts25.coerceIn(0, 25)
            val status = when {
                clamped == 25 -> ConfluenceStatus.CONFIRMED
                clamped > 0 -> ConfluenceStatus.PARTIAL
                else -> ConfluenceStatus.CONFLICT
            }
            return ConfluenceItem(name, clamped == 25, detail, status, clamped)
        }

        if (candidate == null) {
            return listOf(
                item("رژیم و ساختار بازار · ALS-5", if (regime.regime != Regime.UNCERTAIN_HIGH_VOL) 12 else 0, regime.detail),
                item("کیفیت ستاپ و سطح نقدینگی · ALS-5", 0, "بدون سطح فعال در موتورهای سه‌گانه"),
                item("تأیید ورود و شکست ریزساختار · ALS-5", 0, "بدون تأیید ورود"),
                item("نسبت ریسک به ریوارد و شرایط اجرا · ALS-5", 0, "بدون معامله"),
            )
        }

        val s1 = if (actionable && candidate.structureScore >= 1) 25 else (candidate.structureScore * 12)
        val s2 = if (actionable && candidate.setupLevelScore >= 1) 25 else (candidate.setupLevelScore * 12)
        val s3 = if (actionable && candidate.confirmationScore == 2) 25 else (candidate.confirmationScore * 12)
        val combinedRrExec = candidate.rrScore + candidate.executionScore // 0..4
        val s4 = if (actionable && combinedRrExec >= 2) 25 else (combinedRrExec * 6)

        return listOf(
            item(
                "رژیم و ساختار بازار · ALS-5",
                s1,
                "${regime.regime.labelFa} · امتیاز ساختار=${candidate.structureScore}/2 · ${regime.detail}",
            ),
            item(
                "کیفیت ستاپ و سطح نقدینگی · ALS-5",
                s2,
                "${candidate.engine.labelFa} · سطح=${"%.2f".format(candidate.structuralLevel)} · امتیاز سطح=${candidate.setupLevelScore}/2",
            ),
            item(
                "تأیید ورود و شکست ریزساختار · ALS-5",
                s3,
                "وضعیت=${candidate.state.name} · تکمیل=${candidate.satisfied.size} شرط · امتیاز تأیید=${candidate.confirmationScore}/2",
            ),
            item(
                "نسبت ریسک به ریوارد و شرایط اجرا · ALS-5",
                s4,
                "RR=${"%.2f".format(candidate.rewardToRisk)} (خالص=${"%.2f".format(candidate.netRewardToRisk)}) · امتیاز کل=${candidate.totalScore10}/10",
            ),
        )
    }
}
