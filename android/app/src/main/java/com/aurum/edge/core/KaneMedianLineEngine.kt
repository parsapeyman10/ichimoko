package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * استراتژی «خط میانه و هم‌افزایی فیبوناچی» به سبک کین (`kane_median_line_fibonacci_synergy`).
 *
 * چه چیزی از کتاب کین گرفته شده و چه چیزی نه — صریح و قابل حسابرسی:
 * **گرفته شده:** ایدهٔ هم‌افزایی (وقتی خط میانهٔ اندروز، موازی‌های آن و کلاستر فیبوناچی به یک ناحیهٔ
 * واحد اشاره کنند، آن ناحیه «ناحیهٔ معاملهٔ بالقوه» است)، و ساخت فورک از سه پیوت متناوب
 * (`P1` تا وسط `P2-P3`، سپس موازی از `P2` و `P3`).
 * **گرفته نشده:** قواعد اختصاصی کتاب کین منتشر نشده‌اند (کتاب چاپی و خصوصی است)، پس این‌جا یک
 * نسخهٔ **مکانیزهٔ شفاف** پیاده شده است: تمام تصمیم‌ها از هندسه و آستانه‌های ثابت ATR می‌آیند، انتخاب
 * پیوت قطعی است (فراکتال + برجستگی ATR + تناوب)، و هر معامله سه‌گانهٔ پیوت خودش را ثبت می‌کند تا
 * بعداً قابل حسابرسی باشد. هیچ پارامتری برای یک نماد خاص تیون نمی‌شود.
 *
 * این موتور **خالص** است: فقط کندل و تنظیمات می‌گیرد و نتیجه می‌دهد؛ نه به شبکه، نه به ژورنال و نه
 * به لایهٔ عصبی وابسته نیست. قرارداد پول (`$۵` دقیق و `R × $۵`) در لایهٔ اجرا اعمال می‌شود، نه این‌جا.
 */
enum class KaneEntryMode(val labelFa: String) {
    PARALLEL_REJECTION("رد در موازی مقابل و بازگشت به خط میانه"),
    MEDIAN_CONTINUATION("پولبک به خط میانه در جهت فورک"),
    BOTH("هر دو حالت (هر کدام اول فعال شود)"),
}

/** یک پیوت تأییدشدهٔ ساختار (سقف یا کف) روی تایم‌فریم ساختار. */
data class KanePivot(
    val index: Int,
    val time: Long,
    val price: Double,
    val isHigh: Boolean,
) {
    val labelFa: String get() = if (isHigh) "سقف" else "کف"
}

/**
 * فورک خط میانهٔ اندروز: خط میانه از `P1` و از وسط `P2-P3` می‌گذرد؛ دو موازی با همان شیب از
 * `P2` و `P3` کشیده می‌شوند.
 */
data class KaneFork(
    val p1: KanePivot,
    val p2: KanePivot,
    val p3: KanePivot,
    val slopePerBar: Double,
) {
    val midIndex: Double get() = (p2.index + p3.index) / 2.0
    val midPrice: Double get() = (p2.price + p3.price) / 2.0

    /** فورک صعودی یعنی `P1` یک کف بوده و ساختار سقف-کف-سقف بالا رفته است. */
    val isBullishStructure: Boolean get() = !p1.isHigh

    fun medianAt(index: Int): Double = p1.price + slopePerBar * (index - p1.index)

    fun lineThrough(pivot: KanePivot, index: Int): Double =
        pivot.price + slopePerBar * (index - pivot.index)

    fun upperAt(index: Int): Double = max(lineThrough(p2, index), lineThrough(p3, index))

    fun lowerAt(index: Int): Double = min(lineThrough(p2, index), lineThrough(p3, index))

    val anchorsFa: String
        get() = "P1 ${p1.labelFa} #${p1.index} · P2 ${p2.labelFa} #${p2.index} · P3 ${p3.labelFa} #${p3.index}"
}

/** یک سطح فیبوناچی روی موج `P2-P3` با برچسب فارسی. */
data class KaneFibLevel(val labelFa: String, val price: Double)

