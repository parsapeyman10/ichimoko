package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

/**
 * موتور **نئوویو (NeoWave — گلن نیلی، Mastering Elliott Wave)** به‌صورت یک «لایهٔ تأیید» اختیاری.
 *
 * ## ساختار پیاده‌سازی (بر اساس متد منتشرشدهٔ نیلی)
 * ۱. **مونوویو** با زیگزاگ ATR-تطبیقی؛ سپس **قواعد نسبت طول موج‌ها** (Retracement Rules 1..7)
 *    که بر پایهٔ نسبت `m2/m1` و موقعیت `m0` تعیین می‌کنند موج بعدی چه رفتاری باید داشته باشد.
 * ۲. **برچسب ساختاری** (`:5`، `:F3`، `:c3`، `:L3`، `:L5`، `:s5`) که نشان می‌دهد موج در
 *    کجای ساختار قرار دارد؛ این برچسب‌ها همان چیزی هستند که «لیست ساختاری» impulse را می‌سازند
 *    (`:5 – :F3 – :5 – :F3 – :L5`).
 * ۳. **الگوها:** Impulse (۱۲ قاعده)، ZigZag، Flat، Triangle (همگرا/واگرا/خنثی)،
 *    Diametric ۷موجی، Symmetrical ۹موجی و Extracting Triangle.
 * ۴. **قواعد سخت (Hard Rules):** شکستنشان الگو را کاملاً رد می‌کند (مثل موج ۴ روی موج ۱).
 * ۵. **تأیید پس‌ساختاری (Post-Constructive / Self-Confirmation):** متد نیلی می‌گوید شمارش تا
 *    وقتی که حرکت بعدی آن را تأیید نکند معتبر نیست؛ این لایه همان بررسی را انجام می‌دهد.
 *
 * ## قانون خنثی‌بودن (Zero-Effect Contract)
 * وقتی `BaseStrategyConfig.neoWaveEnabled == false` باشد (پیش‌فرض)، هیچ‌کدام از این توابع فراخوانی
 * نمی‌شوند و خروجی موتور استراتژی بیت‌به‌بیت مثل نسخهٔ ۳.۹.۲۹ می‌ماند.
 */
object NeoWaveEngine {

    /** حداقل کندل لازم برای یک شمارش معتبر. */
    const val MIN_BARS: Int = 45

    /** حداکثر کندلی که برای شمارش خوانده می‌شود (کنترل بودجهٔ پردازش). */
    const val MAX_BARS: Int = 240

    /** دامنهٔ پیش‌فرض زیگزاگ بر حسب ATR. */
    const val DEFAULT_AMPLITUDE_ATR: Double = 0.55

    /** حداقل قواعد پاس‌شده برای «شمارش معتبر» (آستانهٔ پیش‌فرض گیت). */
    const val MIN_RULE_PASS: Int = 6

    /** حداقل اطمینان برای اینکه نئوویو اجازهٔ وتو داشته باشد. */
    const val MIN_CONFIDENCE_PCT: Double = 55.0

    /** حداقل نسبت اعتبار برای پذیرش یک الگو به‌عنوان نامزد. */
    private const val CANDIDATE_MIN_RATIO: Double = 0.60

    /** ضریب گسترش نیلی (امواج جهت‌دار). */
    private const val EXTENSION_RATIO: Double = 1.618

    // ────────────────────────────────────────────────────────────────────────
    // مدل‌های داده
    // ────────────────────────────────────────────────────────────────────────

    /** الگوهای نئوویو (زیرمجموعهٔ مکانیکی و قابل‌سنجش). */
    enum class NeoWavePattern(
        val titleFa: String,
        val canonicalWaves: Int,
        val isCorrective: Boolean,
        val badgeFa: String,
    ) {
        IMPULSE("موجانگیزهٔ ۵ موجی (Impulse)", 5, false, "۵موجی"),
        IMPULSE_IN_PROGRESS("موجانگیزهٔ در حال گسترش (موج ۳/۵)", 3, false, "در گسترش"),
        ZIGZAG("زیگزاگ اصلاحی ۵-۳-۵", 3, true, "زیگزاگ"),
        FLAT("فلت اصلاحی ۳-۳-۵", 3, true, "فلت"),
        TRIANGLE("مثلث همگرا ۵ موجی", 5, true, "مثلث همگرا"),
        EXPANDING_TRIANGLE("مثلث واگرا ۵ موجی", 5, true, "مثلث واگرا"),
        NEUTRAL_TRIANGLE("مثلث خنثی ۵ موجی", 5, true, "مثلث خنثی"),
        EXTRACTING_TRIANGLE("مثلث استخراجی ۵ موجی", 5, true, "استخراجی"),
        DIAMETRIC("دیامتریک ۷ موجی (Neely)", 7, true, "دیامتریک"),
        SYMMETRICAL("سیمتریکال ۹ موجی (Neely)", 9, true, "سیمتریکال"),
        UNKNOWN("ساختار بدون الگوی معتبر", 0, true, "نامشخص"),
    }

    /** یک پیوت تأییدشده (سقف یا کف سوئینگ). */
    data class NeoWavePivot(
        val index: Int,
        val time: Long,
        val price: Double,
        val isHigh: Boolean,
    )

    /**
     * یک قاعدهٔ نیلی با نتیجهٔ حسابرسی.
     * @property weight وزن قاعده در امتیاز اطمینان (قواعد سخت وزن بالاتر دارند).
     * @property isHard قاعدهٔ سخت؛ شکستن آن کل الگو را باطل می‌کند.
     */
    data class NeoWaveRule(
        val index: Int,
        val titleFa: String,
        val passed: Boolean,
        val detailFa: String,
        val weight: Double = 1.0,
        val isHard: Boolean = false,
    )

    /** قواعد نسبت طول موج‌ها (Retracement Rules 1..7) بر پایهٔ m2/m1. */
    enum class RetracementRule(val number: Int, val titleFa: String, val expectationFa: String) {
        R1(1, "m2 کمتر از ۳۸.۲٪ m1", "موج بعدی باید هم در قیمت و هم در زمان از m1 بزرگ‌تر باشد (روند قوی)"),
        R2(2, "m2 بین ۳۸.۲ تا ۶۱.۸٪ m1", "موج بعدی باید از نظر قیمت و زمان مشابه m1 باشد (اصلاح سالم)"),
        R3(3, "m2 نزدیک ۶۱.۸٪ m1", "مرز اصلاح سالم؛ موج بعدی باید دست‌کم ۶۱.۸٪ m1 باشد"),
        R4(4, "m2 بین ۶۱.۸ تا ۱۰۰٪ m1", "اصلاح عمیق؛ انتظار ساختار پیچیده‌تر و تناوب با موج قبلی"),
        R5(5, "m2 بین ۱۰۰ تا ۱۶۱.۸٪ m1", "m1 احتمالاً اصلاحی بوده؛ موج بعدی می‌تواند تا ۶۱.۸٪ m2 باشد"),
        R6(6, "m2 بین ۱۶۱.۸ تا ۲۶۱.۸٪ m1", "ساختار اصلاحی مرکب؛ ادامهٔ موج نیازمند تأیید پس‌ساختاری است"),
        R7(7, "m2 بیش از ۲۶۱.۸٪ m1", "m1 تقریباً بی‌اعتبار است؛ شمارش باید از نو انجام شود"),
    }

