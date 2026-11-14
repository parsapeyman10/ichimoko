package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import java.util.Locale

/**
 * # دروازهٔ ایمنی ورود خودکار (Entry Safety Gate)
 *
 * قبل از این دروازه، موتور خودکار فقط دو چیز را می‌سنجید: معتبر بودن پلن (`TradePlanGuard`) و
 * داور عصبی (`evaluateReport`). هیچ‌کدام از این‌ها «چند پوزیشن باز است؟»، «امروز چقدر باخته‌ایم؟»،
 * «این ساعت سشن مناسبی است؟» یا «این ماشه چند کندل کهنه است؟» را نمی‌پرسید. نتیجه: ۱۶۰ معاملهٔ
 * پشت‌سرهم با ۰.۵٪ ریسک، بدون یک ترمز، و ۶۹٪ ورودها در بدترین سشن.
 *
 * این دروازه پنج ترمز اضافه می‌کند؛ همهٔ آن‌ها فقط روی مسیر **خودکار** اعمال می‌شوند و هیچ
 * محدودیتی روی معاملهٔ دستی کاربر نمی‌گذارند:
 *
 * ۱. **سقف پوزیشن هم‌زمان** — هم کل حساب و هم هر نماد.
 * ۲. **سقف ضرر روزانه و افت سرمایه** — همان `RiskAndCalendarEngine.evaluateRiskLimits` که تا
 *    امروز فقط در صفحهٔ ژورنال *نمایش* داده می‌شد و هیچ‌جا جلوی معامله را نمی‌گرفت.
 * ۳. **فیلتر سشن** — از سشن‌هایی که داده می‌گوید زیان‌ده بوده‌اند پرهیز می‌کند.
 * ۴. **انقضای ماشه** — سیگنالی که از چند کندل قبل کهنه شده، دیگر معامله نمی‌شود.
 * ۵. **سقف هزینه** — اگر کارمزد و اسپرد رفت‌وبرگشت بیش از نسبت مجاز از بودجهٔ ریسک را بخورد
 *    (مثلاً شاخص DOW در تایم ۵ دقیقه که ۱R چند صدم درصد است)، ورود رد می‌شود.
 */
object EntrySafetyGate {

    /**
     * سقف پوزیشن باز هم‌زمان در کل حساب. **۰ = بدون سقف.**
     *
     * درخواست صریح کاربر: «این سد ایمنی هم بردار» → سقف پوزیشن باز هم‌زمان برداشته شد.
     * منطق پشتش هم درست است: سقف پوزیشن یک *دلیل اضافه برای رد کردن معامله* بود، و قاعدهٔ
     * خودت این است که اگر شرایط استراتژی برقرار شد، معامله باید انجام شود. اگر کاربر خودش
     * عدد بگذارد (۱ تا ۱۲)، همان عدد مثل قبل اعمال می‌شود.
     */
    const val DEFAULT_MAX_CONCURRENT_POSITIONS: Int = 0

    /** سقف پوزیشن باز روی یک نماد. **۰ = بدون سقف** (همان دلیل بالا). */
    const val DEFAULT_MAX_POSITIONS_PER_SYMBOL: Int = 0

    /** بیشینهٔ عمر مجاز ماشه بر حسب کندل؛ بعد از آن سیگنال باطل است. */
    const val DEFAULT_MAX_SIGNAL_AGE_BARS: Int = 3

    /** بیشینهٔ سهم هزینهٔ رفت‌وبرگشت از بودجهٔ ریسک یک معامله. */
    const val DEFAULT_MAX_FEE_TO_RISK_RATIO: Double = 0.25

    /**
     * نمادهایی که در تایم‌فریم‌های پایین مجاز به ورود خودکار نیستند.
     * دادهٔ ۱۶۰ معامله: DOW روی ۵m و ۱۵m با «پیوستگی روند ۱۵٪ · اعتبار شکست ۰٪» ۱۵ معامله و
     * صفر برد داشت؛ ۱R در این تایم‌فریم چند صدم درصد است و زیر هزینهٔ رفت‌وبرگشت می‌ماند.
     */
    val LOW_TIMEFRAME_RESTRICTED_SYMBOLS: Set<String> = setOf("DOW")

    /** تایم‌فریم‌هایی که نمادهای محدود در آن‌ها مجاز نیستند. */
    val LOW_TIMEFRAMES: Set<Interval> = setOf(Interval.M1, Interval.M3, Interval.M5, Interval.M15)

    /**
     * ساعتِ روز هرگز به‌تنهایی مانع ورود نیست.
     *
     * تنها قید زمانی این است که **با معاملهٔ باز داخل بازارِ بسته نرویم**؛ آن با
     * [MarketHours.closedFor] سنجیده می‌شود، نه با ساعت UTC. این مجموعه برای سازگاری با
     * فراخوانی‌های موجود نگه داشته شده و همیشه خالی است.
     */
    val DEFAULT_BLOCKED_SESSIONS_UTC_HOURS: Set<Int> = emptySet()