object KaneFibonacci {
    /**
     * سطوح فیبوناچی موج `P2-P3` به‌صورت متقارن:
     * سه اصلاح داخل موج (۳۸٫۲٪، ۵۰٪، ۶۱٫۸٪) و اکستنشن‌های ۱۲۷٫۲٪ و ۱۶۱٫۸٪ در **هر دو جهت**
     * (بالای سقف موج و پایین کف موج). دلیل تقارن: در فورک صعودی، هم رد در موازی بالا (اکستنشن بالای
     * موج) و هم پولبک به خط میانه (باند اصلاح) باید بتوانند هم‌افزایی فیبوناچی بسازند.
     */
    fun levels(p2Price: Double, p3Price: Double): List<KaneFibLevel> {
        val high = max(p2Price, p3Price)
        val low = min(p2Price, p3Price)
        val range = high - low
        if (range <= 0.0) return emptyList()
        return listOf(
            KaneFibLevel("اصلاح ۳۸٫۲٪", low + range * 0.382),
            KaneFibLevel("اصلاح ۵۰٪", low + range * 0.5),
            KaneFibLevel("اصلاح ۶۱٫۸٪", low + range * 0.618),
            KaneFibLevel("اکستنشن ۱۲۷٫۲٪ بالا", high + range * 0.272),
            KaneFibLevel("اکستنشن ۱۶۱٫۸٪ بالا", high + range * 0.618),
            KaneFibLevel("اکستنشن ۱۲۷٫۲٪ پایین", low - range * 0.272),
            KaneFibLevel("اکستنشن ۱۶۱٫۸٪ پایین", low - range * 0.618),
        )
    }

    /** «هم‌افزایی» = حداقل یک سطح داخل باند تلورانس حول ناحیهٔ لمس. */
    fun cluster(levels: List<KaneFibLevel>, target: Double, tolerance: Double): List<KaneFibLevel> =
        levels.filter { abs(it.price - target) <= tolerance }

    /** باند اصلاح ۳۸٫۲٪ تا ۶۱٫۸٪ موج؛ ناحیه‌ای که خط میانه معمولاً داخلش می‌افتد. */
    fun retracementZone(p2Price: Double, p3Price: Double): ClosedFloatingPointRange<Double>? {
        val high = max(p2Price, p3Price)
        val low = min(p2Price, p3Price)
        val range = high - low
        if (range <= 0.0) return null
        return (low + range * 0.382)..(low + range * 0.618)
    }
}

/** تنظیمات ثابت موتور خط میانه–فیبوناچی. تمام مقادیر از تئوری آمده‌اند، نه از بهینه‌سازی. */
data class KaneConfig(
    val pivotLeft: Int = 3,
    val pivotRight: Int = 3,
    val minPivotProminenceAtr: Double = 0.8,
    val minPivotSeparationBars: Int = 6,
    /** تلورانس لمس خط/موازی روی تایم‌فریم ورود. */
    val touchToleranceAtr: Double = 0.25,
    /** باند هم‌افزایی فیبوناچی. */
    val fibClusterToleranceAtr: Double = 0.15,
    /** حداقل هم‌راستایی فیبوناچی برای تأیید ناحیهٔ معامله؛ ۲ مورد یا بیشتر «هم‌افزایی قوی» است. */
    val minFibConfluence: Int = 1,
    val minWickRatio: Double = 0.35,
    val slBufferAtr: Double = 0.25,
    val maxStopAtr: Double = 3.0,
    val minStopAtr: Double = 0.30,
    val minEfficiencyRatio: Double = 0.20,
    val minRoomToMedianAtr: Double = 0.50,
    val minRiskReward: Double = 1.5,
    val maxRiskReward: Double = 3.0,
    val maxForkFadeAtr: Double = 1.0,
    val minSlopeAtrPerBar: Double = 0.02,
    val entryMode: KaneEntryMode = KaneEntryMode.BOTH,
    val minMacroBars: Int = 60,
    val minEntryBars: Int = 30,
)

