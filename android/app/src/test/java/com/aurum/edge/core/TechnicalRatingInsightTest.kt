package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * گزارش «نرخ برد در هر وضعیت بازار»: فقط معاملات بستهٔ زنده با دادهٔ کامل شمرده می‌شوند و
 * معاملهٔ بدون数据 (‑۱) در هیچ دسته‌ای نمی‌نشیند.
 */
class TechnicalRatingInsightTest {

    private fun trade(
        id: String,
        buy1h: Int,
        neutral1h: Int,
        sell1h: Int,
        pnl: Double,
        open: Boolean = false,
        origin: String = PaperTrade.ORIGIN_LIVE,
    ) = PaperTrade(
        id = id,
        symbol = "XAU/USD",
        interval = Interval.M15,
        action = SignalAction.BUY,
        entry = 2_600.0,
        stopLoss = 2_592.2,
        takeProfit = 2_615.6,
        confidence = 90.0,
        riskReward = 2.0,
        openedAt = 1_000L,
        closedAt = if (open) null else 2_000L,
        pnlUsd = if (open) null else pnl,
        tech1hBuy = buy1h,
        tech1hNeutral = neutral1h,
        tech1hSell = sell1h,
        tech4hBuy = buy1h,
        tech4hNeutral = neutral1h,
        tech4hSell = sell1h,
        fixedRiskUsd = 5.0,
        origin = origin,
    )

    @Test
    fun `buckets group wins and losses by the rating at entry`() {
        val trades = listOf(
            trade("1", 18, 6, 2, 10.0),   // strong buy, win
            trade("2", 16, 8, 2, 10.0),   // strong buy, win
            trade("3", 12, 10, 4, -5.0),  // buy, loss
            trade("4", 4, 10, 12, -5.0),  // sell, loss
            trade("5", 5, 16, 5, 7.5),    // neutral, win
            trade("6", -1, -1, -1, 3.0),  // unknown data — must be ignored
            trade("7", 18, 6, 2, 99.0, open = true), // open trade — must be ignored
            trade("8", 18, 6, 2, 99.0, origin = PaperTrade.ORIGIN_BACKTEST), // backtest — ignored
        )
        val insight = TechnicalRatingInsight.build(trades)
        val hour = insight.oneHour
        assertEquals("تنها ۵ معامله دارای داده", 5, hour.tradesWithData)
        assertEquals("معاملات بستهٔ زنده", 5, hour.tradesTotal)

        val strongBuy = hour.buckets.first { it.key == RatingBucketKey.STRONG_BUY }
        assertEquals(2, strongBuy.trades)
        assertEquals(2, strongBuy.wins)
        assertEquals(100.0, strongBuy.winRatePct, 1e-9)
        assertEquals(20.0, strongBuy.netPnlUsd, 1e-9)
        assertEquals("میانگین R = ۱۰ دلار ÷ ۵ دلار", 2.0, strongBuy.avgR, 1e-9)

        val buy = hour.buckets.first { it.key == RatingBucketKey.BUY }
        assertEquals(1, buy.trades)
        assertEquals(0, buy.wins)
        assertEquals(-1.0, buy.avgR, 1e-9)

        val sell = hour.buckets.first { it.key == RatingBucketKey.SELL }
        assertEquals(1, sell.trades)

        val neutral = hour.buckets.first { it.key == RatingBucketKey.NEUTRAL }
        assertEquals(1, neutral.trades)
        assertEquals(100.0, neutral.winRatePct, 1e-9)
    }

    @Test
    fun `without data the report stays empty instead of inventing numbers`() {
        val insight = TechnicalRatingInsight.build(emptyList())
        assertTrue(!insight.hasEvidence)
        assertEquals(0, insight.oneHour.tradesWithData)
        assertTrue(insight.oneHour.buckets.isEmpty())
    }

    @Test
    fun `bucket thresholds follow the rating score`() {
        assertEquals(RatingBucketKey.STRONG_BUY, TechnicalRatingInsight.bucketOf(20, 2))
        assertEquals(RatingBucketKey.BUY, TechnicalRatingInsight.bucketOf(12, 4))
        assertEquals(RatingBucketKey.NEUTRAL, TechnicalRatingInsight.bucketOf(10, 10))
        assertEquals(RatingBucketKey.SELL, TechnicalRatingInsight.bucketOf(4, 12))
        assertEquals(RatingBucketKey.STRONG_SELL, TechnicalRatingInsight.bucketOf(1, 18))
    }
}
