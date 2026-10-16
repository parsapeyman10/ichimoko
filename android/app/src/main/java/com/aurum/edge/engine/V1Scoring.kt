package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.Interval
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.MarketHours
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The scoring implementation behind SignalEngine.evaluate; never an alternative entry path.
 * Four independent layers, each worth 25 points. Only M5/M15/H1 vote on alignment.
 * Missing timeframes are UNKNOWN, never
 * synthesized by splitting or aggregating bars from another interval.
 */
internal object V1Scoring {
    val intervals = listOf(Interval.M5, Interval.M15, Interval.H1)
    private const val MIN_BARS = HistoryPolicy.LIVE_MIN_CANDLES // 1500 genuine closed M5 bars

    fun minBarsFor(interval: Interval): Int = when (interval) {
        Interval.M5 -> HistoryPolicy.LIVE_MIN_CANDLES
        Interval.M15 -> HistoryPolicy.RESAMPLED_M15_CANDLES
        Interval.H1 -> HistoryPolicy.RESAMPLED_H1_CANDLES
        else -> HistoryPolicy.LIVE_MIN_CANDLES
    }

    fun evaluate(input: List<Candle>, interval: Interval, symbol: String, threshold: Double,
                 mode: CategoryStrategy, external: Map<Interval, List<Candle>>): Signal {
        val clock = System.currentTimeMillis()
        val bars = input.filter { it.closed && it.time + interval.millis <= clock }
            .takeLast(MIN_BARS)
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
        // The selected chart may use other intervals for analysis; only a closed 5m
        // base bar can produce an actionable NEW paper trade, for every asset class.
        if (interval != Interval.M5) blockers += "ورود کاغذی فقط روی کندل بستهٔ ۵ دقیقه (5m) مجاز است"
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
        if (mode == CategoryStrategy.TREND && adx < 20.0) blockers += "استراتژی روندگیر در بازار بدون روند قفل است"
        if (mode == CategoryStrategy.RANGE && adx >= 25.0) blockers += "استراتژی رنج‌گیر در بازار رونددار قفل است"

        val setting = SignalEngine.ichimokuSetting(interval)
        val ichi = Ichimoku.compute(bars, setting.tenkan, setting.kijun, setting.spanB, setting.kijun)
        val buySetup = IchimokuEntrySetup.assess(bars, ichi, atrSeries, interval, SignalAction.BUY)
        val sellSetup = IchimokuEntrySetup.assess(bars, ichi, atrSeries, interval, SignalAction.SELL)
        val ichiRead = IchimokuAlignment.read(bars, ichi, atr)
            ?: return blocked("اجزای هم‌زمان ایچیموکو آماده نیستند")
        // Non-crypto only: crypto is exclusively routed through CryptoCompressionEngine.
        val range = when (mode) {
            CategoryStrategy.RANGE -> true
            CategoryStrategy.TREND -> false
            CategoryStrategy.HYBRID -> adx < 20.0
        }
        val ema = Indicators.ema(bars.map { it.close }, 200).lastOrNull() ?: return blocked("EMA200 آماده نیست")
        val rsi = Indicators.rsi(bars, 7).lastOrNull() ?: return blocked("RSI آماده نیست")
        val macdValues = Indicators.macdHistogram(bars)
        val macd = macdValues.lastOrNull() ?: return blocked("MACD آماده نیست")
        val prevMacd = macdValues.getOrNull(bars.lastIndex - 1) ?: return blocked("MACD قبلی آماده نیست")
        if (!macd.isFinite() || !prevMacd.isFinite()) return blocked("MACD نامعتبر است")
        val support = bars.dropLast(1).takeLast(20).minOf { it.low }
        val resistance = bars.dropLast(1).takeLast(20).maxOf { it.high }
        // The M5 entry path must never read an M15/H1 bar that closed after its own base
        // candle, even if the wall clock has advanced. For non-M5 analysis views only,
        // allow the most recent closed frame at the live clock when the base bar is fresh.
        val frameAsOf = if (interval != Interval.M5 &&
            clock - asOf in 0L..(interval.millis + 90_000L)) clock else asOf
        val resolvedExternal = if (external.isEmpty() && interval == Interval.M5) {
            mapOf(
                Interval.M15 to MtfAnalyzer.convertFromM5(bars, Interval.M15),
                Interval.H1 to MtfAnalyzer.convertFromM5(bars, Interval.H1),
            )
        } else external
        val frameCounts = mutableMapOf<Interval, Int>()
        val stack = intervals.map { target ->
            val reqBars = minBarsFor(target)
            val source = if (target == interval) bars else resolvedExternal[target].orEmpty()
                .filter { it.closed && it.time + target.millis <= frameAsOf }
                .sortedBy { it.time }.takeLast(MIN_BARS)
            frameCounts[target] = source.size
            target to frameBias(source, target, frameAsOf, reqBars)
        }
        val missing = stack.filter { it.second == null }.map { "${it.first.label}(${frameCounts[it.first]}/${minBarsFor(it.first)})" }
        if (missing.isNotEmpty()) blockers += "پشتهٔ ۳ تایم‌فریمی کامل نیست: ${missing.joinToString("، ")}؛ هیچ بازه‌ای حدس زده نمی‌شود"

        fun score(side: SignalAction): Pair<Int, List<ConfluenceItem>> {
            val buy = side == SignalAction.BUY
            val ichiOk = if (range) ichiRead.rangeAligned(last.close)
                         else ichiRead.trendAligned(side, last.close)
            val trend = (if (if (range) (if (buy) last.close <= ema else last.close >= ema)
                         else (if (buy) last.close > ema else last.close < ema)) 12 else 0) +
                (if (ichiOk) 13 else 0)
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
            // Sideways frames are neutral context, not confirmation of either entry side.
            val tfScore = directionalAlignment(stack.map { it.second }, side)
            fun item(label: String, points: Int, detail: String, unknown: Boolean = false) =
                ConfluenceItem(label, points == 25 && !unknown, detail,
                    when {
                        unknown -> ConfluenceStatus.UNKNOWN
                        points == 25 -> ConfluenceStatus.CONFIRMED
                        points > 0 -> ConfluenceStatus.PARTIAL
                        else -> ConfluenceStatus.CONFLICT
                    }, points)
            val items = listOf(
                item("ساختار روند · EMA + ایچیموکو", trend,
                    "${mode.label} · EMA200=$ema · " +
                        "${ichiRead.detail(side)} · هم‌راستایی ایچیموکو=${if (ichiOk) "تأیید" else "رد"} · $trend/25"),
                item("مومنتوم · RSI + MACD", momentum, "RSI=$rsi · MACD=$macd · $momentum/25"),
                item("پرایس‌اکشن · حمایت/مقاومت", priceAction,
                    "حمایت=$support · مقاومت=$resistance · ATR=$atr · $priceAction/25"),
                item("پشتهٔ ۳ تایم‌فریمی", tfScore,
                    stack.joinToString(" · ") { "${it.first.label}:${it.second?.name ?: "نامعلوم"} (${frameCounts[it.first]}/${minBarsFor(it.first)} کندل)" } + " · $tfScore/25",
                    missing.isNotEmpty()),
            )
            return (trend + momentum + priceAction + tfScore) to items
        }
        val long = score(SignalAction.BUY)
        val short = score(SignalAction.SELL)
        // Equal scores have no directional edge; keep evidence visible but never default BUY.
        val side = chooseSide(long.first, short.first)
        val selected = if (side == SignalAction.SELL) SignalAction.SELL to short else SignalAction.BUY to long
        if (side == SignalAction.NO_TRADE) blockers += "امتیاز خرید و فروش برابر است؛ جهت ورود نامعلوم"
        // This is the Ichimoku half of the FIRST layer, not a parallel strategy. Never
        // authorize a trend entry on the EMA half alone while the displaced components
        // disagree or the lines/price are over-extended relative to real ATR.
        val selectedSetup = if (side == SignalAction.BUY) buySetup else sellSetup
        if (selectedSetup == null) blockers += "ورود فقط پس از کراس تنکان/کیجون در جهت معامله، ابر نازک و فشردگی ۳ از ۵ کندل بسته مجاز است"
        if (side != SignalAction.NO_TRADE && !range &&
            !ichiRead.trendAligned(side, last.close))
            blockers += "اجزای ایچیموکو هم‌جهت/هم‌فاصله نیستند (ابر، تنکان/کیجون، چیکو و ATR)"
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
            interval = interval, barTime = last.time, ichimokuEntry = if (blockers.isEmpty()) selectedSetup else null)
    }

    /** A neutral or unknown frame cannot vote for BUY or SELL. Only three real frames vote. */
    internal fun directionalAlignment(frames: List<SignalAction?>, side: SignalAction): Int {
        if (frames.size != intervals.size || frames.any { it == null } || side == SignalAction.NO_TRADE)
            return 0
        val aligned = frames.count { it == side }
        val opposite = frames.count { it != side && it != SignalAction.NO_TRADE }
        return when {
            aligned == 3 -> 25
            aligned == 2 && opposite == 0 -> 20
            else -> 0
        }
    }

    internal fun chooseSide(buyPoints: Int, sellPoints: Int): SignalAction = when {
        buyPoints > sellPoints -> SignalAction.BUY
        sellPoints > buyPoints -> SignalAction.SELL
        else -> SignalAction.NO_TRADE
    }

    private fun frameBias(input: List<Candle>, interval: Interval, asOf: Long, reqBars: Int = minBarsFor(interval)): SignalAction? {
        val bars = input.takeLast(MIN_BARS)
        if (bars.size < reqBars || bars.zipWithNext().any { (a, b) -> b.time <= a.time }) return null
        val last = bars.last()
        if (!last.closed || last.time + interval.millis > asOf ||
            asOf - (last.time + interval.millis) > interval.millis * 3 ||
            bars.any { !listOf(it.open, it.high, it.low, it.close).all { value -> value.isFinite() && value > 0.0 } ||
                it.high < max(it.open, it.close) || it.low > min(it.open, it.close) }) return null
        val setting = SignalEngine.ichimokuSetting(interval)
        val ichi = Ichimoku.compute(bars, setting.tenkan, setting.kijun, setting.spanB, setting.kijun)
        val atr = Indicators.atr(bars, 14).lastOrNull() ?: return null
        val read = IchimokuAlignment.read(bars, ichi, atr) ?: return null
        val emaPeriod = if (bars.size >= 200) 200 else 50
        val ema = Indicators.ema(bars.map { it.close }, emaPeriod).lastOrNull() ?: return null
        return when {
            last.close > ema && read.trendAligned(SignalAction.BUY, last.close) -> SignalAction.BUY
            last.close < ema && read.trendAligned(SignalAction.SELL, last.close) -> SignalAction.SELL
            else -> SignalAction.NO_TRADE
        }
    }
}
