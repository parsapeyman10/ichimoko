package com.aurum.edge.core

import java.util.Locale
import kotlin.math.abs

/**
 * تصویر لحظه‌ای از «پول حساب» برای صفحهٔ نخست.
 *
 * پیش از این نسخه، چهار عددِ صفحهٔ نخست (موجودی، اکوئیتی، برآیند ژورنال و مارجین آزاد) از سه مسیر
 * مختلف ساخته می‌شدند و هیچ‌جا با هم تطبیق داده نمی‌شدند؛ نتیجه این بود که کاربر اعدادی می‌دید که
 * جمعشان با هم نمی‌خواند. این کلاس یک «دفتر واحد» می‌سازد که هر عددش از عددهای دیگر قابل بازسازی است.
 *
 * رابطه‌های ثابت:
 * ```
 * اکوئیتی      = موجودی پایه + سود/زیان شناور
 * مارجین آزاد  = اکوئیتی − مارجین درگیر            (می‌تواند منفی شود)
 * موجودی سازگار با ژورنال = سرمایهٔ پایه + سود/زیان تحققیافته
 * اختلاف        = موجودی پایه − موجودی سازگار با ژورنال
 * ```
 */
data class AccountLedgerSnapshot(
    /** `settings.accountBalance` — موجودی‌ای که موتور ریسک و مارجین از آن استفاده می‌کند. */
    val baseBalanceUsd: Double,
    /** `settings.initialCapital` — سرمایه‌ای که ژورنال از آن شروع شده است. */
    val initialCapitalUsd: Double,
    /** مجموع سود/زیان معاملات بسته‌شده (`JournalStats.netPnl`). */
    val realizedNetPnlUsd: Double,
    /** ارزش‌گذاری لحظه‌ای معاملات باز؛ `null` یعنی قیمتی برای ارزش‌گذاری در دسترس نبود. */
    val floatingNetPnlUsd: Double?,
    /** مجموع مارجین قفل‌شدهٔ معاملات باز (نوشنال ÷ اهرم). */
    val marginInUseUsd: Double,
    /** ریسک هر معامله با همان قاعده‌ای که موتور اجرا می‌کند. */
    val riskPerTradeUsd: Double,
    /** آیا ریسک هر معامله با مبلغ ثابت دلاری تعیین می‌شود یا درصدی از موجودی. */
    val usesFixedDollarRisk: Boolean,
    /** تعداد معاملات بازی که قیمت لحظه‌ای نداشته‌اند (ارزش‌گذاری‌شان ناقص است). */
    val unpricedOpenTrades: Int = 0,
) {
    val floatingOrZeroUsd: Double get() = floatingNetPnlUsd ?: 0.0
    val equityUsd: Double get() = baseBalanceUsd + floatingOrZeroUsd
    val freeMarginUsd: Double get() = equityUsd - marginInUseUsd
    /** موجودی‌ای که اگر فقط ژورنال را مبنا بگیریم باید روی حساب باشد. */
    val journalBackedBalanceUsd: Double get() = initialCapitalUsd + realizedNetPnlUsd
    /** اختلاف موجودی دستی با موجودیِ برآمده از ژورنال. */
    val driftUsd: Double get() = baseBalanceUsd - journalBackedBalanceUsd
    val isAligned: Boolean get() = abs(driftUsd) < 0.01
    val isMarginExceeded: Boolean get() = freeMarginUsd < 0.0
    val isFloatingPartial: Boolean get() = unpricedOpenTrades > 0

    fun riskModeFa(): String =
        if (usesFixedDollarRisk) {
            "ریسک ثابت دلاری"
        } else {
            "درصدی از موجودی (${String.format(Locale.US, "%.2f", riskPerTradeUsd / baseBalanceUsd * 100.0)}٪)"
        }

    /** معادلهٔ خوانایی که هر عدد را به عدد بعدی وصل می‌کند. */
    fun equationFa(): String = buildString {
        append("موجودی پایه ")
        append(money(baseBalanceUsd))
        append(" + شناور ")
        append(signed(floatingOrZeroUsd))
        append(" = اکوئیتی ")
        append(money(equityUsd))
        append("  ·  اکوئیتی − مارجین درگیر ")
        append(money(marginInUseUsd))
        append(" = مارجین آزاد ")
        append(money(freeMarginUsd))
    }

    /** توضیح فارسی وضعیت تطبیق حساب. */
    fun alignmentFa(): String = when {
        isAligned -> "✅ حساب با ژورنال هم‌تراز است: موجودی = سرمایهٔ پایه + سود/زیان تحققیافته"
        driftUsd > 0.0 -> "⚠️ موجودی ${money(driftUsd)} بیشتر از چیزی است که ژورنال نشان می‌دهد؛ " +
            "معمولاً از حذف/ویرایش دستی معاملهٔ بسته‌شده یا تغییر دستی موجودی می‌آید"
        else -> "⚠️ موجودی ${money(abs(driftUsd))} کمتر از چیزی است که ژورنال نشان می‌دهد؛ " +
            "معمولاً از وارد کردن سابقه یا تغییر دستی موجودی می‌آید"
    }

    fun floatingNoteFa(): String? =
        if (isFloatingPartial) {
            "قیمت لحظه‌ای برای $unpricedOpenTrades معاملهٔ باز در دسترس نیست؛ سود/زیان شناور مقداری کمتر از واقع نمایش داده می‌شود"
        } else null

    fun marginNoteFa(): String? =
        if (isMarginExceeded) "مارجین درگیر از اکوئیتی بیشتر شده است؛ معاملهٔ جدید باز نکنید" else null

    companion object {
        fun money(value: Double): String =
            (if (value < 0) "−$" else "$") + String.format(Locale.US, "%,.2f", abs(value))

        fun signed(value: Double): String =
            (if (value >= 0) "+" else "−") + String.format(Locale.US, "%,.2f", abs(value))
    }
}

