package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.domain.Interval

/**
 * Feed-guard view of the selected market: keeps the feed snapshot the UI and quote policy read,
 * so a delayed feed is shown as delayed instead of silently fresh.
 *
 * Lives in [core] so both [core] rules (AlertDiagnostics, IctEntryRules, PaperAlertRules,
 * PaperAutoRules) and [data] repositories can depend on it without a core → data cycle.
 */
data class MarketState(
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val candles: List<Candle> = emptyList(),
    val lastPrice: Double? = null,
    val feed: FeedStatus = FeedStatus(FeedMode.NO_KEY),
    val showingCachedData: Boolean = false,
    /** Original exchange timestamp of the last executed trade; not REST receipt time. */
    val lastTradeAt: Long? = null,
)
