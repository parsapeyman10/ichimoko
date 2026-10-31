package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * «چقدر قبل‌تر معامله را ببندیم؟» — یادگیری از نتیجهٔ واقعی بازار.
 *
 * ### چرا این روش انتخاب شد
 *
 * برای این تصمیم سه خانوادهٔ روش جهانی وجود دارد:
 *  ۱. **قاعدهٔ ثابت** (مثلاً «۱۵ دقیقه قبل از بسته شدن ببند») — ساده ولی برای هر بازار و هر
 *     رژیم نوسان یکسان است و یا خیلی دیر است یا خیلی زود.
 *  ۲. **مدل رگرسیونی/شبکه‌ای روی ویژگی‌های بازار** — دقیق‌تر، اما به دادهٔ برچسب‌دار زیاد نیاز دارد و
 *     روی گوشی هزینهٔ محاسباتی و ریسک بیش‌برازش دارد.
 *  ۳. **باندیت چندبازویی با نمونه‌های شمارش‌پذیر (Multi-Armed Bandit + shrinkage)** — روشی که در
 *     سیستم‌های معاملاتی تولیدی برای «تنظیم پارامترهای اجرایی» به‌کار می‌رود: هر گزینهٔ «زمان سربار»
 *     یک بازو است، هر خروج واقعی یک پاداش، و مقدار پیش‌فرض به‌عنوان prior از بیش‌برازش جلوگیری می‌کند.
 *
 * همین روش سوم پیاده شده است: گزینه‌ها `[0, 5, 10, 15, 20, 30, 45]` دقیقه، پاداش = «دلاری که با
 * خروج زودتر نسبت به نگه‌داشتن پوزیشن تا بازگشاییِ بعدی نجات پیدا کرد»، و مقدار پیش‌فرض هر دستهٔ
 * دارایی به‌عنوان prior با وزن ۳ نمونه. جست‌وجو (exploration) با نرخ ۱۵٪ انجام می‌شود تا سیستم با
 * تغییر رژیم بازار خودش را دوباره کالیبره کند.
 *
 * ### چرا این یادگیری «آینده‌نگر» است
 *
 * هر خروج پیش از سشن یک نمونهٔ «زنده» تولید می‌کند؛ وقتی بازار بازگشایی شد، قیمت بازگشایی ثبت
 * می‌شود و معلوم می‌کند نگه‌داشتن پوزیشن سود می‌داد یا زیان. بنابراین تصمیم «چقدر زودتر ببندیم»
 * هرگز حبس در یک عدد ثابت نمی‌شود و با گذشت زمان دقیق‌تر می‌شود.
 */
object SessionExitLearning {

    /** یک مشاهدهٔ واقعی: با این زمان سربار خارج شدیم و این مقدار دلار نجات/از‌دست رفت. */
    data class Observation(
        val assetClassCode: String,
        val leadMinutes: Int,
        /** مثبت = خروج زودتر بهتر بود (پول نجات یافت) · منفی = نگه‌داشتن بهتر بود. */
        val savedUsd: Double,
        val atMs: Long,
        val symbol: String = "",
    )

    /** نتیجهٔ تخمین برای یک دستهٔ دارایی. */
    data class Estimate(
        val assetClassCode: String,
        val labelFa: String,
        val leadMinutes: Int,
        val samples: Int,
        val savedUsd: Double,
        val hitRatePct: Double,
        val confidencePct: Int,
        val noteFa: String,
    )

    /** وزن prior؛ با این تعداد نمونه، مقدار یاد‌گرفته‌شده به‌اندازهٔ پیش‌فرض وزن دارد. */
    private const val PRIOR_WEIGHT = 3.0

    /** جریمهٔ عدم‌قطعیت (دلار) برای بازوهایی که نمونهٔ کم دارند. */
    private const val UNCERTAINTY_USD = 6.0

    /** سقف نمونه‌ها برای هر بازو تا حافظه و محاسبه محدود بماند. */
    const val MAX_OBSERVATIONS = 480

