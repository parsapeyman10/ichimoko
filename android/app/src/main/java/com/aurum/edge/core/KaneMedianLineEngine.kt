package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * پورت Kotlin از فایل مرجع کاربر: `kane_median_line_fibonacci_synergy.py`
 * (Jim Kane — *Median Line and Fibonacci Synergy*).
 *
 * این فایل عیناً همان زنجیرهٔ تصمیم فایل پایتون را پیاده می‌کند:
 * زیگزاگ علّی → الگوی ABCD → گروه‌بندی فیبوناچی برای ساخت ناحیهٔ PTA → ساخت همهٔ خانواده‌های خط
 * (ست بزرگ، ست روی خود ABCD استاندارد و Modified Schiff، ست Adjusted، خطوط روند/کلون، Offset ها) →
 * شمارش برخورد خطوط با PTA + «دیده‌شدن» ست + هم‌زمانی زمانی → امتیاز ۱..۱۰ → آستانهٔ ۷ →
 * انتظار برای لمس ناحیه → تریگر اجباری EMA 5/13 → حد ضرر و هدف ۱٫۲۷۲ اکستنشن.
 *
 * تفاوت‌های عمدی با فایل پایتون (همه در چک‌لیست و خلاصهٔ فارسی هم گزارش می‌شوند):
 * ۱) قرارداد پول این اپ: R حداقل ۱٫۵ و حداکثر ۳٫۰ (فایل مرجع `min_rr = 1.0` دارد). اگر هدف هندسی
 *    کمتر از ۱٫۵R بدهد، هدف روی ۱٫۵R بسته می‌شود و همین در چک‌لیست نوشته می‌شود؛ اگر بیشتر از ۳R
 *    باشد روی ۳R بریده می‌شود.
 * ۲) ضریب حجم `_scale` فایل مرجع (۰٫۵ / ۱٫۰ / ۱٫۲۵ بر اساس امتیاز) اعمال **نمی‌شود**، چون ریسک هر
 *    معامله در این اپ باید دقیقاً ۵ دلار بماند. امتیاز فقط برای اولویت‌بندی و نمایش استفاده می‌شود.
 * ۳) ساختار (زیگزاگ/ست/امتیاز) روی تایم‌فریم ۴ ساعته و تریگر/حد ضرر روی ۱ ساعته محاسبه می‌شود.
 */
data class KanePivot(
    val index: Int,
    val price: Double,
    val isHigh: Boolean,
    /** باری که پیوت بدون نگاه به آینده تأیید می‌شود. */
    val confirmIndex: Int,
)

/** یک خط از یک ست: `y = slope * x + intercept`. */
data class KaneLine(
    val slope: Double,
    val intercept: Double,
    val name: String,
    val family: String,
    var weight: Double,
    val setId: Int,
) {
    fun y(x: Double): Double = slope * x + intercept
}

/** یک عدد فیبوناچی در ناحیهٔ PTA؛ `kind` یا `pat` است یا `pos`. */
data class KaneFibNumber(
    val price: Double,
    val label: String,
    val kind: String,
    val preferred: Boolean,
)

/** ستاپ کامل یک طرف (خرید/فروش) در لحظهٔ تأیید نقطهٔ C. */
data class KaneSetup(
    val side: Int,
    val barIndex: Int,
    val a: KanePivot,
    val b: KanePivot,
    val c: KanePivot,
    val x: KanePivot,
    val impulseHigh: Double,
    val zoneLow: Double,
    val zoneHigh: Double,
    val windowStart: Double,
    val windowEnd: Double,
    val expireBar: Int,
    val rating: Double,
    val partsFa: Map<String, Double>,
    val groupLabelsFa: List<String>,
    val familyBest: Map<String, Double>,
    val timeScore: Double,
    val abBars: Int,
    val atr: Double,
)

/** پارامترهای موتور؛ نام‌ها همان نام‌های فایل مرجع پایتون هستند. */
data class KaneConfig(
    val atrLength: Int = 14,
    val zzSmall: Double = 1.6,
    val zzBig: Double = 4.5,
    val emaFast: Int = 5,
    val emaSlow: Int = 13,
    val trendMinAtr: Double = 6.0,
    val bcMin: Double = 0.382,
    val bcMax: Double = 1.272,
    val projectionRatios: List<Double> = listOf(1.000, 1.128, 1.272),
    val extensionRatios: List<Double> = listOf(1.272, 1.460),
    val positionPreferred: List<Double> = listOf(0.300, 0.382, 0.447),
    val positionOther: List<Double> = listOf(0.500, 0.618, 0.685, 0.786),
    val groupToleranceAtr: Double = 0.8,
    val zonePadAtr: Double = 0.15,
    val hitToleranceAtr: Double = 0.35,
    val seenToleranceAtr: Double = 0.35,
    val slopeMinAtr: Double = 0.01,
    val slopeMaxAtr: Double = 1.50,
    val adjustedPairs: Int = 3,
    val bigSets: Int = 3,
    val minRating: Double = 7.0,
    val perfectCap: Double = 9.75,
    val allowCountertrend: Boolean = false,
    val maxWaitMultiplier: Double = 3.0,
    val invalidAtr: Double = 1.0,
    val maxTriggerBars: Int = 12,
    val crossLookback: Int = 3,
    val stopAtr: Double = 0.35,
    val maxRiskAtr: Double = 3.5,
    val targetExtension: Double = 1.272,
    val maxHoldBars: Int = 250,
    /** قرارداد این اپ: کف و سقف R. */
    val minRiskReward: Double = 2.0,
    val maxRiskReward: Double = 3.0,
    /** با بودجهٔ کندل این اپ (حدود ۱۳۰ کندل ۴ ساعته) آستانه روی ۱۲۰ تنظیم شده است. */
    val minMacroBars: Int = 120,
    val minEntryBars: Int = 40,
)

/** نتیجهٔ سنجش یک نماد در این لحظه. */
data class KaneEvaluation(
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
    val rating: Double = 0.0,
    val zoneLow: Double = 0.0,
    val zoneHigh: Double = 0.0,
    val ratingPartsFa: Map<String, Double> = emptyMap(),
    val triggerBarTime: Long = 0L,
    val livePrice: Double = 0.0,
    val contractNoteFa: String = "",
)

object KaneMedianLineEngine {

    // ───────────────────────── ابزارهای عددی ─────────────────────────

    fun ewma(values: DoubleArray, span: Int): DoubleArray {
        if (values.isEmpty()) return values
        val alpha = 2.0 / (span + 1.0)
        val out = DoubleArray(values.size)
        out[0] = values[0]
        for (i in 1 until values.size) out[i] = out[i - 1] + alpha * (values[i] - out[i - 1])
        return out
    }

