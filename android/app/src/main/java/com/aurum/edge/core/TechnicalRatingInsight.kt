package com.aurum.edge.core

/**
 * «سنجش اثر وضعیت بازار بر نتیجه» — برای هر معامله‌ای که از نسخهٔ ۳.۹.۸۸ به بعد ثبت شده،
 * رأی‌های سنجش تکنیکال ۱H و ۴H در همان لحظهٔ ورود ذخیره شده است. این فایل فقط آن‌ها را
 * دسته‌بندی می‌کند و می‌گوید در هر وضعیت، نرخ برد و R میانگین چقدر بوده است.
 *
 * هیچ تصمیم‌گیری‌ای این‌جا نیست: فقط شمارش و گزارش. هیچ عددی هم جعل نمی‌شود؛ معامله‌ای که
 * داده‌اش نامعلوم است (`-1`) در هیچ دسته‌ای شمرده نمی‌شود.
 */
data class RatingBucketStat(
    val key: RatingBucketKey,
    val trades: Int,
    val wins: Int,
    val winRatePct: Double,
    val netPnlUsd: Double,
    val avgR: Double,
)

data class RatingDimensionInsight(
    val timeframeFa: String,
    val buckets: List<RatingBucketStat>,
    val tradesWithData: Int,
    val tradesTotal: Int,
)

data class TechnicalRatingInsight(
    val oneHour: RatingDimensionInsight,
    val fourHour: RatingDimensionInsight,
) {
    val hasEvidence: Boolean get() = oneHour.tradesWithData > 0 || fourHour.tradesWithData > 0

    companion object {

        fun build(trades: List<PaperTrade>): TechnicalRatingInsight {
            val closed = trades.filter { !it.isOpen && it.origin == PaperTrade.ORIGIN_LIVE }
            return TechnicalRatingInsight(
                oneHour = buildDimension("۱ ساعته", closed) { trade ->
                    Triple(trade.tech1hBuy, trade.tech1hNeutral, trade.tech1hSell)
                },
                fourHour = buildDimension("۴ ساعته", closed) { trade ->
                    Triple(trade.tech4hBuy, trade.tech4hNeutral, trade.tech4hSell)
                },
            )
        }

        fun bucketOf(buy: Int, sell: Int): RatingBucketKey = when {
            buy - sell >= TechnicalRating.STRONG_SCORE -> RatingBucketKey.STRONG_BUY
            buy - sell > 0 -> RatingBucketKey.BUY
            buy - sell == 0 -> RatingBucketKey.NEUTRAL
            buy - sell > -TechnicalRating.STRONG_SCORE -> RatingBucketKey.SELL
            else -> RatingBucketKey.STRONG_SELL
        }

        private fun buildDimension(
            title: String,
            trades: List<PaperTrade>,
            counts: (PaperTrade) -> Triple<Int, Int, Int>,
        ): RatingDimensionInsight {
            val buckets = LinkedHashMap<RatingBucketKey, MutableList<PaperTrade>>()
            var withData = 0
            for (trade in trades) {
                val countsTriple = counts(trade)
                val buy = countsTriple.first
                val neutral = countsTriple.second
                val sell = countsTriple.third
                if (buy < 0 || neutral < 0 || sell < 0) continue
                if (buy + neutral + sell <= 0) continue
                withData++
                buckets.getOrPut(bucketOf(buy, sell)) { ArrayList() } += trade
            }
            val stats = RatingBucketKey.entries.mapNotNull { key ->
                val group = buckets[key] ?: return@mapNotNull null
                val wins = group.count { (it.pnlUsd ?: 0.0) > 0.0 }
                val net = group.sumOf { it.pnlUsd ?: 0.0 }
                val rValues = group.mapNotNull { trade ->
                    val risk = trade.fixedRiskUsd
                    val pnl = trade.pnlUsd
                    if (risk > 0.0 && pnl != null) pnl / risk else null
                }
                RatingBucketStat(
                    key = key,
                    trades = group.size,
                    wins = wins,
                    winRatePct = if (group.isEmpty()) 0.0 else wins.toDouble() / group.size * 100.0,
                    netPnlUsd = net,
                    avgR = if (rValues.isEmpty()) 0.0 else rValues.average(),
                )
            }
            return RatingDimensionInsight(
                timeframeFa = title,
                buckets = stats,
                tradesWithData = withData,
                tradesTotal = trades.size,
            )
        }
    }
}
