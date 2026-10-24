package com.aurum.edge.core

/**
 * Fail-closed quarantine for NEW paper oil entries until the user's losing trades can be
 * reconciled against venue-matched prices, spread and the seven closed-candle timeframes.
 * No runtime preference or automatic bypass: removing this guard requires a reviewed change.
 * Read-only market inspection and settlement of existing positions remain available.
 */
object OilEntrySafety {
    const val REASON = "ورود تازهٔ نفت (دستی/خودکار) متوقف است؛ ابتدا ژورنال معاملات زیان‌ده و فید هم‌هویت بررسی شود. پوزیشن‌های باز و خروج آن‌ها دست‌نخورده‌اند."

    fun blocker(symbol: String): String? =
        if (MarketPlaybook.familyOf(symbol) == MarketFamily.OIL) REASON else null
}