    /** ATR به سبک فایل مرجع: میانگین نمایی دامنهٔ واقعی با `alpha = 1/length`. */
    fun atrSeries(candles: List<Candle>, length: Int): DoubleArray {
        if (candles.isEmpty()) return DoubleArray(0)
        val tr = DoubleArray(candles.size)
        for (i in candles.indices) {
            val previousClose = if (i == 0) candles[0].close else candles[i - 1].close
            tr[i] = max(
                candles[i].high - candles[i].low,
                max(abs(candles[i].high - previousClose), abs(candles[i].low - previousClose)),
            )
        }
        val alpha = 1.0 / length.toDouble()
        val out = DoubleArray(candles.size)
        out[0] = tr[0]
        for (i in 1 until candles.size) out[i] = out[i - 1] + alpha * (tr[i] - out[i - 1])
        return out
    }

    /**
     * زیگزاگ علّی فایل مرجع (بدون نگاه به آینده): سوینگ وقتی تأیید می‌شود که قیمت به‌اندازهٔ
     * `mult × ATR` از اکستریم برگردد؛ `confirmIndex` همان باری است که تأیید رخ داده.
     */
    fun zigzag(high: DoubleArray, low: DoubleArray, atr: DoubleArray, mult: Double): List<KanePivot> {
        val n = high.size
        if (n < 3) return emptyList()
        val pivots = mutableListOf<KanePivot>()
        var direction = 0
        var hi = high[0]
        var hiIndex = 0
        var lo = low[0]
        var loIndex = 0
        for (i in 1 until n) {
            val threshold = mult * atr[i]
            when (direction) {
                0 -> {
                    if (high[i] > hi) { hi = high[i]; hiIndex = i }
                    if (low[i] < lo) { lo = low[i]; loIndex = i }
                    if (high[i] - lo >= threshold && loIndex <= i) {
                        pivots.add(KanePivot(loIndex, lo, false, i))
                        direction = 1
                        hi = high[i]
                        hiIndex = i
                    } else if (hi - low[i] >= threshold) {
                        pivots.add(KanePivot(hiIndex, hi, true, i))
                        direction = -1
                        lo = low[i]
                        loIndex = i
                    }
                }
                1 -> {
                    if (high[i] > hi) {
                        hi = high[i]
                        hiIndex = i
                    } else if (hi - low[i] >= threshold) {
                        pivots.add(KanePivot(hiIndex, hi, true, i))
                        direction = -1
                        lo = low[i]
                        loIndex = i
                    }
                }
                else -> {
                    if (low[i] < lo) {
                        lo = low[i]
                        loIndex = i
                    } else if (high[i] - lo >= threshold) {
                        pivots.add(KanePivot(loIndex, lo, false, i))
                        direction = 1
                        hi = high[i]
                        hiIndex = i
                    }
                }
            }
        }
        return pivots
    }

    /** آینه‌کردن برای فروش: همهٔ منطق فقط یک‌بار (خرید) نوشته می‌شود. */
    private fun mirror(pivot: KanePivot, side: Int): KanePivot =
        if (side == 1) pivot else pivot.copy(price = -pivot.price, isHigh = !pivot.isHigh)

    private fun viewHigh(candles: List<Candle>, side: Int, index: Int): Double =
        if (side == 1) candles[index].high else -candles[index].low

    private fun viewLow(candles: List<Candle>, side: Int, index: Int): Double =
        if (side == 1) candles[index].low else -candles[index].high

    private fun viewClose(candles: List<Candle>, side: Int, index: Int): Double =
        side * candles[index].close

    // ───────────────────────── خطوط (فصل‌های ۱ تا ۶) ─────────────────────────

    /** ML + موازی‌ها + division line + warning line. */
    fun channelLines(slope: Double, upperIntercept: Double, lowerIntercept: Double, family: String, setId: Int): List<KaneLine> {
        val up = max(upperIntercept, lowerIntercept)
        val lo = min(upperIntercept, lowerIntercept)
        val halfWidth = (up - lo) / 2.0
        if (halfWidth < 1e-9) return emptyList()
        val mid = (up + lo) / 2.0
        val spec = listOf(
            Triple("ML", mid, 1.0),
            Triple("UP", up, 1.0),
            Triple("LO", lo, 1.0),
            Triple("DIV_U", mid + halfWidth / 2.0, 0.7),
            Triple("DIV_L", mid - halfWidth / 2.0, 0.7),
            Triple("DIV_UU", up + halfWidth / 2.0, 0.5),
            Triple("DIV_LL", lo - halfWidth / 2.0, 0.5),
            Triple("WARN_U1", up + halfWidth, 0.5),
            Triple("WARN_L1", lo - halfWidth, 0.5),
            Triple("WARN_U2", up + 2 * halfWidth, 0.4),
            Triple("WARN_L2", lo - 2 * halfWidth, 0.4),
        )
        return spec.map { (name, intercept, weight) -> KaneLine(slope, intercept, name, family, weight, setId) }
    }

    /** ست استاندارد (خط میانه از P1 و وسط P2-P3) یا Modified Schiff (مبدأ = وسط P1-P2). */
    fun medianSet(p1: KanePivot, p2: KanePivot, p3: KanePivot, family: String, setId: Int, schiff: Boolean = false): List<KaneLine> {
        var x1 = p1.index.toDouble()
        var y1 = p1.price
        if (schiff) {
            x1 = (p1.index + p2.index) / 2.0
            y1 = (p1.price + p2.price) / 2.0
        }
        val xMid = (p2.index + p3.index) / 2.0
        val yMid = (p2.price + p3.price) / 2.0
        if (xMid <= x1) return emptyList()
        val slope = (yMid - y1) / (xMid - x1)
        if (!slope.isFinite()) return emptyList()
        return channelLines(slope, p2.price - slope * p2.index, p3.price - slope * p3.index, family, setId)
    }

    /** ست Adjusted (فصل ۲): نقطهٔ ۱ «در فضا» است و C روی یکی از خطوط قفل می‌شود. */
    fun adjustedSet(r2: KanePivot, r3: KanePivot, c: KanePivot, mode: String, setId: Int): List<KaneLine> {
        val hiPoint = if (r2.price > r3.price) r2 else r3
        val loPoint = if (r2.price > r3.price) r3 else r2
        val x0: Double
        val y0: Double
        when (mode) {
            "distal" -> { x0 = hiPoint.index.toDouble(); y0 = hiPoint.price }
            "proximal" -> { x0 = loPoint.index.toDouble(); y0 = loPoint.price }
            else -> {
                x0 = (r2.index + r3.index) / 2.0
                y0 = (r2.price + r3.price) / 2.0
            }
        }
        val dx = c.index - x0
        if (dx <= 0.0) return emptyList()
        val slope = (c.price - y0) / dx
        val upperIntercept = hiPoint.price - slope * hiPoint.index
        val lowerIntercept = loPoint.price - slope * loPoint.index
        if (upperIntercept <= lowerIntercept) return emptyList()
        return channelLines(slope, upperIntercept, lowerIntercept, "ADJ", setId)
    }

