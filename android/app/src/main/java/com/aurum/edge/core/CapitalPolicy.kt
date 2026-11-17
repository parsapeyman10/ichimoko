package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.round

/**
 * «مدیریت سرمایه» یک تعریف دارد و همین‌جاست.
 *
 * پیش از این، سه جای مختلف (SettingsStore.update، AurumViewModel.saveRiskAndCapitalSettings و
 * خودِ صفحهٔ تنظیمات) هرکدام جداگانه عددها را می‌بریدند و سقف‌هایشان هم یکی نبود: مثلاً UI عدد
 * ۳۰٪ را نشان می‌داد ولی SettingsStore بی‌صدا آن را ۲۵٪ ذخیره می‌کرد، یا ریسک ثابت ۰٫۴ دلار
 * فقط در ذخیره‌سازی به ۵ دلار پرت می‌شد. این فایل تنها مرجع است: هر عددی که از کاربر می‌آید از
 * [CapitalPolicy.sanitized] عبور می‌کند، هر اصلاحی هم به‌صورت یک [CapitalFinding] **گفته** می‌شود
 * (هیچ‌چیز بی‌صدا عوض نمی‌شود)، و هر هشدارِ «این تنظیم خطرناک است» از [CapitalPolicy.findings]
 * می‌آید.
 */

/** شدت یک یافتهٔ مدیریت سرمایه. */
enum class CapitalSeverity { INFO, WARNING, DANGER }

/**
 * یک یافته: یا اصلاحی که روی عدد کاربر انجام شد، یا هشداری دربارهٔ همان تنظیمات.
 * [code] پایدار و ماشین‌خوان است (برای تست و لاگ)، [messageFa] همان چیزی است که کاربر می‌خواند.
 */
data class CapitalFinding(
    val code: String,
    val severity: CapitalSeverity,
    val messageFa: String,
)

/** خروجی پاک‌سازی: سیاستِ نهایی + فهرست اصلاح‌هایی که انجام شد. */
data class CapitalSanitization(
    val policy: CapitalPolicy,
    val fixes: List<CapitalFinding>,
) {
    val changed: Boolean get() = fixes.isNotEmpty()
}

/**
 * همهٔ ورودی‌های «مدیریت سرمایه» در یک شیء تغییرناپذیر.
 *
 * ریسک هر معامله فقط از یک‌جا خوانده می‌شود: [riskBudgetUsd]. نه UI و نه موتور معامله اجازه
 * ندارند خودشان دوباره «درصد × موجودی» را حساب کنند، چون دیر یا زود دو عدد ناهمخوان می‌شود.
 */