    private fun labels(code: String): String = when (code) {
        AssetClass.CRYPTO.code -> "رمزارز"
        AssetClass.FOREX.code -> "فارکس"
        AssetClass.COMMODITY.code -> "کالا و انرژی"
        AssetClass.STOCK.code -> "سهام و شاخص"
        else -> code
    }

    fun assetClassOf(code: String): AssetClass? =
        AssetClass.values().firstOrNull { it.code.equals(code, ignoreCase = true) }

    /**
     * تخمین بهترین زمان سربار برای یک دستهٔ دارایی.
     *
     * امتیاز هر بازو = میانگین پاداش با کشیدگی به سمت prior:
     * `score(L) = (Σ savedUsd | lead=L + 3 × priorScore) / (n_L + 3)`
     * که `priorScore` امتیاز مشاهده‌شدهٔ زمان پیش‌فرض است (پس اگر داده‌ای برای هیچ بازویی نباشد،
     * همان پیش‌فرض برمی‌گردد). بین بازوها، بیشترین امتیاز برنده است و در تساوی، نزدیک‌ترین به
     * پیش‌فرض انتخاب می‌شود تا رفتار سیستم به‌سادگی تغییر نکند.
     */
    /**
     * گزینه‌های همان دسته: فهرست عمومی + مقدار پیش‌فرضِ همان دسته.
     *
     * این نکته مهم است: چون خروج‌های واقعی با مقدار پیش‌فرض هم انجام می‌شوند (مثلاً ۱۲ دقیقه برای
     * سهام)، آن مقدار باید در فهرست گزینه‌ها باشد، وگرنه داده‌هایش هرگز دیده نمی‌شوند.
     */
    fun candidatesFor(assetClass: AssetClass): List<Int> =
        (SessionExitPlanner.LEAD_CANDIDATES + SessionExitPlanner.defaultLeadMinutes(assetClass))
            .distinct()
            .sorted()

    fun estimate(
        assetClass: AssetClass,
        observations: List<Observation>,
    ): Estimate {
        val prior = SessionExitPlanner.defaultLeadMinutes(assetClass)
        val mine = observations.filter { it.assetClassCode.equals(assetClass.code, ignoreCase = true) }
        // prior = میانگین کل مشاهده‌های همین دسته (نه میانگین بازوی پیش‌فرض) تا یک بازوی دُم‌دار
        // نتواند فقط به‌خاطر هم‌جهت بودن با پیش‌فرض، برنده شود.
        val priorScore = if (mine.isEmpty()) 0.0 else mine.sumOf { it.savedUsd } / mine.size

        /**
         * امتیاز بدبینانه (Lower Confidence Bound): میانگین کشیده‌شده به prior منهای جریمهٔ
         * کم‌بودن نمونه. این همان کاری است که در باندیت‌های تولیدی انجام می‌شود تا یک نمونهٔ
         * خوش‌شانس/بدشانس تنها، پارامتر سیستم را جابه‌جا نکند.
         */
        fun scoreFor(lead: Int): Double {
            val window = mine.filter { it.leadMinutes == lead }
            if (window.isEmpty()) return Double.NEGATIVE_INFINITY
            val sum = window.sumOf { it.savedUsd }
            val shrunk = (sum + PRIOR_WEIGHT * priorScore) / (window.size + PRIOR_WEIGHT)
            return shrunk - UNCERTAINTY_USD / sqrt(window.size.toDouble())
        }

        val candidates = candidatesFor(assetClass)
        val scored = candidates.map { it to scoreFor(it) }.filter { it.second.isFinite() }
        val chosen = when {
            scored.isEmpty() -> prior
            else -> scored
                .sortedWith(
                    compareByDescending<Pair<Int, Double>> { it.second }
                        .thenBy { abs(it.first - prior) }
                )
                .first().first
        }

        val total = mine.size
        val saved = round2(mine.sumOf { it.savedUsd })
        val hits = mine.count { it.savedUsd > 0.0 }
        val hitRate = if (total == 0) 0.0 else round2(hits * 100.0 / total)
        val confidence = min(100, (total * 100) / 40)
        val label = labels(assetClass.code)

        val noteFa = when {
            total == 0 -> "پیش‌فرض $prior دقیقه برای $label — هنوز نمونهٔ واقعی برای یادگیری ثبت نشده است."
            chosen == prior -> "$label: پیش‌فرض $prior دقیقه با $total مشاهده تأیید شد (نرخ موفقیت ${hitRate}%)."
            else -> "$label: زمان سربار از $prior به $chosen دقیقه تغییر کرد بر اساس $total مشاهده."
        }
        return Estimate(
            assetClassCode = assetClass.code,
            labelFa = label,
            leadMinutes = chosen,
            samples = total,
            savedUsd = saved,
            hitRatePct = hitRate,
            confidencePct = confidence,
            noteFa = noteFa,
        )
    }