    /** فصل ۶: خط روند بین دو اکستریم آخر + کلون موازی (sliding) روی اکستریم مقابل. */
    fun trendLines(pivots: List<KanePivot>, initialSetId: Int): List<KaneLine> {
        val out = mutableListOf<KaneLine>()
        var setId = initialSetId
        for (kind in listOf(true, false)) {
            val sameKind = pivots.filter { it.isHigh == kind }.takeLast(3)
            for (i in 0 until sameKind.size - 1) {
                val a = sameKind[i]
                val b = sameKind[i + 1]
                if (b.index <= a.index) continue
                val slope = (b.price - a.price) / (b.index - a.index)
                out.add(KaneLine(slope, a.price - slope * a.index, "TL", "TL", 0.8, setId))
                val opposite = pivots.filter { it.isHigh != kind && it.index in (a.index + 1) until b.index }
                if (opposite.isNotEmpty()) {
                    val reference = if (kind) opposite.minByOrNull { it.price }!! else opposite.maxByOrNull { it.price }!!
                    out.add(KaneLine(slope, reference.price - slope * reference.index, "SLIDE", "TL", 0.6, setId))
                }
                setId += 1
            }
        }
        return out
    }

    /** فصل ۵: کلون موازی خطِ یک ست روی سوینگ‌هایی که روی هیچ خطی نیستند. */
    fun slidingFromSlope(slope: Double, pivots: List<KanePivot>, lines: List<KaneLine>, atr: Double, setId: Int): List<KaneLine> {
        val out = mutableListOf<KaneLine>()
        for (pivot in pivots.takeLast(6)) {
            val distance = lines.minOfOrNull { abs(pivot.price - it.y(pivot.index.toDouble())) } ?: Double.MAX_VALUE
            if (distance > 0.3 * atr) {
                out.add(KaneLine(slope, pivot.price - slope * pivot.index, "SLIDE", "TL", 0.6, setId))
            }
        }
        return out
    }

    /** فصل ۴: displacement و reflection ست بر اساس «جا ماندن» قیمت از خطوط. */
    fun offsetLines(lines: List<KaneLine>, pivots: List<KanePivot>, sinceIndex: Int, untilIndex: Int, atr: Double): List<KaneLine> {
        if (lines.isEmpty()) return emptyList()
        val base = lines.filter { it.name in listOf("ML", "UP", "LO", "DIV_U", "DIV_L") }
        val up = lines.firstOrNull { it.name == "UP" }
        val lo = lines.firstOrNull { it.name == "LO" }
        if (base.isEmpty() || up == null || lo == null) return emptyList()
        val halfWidth = abs(up.intercept - lo.intercept) / 2.0
        val misses = mutableListOf<Double>()
        for (pivot in pivots) {
            if (pivot.index <= sinceIndex || pivot.index >= untilIndex) continue
            val nearest = base.minByOrNull { abs(pivot.price - it.y(pivot.index.toDouble())) } ?: continue
            val delta = pivot.price - nearest.y(pivot.index.toDouble())
            if (abs(delta) >= 0.12 * atr && abs(delta) <= 0.5 * halfWidth) misses.add(delta)
        }
        val out = mutableListOf<KaneLine>()
        for (delta in misses.takeLast(2)) {
            for (tag in listOf("DISP", "REFL")) {
                val sign = if (tag == "DISP") 1.0 else -1.0
                for (line in lines) {
                    out.add(
                        KaneLine(
                            line.slope,
                            line.intercept + sign * delta,
                            "${tag}_${line.name}",
                            "OFF",
                            0.6 * line.weight,
                            line.setId,
                        )
                    )
                }
            }
        }
        return out
    }

    /** آیا خط در بازهٔ زمانی [x0,x1] از ناحیهٔ PTA می‌گذرد؟ اگر بله، زمان برخورد. */
    fun lineHit(line: KaneLine, zoneLow: Double, zoneHigh: Double, x0: Double, x1: Double, tolerance: Double): Double? {
        val y0 = line.y(x0)
        val y1 = line.y(x1)
        val lo = min(y0, y1)
        val hi = max(y0, y1)
        if (hi < zoneLow - tolerance || lo > zoneHigh + tolerance) return null
        val mid = 0.5 * (zoneLow + zoneHigh)
        val x = if (abs(line.slope) < 1e-12) 0.5 * (x0 + x1) else (mid - line.intercept) / line.slope
        return min(max(x, x0), x1)
    }

    /** «دیده‌شدن» ست: چه نسبتی از سوینگ‌های قبلی خودشان را روی خطوط اصلی ست دیده‌اند. */
    fun seenRatio(
        lines: List<KaneLine>,
        pivots: List<KanePivot>,
        startIndex: Int,
        endIndex: Int,
        excluded: Set<Int>,
        atr: Double,
        toleranceAtr: Double,
    ): Double {
        val main = lines.filter { it.name in listOf("ML", "UP", "LO", "DIV_U", "DIV_L") }
        if (main.isEmpty()) return 0.0
        var count = 0
        for (pivot in pivots) {
            if (pivot.index <= startIndex || pivot.index > endIndex || pivot.index in excluded) continue
            val nearest = main.minOf { abs(pivot.price - it.y(pivot.index.toDouble())) }
            if (nearest <= toleranceAtr * atr) count++
        }
        return min(1.0, count / 3.0)
    }

