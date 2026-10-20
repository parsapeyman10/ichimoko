package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.CryptoBreakoutEvidence
import com.aurum.edge.core.CryptoCompressionPolicy
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The ONLY crypto signal strategy. Paper-only; no exchange orders. M15/H1 are independent
 * confirmations, never entry candles. This does not backfill missing trades, volumes or quotes.
 * OHLC cannot prove the intrabar order in which several lines were crossed: demand a body
 * that starts on the opposite side of ALL levels and closes past ALL of them; never claim a
 * tick-by-tick sequence from a candle. Standard 9/26/52 with a 26-bar display shift.
 */
internal object CryptoCompressionEngine {
    private const val WINDOW = HistoryPolicy.LIVE_MIN_CANDLES
    private const val MAX_POINTS = 26
    private val labels = listOf("فشردگی · ۲۰ کندل", "انفجار · حجم/ADX/چیکو",
        "فیلتر کلان · ۵۲/۱۰۳ و M15/H1", "ورود · کندل/ریسک/سشن")

    private fun blocked(interval: Interval, bars: List<Candle>, reason: String, score: Int = 0): Signal =
        Signal(SignalAction.NO_TRADE, score * 100.0 / MAX_POINTS,
            blockers = listOf(reason), reasons = listOf("فشردگی ← انفجار · $score/$MAX_POINTS"),
            interval = interval, barTime = bars.lastOrNull()?.time ?: 0L)

