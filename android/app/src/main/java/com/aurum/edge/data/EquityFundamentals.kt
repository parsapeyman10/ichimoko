package com.aurum.edge.data

/**
 * Several INDEPENDENT, real, no-scraping fundamental methods computed from the SAME BrsApi board
 * snapshot the numeric screen already fetched (plus our own locally-observed price history for
 * momentum). No Codal automation here: that endpoint blocks non-browser HTTP clients (verified),
 * so nothing here depends on it. Every method's formula is disclosed; none is a black-box score.
 */
data class EquityFundamentalScore(
    val row: EquityRow,
    /** Method 1: cross-sectional P/E cheapness vs the REST of today's board (0=most expensive, 100=cheapest). */
    val valuePercentile: Double?,
    /** Method 2: composite of turnover share, real-buyer power and spread tightness (0-100, disclosed weights). */
    val qualityScore: Double?,
    /** Method 3: % change in "آخرین قیمت" vs our OWN previous locally-stored observation of this symbol. */
    val momentumPct: Double?,
    val momentumSpanMillis: Long?,
) {
    /** Plain average of the two board-only methods; momentum is shown separately (needs history to exist). */
    val compositeScore: Double? get() {
        val parts = listOfNotNull(valuePercentile, qualityScore)
        return if (parts.isNotEmpty()) parts.average() else null
    }
}

object EquityFundamentals {
    /** Method 1: needs at least 3 positive-EPS/PE rows on today's board to make a percentile meaningful. */
    fun valuePercentiles(rows: List<EquityRow>): Map<String, Double> {
        val withPe = rows.filter { it.epsRial?.let { e -> e > 0 } == true && (it.pe ?: -1.0) > 0.0 }
        if (withPe.size < 3) return emptyMap()
        val sorted = withPe.sortedBy { it.pe }
        val last = (sorted.size - 1).coerceAtLeast(1)
        return sorted.mapIndexed { index, row -> row.isin to (1.0 - index.toDouble() / last) * 100.0 }.toMap()
    }

    /** Method 2: real board fields only; NOT a promise of future demand or quality of disclosure. */
    fun qualityScore(row: EquityRow, maxTurnoverOnBoard: Long?): Double? {
        val turnover = row.turnoverRial?.takeIf { it > 0 }
        val maxTurnover = maxTurnoverOnBoard?.takeIf { it > 0 }
        val turnoverScore = if (turnover != null && maxTurnover != null) (turnover.toDouble() / maxTurnover * 100.0).coerceIn(0.0, 100.0) else null
        val powerScore = row.retailPower?.let { (it.coerceIn(0.0, 3.0) / 3.0 * 100.0) }
        val spreadScore = row.bestSpreadPct?.let { (100.0 - (it.coerceIn(0.0, 5.0) / 5.0 * 100.0)) }
        val parts = listOfNotNull(turnoverScore, powerScore, spreadScore)
        return if (parts.size >= 2) parts.average() else null
    }

    /** Method 3: real self-observed history only; null (not zero) until we have a 2nd observation. */
    suspend fun momentum(history: QuoteHistoryStore, isin: String, currentPrice: Long?, currentAt: Long): Pair<Double, Long>? {
        val price = currentPrice?.toDouble()?.takeIf { it > 0 } ?: return null
        val previous = history.page("iran_stock_$isin", "brsapi", before = currentAt, limit = 1).firstOrNull() ?: return null
        val prevPrice = previous.price?.takeIf { it > 0 } ?: return null
        val span = currentAt - previous.ts
        if (span <= 0L) return null
        val pct = ((price / prevPrice) - 1.0) * 100.0
        return if (pct.isFinite()) pct to span else null
    }
}