    /** گروه‌بندی فیبوناچی: خوشه‌ای که هم عدد pattern دارد و هم عدد position. */
    fun bestGroup(levels: List<KaneFibNumber>, tolerance: Double): List<KaneFibNumber>? {
        if (levels.isEmpty()) return null
        val sorted = levels.sortedBy { it.price }
        var best: List<KaneFibNumber>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in sorted.indices) {
            val group = sorted.drop(i).takeWhile { it.price - sorted[i].price <= tolerance }
            val kinds = group.map { it.kind }.toSet()
            if (!kinds.contains("pat") || !kinds.contains("pos")) continue
            val preferredBonus = if (group.any { it.preferred }) 0.5 else 0.0
            val width = (group.last().price - group.first().price)
            val score = group.size + preferredBonus - width
            if (score > bestScore) {
                bestScore = score
                best = group
            }
        }
        return best
    }

    // ───────────────────────── ساخت ستاپ ─────────────────────────

    private fun contextDirection(
        barIndex: Int,
        bigPivots: List<KanePivot>,
        closes: DoubleArray,
        ema50: DoubleArray,
        ema100: DoubleArray,
        ema200: DoubleArray,
    ): Int {
        val confirmed = bigPivots.filter { it.confirmIndex <= barIndex }
        val highs = confirmed.filter { it.isHigh }.takeLast(2)
        val lows = confirmed.filter { !it.isHigh }.takeLast(2)
        var structure = 0
        if (highs.size == 2 && lows.size == 2) {
            if (highs[1].price > highs[0].price && lows[1].price > lows[0].price) structure = 1
            else if (highs[1].price < highs[0].price && lows[1].price < lows[0].price) structure = -1
        }
        val score = structure.toDouble() +
            sign(closes[barIndex] - ema100[barIndex]) +
            sign(ema50[barIndex] - ema200[barIndex])
        return when {
            score >= 2.0 -> 1
            score <= -2.0 -> -1
            else -> 0
        }
    }

    private fun buildSetup(
        side: Int,
        barIndex: Int,
        smallPivots: List<KanePivot>,
        bigPivots: List<KanePivot>,
        macro: List<Candle>,
        atr: DoubleArray,
        context: Int,
        config: KaneConfig,
    ): KaneSetup? {
        val viewSmall = smallPivots.takeLast(14).map { mirror(it, side) }
        if (viewSmall.size < 4) return null
        val c = viewSmall[viewSmall.size - 1]
        val b = viewSmall[viewSmall.size - 2]
        val a = viewSmall[viewSmall.size - 3]
        if (!c.isHigh || b.isHigh || !a.isHigh) return null

        val atrNow = atr[barIndex]
        if (atrNow <= 0.0) return null
        val viewBig = bigPivots.map { mirror(it, side) }
        val x = viewBig.lastOrNull { !it.isHigh && it.index < a.index } ?: return null
        if (x.price >= b.price) return null

        var impulseHigh = Double.NEGATIVE_INFINITY
        for (i in x.index..c.index) impulseHigh = max(impulseHigh, viewHigh(macro, side, i))
        val impulseLow = x.price

        val ab = a.price - b.price
        val bc = c.price - b.price
        if (ab <= 0.0) return null
        val bcRatio = bc / ab
        if (bcRatio < config.bcMin || bcRatio > config.bcMax) return null
        if (impulseHigh - impulseLow < config.trendMinAtr * atrNow) return null
        if (context == -side && !config.allowCountertrend) return null

        // (الف) ناحیهٔ PTA از گروه‌بندی فیبوناچی: اعداد pattern و اعداد position
        val levels = mutableListOf<KaneFibNumber>()
        for (ratio in config.projectionRatios) {
            levels.add(KaneFibNumber(c.price - ratio * ab, "AB=CD ${ratioText(ratio)}", "pat", false))
        }
        for (ratio in config.extensionRatios) {
            levels.add(KaneFibNumber(c.price - ratio * bc, "BCext ${ratioText(ratio)}", "pat", false))
        }
        for (ratio in config.positionPreferred) {
            levels.add(KaneFibNumber(impulseHigh - ratio * (impulseHigh - impulseLow), "pos ${ratioText(ratio)}", "pos", true))
        }
        for (ratio in config.positionOther) {
            levels.add(KaneFibNumber(impulseHigh - ratio * (impulseHigh - impulseLow), "pos ${ratioText(ratio)}", "pos", false))
        }
        val inRange = levels.filter { it.price > impulseLow && it.price < c.price }
        val group = bestGroup(inRange, config.groupToleranceAtr * atrNow) ?: return null
        val zoneLow = group.minOf { it.price } - config.zonePadAtr * atrNow
        val zoneHigh = group.maxOf { it.price } + config.zonePadAtr * atrNow
        val groupWidth = zoneHigh - zoneLow - 2 * config.zonePadAtr * atrNow

        val abBars = max(1, b.index - a.index)
        val windowStart = max(barIndex + 1.0, c.index + 0.5 * abBars)
        val windowEnd = max(windowStart + 3.0, c.index + 2.0 * abBars + 5.0)
        val tolerance = config.hitToleranceAtr * atrNow

        // (ب) خطوط همهٔ خانواده‌ها
        val lines = mutableListOf<KaneLine>()
        var setId = 0
        data class SetMeta(val family: String, val slope: Double, val start: Int, val third: Int, val excluded: Set<Int>, val lines: List<KaneLine>)
        val metas = mutableMapOf<Int, SetMeta>()

        fun addSet(candidate: List<KaneLine>, family: String, anchors: List<KanePivot>, startIndex: Int, thirdIndex: Int) {
            if (candidate.isEmpty()) return
            val slope = candidate[0].slope
            val weighted = candidate.toMutableList()
            if (!(abs(slope) >= config.slopeMinAtr * atrNow && abs(slope) <= config.slopeMaxAtr * atrNow)) {
                weighted.forEach { it.weight *= 0.5 }
            }
            metas[setId] = SetMeta(family, slope, startIndex, thirdIndex, anchors.map { it.index }.toSet(), weighted)
            lines.addAll(weighted)
            setId += 1
        }

        val viewBigConfirmed = viewBig.filter { it.confirmIndex <= barIndex }
        val bigWindowStart = max(0, viewBigConfirmed.size - 2 - config.bigSets)
        for (j in bigWindowStart until max(bigWindowStart, viewBigConfirmed.size - 2)) {
            if (j + 2 >= viewBigConfirmed.size) break
            val p1 = viewBigConfirmed[j]
            val p2 = viewBigConfirmed[j + 1]
            val p3 = viewBigConfirmed[j + 2]
            val set = medianSet(p1, p2, p3, "BIG", setId).toMutableList()
            if (set.isNotEmpty() && set[0].slope < 0) set.forEach { it.weight *= 0.6 }
            addSet(set, "BIG", listOf(p1, p2, p3), p1.index, p3.index)
        }

        addSet(medianSet(a, b, c, "ABCD_ML", setId), "ABCD_ML", listOf(a, b, c), a.index, c.index)
        addSet(medianSet(a, b, c, "ABCD_MS", setId, schiff = true), "ABCD_MS", listOf(a, b, c), a.index, c.index)

        val before = viewSmall.dropLast(1)
        for (j in 0 until config.adjustedPairs) {
            if (before.size < 2 + j) break
            val r2 = before[before.size - 2 - j]
            val r3 = before[before.size - 1 - j]
            for (mode in listOf("distal", "proximal", "ml")) {
                addSet(adjustedSet(r2, r3, c, mode, setId), "ADJ", listOf(r2, r3, c), r2.index, r3.index)
            }
        }

        val trend = trendLines(viewSmall, 1000)
        lines.addAll(trend)
        for ((metaId, meta) in metas.entries.take(6)) {
            lines.addAll(slidingFromSlope(meta.slope, viewSmall, meta.lines, atrNow, 2000 + metaId))
        }
        for ((_, meta) in metas) {
            if (meta.family == "BIG" || meta.family == "ADJ") {
                lines.addAll(offsetLines(meta.lines, viewSmall, meta.third, c.index, atrNow))
            }
        }

        // (ج) برخورد خطوط با PTA + دیده‌شدن ست + هم‌زمانی زمانی
        val seen = metas.mapValues { (_, meta) ->
            seenRatio(meta.lines, viewSmall, meta.start, c.index, meta.excluded, atrNow, config.seenToleranceAtr)
        }
        val hits = mutableListOf<Pair<KaneLine, Double>>()
        val familyBest = mutableMapOf<String, Double>()
        for (line in lines) {
            val hit = lineHit(line, zoneLow, zoneHigh, windowStart, windowEnd, tolerance) ?: continue
            var weight = line.weight
            val setIdOfLine = line.setId
            val seenValue = seen[setIdOfLine]
            if (seenValue != null) weight *= 0.5 + 0.5 * seenValue
            hits.add(line to hit)
            familyBest[line.family] = max(familyBest[line.family] ?: 0.0, weight)
        }
        if (hits.isEmpty()) return null

        val timeWindow = max(3.0, 0.15 * abBars)
        var bestCount = 0
        var bestFamilies = 0
        for ((_, hitX) in hits.sortedBy { it.second }) {
            val inWindow = hits.filter { abs(it.second - hitX) <= timeWindow }
            if (inWindow.size > bestCount) {
                bestCount = inWindow.size
                bestFamilies = inWindow.map { it.first.family }.toSet().size
            }
        }
        val timeScore = when {
            bestFamilies >= 2 && bestCount >= 3 -> 1.0
            bestFamilies >= 2 -> 0.5
            else -> 0.0
        }

        // (د) امتیاز ۱..۱۰ (مجموع وزن‌ها = ۱۰)
        val parts = linkedMapOf<String, Double>()
        val preferred = group.any { it.preferred }
        parts["pattern"] = (if (preferred) 1.0 else 0.5) +
            (if (bcRatio in 0.5..0.9) 0.5 else 0.3) +
            (if ((c.index - b.index).toDouble() / abBars in 0.3..1.5) 0.5 else 0.0)
        parts["grouping"] = min(0.9, 0.3 * (group.size - 1)) +
            when {
                groupWidth <= 0.5 * atrNow -> 0.6
                groupWidth <= 1.0 * atrNow -> 0.3
                else -> 0.0
            }
        parts["big_set"] = 1.5 * (familyBest["BIG"] ?: 0.0)
        parts["abcd_sets"] = min(2.0, (familyBest["ABCD_ML"] ?: 0.0) + (familyBest["ABCD_MS"] ?: 0.0))
        parts["adjusted"] = 1.0 * (familyBest["ADJ"] ?: 0.0)
        parts["tl_off_slide"] = 0.5 * max(familyBest["TL"] ?: 0.0, familyBest["OFF"] ?: 0.0)
        parts["time"] = 1.0 * timeScore
        parts["context"] = if (context == side) 0.5 else 0.0
        val rating = min(10.0, parts.values.sum())

        return KaneSetup(
            side = side,
            barIndex = barIndex,
            a = a,
            b = b,
            c = c,
            x = x,
            impulseHigh = impulseHigh,
            zoneLow = zoneLow,
            zoneHigh = zoneHigh,
            windowStart = windowStart,
            windowEnd = windowEnd,
            expireBar = (barIndex + config.maxWaitMultiplier * abBars + 10).toInt(),
            rating = rating,
            partsFa = parts,
            groupLabelsFa = group.map { it.label },
            familyBest = familyBest,
            timeScore = timeScore,
            abBars = abBars,
            atr = atrNow,
        )
    }

    // ───────────────────────── سنجش کامل ─────────────────────────

    fun evaluate(
        macroCandles: List<Candle>,
        entryCandles: List<Candle>,
        config: KaneConfig = KaneConfig(),
        livePriceOverride: Double? = null,
    ): KaneEvaluation {
        if (macroCandles.size < config.minMacroBars || entryCandles.size < config.minEntryBars) {
            return KaneEvaluation(
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                summaryFa = "برای زیگزاگ/ست‌های کین حداقل ${config.minMacroBars} کندل ساختار و " +
                    "${config.minEntryBars} کندل ورود لازم است (فعلی: ${macroCandles.size}/${entryCandles.size}).",
                livePrice = entryCandles.lastOrNull()?.close ?: 0.0,
            )
        }

        val macroAtr = atrSeries(macroCandles, config.atrLength)
        val entryAtr = atrSeries(entryCandles, config.atrLength)
        val highs = DoubleArray(macroCandles.size) { macroCandles[it].high }
        val lows = DoubleArray(macroCandles.size) { macroCandles[it].low }
        val closes = DoubleArray(macroCandles.size) { macroCandles[it].close }

        val small = zigzag(highs, lows, macroAtr, config.zzSmall)
        val big = zigzag(highs, lows, macroAtr, config.zzBig)
        val ema50 = ewma(closes, 50)
        val ema100 = ewma(closes, 100)
        val ema200 = ewma(closes, 200)
        val barIndex = macroCandles.lastIndex
        val context = contextDirection(barIndex, big, closes, ema50, ema100, ema200)

        val entryCloses = DoubleArray(entryCandles.size) { entryCandles[it].close }
        val entryFast = ewma(entryCloses, config.emaFast)
        val entrySlow = ewma(entryCloses, config.emaSlow)
        val lastEntry = entryCandles.lastIndex
        val livePrice = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 }

        var bestEvaluation: KaneEvaluation? = null
        var bestRating = Double.NEGATIVE_INFINITY
        var considered = 0
        var ratingRejected = 0

        for (side in listOf(1, -1)) {
            val setup = buildSetup(side, barIndex, small, big, macroCandles, macroAtr, context, config) ?: continue
            val sideConditions = mutableListOf<StrategyConditionCheck>()

            fun add(titleFa: String, passed: Boolean, detailFa: String) {
                sideConditions.add(StrategyConditionCheck(sideConditions.size + 1, titleFa, passed, detailFa))
            }
            considered += 1
            if (setup.rating < config.minRating) {
                ratingRejected += 1
                continue
            }
            val atrEntryNow = entryAtr[lastEntry]
            if (atrEntryNow <= 0.0) continue
            val tolerance = config.hitToleranceAtr * atrEntryNow

            // لمس ناحیهٔ PTA
            var touchIndex = -1
            val searchFrom = max(0, lastEntry - (config.maxTriggerBars + 5))
            for (i in searchFrom..lastEntry) {
                if (viewLow(entryCandles, side, i) <= setup.zoneHigh + tolerance) touchIndex = i
            }
            val touched = touchIndex >= 0
            var lowSinceTouch = Double.POSITIVE_INFINITY
            if (touched) {
                for (i in touchIndex..lastEntry) {
                    lowSinceTouch = min(lowSinceTouch, viewLow(entryCandles, side, i))
                }
            }
            val invalidated = viewLow(entryCandles, side, lastEntry) < setup.zoneLow - config.invalidAtr * atrEntryNow
            val barsSinceTouch = if (touched) lastEntry - touchIndex else Int.MAX_VALUE
            val triggerLive = touched && !invalidated && barsSinceTouch <= config.maxTriggerBars

            var crossed = false
            for (k in max(1, lastEntry - config.crossLookback + 1)..lastEntry) {
                if (entryFast[k] > entrySlow[k] && entryFast[k - 1] <= entrySlow[k - 1]) {
                    crossed = true
                    break
                }
            }
            val viewEntry = livePrice?.let { side * it } ?: viewClose(entryCandles, side, lastEntry)
            val triggerFired = triggerLive &&
                crossed &&
                entryFast[lastEntry] > entrySlow[lastEntry] &&
                viewEntry > entryFast[lastEntry]

            val baseStop = if (touched && lowSinceTouch.isFinite()) min(lowSinceTouch, setup.zoneLow) else setup.zoneLow
            val viewStop = baseStop - config.stopAtr * atrEntryNow
            val viewRisk = viewEntry - viewStop
            val targetCandidate = (if (lowSinceTouch.isFinite()) lowSinceTouch else setup.zoneLow)
            val viewTargetSpec = targetCandidate + config.targetExtension * (setup.c.price - targetCandidate)

            var riskOk = viewRisk > 0.0 && viewRisk <= config.maxRiskAtr * atrEntryNow
            var viewTarget = viewTargetSpec
            var rr = if (viewRisk > 0.0) (viewTargetSpec - viewEntry) / viewRisk else 0.0
            var contractNote = ""
            if (riskOk && rr < config.minRiskReward) {
                viewTarget = viewEntry + config.minRiskReward * viewRisk
                rr = config.minRiskReward
                contractNote = "هدف کتاب (۱٫۲۷۲ اکستنشن) فقط ${format(rr)}R می‌داد؛ طبق قرارداد به کف ۲R بسته شد"
            }
            if (riskOk && rr > config.maxRiskReward) {
                viewTarget = viewEntry + config.maxRiskReward * viewRisk
                rr = config.maxRiskReward
                contractNote = "هدف کتاب از سقف ۳R دورتر بود؛ طبق قرارداد روی ۳R بریده شد"
            }
            if (rr < config.minRiskReward) riskOk = false

            val actionable = triggerFired && riskOk
            val rating = setup.rating
            if (actionable && rating <= bestRating) continue

            val realEntry = side * viewEntry
            val realStop = side * viewStop
            val realTarget = side * viewTarget

            add(
                "الگوی ABCD با نسبت مجاز",
                true,
                "BC/AB = ${format((setup.c.price - setup.b.price) / (setup.a.price - setup.b.price))} " +
                    "(بازهٔ ${format(config.bcMin)} تا ${format(config.bcMax)}) · X→H = " +
                    "${format(setup.impulseHigh - setup.x.price)}",
            )
            add(
                "گروه‌بندی فیبوناچی ناحیهٔ PTA",
                true,
                "${setup.groupLabelsFa.size} عدد: ${setup.groupLabelsFa.joinToString("، ")}",
            )
            add(
                "خطوط هم‌گرا با ناحیه",
                setup.familyBest.values.any { it > 0.0 },
                setup.familyBest.entries.joinToString("، ") { "${it.key}=${format(it.value)}" },
            )
            add("امتیاز Synergy ≥ ${format(config.minRating)}", true, "امتیاز ${format(setup.rating)} از ۱۰")
            add(
                "هم‌راستایی context",
                context == side,
                when (context) {
                    1 -> "context صعودی"
                    -1 -> "context نزولی"
                    else -> "context خنثی"
                },
            )
            add(
                "لمس ناحیهٔ PTA",
                triggerLive,
                "ناحیه ${format(side * setup.zoneLow)} تا ${format(side * setup.zoneHigh)} · " +
                    if (touched) "لمس در کندل $barsSinceTouch قبل" else "بدون لمس",
            )
            add(
                "تریگر اجباری EMA ${config.emaFast}/${config.emaSlow}",
                triggerFired,
                if (crossed) "کراس در ${config.crossLookback} کندل اخیر رخ داد" else "بدون کراس",
            )
            add(
                "حد ضرر در محدودهٔ مجاز",
                riskOk,
                "ریسک ${format(abs(viewRisk) / atrEntryNow)} ATR · سقف ${format(config.maxRiskAtr)} ATR",
            )
            add(
                "R در قرارداد ${format(config.minRiskReward)} تا ${format(config.maxRiskReward)}",
                riskOk,
                "${format(rr)}R" + if (contractNote.isBlank()) "" else " · $contractNote",
            )

            val summary = buildString {
                append(if (side == 1) "خرید" else "فروش").append(" · امتیاز Synergy ").append(format(rating)).append(" از ۱۰")
                append(" · ABCD ").append(format((setup.c.price - setup.b.price) / (setup.a.price - setup.b.price)))
                append(" · ناحیهٔ PTA ").append(format(side * setup.zoneLow)).append(" تا ").append(format(side * setup.zoneHigh))
                if (actionable) {
                    append(" · ورود ").append(priceText(realEntry))
                    append(" · حد ضرر ").append(priceText(realStop))
                    append(" · هدف ").append(priceText(realTarget))
                    append(" (").append(format(rr)).append("R)")
                } else {
                    append(" · بدون ورود: ")
                    append(
                        when {
                            !triggerLive -> if (touched) "مهلت تریگر (${config.maxTriggerBars} کندل) گذشته یا ناحیه باطل شد" else "قیمت هنوز به ناحیه نرسیده"
                            !crossed -> "تریگر EMA فعال نشده"
                            !riskOk -> "قرارداد R برقرار نیست"
                            else -> "شرط ناشناخته"
                        }
                    )
                }
                if (contractNote.isNotBlank()) append(" · ").append(contractNote)
            }

            val entryPath = buildString {
                append("Kane · خط میانه–فیبوناچی · زیگزاگ ").append(format(config.zzSmall))
                append("/").append(format(config.zzBig)).append(" ATR")
                append(" · ABCD: A#").append(setup.a.index).append(" B#").append(setup.b.index).append(" C#").append(setup.c.index)
                append(" · X#").append(setup.x.index).append(" · H=").append(priceText(side * setup.impulseHigh))
                append(" · امتیاز: ").append(setup.partsFa.entries.joinToString("، ") { "${it.key}=${format(it.value)}" })
                append(" · خانواده‌های برخوردکرده: ").append(setup.familyBest.keys.joinToString("، "))
                append(" · هم‌زمانی زمانی: ").append(format(setup.timeScore))
                if (contractNote.isNotBlank()) append(" · ").append(contractNote)
            }

            val evaluation = KaneEvaluation(
                action = if (actionable) (if (side == 1) SignalAction.BUY else SignalAction.SELL) else SignalAction.NO_TRADE,
                readiness = when {
                    actionable && side == 1 -> StrategyReadinessState.SIGNAL_BUY
                    actionable && side == -1 -> StrategyReadinessState.SIGNAL_SELL
                    else -> StrategyReadinessState.WATCH_SETUP
                },
                triggerType = if (actionable) StrategyTriggerType.MEDIAN_LINE_REJECTION else StrategyTriggerType.NONE,
                entry = if (actionable) realEntry else null,
                stopLoss = if (actionable) realStop else null,
                takeProfit = if (actionable) realTarget else null,
                riskReward = if (actionable) rr else null,
                confidence = (rating / 10.0 * 100.0).coerceIn(0.0, 100.0),
                conditions = sideConditions.toList(),
                summaryFa = summary,
                entryPathFa = entryPath,
                rating = rating,
                zoneLow = side * setup.zoneLow,
                zoneHigh = side * setup.zoneHigh,
                ratingPartsFa = setup.partsFa,
                triggerBarTime = entryCandles.last().time,
                livePrice = realEntry,
                contractNoteFa = contractNote,
            )
            if (actionable) {
                bestEvaluation = evaluation
                bestRating = rating
            } else if (bestEvaluation == null) {
                bestEvaluation = evaluation
            }
        }

        // ── لایهٔ خط روندی (درخواست کاربر): شکست کانال و پولبک به کانال ─────────
        // این لایه همان قرارداد پول را رعایت می‌کند (ریسک ۵ دلار، هدف ۲ تا ۳R). اگر ستاپ خط
        // میانه ورود نداشته باشد، کانال می‌تواند وارد شود؛ اگر هر دو باشند، امتیاز بالاتر برنده است.
        val channelSetup = evaluateChannelSetup(entryCandles, config, livePrice)
        if (channelSetup != null && channelSetup.action != SignalAction.NO_TRADE) {
            val medianRating = bestEvaluation?.takeIf { it.action != SignalAction.NO_TRADE }?.rating ?: -1.0
            if (medianRating < 0.0 || channelSetup.rating >= medianRating) return channelSetup
        }

        if (bestEvaluation != null) return bestEvaluation

        val noSetupNote = when {
            considered == 0 -> "هیچ الگوی ABCD معتبر با ناحیهٔ فیبوناچی و خطوط هم‌گرا پیدا نشد."
            ratingRejected > 0 -> "ستاپ ساخته شد ولی امتیاز Synergy آن زیر آستانهٔ ${format(config.minRating)} از ۱۰ است."
            else -> "ستاپ معتبری برای این لحظه وجود ندارد."
        }
        return KaneEvaluation(
            readiness = StrategyReadinessState.BLOCKED,
            summaryFa = noSetupNote,
            livePrice = livePrice ?: entryCandles.last().close,
            triggerBarTime = entryCandles.last().time,
        )
    }

    private fun format(value: Double): String = if (!value.isFinite()) "—" else "%.2f".format(Locale.US, value)

    private fun ratioText(value: Double): String = "%.3f".format(Locale.US, value)

    fun priceText(value: Double): String {
        if (!value.isFinite()) return "—"
        val text = "%.6f".format(Locale.US, value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    // ───────────────── خط روندی: کانال رگرسیونی، شکست و پولبک ─────────────────

    /**
     * کانال روند: خط رگرسیون کمترین‌مربعات روی کلوزهای پنجرهٔ آخر + نوارهای موازی.
     *
     * چرا رگرسیون و نه دو پیوت؟ پیوت‌ها با یک کندل جابه‌جا می‌شوند و کانال «می‌لرزد»؛ خط
     * کمترین‌مربعات پایدار است و همان چیزی است که در روش‌های کلاسیک خط روند/کانال استفاده
     * می‌شود. نوار با صدک ۹۰ فاصلهٔ سقف/کف از خط ساخته می‌شود تا یک شاخهٔ ناهنجار کل عرض را
     * باز نکند. `r2` می‌گوید قیمت واقعاً روی خط راه رفته یا نه (کانالِ بی‌معنا رد می‌شود).
     */
    data class TrendChannel(
        val slope: Double,
        val intercept: Double,
        val midNow: Double,
        val upperNow: Double,
        val lowerNow: Double,
        val bandAtr: Double,
        val r2: Double,
        val bars: Int,
    )

    fun trendChannel(candles: List<Candle>, lookback: Int = 40, atr: Double = 0.0): TrendChannel? {
        if (candles.size < lookback || lookback < 10) return null
        val window = candles.takeLast(lookback)
        val n = window.size
        val meanX = (n - 1) / 2.0
        val meanY = window.sumOf { it.close } / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            sxy += dx * (window[i].close - meanY)
            sxx += dx * dx
        }
        if (sxx <= 1e-12) return null
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        if (!slope.isFinite() || !intercept.isFinite()) return null
        var ssTot = 0.0
        var ssRes = 0.0
        val deviations = DoubleArray(n)
        for (i in 0 until n) {
            val fit = slope * i + intercept
            val dr = window[i].close - fit
            ssRes += dr * dr
            ssTot += (window[i].close - meanY) * (window[i].close - meanY)
            deviations[i] = max(abs(window[i].high - fit), abs(window[i].low - fit))
        }
        val sorted = deviations.sorted()
        val band = sorted[((n - 1) * 0.90).toInt().coerceIn(0, n - 1)]
        val r2 = if (ssTot > 1e-12) (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0
        val midNow = slope * (n - 1) + intercept
        val atrSafe = if (atr.isFinite() && atr > 0.0) atr else 1.0
        return TrendChannel(
            slope = slope,
            intercept = intercept,
            midNow = midNow,
            upperNow = midNow + band,
            lowerNow = midNow - band,
            bandAtr = band / atrSafe,
            r2 = r2,
            bars = n,
        )
    }

    /**
     * ستاپ خط روندی روی کندل‌های ورود: **شکست کانال** یا **پولبک به کانال**.
     *
     * - شکست: کلوز کندل آخر از نوار بالایی (در روند صعودی) رد شود.
     * - پولبک: در ۱۰ کندل اخیر شکست رخ داده باشد، قیمت به داخل کانال برگشته و از خط میانی
     *   رد نشده باشد، و کندل آخر در جهت روند بسته شود.
     *
     * قرارداد پول همان است: ریسک ≤ ۲ ATR، هدف بین ۲ و ۳R (کف ۲R اجباری).
     */
    fun evaluateChannelSetup(
        candles: List<Candle>,
        config: KaneConfig = KaneConfig(),
        livePrice: Double? = null,
    ): KaneEvaluation? {
        if (candles.size < 60) return null
        val atrSeries = atrSeries(candles, config.atrLength)
        val atr = atrSeries.lastOrNull() ?: 0.0
        if (!atr.isFinite() || atr <= 0.0) return null
        val lookback = 40
        // کانال باید از کندل‌های **قبل از** کندل تصمیم ساخته شود؛ اگر کندل شکست داخل رگرسیون
        // بیاید، خودش نوار را بالا می‌کشد و «شکست» هرگز دیده نمی‌شود.
        val channel = trendChannel(candles.dropLast(1), lookback, atr) ?: return null
        // کانال فقط وقتی معنا دارد که قیمت واقعاً روی خط راه رفته باشد و عرضش از نوسان عادی
        // بزرگ‌تر نباشد؛ وگرنه «هر شکستی» سیگنال می‌شود.
        if (channel.r2 < 0.55) return null
        if (channel.bandAtr <= 0.0 || channel.bandAtr > 1.6) return null
        val slopeAtr = abs(channel.slope) / atr
        if (slopeAtr < 0.015) return null

        val side = if (channel.slope > 0.0) 1 else -1
        fun view(price: Double): Double = side * price
        val last = candles.last()
        val viewClose = view(livePrice?.takeIf { it.isFinite() && it > 0.0 } ?: last.close)
        val viewMid = view(channel.midNow)
        val viewUpper = view(channel.upperNow)
        val viewLower = view(channel.lowerNow)

        // «عبور» یعنی کلوز قبلی داخل کانال بوده و کلوز فعلی بیرون آن بسته شده؛ نه اینکه قیمت
        // از قبل بیرون بوده باشد.
        val previousClose = candles[candles.lastIndex - 1].close
        val viewPrevClose = view(previousClose)
        val breakoutNow = viewPrevClose <= viewUpper && viewClose > viewUpper
        // پولبک: شکست در ۱۰ کندل اخیر، بازگشت به داخل کانال، حفظ خط میانی
        var breakoutIdx = -1
        for (i in max(lookback, candles.lastIndex - 10)..candles.lastIndex) {
            val slice = candles.take(i + 1)
            val ch = trendChannel(slice, lookback, atr) ?: continue
            if (view(slice.last().close) > view(ch.upperNow)) breakoutIdx = i
        }
        val pullbackNow = breakoutIdx in (candles.lastIndex - 10) until candles.lastIndex &&
            viewClose <= viewUpper && viewClose >= viewMid &&
            (if (side == 1) last.close >= last.open else last.close <= last.open)

        if (!breakoutNow && !pullbackNow) return null

        val triggerType = if (breakoutNow) StrategyTriggerType.KANE_CHANNEL_BREAKOUT
        else StrategyTriggerType.KANE_CHANNEL_PULLBACK
        val entry = viewClose
        // حد ضرر کلاسیک خط روندی: در «شکست»، پشت همان نوارِ شکسته‌شده (نه خط میانی دور) و در
        // «پولبک»، پشت خط میانی. بافر ۰٫۱۵ ATR تا نویز کندل ورود، حد ضرر را نزند.
        val viewStop = if (breakoutNow) viewUpper - 0.15 * atr else viewMid - 0.15 * atr
        val risk = entry - viewStop
        if (risk <= 0.0 || risk > 2.0 * atr) return null
        // سقف «دویدن دنبال قیمت»: از نوار شکسته‌شده بیشتر از ۱٫۵ ATR دور نشویم.
        if (breakoutNow && entry - viewUpper > 1.5 * atr) return null
        var rr = 2.5
        if (rr < config.minRiskReward) rr = config.minRiskReward
        if (rr > config.maxRiskReward) rr = config.maxRiskReward
        val target = entry + rr * risk
        val actionable = breakoutNow || pullbackNow

        val conditions = listOf(
            StrategyConditionCheck(1, "کانال روند معتبر (R² ≥ ۰٫۵۵)", true, "R² = ${format(channel.r2)}"),
            StrategyConditionCheck(2, "عرض کانال در محدودهٔ نوسان", true, "${format(channel.bandAtr)} ATR"),
            StrategyConditionCheck(
                3,
                if (breakoutNow) "شکست نوار بالایی کانال" else "پولبک به کانال پس از شکست",
                true,
                "کلوز ${priceText(side * entry)} · نوار ${priceText(side * viewUpper)}",
            ),
            StrategyConditionCheck(
                4,
                "حد ضرر پشت خط میانی و ریسک ≤ ۲ ATR",
                risk <= 2.0 * atr,
                "${format(risk / atr)} ATR",
            ),
            StrategyConditionCheck(
                5,
                "R در قرارداد ${format(config.minRiskReward)} تا ${format(config.maxRiskReward)}",
                rr >= config.minRiskReward,
                "${format(rr)}R",
            ),
        )
        val summary = buildString {
            append(if (side == 1) "خرید" else "فروش")
            append(" · ")
            append(if (breakoutNow) "شکست کانال" else "پولبک به کانال")
            append(" · خط میانه ").append(priceText(channel.midNow))
            append(" · ورود ").append(priceText(side * entry))
            append(" · حد ضرر ").append(priceText(side * viewStop))
            append(" · هدف ").append(priceText(side * target))
            append(" (").append(format(rr)).append("R)")
        }
        return KaneEvaluation(
            action = if (side == 1) SignalAction.BUY else SignalAction.SELL,
            readiness = if (side == 1) StrategyReadinessState.SIGNAL_BUY else StrategyReadinessState.SIGNAL_SELL,
            triggerType = triggerType,
            entry = side * entry,
            stopLoss = side * viewStop,
            takeProfit = side * target,
            riskReward = rr,
            confidence = (channel.r2.coerceIn(0.0, 1.0) * 100.0),
            conditions = conditions,
            summaryFa = summary,
            entryPathFa = "خط روندی: کانال رگرسیونی ۴۰ کندلی + ${if (breakoutNow) "شکست" else "پولبک"}",
            rating = (channel.r2 * 10.0).coerceIn(0.0, 10.0),
            triggerBarTime = last.time,
            livePrice = side * entry,
            zoneLow = side * viewLower,
            zoneHigh = side * viewUpper,
            contractNoteFa = "هدف روی ۲ تا ۳R بسته شد (قرارداد این اپ)",
        ).takeIf { actionable }
    }
}