    /** برچسب فارسی سشن بر اساس ساعت UTC — فقط برای نمایش، نه برای فیلترکردن. */
    fun sessionLabelFa(utcHour: Int): String = when (utcHour) {
        in 12..15 -> "هم‌پوشانی لندن–نیویورک"
        in 7..11 -> "لندن"
        in 16..20 -> "نیویورک"
        else -> "آسیا"
    }

    fun utcHourOf(nowMillis: Long): Int = (((nowMillis / 3_600_000L) % 24L) + 24L).toInt() % 24

    /** عمر مجاز ماشه بر حسب میلی‌ثانیه. */
    fun signalMaxAgeMillis(
        interval: Interval,
        maxAgeBars: Int = DEFAULT_MAX_SIGNAL_AGE_BARS,
    ): Long = interval.millis.coerceAtLeast(60_000L) * maxAgeBars.coerceAtLeast(1).toLong()

    /**
     * آیا ماشهٔ یک سیگنال کهنه شده است.
     *
     * @param signalBarTime زمان کندل ماشه؛ صفر/منفی یعنی «نامشخص» و کهنگی سنجیده نمی‌شود.
     */
    fun isSignalStale(
        signalBarTime: Long?,
        interval: Interval,
        nowMillis: Long,
        maxAgeBars: Int = DEFAULT_MAX_SIGNAL_AGE_BARS,
    ): Boolean {
        val barTime = signalBarTime ?: return false
        if (barTime <= 0L) return false
        val age = nowMillis - barTime
        if (age < 0L) return false
        return age > signalMaxAgeMillis(interval, maxAgeBars)
    }

    data class Limits(
        val maxConcurrentPositions: Int = DEFAULT_MAX_CONCURRENT_POSITIONS,
        val maxPositionsPerSymbol: Int = DEFAULT_MAX_POSITIONS_PER_SYMBOL,
        val maxSignalAgeBars: Int = DEFAULT_MAX_SIGNAL_AGE_BARS,
        val maxFeeToRiskRatio: Double = DEFAULT_MAX_FEE_TO_RISK_RATIO,
        /** خالی است؛ فقط بازارِ بسته جلوی ورود را می‌گیرد، نه ساعتِ روز. */
        val blockedSessionsUtcHours: Set<Int> = DEFAULT_BLOCKED_SESSIONS_UTC_HOURS,
        val restrictedSymbolsLowTimeframe: Boolean = true,
    )

    data class Decision(
        val allowed: Boolean,
        val reasonsFa: List<String>,
        val summaryFa: String,
    ) {
        companion object {
            val ALLOW = Decision(
                allowed = true,
                reasonsFa = emptyList(),
                summaryFa = "🟢 دروازهٔ ایمنی ورود: مجاز",
            )
        }
    }

