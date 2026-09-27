package com.aurum.edge.core

import com.aurum.edge.data.MarketState

/** Presentation only. A cached quote or an old receipt is never called a current price. */
data class HomeReadout(
    val current: Boolean,
    val value: Double?,
    val observedAt: Long?,
    val label: String,
) {
    companion object {
        fun from(market: MarketState, now: Long = System.currentTimeMillis()): HomeReadout {
            val price = market.lastPrice?.takeIf { it.isFinite() && it > 0.0 }
            val lastBarAt = market.candles.lastOrNull()?.time?.takeIf { it > 0L }
            val current = price != null && !market.showingCachedData &&
                market.feed.mode in setOf(FeedMode.LIVE, FeedMode.POLLING) &&
                market.feed.lastSuccessAt?.let { now - it in 0L..90_000L } == true &&
                lastBarAt?.let { now - it in 0L..(market.interval.millis + 90_000L) } == true
            return HomeReadout(
                current = current,
                value = price?.takeIf { current || lastBarAt != null },
                observedAt = if (current) market.feed.lastSuccessAt else lastBarAt,
                label = when {
                    current && market.feed.mode == FeedMode.LIVE -> "تیک تازهٔ WebSocket"
                    current -> "کندل تازهٔ REST؛ نه تیک قابل اجرای سفارش"
                    market.feed.mode == FeedMode.NO_KEY -> "کلید دادهٔ بازار روی این نصب موجود نیست"
                    market.feed.mode == FeedMode.MARKET_CLOSED -> "بازار طبق برنامهٔ معمول بسته است؛ قیمت قبلی است"
                    market.showingCachedData || lastBarAt != null -> "دادهٔ قبلی/کش؛ قیمت اکنون تأیید نشده"
                    market.feed.mode == FeedMode.CONNECTING -> "در حال اتصال به دادهٔ واقعی"
                    else -> "دادهٔ تازه در دسترس نیست"
                },
            )
        }
    }
}
