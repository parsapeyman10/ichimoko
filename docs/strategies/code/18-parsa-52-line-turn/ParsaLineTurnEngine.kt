package com.aurum.edge.core

import java.util.Locale

/**
 * # استراتژی پارسا — «چرخش خط ۵۲ (Senkou Span B) و چرخش Senkou Span A»
 *
 * ## چه چیزی خراب بود
 * هر نُه شرط با `AND` خالص روی هم سوار شده بودند. هر شرط به‌تنهایی معقول است، اما
 * حاصل‌ضرب نُه احتمال (حتی اگر هر کدام ۶۰٪ باشد) می‌شود ۱٪ — و اندازه‌گیری روی سه سری
 * ۲۵۰۰ کندلی این را نشان داد: **۰ معامله، و هیچ‌وقت بیش از ۸ شرط از ۹ شرط**.
 * یعنی استراتژی به‌طور ساختاری مرده بود، نه اینکه بازار ستاپ نداده باشد.
 *
 * ## چه چیزی جایش آمد
 * همان سه‌لایه‌ای که خودِ موتور برای روش بیس مستند کرده است (رجوع به بلوک «دروازه‌بندی
 * مکتبی بیس» در `IchimokuStrategyEngine`):
 *
 * | لایه | شرط‌ها | قاعده |
 * |---|---|---|
 * | ۱ — هویت روش (وتوی سخت) | فلت ۱۸+ کندلی SSB · چرخش Span A · کراس TK در ۸ کندل | **همه** باید باشند |
 * | ۲ — کیفیت و تأیید | شیب · فاصلهٔ TK · آزادی چیکو · سمت ابر · حرکت شارپ | دست‌کم [MIN_QUALITY_SCORE] از ۵ |
 *
 * چرا این «شل‌کردن» نیست: هویت روش دست‌نخورده است. اگر SSB فلت نباشد، یا Span A
 * نچرخد، یا کراس تازه نباشد، هیچ معامله‌ای باز نمی‌شود — همان چیزی که نام روش می‌گوید.
 * آنچه حذف شد، «هم‌زمانیِ کاملِ پنج تأیید کیفیتی» بود که در هیچ منبعی شرط ورود نیست.
 */
object ParsaLineTurnEngine {

    /** کمینهٔ کندل‌های فلت Senkou Span B (خط دورهٔ ۵۲) پیش از چرخش. */
    const val MIN_FLAT_BARS: Int = 18

    /** مهلت مجاز پس از کراس Tenkan/Kijun. */
    const val MAX_CROSS_AGE_BARS: Int = 8

    /** کمینهٔ شیب Span A برای «چرخش» (بر حسب ATR در هر کندل). */
    const val MIN_SPAN_A_TURN_ATR: Double = 0.025

    /** از پنج تأیید کیفیتی، دست‌کم چند‌تا لازم است. */
    const val MIN_QUALITY_SCORE: Int = 3

    /** یک شرط قابل‌نمایش در گزارش استراتژی. */
    data class Rule(
        val index: Int,
        val titleFa: String,
        val passed: Boolean,
        val detailFa: String,
        /** آیا این شرط از لایهٔ ۱ (هویت روش) است؟ */
        val isCore: Boolean,
    )

    /**
     * نتیجهٔ سنجش کامل روش.
     *
     * @property rules هشت شرط روش (شرط نهم = پلن/R:R را موتور اضافه می‌کند).
     * @property corePassed هر سه شرط هویتی پاس شده‌اند.
     * @property qualityScore تعداد تأییدهای کیفیتی پاس‌شده (۰..۵).
     * @property passed آیا روش اجازهٔ ورود می‌دهد.
     */
    data class Spec(
        val rules: List<Rule>,
        val corePassed: Boolean,
        val qualityScore: Int,
        val passed: Boolean,
    ) {
        val coreRules: List<Rule> get() = rules.filter { it.isCore }
        val failedCoreFa: String
            get() = coreRules.filterNot { it.passed }.joinToString(" · ") { it.titleFa }
        val summaryFa: String
            get() = if (passed) {
                String.format(
                    Locale.US,
                    "✅ روش پارسا کامل است: هویت روش تأیید شد و %d از ۵ تأیید کیفیتی رسید.",
                    qualityScore,
                )
            } else if (!corePassed) {
                "⛔ هویت روش کامل نیست: $failedCoreFa"
            } else {
                String.format(
                    Locale.US,
                    "⏸ تأییدهای کیفیتی کافی نیست: %d از ۵ (حد لازم %d).",
                    qualityScore,
                    MIN_QUALITY_SCORE,
                )
            }
    }

    /** ارقام لاتین → فارسی. متن‌های برنامه فارسی‌اند و «18» در آن‌ها ناهمخوان دیده می‌شود. */
    internal fun faDigits(value: Int): String =
        value.toString().map { ch -> if (ch in '0'..'9') '۰' + (ch - '0') else ch }.joinToString("")

