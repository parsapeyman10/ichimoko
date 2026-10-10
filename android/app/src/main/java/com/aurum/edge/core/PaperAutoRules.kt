package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.PersianNewsState
import kotlin.math.abs

/** Side-effect-free guard shared by service and unit tests. NEVER submits a broker order. */
object PaperAutoRules {
    fun blocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                now: Long = System.currentTimeMillis()): String? {
        if (!settings.autoPaperTrading) return "معاملهٔ خودکار کاغذی خاموش است"
        if (market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید معتبر در دسترس نیست (وضعیت فید: ${market.feed.mode.label})"
        return opportunityBlocker(market, settings, news, now, requireMonitor = false,
            allowedSymbols = settings.activeWatchlist,
            barAgeGraceMs = market.interval.millis + 90_000L)
    }

    /**
     * A technical candidate alert can be enabled while automatic paper entry is OFF.
     * Supports every user-selected symbol with a verified independent feed.
     */
    fun opportunityBlocker(market: MarketState, settings: AppSettings, news: PersianNewsState,
                           now: Long = System.currentTimeMillis(),
                           allowedSymbols: List<String>? = null,
                           barAgeGraceMs: Long = 90_000L,
                           requireMonitor: Boolean = true): String? {
        // Per symbol: a 24/7 crypto venue must not inherit the forex weekend.
        if (MarketHours.closedFor(market.symbol, now)) return "بازار برای نماد ${market.symbol} بسته است"
        if (requireMonitor && !settings.backgroundMonitor) return "برای هشدار/ورود، پایش پس‌زمینه باید روشن باشد"
        if (allowedSymbols != null) {
            if (market.symbol !in allowedSymbols)
                return "نماد ${market.symbol} در لیست معتبر نیست"
        } else if (market.symbol != settings.symbol || market.interval != settings.interval) {
            return "نماد/بازه عوض شده است"
        }
        if (market.showingCachedData) return "فید واقعی در دسترس نیست؛ کش برای ورود ممنوع"
        if (market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید زنده/معتبر در دسترس نیست"
        if (!FeedLiveness.hasRecentReceipt(market.feed, now))
            return "قیمت زنده/تازه در دسترس نیست"
        val signal = market.signal ?: return "سیگنال محاسبه نشده است"
        val vetted = com.aurum.edge.engine.NewsConfluence.apply(signal, market.symbol, news, now)
        if (vetted?.isActionable != true) return vetted?.blockers?.lastOrNull() ?: "وتوی AI یا نبود سیگنال"
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return "سیگنال فنی قابل معامله موجود نیست"
        if (!TechnicalEvidence.confirmed(signal))
            return "چهار لایهٔ فنی معتبر نیستند"
        if (!signal.confidence.isFinite() || !settings.minConfidence.isFinite() ||
            signal.confidence < settings.minConfidence)
            return "اطمینان سیگنال از آستانهٔ تنظیم‌شده کمتر است"
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

