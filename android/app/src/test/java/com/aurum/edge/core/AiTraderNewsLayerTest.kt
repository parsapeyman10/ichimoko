package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # تست لایهٔ خبریِ «معامله‌گر هوشمند»
 *
 * کاربر خواسته بود: **«شرایط هر نماد بر اساس هوش خبری، پیش از نزدیک شدن معامله ساخته شود»**.
 * این تست همان قرارداد را می‌سنجد و دو چیز را ثابت می‌کند:
 *
 * ۱. بدون خبر، خروجی پیش‌بینی دقیقاً همان چیزی است که قبلاً بود (هیچ خط اضافه‌ای، هیچ عدد ساختگی).
 * ۲. با شوک خبری/احساس نماد، پیش‌بینی همان نماد **هشدار خبری** می‌گیرد — یعنی خبر واقعاً به
 *    تصمیم قبل از معامله می‌رسد، نه به توضیح بعد از معامله.
 */
class AiTraderNewsLayerTest {

    /** گزارش ساختگی با شرط‌های مشخص: از ۱۰ شرط، [passedCount] شرط پاس است. */
    private fun report(symbol: String, passedCount: Int = 7): StrategyEvaluationReport {
        val conditions = (1..10).map { idx ->
            StrategyConditionCheck(
                index = idx,
                titleFa = "شرط $idx",
                passed = idx <= passedCount,
                detailFa = if (idx <= passedCount) "برقرار" else "برقرار نیست",
            )
        }
        return StrategyEvaluationReport(
            symbol = symbol,
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
            strategyTitleFa = BitcoinStrategyModel.BASE_ICHIMOKU.titleFa,
            strategyBadgeFa = "پایه",
            macroInterval = com.aurum.edge.domain.Interval.H4,
            strategyInterval = com.aurum.edge.domain.Interval.H1,
            triggerInterval = com.aurum.edge.domain.Interval.M15,
            readiness = StrategyReadinessState.WATCH_SETUP,
            action = com.aurum.edge.domain.SignalAction.NO_TRADE,
            triggerType = StrategyTriggerType.NONE,
            confidence = 40.0,
            livePrice = 2700.0,
            suggestedEntry = 2701.0,
            suggestedStopLoss = 2695.0,
            targetV = null,
            targetN = null,
            targetE = null,
            primaryTakeProfit = 2712.0,
            riskReward = 1.8,
            macroSnapshot = null,
            strategySnapshot = null,
            triggerSnapshot = null,
            waveProjection = null,
            conditions = conditions,
            summaryFa = "خلاصه",
            triggerBarTime = 1_760_000_000_000L,
        )
    }

    @Test
    fun `forecast is built and no news means no news lines`() {
        val forecasts = AiTraderEngine.forecastEmergingConditions(
            reportsBySymbol = mapOf("XAU/USD" to listOf(report("XAU/USD"))),
            nowMillis = 1_760_000_100_000L,
        )
        assertEquals(1, forecasts.size)
        val f = forecasts.single()
        assertEquals("XAU/USD", f.symbol)
        assertEquals(7, f.emergingPassed)
        assertEquals(10, f.totalConditions)
        // بدون خبر: هیچ خط خبری نباید ساخته شود.
        assertTrue(
            "بدون ورودی خبری، هیچ خط خبری نباید اضافه شود",
            f.emergingConditionsFa.none {
                it.startsWith("📰") || it.startsWith("🌊") || it.startsWith("🗞️") || it.startsWith("📊")
            },
        )
    }

    @Test
    fun `news shock and per-symbol sentiment reach the pre-trade forecast`() {
        val news = NeuralMacroNewsSnapshot(
            updatedAtMs = 1_760_000_090_000L,
            overallMacroSentiment = -0.6,
            shockIntensity = 0.72,
            isHighImpactNewsWindow = true,
            activeMacroCatalystFa = "انتشار دادهٔ تورم آمریکا",
        )
        val attribution = NeuralMarketCausalAttribution(
            symbol = "XAU/USD",
            categoryCode = "COMMODITY",
            categoryTitleFa = "کالا",
            timeframeLabel = "15m",
            marketRegimeStateFa = "پرنوسان",
            primaryCauseCode = "NEWS",
            primaryCauseBadgeFa = "خبر",
            whatIsHappeningFa = "طلا زیر فشار داده‌های تورمی",
            whyItIsHappeningFa = "بازار انتظار نرخ بهرهٔ بالاتر دارد",
            newsImpactSummaryFa = "اثر منفی خبر",
            unified9BookSummaryFa = "",
            recommendedTacticalActionFa = "ورود را تا آرام‌شدن شوک عقب بینداز",
            causalConfidencePct = 71.0,
            newsSentimentScore = -0.48,
            updatedAtMs = 1_760_000_090_000L,
        )

        val forecasts = AiTraderEngine.forecastEmergingConditions(
            reportsBySymbol = mapOf("XAU/USD" to listOf(report("XAU/USD"))),
            nowMillis = 1_760_000_100_000L,
            newsSnapshot = news,
            attributions = listOf(attribution),
        )
        val f = forecasts.single()
        val joined = f.emergingConditionsFa.joinToString("\n")

        assertTrue(joined, joined.contains("پنجرهٔ خبری پراثر"))
        assertTrue(joined, joined.contains("0.72"))
        assertTrue(joined, joined.contains("انتشار دادهٔ تورم آمریکا"))
        assertTrue(joined, joined.contains("احساس خبری"))
        assertTrue(joined, joined.contains("ورود را تا آرام‌شدن شوک عقب بینداز"))

        // همان چیزی که ساخته می‌شود باید در خلاصهٔ فارسی ذخیره‌شدنی هم دیده شود.
        val summaryLines = AiTraderEngine.buildTraderSummaryFa(
            forecasts = forecasts,
            healings = emptyList(),
            matrix = emptyList(),
        )
        assertTrue(summaryLines.any { it.contains("پیش‌بینی خبری") })
    }

    @Test
    fun `ambiguous news stays silent`() {
        val news = NeuralMacroNewsSnapshot(
            updatedAtMs = 1L,
            shockIntensity = 0.05,
            isHighImpactNewsWindow = false,
            activeMacroCatalystFa = "بدون شوک خبری ناگهانی — جریان تکنیکال و نقدینگی بین‌بانکی حاکم است",
        )
        val forecasts = AiTraderEngine.forecastEmergingConditions(
            reportsBySymbol = mapOf("BTC/USDT" to listOf(report("BTC/USDT"))),
            newsSnapshot = news,
        )
        val f = forecasts.single()
        assertTrue(
            "خبر بی‌اثر نباید هشدار بسازد",
            f.emergingConditionsFa.none { it.startsWith("📰") || it.startsWith("🌊") },
        )
    }
}
