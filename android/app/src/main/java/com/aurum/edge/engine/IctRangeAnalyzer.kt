package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Causal, OHLC-only approximations of a range and some ICT vocabulary. This is NOT an
 * institutional order-flow detector. All indices below refer to CLOSED bars; no pivot,
 * level or pattern may use a candle later than the bar being assessed.
 */
object IctRangeAnalyzer {
    private const val RANGE_BARS = 32
    private const val MAX_SETUP_BARS = 8
    private val newYork: ZoneId = ZoneId.of("America/New_York") // DST, not a fixed UTC offset

    enum class Side { BUY, SELL }
    enum class State {
        INVALID_DATA, NO_RANGE, WAIT_SWEEP, BROKEN_RANGE, WAIT_MSS, WAIT_FVG,
        WAIT_RETEST, OUTSIDE_SESSION, POOR_REWARD_RISK, READY,
    }
    enum class ZoneKind { FVG, ORDER_BLOCK_PROXY }
    enum class Session { LONDON, NEW_YORK, OUTSIDE }

    data class Window(val session: Session, val date: LocalDate, val localTime: String) {
        val label: String get() = when (session) {
            Session.LONDON -> "لندن · ۰۲–۰۵ نیویورک"
            Session.NEW_YORK -> "نیویورک · ۰۷–۱۰ نیویورک"
            Session.OUTSIDE -> "بیرون پنجرهٔ ICT"
        }
    }

    data class Range(
        val support: Double,
        val resistance: Double,
        val atr: Double,
        val supportTouches: Int,
        val resistanceTouches: Int,
        val confirmedAt: Long,
        val fromTime: Long,
    ) {
        val width: Double get() = resistance - support
        val midpoint: Double get() = (support + resistance) / 2.0
    }

    data class Zone(val low: Double, val high: Double, val at: Long, val kind: ZoneKind)

    data class Setup(
        val side: Side,
        val state: State,
        val sweepAt: Long? = null,
        val shiftAt: Long? = null,
        val fvg: Zone? = null,
        val orderBlock: Zone? = null,
        val retestAt: Long? = null,
        val proposedStop: Double? = null,
        val opposingLevel: Double? = null,
        val rewardRisk: Double? = null,
    ) {
        val ready: Boolean get() = state == State.READY
    }

    data class Snapshot(
        val barTime: Long?,
        val range: Range?,
        val window: Window?,
        val buy: Setup,
        val sell: Setup,
    ) {
        val valid: Boolean get() = buy.state != State.INVALID_DATA
    }

    /** Input is the feed series, which may have ONE unfinished last bar. Never use it. */
    fun analyze(candles: List<Candle>, interval: Interval): Snapshot {
        val recent = candles.takeLast(RANGE_BARS + MAX_SETUP_BARS + 13)
        val bars = recent.filter { it.closed }
        val lastTime = bars.lastOrNull()?.time
        val invalid = Setup(Side.BUY, State.INVALID_DATA)
        val invalidSell = Setup(Side.SELL, State.INVALID_DATA)
        if (interval.minutes !in 1..15 || bars.size < RANGE_BARS + 3 ||
            recent.dropLast(1).any { !it.closed } || !wellFormed(bars, interval)) {
            return Snapshot(lastTime, null, null, invalid, invalidSell)
        }
        val last = bars.lastIndex
        val window = sessionAt(bars[last].time + interval.minutes * 60_000L)
        val buy = setup(bars, last, window, Side.BUY, interval)
        val sell = setup(bars, last, window, Side.SELL, interval)
        // Prefer the pre-sweep range, so the liquidity wick never moves its own boundary.
        val latestSweepRange = listOf(buy, sell)
            .filter { it.first != null && it.second.sweepAt != null }
            .maxByOrNull { it.second.sweepAt!! }?.first
        val range = latestSweepRange ?: confirmedRange(bars, last)
        return Snapshot(lastTime, range, window, buy.second, sell.second)
    }

    /** Timestamp is the instant of the CLOSED bar's close, not the phone's local clock. */
    fun sessionAt(closeTimeMs: Long): Window {
        val local = Instant.ofEpochMilli(closeTimeMs).atZone(newYork)
        val minute = local.hour * 60 + local.minute
        val session = when (minute) {
            in 2 * 60 until 5 * 60 -> Session.LONDON
            in 7 * 60 until 10 * 60 -> Session.NEW_YORK
            else -> Session.OUTSIDE
        }
        return Window(session, local.toLocalDate(), local.toLocalTime().toString())
    }

