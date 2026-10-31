package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

/**
 * موتور **نئوویو (NeoWave — گلن نیلی)** به‌صورت یک «لایهٔ تأیید» اختیاری.
 *
 * ## چرا لایهٔ تأیید و نه استراتژی مستقل؟
 * موتور اصلی (`IchimokuStrategyEngine`) برای هر ۱۶ مدل، تمام ۱۶ شرط ساختاری را الزامی می‌داند
 * (`requiredIndicesForModel = (1..16)`). بنابراین افزودن مدل هفدهم عملاً یک نسخهٔ دیگر از همان
 * معامله می‌سازد. نئوویو در این معماری یک **داور** است: امتیاز ورودهای موجود را تقویت یا وتو می‌کند.
 *
 * ## آنچه محاسبه می‌شود
 * ۱. تجزیهٔ قیمت به **مونوویو** با زیگزاگ ATR-تطبیقی (پیوت‌های فراکتالی + فیلتر دامنه).
 * ۲. طبقه‌بندی ساختار: Impulse پنج‌موجی، Impulse در حال گسترش، ZigZag، Flat، Triangle،
 *    Diametric هفت‌موجی، Symmetrical نه‌موجی و Extracting Triangle.
 * ۳. حسابرسی قواعد نیلی (بازگشت قیمتی، محدودیت زمانی، موج سوم کوتاه‌ترین نباشد،
 *    عدم همپوشانی موج ۴ با موج ۱، تناوب موج ۲ و ۴، کانال ۰-۲، هدف فیبوناچی).
 * ۴. جهت مورد انتظار موج بعدی + هدف پروجکشن + **قیمت ابطال** ساختار.
 *
 * ## صداقت مهندسی
 * این یک داور **قاعده‌محور** است، نه تحلیلگر ذهنیِ حرفه‌ای. قواعد مکانیکیِ منتشرشدهٔ نیلی پیاده
 * شده‌اند و خروجی همیشه با «درصد اطمینان = نسبت قواعد پاس‌شده» همراه است تا کاربر بداند
 * شمارش چقدر محکم است. هیچ ادعای قطعیت نمی‌شود.
 *
 * ## قانون خنثی‌بودن (Zero-Effect Contract)
 * وقتی `BaseStrategyConfig.neoWaveEnabled == false` باشد (پیش‌فرض)، هیچ‌کدام از این توابع فراخوانی
 * نمی‌شوند و خروجی موتور استراتژی بیت‌به‌بیت مثل نسخهٔ قبل از نئوویو می‌ماند.
 */
object NeoWaveEngine {

    /** حداقل کندل لازم برای یک شمارش معتبر. */
    const val MIN_BARS: Int = 45

    /** حداکثر کندلی که برای شمارش خوانده می‌شود (کنترل بودجهٔ پردازش). */
    const val MAX_BARS: Int = 240

    /** دامنهٔ پیش‌فرض زیگزاگ بر حسب ATR (کوچک‌تر = شمارش ریزتر و پرنویز‌تر). */
    const val DEFAULT_AMPLITUDE_ATR: Double = 0.55

    /** حداقل قواعد پاس‌شده برای اینکه شمارش «معتبر» شمرده شود. */
    const val MIN_RULE_PASS: Int = 6

    /** حداقل اطمینان برای اینکه نئوویو اجازهٔ وتو داشته باشد. */
    const val MIN_CONFIDENCE_PCT: Double = 55.0

    /** نسبت حداقل اطمینان برای پذیرش یک الگو به‌عنوان نامزد. */
    private const val CANDIDATE_MIN_RATIO: Double = 0.60

    // ────────────────────────────────────────────────────────────────────────
    // مدل‌های داده
    // ────────────────────────────────────────────────────────────────────────

