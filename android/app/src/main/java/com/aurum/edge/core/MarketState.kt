package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.FeedMode
import com.aurum.edge.domain.FeedStatus
import com.aurum.edge.domain.Interval

/**
 * Feed-guard view of the selected market: shows the feed as-delayed instead of silently fresh,
 * nulls the signal when the feed is delayed or a source-integrity conflict is active, and audits
 * every candidate. Shared by chart, signal tab, notifications and automatic *paper* entries.
 * Expires on time.
 *
 * Lives in [core] so both [core] rules (AlertDiagnostics, IctEntryRules, PaperAlertRules,
 * PaperAutoRules) and [data] repositories can depend on it without a core → data cycle.
 */
data class MarketState(
    val symbol: String = "XAU/USD",
    val interval: Interval = Interval.M5,
    val candles: List<Candle> = emptyList(),
    val lastPrice: Double? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    val feed: FeedStatus = FeedStatus(FeedMode.NO_KEY),
    val signal: Signal? = null,
    val showingCachedData: Boolean = false,
    /** Distinct from a valid NO_TRADE or insufficient history; never authorizes entry. */
    val evaluationError: String? = null,
    /** Source comparison generation used for this evaluation, not the latest tick receipt. */
    val sourceIntegrityRevision: Long = 0L,
    /** Live history provenance; a disk cache has no verified venue. */
    val historyProvider: String? = null,
    /** Original exchange timestamp/price of the last executed trade; not REST receipt time. */
    val lastTradeAt: Long? = null,
    val lastTradePrice: Double? = null,
) {
    val closedCount: Int get() = candles.count { it.closed }
}