    private fun wellFormed(bars: List<Candle>, interval: Interval): Boolean = bars.withIndex().all { (i, c) ->
        c.time in 1L until 4_102_444_800_000L && c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
            c.low > 0 && c.low <= min(c.open, c.close) && c.high >= max(c.open, c.close) &&
            (i == 0 || c.time > bars[i - 1].time &&
                c.time - bars[i - 1].time <= interval.minutes * 120_000L)
    }

    /** Levels are based on repeated, separated wick touches; confirmed only after BOTH exist. */
    private fun confirmedRange(bars: List<Candle>, endExclusive: Int): Range? {
        if (endExclusive < RANGE_BARS) return null
        val history = bars.subList(endExclusive - RANGE_BARS, endExclusive)
        val tr = history.indices.drop(1).map { i ->
            val c = history[i]
            max(c.high - c.low, max(abs(c.high - history[i - 1].close), abs(c.low - history[i - 1].close)))
        }
        val atr = tr.takeLast(14).average()
        if (!atr.isFinite() || atr <= 0.0) return null
        // Ignore isolated extreme wicks. The 3rd low/high needs subsequent touches.
        val support = history.map { it.low }.sorted()[2]
        val resistance = history.map { it.high }.sortedDescending()[2]
        val width = resistance - support
        if (width !in 3.0 * atr..12.0 * atr) return null
        val tolerance = max(0.30 * atr, 0.035 * width)
        fun touches(side: Side): List<Int> {
            val matched = history.indices.filter { i ->
                abs((if (side == Side.BUY) history[i].low else history[i].high) -
                    (if (side == Side.BUY) support else resistance)) <= tolerance
            }
            return matched.fold(emptyList()) { kept, index ->
                if (kept.isEmpty() || index - kept.last() >= 4) kept + index else kept
            }
        }
        val lows = touches(Side.BUY)
        val highs = touches(Side.SELL)
        if (lows.size < 2 || highs.size < 2) return null
        val inside = history.count { it.close >= support - tolerance && it.close <= resistance + tolerance }
        if (inside < 28) return null
        // A directional drift through the range is not a sideways consolidation.
        val firstMean = history.take(16).map { it.close }.average()
        val secondMean = history.drop(16).map { it.close }.average()
        if (abs(firstMean - secondMean) > 0.30 * width) return null
        val confirmIndex = max(lows[1], highs[1]) + 1 // one closed bar beyond the second touch
        if (confirmIndex >= history.size) return null
        return Range(support, resistance, atr, lows.size, highs.size,
            history[confirmIndex].time, history.first().time)
    }