    /** خروجی کامل شمارش نئوویو برای یک نماد/تایم‌فریم. */
    data class NeoWaveVerdict(
        val pattern: NeoWavePattern,
        /** جهت مورد انتظار موج بعدی. */
        val expectedDirection: SignalAction,
        /** آیا ساختار مورد انتظار کامل شده است (موج آخر بسته شده). */
        val isComplete: Boolean,
        val rules: List<NeoWaveRule>,
        val rulesPassed: Int,
        val confidencePct: Double,
        val invalidationPrice: Double?,
        val nextTarget: Double?,
        val monowaveCount: Int,
        val structureHeight: Double,
        val badgeFa: String,
        val summaryFa: String,
        /** برچسب ساختاری نیلی برای موج جاری. */
        val structureLabelFa: String = "",
        /** شمارهٔ قاعدهٔ نسبت طول موج‌ها (۱ تا ۷). */
        val retracementRule: Int = 0,
        /** انتظار رفتاری موج بعدی طبق جدول نیلی. */
        val retracementExpectationFa: String = "",
        /** تأیید پس‌ساختاری: آیا حرکت بعد از الگو، شمارش را تأیید کرده است. */
        val isConfirmed: Boolean = false,
        /** توضیح فارسی وضعیت تأیید پس‌ساختاری. */
        val postConstructiveFa: String = "",
        /** آیا یکی از قواعد سخت نقض شده است (شمارش باطل). */
        val hardBreach: Boolean = false,
    ) {
        val rulesTotal: Int get() = rules.size
        val isUsable: Boolean
            get() = pattern != NeoWavePattern.UNKNOWN && rulesTotal > 0 && !hardBreach

        fun isAlignedWith(isLong: Boolean): Boolean = when (expectedDirection) {
            SignalAction.BUY -> isLong
            SignalAction.SELL -> !isLong
            SignalAction.NO_TRADE -> false
        }
    }

