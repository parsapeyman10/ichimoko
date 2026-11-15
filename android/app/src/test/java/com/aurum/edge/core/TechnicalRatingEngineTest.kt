package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * سنجش تکنیکال باید از کندل واقعی ساخته شود، برای هر روند جهت درست بدهد، و **هیچ‌جا** در
 * تصمیم معامله استفاده نشود (این فایل فقط خودِ محاسبه را می‌سنجد).
 */
class TechnicalRatingEngineTest {

    private fun series(bars: Int, start: Double, step: Double): List<Candle> {
        var price = start
        return (0 until bars).map { i ->
            val open = price
            price += step
            val close = price
            Candle(
                time = i * 3_600_000L,
                open = open,
                high = max(open, close) + 0.4,
                low = min(open, close) - 0.4,
                close = close,
                volume = 120.0,
            )
        }
    }

    @Test
    fun `twenty six votes are produced for a full series`() {
        val rating = TechnicalRatingEngine.evaluate(series(260, 2_000.0, 1.0))
        assertNotNull("با ۲۶۰ کندل باید رأی ساخته شود", rating)
        rating!!
        assertEquals("جمع رأی‌ها باید ۲۶ باشد", 26, rating.total)
        assertEquals(rating.total, rating.votes.size)
        assertEquals(rating.buy + rating.neutral + rating.sell, rating.total)
    }

    @Test
    fun `a steady uptrend votes buy and a downtrend votes sell`() {
        val up = TechnicalRatingEngine.evaluate(series(260, 2_000.0, 1.0))!!
        assertTrue("در روند صعودی رأی خرید باید از فروش بیشتر باشد", up.buy > up.sell)
        assertEquals(RatingBucketKey.STRONG_BUY, up.bucket)

        val down = TechnicalRatingEngine.evaluate(series(260, 3_000.0, -1.0))!!
        assertTrue("در روند نزولی رأی فروش باید از خرید بیشتر باشد", down.sell > down.buy)
        assertEquals(RatingBucketKey.STRONG_SELL, down.bucket)
    }

    @Test
    fun `too few candles produce no rating instead of a made-up number`() {
        assertNull(TechnicalRatingEngine.evaluate(emptyList()))
        assertNull(TechnicalRatingEngine.evaluate(series(40, 2_000.0, 1.0)))
    }

    @Test
    fun `a flat market leans neutral`() {
        val flat = (0 until 260).map { i ->
            Candle(
                time = i * 3_600_000L,
                open = 2_000.0,
                high = 2_000.2,
                low = 1_999.8,
                close = 2_000.0,
                volume = 100.0,
            )
        }
        val rating = TechnicalRatingEngine.evaluate(flat)!!
        assertEquals("در بازار تخت هیچ رأی خریدی نباید غالب باشد", 0, rating.score)
        assertEquals(TechnicalRating.STRONG_SCORE, 10)
    }

    @Test
    fun `rating is stored on the trade and readable in persian`() {
        val trade = PaperTrade(
            id = "TR-1",
            symbol = "XAU/USD",
            interval = Interval.M15,
            action = SignalAction.BUY,
            entry = 2_600.0,
            stopLoss = 2_592.2,
            takeProfit = 2_615.6,
            confidence = 90.0,
            riskReward = 2.0,
            openedAt = 1_000L,
            tech1hBuy = 20,
            tech1hNeutral = 4,
            tech1hSell = 2,
            tech4hBuy = 5,
            tech4hNeutral = 10,
            tech4hSell = 11,
            fixedRiskUsd = 5.0,
        )
        assertEquals("20 خرید · 4 خنثی · 2 فروش", trade.tech1hFa)
        assertEquals("فروش", trade.tech4hLabelFa)
        assertEquals("خرید قوی", trade.tech1hLabelFa)

        val unknown = trade.copy(tech1hBuy = -1, tech1hNeutral = -1, tech1hSell = -1)
        assertEquals("—", unknown.tech1hFa)
        assertEquals("—", unknown.tech1hLabelFa)
    }
}