    /** تخمین برای همهٔ دسته‌ها (برای پنل فارسی). */
    fun estimateAll(observations: List<Observation>): List<Estimate> =
        AssetClass.values().map { estimate(it, observations) }

    /**
     * انتخاب بازوی بعدی برای یک خروج (باندیت ε-greedy).
     *
     * @param explorationPercent درصد جست‌وجو (پیش‌فرض ۱۵٪). صفر = همیشه بهترین گزینهٔ شناخته‌شده.
     * @param randomSample عددی در بازهٔ `[0,1)` که فراخوان می‌دهد (برای تست‌پذیری تزریق می‌شود).
     */
    fun nextLeadMinutes(
        assetClass: AssetClass,
        observations: List<Observation>,
        randomSample: Double = kotlin.random.Random.nextDouble(),
        explorationPercent: Double = 15.0,
    ): Int {
        val best = estimate(assetClass, observations).leadMinutes
        if (randomSample * 100.0 >= explorationPercent) return best
        val candidates = candidatesFor(assetClass).filter { it != best }
        if (candidates.isEmpty()) return best
        val idx = (randomSample * 10_000).toInt().let { seed ->
            // انتخاب شبه‌تصادفی پایدار بر اساس همان نمونه، بدون نیاز به مولد حالت‌دار.
            (seed xor (observations.size * 31)) % candidates.size
        }
        return candidates[abs(idx)]
    }

    /** افزودن مشاهدهٔ جدید با سقف حافظه (قدیمی‌ترین‌ها حذف می‌شوند). */
    fun appendObservation(
        existing: List<Observation>,
        observation: Observation,
        max: Int = MAX_OBSERVATIONS,
    ): List<Observation> = (existing + observation)
        .sortedByDescending { it.atMs }
        .take(max.coerceAtLeast(1))

    /**
     * محاسبهٔ پاداش از دو قیمت واقعی.
     *
     * @param exitPrice قیمتی که با آن پیش از سشن خارج شدیم.
     * @param reopenPrice اولین قیمت معتبر پس از بازگشایی سشن.
     * @param direction ‎+1 برای خرید و ‎−1 برای فروش.
     * @param positionSize حجم پوزیشن (برای تبدیل به دلار).
     */
    fun savedUsd(exitPrice: Double, reopenPrice: Double, direction: Int, positionSize: Double): Double {
        if (!exitPrice.isFinite() || !reopenPrice.isFinite() || exitPrice <= 0.0 || reopenPrice <= 0.0) return 0.0
        val heldDelta = (reopenPrice - exitPrice) * direction.toDouble() * positionSize
        return round2(-heldDelta)
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0

    /** سقف ایمنی پاداش: یک نمونهٔ گلِیچ‌خورده نباید تخمین را منحرف کند. */
    fun clampReward(value: Double, referenceUsd: Double = 50.0): Double {
        val cap = max(abs(referenceUsd), 1.0)
        return value.coerceIn(-cap * 4.0, cap * 4.0)
    }
}
