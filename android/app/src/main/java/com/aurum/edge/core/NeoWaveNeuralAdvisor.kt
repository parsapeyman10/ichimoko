package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * مشاور عصبیِ نئوویو — تنظیمات لایهٔ نئوویو را به «هوش عصبی» می‌سپارد.
 *
 * منطق: هر معاملهٔ بسته‌شده‌ای که در لحظهٔ ورودش یک شمارش نئوویو داشته، یک نمونهٔ آموزش است
 * (`rulesPassed`، `rulesTotal`، `confidencePct`، قاعده‌های شکست‌خورده، برنده/بازنده، R تحقق‌یافته).
 * این لایه از همان نمونه‌ها سه چیز را می‌آموزد:
 *
 * ۱) **آستانهٔ قواعد و اطمینان:** همهٔ جفت‌های (قواعد، اطمینان) جاروب می‌شوند و جفتی که
 *    بیشترین «امید ریاضی R» را با نمونهٔ کافی دارد انتخاب می‌شود — نه سلیقهٔ دستی.
 * ۲) **وزن قواعد نیلی:** برای هر قاعده، وین‌ریت معاملاتی که آن قاعده را پاس کرده‌اند با وین‌ریت
 *    معاملاتی که آن را شکسته‌اند مقایسه می‌شود؛ قاعدهٔ سودآور وزن > ۱ و قاعدهٔ زیان‌ده وزن < ۱ می‌گیرد.
 * ۳) **دلیل فارسی:** خروجی همیشه با توضیح «بر پایهٔ چند نمونه، چه چیزی انتخاب شد» همراه است تا
 *    کاربر بداند این اعداد از کجا آمده‌اند (بدون جعبهٔ سیاه).
 *
 * صداقت روش: این یک لایهٔ **آماری/یادگیرنده روی دادهٔ واقعی معاملات** است، نه پیش‌بینی جادویی.
 * با نمونهٔ کم، هیچ تغییری اعمال نمی‌شود و آستانه‌های قبلی دست‌نخورده می‌مانند.
 */
object NeoWaveNeuralAdvisor {

    /** حداقل نمونهٔ لازم برای اینکه اصلاً حق دست‌زدن به تنظیمات داشته باشیم. */
    const val MIN_SAMPLES: Int = 20

    /** حداقل نمونه در هر سبد (جفت آستانه) تا آن سبد قابل اعتماد شمرده شود. */
    const val MIN_BUCKET_SAMPLES: Int = 8

    /** بازهٔ جاروب آستانهٔ قواعد. */
    val RULE_CANDIDATES: IntRange = 4..10

    /** گام جاروب آستانهٔ اطمینان. */
    const val CONFIDENCE_STEP: Int = 5
    val CONFIDENCE_CANDIDATES: IntRange = 45..85

    /** یک نمونهٔ آموزش: یک معاملهٔ بسته‌شده با شمارش نئوویو در لحظهٔ ورود. */
    data class Sample(
        val patternId: String,
        val rulesPassed: Int,
        val rulesTotal: Int,
        val confidencePct: Double,
        val failedRuleIndexes: List<Int> = emptyList(),
        val won: Boolean,
        val achievedR: Double,
    )

    /** یک جفت آستانهٔ کاندید با آمارش. */
    data class Candidate(
        val rules: Int,
        val confidencePct: Double,
        val samples: Int,
        val winRatePct: Double,
        val expectancyR: Double,
    )

    /**
     * خروجی مشاور.
     *
     * @property rulesThreshold آستانهٔ قواعد انتخاب‌شده (یا مقدار قبلی وقتی [isActive] = false).
     * @property confidenceThresholdPct آستانهٔ اطمینان انتخاب‌شده.
     * @property ruleWeights وزن آموخته‌شدهٔ قواعد (کلید = شمارهٔ قاعده).
     * @property isActive آیا داده کافی بود و تنظیمات واقعاً به‌روزرسانی شد.
     * @property basisFa توضیح فارسی مبنای تصمیم.
     */
    data class Tuning(
        val rulesThreshold: Int,
        val confidenceThresholdPct: Double,
        val ruleWeights: Map<Int, Double> = emptyMap(),
        val samples: Int = 0,
        val winRatePct: Double = 0.0,
        val expectancyR: Double = 0.0,
        val isActive: Boolean = false,
        val basisFa: String = "",
        val candidates: List<Candidate> = emptyList(),
    )

