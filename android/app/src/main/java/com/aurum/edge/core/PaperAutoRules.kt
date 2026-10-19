package com.aurum.edge.core

import com.aurum.edge.data.MarketState
import com.aurum.edge.data.BinanceSpotClient
import kotlin.math.abs

/** Side-effect-free guard shared by service and unit tests. NEVER submits a broker order.
 * News/AI layers were removed: only feed freshness, source integrity, technical evidence
 * and risk rules gate a paper entry now. */
object PaperAutoRules {
    fun blocker(market: MarketState, settings: AppSettings,
                now: Long = System.currentTimeMillis(),
                allowedSymbols: List<String> = settings.activeSymbols): String? {
        com.aurum.edge.data.SourceIntegrity.shared.blocker(market.symbol, market.sourceIntegrityRevision)?.let { return it }
        if (market.interval != Interval.M5 || market.signal?.interval?.let { it != Interval.M5 } == true)
            return "ورود کاغذی فقط روی کندل بستهٔ ۵ دقیقه (5m) مجاز است"
        OilEntrySafety.blocker(market.symbol)?.let { return it }
        if (!settings.autoPaperTrading) return "معاملهٔ خودکار کاغذی خاموش است"
        if (V1Universe.normalize(market.symbol) == "XAU/USD") {
            val goldSignal = market.signal ?: return "سیگنال ALS-5 طلا موجود نیست"
            if (goldSignal.als5Opportunity?.matches(goldSignal) != true)
                return "برای طلا فقط BUY/SELL تأییدشدهٔ ALS-5 مجاز است"
        }
        if (AssetClass.of(market.symbol) == AssetClass.CRYPTO) {
            val signal = market.signal ?: return "سیگنال فشردگی/انفجار موجود نیست"
            if (signal.cryptoBreakout?.matches(signal) != true ||
                signal.ichimokuEntry?.matches(signal) != true)
                return "فقط استراتژی فشردگی ← انفجار با شواهد بسته مجاز است"
            val tick = market.lastTradeAt?.let { time -> market.lastTradePrice?.let { price ->
                PriceTick(price, time, market.bid, market.ask)
            } }
            CryptoCompressionPolicy.quoteBlocker(signal, tick, now)?.let { return it }
            if (!com.aurum.edge.data.CryptoVenuePolicy.compatible(
                    market.symbol, market.historyProvider, market.feed.provider))
                return "تاریخچهٔ هم‌صرافی منبع اختصاصی برای ورود تأیید نشده است"
            val tradeAt = market.lastTradeAt
            val tradePrice = market.lastTradePrice
            if (market.feed.mode != FeedMode.LIVE || tradeAt == null ||
                tradeAt !in (now - 45_000L)..(now + 10_000L) ||
                tradePrice == null || !tradePrice.isFinite() || tradePrice <= 0.0)
                return "معاملهٔ واقعی تازه و زمان‌دار Binance Spot برای ورود موجود نیست"
        }
        if (market.feed.mode !in setOf(FeedMode.LIVE, FeedMode.POLLING))
            return "فید معتبر در دسترس نیست (وضعیت فید: ${market.feed.mode.label})"
        return opportunityBlocker(market, settings, now, requireMonitor = false,
            allowedSymbols = allowedSymbols,
            barAgeGraceMs = market.interval.millis + 90_000L)
    }

    /**
     * A technical candidate alert can be enabled while automatic paper entry is OFF.
     * Supports every user-selected symbol with a verified independent feed.
     */
    fun opportunityBlocker(market: MarketState, settings: AppSettings,
                           now: Long = System.currentTimeMillis(),
                           allowedSymbols: List<String>? = null,
                           barAgeGraceMs: Long = 90_000L,
                           requireMonitor: Boolean = true): String? {
        // Per symbol: a 24/7 crypto venue must not inherit the forex weekend.
        com.aurum.edge.data.SourceIntegrity.shared.blocker(market.symbol, market.sourceIntegrityRevision)?.let { return it }
        if (market.interval != Interval.M5 || market.signal?.interval?.let { it != Interval.M5 } == true)
            return "ورود کاغذی فقط روی کندل بستهٔ ۵ دقیقه (5m) مجاز است"
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
