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
)

object PaperOrderRules {
    fun unitFor(symbol: String): String = when (symbol.substringBefore('/')) {
        "XAU", "XAG" -> "oz"
        else -> symbol.substringBefore('/').take(12).ifBlank { "units" }
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
        require(symbol.matches(Regex("[A-Z0-9]{2,12}/(USD|USDT)"))) {
            "محاسبهٔ ریسک دلاری فقط برای جفت‌ارزهای /USD یا /USDT معتبر است"
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
        val distance = abs(entry - stop)
        require(distance / entry in 0.0005..0.15) { "فاصلهٔ استاپ باید بین ۰٫۰۵٪ تا ۱۵٪ قیمت باشد" }
        val rr = abs(target - entry) / distance
        require(rr.isFinite() && rr in 1.5..5.0) { "نسبت سود به زیان باید بین ۱٫۵ و ۵ باشد" }
        val budget = balance * riskPercent / 100.0
        // Round DOWN, never up or to a positive minimum that could breach the risk budget.
        // These are fractional *paper* units, NOT a broker's minimum lot/step or margin quote.
        val quantity = floor(budget / distance * 1_000_000.0) / 1_000_000.0
        require(quantity.isFinite() && quantity >= 0.000001) { "حجم با بودجهٔ ریسک فعلی بسیار کوچک است" }
        val actualRisk = quantity * distance
        val notional = quantity * entry
        require(actualRisk.isFinite() && actualRisk <= budget + 1e-8 && notional.isFinite() &&
            notional <= balance * 3.0 + 1e-8) {
            "ارزش فرضی پوزیشن از سقف ۳ برابر موجودی کاغذی فراتر می‌رود"
        }
        return PaperTicket(quantity, unitFor(symbol), budget, actualRisk, notional, rr)
    }
}
