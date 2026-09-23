package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.engine.NewsConfluence
import kotlin.math.abs

/** Side-effect-free guard shared by service and unit tests. NEVER submits a broker order. */
object PaperAutoRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                now: Long = System.currentTimeMillis()): String? {
        if (!settings.autoPaperTrading) return "معاملهٔ خودکار کاغذی خاموش است"
        return opportunityBlocker(market, settings, news, now)
    }

    /** A 9/9 educational alert can be enabled while automatic paper entry is OFF. */
    fun opportunityBlocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                           now: Long = System.currentTimeMillis()): String? {
        if (!settings.backgroundMonitor) return "برای هشدار/ورود، پایش پس‌زمینه باید روشن باشد"
        if (market.symbol != settings.symbol || market.interval != settings.interval) return "نماد/بازه عوض شده است"
        if (market.showingCachedData || market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید واقعی زنده نیست؛ کش برای ورود ممنوع"
        if (market.feed.lastSuccessAt?.let { now - it in 0L..90_000L } != true)
            return "قیمت دریافتی قدیمی است"
        val signal = market.signal ?: return "سیگنال محاسبه نشده است"
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return "سیگنال ۹/۹ قابل معامله موجود نیست"
        if (signal.confluence.take(8).size != 8 || signal.confluence.take(8).any { !it.ok } ||
            signal.confluence.getOrNull(8)?.let { it.name == NewsConfluence.NEWS_LABEL && it.ok } != true)
            return "تمام هشت شرط فنی و خبر AI هم‌زمان تأیید نشده‌اند"
        val match = NewsConfluence.alignment(market.symbol, signal.action, news, now)
        if (match.status != ConfluenceStatus.CONFIRMED) return "خبر AI معتبر نیست: ${match.detail}"
        val lastClosed = market.candles.lastOrNull { it.closed }
        if (lastClosed?.time != signal.barTime || signal.barTime <= 0L ||
            now - (signal.barTime + market.interval.millis) !in 0L..90_000L)
            return "سیگنال روی تازه‌ترین کندل بسته نیست یا اعتبار آن گذشته است"
        val price = market.lastPrice
        if (price == null || !price.isFinite() || price <= 0.0 || !signal.entry.isFinite() ||
            signal.entry <= 0.0 || abs(price / signal.entry - 1.0) > 0.005)
            return "قیمت تازه از ورود سیگنال فاصله گرفته است"
        return null
    }
}
