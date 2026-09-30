package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import kotlin.math.abs

/** Side-effect-free guard shared by service and unit tests. NEVER submits a broker order. */
object PaperAutoRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                now: Long = System.currentTimeMillis()): String? {
        if (!settings.autoPaperTrading) return "معاملهٔ خودکار کاغذی خاموش است"
        // REST publishes a recent BAR, not a timestamped last trade within that bar.
        // It can justify an educational candidate, never an automatic paper fill.
        if (market.feed.mode != FeedMode.LIVE) return "ورود خودکار کاغذی فقط با تیک تازهٔ زنده مجاز است؛ کندل/تاریخچهٔ دوره‌ای فقط نامزد آموزشی است"
        return opportunityBlocker(market, settings, news, now, requireMonitor = false)
    }

    /**
     * An educational 8/8 technical candidate alert can be enabled while automatic paper entry is OFF.
     *
     * [allowedSymbols] widens the check from the single selected chart symbol to a catalog sweep
     * (the multi-pair scanner); [barAgeGraceMs] extends the signal-bar freshness window by one
     * interval for REST-swept pairs, where the provider's latest closed bar may be up to one
     * interval old at fetch time. The live tick path keeps the strict 90s defaults.
     */
    fun opportunityBlocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                           now: Long = System.currentTimeMillis(),
                           allowedSymbols: List<String>? = null,
                           barAgeGraceMs: Long = 90_000L,
                           requireMonitor: Boolean = true): String? {
        if (MarketHours.forexWeekendClosed(now)) return "بازار فارکس طبق برنامهٔ معمول پایان هفته بسته است؛ ورود/اعلان معاملاتی نداریم"
        if (requireMonitor && !settings.backgroundMonitor) return "برای هشدار/ورود، پایش پس‌زمینه باید روشن باشد"
        if (allowedSymbols != null) {
            if (market.symbol !in allowedSymbols || market.interval != settings.interval) return "نماد/بازه اسکن معتبر نیست"
        } else if (market.symbol != settings.symbol || market.interval != settings.interval) {
            return "نماد/بازه عوض شده است"
        }
        if (market.showingCachedData || market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید واقعی زنده نیست؛ کش برای ورود ممنوع"
        if (!FeedLiveness.hasRecentReceipt(market.feed, now))
            return "قیمت دریافتی قدیمی است"
        val signal = market.signal ?: return "سیگنال محاسبه نشده است"
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return "سیگنال فنی قابل معامله موجود نیست"
        if (signal.confluence.take(8).size != 8 ||
            signal.confluence.take(8).any { !it.ok || it.status != ConfluenceStatus.CONFIRMED })
            return "تمام هشت شرط فنی اصلی هم‌زمان تأیید نشده‌اند"
        // News is deliberately not an entry condition for paper trading. It is mined separately
        // into the journal when it is near the trade, so technical/option gates stay deterministic.
        val lastClosed = market.candles.lastOrNull { it.closed }
        if (lastClosed?.time != signal.barTime || signal.barTime <= 0L ||
            now - (signal.barTime + market.interval.millis) !in 0L..barAgeGraceMs)
            return "سیگنال روی تازه‌ترین کندل بسته نیست یا اعتبار آن گذشته است"
        val price = market.lastPrice
        if (price == null || !price.isFinite() || price <= 0.0 || !signal.entry.isFinite() ||
            signal.entry <= 0.0 || abs(price / signal.entry - 1.0) > 0.005)
            return "قیمت تازه از ورود سیگنال فاصله گرفته است"
        // An additional gate, never a substitute for the eight technical checks.
        return IctEntryRules.assess(market, now, maxBarAgeMs = barAgeGraceMs).reason
    }
}
