package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Multi-timeframe confluence, computed **on the phone** from the real candles that were
 * downloaded from the provider.
 *
 * Rules that keep this honest:
 *  - Timeframes are built by aggregating real bars into epoch-aligned buckets; a bucket is only
 *    used when it holds at least half of the bars the target timeframe expects, and the still
 *    forming bucket is never treated as closed. No bar is ever interpolated or invented.
 *  - A timeframe that does not have enough real history is reported as NEUTRAL with a
 *    "داده کافی نیست" note instead of a guessed bias.
 *  - The disagreement veto is descriptive, not a promise: it only says the timeframes conflict.
 */
object MtfAnalyzer {

    data class FrameBias(
        val interval: Interval,
        val bias: SignalAction,
        val strength: Int,
        val price: Double,
        val cloudTop: Double,
        val cloudBottom: Double,
        val ema: Double,
        val rsi: Double,
        val adx: Double?,
        val weight: Double,
        val detail: String,
    )

    data class Snapshot(
        val baseInterval: Interval,
        val frames: List<FrameBias>,
        val bias: SignalAction,
        val score: Double,
        val alignment: Double,
        val buyCount: Int,
        val sellCount: Int,
        val neutralCount: Int,
        val veto: Boolean,
        val vetoReason: String?,
        val advisory: String,
        val barTime: Long,
        val skippedFrames: List<String>,
    )

    private const val MIN_BASE_BARS = 30

    /** Timeframes that can be built from [base] by aggregation only (never by splitting). */
    fun framesFor(base: Interval): List<Interval> = when (base) {
        Interval.M1 -> listOf(Interval.M1, Interval.M5, Interval.M15, Interval.H1)
        Interval.M5 -> listOf(Interval.M5, Interval.M15, Interval.H1)
        Interval.M15 -> listOf(Interval.M15, Interval.H1)
        Interval.H1 -> listOf(Interval.H1)
        Interval.H4 -> listOf(Interval.H4)
    }

    private fun weightFor(interval: Interval): Double = when (interval) {
        Interval.M1 -> 0.15
        Interval.M5 -> 0.20
        Interval.M15 -> 0.25
        Interval.H1 -> 0.25
        Interval.H4 -> 0.15
    }

    /**
     * Aggregate real bars into [target]. The newest bucket stays open (its bar is marked
     * not-closed) and buckets that are clearly incomplete are dropped.
     */
    fun resample(candles: List<Candle>, target: Interval, base: Interval): List<Candle> {
        if (target == base) return candles
        if (target.millis <= base.millis || target.millis % base.millis != 0L) return candles
        val expected = (target.millis / base.millis).toInt()
        val minBars = maxOf(1, expected / 2)

        val buckets = LinkedHashMap<Long, MutableList<Candle>>()
        candles.forEach { candle ->
            val key = candle.time - (candle.time % target.millis)
            buckets.getOrPut(key) { mutableListOf() }.add(candle)
        }
        if (buckets.isEmpty()) return emptyList()
        val newestKey = buckets.keys.maxOrNull() ?: return emptyList()

        val out = ArrayList<Candle>(buckets.size)
        buckets.forEach { (key, group) ->
            if (group.size < minBars) return@forEach
            val ordered = group.sortedBy { it.time }
            out += Candle(
                time = key,
                open = ordered.first().open,
                high = ordered.maxOf { it.high },
                low = ordered.minOf { it.low },
                close = ordered.last().close,
                volume = ordered.sumOf { it.volume },
                closed = key != newestKey && ordered.all { it.closed },
            )
        }
        return out
    }