    /**
     * مشورت‌گیری.
     *
     * @param samples نمونه‌های معاملات بسته‌شده.
     * @param fallbackRules آستانهٔ فعلی قواعد (اگر داده کافی نبود).
     * @param fallbackConfidence آستانهٔ فعلی اطمینان.
     */
    fun advise(
        samples: List<Sample>,
        fallbackRules: Int,
        fallbackConfidence: Double,
    ): Tuning {
        val usable = samples.filter { it.rulesTotal > 0 && it.rulesPassed in 0..it.rulesTotal }
        if (usable.size < MIN_SAMPLES) {
            return Tuning(
                rulesThreshold = fallbackRules,
                confidenceThresholdPct = fallbackConfidence,
                samples = usable.size,
                isActive = false,
                basisFa = "هنوز نمونهٔ کافی برای سپردن تنظیمات به هوش عصبی نیست " +
                    "(${usable.size} از $MIN_SAMPLES معاملهٔ دارای شمارش نئوویو)؛ آستانه‌های فعلی دست‌نخورده ماندند.",
            )
        }

        val minBucket = max(MIN_BUCKET_SAMPLES, usable.size / 4)
        val candidates = ArrayList<Candidate>()
        for (rules in RULE_CANDIDATES) {
            for (conf in CONFIDENCE_CANDIDATES step CONFIDENCE_STEP) {
                val bucket = usable.filter { it.rulesPassed >= rules && it.confidencePct >= conf - 0.0001 }
                if (bucket.size < minBucket) continue
                val wins = bucket.count { it.won }
                val winRate = wins * 100.0 / bucket.size
                val expectancy = bucket.sumOf { it.achievedR } / bucket.size
                candidates += Candidate(rules, conf.toDouble(), bucket.size, winRate, expectancy)
            }
        }
        if (candidates.isEmpty()) {
            return Tuning(
                rulesThreshold = fallbackRules,
                confidenceThresholdPct = fallbackConfidence,
                samples = usable.size,
                isActive = false,
                basisFa = "با ${usable.size} نمونه هیچ سبدی به حداقل ${minBucket} معامله نرسید؛ " +
                    "برای جلوگیری از تصمیم روی دادهٔ ناکافی، تنظیمات فعلی نگه داشته شد.",
                candidates = candidates,
            )
        }

        // امتیاز هر سبد = امید ریاضی منهای جریمهٔ نمونهٔ کم (کرانِ محافظه‌کارانه).
        // سبدهای کوچک نباید فقط چون «خوش‌شانس» بوده‌اند برنده شوند.
        val scored = candidates.map { it to (it.expectancyR - 0.35 / sqrt(it.samples.toDouble())) }
        val bestScore = scored.maxOf { it.second }
        val best = scored.first { it.second == bestScore }.first
        val chosen = if (best.expectancyR <= 0.0) {
            candidates.filter { it.expectancyR > 0.0 }
                .maxWithOrNull(compareBy<Candidate>({ it.winRatePct }, { it.samples }))
                ?: best
        } else {
            // بین سبدهای «تقریباً هم‌امتیاز»، سخت‌گیرانه‌ترین را انتخاب می‌کنیم: کیفیت مهم‌تر از
            // تعداد سیگنال است و کاربر نباید بی‌دلیل آستانهٔ شل بگیرد.
            scored.filter { it.second >= bestScore - 0.05 }
                .map { it.first }
                .maxWithOrNull(compareBy<Candidate>({ it.rules }, { it.confidencePct }, { it.samples }))
                ?: best
        }

        val weights = ruleWeights(usable)
        val basisFa = buildString {
            append("بر پایهٔ ").append(usable.size).append(" معاملهٔ بسته‌شده با شمارش نئوویو: ")
            append("آستانهٔ «").append(chosen.rules).append(" قاعده» و «")
            append(chosen.confidencePct.toInt()).append("٪ اطمینان» ")
            append("با ").append(chosen.samples).append(" معامله، وین‌ریت ")
            append(String.format(java.util.Locale.US, "%.0f", chosen.winRatePct)).append("٪ و امید ریاضی ")
            append(String.format(java.util.Locale.US, "%.2f", chosen.expectancyR)).append("R بهترین نتیجه را داد.")
            if (weights.isNotEmpty()) {
                val top = weights.entries.sortedByDescending { it.value }.take(3)
                    .joinToString("، ") {
                        "قاعدهٔ ${it.key} ×${String.format(java.util.Locale.US, "%.2f", it.value)}"
                    }
                append(" سنگین‌ترین قواعد آموخته‌شده: ").append(top).append(".")
            }
        }

        return Tuning(
            rulesThreshold = chosen.rules,
            confidenceThresholdPct = chosen.confidencePct,
            ruleWeights = weights,
            samples = usable.size,
            winRatePct = chosen.winRatePct,
            expectancyR = chosen.expectancyR,
            isActive = true,
            basisFa = basisFa,
            candidates = candidates,
        )
    }

