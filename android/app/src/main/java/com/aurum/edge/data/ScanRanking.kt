package com.aurum.edge.data

import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.Interval
import com.aurum.edge.core.MarketHours

/** Rankings only from the *current*, fresh sweep. Observed means technically valid closed
 * candles outside the user's watchlist: never an alert, entry, or verified live quote.
 */
object ScanRanking {
    fun rank(statuses: List<PairScanStatus>, interval: Interval, now: Long,
             includeObserved: Boolean = false): List<PairScanStatus> = statuses
        .filter { it.price != null && (it.state == "candidate" || (includeObserved && it.state == "observed")) &&
            ScanFreshness.current(it.lastScanAt, interval, now) && !MarketHours.closedFor(it.symbol, now) }
        .sortedWith(compareByDescending<PairScanStatus> { it.state == "candidate" }
            .thenByDescending { it.playbookAllowed == true }
            .thenByDescending { it.trendAligned == true }
            .thenByDescending { it.riskReward ?: 0.0 }
            .thenByDescending { it.confidence ?: 0.0 }
            .thenByDescending { it.technicalScore ?: 0 }
            .thenBy { it.symbol })

    fun byMarket(statuses: List<PairScanStatus>, interval: Interval, now: Long): Map<AssetClass, List<PairScanStatus>> {
        val ranked = rank(statuses, interval, now, includeObserved = true)
        return AssetClass.entries.associateWith { category ->
            ranked.filter { it.assetClass == category }.take(4)
        }
    }
}