/** نتیجهٔ کامل سنجش یک نماد با موتور خط میانه–فیبوناچی. */
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
    val fork: KaneFork? = null,
    val atrMacro: Double = 0.0,
    val atrEntry: Double = 0.0,
    val medianLine: Double = 0.0,
    val upperParallel: Double = 0.0,
    val lowerParallel: Double = 0.0,
    val fibMatchesFa: List<String> = emptyList(),
    val modeUsed: KaneEntryMode? = null,
    val triggerBarTime: Long = 0L,
    val livePrice: Double = 0.0,
)

object KaneMedianLineEngine {

    // ───────────────────────── ابزارهای خالص ─────────────────────────

    /** میانگین دامنهٔ واقعی (ATR) سادهٔ `period` کندل آخر. */
    fun averageTrueRange(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < 2 || period < 1) return 0.0
        val from = max(1, candles.size - period)
        var sum = 0.0
        var count = 0
        for (i in from until candles.size) {
            val previousClose = candles[i - 1].close
            val trueRange = max(
                candles[i].high - candles[i].low,
                max(abs(candles[i].high - previousClose), abs(candles[i].low - previousClose)),
            )
            sum += trueRange
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** نسبت کارایی کافمن: حرکت خالص تقسیم بر مسیر طی‌شده. نزدیک ۱ یعنی روند، نزدیک ۰ یعنی نویز. */
    fun efficiencyRatio(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size <= period) return 0.0
        val last = candles.last().close
        val first = candles[candles.size - 1 - period].close
        var travelled = 0.0
        for (i in (candles.size - period) until candles.size) {
            travelled += abs(candles[i].close - candles[i - 1].close)
        }
        if (travelled <= 0.0) return 0.0
        return abs(last - first) / travelled
    }

    /**
     * تشخیص قطعی پیوت‌ها: فراکتال (`left` کندل چپ و `right` کندل راست)، سپس تناوب سقف/کف و
     * حذف پیوت‌های کم‌برجسته‌تر از `minPivotProminenceAtr × ATR`. خروجی همیشه قطعی است و به
     * ترتیب زمان برمی‌گردد.
     */
    fun detectPivots(candles: List<Candle>, config: KaneConfig = KaneConfig(), atr: Double): List<KanePivot> {
        if (candles.size < config.pivotLeft + config.pivotRight + 2 || atr <= 0.0) return emptyList()
        val raw = mutableListOf<KanePivot>()

        for (i in config.pivotLeft until candles.size - config.pivotRight) {
            val bar = candles[i]
            var isHigh = true
            var isLow = true
            for (offset in 1..config.pivotLeft) {
                val other = candles[i - offset]
                if (other.high >= bar.high) isHigh = false
                if (other.low <= bar.low) isLow = false
            }
            for (offset in 1..config.pivotRight) {
                val other = candles[i + offset]
                if (other.high > bar.high) isHigh = false
                if (other.low < bar.low) isLow = false
            }
            when {
                isHigh -> raw.add(KanePivot(i, bar.time, bar.high, true))
                isLow -> raw.add(KanePivot(i, bar.time, bar.low, false))
            }
        }

        // تناوب: دو سقف پشت‌سرهم یعنی فقط بلندترین سقف معتبر است؛ دو کف پشت‌سرهم یعنی فقط پایین‌ترین کف.
        val alternating = mutableListOf<KanePivot>()
        for (pivot in raw) {
            val previous = alternating.lastOrNull()
            if (previous == null) {
                alternating.add(pivot)
                continue
            }
            if (previous.isHigh == pivot.isHigh) {
                val keepNew = if (pivot.isHigh) pivot.price >= previous.price else pivot.price <= previous.price
                if (keepNew) alternating[alternating.lastIndex] = pivot
            } else {
                if (pivot.index - previous.index >= config.minPivotSeparationBars) alternating.add(pivot)
            }
        }

        // برجستگی: هر پیوت باید نسبت به پیوت مخالف قبلی خودش به‌اندازهٔ کافی دور باشد.
        val threshold = config.minPivotProminenceAtr * atr
        val prominent = mutableListOf<KanePivot>()
        for (pivot in alternating) {
            val reference = prominent.lastOrNull()
            if (reference == null) {
                prominent.add(pivot)
            } else if (abs(pivot.price - reference.price) >= threshold) {
                prominent.add(pivot)
            }
        }
        return prominent
    }