    /**
     * سنجش روش پارسا.
     *
     * همهٔ ورودی‌ها از `IchimokuBarSnapshot` آمده‌اند؛ این تابع خودش هیچ محاسبهٔ ایچیموکویی
     * انجام نمی‌دهد تا در آزمون بتوان هر شرط را تک‌تک روشن/خاموش کرد.
     */
    @Suppress("LongParameterList")
    fun spec(
        flatBars: Int,
        spanAPrevSlopeAtr: Double,
        spanASlopeAtr: Double,
        crossAgeBars: Int,
        slopeOk: Boolean,
        lineSpacingOk: Boolean,
        chikouOpen: Boolean,
        cloudSideOk: Boolean,
        sharpMoveOk: Boolean,
        isLong: Boolean,
        slopeDetailFa: String = "",
        spacingDetailFa: String = "",
        sharpDetailFa: String = "",
    ): Spec {
        val flatOk = flatBars >= MIN_FLAT_BARS
        val spanATurnOk = if (isLong) {
            spanAPrevSlopeAtr <= 0.0 && spanASlopeAtr >= MIN_SPAN_A_TURN_ATR
        } else {
            spanAPrevSlopeAtr >= 0.0 && spanASlopeAtr <= -MIN_SPAN_A_TURN_ATR
        }
        val crossOk = crossAgeBars <= MAX_CROSS_AGE_BARS

        val rules = listOf(
            Rule(
                1,
                "۱. فلت واقعی Senkou Span B (خط دورهٔ ۵۲)",
                flatOk,
                "SSB به‌مدت ${faDigits(flatBars)} کندل بسته تخت؛ حد لازم ${faDigits(MIN_FLAT_BARS)} کندل.",
                isCore = true,
            ),
            Rule(
                2,
                "۲. چرخش Senkou Span A برای ابر آینده",
                spanATurnOk,
                String.format(
                    Locale.US,
                    "شیب قبلی=%+.2f ATR · اکنون=%+.2f ATR (حد چرخش %.3f)",
                    spanAPrevSlopeAtr,
                    spanASlopeAtr,
                    MIN_SPAN_A_TURN_ATR,
                ),
                isCore = true,
            ),
            Rule(
                3,
                "۳. کراس هم‌جهت Tenkan/Kijun در ۸ کندل",
                crossOk,
                "سن کراس: ${if (crossAgeBars == Int.MAX_VALUE) "یافت نشد" else "${faDigits(crossAgeBars)} کندل"}؛ " +
                    "مهلت مجاز ${faDigits(MAX_CROSS_AGE_BARS)} کندل.",
                isCore = true,
            ),
            Rule(
                4,
                "۴. شیب همسو و نبود مخالفت شدید Kijun",
                slopeOk,
                slopeDetailFa.ifBlank { "شیب Tenkan/Kijun/Senkou A سنجیده شد." },
                isCore = false,
            ),
            Rule(
                5,
                "۵. فاصله و گسترش سنجیدهٔ Tenkan/Kijun",
                lineSpacingOk,
                spacingDetailFa.ifBlank { "فاصله و گسترش تنکان/کیجون سنجیده شد." },
                isCore = false,
            ),
            Rule(
                6,
                "۶. آزادی Chikou",
                chikouOpen,
                if (chikouOpen) "چیکو از کندل، TK و ابر در مسیر معامله آزاد است."
                else "چیکو هنوز درگیر ساختار متناظر است.",
                isCore = false,
            ),
            Rule(
                7,
                "۷. کیفیت حرکت شارپ و تأیید حجم/ATR",
                sharpMoveOk,
                sharpDetailFa.ifBlank { "بدنه، حجم و گسترش ATR سنجیده شد." },
                isCore = false,
            ),
            Rule(
                8,
                "۸. سمت قیمت و نبود تضاد تایم‌فریم بالاتر",
                cloudSideOk,
                "قیمت نسبت به Kijun و تایم‌فریم بالاتر با جهت معامله سنجیده شد.",
                isCore = false,
            ),
        )
        // توجه: شرط «۹. پلن نهایی و R:R حداقل ۱:۱٫۵» عمداً این‌جا نیست — به پلن معامله
        // وابسته است که پیش از این تابع ساخته نمی‌شود، و در `IchimokuStrategyEngine`
        // به همین فهرست اضافه می‌شود. اگر این‌جا `true` گزارش می‌شد، شرط نهم به یک
        // تیک بی‌معنا تبدیل می‌شد.

        val corePassed = flatOk && spanATurnOk && crossOk
        val qualityScore = listOf(4 to slopeOk, 5 to lineSpacingOk, 6 to chikouOpen, 7 to sharpMoveOk, 8 to cloudSideOk)
            .count { it.second }
        return Spec(
            rules = rules,
            corePassed = corePassed,
            qualityScore = qualityScore,
            passed = corePassed && qualityScore >= MIN_QUALITY_SCORE,
        )
    }
}