    /**
     * داوری ورود خودکار.
     *
     * @param openTrades همهٔ معاملات (باز و بسته)؛ فقط بازها شمرده می‌شوند.
     * @param riskStatus وضعیت سقف ضرر روزانه/افت سرمایه؛ `null` یعنی سنجیده نشود.
     * @param proposedRiskUsd بودجهٔ ریسک پیشنهادی این معامله (همان `riskBudgetUsd`).
     * @param roundTripFeeUsd مجموع کارمزد و اسپرد رفت‌وبرگشت این معامله.
     */
    fun evaluate(
        symbol: String,
        interval: Interval,
        openTrades: List<PaperTrade>,
        nowMillis: Long,
        signalBarTime: Long?,
        proposedRiskUsd: Double,
        roundTripFeeUsd: Double,
        riskStatus: RiskLimitStatus?,
        limits: Limits = Limits(),
        enforceSession: Boolean = true,
        /**
         * شناسهٔ استراتژی‌ای که می‌خواهد وارد شود (خالی = ندانیم و این قاعده سنجیده نشود).
         *
         * قاعدهٔ کاربر: «سقف یک پوزیشن روی هر نماد فقط وقتی برداشته می‌شود که استراتژی
         * یکسان نباشد.» یعنی روی یک نماد می‌توان چند پوزیشن داشت، ولی **هر استراتژی روی هر
         * نماد فقط یک پوزیشن باز**. اگر همان استراتژی دوباره همان نماد را سیگنال داد، پوزیشن
         * دوم تکرار همان ریسک است، نه تنوع.
         */
        strategyId: String = "",
    ): Decision {
        val norm = V1Universe.normalize(symbol)
        val open = openTrades.filter { it.isOpen }
        val reasons = mutableListOf<String>()

        // ۱. سقف پوزیشن هم‌زمان — ۰ یعنی هیچ سقفی نیست و هیچ دلیلی هم ساخته نمی‌شود.
        if (limits.maxConcurrentPositions > 0 && open.size >= limits.maxConcurrentPositions) {
            reasons += "سقف پوزیشن باز هم‌زمان پر است (${open.size}/${limits.maxConcurrentPositions})"
        }
        val sameSymbol = open.count { V1Universe.normalize(it.symbol) == norm }
        if (limits.maxPositionsPerSymbol > 0 && sameSymbol >= limits.maxPositionsPerSymbol) {
            reasons += "$norm همین حالا $sameSymbol پوزیشن باز دارد (سقف ${limits.maxPositionsPerSymbol})"
        }
        // قاعدهٔ «هر استراتژی، روی هر نماد، یک پوزیشن باز».
        if (strategyId.isNotBlank()) {
            val sameStrategy = open.count {
                V1Universe.normalize(it.symbol) == norm && it.strategyId.trim() == strategyId.trim()
            }
            if (sameStrategy > 0) {
                val title = BitcoinStrategyModel.fromId(strategyId).titleFa
                reasons += "همین استراتژی ($title) روی $norm پوزیشن باز دارد؛ " +
                    "پوزیشن دوم همان استراتژی روی همان نماد تکرار همان ریسک است (سقف یک پوزیشن به‌ازای هر استراتژی روی هر نماد)"
            }
        }

        // ۲. سقف ضرر روزانه و افت سرمایه
        if (riskStatus != null) {
            if (riskStatus.isDailyLimitBreached) {
                reasons += "سقف ضرر روزانه پر شده است (${money(riskStatus.dailyLossLimitUsd)})"
            }
            if (riskStatus.isOverallDrawdownBreached) {
                reasons += "سقف افت سرمایه پر شده است (${money(riskStatus.maxDrawdownLimitUsd)})"
            }
            if (proposedRiskUsd > riskStatus.remainingDailyRiskUsd + 0.01) {
                reasons += "بودجهٔ ریسک روزانه برای این معامله کافی نیست " +
                    "(نیاز ${money(proposedRiskUsd)} · بازمانده ${money(riskStatus.remainingDailyRiskUsd)})"
            }
            if (proposedRiskUsd > riskStatus.remainingDrawdownUsd + 0.01) {
                reasons += "بودجهٔ افت سرمایه برای این معامله کافی نیست " +
                    "(نیاز ${money(proposedRiskUsd)} · بازمانده ${money(riskStatus.remainingDrawdownUsd)})"
            }
        }

        // ۳. بازار بسته: هرگز با معاملهٔ باز وارد بازارِ تعطیل نمی‌شویم.
        //    این تنها «زمان‌بندی»ای است که باقی مانده است؛ ساعتِ روز به‌تنهایی هیچ ممنوعیتی نمی‌سازد.
        if (enforceSession && MarketHours.closedFor(norm, nowMillis)) {
            reasons += "بازار $norm در این لحظه بسته است (${MarketHours.sessionBadgeFa(norm, nowMillis)})"
        }

        // ۳-ب. نمادهای محدود در تایم‌فریم پایین (شاخص در ۵m/۱۵m زیر هزینهٔ معامله است)
        if (limits.restrictedSymbolsLowTimeframe &&
            norm in LOW_TIMEFRAME_RESTRICTED_SYMBOLS &&
            interval in LOW_TIMEFRAMES
        ) {
            reasons += "$norm در تایم ${interval.label} مجاز به ورود خودکار نیست " +
                "(نوسان کمتر از هزینهٔ رفت‌وبرگشت؛ فقط تایم‌فریم‌های بالاتر)"
        }

        // ۴. انقضای ماشه
        if (isSignalStale(signalBarTime, interval, nowMillis, limits.maxSignalAgeBars)) {
            val barTime = signalBarTime ?: 0L
            val ageMin = ((nowMillis - barTime) / 60_000.0)
            reasons += "ماشه کهنه شده است (${String.format(Locale.US, "%.0f", ageMin)} دقیقه؛ " +
                "سقف ${limits.maxSignalAgeBars} کندل ${interval.label})"
        }

        // ۵. سقف هزینه نسبت به ریسک
        if (proposedRiskUsd > 0.0 && roundTripFeeUsd > 0.0) {
            val ratio = roundTripFeeUsd / proposedRiskUsd
            if (ratio > limits.maxFeeToRiskRatio) {
                reasons += String.format(
                    Locale.US,
                    "هزینهٔ رفت‌وبرگشت %.0f%% از بودجهٔ ریسک است (سقف %.0f%%)",
                    ratio * 100.0,
                    limits.maxFeeToRiskRatio * 100.0,
                )
            }
        }

        return if (reasons.isEmpty()) {
            Decision.ALLOW
        } else {
            Decision(
                allowed = false,
                reasonsFa = reasons,
                summaryFa = "🚧 دروازهٔ ایمنی ورود مسدود کرد: " + reasons.joinToString(" · "),
            )
        }
    }

    private fun money(v: Double): String = String.format(Locale.US, "$%.2f", v)
}
