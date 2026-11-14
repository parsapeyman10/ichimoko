package com.aurum.edge.core

/**
 * # ویژگی‌های «وضعیت بازار» برای شبکهٔ عصبی
 *
 * درخواست کاربر: «هوش عصبی‌ام روی برد و باخت‌ها و **تمام حالت‌ها** ترین بشه.»
 *
 * ۱۶ ویژگی قبلی بیشتر «هویت» را می‌دیدند (کلاس دارایی، تایم‌فریم، جهت، کتاب‌های استراتژی)
 * و چند عدد خام (ER، ضخامت ابر، فاصله از کیجون). چیزی که کم بود، **حالت** بود: فاز روند،
 * سشن، رژیم نوسان، نسبت حجم، و فاصلهٔ واقعی R:R که پلن بسته شد. شبکه‌ای که «بازار آرام
 * لندن» و «سشن نیویورک با نوسان بالا» را از هم تشخیص نمی‌دهد، در یکی از آن دو می‌بازد و
 * نمی‌فهمد چرا.
 *
 * این فایل ۸ عدد نرمال‌شده در `[-1, +1]` می‌سازد و به بردار ۱۶تایی **اضافه** می‌کند
 * (ایندکس ۱۶ تا ۲۳). هیچ‌کدام از ۱۶ ویژگی قبلی جابه‌جا نمی‌شود، پس دانش قبلی شبکه معنا
 * را از دست نمی‌دهد.
 *
 * قاعدهٔ طراحی: هر عدد باید **از دادهٔ واقعیِ موجود** محاسبه شود. اگر عددی در دسترس نباشد
 * (`null` یا `-1`)، خروجی صفر است — یعنی «بی‌طرف» — نه یک حدس.
 */
data class NeuralStateInputs(
    /** `GENESIS` / `CORE_EXPANSION` / `LATE_EXHAUSTION` / `CHOP_RANGE` — خالی = نامعلوم. */
    val trendPhaseCode: String = "",
    /** کارایی جهت‌دار کافمن (۰..۱) روی تایم ماشه. */
    val er14: Double? = null,
    /** ATR14 تقسیم بر قیمت، به درصد (مثلاً ۰٫۳۵ یعنی ۰٫۳۵٪). */
    val atrPctOfPrice: Double? = null,
    /** فاصلهٔ قیمت از کیجون بر حسب ATR. */
    val distKijunAtr: Double? = null,
    /** ضخامت ابر بر حسب ATR. */
    val kumoThicknessAtr: Double? = null,
    /** حجم کندل ماشه تقسیم بر میانگین ۲۰ دوره. */
    val volumeRatio20: Double? = null,
    /** R:R واقعیِ پلن (نه هدف). */
    val planRr: Double? = null,
    /** زمان ورود (UTC) برای تعیین سشن. */
    val entryTimeMs: Long = 0L,
) {
    /** کد سشن بر اساس ساعت UTC — همان تقسیم‌بندی که در بررسی سشن‌ها استفاده می‌شد. */
    val sessionCode: String
        get() = NeuralStateFeatures.sessionCodeOf(entryTimeMs)
}

object NeuralStateFeatures {

    /** مقدار «نامعلوم» در فیلدهای `state*` معامله. هرگز با صفر اشتباه نشود. */
    const val UNKNOWN: Double = -1.0

    /** تعداد ویژگی‌های تازه (ایندکس ۱۶ تا ۲۳ در بردار نهایی). */
    const val COUNT: Int = 8

    /** آستانهٔ «نوسان بالا» که در لایهٔ رفتاری هم استفاده می‌شود (R38). */
    private const val HIGH_VOL_ATR_PCT: Double = 0.30

    /**
     * سشن بر اساس ساعت UTC. تعطیلی بازار (شنبه/یکشنبه) با `V1Universe` سنجیده نمی‌شود چون
     * این تابع باید خالص و مستقل از دادهٔ بازار بماند؛ فقط «ساعت» را می‌بیند.
     */
    fun sessionCodeOf(entryTimeMs: Long): String {
        if (entryTimeMs <= 0L) return ""
        val hourUtc = ((entryTimeMs / 3_600_000L) % 24L).toInt()
        return when {
            hourUtc in 0..6 -> "ASIA"
            hourUtc in 7..11 -> "LONDON"
            hourUtc in 12..15 -> "LONDON_NY"
            hourUtc in 16..20 -> "NEW_YORK"
            else -> "LATE_NY"
        }
    }

    private fun bucket(value: Double?, center: Double, halfWidth: Double, unknown: Double = 0.0): Double {
        if (value == null || !value.isFinite() || value < 0.0) return unknown
        return ((value - center) / halfWidth).coerceIn(-1.0, 1.0)
    }

    private fun phaseScore(code: String): Double = when (code.uppercase()) {
        "GENESIS" -> 1.0
        "CORE_EXPANSION" -> 0.55
        "LATE_EXHAUSTION" -> -0.9
        "CHOP_RANGE" -> -1.0
        else -> 0.0
    }

