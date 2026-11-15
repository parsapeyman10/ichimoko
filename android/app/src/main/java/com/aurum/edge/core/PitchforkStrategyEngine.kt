package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * پورت Kotlin از فایل مرجع کاربر: `pitchfork_strategy.py`
 * (Gordon DeRoos — *Trading with the Pitchfork*، روش خط میانهٔ اندروز).
 *
 * دو استراتژی کتاب عیناً پیاده شده‌اند:
 * **A) مینی‌خط میانه (ادامهٔ روند):** واگرایی معکوس → کراس کلوز از SMA ۵ → چنگال مینی از کلوزها
 * (P3 = اولین کلوز بالای میانگین، P2 = پایین‌ترین کلوز بازهٔ زیر میانگین، P1 = بالاترین کلوز بازهٔ بالای
 * میانگین) با شیب منفی → عبور از موازی بالایی (بیش از نیمی از دامنهٔ کندل آن‌سوی خط) → موازی لغزان از کف
 * همان کندل → ورود با عبور از موازی لغزان؛ حد ضرر «چند تیک» زیر موازی بالایی.
 * **B) شکست قیمت (Price failure):** چنگال نزولی که قیمت هرگز به خط میانه‌اش نرسیده → عبور از موازی
 * بالایی → موازی لغزان → خط هاگوپیان (P1→P3) → واگرایی استاندارد → ورود چند تیک بالای هاگوپیان؛
 * حد ضرر زیر کف بازگشتی.
 * هر دو برای فروش با آینه‌کردن قیمت اجرا می‌شوند (همان ترفند فایل مرجع).
 *
 * استقلال کامل: این موتور هیچ شرط ایچیموکو، هیچ ست/امتیاز کین، هیچ گیت کتاب و هیچ لایهٔ عصبی را
 * نمی‌بیند. **تنها اشتراک با بقیهٔ سیستم، لایهٔ سرمایه است** (ریسک دقیقاً ۵ دلار و سود `R × ۵`).
 * فایل مرجع سایز می‌دهد (`risk_pct` و `qty`)؛ آن بخش عمداً حذف شده چون سایز در این اپ فقط از لایهٔ
 * سرمایهٔ ۵ دلاری می‌آید.
 */
data class PitchforkPoint(
    val index: Int,
    val price: Double,
    /** باری که سوینگ بدون نگاه به آینده تأیید می‌شود. */
    val confirmIndex: Int,
)

/** چنگال اندروز: خط میانه از P1 و وسط P2–P3؛ دو موازی از P2 و P3. */
data class PitchforkFork(
    val p1: PitchforkPoint,
    val p2: PitchforkPoint,
    val p3: PitchforkPoint,
    val slope: Double,
) {
    private fun through(point: PitchforkPoint, x: Double): Double = point.price + slope * (x - point.index)
    fun median(x: Double): Double = through(p1, x)
    fun upper(x: Double): Double = max(through(p2, x), through(p3, x))
    fun lower(x: Double): Double = min(through(p2, x), through(p3, x))
    val descriptionFa: String
        get() = "P1 #${p1.index} (${price(p1.price)}) · P2 #${p2.index} (${price(p2.price)}) · " +
            "P3 #${p3.index} (${price(p3.price)}) · شیب ${"%.5f".format(Locale.US, slope)}"

    private fun price(value: Double) = "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.')
}

enum class PitchforkSignalKind(val labelFa: String) {
    MINI_MEDIAN("مینی‌خط میانه — ادامهٔ روند"),
    PRICE_FAILURE("شکست قیمت (Price Failure) — تغییر روند"),
}

data class PitchforkSignal(
    val bar: Int,
    val side: Int,
    val entry: Double,
    val stop: Double,
    val kind: PitchforkSignalKind,
    val fork: PitchforkFork,
    val noteFa: String,
)

