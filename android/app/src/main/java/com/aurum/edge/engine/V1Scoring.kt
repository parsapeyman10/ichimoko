package com.aurum.edge.engine

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The scoring implementation behind SignalEngine.evaluate; never an alternative entry path.
 * Four independent layers, each worth 25 points. Missing timeframes are UNKNOWN, never
 * synthesized by splitting larger candles; only aggregation of real smaller bars is allowed.
 */
internal object V1Scoring {
    val intervals = listOf(Interval.M1, Interval.M5, Interval.M15, Interval.M30,
        Interval.H1, Interval.H4, Interval.D1)
    private const val MIN_BARS = 210 // EMA200 + visible Ichimoku 8/24/72

    fun evaluate(input: List<Candle>, interval: Interval, symbol: String, threshold: Double,
                 mode: CategoryStrategy, external: Map<Interval, List<Candle>>): Signal {
        val clock = System.currentTimeMillis()
        val bars = input.filter { it.closed && it.time + interval.millis <= clock }
            .takeLast(SignalEngine.ENGINE_WINDOW)
        val last = bars.lastOrNull()
        val asOf = last?.time?.plus(interval.millis) ?: 0L
        val blockers = mutableListOf<String>()
        fun blocked(message: String): Signal = Signal(SignalAction.NO_TRADE, 0.0,
            blockers = listOf(message), interval = interval, barTime = last?.time ?: 0L)
        if (symbol.isBlank() || !threshold.isFinite() || threshold !in 60.0..95.0)
            return blocked("نماد یا آستانهٔ ۶۰ تا ۹۵ معتبر نیست")
        if (bars.size < MIN_BARS || last == null) return blocked("حداقل $MIN_BARS کندل بستهٔ واقعی لازم است")
        if (bars.zipWithNext().any { (a, b) -> b.time <= a.time } ||
            bars.any { it.time <= 0L || !listOf(it.open, it.high, it.low, it.close).all { v -> v.isFinite() && v > 0.0 } ||
                it.high < max(it.open, it.close) || it.low > min(it.open, it.close) })
            return blocked("ترتیب یا OHLC کندل‌ها نامعتبر است")
        if (MarketHours.closedFor(symbol, asOf) || MarketHours.closedFor(symbol, clock))
            blockers += "بازار بسته است"
        if (clock - asOf !in 0L..(interval.millis + 90_000L))
            blockers += "کندل پایه برای ورود تازه نیست"
        val atrSeries = Indicators.atr(bars, 14)
        val atr = atrSeries.lastOrNull() ?: return blocked("ATR معتبر نیست")
        if (!atr.isFinite() || atr <= 0.0) return blocked("ATR صفر یا نامعتبر است")
        val recentAtr = atrSeries.takeLast(60).filterNotNull().sorted()
        val medianAtr = recentAtr.getOrNull(recentAtr.size / 2) ?: atr
        val adx = Indicators.adx(bars, 14).lastOrNull() ?: return blocked("ADX معتبر نیست")
        if (!adx.isFinite()) return blocked("ADX معتبر نیست")
        if (medianAtr <= 0.0 || atr > medianAtr * 2.5) blockers += "قفل مطلق: جهش غیرعادی بازار"
        if (atr < medianAtr * 0.6 || atr / last.close < 0.00001)
            blockers += "قفل مطلق: بازار بی‌جان"
        val range = when (mode) {
            CategoryStrategy.RANGE -> true
            CategoryStrategy.TREND -> false
            CategoryStrategy.HYBRID -> adx < 20.0
        }
        if (mode == CategoryStrategy.TREND && adx < 20.0) blockers += "استراتژی روندگیر در بازار بدون روند قفل است"
        if (mode == CategoryStrategy.RANGE && adx >= 25.0) blockers += "استراتژی رنج‌گیر در بازار رونددار قفل است"

        val ichi = Ichimoku.compute(bars, 8, 24, 72, 24)
        val cloud = ichi.cloudAt(bars.lastIndex) ?: return blocked("ابر قابل‌مشاهده هنوز آماده نیست")
        val tenkan = ichi.tenkan.lastOrNull() ?: return blocked("تنکان آماده نیست")
        val kijun = ichi.kijun.lastOrNull() ?: return blocked("کیجون آماده نیست")
        val ema = Indicators.ema(bars.map { it.close }, 200).lastOrNull() ?: return blocked("EMA200 آماده نیست")
        val rsi = Indicators.rsi(bars, 7).lastOrNull() ?: return blocked("RSI آماده نیست")
        val macd = Indicators.macdHistogram(bars).lastOrNull() ?: return blocked("MACD آماده نیست")
        val prevMacd = Indicators.macdHistogram(bars).getOrNull(bars.lastIndex - 1) ?: 0.0
        val support = bars.dropLast(1).takeLast(20).minOf { it.low }
        val resistance = bars.dropLast(1).takeLast(20).maxOf { it.high }
        val stack = intervals.map { target ->
            val supplied = external[target]
            val source = when {
                target == interval -> bars
                supplied != null -> supplied.filter { it.closed && it.time + target.millis <= asOf }
                target.millis > interval.millis && target.millis % interval.millis == 0L ->
                    MtfAnalyzer.resample(bars, target, interval).filter { it.closed }
                else -> emptyList()
            }
            target to frameBias(source, target, asOf)
        }
        val missing = stack.filter { it.second == null }.map { it.first.label }
        if (missing.isNotEmpty()) blockers += "پشتهٔ ۷ تایم‌فریمی کامل نیست: ${missing.joinToString("، ")}؛ کندل ریزتر ساخته نمی‌شود"

        fun score(side: SignalAction): Pair<Int, List<ConfluenceItem>> {
            val buy = side == SignalAction.BUY
            val trend = if (range) {
                (if ((if (buy) last.close <= ema else last.close >= ema)) 12 else 0) +
                    (if (last.close in min(cloud.first, cloud.second)..max(cloud.first, cloud.second)) 13 else 0)
            } else {
                (if ((if (buy) last.close > ema else last.close < ema)) 12 else 0) +
                    (if ((if (buy) last.close > max(cloud.first, cloud.second) && tenkan > kijun
                          else last.close < min(cloud.first, cloud.second) && tenkan < kijun)) 13 else 0)
            }
            val momentum = (if ((if (range) (if (buy) rsi < 42 else rsi > 58)
                                 else (if (buy) rsi in 50.0..75.0 else rsi in 25.0..50.0))) 12 else 0) +
                (if ((if (range) (if (buy) macd > prevMacd else macd < prevMacd)
                     else (if (buy) macd > 0 else macd < 0))) 13 else 0)
            val touch = if (buy) last.low <= support + atr * 1.5 && last.close > support
                        else last.high >= resistance - atr * 1.5 && last.close < resistance
            val breakout = if (buy) last.close > resistance && last.close > last.open
                           else last.close < support && last.close < last.open
            val priceAction = if ((if (range) touch else touch || breakout) &&
                (if (buy) last.close > last.open else last.close < last.open)) 25
                else if (if (buy) last.close > last.open else last.close < last.open) 12 else 0
            val expected = if (range) SignalAction.NO_TRADE else side
            val aligned = stack.count { it.second == expected }
            val opposite = stack.count { it.second != null && it.second != expected && it.second != SignalAction.NO_TRADE }
            val tfScore = if (missing.isNotEmpty()) 0 else when {
                aligned == 7 -> 25
                aligned == 6 && opposite == 0 -> 20
                aligned >= 5 && opposite <= 1 -> 15
                else -> 0
            }
            fun item(label: String, points: Int, detail: String, unknown: Boolean = false) =
                ConfluenceItem(label, points == 25 && !unknown, detail,
                    if (unknown) ConfluenceStatus.UNKNOWN else if (points > 0) ConfluenceStatus.CONFIRMED
                    else ConfluenceStatus.CONFLICT, points)
            val items = listOf(
                item("ساختار روند · EMA + ایچیموکو", trend,
                    "${mode.label} · EMA200=$ema · ابر=${cloud.first}/${cloud.second} · تنکان=$tenkan کیجون=$kijun · $trend/25"),
                item("مومنتوم · RSI + MACD", momentum, "RSI=$rsi · MACD=$macd · $momentum/25"),
                item("پرایس‌اکشن · حمایت/مقاومت", priceAction,
                    "حمایت=$support · مقاومت=$resistance · ATR=$atr · $priceAction/25"),
                item("پشتهٔ ۷ تایم‌فریمی", tfScore,
                    stack.joinToString(" · ") { "${it.first.label}:${it.second?.name ?: "نامعلوم"}" } + " · $tfScore/25",
                    missing.isNotEmpty()),
            )
            return (trend + momentum + priceAction + tfScore) to items
        }
        val long = score(SignalAction.BUY)
        val short = score(SignalAction.SELL)
        val selected = if (long.first >= short.first) SignalAction.BUY to long else SignalAction.SELL to short
        val side = selected.first
        val total = selected.second.first.toDouble()
        if (total < threshold) blockers += "امتیاز $total/۱۰۰ از آستانهٔ $threshold کمتر است"
        val stop = if (side == SignalAction.BUY) min(support, last.close - atr) else max(resistance, last.close + atr)
        val risk = abs(last.close - stop)
        val target = if (side == SignalAction.BUY) last.close + risk * 1.5 else last.close - risk * 1.5
        if (stop <= 0.0 || target <= 0.0 || risk <= 0.0 || !risk.isFinite()) blockers += "طرح استاپ/هدف معتبر نیست"
        return Signal(if (blockers.isEmpty()) side else SignalAction.NO_TRADE, total,
            entry = if (blockers.isEmpty()) last.close else null,
            stopLoss = if (blockers.isEmpty()) stop else null,
            takeProfit = if (blockers.isEmpty()) target else null,
            riskReward = if (blockers.isEmpty()) 1.5 else null,
            reasons = listOf("موتور واحد چهارلایه · ${mode.label} · $total/۱۰۰"),
            blockers = blockers, confluence = selected.second.second,
            interval = interval, barTime = last.time)
    }

    private fun frameBias(input: List<Candle>, interval: Interval, asOf: Long): SignalAction? {
        val bars = input.takeLast(400)
        if (bars.size < MIN_BARS || bars.zipWithNext().any { (a, b) -> b.time <= a.time }) return null
        val last = bars.last()
        if (!last.closed || last.time + interval.millis > asOf ||
            asOf - (last.time + interval.millis) > interval.millis * 3 ||
            bars.any { !listOf(it.open, it.high, it.low, it.close).all { value -> value.isFinite() && value > 0.0 } ||
                it.high < max(it.open, it.close) || it.low > min(it.open, it.close) }) return null
        val ichi = Ichimoku.compute(bars, 8, 24, 72, 24)
        val cloud = ichi.cloudAt(bars.lastIndex) ?: return null
        val ema = Indicators.ema(bars.map { it.close }, 200).lastOrNull() ?: return null
        return when {
            last.close > max(cloud.first, cloud.second) && last.close > ema -> SignalAction.BUY
            last.close < min(cloud.first, cloud.second) && last.close < ema -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
    }
}