    private fun setup(bars: List<Candle>, last: Int, window: Window, side: Side,
                      interval: Interval): Pair<Range?, Setup> {
        // Rebuild each candidate baseline from bars STRICTLY BEFORE its sweep. A new sweep
        // supersedes an old setup; the last bar cannot retroactively create an earlier range.
        val candidate = (last downTo max(RANGE_BARS, last - MAX_SETUP_BARS)).firstNotNullOfOrNull { i ->
            val range = confirmedRange(bars, i) ?: return@firstNotNullOfOrNull null
            val c = bars[i]
            val margin = 0.08 * range.atr
            val swept = if (side == Side.BUY) {
                c.low < range.support - margin && c.close >= range.support && c.close < range.midpoint
            } else {
                c.high > range.resistance + margin && c.close <= range.resistance && c.close > range.midpoint
            }
            if (swept) i to range else null
        }
        val fallback = confirmedRange(bars, last)
        if (candidate == null) {
            val broken = fallback != null && (bars[last].close < fallback.support - 0.20 * fallback.atr ||
                bars[last].close > fallback.resistance + 0.20 * fallback.atr)
            return fallback to Setup(side, when {
                fallback == null -> State.NO_RANGE
                broken -> State.BROKEN_RANGE
                else -> State.WAIT_SWEEP
            })
        }
        val (sweepIndex, range) = candidate
        val sweep = bars[sweepIndex]
        fun result(state: State, shift: Int? = null, fvg: Zone? = null,
                   ob: Zone? = null, retest: Long? = null, stop: Double? = null,
                   opponent: Double? = null, rr: Double? = null) = range to Setup(
            side, state, sweepAt = sweep.time, shiftAt = shift?.let { bars[it].time },
            fvg = fvg, orderBlock = ob, retestAt = retest,
            proposedStop = stop, opposingLevel = opponent, rewardRisk = rr,
        )
        // Subsequent close through the swept boundary invalidates the reclaim.
        if ((sweepIndex + 1..last).any { i ->
                if (side == Side.BUY) bars[i].close < range.support - 0.20 * range.atr
                else bars[i].close > range.resistance + 0.20 * range.atr
            }) return result(State.BROKEN_RANGE)
        val shift = (sweepIndex + 1..min(last, sweepIndex + 3)).firstOrNull { i ->
            val c = bars[i]
            val prior = bars.subList(max(0, sweepIndex - 3), sweepIndex + 1)
            val pivot = if (side == Side.BUY) prior.maxOf { it.high } else prior.minOf { it.low }
            val direction = if (side == Side.BUY) c.close > c.open && c.close > pivot + 0.08 * range.atr
                            else c.close < c.open && c.close < pivot - 0.08 * range.atr
            direction && abs(c.close - c.open) >= 0.65 * range.atr
        } ?: return result(State.WAIT_MSS)
        val fvgIndex = (shift + 1..min(last, shift + 2)).firstOrNull { i ->
            val left = bars[i - 2]
            val right = bars[i]
            if (side == Side.BUY) right.low > left.high + 0.08 * range.atr
            else right.high < left.low - 0.08 * range.atr
        } ?: return result(State.WAIT_FVG, shift)
        val fvg = if (side == Side.BUY) Zone(bars[fvgIndex - 2].high, bars[fvgIndex].low,
            bars[fvgIndex].time, ZoneKind.FVG)
        else Zone(bars[fvgIndex].high, bars[fvgIndex - 2].low,
            bars[fvgIndex].time, ZoneKind.FVG)
        val obIndex = (shift - 1 downTo max(0, sweepIndex - 2)).firstOrNull { i ->
            if (side == Side.BUY) bars[i].close < bars[i].open else bars[i].close > bars[i].open
        }
        // A last opposite candle before displacement is only an OB *proxy* from OHLC.
        val ob = obIndex?.let { Zone(bars[it].low, bars[it].high, bars[it].time, ZoneKind.ORDER_BLOCK_PROXY) }
        if (last <= fvgIndex || last - fvgIndex > 3) return result(State.WAIT_RETEST, shift, fvg, ob)
        val c = bars[last]
        val overlapsFvg = c.low <= fvg.high && c.high >= fvg.low
        val overlapsOb = ob != null && c.low <= ob.high && c.high >= ob.low
        val discount = if (side == Side.BUY) c.close <= range.support + 0.45 * range.width
                       else c.close >= range.resistance - 0.45 * range.width
        val confirmed = if (side == Side.BUY) c.close > c.open && c.close >= range.support + 0.06 * range.atr
                        else c.close < c.open && c.close <= range.resistance - 0.06 * range.atr
        if ((!overlapsFvg && !overlapsOb) || !discount || !confirmed) {
            return result(State.WAIT_RETEST, shift, fvg, ob)
        }
        val stop = if (side == Side.BUY) sweep.low - 0.15 * range.atr else sweep.high + 0.15 * range.atr
        val opponent = if (side == Side.BUY) range.resistance - 0.15 * range.atr
                       else range.support + 0.15 * range.atr
        val risk = if (side == Side.BUY) c.close - stop else stop - c.close
        val reward = if (side == Side.BUY) opponent - c.close else c.close - opponent
        val rr = if (risk > 0.0) reward / risk else 0.0
        // The sweep and confirmation must be in the same New York session and date.
        val sweepWindow = sessionAt(sweep.time + interval.millis)
        if (window.session == Session.OUTSIDE || sweepWindow.session != window.session ||
            sweepWindow.date != window.date) return result(State.OUTSIDE_SESSION, shift, fvg, ob,
            c.time, stop, opponent, rr)
        if (!rr.isFinite() || rr < 1.5) return result(State.POOR_REWARD_RISK, shift, fvg, ob,
            c.time, stop, opponent, rr)
        return result(State.READY, shift, fvg, ob, c.time, stop, opponent, rr)
    }
}