    /**
     * نتیجهٔ داوری نئوویو روی یک ورود.
     *
     * @property veto اگر true باشد، ورود باید مسدود شود.
     * @property confidenceDelta تغییر امتیاز اطمینان (مثبت تقویت، منفی تضعیف).
     */
    data class NeoWaveGate(
        val enabled: Boolean,
        val veto: Boolean,
        val confidenceDelta: Double,
        val noteFa: String,
        val verdict: NeoWaveVerdict?,
    ) {
        val hasEffect: Boolean get() = enabled && (veto || confidenceDelta != 0.0)

        companion object {
            /** حالت خاموش: هیچ اثری روی موتور استراتژی ندارد. */
            val OFF: NeoWaveGate = NeoWaveGate(
                enabled = false,
                veto = false,
                confidenceDelta = 0.0,
                noteFa = "",
                verdict = null,
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ابزارهای پایه
    // ────────────────────────────────────────────────────────────────────────

    /** ATR وایلدر روی آخرین `period` کندل. */
    fun atr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.isEmpty()) return 0.0
        val p = period.coerceIn(2, 50)
        if (candles.size == 1) return (candles[0].high - candles[0].low).coerceAtLeast(0.0)
        val start = (candles.size - p).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        for (i in start until candles.size) {
            val prevClose = candles[i - 1].close
            val tr = max(
                candles[i].high - candles[i].low,
                max(abs(candles[i].high - prevClose), abs(candles[i].low - prevClose)),
            )
            if (tr.isFinite() && tr >= 0.0) {
                sum += tr
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * استخراج پیوت‌های سوئینگ با پنجرهٔ فراکتالی و فیلتر دامنهٔ ATR.
     * مراحل: اکسترمم محلی → ادغام هم‌نوع‌ها → حذف حرکات کوچک → تکمیل ساختار از نقطهٔ صفر.
     */
    fun pivots(
        candles: List<Candle>,
        amplitudeAtr: Double = DEFAULT_AMPLITUDE_ATR,
        window: Int = 3,
    ): List<NeoWavePivot> {
        val bars = candles.filter { it.closed }.ifEmpty { candles }.takeLast(MAX_BARS)
        if (bars.size < MIN_BARS) return emptyList()

        val atrValue = atr(bars)
        val lastClose = bars.last().close
        val amplitude = amplitudeAtr.coerceIn(0.20, 2.00)
        val threshold = max(atrValue * amplitude, lastClose * 0.0005)
        if (!threshold.isFinite() || threshold <= 0.0) return emptyList()

        val w = window.coerceIn(1, 5)
        val raw = ArrayList<NeoWavePivot>()
        for (i in w until bars.size - w) {
            val bar = bars[i]
            var isHigh = true
            var isLow = true
            for (j in (i - w)..(i + w)) {
                if (j == i) continue
                // تساوی مجاز است: سقف‌های هم‌ارتفاع (پلاتو) نباید پیوت را حذف کنند.
                if (bars[j].high > bar.high) isHigh = false
                if (bars[j].low < bar.low) isLow = false
                if (!isHigh && !isLow) break
            }
            if (isHigh) raw.add(NeoWavePivot(i, bar.time, bar.high, true))
            if (isLow) raw.add(NeoWavePivot(i, bar.time, bar.low, false))
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortBy { it.index }

        // ۱) ادغام اکسترمم‌های هم‌نوع متوالی.
        val merged = ArrayList<NeoWavePivot>()
        for (p in raw) {
            val last = merged.lastOrNull()
            if (last == null || last.isHigh != p.isHigh) {
                merged.add(p)
            } else {
                val better = if (p.isHigh) p.price >= last.price else p.price <= last.price
                if (better) merged[merged.lastIndex] = p
            }
        }

        // ۲) حذف حرکات کوچک‌تر از آستانه.
        val cleaned = ArrayList<NeoWavePivot>()
        for (p in merged) {
            val last = cleaned.lastOrNull()
            if (last == null) {
                cleaned.add(p)
                continue
            }
            if (abs(p.price - last.price) < threshold) {
                when {
                    p.isHigh && last.isHigh -> if (p.price > last.price) cleaned[cleaned.lastIndex] = p
                    !p.isHigh && !last.isHigh -> if (p.price < last.price) cleaned[cleaned.lastIndex] = p
                    else -> Unit
                }
            } else {
                cleaned.add(p)
            }
        }

        // ۳) ادغام دوبارهٔ هم‌نوع‌های پشت‌سرهم.
        val finalPivots = ArrayList<NeoWavePivot>()
        for (p in cleaned) {
            val last = finalPivots.lastOrNull()
            if (last != null && last.isHigh == p.isHigh) {
                val better = if (p.isHigh) p.price >= last.price else p.price <= last.price
                if (better) finalPivots[finalPivots.lastIndex] = p
            } else {
                finalPivots.add(p)
            }
        }

        // ۴) تکمیل ساختار در ابتدا: اکسترمم مخالفِ پیش از اولین پیوت.
        if (finalPivots.isNotEmpty()) {
            val first = finalPivots.first()
            val prefixEnd = first.index - 1
            if (prefixEnd >= 1) {
                var idx = 0
                var price = if (first.isHigh) bars[0].low else bars[0].high
                for (i in 0..prefixEnd) {
                    if (first.isHigh) {
                        if (bars[i].low <= price) { price = bars[i].low; idx = i }
                    } else {
                        if (bars[i].high >= price) { price = bars[i].high; idx = i }
                    }
                }
                if (abs(price - first.price) >= threshold) {
                    finalPivots.add(0, NeoWavePivot(idx, bars[idx].time, price, !first.isHigh))
                }
            }
        }
        return finalPivots
    }

    // ────────────────────────────────────────────────────────────────────────
    // مونوویو و قواعد نسبت طول
    // ────────────────────────────────────────────────────────────────────────

    /** یک مونوویو: حرکت بین دو پیوت متوالی. */
    private data class Mono(
        val from: NeoWavePivot,
        val to: NeoWavePivot,
        val lens: Double,
        val bars: Int,
        val dir: Int,
    )

    private fun monowaves(pivots: List<NeoWavePivot>): List<Mono> {
        if (pivots.size < 2) return emptyList()
        val out = ArrayList<Mono>(pivots.size - 1)
        for (i in 1 until pivots.size) {
            val a = pivots[i - 1]
            val b = pivots[i]
            val len = abs(b.price - a.price)
            if (len <= 0.0) continue
            val bars = (b.index - a.index).coerceAtLeast(1)
            val dir = if (b.price > a.price) 1 else -1
            out.add(Mono(a, b, len, bars, dir))
        }
        return out
    }

    private fun retraceRatio(child: Mono, parent: Mono): Double =
        if (parent.lens <= 0.0) 0.0 else child.lens / parent.lens

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    /** تعیین قاعدهٔ نسبت طول موج‌ها (۱ تا ۷) از نسبت m2/m1 نیلی. */
    fun retracementRuleOf(ratio: Double): RetracementRule = when {
        ratio < 0.382 -> RetracementRule.R1
        ratio < 0.618 -> RetracementRule.R2
        ratio < 0.66 -> RetracementRule.R3
        ratio < 1.0 -> RetracementRule.R4
        ratio < 1.618 -> RetracementRule.R5
        ratio <= 2.618 -> RetracementRule.R6
        else -> RetracementRule.R7
    }

    /**
     * برچسب ساختاری نیلی برای موج جاری.
     *
     * `:5` موج انگیزهٔ ادامه‌دار · `:L5` موج انگیزهٔ پایانی · `:F3` سه‌موجی نخست ·
     * `:c3` سه‌موجی میانی · `:L3` سه‌موجی پایانی · `:s5` پنج‌موجی کوتاه/ضعیف.
     */
    private fun structureLabel(
        pattern: NeoWavePattern,
        rule: RetracementRule,
        isComplete: Boolean,
    ): String = when (pattern) {
        NeoWavePattern.IMPULSE -> if (isComplete) ":L5" else ":5"
        NeoWavePattern.IMPULSE_IN_PROGRESS -> ":5"
        NeoWavePattern.ZIGZAG, NeoWavePattern.FLAT ->
            if (isComplete) ":L3" else if (rule.number >= 4) ":F3" else ":c3"
        NeoWavePattern.TRIANGLE, NeoWavePattern.EXPANDING_TRIANGLE,
        NeoWavePattern.NEUTRAL_TRIANGLE, NeoWavePattern.EXTRACTING_TRIANGLE,
        NeoWavePattern.DIAMETRIC, NeoWavePattern.SYMMETRICAL -> if (isComplete) ":L3" else ":c3"
        NeoWavePattern.UNKNOWN -> "—"
    }

    // ────────────────────────────────────────────────────────────────────────
    // نامزدهای الگو
    // ────────────────────────────────────────────────────────────────────────

    private data class Candidate(
        val pattern: NeoWavePattern,
        val rules: List<NeoWaveRule>,
        val expectedDirection: SignalAction,
        val isComplete: Boolean,
        val invalidation: Double?,
        val target: Double?,
        val height: Double,
        val priority: Int,
        val recency: Int,
        val rule: RetracementRule,
    ) {
        val passed: Int get() = rules.count { it.passed }
        val hardBreach: Boolean get() = rules.any { it.isHard && !it.passed }
        val ratio: Double
            get() {
                if (rules.isEmpty()) return 0.0
                val total = rules.sumOf { it.weight }
                val got = rules.filter { it.passed }.sumOf { it.weight }
                return if (total <= 0.0) 0.0 else got / total
            }
    }

    private fun window(monos: List<Mono>, end: Int, count: Int): List<Mono>? =
        if (end - count < 0 || end > monos.size) null else monos.subList(end - count, end)

    private fun priorDirection(monos: List<Mono>, end: Int, count: Int): Int {
        val start = end - count
        return if (start - 1 >= 0) monos[start - 1].dir else 0
    }

    private fun expectedAfterCorrection(monos: List<Mono>, end: Int, count: Int, fallback: Int): SignalAction {
        val prior = priorDirection(monos, end, count)
        val dir = if (prior != 0) prior else -fallback
        return if (dir > 0) SignalAction.BUY else SignalAction.SELL
    }

    private fun rule(
        index: Int,
        title: String,
        passed: Boolean,
        detail: String,
        weight: Double = 1.0,
        isHard: Boolean = false,
    ) = NeoWaveRule(index, title, passed, detail, weight, isHard)

    private fun pct(v: Double): String = "${(v * 100.0).toInt()}٪"

    private fun num(v: Double): String = ((round(v * 100.0) / 100.0)).toString()

    private fun symmetryRatio(a: Int, b: Int): Double = symmetryRatio(a.toDouble(), b.toDouble())

    private fun symmetryRatio(a: Double, b: Double): Double {
        val hi = max(a, b)
        val lo = min(a, b)
        return if (lo <= 0.0) 99.0 else hi / lo
    }

    // ────────────────────────────────────────────────────────────────────────
    // تحلیل کامل
    // ────────────────────────────────────────────────────────────────────────

    /**
     * تحلیل کامل نئوویو روی یک سری کندل.
     * @return اگر کندل کافی یا ساختار معتبری نباشد `null` برمی‌گردد (داور خنثی می‌ماند).
     */
    fun analyze(
        candles: List<Candle>,
        amplitudeAtr: Double = DEFAULT_AMPLITUDE_ATR,
    ): NeoWaveVerdict? {
        val bars = candles.filter { it.closed }.ifEmpty { candles }.takeLast(MAX_BARS)
        if (bars.size < MIN_BARS) return null
        val pivots = pivots(bars, amplitudeAtr)
        if (pivots.size < 4) return null
        val monos = monowaves(pivots)
        if (monos.size < 3) return null

        val candidates = ArrayList<Candidate>()
        for (recency in 0..2) {
            val end = monos.size - recency
            if (end < 3) break
            classifyImpulseComplete(monos, end, recency)?.let { candidates.add(it) }
            classifyImpulseInProgress(monos, end, recency)?.let { candidates.add(it) }
            classifyZigZag(monos, end, recency)?.let { candidates.add(it) }
            classifyFlat(monos, end, recency)?.let { candidates.add(it) }
            classifyTriangle(monos, end, recency)?.let { candidates.add(it) }
            classifyDiametric(monos, end, recency)?.let { candidates.add(it) }
            classifySymmetrical(monos, end, recency)?.let { candidates.add(it) }
        }

        val best = candidates
            .filter { !it.hardBreach && it.passed >= 3 && it.ratio >= CANDIDATE_MIN_RATIO }
            .maxWithOrNull(
                compareBy<Candidate> { -it.recency }
                    .thenBy { it.priority }
                    .thenBy { it.ratio },
            ) ?: return null

        // لگ زنده: از آخرین پیوت تا قیمت فعلی (برای تأیید پس‌ساختاری).
        val lastPivot = pivots.last()
        val livePrice = bars.last().close
        val liveDir = sign(livePrice - lastPivot.price).toInt()
        val liveLength = abs(livePrice - lastPivot.price)

        val confirmation = postConstructiveCheck(best, monos, liveDir, liveLength, bars.size - lastPivot.index)
        var confidence = best.ratio * 100.0
        if (!best.isComplete) confidence -= 8.0
        if (!confirmation.confirmed) confidence -= 10.0
        confidence = round2(confidence.coerceIn(0.0, 100.0))

        val label = structureLabel(best.pattern, best.rule, best.isComplete)
        val dirFa = when (best.expectedDirection) {
            SignalAction.BUY -> "صعودی"
            SignalAction.SELL -> "نزولی"
            SignalAction.NO_TRADE -> "بی‌جهت"
        }
        val badge = "🌊 نئوویو: ${best.pattern.badgeFa} $label · ${best.passed}/${best.rules.size} قاعده" +
            (if (confirmation.confirmed) " · تأییدشده" else " · بدون تأیید") + " · انتظار $dirFa"
        val summary = buildString {
            append("${best.pattern.titleFa} با برچسب $label و ${monos.size} مونوویو؛ ")
            append("${best.passed} از ${best.rules.size} قاعدهٔ نیلی (اطمینان ${confidence.toInt()}%)؛ ")
            append("قاعدهٔ نسبت ${best.rule.number}: ${best.rule.titleFa} ⇒ ${best.rule.expectationFa}؛ ")
            append("جهت مورد انتظار موج بعدی: $dirFa")
            best.target?.let { append(" · هدف پروجکشن ${round2(it)}") }
            best.invalidation?.let { append(" · ابطال ساختار ${round2(it)}") }
            if (!confirmation.confirmed) append(" · ${confirmation.noteFa}")
        }

        return NeoWaveVerdict(
            pattern = best.pattern,
            expectedDirection = best.expectedDirection,
            isComplete = best.isComplete,
            rules = best.rules,
            rulesPassed = best.passed,
            confidencePct = confidence,
            invalidationPrice = best.invalidation?.let { round2(it) },
            nextTarget = best.target?.let { round2(it) },
            monowaveCount = monos.size,
            structureHeight = round2(best.height),
            badgeFa = badge,
            summaryFa = summary,
            structureLabelFa = label,
            retracementRule = best.rule.number,
            retracementExpectationFa = best.rule.expectationFa,
            isConfirmed = confirmation.confirmed,
            postConstructiveFa = confirmation.noteFa,
            hardBreach = best.hardBreach,
        )
    }

    /** نتیجهٔ بررسی پس‌ساختاری. */
    private data class Confirmation(val confirmed: Boolean, val noteFa: String)

    /**
     * **تأیید پس‌ساختاری (Post-Constructive Rule)** — همان بخشی که متد نیلی اعتبار شمارش را
     * مشروط به حرکت بعدی می‌کند:
     * - الگوی کامل‌شده باید با یک لگ زنده در جهت انتظار، حداقل ۲۳.۶٪ ارتفاع الگو را طی کند.
     * - اگر قیمت هنوز در جهت خود الگو (فراتر از نقطهٔ پایانی) پیش رفته باشد، الگو هنوز کامل نیست.
     */
    private fun postConstructiveCheck(
        best: Candidate,
        monos: List<Mono>,
        liveDir: Int,
        liveLength: Double,
        liveBars: Int,
    ): Confirmation {
        if (!best.isComplete || best.height <= 0.0) {
            return Confirmation(false, "ساختار هنوز کامل نشده است.")
        }
        val expectDir = when (best.expectedDirection) {
            SignalAction.BUY -> 1
            SignalAction.SELL -> -1
            SignalAction.NO_TRADE -> 0
        }
        if (expectDir == 0) return Confirmation(false, "جهت مورد انتظار مشخص نیست.")
        val minMove = best.height * 0.236
        if (liveDir == expectDir && liveLength >= minMove) {
            return Confirmation(true, "حرکت پس از الگو (${num(liveLength)}) جهت مورد انتظار را تأیید کرد.")
        }
        if (liveDir != expectDir && liveDir != 0 && liveBars <= 3) {
            return Confirmation(false, "حرکت پس از الگو هنوز شروع نشده؛ تأیید ناقص است.")
        }
        if (liveDir == -expectDir) {
            return Confirmation(
                false,
                "حرکت پس از الگو خلاف جهت مورد انتظار است؛ شمارش در خطر ابطال قرار دارد.",
            )
        }
        return Confirmation(false, "جهش تأییدی به اندازهٔ ۲۳.۶٪ ارتفاع الگو رخ نداده است.")
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۱: موج‌انگیزهٔ ۵ موجی — ۱۲ قاعدهٔ نیلی
    // ────────────────────────────────────────────────────────────────────────

    private fun classifyImpulseComplete(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 5) ?: return null
        val d = m[0].dir
        if (d == 0) return null
        if (m[1].dir != -d || m[2].dir != d || m[3].dir != -d || m[4].dir != d) return null

        val r2 = retraceRatio(m[1], m[0])
        val r4 = retraceRatio(m[3], m[2])
        val drivers = listOf(m[0].lens, m[2].lens, m[4].lens)
        val maxDriver = drivers.max()
        val extended = drivers.count { it >= maxDriver * EXTENSION_RATIO }
        val shortestIdx = drivers.indexOf(drivers.min())
        val rules = ArrayList<NeoWaveRule>()

        rules.add(
            rule(1, "بازگشت موج ۲ حداکثر ۶۱.۸٪ موج ۱ (قاعدهٔ سخت)", r2 <= 0.618,
                "بازگشت موج ۲ = ${pct(r2)}", weight = 2.0, isHard = true),
        )
        rules.add(
            rule(2, "موج ۴ وارد قلمرو قیمتی موج ۱ نمی‌شود", if (d > 0) m[3].to.price > m[0].to.price else m[3].to.price < m[0].to.price,
                "پایان موج۴=${num(m[3].to.price)} · پایان موج۱=${num(m[0].to.price)}", weight = 2.0, isHard = true),
        )
        rules.add(
            rule(3, "موج ۴ وارد قلمرو موج ۲ نمی‌شود",
                if (d > 0) m[3].to.price > m[1].to.price else m[3].to.price < m[1].to.price,
                "پایان موج۴=${num(m[3].to.price)} · پایان موج۲=${num(m[1].to.price)}", weight = 1.5),
        )
        rules.add(
            rule(4, "موج ۳ کوتاه‌ترین موج جهت‌دار نیست (قاعدهٔ سخت)", shortestIdx != 1,
                "موج۱=${num(m[0].lens)} · موج۳=${num(m[2].lens)} · موج۵=${num(m[4].lens)}",
                weight = 2.0, isHard = true),
        )
        rules.add(
            rule(5, "دست‌کم یک موج جهت‌دار ۱.۶۱۸ برابر بزرگ‌ترین موج دیگر است", extended >= 1,
                "بزرگ‌ترین=${num(maxDriver)} · تعداد موج گسترش‌یافته=$extended", weight = 1.5),
        )
        // تابع‌بودن (subdivision): موج گسترش‌یافته باید بلندترین و طولانی‌ترین زمان را هم داشته باشد.
        val extIdx = drivers.indexOf(maxDriver)
        val extMono = listOf(m[0], m[2], m[4])[extIdx]
        val extTimes = listOf(m[0].bars, m[2].bars, m[4].bars)
        rules.add(
            rule(6, "موج گسترش‌یافته تابع (subdivided) است: هم بلندترین و هم زمان‌برترین",
                extTimes[extIdx] >= extTimes.max() * 0.9,
                "زمان موج گسترش‌یافته=${extMono.bars} · بیشترین زمان=${extTimes.max()}", weight = 1.2),
        )
        rules.add(
            rule(7, "موج اصلاحی ۲ زمان بیشتری از موج ۱ گرفته است",
                m[1].bars >= m[0].bars || r2 <= 0.618,
                "زمان موج۲=${m[1].bars} · موج۱=${m[0].bars} · عمق=${pct(r2)}"),
        )
        val timeRatio = if (min(m[1].bars, m[3].bars) == 0) 99.0
        else max(m[1].bars, m[3].bars).toDouble() / min(m[1].bars, m[3].bars).toDouble()
        rules.add(
            rule(8, "تناوب موج ۲ و ۴ (عمق یا زمان)",
                abs(r2 - r4) >= 0.15 || timeRatio >= 1.6,
                "عمق۲=${pct(r2)} · عمق۴=${pct(r4)} · نسبت زمان=${num(timeRatio)}"),
        )
        val projRatio = m[4].lens / m[0].lens
        rules.add(
            rule(9, "پروجکشن موج ۵ در دامنهٔ ۳۸.۲ تا ۱۶۱.۸٪ موج ۱",
                projRatio in 0.382..1.618, "موج۵/موج۱ = ${num(projRatio)}"),
        )
        rules.add(
            rule(10, "شکست کانال ۰-۲ توسط موج ۳",
                if (d > 0) m[2].to.price > m[0].to.price else m[2].to.price < m[0].to.price,
                "پایان موج۳=${num(m[2].to.price)} · پایان موج۱=${num(m[0].to.price)}"),
        )
        rules.add(
            rule(11, "قاعدهٔ نقاط تماس: حداکثر ۴ نقطه از ۶ نقطه روی کانال",
                channelTouchPoints(m, d) <= 4, "نقاط روی کانال = ${channelTouchPoints(m, d)}"),
        )
        rules.add(
            rule(12, "موج ۳ زمان بیشتری از موج ۱ گرفته است", m[2].bars >= m[0].bars * 0.9,
                "زمان موج۳=${m[2].bars} · موج۱=${m[0].bars}"),
        )

        val rule = retracementRuleOf(r2)
        val height = abs(m[2].to.price - m[0].from.price)
        val target = m[4].to.price - (if (d > 0) 1.0 else -1.0) * height * 0.382
        return Candidate(
            pattern = NeoWavePattern.IMPULSE,
            rules = rules,
            expectedDirection = if (d > 0) SignalAction.SELL else SignalAction.BUY,
            isComplete = true,
            invalidation = m[4].to.price,
            target = target,
            height = height,
            priority = 50,
            recency = recency,
            rule = rule,
        )
    }

    /** شمارش نقاط تماس با کانال ۱-۳ (قاعدهٔ نقاط تماس نیلی). */
    private fun channelTouchPoints(m: List<Mono>, d: Int): Int {
        val p1 = m[0].from.price
        val p2 = m[0].to.price
        val p3 = m[2].to.price
        if (p2 == p1) return 6
        val slope = (p3 - p1) / 4.0 // کانال ۰-۲ در مختصات پیوت
        var touches = 0
        val points = listOf(m[0].from.price to 0, m[0].to.price to 1, m[1].to.price to 2, m[2].to.price to 3, m[3].to.price to 4, m[4].to.price to 5)
        points.forEach { (price, idx) ->
            val channel = p1 + slope * idx
            val tolerance = abs(p3 - p1) * 0.05
            if (abs(price - channel) <= tolerance) touches++
        }
        return touches
    }

    /** موج‌انگیزهٔ در حال گسترش: سه مونوویو با موج ۳ کشیده. */
    private fun classifyImpulseInProgress(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 3) ?: return null
        val d = m[0].dir
        if (d == 0 || m[1].dir != -d || m[2].dir != d) return null
        if (m[0].lens <= 0.0) return null

        val r2 = retraceRatio(m[1], m[0])
        val extension = m[2].lens / m[0].lens
        if (extension < 0.95) return null
        if (r2 > 0.786) return null

        val rules = listOf(
            rule(1, "بازگشت موج ۲ کمتر از ۶۱.۸٪ موج ۱", r2 <= 0.618, "بازگشت = ${pct(r2)}",
                weight = 2.0, isHard = r2 > 0.786),
            rule(2, "موج ۳ از موج ۱ کشیده‌تر است", extension >= 1.0, "موج۳/موج۱ = ${num(extension)}",
                weight = 1.5),
            rule(3, "موج ۳ زمان بیشتری از موج ۲ گرفته است", m[2].bars >= m[1].bars,
                "زمان موج۳=${m[2].bars} · موج۲=${m[1].bars}"),
            rule(4, "شکست سقف/کف موج ۱ توسط موج ۳",
                if (d > 0) m[2].to.price > m[0].to.price else m[2].to.price < m[0].to.price,
                "پایان موج۳=${num(m[2].to.price)}"),
            rule(5, "پروجکشن موج ۵ در دسترس است", true, "هدف = انتهای موج ۳ + ۰.۶۱۸ × موج ۱"),
        )
        val height = abs(m[2].to.price - m[0].from.price)
        val target = m[2].to.price + (if (d > 0) 1.0 else -1.0) * m[0].lens * 0.618
        return Candidate(
            pattern = NeoWavePattern.IMPULSE_IN_PROGRESS,
            rules = rules,
            expectedDirection = if (d > 0) SignalAction.BUY else SignalAction.SELL,
            isComplete = false,
            invalidation = m[0].from.price,
            target = target,
            height = height,
            priority = 40,
            recency = recency,
            rule = retracementRuleOf(r2),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۲: زیگزاگ ۵-۳-۵
    // ────────────────────────────────────────────────────────────────────────

    private fun classifyZigZag(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 3) ?: return null
        val d = m[0].dir
        if (d == 0 || m[1].dir != -d || m[2].dir != d) return null
        if (m[0].lens <= 0.0) return null
        val r2 = retraceRatio(m[1], m[0])
        if (r2 < 0.236 || r2 > 0.886) return null

        val beyond = if (d > 0) m[2].to.price > m[0].to.price else m[2].to.price < m[0].to.price
        val cRatio = m[2].lens / m[0].lens
        val rules = listOf(
            rule(1, "بازگشت موج b در دامنهٔ ۳۳ تا ۶۱.۸٪ موج a (قاعدهٔ سخت)",
                r2 in 0.33..0.618, "بازگشت = ${pct(r2)}", weight = 2.0, isHard = r2 > 0.886),
            rule(2, "موج c فراتر از انتهای موج a می‌رود (قاعدهٔ سخت)", beyond,
                "پایان c=${num(m[2].to.price)} · پایان a=${num(m[0].to.price)}", weight = 2.0, isHard = true),
            rule(3, "موج c دست‌کم ۶۱.۸٪ موج a است", cRatio >= 0.618, "c/a = ${num(cRatio)}", weight = 1.5),
            rule(4, "موج b زمان مساوی یا بیشتری از موج a گرفته است", m[1].bars >= m[0].bars,
                "زمان b=${m[1].bars} · a=${m[0].bars}", weight = 1.5),
            rule(5, "تناوب a و b (قیمت یا زمان)",
                abs(m[1].lens - m[0].lens) / m[0].lens >= 0.2 || symmetryRatio(m[0].bars, m[1].bars) >= 1.6,
                "قیمت a=${num(m[0].lens)} · b=${num(m[1].lens)} · زمان a=${m[0].bars} · b=${m[1].bars}"),
            rule(6, "موج c دامنهٔ متفاوتی از موج a دارد",
                symmetryRatio(m[0].lens, m[2].lens) >= 1.05 || symmetryRatio(m[0].bars, m[2].bars) >= 1.2,
                "c/a قیمتی = ${num(cRatio)} · زمان a=${m[0].bars} · c=${m[2].bars}"),
        )
        val height = abs(m[2].to.price - m[1].from.price)
        val expected = expectedAfterCorrection(monos, end, 3, fallback = d)
        val resumeSign = if (expected == SignalAction.BUY) 1.0 else -1.0
        val target = m[2].to.price + resumeSign * m[0].lens * 0.618
        return Candidate(
            pattern = NeoWavePattern.ZIGZAG,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[2].to.price,
            target = target,
            height = height,
            priority = 30,
            recency = recency,
            rule = retracementRuleOf(r2),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۳: فلت ۳-۳-۵
    // ────────────────────────────────────────────────────────────────────────

    private fun classifyFlat(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 3) ?: return null
        val d = m[0].dir
        if (d == 0 || m[1].dir != -d || m[2].dir != d) return null
        if (m[0].lens <= 0.0) return null
        val r2 = retraceRatio(m[1], m[0])
        if (r2 < 0.618 || r2 > 1.382) return null
        val cRatio = m[2].lens / m[0].lens
        val closeToEndOfA = abs(m[2].to.price - m[0].to.price) <= m[0].lens * 0.382

        val rules = listOf(
            rule(1, "بازگشت موج b دست‌کم ۶۱.۸٪ موج a (قاعدهٔ سخت)", r2 >= 0.618,
                "بازگشت = ${pct(r2)}", weight = 2.0, isHard = true),
            rule(2, "موج b از ۱۳۸.۲٪ موج a فراتر نمی‌رود", r2 <= 1.382,
                "نسبت = ${num(r2)}", weight = 1.5),
            rule(3, "موج c دست‌کم ۳۸.۲٪ موج a است", cRatio >= 0.382,
                "c/a = ${num(cRatio)}", weight = 1.5),
            rule(4, "موج b زمان مساوی یا بیشتری از موج a گرفته است", m[1].bars >= m[0].bars,
                "زمان b=${m[1].bars} · a=${m[0].bars}", weight = 1.5),
            rule(5, "پایان موج c نزدیک پایان موج a (فلت منظم)", closeToEndOfA,
                "فاصله = ${num(abs(m[2].to.price - m[0].to.price))}"),
            rule(6, "تناوب b و c (قیمت یا زمان)",
                symmetryRatio(m[1].lens, m[2].lens) >= 1.1 || symmetryRatio(m[1].bars, m[2].bars) >= 1.3,
                "قیمت b=${num(m[1].lens)} · c=${num(m[2].lens)}"),
        )
        val height = abs(m[1].to.price - m[0].from.price)
        val expected = expectedAfterCorrection(monos, end, 3, fallback = d)
        val resumeSign = if (expected == SignalAction.BUY) 1.0 else -1.0
        val target = m[2].to.price + resumeSign * m[0].lens * 1.0
        return Candidate(
            pattern = NeoWavePattern.FLAT,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[2].to.price,
            target = target,
            height = height,
            priority = 28,
            recency = recency,
            rule = retracementRuleOf(r2),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۴: مثلث‌ها (همگرا / واگرا / خنثی / استخراجی)
    // ────────────────────────────────────────────────────────────────────────

    private fun classifyTriangle(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 5) ?: return null
        val a = m[0].lens
        val b = m[1].lens
        val c = m[2].lens
        val dLeg = m[3].lens
        val e = m[4].lens
        if (a <= 0.0) return null

        val contracting = c < a * 0.95 && dLeg < b * 0.95 && e < c
        val expanding = c > a * 1.05 && dLeg > b * 1.05 && e > c
        val neutral = !contracting && !expanding && abs(c - a) / a <= 0.15
        val extracting = c > a && e < c && dLeg < b
        if (!contracting && !expanding && !neutral && !extracting) return null

        val pattern = when {
            contracting -> NeoWavePattern.TRIANGLE
            expanding -> NeoWavePattern.EXPANDING_TRIANGLE
            neutral -> NeoWavePattern.NEUTRAL_TRIANGLE
            else -> NeoWavePattern.EXTRACTING_TRIANGLE
        }
        val ratioCE = if (a <= 0.0) 0.0 else e / a
        // قاعدهٔ ساختاری سخت: مثلث یک ساختار «جانبی» است؛ جابه‌جایی خالص نباید بزرگ باشد.
        val maxLeg = maxOf(a, b, c, dLeg, e)
        val totalPath = a + b + c + dLeg + e
        val netMove = abs(m[4].to.price - m[0].from.price)
        // ملاک ساختاری مثلث: هم‌پوشانی متوالی — پایان هر موج داخل گسترهٔ موج قبلی می‌افتد.
        fun span(i: Int): Pair<Double, Double> {
            val lo = min(m[i].from.price, m[i].to.price)
            val hi = max(m[i].from.price, m[i].to.price)
            return lo to hi
        }
        fun insidePrevious(i: Int): Boolean {
            val (lo, hi) = span(i - 1)
            return m[i].to.price in lo..hi
        }
        val overlaps = (1..4).count { insidePrevious(it) }
        val isLateral = overlaps == 4
        val lowEfficiency = totalPath > 0.0 && netMove <= totalPath * 0.30
        val insideFirstLeg = m[4].to.price >= min(m[0].from.price, m[0].to.price) - a * 0.25 &&
            m[4].to.price <= max(m[0].from.price, m[0].to.price) + a * 0.25
        if (!isLateral || !insideFirstLeg) return null
        val rules = listOf(
            rule(1, "توالی دامنه‌ها با نوع مثلث سازگار است",
                contracting || expanding || neutral || extracting,
                if (contracting) "همگرا" else if (expanding) "واگرا" else if (neutral) "خنثی" else "استخراجی",
                weight = 2.0, isHard = true),
            rule(2, "هم‌پوشانی متوالی کامل: هر موج داخل گسترهٔ موج قبلی تمام می‌شود", isLateral,
                "هم‌پوشانی=$overlaps/4", weight = 2.0, isHard = true),
            rule(3, "بازدهی مسیر کم است (ساختار جانبی)",
                lowEfficiency,
                "جابه‌جایی=${num(netMove)} · کل مسیر=${num(totalPath)} · بلندترین=${num(maxLeg)}",
                weight = 1.2),
            rule(4, "نسبت موج E به موج A در دامنهٔ ۳۸.۲ تا ۱۳۸.۲٪",
                ratioCE in 0.382..1.382, "E/A = ${num(ratioCE)}", weight = 1.5),
            rule(5, "موج C دست‌کم ۶۱.۸٪ موج A است", c / a >= 0.618, "C/A = ${num(c / a)}", weight = 1.5),
            rule(6, "طول موج D با موج B نسبت معنادار دارد",
                symmetryRatio(b, dLeg) <= 2.618, "B/D = ${num(symmetryRatio(b, dLeg))}"),
            rule(7, "قاعدهٔ تقارن: نسبت موج E به D از ۱۶۱.۸٪ فراتر نمی‌رود",
                symmetryRatio(dLeg, e) <= 1.618, "D/E = ${num(symmetryRatio(dLeg, e))}"),
            rule(8, "پایان موج E داخل دامنهٔ موج A باقی مانده است", insideFirstLeg,
                "پایان موج E=${num(m[4].to.price)}", weight = 1.5, isHard = true),
        )
        val height = abs(m[0].to.price - m[0].from.price)
        val expected = expectedAfterCorrection(monos, end, 5, fallback = m[4].dir)
        val target = m[4].to.price + (if (expected == SignalAction.BUY) 1.0 else -1.0) * height * 0.618
        return Candidate(
            pattern = pattern,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[4].to.price,
            target = target,
            height = height,
            priority = if (pattern == NeoWavePattern.TRIANGLE) 35 else 33,
            recency = recency,
            rule = retracementRuleOf(b / a),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۵: دیامتریک ۷ موجی
    // ────────────────────────────────────────────────────────────────────────

    private fun classifyDiametric(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 7) ?: return null
        val lens = m.map { it.lens }
        val center = lens[3]
        if (center <= 0.0) return null
        if (lens.indexOf(lens.max()) != 3) return null

        val timeRatios = m.map { it.bars }.sorted()
        val medianTime = timeRatios[timeRatios.size / 2].toDouble().coerceAtLeast(1.0)
        val similarTime = m.count { it.bars <= medianTime * 1.5 }

        val rules = listOf(
            rule(1, "هفت مونوویو با تناوب کامل", m.size == 7, "${m.size} مونوویو",
                weight = 1.5, isHard = true),
            rule(2, "موج میانی (چهارم) بزرگ‌ترین موج است", lens.indexOf(lens.max()) == 3,
                "بزرگ‌ترین موج = ${lens.indexOf(lens.max()) + 1}", weight = 2.0, isHard = true),
            rule(3, "تقارن زمانی موج ۳ و ۵", symmetryRatio(m[2].bars, m[4].bars) <= 1.75,
                "نسبت = ${num(symmetryRatio(m[2].bars, m[4].bars))}"),
            rule(4, "تقارن زمانی موج ۲ و ۶", symmetryRatio(m[1].bars, m[5].bars) <= 1.75,
                "نسبت = ${num(symmetryRatio(m[1].bars, m[5].bars))}"),
            rule(5, "تقارن زمانی موج ۱ و ۷", symmetryRatio(m[0].bars, m[6].bars) <= 2.00,
                "نسبت = ${num(symmetryRatio(m[0].bars, m[6].bars))}"),
            rule(6, "تقارن قیمتی موج ۲ و ۶", symmetryRatio(m[1].lens, m[5].lens) <= 1.80,
                "نسبت = ${num(symmetryRatio(m[1].lens, m[5].lens))}"),
            rule(7, "پنج موج هم‌زمان و دو موج به‌مراتب بلندتر/کوتاه‌تر", similarTime >= 5,
                "تعداد موج با زمان مشابه = $similarTime", weight = 1.5),
        )
        val height = lens.sum()
        val expected = expectedAfterCorrection(monos, end, 7, fallback = m[6].dir)
        val target = m[6].to.price + (if (expected == SignalAction.BUY) 1.0 else -1.0) * center * 0.618
        return Candidate(
            pattern = NeoWavePattern.DIAMETRIC,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[6].to.price,
            target = target,
            height = height,
            priority = 60,
            recency = recency,
            rule = retracementRuleOf(m[1].lens / m[0].lens),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // الگوی ۶: سیمتریکال ۹ موجی
    // ────────────────────────────────────────────────────────────────────────

    private fun classifySymmetrical(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 9) ?: return null
        val lens = m.map { it.lens }
        val center = lens[4]
        if (center <= 0.0) return null
        if (lens.indexOf(lens.max()) != 4) return null

        val rules = listOf(
            rule(1, "نُه مونوویو با تناوب کامل", m.size == 9, "${m.size} مونوویو",
                weight = 1.5, isHard = true),
            rule(2, "موج میانی (پنجم) بلندترین موج است", lens.indexOf(lens.max()) == 4,
                "بلندترین موج = ${lens.indexOf(lens.max()) + 1}", weight = 2.0, isHard = true),
            rule(3, "تقارن زمانی موج ۴ و ۶", symmetryRatio(m[3].bars, m[5].bars) <= 1.75,
                "نسبت = ${num(symmetryRatio(m[3].bars, m[5].bars))}"),
            rule(4, "تقارن زمانی موج ۳ و ۷", symmetryRatio(m[2].bars, m[6].bars) <= 1.75,
                "نسبت = ${num(symmetryRatio(m[2].bars, m[6].bars))}"),
            rule(5, "تقارن زمانی موج ۲ و ۸", symmetryRatio(m[1].bars, m[7].bars) <= 1.75,
                "نسبت = ${num(symmetryRatio(m[1].bars, m[7].bars))}"),
            rule(6, "تقارن زمانی موج ۱ و ۹", symmetryRatio(m[0].bars, m[8].bars) <= 2.00,
                "نسبت = ${num(symmetryRatio(m[0].bars, m[8].bars))}"),
            rule(7, "تقارن قیمتی موج ۲ و ۸", symmetryRatio(m[1].lens, m[7].lens) <= 1.80,
                "نسبت = ${num(symmetryRatio(m[1].lens, m[7].lens))}", weight = 1.5),
        )
        val height = lens.sum()
        val expected = expectedAfterCorrection(monos, end, 9, fallback = m[8].dir)
        val target = m[8].to.price + (if (expected == SignalAction.BUY) 1.0 else -1.0) * center * 0.618
        return Candidate(
            pattern = NeoWavePattern.SYMMETRICAL,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[8].to.price,
            target = target,
            height = height,
            priority = 65,
            recency = recency,
            rule = retracementRuleOf(m[1].lens / m[0].lens),
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // داوری (Gate)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * داوری نئوویو روی یک ورود.
     *
     * جدول تصمیم:
     * - خاموش یا بدون ساختار معتبر → خنثی.
     * - الگوی کامل، هم‌جهت، تأییدشده و قواعد کافی → تقویت اطمینان (+۸).
     * - الگوی کامل، هم‌جهت، قواعد ناکافی یا بدون تأیید → تقویت ملایم (+۲).
     * - الگوی کامل، مخالف، **تأییدشده** و قواعد/اطمینان کافی → **وتو**.
     * - الگوی کامل، مخالف، بدون تأیید پس‌ساختاری → تضعیف (−۳)؛ بدون وتو.
     * - الگوی در حال تکمیل → ±۳/−۴ بدون وتو.
     */
    fun gate(
        verdict: NeoWaveVerdict?,
        isLong: Boolean,
        minRulesPassed: Int = MIN_RULE_PASS,
        minConfidencePct: Double = MIN_CONFIDENCE_PCT,
    ): NeoWaveGate {
        if (verdict == null || !verdict.isUsable) {
            return NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = 0.0,
                noteFa = "ساختار نئوویو برای این تایم‌فریم هنوز معتبر نیست (داور خنثی).",
                verdict = null,
            )
        }
        val minRules = minRulesPassed.coerceIn(1, 12)
        val minConf = minConfidencePct.coerceIn(35.0, 95.0)
        val strong = verdict.rulesPassed >= minRules && verdict.confidencePct >= minConf
        val aligned = verdict.isAlignedWith(isLong)
        val dirFa = if (isLong) "خرید" else "فروش"

        return when {
            verdict.isComplete && aligned && strong && verdict.isConfirmed -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = 8.0,
                noteFa = "✅ نئوویو تأیید می‌کند: ${verdict.badgeFa} (هم‌جهت با $dirFa)",
                verdict = verdict,
            )

            verdict.isComplete && aligned -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = 2.0,
                noteFa = "🟡 نئوویو هم‌جهت است اما شمارش محکم/تأییدشده نیست " +
                    "(${verdict.rulesPassed}/${verdict.rulesTotal} قاعده · ${verdict.confidencePct.toInt()}%)",
                verdict = verdict,
            )

            verdict.isComplete && strong && verdict.isConfirmed -> NeoWaveGate(
                enabled = true,
                veto = true,
                confidenceDelta = -12.0,
                noteFa = "⛔ نئوویو وتو کرد: ${verdict.badgeFa} با تأیید پس‌ساختاری مخالف $dirFa است",
                verdict = verdict,
            )

            verdict.isComplete -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = -3.0,
                noteFa = "⚠️ نئوویو مخالف $dirFa است ولی شمارش/تأیید کافی ندارد (${verdict.confidencePct.toInt()}%)",
                verdict = verdict,
            )

            aligned -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = 3.0,
                noteFa = "🌊 نئوویو: ${verdict.badgeFa} در حال تکمیل، هم‌جهت با $dirFa",
                verdict = verdict,
            )

            else -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = -4.0,
                noteFa = "🌊 نئوویو: ${verdict.badgeFa} در حال تکمیل، مخالف $dirFa",
                verdict = verdict,
            )
        }
    }

    /** خلاصهٔ فارسی وضعیت نئوویو برای نمایش در پنل (بدون تحلیل تازه). */
    fun statusLineFa(verdict: NeoWaveVerdict?): String = when {
        verdict == null -> "🌊 نئوویو: کندل کافی برای شمارش معتبر موجود نیست."
        verdict.pattern == NeoWavePattern.UNKNOWN -> "🌊 نئوویو: ساختار فعلی هیچ الگوی معتبری از قواعد نیلی را پاس نمی‌کند."
        else -> verdict.summaryFa
    }
}