    /** الگوهای قابل تشخیص نئوویو (زیرمجموعهٔ مکانیکیِ قابل پیاده‌سازی). */
    enum class NeoWavePattern(
        val titleFa: String,
        val canonicalWaves: Int,
        val isCorrective: Boolean,
        val badgeFa: String,
    ) {
        IMPULSE("موجانگیزهٔ ۵ موجی (Impulse)", 5, false, "۵موجی"),
        IMPULSE_IN_PROGRESS("موجانگیزهٔ در حال گسترش (موج ۳/۵)", 3, false, "در گسترش"),
        ZIGZAG("زیگزاگ اصلاحی ۳ موجی", 3, true, "زیگزاگ"),
        FLAT("فلت اصلاحی ۳ موجی", 3, true, "فلت"),
        TRIANGLE("مثلث همگرای ۵ موجی", 5, true, "مثلث"),
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

    /** یک قاعدهٔ نیلی با نتیجهٔ حسابرسی. */
    data class NeoWaveRule(
        val index: Int,
        val titleFa: String,
        val passed: Boolean,
        val detailFa: String,
    )

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
    ) {
        val rulesTotal: Int get() = rules.size
        val isUsable: Boolean
            get() = pattern != NeoWavePattern.UNKNOWN && rulesTotal > 0

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
     * مراحل: پیدا کردن اکسترمم محلی → ادغام اکسترمم‌های هم‌نوع متوالی → حذف حرکات کوچک‌تر از آستانه.
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

        // ۱) ادغام اکسترمم‌های هم‌نوع متوالی (بالاترین سقف / پایین‌ترین کف).
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

        // ۲) حذف حرکات کوچک‌تر از آستانه + حفظ تناوب سقف/کف.
        val cleaned = ArrayList<NeoWavePivot>()
        for (p in merged) {
            val last = cleaned.lastOrNull()
            if (last == null) {
                cleaned.add(p)
                continue
            }
            if (abs(p.price - last.price) < threshold) {
                // حرکت بی‌معنا: اکسترمم قوی‌تر را نگه می‌داریم.
                when {
                    p.isHigh && last.isHigh -> if (p.price > last.price) cleaned[cleaned.lastIndex] = p
                    !p.isHigh && !last.isHigh -> if (p.price < last.price) cleaned[cleaned.lastIndex] = p
                    else -> Unit // سقف/کف متناوب اما بی‌دامنه: هر دو را رد می‌کنیم
                }
            } else {
                cleaned.add(p)
            }
        }
        // ۳) بعد از پاک‌سازی ممکن است دو پیوت هم‌نوع پشت‌سرهم بمانند؛ دوباره ادغام می‌کنیم.
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

        // ۴) تکمیل ساختار در ابتدا: پنجرهٔ فراکتالی نمی‌تواند پیوتِ نخستین کندل‌ها را ببیند،
        // بنابراین اکسترمم مخالف قبل از اولین پیوت را اضافه می‌کنیم تا شمارش موج از نقطهٔ صفر شروع شود.
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
                    finalPivots.add(
                        0,
                        NeoWavePivot(idx, bars[idx].time, price, !first.isHigh),
                    )
                }
            }
        }
        return finalPivots
    }

    // ────────────────────────────────────────────────────────────────────────
    // شمارش و طبقه‌بندی
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
            val bars = (b.index - a.index).coerceAtLeast(1)
            val dir = when {
                b.price > a.price -> 1
                b.price < a.price -> -1
                else -> 0
            }
            if (len > 0.0) out.add(Mono(a, b, len, bars, dir))
        }
        return out
    }

    private fun retraceRatio(child: Mono, parent: Mono): Double =
        if (parent.lens <= 0.0) 0.0 else child.lens / parent.lens

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    /** یک نامزد الگو قبل از انتخاب نهایی. */
    private data class Candidate(
        val pattern: NeoWavePattern,
        val rules: List<NeoWaveRule>,
        val expectedDirection: SignalAction,
        val isComplete: Boolean,
        val invalidation: Double?,
        val target: Double?,
        val height: Double,
        val priority: Int,
        /** ۰ = ساختار به آخرین پیوت ختم می‌شود؛ ۱ و ۲ = یک/دو پیوت عقب‌تر. */
        val recency: Int,
    ) {
        val passed: Int get() = rules.count { it.passed }
        val ratio: Double get() = if (rules.isEmpty()) 0.0 else passed.toDouble() / rules.size
    }

    /**
     * پنجره‌ای از مونوویوها که به پیوت `end` ختم می‌شود.
     *
     * چرا پنجره و نه همیشه «آخرین n مونوویو»؟ چون پیوت فقط پس از یک برگشتِ تأییدشده ساخته می‌شود؛
     * بنابراین درست وقتی موج ۵ تمام می‌شود، یک مونوویوی جدید (شروع اصلاح) هم شکل گرفته است.
     * بررسی دو پنجرهٔ عقب‌تر اجازه می‌دهد الگوی تازه‌تمام‌شده هم دیده شود.
     */
    private fun window(monos: List<Mono>, end: Int, count: Int): List<Mono>? =
        if (end - count < 0 || end > monos.size) null else monos.subList(end - count, end)

    /** جهت روندِ قبل از ساختار (موج بزرگ‌تر). برای الگوهای اصلاحی، جهت بازگشت = همین. */
    private fun priorDirection(monos: List<Mono>, end: Int, count: Int): Int {
        val start = end - count
        return if (start - 1 >= 0) monos[start - 1].dir else 0
    }

    private fun expectedAfterCorrection(monos: List<Mono>, end: Int, count: Int, fallback: Int): SignalAction {
        val prior = priorDirection(monos, end, count)
        val dir = if (prior != 0) prior else -fallback
        return if (dir > 0) SignalAction.BUY else SignalAction.SELL
    }

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
        }

        val best = candidates
            .filter { it.passed >= 3 && it.ratio >= CANDIDATE_MIN_RATIO }
            .maxWithOrNull(
                compareBy<Candidate> { -it.recency }
                    .thenBy { it.priority }
                    .thenBy { it.ratio },
            ) ?: return null

        var confidence = best.ratio * 100.0
        if (!best.isComplete) confidence -= 8.0
        confidence = round2(confidence.coerceIn(0.0, 100.0))

        val dirFa = when (best.expectedDirection) {
            SignalAction.BUY -> "صعودی"
            SignalAction.SELL -> "نزولی"
            SignalAction.NO_TRADE -> "بی‌جهت"
        }
        val badge = "🌊 نئوویو: ${best.pattern.badgeFa} · ${best.passed}/${best.rules.size} قاعده · انتظار $dirFa"
        val summary = buildString {
            append("${best.pattern.titleFa}؛ ")
            append("${best.passed} از ${best.rules.size} قاعدهٔ نیلی پاس شد (اطمینان ${confidence.toInt()}%)؛ ")
            append("جهت مورد انتظار موج بعدی: $dirFa")
            best.target?.let { append(" · هدف پروجکشن ${round2(it)}") }
            best.invalidation?.let { append(" · ابطال ساختار ${round2(it)}") }
            if (!best.isComplete) append(" · ساختار هنوز کامل نشده")
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
        )
    }

    /** موج‌انگیزهٔ ۵ موجی کامل. */
    private fun classifyImpulseComplete(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 5) ?: return null
        val d = m[0].dir
        if (d == 0) return null
        if (m[1].dir != -d || m[2].dir != d || m[3].dir != -d || m[4].dir != d) return null

        val r2 = retraceRatio(m[1], m[0])
        val r4 = retraceRatio(m[3], m[2])
        val rules = ArrayList<NeoWaveRule>()

        rules.add(rule(1, "بازگشت موج ۲ حداکثر ۶۱.۸٪ موج ۱", r2 <= 0.618, "بازگشت موج ۲ = ${pct(r2)}"))
        rules.add(rule(2, "موج ۲ کل موج ۱ را اصلاح نمی‌کند", r2 < 0.995, "نسبت اصلاح = ${pct(r2)}"))
        rules.add(
            rule(3, "موج ۳ کوتاه‌ترین موج درایو نیست", m[2].lens >= min(m[0].lens, m[4].lens) * 0.95,
                "موج۳=${num(m[2].lens)} · موج۱=${num(m[0].lens)} · موج۵=${num(m[4].lens)}"),
        )
        val nonOverlap = if (d > 0) m[3].to.price > m[0].to.price else m[3].to.price < m[0].to.price
        rules.add(
            rule(4, "موج ۴ با محدودهٔ قیمتی موج ۱ هم‌پوشانی ندارد", nonOverlap,
                "پایان موج۴=${num(m[3].to.price)} · پایان موج۱=${num(m[0].to.price)}"),
        )
        rules.add(
            rule(5, "محدودیت زمانی موج ۲ (≤ ۱.۶۱۸ × زمان موج ۱)", m[1].bars <= m[0].bars * 1.618,
                "زمان موج۲=${m[1].bars} · موج۱=${m[0].bars}"),
        )
        val timeRatio = if (min(m[1].bars, m[3].bars) == 0) 99.0
        else max(m[1].bars, m[3].bars).toDouble() / min(m[1].bars, m[3].bars).toDouble()
        rules.add(
            rule(6, "تناوب موج ۲ و موج ۴ (عمق یا زمان)", abs(r2 - r4) >= 0.15 || timeRatio >= 1.6,
                "عمق۲=${pct(r2)} · عمق۴=${pct(r4)} · نسبت زمان=${num(timeRatio)}"),
        )
        val projRatio = m[4].lens / m[0].lens
        rules.add(
            rule(7, "هدف موج ۵ در دامنهٔ فیبوناچی (۰.۳۸۲ تا ۲.۶۱۸ موج ۱)", projRatio in 0.382..2.618,
                "موج۵/موج۱ = ${num(projRatio)}"),
        )
        rules.add(
            rule(8, "شکست کانال ۰-۲ توسط موج ۳ (گسترش سالم)",
                if (d > 0) m[2].to.price > m[0].to.price else m[2].to.price < m[0].to.price,
                "پایان موج۳=${num(m[2].to.price)} · پایان موج۱=${num(m[0].to.price)}"),
        )

        val height = abs(m[2].to.price - m[0].from.price)
        val target = m[4].to.price - (if (d > 0) 1.0 else -1.0) * height * 0.382
        // پایان موج ۵ ⇒ موج بعدی اصلاحی و خلاف جهت درایو است.
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
        )
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
            rule(1, "بازگشت موج ۲ کمتر از ۶۱.۸٪ موج ۱", r2 <= 0.618, "بازگشت = ${pct(r2)}"),
            rule(2, "موج ۳ از موج ۱ کشیده‌تر است", extension >= 1.0, "موج۳/موج۱ = ${num(extension)}"),
            rule(3, "محدودیت زمانی موج ۲", m[1].bars <= m[0].bars * 1.618,
                "زمان موج۲=${m[1].bars} · موج۱=${m[0].bars}"),
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
        )
    }

    /** زیگزاگ اصلاحی سه‌موجی. */
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
            rule(1, "بازگشت موج b در دامنهٔ ۳۸.۲ تا ۷۹.۱٪ موج a", r2 in 0.382..0.791, "بازگشت = ${pct(r2)}"),
            rule(2, "موج c فراتر از انتهای موج a می‌رود", beyond,
                "پایان c=${num(m[2].to.price)} · پایان a=${num(m[0].to.price)}"),
            rule(3, "نسبت موج c به موج a در دامنهٔ ۰.۶۱۸ تا ۱.۶۱۸", cRatio in 0.618..1.618, "c/a = ${num(cRatio)}"),
            rule(4, "محدودیت زمانی موج b", m[1].bars <= m[0].bars * 1.618, "زمان b=${m[1].bars} · a=${m[0].bars}"),
            rule(5, "موج b زمان کمتری از موج c گرفته است", m[1].bars <= m[2].bars * 1.5,
                "زمان b=${m[1].bars} · c=${m[2].bars}"),
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
        )
    }

    /** فلت اصلاحی: موج b تقریباً کل موج a را برمی‌گرداند و c هم‌اندازهٔ a است. */
    private fun classifyFlat(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 3) ?: return null
        val d = m[0].dir
        if (d == 0 || m[1].dir != -d || m[2].dir != d) return null
        if (m[0].lens <= 0.0) return null
        val r2 = retraceRatio(m[1], m[0])
        if (r2 < 0.70 || r2 > 1.382) return null
        val cRatio = m[2].lens / m[0].lens
        // در فلت، موج c نزدیک «پایان موج a» تمام می‌شود (نه نزدیک شروع آن).
        val closeToEndOfA = abs(m[2].to.price - m[0].to.price) <= m[0].lens * 0.35

        val rules = listOf(
            rule(1, "بازگشت موج b حداقل ۷۹.۱٪ موج a", r2 >= 0.791, "بازگشت = ${pct(r2)}"),
            rule(2, "موج b از ۱۳۸٪ موج a فراتر نمی‌رود", r2 <= 1.382, "نسبت = ${num(r2)}"),
            rule(3, "موج c هم‌اندازهٔ موج a (۰.۷ تا ۱.۳)", cRatio in 0.70..1.30, "c/a = ${num(cRatio)}"),
            rule(4, "پایان موج c نزدیک پایان موج a (فلت منظم)", closeToEndOfA,
                "فاصله = ${num(abs(m[2].to.price - m[0].to.price))}"),
            rule(5, "محدودیت زمانی موج b", m[1].bars <= m[0].bars * 2.618,
                "زمان b=${m[1].bars} · a=${m[0].bars}"),
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
        )
    }

    /** مثلث همگرا (۵ مونوویو با دامنهٔ نزولی) یا مثلث استخراجی (دامنهٔ صعودی). */
    private fun classifyTriangle(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 5) ?: return null
        val contracting = m[0].lens > m[2].lens && m[2].lens > m[4].lens && m[1].lens > m[3].lens
        val extracting = m[0].lens < m[2].lens && m[2].lens < m[4].lens && m[1].lens < m[3].lens
        if (!contracting && !extracting) return null
        val ratio = if (m[0].lens <= 0.0) 0.0 else m[4].lens / m[0].lens
        val rules = listOf(
            rule(1, "توالی دامنه‌ها یکنواخت است", contracting || extracting, if (contracting) "همگرا" else "واگرا"),
            rule(2, "نسبت آخرین موج به اولین موج در دامنهٔ ۰.۲ تا ۰.۹", ratio in 0.20..0.90, "نسبت = ${num(ratio)}"),
            rule(3, "موج‌های زوج کوچک‌تر/بزرگ‌تر از موج‌های فرد هستند",
                if (contracting) m[1].lens > m[3].lens else m[1].lens < m[3].lens,
                "موج۲=${num(m[1].lens)} · موج۴=${num(m[3].lens)}"),
            rule(4, "داخل دامنهٔ موج ۱ باقی مانده است",
                m[4].to.price >= min(m[0].from.price, m[0].to.price) - m[0].lens * 0.25 &&
                    m[4].to.price <= max(m[0].from.price, m[0].to.price) + m[0].lens * 0.25,
                "پایان موج۵=${num(m[4].to.price)}"),
            rule(5, "تقارن زمانی نسبی", m[0].bars + m[2].bars + m[4].bars > 0, "زمان‌ها محاسبه شد"),
        )
        val height = abs(m[0].to.price - m[0].from.price)
        val expected = expectedAfterCorrection(monos, end, 5, fallback = m[4].dir)
        val target = m[4].to.price + (if (expected == SignalAction.BUY) 1.0 else -1.0) * height * 0.618
        return Candidate(
            pattern = if (contracting) NeoWavePattern.TRIANGLE else NeoWavePattern.EXTRACTING_TRIANGLE,
            rules = rules,
            expectedDirection = expected,
            isComplete = true,
            invalidation = m[4].to.price,
            target = target,
            height = height,
            priority = 35,
            recency = recency,
        )
    }

    /** دیامتریک هفت‌موجی نیلی: موج چهارم بزرگ‌ترین و تقارن زمانی حول آن. */
    private fun classifyDiametric(monos: List<Mono>, end: Int, recency: Int): Candidate? {
        val m = window(monos, end, 7) ?: return null
        val lens = m.map { it.lens }
        val center = lens[3]
        if (center <= 0.0) return null
        if (lens.indexOf(lens.max()) != 3) return null

        val timeSymmetry1 = symmetryRatio(m[2].bars, m[4].bars)
        val timeSymmetry2 = symmetryRatio(m[1].bars, m[5].bars)
        val timeSymmetry3 = symmetryRatio(m[0].bars, m[6].bars)
        val priceSymmetry = symmetryRatio(m[1].lens, m[5].lens)

        val rules = listOf(
            rule(1, "هفت مونوویو با تناوب کامل", m.size == 7, "${m.size} مونوویو"),
            rule(2, "موج میانی (چهارم) بزرگ‌ترین موج است", lens.indexOf(lens.max()) == 3,
                "بزرگ‌ترین موج = ${lens.indexOf(lens.max()) + 1}"),
            rule(3, "تقارن زمانی موج ۳ و ۵", timeSymmetry1 <= 1.75, "نسبت = ${num(timeSymmetry1)}"),
            rule(4, "تقارن زمانی موج ۲ و ۶", timeSymmetry2 <= 1.75, "نسبت = ${num(timeSymmetry2)}"),
            rule(5, "تقارن زمانی موج ۱ و ۷", timeSymmetry3 <= 2.00, "نسبت = ${num(timeSymmetry3)}"),
            rule(6, "تقارن قیمتی موج ۲ و ۶", priceSymmetry <= 1.80, "نسبت = ${num(priceSymmetry)}"),
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
        )
    }

    private fun symmetryRatio(a: Int, b: Int): Double = symmetryRatio(a.toDouble(), b.toDouble())

    private fun symmetryRatio(a: Double, b: Double): Double {
        val hi = max(a, b)
        val lo = min(a, b)
        return if (lo <= 0.0) 99.0 else hi / lo
    }

    private fun rule(index: Int, title: String, passed: Boolean, detail: String) =
        NeoWaveRule(index, title, passed, detail)

    private fun pct(v: Double): String = "${(v * 100.0).toInt()}٪"

    private fun num(v: Double): String = ((round(v * 100.0) / 100.0)).toString()
    // ────────────────────────────────────────────────────────────────────────
    // داوری (Gate)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * داوری نئوویو روی یک ورود مشخص.
     *
     * جدول تصمیم:
     * - خاموش یا بدون ساختار معتبر → خنثی (بدون تغییر).
     * - الگوی کامل، هم‌جهت، قواعد کافی → تقویت اطمینان (+۸).
     * - الگوی کامل، هم‌جهت، قواعد ناکافی → تقویت ملایم (+۲).
     * - الگوی کامل، مخالف جهت، قواعد و اطمینان کافی → **وتو**.
     * - الگوی کامل، مخالف جهت، قواعد ناکافی → تضعیف (−۳).
     * - الگوی در حال تکمیل، هم‌جهت → تقویت ملایم (+۳)؛ مخالف → تضعیف (−۴)، بدون وتو.
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
            verdict.isComplete && aligned && strong -> NeoWaveGate(
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
                noteFa = "🟡 نئوویو هم‌جهت است اما شمارش محکم نیست (${verdict.rulesPassed}/${verdict.rulesTotal} قاعده · ${verdict.confidencePct.toInt()}%)",
                verdict = verdict,
            )

            verdict.isComplete && strong -> NeoWaveGate(
                enabled = true,
                veto = true,
                confidenceDelta = -12.0,
                noteFa = "⛔ نئوویو وتو کرد: ${verdict.badgeFa} مخالف $dirFa است",
                verdict = verdict,
            )

            verdict.isComplete -> NeoWaveGate(
                enabled = true,
                veto = false,
                confidenceDelta = -3.0,
                noteFa = "⚠️ نئوویو مخالف $dirFa است ولی شمارش ضعیف است (${verdict.confidencePct.toInt()}%)",
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