/** پارامترها با همان نام‌ها و پیش‌فرض‌های فایل مرجع. */
data class PitchforkParams(
    val rsiLength: Int = 14,
    val maLength: Int = 5,
    val swingK: Int = 3,
    val trendLength: Int = 50,
    val trendSlopeBars: Int = 10,
    val divergenceLookback: Int = 15,
    val maxWait: Int = 20,
    val stopTicks: Int = 2,
    /** نرخ «یک تیک» نسبت به قیمت (نمادهای این اپ تیک ثابت ندارند). */
    val tickFraction: Double = 0.00005,
    val requireWeeklyFilter: Boolean = false,
    /** قرارداد سرمایه‌ای این اپ؛ فایل مرجع حد سود ندارد و با حد ضرر/برگشت خارج می‌شود. */
    val minRiskReward: Double = 1.5,
    val maxRiskReward: Double = 3.0,
    val minBars: Int = 80,
)

data class PitchforkEvaluation(
    val action: SignalAction = SignalAction.NO_TRADE,
    val readiness: StrategyReadinessState = StrategyReadinessState.INSUFFICIENT_DATA,
    val triggerType: StrategyTriggerType = StrategyTriggerType.NONE,
    val entry: Double? = null,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val riskReward: Double? = null,
    val confidence: Double = 0.0,
    val conditions: List<StrategyConditionCheck> = emptyList(),
    val summaryFa: String = "",
    val entryPathFa: String = "",
    val kind: PitchforkSignalKind? = null,
    val livePrice: Double = 0.0,
    val triggerBarTime: Long = 0L,
    val medianTarget: Double = 0.0,
    val contractNoteFa: String = "",
) {
    val isActionable: Boolean
        get() = action != SignalAction.NO_TRADE && entry != null && stopLoss != null && takeProfit != null
}

object PitchforkStrategyEngine {

    // ───────────────────────── اندیکاتورها ─────────────────────────