    /**
     * وزن قواعد نیلی از روی نمونه‌ها.
     *
     * قاعده‌ای که **شکستنش** با معاملات بازنده همبستگی دارد وزن بالاتر (> ۱) می‌گیرد؛ یعنی اگر
     * شمارشی همان قاعده را نشکسته باشد، اطمینانش بیشتر بالا می‌رود و اگر شکسته باشد، بیشتر تنبیه می‌شود.
     * قاعده‌ای که شکستنش تفاوتی در نتیجه ایجاد نکرده، وزن خنثی (۱.۰) می‌ماند.
     */
    fun ruleWeights(samples: List<Sample>): Map<Int, Double> {
        if (samples.isEmpty()) return emptyMap()
        val overallWin = samples.count { it.won }.toDouble() / samples.size
        val out = LinkedHashMap<Int, Double>()
        for (rule in 1..12) {
            var passed = 0
            var passedWins = 0
            var failed = 0
            var failedWins = 0
            samples.forEach { s ->
                if (rule > s.rulesTotal) return@forEach
                if (rule in s.failedRuleIndexes) {
                    failed++
                    if (s.won) failedWins++
                } else {
                    passed++
                    if (s.won) passedWins++
                }
            }
            if (passed < 4 || failed < 4) {
                out[rule] = 1.0
                continue
            }
            val passRate = passedWins.toDouble() / passed
            val failRate = failedWins.toDouble() / failed
            val deltaPct = (passRate - failRate) * 100.0
            // دلتای ±۱۵٪ ⇒ وزن حدود ۱.۳ / ۰.۷؛ وزن‌ها در بازهٔ [۰.۶، ۱.۴] قفل می‌شوند.
            val weight = (1.0 + 0.6 * tanh(deltaPct / 15.0)).coerceIn(0.6, 1.4)
            out[rule] = if (abs(weight - 1.0) < 0.02 && abs(overallWin - passRate) < 0.02) 1.0 else round2(weight)
        }
        return out
    }

    /**
     * امتیاز وزنی قواعد برای یک شمارش مشخص.
     *
     * @return جفت (تعداد مؤثر قواعد پاس‌شده، نسبت وزنی ۰..۱)
     */
    fun weightedPassScore(
        rulesTotal: Int,
        failedRuleIndexes: List<Int>,
        ruleWeights: Map<Int, Double>,
    ): Pair<Double, Double> {
        if (rulesTotal <= 0) return 0.0 to 0.0
        if (ruleWeights.isEmpty()) {
            val passed = rulesTotal - failedRuleIndexes.count { it in 1..rulesTotal }
            return passed.toDouble() to (passed.toDouble() / rulesTotal)
        }
        var totalWeight = 0.0
        var passedWeight = 0.0
        for (rule in 1..rulesTotal) {
            val w = (ruleWeights[rule] ?: 1.0).coerceIn(0.3, 2.0)
            totalWeight += w
            if (rule !in failedRuleIndexes) passedWeight += w
        }
        if (totalWeight <= 0.0) return 0.0 to 0.0
        val ratio = passedWeight / totalWeight
        return (ratio * rulesTotal) to ratio
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    /** فقط برای تست/نمایش: بهترین سبد بر اساس امید ریاضی. */
    fun bestCandidate(candidates: List<Candidate>): Candidate? =
        candidates.maxWithOrNull(compareBy<Candidate>({ it.expectancyR }, { it.winRatePct }, { it.samples }))

    /** درصد برد نمونه‌ها. */
    fun winRatePct(samples: List<Sample>): Double =
        if (samples.isEmpty()) 0.0 else samples.count { it.won } * 100.0 / samples.size

    /** میانگین R تحقق‌یافته. */
    fun expectancyR(samples: List<Sample>): Double =
        if (samples.isEmpty()) 0.0 else samples.sumOf { it.achievedR } / samples.size

    /** کوچک‌ترین آستانه‌ای که هنوز حداقل نمونه را دارد (برای نمایش «تا کجا می‌توان نرم‌تر شد»). */
    fun tightestUsableConfidence(samples: List<Sample>, rules: Int, minSamples: Int = MIN_BUCKET_SAMPLES): Double? {
        var best: Double? = null
        for (conf in CONFIDENCE_CANDIDATES step CONFIDENCE_STEP) {
            val n = samples.count { it.rulesPassed >= rules && it.confidencePct >= conf - 0.0001 }
            if (n >= minSamples) best = conf.toDouble()
        }
        return best
    }

    /** کوچک‌ترین آستانهٔ قواعد با نمونهٔ کافی. */
    fun tightestUsableRules(samples: List<Sample>, minSamples: Int = MIN_BUCKET_SAMPLES): Int? {
        var best: Int? = null
        for (rules in RULE_CANDIDATES) {
            val n = samples.count { it.rulesPassed >= rules }
            if (n >= minSamples) best = rules
        }
        return best
    }

    /** تعداد نمونه‌های قابل استفاده از یک فهرست معامله. */
    fun usableCount(samples: List<Sample>): Int =
        samples.count { it.rulesTotal > 0 && it.rulesPassed in 0..it.rulesTotal }

    /** سقف و کف مجاز آستانه‌ها برای اعمال روی پیکربندی. */
    fun sanitizeRules(value: Int): Int = min(12, max(1, value))

    fun sanitizeConfidence(value: Double): Double = value.coerceIn(35.0, 95.0)
}
