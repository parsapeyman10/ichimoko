package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * آزمون «آماره‌های گزارش، ساختگی نباشند».
 *
 * منشأ این فایل، ممیزی گزارش PDF بود: جدول پارامترها **t-stat دستی** (۴٫۶۵، ۳٫۹۲، −۴٫۸۵ …) و
 * **خطای معیار ثابت** نشان می‌داد، در حالی که R² صفر بود؛ بازه‌های اطمینان هم دستی ساخته
 * می‌شدند. هر عددی در گزارش باید یا محاسبه‌شده باشد یا «—».
 *
 * قفل‌هایی که این فایل می‌گذارد:
 *  ۱. بدون نمونه، هیچ پارامتری «معنادار» و اعمال‌شده نیست.
 *  ۲. پارامتر اعمال‌شده یعنی `|t| ≥ ۱٫۹۶`، و پارامتر اعمال‌نشده **دقیقاً** مقدار پیشین است.
 *  ۳. R² با نمونهٔ ناکافی `NaN` است، نه صفرِ چسبانده‌شده.
 *  ۴. نمونهٔ یک استراتژی هیچ‌وقت استراتژی دیگر را تیون نمی‌کند (آلودگی متقابل).
 */
class NeuralRegressionHonestyTest {

    private fun engine(tag: String): Pair<NeuralSelfLearningEngine, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "aurum-reg-$tag-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return NeuralSelfLearningEngine(
            context = null,
            storageFile = File(dir, "neural_v1.json"),
        ) to dir
    }

    private fun trade(
        id: String,
        pnlR: Double,
        strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        closedAt: Long = 1_700_000_000_000L,
    ): PaperTrade = PaperTrade(
        id = id,
        symbol = "XAU/USD",
        interval = Interval.M15,
        action = SignalAction.BUY,
        entry = 2_400.0,
        stopLoss = 2_394.0,
        takeProfit = 2_412.0,
        confidence = 70.0,
        riskReward = 2.0,
        openedAt = closedAt - 900_000L,
        closedAt = closedAt,
        exitPrice = 2_400.0 + pnlR * 6.0,
        exitReason = if (pnlR >= 0.0) "TAKE_PROFIT" else "STOP_LOSS",
        pnlUsd = pnlR,
        fixedRiskUsd = 1.0,
        strategyId = strategyId,
        strategyTitleFa = BitcoinStrategyModel.fromId(strategyId).titleFa,
        initialStopLoss = 2_394.0,
    )

    // ۲۴ ویژگی ورودی شبکه (۱۶ هویتی + ۸ حالت بازار) — با همان ابعاد واقعی موتور.
    private fun features(seed: Int): DoubleArray = DoubleArray(NeuralSelfLearningEngine.INPUT_DIM) { i ->
        val base = ((seed * 37 + i * 11) % 100) / 100.0
        base.coerceIn(0.0, 1.0)
    }

    private fun feed(eng: NeuralSelfLearningEngine, count: Int, strategyId: String = BitcoinStrategyModel.BASE_ICHIMOKU.id) {
        for (i in 0 until count) {
            val won = i % 2 == 0
            val t = trade(
                id = "T-$strategyId-$i",
                pnlR = if (won) 1.8 else -1.0,
                strategyId = strategyId,
                closedAt = 1_700_000_000_000L + i * 900_000L,
            )
            eng.recordCompletedTrade(
                trade = t,
                report = null,
                featuresOverride = features(i),
                conditionKeyOverride = "COND-${i % 7}",
            )
        }
    }

    @Test
    fun `empty memory never claims a significant parameter`() {
        val (eng, dir) = engine("empty")
        try {
            val report = eng.runParameterRegression(nowMs = 1_700_000_000_000L)
            assertEquals("بدون نمونه، تعداد نمونه صفر است", 0, report.samplesCount)
            assertEquals("هیچ پارامتری نباید اعمال شود", 0, report.parametersApplied)
            report.parameterRows.forEach { row ->
                assertFalse("پارامتر ${row.paramKey} بی‌داده اعمال شد", row.applied)
                assertEquals(
                    "پارامتر اعمال‌نشده باید دقیقاً مقدار پیشین بماند (${row.paramKey})",
                    row.priorValue,
                    row.regressedValue,
                    1e-9,
                )
            }
            assertTrue(
                "R² با نمونهٔ ناکافی باید NaN باشد، نه صفرِ ساختگی",
                report.rSquared.isNaN(),
            )
            assertTrue(
                "قضاوت کیفیت برازش باید صریح بگوید نمونه کافی نیست",
                report.fitQualityVerdictFa.contains("نمونه"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `applied parameters always carry a real t-statistic`() {
        val (eng, dir) = engine("applied")
        try {
            feed(eng, 80)
            val report = eng.runParameterRegression(nowMs = 1_700_000_000_000L)
            assertEquals("نمونه‌ها باید ثبت شده باشند", 80, report.samplesCount)
            assertEquals(
                "شمارندهٔ اعمال‌شده باید با ردیف‌های applied بخواند",
                report.parameterRows.count { it.applied },
                report.parametersApplied,
            )
            report.parameterRows.forEach { row ->
                if (row.applied) {
                    assertTrue("t باید محاسبه‌شده باشد (${row.paramKey})", row.tStat.isFinite())
                    assertTrue(
                        "پارامتر اعمال‌شده باید |t| ≥ ۱٫۹۶ داشته باشد (${row.paramKey}: ${row.tStat})",
                        abs(row.tStat) >= 1.96,
                    )
                    assertTrue("خطای معیار اعمال‌شده باید مثبت باشد (${row.paramKey})", row.stdError > 0.0)
                    assertTrue(
                        "بازهٔ اطمینان باید از مقدار ساخته شود، نه از عدد دستی (${row.paramKey})",
                        row.confidenceIntervalLabel.startsWith("[") &&
                            !row.confidenceIntervalLabel.contains("n کم"),
                    )
                } else {
                    assertEquals(
                        "پارامتر اعمال‌نشده باید پیشین بماند (${row.paramKey})",
                        row.priorValue,
                        row.regressedValue,
                        1e-9,
                    )
                    assertTrue(
                        "دلیل اعمال‌نشدن باید نوشته شود (${row.paramKey})",
                        row.significanceNoteFa.isNotBlank(),
                    )
                }
            }
            // هیچ t ساختگیِ دستی نباید برگردد (عددهای قبلی ۴٫۶۵ / ۳٫۹۲ / ۴٫۱۸ / −۴٫۸۵ بودند)
            val handWritten = setOf(4.65, 3.92, 4.18, -4.85, 3.74, -4.52, 5.12, -4.35, 4.40, 3.60)
            report.parameterRows.forEach { row ->
                if (row.tStat.isFinite()) {
                    assertFalse(
                        "t پارامتر ${row.paramKey} با عدد دستیِ قبلی یکی است (${row.tStat})",
                        handWritten.contains(row.tStat),
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `one strategy never tunes another strategy`() {
        val (eng, dir) = engine("contamination")
        try {
            feed(eng, 60, BitcoinStrategyModel.BASE_ICHIMOKU.id)
            val other = eng.runParameterRegression(
                strategyId = BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id,
                nowMs = 1_700_000_000_000L,
            )
            assertEquals(
                "استراتژی بدون نمونه نباید با نمونهٔ استراتژی دیگر تیون شود",
                0,
                other.samplesCount,
            )
            assertEquals("و نباید پارامتری اعمال شود", 0, other.parametersApplied)
            val own = eng.runParameterRegression(
                strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
                nowMs = 1_700_000_000_000L,
            )
            assertEquals("نمونهٔ خودش باید دیده شود", 60, own.samplesCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `confidence intervals stay inside the locked corridor`() {
        val (eng, dir) = engine("corridor")
        try {
            feed(eng, 90)
            val report = eng.runParameterRegression(nowMs = 1_700_000_000_000L)
            val rr = report.parameterRows.first { it.paramKey == "minRiskReward" }
            assertTrue("کف R:R نباید زیر ۱٫۵ برود (${rr.confidenceIntervalLabel})", rr.regressedValue >= 1.50)
            val kijun = report.parameterRows.first { it.paramKey == "maxKijunExtensionAtr" }
            assertTrue("سقف کیجون باید در بازهٔ قفل‌شده بماند (${kijun.regressedValue})", kijun.regressedValue <= 2.20)
        } finally {
            dir.deleteRecursively()
        }
    }
}