    private fun frameOf(bars: List<Candle>, interval: Interval): FrameBias? {
        val setting = SignalEngine.ichimokuSetting(interval)
        val required = setting.spanB + setting.kijun + 5
        val usable = bars.filter { it.closed }
        if (usable.size < required) return null

        val series = SignalEngine.series(usable, interval)
        val i = series.lastIndex
        val bar = series.bars.getOrNull(i) ?: return null
        val tenkan = series.ichimoku.tenkan.getOrNull(i) ?: return null
        val kijun = series.ichimoku.kijun.getOrNull(i) ?: return null
        val cloud = series.ichimoku.cloudAt(i) ?: return null

        val cloudTop = maxOf(cloud.first, cloud.second)
        val cloudBottom = minOf(cloud.first, cloud.second)
        val price = bar.close
        val ema = series.ema200.getOrNull(i) ?: price
        val rsi = series.rsi.getOrNull(i) ?: 50.0
        val adx = series.adx.getOrNull(i)

        var score = 0
        if (price > cloudTop) score += 2 else if (price < cloudBottom) score -= 2
        if (price > ema) score += 1 else if (price < ema) score -= 1
        if (tenkan > kijun) score += 1 else if (tenkan < kijun) score -= 1
        if (rsi >= 52.0 && rsi <= 72.0) score += 1 else if (rsi >= 28.0 && rsi <= 48.0) score -= 1

        val bias = when {
            score >= 2 -> SignalAction.BUY
            score <= -2 -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        val strength = ((abs(score) / 5.0) * 100.0).roundToInt().coerceIn(0, 100)
        val cloudNote = when {
            price > cloudTop -> "بالای ابر"
            price < cloudBottom -> "زیر ابر"
            else -> "داخل ابر"
        }
        val macdNote = series.macd.getOrNull(i)?.let { if (it >= 0) "MACD+" else "MACD−" } ?: "MACD—"
        val detail = buildString {
            append(cloudNote)
            append(" · ")
            append(if (price > ema) "بالای EMA200" else "زیر EMA200")
            append(" · ")
            append("RSI ")
            append(String.format("%.1f", rsi))
            if (adx != null) {
                append(" · ADX ")
                append(adx.roundToInt())
            }
            append(" · ")
            append(macdNote)
        }

        return FrameBias(
            interval = interval,
            bias = bias,
            strength = strength,
            price = price,
            cloudTop = cloudTop,
            cloudBottom = cloudBottom,
            ema = ema,
            rsi = rsi,
            adx = adx,
            weight = weightFor(interval),
            detail = detail,
        )
    }

    fun analyze(candles: List<Candle>, base: Interval): Snapshot? {
        val closed = candles.filter { it.closed }
        if (closed.size < MIN_BASE_BARS) return null

        val frames = ArrayList<FrameBias>()
        val skipped = ArrayList<String>()
        framesFor(base).forEach { target ->
            val bars = resample(closed, target, base)
            val frame = frameOf(bars, target)
            if (frame == null) skipped += target.label else frames += frame
        }
        if (frames.isEmpty()) return null

        val totalWeight = frames.sumOf { it.weight }
        val score = frames.sumOf { it.weight * direction(it.bias) } / totalWeight
        val bias = when {
            score > 0.25 -> SignalAction.BUY
            score < -0.25 -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
        val matching = frames.filter { it.bias == bias }.sumOf { it.weight }
        val alignment = matching / totalWeight

        val fast = frames.first()
        val slow = frames.last()
        val veto = frames.size > 1 && slow.strength >= 50 &&
            fast.bias != SignalAction.NO_TRADE && slow.bias != SignalAction.NO_TRADE && fast.bias != slow.bias
        val vetoReason = if (veto) {
            "تایم ${slow.interval.label} خلاف تایم ${fast.interval.label} است (قدرت ${slow.strength}%) — این واگرایی ورود را تایید نمی‌کند."
        } else {
            null
        }

        val advisory = when {
            veto -> "وتو چندتایم‌فریم: سیگنال تایم پایه با تایم بالاتر هم‌جهت نیست؛ قاعده این است که وارد نشوی."
            bias == SignalAction.NO_TRADE -> "همگرایی کافی نیست — تایم‌فریم‌ها هم‌جهت نیستند."
            alignment >= 0.75 -> "${(alignment * 100).roundToInt()}% وزن تایم‌فریم‌ها هم‌جهت (${bias.name}) — تایید چندتایم‌فریمی."
            else -> "هم‌جهتی ضعیف (${(alignment * 100).roundToInt()}%) — تایید کامل نیست."
        }

        return Snapshot(
            baseInterval = base,
            frames = frames,
            bias = bias,
            score = score,
            alignment = alignment,
            buyCount = frames.count { it.bias == SignalAction.BUY },
            sellCount = frames.count { it.bias == SignalAction.SELL },
            neutralCount = frames.count { it.bias == SignalAction.NO_TRADE },
            veto = veto,
            vetoReason = vetoReason,
            advisory = advisory,
            barTime = closed.last().time,
            skippedFrames = skipped,
        )
    }

    private fun direction(action: SignalAction): Double = when (action) {
        SignalAction.BUY -> 1.0
        SignalAction.SELL -> -1.0
        SignalAction.NO_TRADE -> 0.0
    }
}