/** ساخت دفتر حساب از ورودی‌های خام؛ در یک نقطه، تا همهٔ صفحه‌ها یک عدد ببینند. */
object AccountReconciliation {

    /**
     * ریسک هر معامله، دقیقاً با همان قاعدهٔ موتور (`RiskAndCalendarEngine.calculatePosition`):
     * اگر «ریسک ثابت دلاری» روشن باشد و مقدار معتبر داشته باشد همان، وگرنه درصدی از موجودی.
     */
    fun riskPerTradeUsd(
        usesFixedDollarRisk: Boolean,
        fixedRiskUsd: Double,
        riskPercent: Double,
        baseBalanceUsd: Double,
    ): Double {
        val fixed = if (usesFixedDollarRisk && fixedRiskUsd.isFinite() && fixedRiskUsd > 0.0) fixedRiskUsd else 0.0
        if (fixed > 0.0) return fixed
        val pct = if (riskPercent.isFinite() && riskPercent > 0.0) riskPercent else 0.0
        val balance = if (baseBalanceUsd.isFinite() && baseBalanceUsd > 0.0) baseBalanceUsd else 0.0
        return balance * pct / 100.0
    }

    fun snapshot(
        baseBalanceUsd: Double,
        initialCapitalUsd: Double,
        realizedNetPnlUsd: Double,
        floatingNetPnlUsd: Double?,
        marginInUseUsd: Double,
        usesFixedDollarRisk: Boolean,
        fixedRiskUsd: Double,
        riskPercent: Double,
        unpricedOpenTrades: Int = 0,
    ): AccountLedgerSnapshot = AccountLedgerSnapshot(
        baseBalanceUsd = baseBalanceUsd.coerceAtLeast(0.0),
        initialCapitalUsd = if (initialCapitalUsd.isFinite() && initialCapitalUsd > 0.0) initialCapitalUsd else baseBalanceUsd,
        realizedNetPnlUsd = if (realizedNetPnlUsd.isFinite()) realizedNetPnlUsd else 0.0,
        floatingNetPnlUsd = floatingNetPnlUsd?.takeIf { it.isFinite() },
        marginInUseUsd = if (marginInUseUsd.isFinite() && marginInUseUsd > 0.0) marginInUseUsd else 0.0,
        riskPerTradeUsd = riskPerTradeUsd(usesFixedDollarRisk, fixedRiskUsd, riskPercent, baseBalanceUsd),
        usesFixedDollarRisk = usesFixedDollarRisk,
        unpricedOpenTrades = unpricedOpenTrades.coerceAtLeast(0),
    )
}