    private fun sessionScore(code: String): Double = when (code) {
        "NEW_YORK" -> 0.9
        "LONDON_NY" -> 0.7
        "LONDON" -> 0.45
        "ASIA" -> -0.55
        "LATE_NY" -> -0.9
        else -> 0.0
    }

    /**
     * بردار ۸تایی در `[-1, +1]`. ترتیب (ثابت و مستند):
     *
     * | # | ویژگی | + یعنی | − یعنی |
     * |---|---|---|---|
     * | ۱۶ | فاز روند | Genesis | اشباع/رِنج |
     * | ۱۷ | ER14 | روند خالص | بازار فرسایشی |
     * | ۱۸ | نوسان (ATR٪) | پرنوسان | آرام |
     * | ۱۹ | فاصله از کیجون | کشیده | چسبیده به تعادل |
     * | ۲۰ | ضخامت ابر | ابر ضخیم | کوموی نازک |
     * | ۲۱ | نسبت حجم | حجم بالا | حجم زیر میانگین |
     * | ۲۲ | سشن | نیویورک | آسیا/پایان روز |
     * | ۲۳ | R:R پلن | سخاوتمندانه | تنگ |
     */
    fun build(input: NeuralStateInputs): List<Double> = listOf(
        phaseScore(input.trendPhaseCode),
        bucket(input.er14, center = 0.35, halfWidth = 0.35),
        bucket(input.atrPctOfPrice, center = HIGH_VOL_ATR_PCT, halfWidth = 0.30),
        bucket(input.distKijunAtr, center = 1.2, halfWidth = 1.2),
        bucket(input.kumoThicknessAtr, center = 0.9, halfWidth = 1.2),
        bucket(input.volumeRatio20, center = 1.0, halfWidth = 0.8),
        sessionScore(input.sessionCode),
        bucket(input.planRr, center = 2.0, halfWidth = 1.0),
    )

    /** توضیح فارسی کوتاه برای امضای شرایط (کلید خوشهٔ تجربه). */
    fun describeFa(input: NeuralStateInputs): String = buildString {
        when (input.trendPhaseCode.uppercase()) {
            "GENESIS" -> append("فاز Genesis")
            "CORE_EXPANSION" -> append("قلب روند")
            "LATE_EXHAUSTION" -> append("اشباع انتهای روند")
            "CHOP_RANGE" -> append("رِنج")
            else -> append("فاز نامعلوم")
        }
        val atr = input.atrPctOfPrice
        if (atr != null && atr >= 0.0) {
            append(if (atr >= HIGH_VOL_ATR_PCT) " · نوسان بالا" else " · نوسان عادی")
        }
        when (input.sessionCode) {
            "ASIA" -> append(" · آسیا")
            "LONDON" -> append(" · لندن")
            "LONDON_NY" -> append(" · هم‌پوشانی لندن/نیویورک")
            "NEW_YORK" -> append(" · نیویورک")
            "LATE_NY" -> append(" · پایان روز")
        }
    }

    /** از گزارش ارزیابی استراتژی در لحظهٔ ورود (منبع دقیق‌ترین حالت بازار). */
    fun fromReport(
        report: StrategyEvaluationReport?,
        planRr: Double?,
        entryTimeMs: Long,
    ): NeuralStateInputs {
        val snap = report?.triggerSnapshot ?: report?.strategySnapshot
        return NeuralStateInputs(
            trendPhaseCode = report?.trendPhase?.name ?: "",
            er14 = snap?.efficiencyRatio14,
            atrPctOfPrice = if (snap != null && snap.close > 0.0) snap.atr14 / snap.close * 100.0 else null,
            distKijunAtr = snap?.distanceToKijunAtr,
            kumoThicknessAtr = if (snap != null && snap.atr14 > 0.0) snap.kumoThickness / snap.atr14 else null,
            volumeRatio20 = snap?.volumeRatio20,
            planRr = planRr?.takeIf { it > 0.0 },
            entryTimeMs = entryTimeMs,
        )
    }

    /** از معاملهٔ ثبت‌شده در ژورنال (فیلدهای `state*` که در لحظهٔ ورود ذخیره شده‌اند). */
    fun fromTrade(trade: PaperTrade): NeuralStateInputs = NeuralStateInputs(
        trendPhaseCode = trade.stateTrendPhaseCode,
        er14 = trade.stateEr14.takeIf { it >= 0.0 },
        atrPctOfPrice = trade.stateAtrPctOfPrice.takeIf { it >= 0.0 },
        distKijunAtr = trade.stateDistKijunAtr.takeIf { it >= 0.0 },
        kumoThicknessAtr = trade.stateKumoThicknessAtr.takeIf { it >= 0.0 },
        volumeRatio20 = trade.stateVolumeRatio20.takeIf { it >= 0.0 },
        planRr = trade.effectiveOptimalRr.takeIf { it > 0.0 },
        entryTimeMs = trade.openedAt,
    )
}