    /** ساخت فورک از سه پیوت متناوب؛ اگر هندسه بی‌معنی باشد `null`. */
    fun buildFork(p1: KanePivot, p2: KanePivot, p3: KanePivot): KaneFork? {
        if (p1.isHigh == p2.isHigh || p2.isHigh == p3.isHigh) return null
        val midIndex = (p2.index + p3.index) / 2.0
        val midPrice = (p2.price + p3.price) / 2.0
        val denominator = midIndex - p1.index
        if (abs(denominator) < 0.5) return null
        val slope = (midPrice - p1.price) / denominator
        if (!slope.isFinite()) return null
        return KaneFork(p1 = p1, p2 = p2, p3 = p3, slopePerBar = slope)
    }

    /**
     * سطوح فیبوناچی که با ناحیهٔ لمس هم‌راستا شده‌اند: یا خود باند اصلاح ۳۸٫۲–۶۱٫۸٪، یا سطح
     * مشخصی که داخل `tolerance` بیفتد. خروجی برچسب‌های فارسی برای نمایش در چک‌لیست است.
     */
    fun fibConfluence(fork: KaneFork, touchLevel: Double, tolerance: Double): List<String> {
        if (touchLevel <= 0.0 || tolerance <= 0.0) return emptyList()
        val labels = mutableListOf<String>()
        val zone = KaneFibonacci.retracementZone(fork.p2.price, fork.p3.price)
        if (zone != null && touchLevel in (zone.start - tolerance)..(zone.endInclusive + tolerance)) {
            labels.add("باند اصلاح ۳۸٫۲–۶۱٫۸٪ موج P2–P3")
        }
        KaneFibonacci.levels(fork.p2.price, fork.p3.price)
            .filter { abs(it.price - touchLevel) <= tolerance }
            .forEach { labels.add("${it.labelFa} در ${priceText(it.price)}") }
        return labels
    }

    // ───────────────────────── سنجش کامل ─────────────────────────

