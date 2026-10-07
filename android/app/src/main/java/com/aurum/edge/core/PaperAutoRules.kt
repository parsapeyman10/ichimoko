package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import com.aurum.edge.data.WatchCatalog
import kotlin.math.abs

/** Side-effect-free guard shared by service and unit tests. NEVER submits a broker order. */
object PaperAutoRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                now: Long = System.currentTimeMillis()): String? {
        if (!settings.autoPaperTrading) return "معاملهٔ خودکار کاغذی خاموش است"
        if (market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید معتبر در دسترس نیست (وضعیت فید: ${market.feed.mode.label})"
        return opportunityBlocker(market, settings, news, now, requireMonitor = false,
            allowedSymbols = WatchCatalog.scannerSymbols,
            barAgeGraceMs = market.interval.millis + 600_000L)
    }

    /**
     * An educational candidate alert can be enabled while automatic paper entry is OFF.
     * Supports all 50+ universe instruments.
     */
    fun opportunityBlocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                           now: Long = System.currentTimeMillis(),
                           allowedSymbols: List<String>? = null,
                           barAgeGraceMs: Long = 90_000L,
                           requireMonitor: Boolean = true): String? {
        // Per symbol: a 24/7 crypto venue must not inherit the forex weekend.
        if (MarketHours.weekendClosedFor(market.symbol, now)) return "بازار برای نماد ${market.symbol} بسته است"
        if (requireMonitor && !settings.backgroundMonitor) return "برای هشدار/ورود، پایش پس‌زمینه باید روشن باشد"
        if (allowedSymbols != null) {
            if (market.symbol !in allowedSymbols && market.symbol !in WatchCatalog.scannerSymbols && market.symbol != settings.symbol)
                return "نماد ${market.symbol} در لیست معتبر نیست"
        } else if (market.symbol != settings.symbol || market.interval != settings.interval) {
            return "نماد/بازه عوض شده است"
        }
        if (market.showingCachedData) return "فید واقعی در دسترس نیست؛ کش برای ورود ممنوع"
        if (!FeedLiveness.hasRecentReceipt(market.feed, now, maxAgeMs = 90_000L))
            return "قیمت زنده/تازه در دسترس نیست"
        val signal = market.signal ?: return "سیگنال محاسبه نشده است"
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return "سیگنال فنی قابل معامله موجود نیست"
        val techItems = signal.confluence.filterNot { it.name == com.aurum.edge.engine.NewsConfluence.NEWS_LABEL }
        if (techItems.size == 8) {
            if (techItems.any { !it.ok || it.status != ConfluenceStatus.CONFIRMED })
                return "تمام هشت شرط فنی اصلی هم‌زمان تأیید نشده‌اند"
            val ict = IctEntryRules.assess(market, now, maxBarAgeMs = barAgeGraceMs)
            if (!ict.allowed) return ict.reason
        } else {
            if (signal.confidence < settings.minConfidence && signal.confidence < 72.0)
                return "احتمال سیگنال (${signal.confidence.toInt()}٪) از حد آستانه (${settings.minConfidence.toInt()}٪) کمتر است"
        }
        val lastClosed = market.candles.lastOrNull { it.closed }
        if (lastClosed?.time != signal.barTime || signal.barTime <= 0L ||
            now - (signal.barTime + market.interval.millis) !in 0L..barAgeGraceMs)
            return "سیگنال روی تازه‌ترین کندل بسته نیست یا اعتبار آن گذشته است"
        val price = market.lastPrice
        if (price == null || !price.isFinite() || price <= 0.0 || !signal.entry.isFinite() ||
            signal.entry <= 0.0 || abs(price / signal.entry - 1.0) > 0.05)
            return "قیمت تازه از ورود سیگنال فاصله گرفته است"
        return null
    }
}