    fun sma(values: DoubleArray, length: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (length <= 0) return out
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= length) sum -= values[i - length]
            if (i >= length - 1) out[i] = sum / length
        }
        return out
    }

    /** RSI وایلدر با `alpha = 1/length` عیناً مثل فایل مرجع. */
    fun rsi(closes: DoubleArray, length: Int): DoubleArray {
        val out = DoubleArray(closes.size) { 50.0 }
        if (closes.size < 2 || length <= 0) return out
        val alpha = 1.0 / length
        var up = 0.0
        var down = 0.0
        for (i in 1 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            up += alpha * (max(delta, 0.0) - up)
            down += alpha * (max(-delta, 0.0) - down)
            out[i] = if (down == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + up / down)
        }
        return out
    }

    /** سوینگ‌ها با تأیید `k` کندل بعد (بدون نگاه به آینده). */
    fun findSwings(high: DoubleArray, low: DoubleArray, k: Int): Pair<List<PitchforkPoint>, List<PitchforkPoint>> {
        val highs = mutableListOf<PitchforkPoint>()
        val lows = mutableListOf<PitchforkPoint>()
        if (k <= 0) return highs to lows
        for (i in k until high.size - k) {
            var isHigh = true
            var isLow = true
            for (j in i - k..i + k) {
                if (j == i) continue
                // مثل argmax/argmin فایل مرجع، برابری هم سوینگ را باطل می‌کند.
                if (high[j] >= high[i]) isHigh = false
                if (low[j] <= low[i]) isLow = false
                if (!isHigh && !isLow) break
            }
            if (isHigh) highs.add(PitchforkPoint(i, high[i], i + k))
            if (isLow) lows.add(PitchforkPoint(i, low[i], i + k))
        }
        return highs to lows
    }

    /** سهم دامنهٔ کندل که آن‌سوی خط افتاده است (قانون «عبور» کتاب). */
    fun portionBeyond(high: Double, low: Double, line: Double): Double {
        val range = high - low
        if (range <= 0.0) return if (high > line) 1.0 else 0.0
        return max(0.0, high - max(low, line)) / range
    }

    private fun buildFork(p1: PitchforkPoint, p2: PitchforkPoint, p3: PitchforkPoint): PitchforkFork? {
        val midIndex = (p2.index + p3.index) / 2.0
        val midPrice = (p2.price + p3.price) / 2.0
        val dx = midIndex - p1.index
        if (abs(dx) < 0.5) return null
        val slope = (midPrice - p1.price) / dx
        if (!slope.isFinite()) return null
        return PitchforkFork(p1, p2, p3, slope)
    }

    // ───────────────────────── استراتژی A ─────────────────────────

    /** چنگال مینی از کلوزها؛ `null` یعنی هندسهٔ کتاب شکل نگرفته. */
    private fun miniFork(closes: DoubleArray, ma: DoubleArray, t: Int): PitchforkFork? {
        var i = t - 1
        while (i >= 0 && (ma[i].isNaN() || closes[i] <= ma[i])) i--
        if (i < 0 || t - 1 - i < 1) return null
        var p2Index = i + 1
        for (x in i + 1 until t) if (closes[x] < closes[p2Index]) p2Index = x
        var j = i
        while (j >= 0 && !ma[j].isNaN() && closes[j] > ma[j]) j--
        if (j == i) return null
        var p1Index = j + 1
        for (x in j + 1..i) if (closes[x] > closes[p1Index]) p1Index = x
        val fork = buildFork(
            PitchforkPoint(p1Index, closes[p1Index], p1Index),
            PitchforkPoint(p2Index, closes[p2Index], p2Index),
            PitchforkPoint(t, closes[t], t),
        ) ?: return null
        // چنگال اصلاحی (نزولی) داخل روند صعودی
        return if (fork.slope < 0) fork else null
    }

    private fun miniMedianBuy(
        open: DoubleArray,
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        rsiValues: DoubleArray,
        params: PitchforkParams,
        tick: Double,
    ): List<PitchforkSignal> {
        val n = close.size
        val ma = sma(close, params.maLength)
        val trendMa = sma(close, params.trendLength)
        val (_, lows) = findSwings(high, low, params.swingK)

        // واگرایی معکوس صعودی: کف بالاتر قیمت، کف پایین‌تر آر اس آی
        val divergenceConfirmBars = mutableSetOf<Int>()
        for (i in 1 until lows.size) {
            val previous = lows[i - 1]
            val current = lows[i]
            if (current.price > previous.price && rsiValues[current.index] < rsiValues[previous.index]) {
                divergenceConfirmBars.add(current.confirmIndex)
            }
        }

        val signals = mutableListOf<PitchforkSignal>()
        var lastDivergence = Int.MIN_VALUE / 4
        var fork: PitchforkFork? = null
        var created = -1
        var slide: Pair<Int, Double>? = null
        val start = max(params.trendLength + params.trendSlopeBars, params.maLength + 2)

        for (t in start until n) {
            if (t in divergenceConfirmBars) lastDivergence = t
            val activeFork = fork
            if (activeFork == null) {
                val trendMaNow = trendMa[t]
                val trendMaBefore = trendMa.getOrNull(t - params.trendSlopeBars) ?: Double.NaN
                val uptrend = !trendMaNow.isNaN() && !trendMaBefore.isNaN() &&
                    close[t] > trendMaNow && trendMaNow > trendMaBefore
                val crossed = !ma[t].isNaN() && !ma[t - 1].isNaN() && close[t] > ma[t] && close[t - 1] <= ma[t - 1]
                if (uptrend && crossed && t - lastDivergence <= params.divergenceLookback) {
                    val candidate = miniFork(close, ma, t)
                    if (candidate != null) {
                        fork = candidate
                        created = t
                        slide = null
                    }
                }
                continue
            }

            if (t - created > params.maxWait) {
                fork = null
                continue
            }
            val currentSlide = slide
            if (currentSlide == null) {
                if (portionBeyond(high[t], low[t], activeFork.upper(t.toDouble())) > 0.5) {
                    slide = t to low[t]
                }
            } else {
                val (anchorIndex, anchorPrice) = currentSlide
                val line = anchorPrice + activeFork.slope * (t - anchorIndex)
                if (t > anchorIndex && high[t] >= line + tick) {
                    val entry = max(open[t], line + tick)
                    val stop = activeFork.upper(t.toDouble()) - params.stopTicks * tick
                    if (stop < entry) {
                        signals.add(
                            PitchforkSignal(
                                bar = t,
                                side = 1,
                                entry = entry,
                                stop = stop,
                                kind = PitchforkSignalKind.MINI_MEDIAN,
                                fork = activeFork,
                                noteFa = "کراس SMA${params.maLength} + واگرایی معکوس، عبور از موازی بالایی و " +
                                    "موازی لغزان در کندل ${anchorIndex + 1}",
                            )
                        )
                    }
                    fork = null
                    lastDivergence = Int.MIN_VALUE / 4
                }
            }
        }
        return signals
    }

    // ───────────────────────── استراتژی B ─────────────────────────

    private fun priceFailureFork(
        highs: List<PitchforkPoint>,
        lows: List<PitchforkPoint>,
        trendMa: DoubleArray,
        params: PitchforkParams,
    ): PitchforkFork? {
        val p3 = highs.lastOrNull() ?: return null
        val p2 = lows.lastOrNull { it.index < p3.index } ?: return null
        val p1 = highs.dropLast(1).lastOrNull { it.index < p2.index } ?: return null
        val i = p3.index
        val previous = trendMa.getOrNull(i - params.trendSlopeBars) ?: Double.NaN
        if (trendMa[i].isNaN() || previous.isNaN() || !(trendMa[i] < previous)) return null
        val fork = buildFork(p1, p2, p3) ?: return null
        return if (fork.slope < 0) fork else null
    }

    private fun priceFailureBuy(
        open: DoubleArray,
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        rsiValues: DoubleArray,
        params: PitchforkParams,
        tick: Double,
    ): List<PitchforkSignal> {
        val n = close.size
        val trendMa = sma(close, params.trendLength)
        val (swingHighs, swingLows) = findSwings(high, low, params.swingK)
        val highsAt = swingHighs.associateBy { it.confirmIndex }
        val lowsAt = swingLows.associateBy { it.confirmIndex }

        val highs = mutableListOf<PitchforkPoint>()
        val lows = mutableListOf<PitchforkPoint>()
        val signals = mutableListOf<PitchforkSignal>()

        var fork: PitchforkFork? = null
        var created = -1
        var hasSlide = false
        var slide: Pair<Int, Double>? = null
        var slideHit = false
        var reversalLow = 0.0

        for (t in max(params.trendLength + params.trendSlopeBars, 0) until n) {
            val newLow = lowsAt[t]
            if (newLow != null) lows.add(newLow)
            val newHigh = highsAt[t]
            if (newHigh != null) {
                highs.add(newHigh)
                val candidate = priceFailureFork(highs, lows, trendMa, params)
                fork = null
                if (candidate != null) {
                    // «شکست قیمت» فقط وقتی معنا دارد که قیمت به خط میانه نرسیده باشد.
                    var medianReached = false
                    var x = candidate.p3.index + 1
                    while (x <= t) {
                        if (low[x] <= candidate.median(x.toDouble())) {
                            medianReached = true
                            break
                        }
                        x++
                    }
                    if (!medianReached) {
                        fork = candidate
                        created = t
                        hasSlide = false
                        slide = null
                        slideHit = false
                        var lowest = Double.POSITIVE_INFINITY
                        for (y in candidate.p3.index..t) lowest = min(lowest, low[y])
                        reversalLow = lowest
                    }
                }
                continue
            }

            val activeFork = fork ?: continue
            reversalLow = min(reversalLow, low[t])
            if (low[t] <= activeFork.median(t.toDouble()) || t - created > params.maxWait) {
                fork = null
                continue
            }

            if (!hasSlide) {
                if (portionBeyond(high[t], low[t], activeFork.upper(t.toDouble())) > 0.5) {
                    hasSlide = true
                    slide = t to low[t]
                }
                continue
            }
            val (anchorIndex, anchorPrice) = slide ?: continue
            if (t > anchorIndex && high[t] > anchorPrice + activeFork.slope * (t - anchorIndex)) {
                slideHit = true
            }
            if (!slideHit) continue

            // خط هاگوپیان: خط روند P1→P3 که قیمت روی آن حرکت می‌کرد
            val hagopian = activeFork.p1.price +
                (activeFork.p3.price - activeFork.p1.price) / (activeFork.p3.index - activeFork.p1.index).toDouble() *
                (t - activeFork.p1.index)

            val recentLows = lows.filter { it.confirmIndex <= t }
            val divergence = recentLows.size >= 2 &&
                recentLows[recentLows.size - 1].confirmIndex >= t - params.divergenceLookback &&
                recentLows[recentLows.size - 1].price < recentLows[recentLows.size - 2].price &&
                rsiValues[recentLows[recentLows.size - 1].index] > rsiValues[recentLows[recentLows.size - 2].index]

            if (divergence && high[t] >= hagopian + tick) {
                val entry = max(open[t], hagopian + tick)
                val stop = reversalLow - params.stopTicks * tick
                if (stop < entry) {
                    signals.add(
                        PitchforkSignal(
                            bar = t,
                            side = 1,
                            entry = entry,
                            stop = stop,
                            kind = PitchforkSignalKind.PRICE_FAILURE,
                            fork = activeFork,
                            noteFa = "خط میانه لمس نشد، عبور از موازی، موازی لغزان، هاگوپیان و واگرایی استاندارد",
                        )
                    )
                }
                fork = null
            }
        }
        return signals
    }

    // ───────────────────────── تولید سیگنال هر دو جهت ─────────────────────────

    /** برای فروش، بازار آینه می‌شود تا همهٔ منطق فقط یک‌بار (خرید) نوشته شود. */
    fun generateSignals(candles: List<Candle>, params: PitchforkParams = PitchforkParams()): List<PitchforkSignal> {
        if (candles.size < params.minBars) return emptyList()
        val open = DoubleArray(candles.size) { candles[it].open }
        val high = DoubleArray(candles.size) { candles[it].high }
        val low = DoubleArray(candles.size) { candles[it].low }
        val close = DoubleArray(candles.size) { candles[it].close }
        val rsiValues = rsi(close, params.rsiLength)
        val out = mutableListOf<PitchforkSignal>()

        for (side in listOf(1, -1)) {
            val o = DoubleArray(open.size) { side * open[it] }
            val h = if (side == 1) high.copyOf() else DoubleArray(high.size) { -low[it] }
            val l = if (side == 1) low.copyOf() else DoubleArray(low.size) { -high[it] }
            val c = DoubleArray(close.size) { side * close[it] }
            val r = if (side == 1) rsiValues.copyOf() else DoubleArray(rsiValues.size) { 100.0 - rsiValues[it] }
            val tick = max(1e-8, abs(c[c.size - 1]) * params.tickFraction)

            val found = buildList {
                addAll(miniMedianBuy(o, h, l, c, r, params, tick))
                addAll(priceFailureBuy(o, h, l, c, r, params, tick))
            }
            for (signal in found) {
                out.add(signal.copy(side = side, entry = side * signal.entry, stop = side * signal.stop))
            }
        }
        return out.sortedBy { it.bar }
    }

    // ───────────────────────── سنجش لحظه‌ای ─────────────────────────

    fun evaluate(
        candles: List<Candle>,
        params: PitchforkParams = PitchforkParams(),
        livePriceOverride: Double? = null,
    ): PitchforkEvaluation {
        val lastIndex = candles.lastIndex
        if (candles.size < params.minBars) {
            return PitchforkEvaluation(
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                summaryFa = "برای سوینگ‌ها، چنگال و واگرایی حداقل ${params.minBars} کندل لازم است " +
                    "(فعلی: ${candles.size}).",
                livePrice = candles.lastOrNull()?.close ?: 0.0,
            )
        }

        val signals = generateSignals(candles, params)
        val nowSignal = signals.lastOrNull { it.bar == lastIndex }
        val bar = candles.last()
        val livePrice = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 }

        if (nowSignal == null) {
            val previous = signals.lastOrNull()
            val recentFa = if (previous == null) {
                "هیچ ستاپ چنگالی در تاریخچهٔ موجود شکل نگرفته است."
            } else {
                "آخرین سیگنال در کندل ${previous.bar} (${previous.kind.labelFa}) بود؛ در کندل جاری ماشه‌ای نیست."
            }
            return PitchforkEvaluation(
                readiness = StrategyReadinessState.BLOCKED,
                summaryFa = recentFa,
                livePrice = livePrice ?: bar.close,
                triggerBarTime = bar.time,
            )
        }

        val side = nowSignal.side
        val entry = livePrice ?: nowSignal.entry
        val median = nowSignal.fork.median(lastIndex.toDouble())
        val medianInProfit = if (side == 1) median > entry else median < entry
        val risk = abs(entry - nowSignal.stop)
        var takeProfit = if (medianInProfit) median else entry + side * params.minRiskReward * risk
        var rr = if (risk > 0.0) abs(takeProfit - entry) / risk else 0.0
        var contractNote = ""

        if (!medianInProfit) {
            contractNote = "خط میانه در جهت سود نبود؛ هدف روی کف ${format(params.minRiskReward)}R بسته شد"
        } else if (rr < params.minRiskReward) {
            rr = params.minRiskReward
            takeProfit = entry + side * rr * risk
            contractNote = "خط میانه فقط ${format(abs(median - entry) / risk)}R می‌داد؛ طبق قرارداد به کف ۱٫۵R بسته شد"
        } else if (rr > params.maxRiskReward) {
            rr = params.maxRiskReward
            takeProfit = entry + side * rr * risk
            contractNote = "خط میانه از سقف ۳R دورتر بود؛ طبق قرارداد روی ۳R بریده شد"
        }
        if (risk <= 0.0) {
            return PitchforkEvaluation(
                readiness = StrategyReadinessState.BLOCKED,
                summaryFa = "ریسک سیگنال صفر است؛ معامله رد شد.",
                livePrice = entry,
                triggerBarTime = bar.time,
            )
        }

        val conditions = listOf(
            StrategyConditionCheck(1, "سوینگ تأییدشده بدون نگاه به آینده", true, "k = ${params.swingK} کندل"),
            StrategyConditionCheck(
                2,
                "نوع ستاپ",
                true,
                nowSignal.kind.labelFa,
            ),
            StrategyConditionCheck(3, "چنگال اندروز", true, nowSignal.fork.descriptionFa),
            StrategyConditionCheck(
                4,
                "ماشهٔ عبور",
                true,
                nowSignal.noteFa,
            ),
            StrategyConditionCheck(
                5,
                "حد ضرر کتاب",
                true,
                "${params.stopTicks} تیک پشت سطح ساختاری (${priceText(nowSignal.stop)})",
            ),
            StrategyConditionCheck(
                6,
                "R در قرارداد ${format(params.minRiskReward)} تا ${format(params.maxRiskReward)}",
                true,
                "${format(rr)}R" + if (contractNote.isBlank()) "" else " · $contractNote",
            ),
        )

        val summary = buildString {
            append(if (side == 1) "خرید" else "فروش").append(" · ").append(nowSignal.kind.labelFa)
            append(" · چنگال: ").append(nowSignal.fork.descriptionFa)
            append(" · ورود ").append(priceText(entry))
            append(" · حد ضرر ").append(priceText(nowSignal.stop))
            append(" · هدف ").append(priceText(takeProfit))
            append(" (").append(format(rr)).append("R)")
            if (contractNote.isNotBlank()) append(" · ").append(contractNote)
        }

        val entryPath = buildString {
            append("Pitchfork · ").append(nowSignal.kind.labelFa)
            append(" · ").append(nowSignal.fork.descriptionFa)
            append(" · خط میانه در کندل جاری ").append(priceText(median))
            append(" · ").append(nowSignal.noteFa)
            if (contractNote.isNotBlank()) append(" · ").append(contractNote)
        }

        return PitchforkEvaluation(
            action = if (side == 1) SignalAction.BUY else SignalAction.SELL,
            readiness = if (side == 1) StrategyReadinessState.SIGNAL_BUY else StrategyReadinessState.SIGNAL_SELL,
            triggerType = if (nowSignal.kind == PitchforkSignalKind.MINI_MEDIAN) {
                StrategyTriggerType.PITCHFORK_MINI_MEDIAN
            } else {
                StrategyTriggerType.PITCHFORK_PRICE_FAILURE
            },
            entry = entry,
            stopLoss = nowSignal.stop,
            takeProfit = takeProfit,
            riskReward = rr,
            confidence = (70.0 + if (nowSignal.kind == PitchforkSignalKind.MINI_MEDIAN) 10.0 else 15.0).coerceAtMost(95.0),
            conditions = conditions,
            summaryFa = summary,
            entryPathFa = entryPath,
            kind = nowSignal.kind,
            livePrice = entry,
            triggerBarTime = bar.time,
            medianTarget = median,
            contractNoteFa = contractNote,
        )
    }

    private fun format(value: Double): String = if (!value.isFinite()) "—" else "%.2f".format(Locale.US, value)

    fun priceText(value: Double): String {
        if (!value.isFinite()) return "—"
        return "%.6f".format(Locale.US, value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