    fun evaluate(
        macroCandles: List<Candle>,
        entryCandles: List<Candle>,
        config: KaneConfig = KaneConfig(),
        livePriceOverride: Double? = null,
    ): KaneEvaluation {
        val conditions = mutableListOf<StrategyConditionCheck>()
        var order = 0
        fun addCondition(titleFa: String, passed: Boolean, detailFa: String) {
            order += 1
            conditions.add(StrategyConditionCheck(index = order, titleFa = titleFa, passed = passed, detailFa = detailFa))
        }

        if (macroCandles.size < config.minMacroBars || entryCandles.size < config.minEntryBars) {
            return KaneEvaluation(
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                summaryFa = "برای فورک خط میانه به حداقل ${config.minMacroBars} کندل ساختار و " +
                    "${config.minEntryBars} کندل ورود نیاز است (فعلی: ${macroCandles.size}/${entryCandles.size}).",
                livePrice = entryCandles.lastOrNull()?.close ?: 0.0,
            )
        }

        val atrMacro = averageTrueRange(macroCandles)
        val atrEntry = averageTrueRange(entryCandles)
        if (atrMacro <= 0.0 || atrEntry <= 0.0) {
            return KaneEvaluation(
                readiness = StrategyReadinessState.INSUFFICIENT_DATA,
                summaryFa = "ATR صفر یا نامعتبر است؛ فورک قابل محاسبه نیست.",
                livePrice = entryCandles.last().close,
            )
        }

        val pivots = detectPivots(macroCandles, config, atrMacro)
        val fork = if (pivots.size >= 3) {
            buildFork(pivots[pivots.size - 3], pivots[pivots.size - 2], pivots[pivots.size - 1])
        } else {
            null
        }

        val bar = entryCandles.last()
        val price = livePriceOverride?.takeIf { it.isFinite() && it > 0.0 } ?: bar.close
        val macroIndex = macroCandles.lastIndex

        if (fork == null) {
            addCondition("ساختار ۴ ساعته: سه پیوت متناوب", false, "تعداد پیوت معتبر: ${pivots.size}")
            return KaneEvaluation(
                readiness = StrategyReadinessState.BLOCKED,
                conditions = conditions,
                summaryFa = "پیوت کافی برای ساخت فورک خط میانه پیدا نشد (${pivots.size} پیوت).",
                atrMacro = atrMacro,
                atrEntry = atrEntry,
                livePrice = price,
                triggerBarTime = bar.time,
            )
        }

        val median = fork.medianAt(macroIndex)
        val upper = fork.upperAt(macroIndex)
        val lower = fork.lowerAt(macroIndex)
        val slopeAtr = fork.slopePerBar / atrMacro
        val efficiency = efficiencyRatio(macroCandles)

        addCondition(
            titleFa = "ساختار ۴ ساعته: سه پیوت متناوب",
            passed = true,
            detailFa = "${fork.anchorsFa} · شیب ${"%.3f".format(Locale.US, slopeAtr)} ATR/کندل",
        )
        addCondition(
            titleFa = "شیب فورک معنادار است",
            passed = abs(slopeAtr) >= config.minSlopeAtrPerBar,
            detailFa = "|شیب| = ${"%.3f".format(Locale.US, abs(slopeAtr))} ATR/کندل · حداقل " +
                "${"%.3f".format(Locale.US, config.minSlopeAtrPerBar)}",
        )

        val fadeTolerance = config.maxForkFadeAtr * atrMacro
        val insideCorridor = price in (lower - fadeTolerance)..(upper + fadeTolerance)
        addCondition(
            titleFa = "فورک ابطال نشده (کلوز داخل کریدور)",
            passed = insideCorridor,
            detailFa = "قیمت ${priceText(price)} · موازی پایین ${priceText(lower)} · موازی بالا ${priceText(upper)}",
        )
        addCondition(
            titleFa = "رژیم روند ۴ ساعته",
            passed = efficiency >= config.minEfficiencyRatio,
            detailFa = "ER₁₄ = ${"%.2f".format(Locale.US, efficiency)} · حداقل " +
                "${"%.2f".format(Locale.US, config.minEfficiencyRatio)}",
        )

        // ── ناحیهٔ لمس و هم‌افزایی فیبوناچی ──
        val tolerance = config.touchToleranceAtr * atrEntry
        val touchedUpper = bar.high >= upper - tolerance
        val touchedLower = bar.low <= lower + tolerance
        val touchedMedian = bar.low <= median + tolerance && bar.high >= median - tolerance

        val upperWick = if (bar.high - bar.low > 0.0) (bar.high - max(bar.open, bar.close)) / (bar.high - bar.low) else 0.0
        val lowerWick = if (bar.high - bar.low > 0.0) (min(bar.open, bar.close) - bar.low) / (bar.high - bar.low) else 0.0

        var action = SignalAction.NO_TRADE
        var modeUsed: KaneEntryMode? = null
        var structuralLevel = 0.0
        var touchLevel = 0.0

        if (config.entryMode != KaneEntryMode.MEDIAN_CONTINUATION && touchedUpper &&
            bar.close < upper - 0.05 * atrEntry && upperWick >= config.minWickRatio
        ) {
            action = SignalAction.SELL
            modeUsed = KaneEntryMode.PARALLEL_REJECTION
            touchLevel = upper
            structuralLevel = max(bar.high, upper)
        } else if (config.entryMode != KaneEntryMode.MEDIAN_CONTINUATION && touchedLower &&
            bar.close > lower + 0.05 * atrEntry && lowerWick >= config.minWickRatio
        ) {
            action = SignalAction.BUY
            modeUsed = KaneEntryMode.PARALLEL_REJECTION
            touchLevel = lower
            structuralLevel = min(bar.low, lower)
        } else if (config.entryMode != KaneEntryMode.PARALLEL_REJECTION && touchedMedian) {
            if (bar.close > median + 0.05 * atrEntry && lowerWick >= config.minWickRatio) {
                action = SignalAction.BUY
                modeUsed = KaneEntryMode.MEDIAN_CONTINUATION
                touchLevel = median
                structuralLevel = min(bar.low, median)
            } else if (bar.close < median - 0.05 * atrEntry && upperWick >= config.minWickRatio) {
                action = SignalAction.SELL
                modeUsed = KaneEntryMode.MEDIAN_CONTINUATION
                touchLevel = median
                structuralLevel = max(bar.high, median)
            }
        }

        // هم‌افزایی فیبوناچی: باند اصلاح ۳۸٫۲–۶۱٫۸٪ موج `P2-P3` به‌عنوان یک ناحیهٔ پیوسته در نظر
        // گرفته می‌شود (نه یک خط نازک) و هر سطح مشخص فیبوناچی که داخل تلورانس بیفتد به آن اضافه
        // می‌شود. تلورانس روی مقیاس ساختار (ATR ۴ ساعته) و ۵٪ دامنهٔ موج سنجیده می‌شود، چون خود موج
        // در مقیاس ساختار است نه در مقیاس ورود.
        val legRange = abs(fork.p2.price - fork.p3.price)
        val fibTolerance = max(config.fibClusterToleranceAtr * atrMacro, 0.05 * legRange)
        val fibMatches: List<String> = if (touchLevel <= 0.0) {
            emptyList()
        } else {
            fibConfluence(fork, touchLevel, fibTolerance)
        }
        val synergyStrong = fibMatches.size >= 2

        val touchLabelFa = when {
            modeUsed == KaneEntryMode.PARALLEL_REJECTION && action == SignalAction.SELL -> "موازی بالا"
            modeUsed == KaneEntryMode.PARALLEL_REJECTION && action == SignalAction.BUY -> "موازی پایین"
            modeUsed == KaneEntryMode.MEDIAN_CONTINUATION -> "خط میانه"
            else -> "بدون لمس"
        }

        addCondition(
            titleFa = "ناحیهٔ لمس (PTA)",
            passed = action != SignalAction.NO_TRADE,
            detailFa = "لمس $touchLabelFa در ${priceText(touchLevel)} (تلورانس ${"%.2f".format(Locale.US, config.touchToleranceAtr)} ATR)",
        )
        addCondition(
            titleFa = "هم‌افزایی فیبوناچی",
            passed = fibMatches.size >= config.minFibConfluence,
            detailFa = if (fibMatches.isEmpty()) {
                "هیچ هم‌راستایی فیبوناچی داخل ${"%.2f".format(Locale.US, config.fibClusterToleranceAtr)} ATR ناحیهٔ لمس نیست"
            } else {
                "${fibMatches.size} هم‌راستایی${if (synergyStrong) " (قوی)" else ""}: ${fibMatches.joinToString("، ")}"
            },
        )
        addCondition(
            titleFa = "کندل تایید ۱ ساعته",
            passed = action != SignalAction.NO_TRADE,
            detailFa = "ویک مخالف ${"%.2f".format(Locale.US, if (action == SignalAction.SELL) upperWick else lowerWick)} · " +
                "حداقل ${"%.2f".format(Locale.US, config.minWickRatio)}",
        )

        // ── حد ضرر، حد سود و قرارداد R ──
        var stop: Double? = null
        var target: Double? = null
        var riskReward: Double? = null
        var roomToMedianAtr = 0.0
        var targetClampedToCap = false

        if (action == SignalAction.BUY) {
            stop = structuralLevel - config.slBufferAtr * atrEntry
            roomToMedianAtr = if (median > price) (median - price) / atrEntry else 0.0
        } else if (action == SignalAction.SELL) {
            stop = structuralLevel + config.slBufferAtr * atrEntry
            roomToMedianAtr = if (median < price) (price - median) / atrEntry else 0.0
        }

        addCondition(
            titleFa = "اتاق حرکت تا خط میانه",
            passed = roomToMedianAtr >= config.minRoomToMedianAtr,
            detailFa = "${"%.2f".format(Locale.US, roomToMedianAtr)} ATR · حداقل " +
                "${"%.2f".format(Locale.US, config.minRoomToMedianAtr)}",
        )

        val risk = if (stop != null) abs(price - stop) else 0.0
        val stopAtr = if (atrEntry > 0.0) risk / atrEntry else 0.0
        addCondition(
            titleFa = "فاصلهٔ حد ضرر ساختاری",
            passed = stopAtr in config.minStopAtr..config.maxStopAtr,
            detailFa = "${"%.2f".format(Locale.US, stopAtr)} ATR · بازهٔ مجاز " +
                "${"%.2f".format(Locale.US, config.minStopAtr)} تا ${"%.2f".format(Locale.US, config.maxStopAtr)}",
        )

        if (action != SignalAction.NO_TRADE && risk > 0.0) {
            val candidates = when (action) {
                SignalAction.BUY -> listOf(median, upper, upper + (upper - median))
                SignalAction.SELL -> listOf(median, lower, lower - (median - lower))
                else -> emptyList()
            }.filter { if (action == SignalAction.BUY) it > price else it < price }

            val scored = candidates
                .map { it to abs(it - price) / risk }
                .filter { it.second >= config.minRiskReward }
                .sortedBy { it.second }

            val chosen = scored.firstOrNull { it.second <= config.maxRiskReward }
            if (chosen != null) {
                target = chosen.first
                riskReward = chosen.second
            } else if (scored.isNotEmpty()) {
                // هدف هندسی دورتر از سقف ۳R است؛ به‌جای گزارش R غیرواقعی، هدف روی همان سقف بریده
                // می‌شود تا حد سود و نسبت R در ژورنال و قرارداد پول دقیقاً یکی باشند.
                val cappedRisk = config.maxRiskReward * risk
                target = if (action == SignalAction.BUY) price + cappedRisk else price - cappedRisk
                riskReward = config.maxRiskReward
                targetClampedToCap = true
            }
        }

        val resolvedRiskReward = riskReward
        addCondition(
            titleFa = "نسبت سود به ضرر (R)",
            passed = resolvedRiskReward != null &&
                resolvedRiskReward >= config.minRiskReward &&
                resolvedRiskReward <= config.maxRiskReward,
            detailFa = resolvedRiskReward?.let { value ->
                "${"%.2f".format(Locale.US, value)}R · بازهٔ مجاز " +
                    "${"%.1f".format(Locale.US, config.minRiskReward)} تا ${"%.1f".format(Locale.US, config.maxRiskReward)}"
            } ?: "هدف هندسی فورک کمتر از ${"%.1f".format(Locale.US, config.minRiskReward)}R می‌دهد",
        )

        // امتیازی (بدون گیت): تقارن ABCD روی چهار پیوت آخر.
        val abcdNote = if (pivots.size >= 4) {
            val a = pivots[pivots.size - 4]
            val b = pivots[pivots.size - 3]
            val c = pivots[pivots.size - 2]
            val d = pivots[pivots.size - 1]
            val ab = abs(b.price - a.price)
            val bc = abs(c.price - b.price)
            val cd = abs(d.price - c.price)
            val bcRatio = if (ab > 0.0) bc / ab else 0.0
            val cdRatio = if (bc > 0.0) cd / bc else 0.0
            val symmetric = bcRatio in 0.382..0.786 && cdRatio in 0.382..0.886
            "BC/AB=${"%.2f".format(Locale.US, bcRatio)} · CD/BC=${"%.2f".format(Locale.US, cdRatio)}" +
                if (symmetric) " · هم‌ساخت" else " · ناهم‌ساخت"
        } else {
            "پیوت کافی برای الگوی چهارنقطه‌ای نیست"
        }

        val passedHard = conditions.count { it.passed }
        val allHardPassed = conditions.all { it.passed }
        val finalStop = stop
        val finalTarget = target
        val actionable = allHardPassed && action != SignalAction.NO_TRADE &&
            finalStop != null && finalTarget != null && resolvedRiskReward != null

        val readiness = when {
            actionable && action == SignalAction.BUY -> StrategyReadinessState.SIGNAL_BUY
            actionable && action == SignalAction.SELL -> StrategyReadinessState.SIGNAL_SELL
            action != SignalAction.NO_TRADE -> StrategyReadinessState.WATCH_SETUP
            else -> StrategyReadinessState.BLOCKED
        }

        val triggerType = when (modeUsed) {
            KaneEntryMode.PARALLEL_REJECTION -> StrategyTriggerType.MEDIAN_LINE_REJECTION
            KaneEntryMode.MEDIAN_CONTINUATION -> StrategyTriggerType.MEDIAN_LINE_CONTINUATION
            else -> StrategyTriggerType.NONE
        }

        val baseConfidence = if (conditions.isEmpty()) 0.0 else {
            passedHard.toDouble() / conditions.size.toDouble() * 100.0
        }
        val confidence = (baseConfidence + if (synergyStrong) 8.0 else 0.0).coerceIn(0.0, 100.0)

        val summary = buildString {
            append("فورک ").append(if (fork.isBullishStructure) "صعودی" else "نزولی").append(" · ")
            append(fork.anchorsFa).append(" · ")
            append("خط میانه ").append(priceText(median))
            append(" · موازی بالا ").append(priceText(upper))
            append(" · موازی پایین ").append(priceText(lower))
            if (!actionable) {
                append(" — ورودی ندارد؛ ")
                val failed = conditions.filterNot { it.passed }.map { it.titleFa }
                append(if (failed.isEmpty()) "هیچ ماشهٔ لمس تایید نشده" else failed.joinToString("؛ "))
            } else {
                append(" — ").append(modeUsed?.labelFa ?: "")
                if (synergyStrong) append(" · هم‌افزایی فیبوناچی قوی (${fibMatches.size} مورد)")
                append("؛ ورود ").append(priceText(price))
                if (finalStop != null) append(" · حد ضرر ").append(priceText(finalStop))
                if (finalTarget != null) append(" · هدف ").append(priceText(finalTarget))
                if (resolvedRiskReward != null) {
                    append(" (").append("%.2f".format(Locale.US, resolvedRiskReward)).append("R")
                    if (targetClampedToCap) append(" — سقف ۳R")
                    append(")")
                }
            }
        }

        val entryPath = buildString {
            append("Kane · خط میانه–فیبوناچی · حالت: ").append(modeUsed?.labelFa ?: "بدون ماشه")
            append(" · سه‌گانه: ").append(fork.anchorsFa)
            append(" · شیب ").append("%.3f".format(Locale.US, slopeAtr)).append(" ATR/کندل")
            append(" · ER ").append("%.2f".format(Locale.US, efficiency))
            append(" · فیبو: ").append(if (fibMatches.isEmpty()) "بدون هم‌راستایی" else fibMatches.joinToString("، "))
            append(" · ABCD: ").append(abcdNote)
            append(" · ATR ساختار ").append(priceText(atrMacro)).append(" / ورود ").append(priceText(atrEntry))
        }

        return KaneEvaluation(
            action = if (actionable) action else SignalAction.NO_TRADE,
            readiness = readiness,
            triggerType = if (actionable) triggerType else StrategyTriggerType.NONE,
            entry = if (actionable) price else null,
            stopLoss = if (actionable) finalStop else null,
            takeProfit = if (actionable) finalTarget else null,
            riskReward = if (actionable) resolvedRiskReward else null,
            confidence = confidence,
            conditions = conditions,
            summaryFa = summary,
            entryPathFa = entryPath,
            fork = fork,
            atrMacro = atrMacro,
            atrEntry = atrEntry,
            medianLine = median,
            upperParallel = upper,
            lowerParallel = lower,
            fibMatchesFa = fibMatches,
            modeUsed = modeUsed,
            triggerBarTime = bar.time,
            livePrice = price,
        )
    }

    /** قیمت با حداکثر ۶ رقم اعشار و بدون صفرهای اضافی. */
    fun priceText(value: Double): String {
        if (!value.isFinite()) return "—"
        val text = "%.6f".format(Locale.US, value)
        return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
