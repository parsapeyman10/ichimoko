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
            barAgeGraceMs = market.interval.millis + 300_000L)
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
        // Per symbol: a 24/7 crypto venue must not inherit the forex weekend.
        if (MarketHours.weekendClosedFor(market.symbol, now)) return "بازار برای نماد ${market.symbol} بسته است"
        if (requireMonitor && !settings.backgroundMonitor) return "برای هشدار/ورود، پایش پس‌زمینه باید روشن باشد"
        if (allowedSymbols != null) {
            if (market.symbol !in allowedSymbols && market.symbol != settings.symbol) return "نماد ${market.symbol} در لیست معتبر نیست"
        } else if (market.symbol != settings.symbol || market.interval != settings.interval) {
            return "نماد/بازه عوض شده است"
        }
        if (market.showingCachedData && market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید واقعی در دسترس نیست؛ کش برای ورود ممنوع"
        val signal = market.signal ?: return "سیگنال محاسبه نشده است"
        if (!signal.isActionable || signal.entry == null || signal.stopLoss == null || signal.takeProfit == null)
            return "سیگنال فنی قابل معامله موجود نیست"
        val techItems = signal.confluence.filterNot { it.name == com.aurum.edge.engine.NewsConfluence.NEWS_LABEL }
        if (techItems.size == 8) {
            if (techItems.any { !it.ok || it.status != ConfluenceStatus.CONFIRMED })
                return "تمام هشت شرط فنی اصلی هم‌زمان تأیید نشده‌اند"
        } else {
            if (signal.confidence < settings.minConfidence)
                return "احتمال سیگنال (${signal.confidence.toInt()}٪) از حد آستانه (${settings.minConfidence.toInt()}٪) کمتر است"
        }
        // News is deliberately not an entry condition for paper trading. It is mined separately
        // into the journal when it is near the trade, so technical/option gates stay deterministic.
        val lastClosed = market.candles.lastOrNull { it.closed }
        if (lastClosed?.time != signal.barTime || signal.barTime <= 0L ||
            now - (signal.barTime + market.interval.millis) !in 0L..barAgeGraceMs)
            return "سیگنال روی تازه‌ترین کندل بسته نیست یا اعتبار آن گذشته است"
        val price = market.lastPrice
        if (price == null || !price.isFinite() || price <= 0.0 || !signal.entry.isFinite() ||
            signal.entry <= 0.0 || abs(price / signal.entry - 1.0) > 0.02)
            return "قیمت تازه از ورود سیگنال فاصله گرفته است"
        // An additional gate for legacy 8-condition / ICT setups.
        if (techItems.size == 8) {
            return IctEntryRules.assess(market, now, maxBarAgeMs = barAgeGraceMs).reason
        }
        return null
    }
}
