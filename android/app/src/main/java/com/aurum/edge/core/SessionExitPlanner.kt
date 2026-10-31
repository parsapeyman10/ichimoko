package com.aurum.edge.core

/**
 * برنامه‌ریز «خروج پیش از پایان سشن» — منبع واحد تصمیم‌های زمانی بازار.
 *
 * چرا این ماژول وجود دارد: در نسخهٔ ۳.۹.۲۸ پوزیشنی که بازارش بسته می‌شد با «آخرین قیمت کهنه»
 * بسته می‌شد. این کار غلط بود، چون:
 *  - قیمت آخر مربوط به قبل از بسته شدن است و معامله در شرایط واقعی بازار انجام نمی‌شود؛
 *  - اگر پوزیشن باید بسته شود، باید **پیش از** بسته شدن و در بازار زنده بسته شود؛
 *  - اگر پوزیشن از سشن قبل باقی مانده، باید تا **بازگشایی** صبر کرد و در اولین تیک معتبر خارج شد.
 *
 * بنابراین سه قاعدهٔ روشن داریم:
 *  ۱. هرگز به‌صرف «بسته بودن بازار» معامله بسته نمی‌شود.
 *  ۲. در فاصلهٔ [پایان سشن − زمان سربارِ آموخته‌شده] پوزیشن‌های باز به‌صورت خودکار بسته می‌شوند.
 *  ۳. در همان فاصله ورود جدید ممنوع است (چون معامله‌ای که تا پایان سشن فرصت ندارد، ریسک گپ است).
 *
 * زمان سربار برای هر دستهٔ دارایی از `SessionExitLearning` می‌آید و با مشاهدهٔ واقعی بازار
 * (قیمت خروج در برابر قیمت بازگشایی) به‌روز می‌شود.
 */
object SessionExitPlanner {

    /** سقف زمان سربار: بیش از این مقدار، معامله عملاً فرصت کارکردن ندارد. */
    const val MAX_LEAD_MINUTES: Int = 60

    /** کف زمان سربار؛ صفر یعنی «تا آخرین لحظهٔ سشن بمان» که فقط برای بازار ۲۴ساعته منطقی است. */
    const val MIN_LEAD_MINUTES: Int = 0

    /** گزینه‌های قابل انتخاب برای الگوریتم یادگیری (باندیت). */
    val LEAD_CANDIDATES: List<Int> = listOf(0, 5, 10, 15, 20, 30, 45)

    /**
     * زمان سربار پیش‌فرض هر دستهٔ دارایی (دقیقه) — نقطهٔ شروع بی‌خطر پیش از یادگیری.
     *
     * - سهام/شاخص: ۱۲ دقیقه (نقدشوندگی در دقایق آخر کمرنگ می‌شود و گپ شبانه رایج است)
     * - کالا: ۱۰ دقیقه (استراحت یک‌ساعتهٔ ۱۷:۰۰ تا ۱۸:۰۰ نیویورک)
     * - فارکس: ۱۵ دقیقه پیش از بسته شدن جمعه
     * - رمزارز: ۰ (بازار ۲۴ساعته است و بستن مصنوعی ندارد)
     */
    fun defaultLeadMinutes(assetClass: AssetClass): Int = when (assetClass) {
        AssetClass.STOCK -> 12
        AssetClass.COMMODITY -> 10
        AssetClass.FOREX -> 15
        AssetClass.CRYPTO -> 0
    }

    /** تصمیم کامل یک نماد در یک لحظه. */
    data class Plan(
        val symbol: String,
        val assetClass: AssetClass,
        /** آیا سشن در این لحظه بسته است؟ */
        val closed: Boolean,
        /** دقیقه تا بسته شدن سشن (null = ۲۴ساعته/بسته). */
        val minutesToClose: Long?,
        /** زمان سربارِ در حال استفاده (آموخته‌شده یا پیش‌فرض). */
        val leadMinutes: Int,
        /** دقیقه تا لحظهٔ خروج اجباری = minutesToClose − leadMinutes. */
        val minutesToExit: Long?,
        /** همین حالا باید بسته شود؟ */
        val shouldCloseNow: Boolean,
        /** همین حالا ورود جدید ممنون است؟ */
        val shouldBlockEntry: Boolean,
        /** پوزیشن از سشن قبل باز مانده و باید تا بازگشایی صبر کرد. */
        val carriedOverClosure: Boolean,
        /** برچسب فارسی آمادهٔ نمایش در UI. */
        val badgeFa: String,
    )