data class CapitalPolicy(
    val accountBalance: Double = 100_000.0,
    val initialCapital: Double = 100_000.0,
    val targetCapital: Double = 100_000.0,
    val riskPercent: Double = 0.5,
    val useFixedDollarRisk: Boolean = true,
    val fixedRiskUsd: Double = 5.0,
    val dailyLossLimitPercent: Double = 5.0,
    val maxDrawdownLimitPercent: Double = 15.0,
    val maxOpenTrades: Int = 0,
    val maxOpenTradesPerSymbol: Int = 0,
) {
    /**
     * مبلغ ریسک هر معامله. همین یک عدد ملاک است و **همیشه** «مبلغ ثابت دلاری» است.
     *
     * چرا درصد نمی‌تواند آن را کوچک کند؟ چون خواستهٔ صریح کاربر این است که زیان هر معامله
     * دقیقاً همان مبلغی باشد که خودش تعیین کرده (پیش‌فرض `$5.00`)؛ در غیر این صورت روی حساب
     * کوچک، همان تنظیم «$5» بی‌صدا به چند سنت تبدیل می‌شد و کاربر عدد دیگری روی معامله
     * می‌دید. درصد موجودی فقط زمانی به کار می‌رود که هیچ مبلغ ثابت معتبری تنظیم نشده باشد.
     */
    val riskBudgetUsd: Double
        get() = if (fixedRiskUsd.isFinite() && fixedRiskUsd > 0.0) {
            fixedRiskUsd
        } else {
            accountBalance * (riskPercent / 100.0)
        }

    /** ریسک هر معامله به‌صورت درصد موجودی — برای هشدار «بیش از ۲٪». */
    val riskBudgetPctOfEquity: Double
        get() = if (accountBalance > 1e-9) riskBudgetUsd / accountBalance * 100.0 else 0.0

    /** کف دلاری سود هر معامله: ۱٫۵R. */
    val rewardFloorUsd: Double get() = riskBudgetUsd * MIN_RR

    /** سقف دلاری سود هر معامله: ۳R. */
    val rewardCeilingUsd: Double get() = riskBudgetUsd * MAX_RR

    val dailyLossLimitUsd: Double get() = accountBalance * (dailyLossLimitPercent / 100.0)

    val maxDrawdownLimitUsd: Double get() = accountBalance * (maxDrawdownLimitPercent / 100.0)

    /** چند باخت پشت‌سرهم تا پر شدن ترمز روزانه. */
    val lossesUntilDailyBrake: Double
        get() = if (riskBudgetUsd > 1e-9) dailyLossLimitUsd / riskBudgetUsd else 0.0

    /** «۱۰۰٪» یعنی ترمز روزانه عملاً خاموش است. */
    val isDailyBrakeOff: Boolean get() = dailyLossLimitPercent >= 99.95

    /** چه نسبتی از موجودی در یک لحظه در خطر است (مجموع ریسک پوزیشن‌های هم‌زمان). */
    val openRiskUsd: Double
        get() = if (maxOpenTrades > 0) riskBudgetUsd * maxOpenTrades else 0.0

    /**
     * سیاستِ پاک‌شده: هر عددی که بیرون از دامنهٔ مجاز باشد به نزدیک‌ترین مقدار سالم برده
     * می‌شود و **دلیلش** هم برگردانده می‌شود تا UI بتواند بگوید «چه چیزی را چرا عوض کردم».
     */
    fun sanitized(): CapitalSanitization {
        val fixes = ArrayList<CapitalFinding>()

        var balance = accountBalance
        if (!balance.isFinite()) {
            fixes += CapitalFinding(
                "balance_not_finite",
                CapitalSeverity.WARNING,
                "موجودی حساب عدد معتبر نبود (NaN/بینهایت)؛ روی $100,000 تنظیم شد.",
            )
            balance = 100_000.0
        } else if (balance < MIN_BALANCE) {
            fixes += CapitalFinding(
                "balance_below_min",
                CapitalSeverity.WARNING,
                "موجودی حساب کمتر از حداقل $" + money(MIN_BALANCE) + " بود؛ روی $" + money(MIN_BALANCE) + " تنظیم شد.",
            )
            balance = MIN_BALANCE
        }

        var initial = initialCapital
        if (!initial.isFinite() || initial < MIN_BALANCE) {
            val next = balance.coerceAtLeast(MIN_BALANCE)
            fixes += CapitalFinding(
                "initial_capital_invalid",
                CapitalSeverity.INFO,
                "سرمایهٔ اولیه معتبر نبود؛ برابر موجودی فعلی ($" + money(next) + ") گذاشته شد.",
            )
            initial = next
        }

        var target = targetCapital
        if (!target.isFinite() || target < MIN_TARGET_CAPITAL) {
            fixes += CapitalFinding(
                "target_capital_invalid",
                CapitalSeverity.INFO,
                "سرمایهٔ هدف باید دست‌کم $" + money(MIN_TARGET_CAPITAL) + " باشد؛ روی $100,000 تنظیم شد.",
            )
            target = 100_000.0
        }

        var riskPct = riskPercent
        if (!riskPct.isFinite()) {
            fixes += CapitalFinding(
                "risk_percent_invalid",
                CapitalSeverity.WARNING,
                "درصد ریسک معتبر نبود؛ روی " + num(MIN_RISK_PERCENT) + "٪ تنظیم شد.",
            )
            riskPct = MIN_RISK_PERCENT
        } else if (riskPct < MIN_RISK_PERCENT || riskPct > MAX_RISK_PERCENT) {
            val next = riskPct.coerceIn(MIN_RISK_PERCENT, MAX_RISK_PERCENT)
            fixes += CapitalFinding(
                "risk_percent_out_of_range",
                CapitalSeverity.WARNING,
                "درصد ریسک باید بین " + num(MIN_RISK_PERCENT) + "٪ و " + num(MAX_RISK_PERCENT) +
                    "٪ باشد؛ از " + num(riskPct) + "٪ به " + num(next) + "٪ اصلاح شد.",
            )
            riskPct = next
        }

        // سقف ریسک ثابت: هرگز بیشتر از نیمی از موجودی در یک معامله؛ وگرنه یک باخت حساب را
        // عملاً صفر می‌کند و همان «۵ دلار» کاربر روی حساب کوچک بی‌معنا می‌شود.
        val maxFixedRisk = maxOf(MIN_FIXED_RISK, balance * MAX_FIXED_RISK_OF_EQUITY)
        var fixedRisk = fixedRiskUsd
        if (!fixedRisk.isFinite()) {
            fixes += CapitalFinding(
                "fixed_risk_invalid",
                CapitalSeverity.WARNING,
                "مبلغ ریسک ثابت معتبر نبود؛ روی $" + money(MIN_FIXED_RISK) + " تنظیم شد.",
            )
            fixedRisk = MIN_FIXED_RISK
        } else if (fixedRisk < MIN_FIXED_RISK) {
            fixes += CapitalFinding(
                "fixed_risk_below_min",
                CapitalSeverity.WARNING,
                "کمترین مبلغ ریسک $" + money(MIN_FIXED_RISK) + " است؛ از $" + money(fixedRisk) +
                    " به $" + money(MIN_FIXED_RISK) + " اصلاح شد.",
            )
            fixedRisk = MIN_FIXED_RISK
        } else if (fixedRisk > maxFixedRisk) {
            fixes += CapitalFinding(
                "fixed_risk_above_cap",
                CapitalSeverity.WARNING,
                "ریسک $" + money(fixedRisk) + " بیش از نیمی از موجودی ($" + money(maxFixedRisk) +
                    ") بود؛ به $" + money(maxFixedRisk) + " محدود شد.",
            )
            // پایین‌گرد: سقف هرگز نباید با رُند کردن یک صدم بالا برود.
            fixedRisk = kotlin.math.floor(maxFixedRisk * 100.0) / 100.0
        }

        var daily = dailyLossLimitPercent
        if (!daily.isFinite()) {
            fixes += CapitalFinding(
                "daily_limit_invalid",
                CapitalSeverity.INFO,
                "سقف ضرر روزانه معتبر نبود؛ روی ۵٪ تنظیم شد.",
            )
            daily = 5.0
        } else if (daily < MIN_DAILY_LIMIT_PERCENT || daily > MAX_DAILY_LIMIT_PERCENT) {
            val next = daily.coerceIn(MIN_DAILY_LIMIT_PERCENT, MAX_DAILY_LIMIT_PERCENT)
            fixes += CapitalFinding(
                "daily_limit_out_of_range",
                CapitalSeverity.INFO,
                "سقف ضرر روزانه باید بین " + num(MIN_DAILY_LIMIT_PERCENT) + "٪ و " +
                    num(MAX_DAILY_LIMIT_PERCENT) + "٪ باشد؛ از " + num(daily) + "٪ به " + num(next) + "٪ اصلاح شد.",
            )
            daily = next
        }

        var drawdown = maxDrawdownLimitPercent
        if (!drawdown.isFinite()) {
            fixes += CapitalFinding(
                "drawdown_limit_invalid",
                CapitalSeverity.INFO,
                "سقف افت سرمایه معتبر نبود؛ روی ۱۵٪ تنظیم شد.",
            )
            drawdown = 15.0
        } else if (drawdown < MIN_DRAWDOWN_LIMIT_PERCENT || drawdown > MAX_DRAWDOWN_LIMIT_PERCENT) {
            val next = drawdown.coerceIn(MIN_DRAWDOWN_LIMIT_PERCENT, MAX_DRAWDOWN_LIMIT_PERCENT)
            fixes += CapitalFinding(
                "drawdown_limit_out_of_range",
                CapitalSeverity.INFO,
                "سقف افت سرمایه باید بین " + num(MIN_DRAWDOWN_LIMIT_PERCENT) + "٪ و " +
                    num(MAX_DRAWDOWN_LIMIT_PERCENT) + "٪ باشد؛ از " + num(drawdown) + "٪ به " + num(next) + "٪ اصلاح شد.",
            )
            drawdown = next
        }

        val maxOpen = maxOpenTrades.coerceIn(0, MAX_OPEN_TRADES)
        val maxPerSymbol = maxOpenTradesPerSymbol.coerceIn(0, MAX_OPEN_TRADES_PER_SYMBOL)

        val policy = CapitalPolicy(
            accountBalance = balance,
            initialCapital = initial,
            targetCapital = target,
            riskPercent = riskPct,
            useFixedDollarRisk = useFixedDollarRisk,
            fixedRiskUsd = fixedRisk,
            dailyLossLimitPercent = daily,
            maxDrawdownLimitPercent = drawdown,
            maxOpenTrades = maxOpen,
            maxOpenTradesPerSymbol = maxPerSymbol,
        )
        return CapitalSanitization(policy = policy, fixes = fixes)
    }

    /**
     * هشدارهای «این تنظیمات خطرناک است». خالی بودن فهرست یعنی هیچ ایرادی پیدا نشد.
     * این‌ها هیچ عددی را عوض نمی‌کنند — تصمیم با کاربر است، ولی هیچ‌چیز هم پنهان نمی‌ماند.
     */
    fun findings(): List<CapitalFinding> {
        val out = ArrayList<CapitalFinding>()
        val budget = riskBudgetUsd
        val riskPct = riskBudgetPctOfEquity

        if (useFixedDollarRisk) {
            val percentMode = accountBalance * (riskPercent / 100.0)
            if (abs(percentMode - budget) > 0.5) {
                out += CapitalFinding(
                    "percent_mode_inert",
                    CapitalSeverity.INFO,
                    "حالت ریسک ثابت روشن است: درصد " + num(riskPercent) + "٪ (معادل $" + money(percentMode) +
                        ") اعمال نمی‌شود؛ هر معامله $" + money(budget) + " ریسک می‌کند.",
                )
            }
        }

        if (riskPct >= 5.0) {
            out += CapitalFinding(
                "risk_above_five_pct",
                CapitalSeverity.DANGER,
                "ریسک هر معامله $" + money(budget) + " معادل " + num(riskPct) + "٪ موجودی است؛ با ۲۰ باخت پشت‌سرهم کل حساب از دست می‌رود.",
            )
        } else if (riskPct > 2.0) {
            out += CapitalFinding(
                "risk_above_two_pct",
                CapitalSeverity.WARNING,
                "ریسک هر معامله $" + money(budget) + " معادل " + num(riskPct) + "٪ موجودی است (سقف توصیه‌شده ۲٪).",
            )
        }

        if (isDailyBrakeOff) {
            out += CapitalFinding(
                "daily_brake_off",
                CapitalSeverity.INFO,
                "ترمز ضرر روزانه خاموش است (۱۰۰٪ موجودی)؛ هیچ روزی به‌خاطر ضرر متوقف نمی‌شود.",
            )
        } else if (lossesUntilDailyBrake < 2.0) {
            out += CapitalFinding(
                "daily_brake_too_tight",
                CapitalSeverity.DANGER,
                "سقف ضرر روزانه (" + num(dailyLossLimitPercent) + "٪ = $" + money(dailyLossLimitUsd) +
                    ") با " + num(lossesUntilDailyBrake) + " باخت پر می‌شود؛ دو باخت پشت‌سرهم معاملات روز را قفل می‌کند.",
            )
        } else if (lossesUntilDailyBrake < 3.0) {
            out += CapitalFinding(
                "daily_brake_tight",
                CapitalSeverity.WARNING,
                "سقف ضرر روزانه فقط جای " + num(lossesUntilDailyBrake) + " باخت دارد (هر باخت $" + money(budget) + ").",
            )
        }

        if (maxOpenTrades > 0 && openRiskUsd > dailyLossLimitUsd + 0.01) {
            out += CapitalFinding(
                "open_risk_above_daily_limit",
                CapitalSeverity.WARNING,
                "سقف " + maxOpenTrades + " پوزیشن هم‌زمان × $" + money(budget) + " = $" + money(openRiskUsd) +
                    " از سقف ضرر روزانه ($" + money(dailyLossLimitUsd) + ") بیشتر است.",
            )
        }

        if (targetCapital < accountBalance) {
            out += CapitalFinding(
                "target_below_balance",
                CapitalSeverity.INFO,
                "سرمایهٔ هدف ($" + money(targetCapital) + ") کمتر از موجودی فعلی ($" + money(accountBalance) + ") است.",
            )
        }

        if (rewardFloorUsd <= 0.0) {
            out += CapitalFinding(
                "zero_budget",
                CapitalSeverity.DANGER,
                "مبلغ ریسک صفر است؛ هیچ معامله‌ای با ریسک صفر باز نمی‌شود.",
            )
        }

        return out
    }

    companion object {
        /** کف و سقف نسبت پاداش به ریسک — همان دالانی که موتور معامله اعمال می‌کند. */
        const val MIN_RR = 1.5
        const val MAX_RR = 3.0

        const val MIN_BALANCE = 10.0
        const val MIN_TARGET_CAPITAL = 100.0
        const val MIN_FIXED_RISK = 0.5
        const val MIN_RISK_PERCENT = 0.1
        const val MAX_RISK_PERCENT = 10.0
        const val MIN_DAILY_LIMIT_PERCENT = 0.5
        const val MAX_DAILY_LIMIT_PERCENT = 100.0
        const val MIN_DRAWDOWN_LIMIT_PERCENT = 1.0
        const val MAX_DRAWDOWN_LIMIT_PERCENT = 100.0
        const val MAX_OPEN_TRADES = 12
        const val MAX_OPEN_TRADES_PER_SYMBOL = 4

        /** سقف ریسک ثابت: نیمی از موجودی. */
        const val MAX_FIXED_RISK_OF_EQUITY = 0.5

        fun from(settings: AppSettings): CapitalPolicy = CapitalPolicy(
            accountBalance = settings.accountBalance,
            initialCapital = settings.initialCapital,
            targetCapital = settings.targetCapital,
            riskPercent = settings.riskPercent,
            useFixedDollarRisk = settings.useFixedDollarRisk,
            fixedRiskUsd = settings.fixedRiskUsd,
            dailyLossLimitPercent = settings.dailyLossLimitPercent,
            maxDrawdownLimitPercent = settings.maxDrawdownLimitPercent,
            maxOpenTrades = settings.maxOpenTrades,
            maxOpenTradesPerSymbol = settings.maxOpenTradesPerSymbol,
        )
    }
}

/** رتبهٔ عددی شدت (برای مرتب‌سازی و انتخاب بدترین). */
/** «۵٫۰۰» با دو رقم اعشار. */
internal fun money(v: Double): String =
    if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%,.2f", v)

/** عدد کوتاه بدون صفرهای اضافی: «۰٫۵»، «۲٫۵»، «۱۰». */
internal fun num(v: Double): String {
    if (!v.isFinite()) return "—"
    val rounded = round(v * 100.0) / 100.0
    return if (abs(rounded - rounded.toLong()) < 1e-9) {
        String.format(java.util.Locale.US, "%,d", rounded.toLong())
    } else {
        String.format(java.util.Locale.US, "%s", rounded.toString().trimEnd('0').trimEnd('.'))
    }
}