    /** Twenty low-energy bars are a WATCH state, not permission to open a position. */
    fun evaluate(input: List<Candle>, interval: Interval, symbol: String,
                 frames: Map<Interval, List<Candle>>, now: Long = System.currentTimeMillis()): Signal {
        val bars = input.filter { it.closed && it.time + interval.millis <= now }.takeLast(WINDOW)
        // BTC/USDT has its own multi-engine model (BtcAresEngine). Refusing it here is what keeps
        // the two crypto models from both scoring the same bar.
        if (com.aurum.edge.core.V1Universe.normalize(symbol) == BtcAresEngine.SYMBOL)
            return blocked(interval, bars, "BTC/USDT با موتور BTC-ARES ارزیابی می‌شود، نه فشردگی")
        if (interval != Interval.M5) return blocked(interval, bars, "ورود رمزارز فقط بر پایهٔ کندل بستهٔ 5m است")
        if (bars.size != WINDOW || bars.any { !valid(it) } ||
            bars.zipWithNext().any { (a,b) -> b.time - a.time != interval.millis } ||
            now - (bars.last().time + interval.millis) !in 0L..(interval.millis + 90_000L))
            return blocked(interval, bars, "${WINDOW} کندل 5m بستهٔ پیوسته و تازه با حجم واقعی لازم است")
        // A venue may publish real zero-execution bars. They belong in price history,
        // but a quiet recent window is not evidence for a volume-confirmed entry.
        if (bars.takeLast(40).any { it.volume <= 0.0 })
            return blocked(interval, bars, "حجم معاملات واقعی در ۴۰ کندل اخیر 5m کامل نیست")
        val last = bars.lastIndex
        val asOf = bars[last].time + interval.millis
        val ichi = Ichimoku.compute(bars, 9, 26, 52, 26)
        val longLine = Indicators.donchianMid(bars, 103)
        val atrs = Indicators.atr(bars, 14)
        val adxs = Indicators.adx(bars, 14)
        // M15/H1 confirmation must come from independently fetched venue candles;
        // a missing frame blocks below instead of being resampled from M5.
        val resolvedFrames = frames
        val readyFrames = listOf(Interval.M15, Interval.H1).associateWith { frame ->
            val minFrameSize = HistoryPolicy.minSize(frame)
            resolvedFrames[frame].orEmpty().filter { it.closed && it.time + frame.millis <= asOf }
                .takeLast(WINDOW).takeIf { series ->
                    series.size >= minFrameSize && series.all(::valid) &&
                        series.zipWithNext().all { (a,b) -> b.time - a.time == frame.millis } &&
                        asOf - (series.last().time + frame.millis) in 0L..(2 * frame.millis)
                }
        }
        if (readyFrames.values.any { it == null })
            return blocked(interval, bars, "تأیید مستقل کندل‌های بستهٔ M15 و H1 کامل/تازه نیست")

        if (readyFrames.values.any { it!!.last().volume <= 0.0 })
            return blocked(interval, bars, "حجم معاملات واقعی کندل بستهٔ M15/H1 موجود نیست")

        // Evaluate a just-closed breakout, or the immediately following closed pullback
        // after an overlarge breakout. No older breakout may be chased from a later candle.
        val attempts = listOf(last to false, last - 1 to true)
        var watch: Signal? = null
        for ((breakIndex, pullback) in attempts) {
            val atr = atrs[breakIndex]?.takeIf { it.isFinite() && it > 0 } ?: continue
            val a = ichi.cloudAt(breakIndex) ?: continue
            val cloudTop = max(a.first, a.second)
            val cloudBottom = min(a.first, a.second)
            val t = ichi.tenkan[breakIndex] ?: continue
            val k = ichi.kijun[breakIndex] ?: continue
            val line52 = ichi.senkouB[breakIndex] ?: continue // computed NOW, not future observed price
            val futureA = ichi.senkouA[breakIndex] ?: continue
            val line103 = longLine[breakIndex] ?: continue
            val bias = when {
                line52 - line103 >= 0.5 * atr -> SignalAction.BUY
                line103 - line52 >= 0.5 * atr -> SignalAction.SELL
                else -> SignalAction.NO_TRADE
            }
            val kind = compressionType(bars, breakIndex, atr)
            val flat = (breakIndex - 20 until breakIndex).all { j ->
                val prior = ichi.kijun[j - 1]
                val current = ichi.kijun[j]
                val unit = atrs[j]
                prior != null && current != null && unit != null && unit > 0 &&
                    abs(current - prior) < 0.08 * unit
            }
            val glued = (breakIndex - 20 until breakIndex).all { j ->
                val unit = atrs[j]
                val tenkan = ichi.tenkan[j]
                val kijun = ichi.kijun[j]
                unit != null && unit > 0 && tenkan != null && kijun != null &&
                    abs(tenkan - kijun) < 0.25 * unit
            }
            val cloud = (breakIndex - 20 until breakIndex).all { j ->
                val visible = ichi.cloudAt(j)
                val unit = atrs[j]
                visible != null && unit != null && unit > 0 &&
                    (abs(visible.first - visible.second) < 0.4 * unit ||
                        min(abs(bars[j].close - visible.first), abs(bars[j].close - visible.second)) < 0.3 * unit)
            }
            val quietAtr = (breakIndex - 20 until breakIndex).all { j ->
                val unit = atrs[j]
                val history = atrs.subList(j - 50, j).filterNotNull()
                unit != null && history.size == 50 && history.all { it > 0 && it.isFinite() } &&
                    unit <= 0.6 * history.average()
            }
            val quietAdx = (breakIndex - 20 until breakIndex).all { j ->
                adxs[j]?.let { it.isFinite() && it < 18 } == true
            }
            val quietVolume = (breakIndex - 20 until breakIndex).all { j ->
                val avg = bars.subList(j - 30, j).map { it.volume }.average()
                avg.isFinite() && avg > 0 && bars[j].volume < 0.7 * avg
            }
            val compression = flat && glued && cloud && quietAtr && quietAdx && quietVolume
            val compressionPoints = (if (flat) 2 else 0) + (if (glued) 2 else 0) +
                (if (cloud) 1 else 0) + (if (quietAtr) 2 else 0) +
                (if (quietAdx) 1 else 0) + (if (quietVolume) 1 else 0) + 3 // twenty bars checked; full compression = 12 watch points
            if (!compression) {
                watch = blocked(interval, bars, "فشردگی ۲۰ کندل کامل نیست: کیجون/ابر/ATR/ADX/حجم", compressionPoints)
                continue
            }
            if (bias == SignalAction.NO_TRADE) return blocked(interval, bars,
                "واچ‌لیست: فشردگی $kind؛ خطوط ۵۲/۱۰۳ در تعادل‌اند", compressionPoints)
            val preVolume = bars.subList(breakIndex - 30, breakIndex).map { it.volume }.average()
            val candle = bars[breakIndex]
            val range = candle.high - candle.low
            val body = abs(candle.close - candle.open)
            val upperWick = candle.high - max(candle.open, candle.close)
            val lowerWick = min(candle.open, candle.close) - candle.low
            val strongBody = range > 0 && body >= 0.6 * range &&
                upperWick <= 0.2 * range && lowerWick <= 0.2 * range
            val levels = listOf(t, k, if (bias == SignalAction.BUY) cloudTop else cloudBottom)
            val crossing = strongBody && if (bias == SignalAction.BUY)
                candle.open <= levels.min() && candle.close > levels.max() &&
                    candle.close > bars.subList(breakIndex - 20, breakIndex).maxOf { it.high }
            else candle.open >= levels.max() && candle.close < levels.min() &&
                candle.close < bars.subList(breakIndex - 20, breakIndex).minOf { it.low }
            val volume = preVolume > 0 && candle.volume >= 2.5 * preVolume &&
                (kind != "WEDGE" && kind != "BASE" || candle.volume >= 3.0 * preVolume)
            val adx = adxs[breakIndex]
            val adxOk = adx != null && adx > 22 && adxs[breakIndex - 1]?.let { adx > it } == true
            val directionIndex = DirectionalIndex.at(bars, breakIndex)
            val structure = (if (bias == SignalAction.BUY) t > k && futureA > line52 &&
                directionIndex?.let { it.plus > it.minus } == true
                else t < k && line52 > futureA &&
                    directionIndex?.let { it.minus > it.plus } == true) &&
                IchimokuEntrySetup.assess(bars.take(breakIndex + 1),
                    Ichimoku.compute(bars.take(breakIndex + 1), 9, 26, 52, 26),
                    atrs.take(breakIndex + 1), Interval.M5, bias) != null
            val lag = breakIndex - 26
            val oldBars = bars.subList(lag - 2, lag + 3)
            val oldClouds = (lag - 2..lag + 2).map { ichi.cloudAt(it) }
            val chikou = oldClouds.all { it != null } && if (bias == SignalAction.BUY)
                candle.close > max(oldBars.maxOf { it.high }, oldClouds.filterNotNull().maxOf { max(it.first,it.second) }) + 0.1*atr
            else candle.close < min(oldBars.minOf { it.low }, oldClouds.filterNotNull().minOf { min(it.first,it.second) }) - 0.1*atr
            val slope = (line52 - (ichi.senkouB[breakIndex-3] ?: line52)) / 3.0
            val previousMove = bars[breakIndex-21].close - bars[breakIndex-40].close
            val previousTrendOk = kind != "FLAG" ||
                (if (bias == SignalAction.BUY) previousMove > 0 else previousMove < 0)
            val macro = previousTrendOk &&
                (if (bias == SignalAction.BUY) slope >= -0.08*atr && candle.close > max(line52,line103)
                else slope <= 0.08*atr && candle.close < min(line52,line103)) &&
                listOf(Interval.M15, Interval.H1).all { frame ->
                    val source = readyFrames.getValue(frame)!!
                    val h = Ichimoku.compute(source, 9, 26, 52, 26)
                    val hi = source.lastIndex
                    val ht = h.tenkan[hi] ?: return@all false
                    val hk = h.kijun[hi] ?: return@all false
                    val hc = h.cloudAt(hi) ?: return@all false
                    if (bias == SignalAction.BUY) source[hi].close > hk && ht > hk &&
                        (frame != Interval.H1 || source[hi].close > max(hc.first,hc.second))
                    else source[hi].close < hk && ht < hk &&
                        (frame != Interval.H1 || source[hi].close < min(hc.first,hc.second))
                }
            val entry = bars[last].close
            val openPullback = if (!pullback) true else {
                val retrace = bars[last]
                val lastTenkan = ichi.tenkan[last] ?: 0.0
                if (bias == SignalAction.BUY) retrace.low <= lastTenkan + 0.25*atr &&
                    retrace.close >= lastTenkan && retrace.close > retrace.open
                else retrace.high >= lastTenkan - 0.25*atr &&
                    retrace.close <= lastTenkan && retrace.close < retrace.open
            }
            val nonChase = abs(entry - k) <= 2*atr &&
                (if (bias == SignalAction.BUY) entry - cloudTop <= 2.5*atr
                 else cloudBottom - entry <= 2.5*atr) &&
                (range <= 1.8*atr || pullback) && openPullback &&
                abs(entry - candle.close) <= 1.8*atr
            val session = CryptoCompressionPolicy.londonOrNewYork(asOf)
            val points = compressionPoints + (if (crossing) 2 else 0) + (if (volume) 2 else 0) +
                (if (adxOk) 2 else 0) + (if (chikou) 1 else 0) + (if (structure) 2 else 0) +
                (if (range <= 1.8*atr || pullback) 1 else 0) + (if (macro) 4 else 0)
            val setup = IchimokuEntrySetup.assess(bars, ichi, atrs, Interval.M5, bias)
            val ready = crossing && volume && adxOk && chikou && structure && macro && nonChase &&
                session && setup != null && points >= 16
            val reason = when {
                !crossing -> "عبور بدنهٔ کندل از خطوط/ابر و شکست محدوده تأیید نشد"
                !volume -> "حجم واقعی شکست کمتر از ۲٫۵ برابر میانگین ۳۰ کندل است"
                !adxOk -> "ADX بالای ۲۲ و صعودی نیست"
                !chikou -> "چیکو در محل تاریخی از قیمت و ابر آزاد نیست"
                !structure || setup == null -> "کراس تنکان/کیجون یا ابر آینده هم‌جهت نیست"
                !macro -> "جهت ۵۲/۱۰۳، شیب ۵۲، M15 یا H1 مخالف/نامعلوم است"
                !nonChase -> "قیمت دور از کیجون/ابر است یا کندل بزرگ هنوز پولبک ندارد"
                !session -> "خارج از ساعات پرحجم لندن/نیویورک"
                else -> "امتیاز کافی نیست"
            }
            if (!ready) {
                watch = blocked(interval, bars, "واچ‌لیست: فشردگی $kind؛ $reason", points)
                continue
            }
            val basis = if (bias == SignalAction.BUY)
                listOf(k, cloudBottom).filter { it < entry }.maxOrNull() ?: return blocked(interval, bars, "استاپ ساختاری موجود نیست")
            else listOf(k, cloudTop).filter { it > entry }.minOrNull() ?: return blocked(interval, bars, "استاپ ساختاری موجود نیست")
            val structural = if (bias == SignalAction.BUY) basis - 0.1*atr else basis + 0.1*atr
            val risk = max(abs(entry - structural), 0.8*atr)
            if (risk > 2*atr || risk/entry !in 0.0001..0.25) {
                watch = blocked(interval, bars, "استاپ ساختاری بیرون بودجهٔ ۰٫۸ تا ۲ ATR است", points)
                continue
            }
            val stop = if (bias == SignalAction.BUY) entry - risk else entry + risk
            val target = if (bias == SignalAction.BUY) entry + 3*risk else entry - 3*risk
            if (stop <= 0 || target <= 0) return blocked(interval, bars, "طرح حد ضرر/هدف نامعتبر است")
            val evidence = CryptoBreakoutEvidence(bias, bars[last].time, bars[breakIndex].time,
                bars[breakIndex-20].time, atr, points, kind)
            val items = labels.map { ConfluenceItem(it, true, "$kind · ${bars[breakIndex].time} · امتیاز $points/26",
                scorePercent = 25) }
            return Signal(bias, 100.0, entry, stop, target, 3.0,
                reasons = listOf("فشردگی ← انفجار · $kind · $points/26 · 5m؛ M15/H1 تأیید"),
                confluence = items, interval = Interval.M5, barTime = bars[last].time,
                cryptoBreakout = evidence,
                ichimokuEntry = setup)
        }
        return watch ?: blocked(interval, bars, "هیچ فشردگی معتبر با کندل بسته یافت نشد")
    }

    private fun valid(bar: Candle): Boolean = bar.time > 0 && bar.closed &&
        listOf(bar.open,bar.high,bar.low,bar.close,bar.volume).all { it.isFinite() } &&
        min(bar.open,bar.close) > 0 && bar.volume >= 0 &&
        bar.high >= max(bar.open,bar.close) && bar.low <= min(bar.open,bar.close) && bar.low > 0

    private fun compressionType(bars: List<Candle>, at: Int, atr: Double): String {
        val before = bars.subList(at - 40, at - 20)
        val move = before.last().close - before.first().close
        if (abs(move) > 2*atr) return "FLAG"
        val left = bars.subList(at - 20, at - 10)
        val right = bars.subList(at - 10, at)
        if (right.maxOf { it.high } < left.maxOf { it.high } &&
            right.minOf { it.low } > left.minOf { it.low }) return "WEDGE"
        return "BASE"
    }
}
