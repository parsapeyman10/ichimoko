package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمایشگاه کیفیت ورود (فاز ۲): ریاضی بُرش‌ها و بازپخش قاعده‌ها باید شفاف باشد.
 */
class EntryQualityLabTest {

    private fun sample(
        id: String,
        pnl: Double,
        isBuy: Boolean = true,
        h1: Pair<Int, Int> = 20 to 2,
        h4: Pair<Int, Int> = 18 to 3,
        category: String = PreEntry3CandleCategory.PULLBACK_REJECTION_IGNITION.code,
        distKijun: Double = 0.6,
        openedAt: Long = 1_700_000_000_000L, // 2023-11-14 22:13 UTC
    ) = EntryQualityLab.Sample(
        tradeId = id,
        strategyId = "BASE_ICHIMOKU",
        strategyTitleFa = "ترکیب ۴گانه · بیس جامع",
        symbol = "EUR/USD",
        intervalLabel = "15m",
        openedAt = openedAt,
        isBuy = isBuy,
        pnlUsd = pnl,
        rMultiple = pnl / 5.0,
        categoryCode = category,
        categoryLabelFa = PreEntry3CandleCategory.fromCode(category).badgeFa,
        distKijunAtr = distKijun,
        er14 = 0.3,
        tech1hBuy = h1.first,
        tech1hNeutral = 5,
        tech1hSell = h1.second,
        tech4hBuy = h4.first,
        tech4hNeutral = 5,
        tech4hSell = h4.second,
    )

    @Test
    fun `buckets report sample count, win rate, expectancy and share`() {
        val report = EntryQualityLab.analyze(
            samples = listOf(
                sample("1", 10.0),
                sample("2", -5.0),
                sample("3", 10.0),
                sample("4", -5.0),
            ),
        )
        val baseline = report.dimensions.first { it.key == "all" }.buckets.single()
        assertEquals(4, baseline.samples)
        assertEquals(2, baseline.wins)
        assertEquals(50.0, baseline.winRatePct, 0.01)
        assertEquals(2.5, baseline.expectancyUsd, 0.01)
        assertEquals(10.0, baseline.totalPnlUsd, 0.01)

        val byStrategy = report.dimensions.first { it.key == "strategy" }.buckets.single()
        assertEquals(100.0, byStrategy.sharePct, 0.01)
        assertTrue(byStrategy.reliable.not())
    }

    @Test
    fun `gauge alignment follows the trade direction and marks unknown data`() {
        val report = EntryQualityLab.analyze(
            samples = listOf(
                // خرید با سنجش صعودی ۱ ساعته ⇒ هم‌جهت
                sample("aligned", 8.0, isBuy = true, h1 = 20 to 2),
                // خرید با سنجش نزولی ⇒ مخالف
                sample("opposite", -5.0, isBuy = true, h1 = 3 to 19),
                // فروش با سنجش نزولی ⇒ هم‌جهت
                sample("sellAligned", 8.0, isBuy = false, h1 = 2 to 20),
                // بدون دادهٔ سنجش
                sample("unknown", -5.0, h1 = -1 to -1),
            ),
        )
        val buckets = report.dimensions.first { it.key == "gauge_1h" }.buckets
        assertEquals(2, buckets.first { it.key == "aligned" }.samples)
        assertEquals(1, buckets.first { it.key == "opposite" }.samples)
        assertEquals(1, buckets.first { it.key == "unknown" }.samples)
        assertEquals(1, report.unknownGaugeSamples)
    }

    @Test
    fun `rule replay measures what a filter would have done to the same trades`() {
        val samples = listOf(
            sample("a", 10.0),
            sample("b", 10.0),
            sample("chase", -5.0, category = PreEntry3CandleCategory.CCLAMP_EXTENDED_CHASE.code),
        )
        val report = EntryQualityLab.analyze(samples)
        val rule = report.rules.first { it.id == "no_cclamp_chase" }
        assertEquals(2, rule.kept)
        assertEquals(1, rule.dropped)
        assertEquals(10.0, rule.keptExpectancyUsd, 0.01)
        assertEquals(5.0, rule.baselineExpectancyUsd, 0.01)
        assertEquals(5.0, rule.deltaPnlUsd, 0.01)
        assertEquals(5.0, rule.expectancyGainUsd, 0.01)
        // فقط ۲ معامله نگه داشته ⇒ قاعده «بهتر» اعلام نمی‌شود (حداقل ۳۰ لازم است)
        assertFalse(rule.better)
    }

    @Test
    fun `trades without gauge data are kept by gauge rules, not silently dropped`() {
        val samples = listOf(
            sample("known", 10.0, h1 = 20 to 2),
            sample("unknown", -5.0, h1 = -1 to -1, h4 = -1 to -1),
        )
        val report = EntryQualityLab.analyze(samples)
        val h1Rule = report.rules.first { it.id == "h1_aligned" }
        assertEquals(2, h1Rule.kept)
        val bothRule = report.rules.first { it.id == "h1h4_aligned" }
        assertEquals(2, bothRule.kept)
    }

    @Test
    fun `kijun distance and session buckets are labelled for humans`() {
        val report = EntryQualityLab.analyze(
            samples = listOf(
                sample("close", 10.0, distKijun = 0.3),
                sample("far", -5.0, distKijun = 1.9),
            ),
        )
        val kijun = report.dimensions.first { it.key == "kijun_distance" }.buckets
        assertTrue(kijun.any { it.labelFa.contains("چسبیده") })
        assertTrue(kijun.any { it.labelFa.contains("کشیده") })

        val session = report.dimensions.first { it.key == "session" }.buckets
        assertTrue(session.isNotEmpty())
        assertTrue(session.first().labelFa.contains("UTC"))
    }

    @Test
    fun `an empty journal produces an honest empty report`() {
        val report = EntryQualityLab.analyze(emptyList())
        assertFalse(report.hasData)
        assertTrue(report.dimensions.isEmpty())
        assertTrue(report.rules.isEmpty())
        assertEquals(0, report.samples)
    }
}