    /**
     * محاسبهٔ تصمیم سشن برای یک نماد.
     *
     * @param learnedLeadMinutes زمان سربارِ آموخته‌شده؛ null یعنی از مقدار پیش‌فرض استفاده شود.
     * @param hasOpenPosition آیا روی این نماد پوزیشن باز داریم (برای متن‌های دقیق‌تر).
     * @param carriedOverClosure آیا این پوزیشن از سشن قبل باز مانده است.
     */
    fun plan(
        symbol: String,
        now: Long = System.currentTimeMillis(),
        learnedLeadMinutes: Int? = null,
        hasOpenPosition: Boolean = false,
        carriedOverClosure: Boolean = false,
    ): Plan {
        val assetClass = AssetClass.of(symbol)
        val closed = MarketHours.closedFor(symbol, now)
        val minutesToClose = if (closed) null else MarketHours.minutesToClose(symbol, now)
        val defaultLead = defaultLeadMinutes(assetClass)
        val lead = (learnedLeadMinutes ?: defaultLead).coerceIn(MIN_LEAD_MINUTES, MAX_LEAD_MINUTES)

        if (assetClass == AssetClass.CRYPTO || minutesToClose == null) {
            val badge = if (closed) {
                val window = MarketHours.sessionWindowFor(symbol, now)
                "🔴 بازار بسته · ${window.nextChangeLabel} ${window.newYorkTimeLabel}"
            } else {
                "🟢 بازار ۲۴ ساعته"
            }
            return Plan(
                symbol = symbol,
                assetClass = assetClass,
                closed = closed,
                minutesToClose = null,
                leadMinutes = lead,
                minutesToExit = null,
                shouldCloseNow = false,
                shouldBlockEntry = closed,
                carriedOverClosure = closed && hasOpenPosition,
                badgeFa = badge,
            )
        }

        val minutesToExit = minutesToClose - lead
        val shouldCloseNow = hasOpenPosition && minutesToExit <= 0L
        // ورود تا خود لحظهٔ بسته شدن ممنوع است، اما با کف ۵ دقیقه تا معامله حتی فرصت باز شدن نداشته باشد.
        val shouldBlockEntry = minutesToExit <= 0L || minutesToClose <= maxOf(lead, 5)

        val badgeFa = when {
            minutesToExit <= 0L && hasOpenPosition -> "⏱️ پنجرهٔ خروج پیش از سشن — الان بسته می‌شود"
            minutesToExit <= 0L -> "⛔ ورود به سشن جدید تا بازگشایی بعدی ممنوع"
            minutesToExit <= 10L -> "🟠 $minutesToExit دقیقه تا خروج خودکار (سربار $lead دقیقه)"
            else -> "🟢 ${minutesToClose} دقیقه تا بسته شدن · خروج خودکار در ${minutesToExit} دقیقه"
        }

        return Plan(
            symbol = symbol,
            assetClass = assetClass,
            closed = false,
            minutesToClose = minutesToClose,
            leadMinutes = lead,
            minutesToExit = minutesToExit,
            shouldCloseNow = shouldCloseNow,
            shouldBlockEntry = shouldBlockEntry,
            carriedOverClosure = false,
            badgeFa = badgeFa,
        )
    }

    /**
     * آیا این پوزیشن از سشن قبل باز مانده است؟
     *
     * «بازمانده» یعنی بازار همین حالا بسته است و پوزیشن هم باز است. چنین پوزیشنی نه با قیمت
     * کهنه بسته می‌شود و نه لحظهٔ بازگشایی به‌صورت کورکورانه؛ با **اولین تیک معتبر** پس از
     * بازگشایی و با قیمت واقعی بازار بسته می‌شود.
     */
    fun survivedClosure(symbol: String, now: Long = System.currentTimeMillis()): Boolean =
        MarketHours.closedFor(symbol, now)

    /** متن دلیل خروج، آماده برای ثبت در ژورنال. */
    fun exitReasonFa(plan: Plan, exitPrice: Double): String = String.format(
        java.util.Locale.US,
        "⏱️ خروج خودکار %d دقیقه پیش از پایان سشن %s (قیمت خروج زنده: %,.4f)",
        plan.leadMinutes,
        plan.assetClass.label,
        exitPrice,
    )
}
