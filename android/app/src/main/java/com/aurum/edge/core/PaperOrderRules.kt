package com.aurum.edge.core

import kotlin.math.abs
import kotlin.math.floor

/** Pure validation for a two-sided *paper-only* ticket. No venue order is ever sent. */
data class PaperTicket(
    val quantity: Double,
    val unit: String,
    val riskBudgetUsd: Double,
    val actualRiskUsd: Double,
    val notionalUsd: Double,
    val rewardRisk: Double,
    val leverage: Int = 20,
    val marginUsd: Double = 0.0,
    val commissionUsd: Double = 0.0,
    val spreadCostUsd: Double = 0.0,
    /** مرجع واقعی اعداد: کارگزار/صرافی معتبر و سقف قانونی همان بازار. */
    val venue: String = "",
    val venueSource: String = "",
    /** هزینهٔ کل رفت‌وبرگشت (کمیسیون دو سمت + اسپرد) بر حسب bps از ارزش معامله. */
    val costBps: Double = 0.0,
)

object PaperOrderRules {
    /** Quote currencies of USD-base crosses whose quote->USD rate IS the pair's own price. */
    private val USD_CROSS_QUOTES = setOf("JPY", "CHF", "CAD")

    /**
     * سقف اهرم همان بازار، از [VenueSpecs]: جفت‌ارز اصلی ۳۰:۱ · جفت‌ارز غیراصلی و طلا ۲۰:۱ ·
     * سایر کالاها ۱۰:۱ · رمزارز ۲:۱ · سهام ۵:۱ (سقف قانونی ESMA برای مشتری خرده‌فروشی).
     */
    fun defaultLeverageFor(symbol: String): Int = VenueSpecs.of(symbol).leverageCap

    /** Paper-sizing supports the entire universe: Forex, Crypto, Commodities, Stocks, Indices. */
    fun paperable(symbol: String): Boolean {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        if (s.isBlank() || s.contains("IRT") || s.contains("IRR")) return false
        return s.matches(Regex("[A-Z0-9]{2,12}(/[A-Z0-9]{2,12})?"))
    }

    fun unitFor(symbol: String): String {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        return when {
            s.startsWith("XAU") || s.startsWith("XAG") -> "oz"
            s.startsWith("BRENT") || s.startsWith("WTI") || s.startsWith("USOIL") || s.startsWith("UKOIL") -> "bbl"
            s.startsWith("NATGAS") -> "mmbtu"
            s.startsWith("COPPER") -> "lbs"
            s.endsWith("USDT") || s.startsWith("BTC") || s.startsWith("ETH") || s.startsWith("SOL") || s.startsWith("TON") -> "coins"
            s.contains("/") -> s.substringBefore('/').take(8).ifBlank { "units" }
            else -> "shares"
        }
    }

    /** Quote-currency P/L to USD: direct for /USD or USDT quotes; USD/XXX converts via its own price. */
    fun quotePnlToUsd(symbol: String, pnlInQuote: Double, price: Double): Double {
        val s = symbol.trim().uppercase(java.util.Locale.ROOT)
        return if ((s.startsWith("USD/") || s.startsWith("USD")) && USD_CROSS_QUOTES.any { s.endsWith(it) }) {
            if (price > 0.0) pnlInQuote / price else pnlInQuote
        } else {
            pnlInQuote
        }
    }

    fun preview(
        side: SignalAction,
        symbol: String,
        entry: Double,
        stop: Double,
        target: Double,
        balance: Double,
        riskPercent: Double,
    ): PaperTicket {
        require(side != SignalAction.NO_TRADE) { "جهت لانگ یا شورت را انتخاب کنید" }
        require(paperable(symbol)) {
            "نماد معاملاتی برای معاملهٔ آزمایشی معتبر نیست"
        }
        require(entry.isFinite() && stop.isFinite() && target.isFinite() &&
            entry > 0.0 && stop > 0.0 && target > 0.0) { "قیمت یا حد ضرر/سود معتبر نیست" }
        require(balance.isFinite() && balance >= 10.0 && riskPercent.isFinite() && riskPercent in 0.1..5.0) {
            "موجودی یا درصد ریسک معتبر نیست (حداکثر ۵٪)"
        }
        require((side == SignalAction.BUY && stop < entry && target > entry) ||
            (side == SignalAction.SELL && stop > entry && target < entry)) {
            "لانگ: SL زیر ورود و TP بالای آن؛ شورت: SL بالای ورود و TP پایین آن باشد"
        }
        val spec = VenueSpecs.of(symbol)
        val distance = abs(entry - stop)
        require(distance / entry in 0.0001..0.25) { "فاصلهٔ استاپ باید متناسب با ساختار قیمت بازار باشد" }
        require(distance >= spec.minStopDistance(entry)) {
            "حد ضرر داخل اسپرد/نویز بازار است؛ حداقل فاصلهٔ مجاز روی ${symbol} برابر " +
                String.format(java.util.Locale.US, "%.6f", spec.minStopDistance(entry)) + " است"
        }
        val rr = abs(target - entry) / distance
        require(rr.isFinite() && rr >= 1.2) { "نسبت سود به زیان باید حداقل ۱٫۲ باشد" }
        val budget = balance * riskPercent / 100.0
        val isUsdBase = (symbol.startsWith("USD/") || symbol.startsWith("USD")) && USD_CROSS_QUOTES.any { symbol.endsWith(it) }
        val quantity = floor(
            (if (!isUsdBase) budget / distance else budget * entry / distance) * 1_000_000.0) / 1_000_000.0
        require(quantity.isFinite() && quantity >= 0.000001) { "حجم با بودجهٔ ریسک فعلی بسیار کوچک است" }
        val actualRisk = if (!isUsdBase) quantity * distance else quantity * distance / entry
        val notional = if (!isUsdBase) quantity * entry else quantity
        require(actualRisk.isFinite() && actualRisk <= budget + 1e-4) {
            "ریسک پوزیشن از بودجه تعیین‌شده فراتر می‌رود"
        }
        val leverage = spec.leverageCap
        val margin = kotlin.math.round((notional / leverage) * 100.0) / 100.0
        // قانون واقعی کارگزار: بدون مارجین کافی، پوزیشن باز نمی‌شود (و ESMA در ۵۰٪ مارجین
        // پوزیشن را می‌بندد). اینجا فقط شرط بازکردن بررسی می‌شود.
        require(margin.isFinite() && margin <= balance) {
            "مارجین لازم " + String.format(java.util.Locale.US, "%.2f", margin) +
                "$ از موجودی " + String.format(java.util.Locale.US, "%.2f", balance) +
                "$ بیشتر است؛ با اهرم واقعی ۱:" + leverage + " این پوزیشن باز نمی‌شود"
        }
        val commission = kotlin.math.round((spec.commissionUsd(entry, quantity) * 2.0) * 10000.0) / 10000.0
        val spreadCost = kotlin.math.round(spec.spreadCostUsd(entry, quantity) * 10000.0) / 10000.0
        val roundTrip = commission + spreadCost
        val costBps = if (notional > 0.0) kotlin.math.round((roundTrip / notional) * 10_000.0 * 100.0) / 100.0 else 0.0
        return PaperTicket(
            quantity = quantity,
            unit = unitFor(symbol),
            riskBudgetUsd = budget,
            actualRiskUsd = actualRisk,
            notionalUsd = notional,
            rewardRisk = rr,
            leverage = leverage,
            marginUsd = margin,
            commissionUsd = commission,
            spreadCostUsd = spreadCost,
            venue = spec.venue,
            venueSource = spec.source,
            costBps = costBps,
        )
    }
}

