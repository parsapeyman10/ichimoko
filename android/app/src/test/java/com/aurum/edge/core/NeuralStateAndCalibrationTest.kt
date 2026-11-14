package com.aurum.edge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «حالت بازار» و «سنجش صادقانهٔ شبکهٔ عصبی» (R19).
 *
 * چرا تست دارند؟ چون ادعای «بهترین مدل معاملاتی» بدون دو چیز بی‌معناست:
 *  ۱. ویژگی‌های حالت باید واقعاً از داده محاسبه شوند و در بازهٔ امن بمانند (نه حدس).
 *  ۲. سنجش باید بتواند صریحاً بگوید «مدل از پایه بهتر نیست».
 *
 * این تست‌ها هیچ عدد جعلی نمی‌سازند؛ ورودی‌ها همه ساختگیِ صریح‌اند و خروجی‌ها محاسبهٔ همان ورودی‌ها.
 */
class NeuralStateAndCalibrationTest {

    // ── ۱. سشن بر اساس ساعت UTC ────────────────────────────────────────────────

    @Test
    fun `session buckets follow UTC hours`() {
        fun ses(hour: Int): String {
            val ms = hour * 3_600_000L
            return NeuralStateFeatures.sessionCodeOf(ms)
        }
        assertEquals("ASIA", ses(0))
        assertEquals("ASIA", ses(6))
        assertEquals("LONDON", ses(7))
        assertEquals("LONDON", ses(11))
        assertEquals("LONDON_NY", ses(12))
        assertEquals("LONDON_NY", ses(15))
        assertEquals("NEW_YORK", ses(16))
        assertEquals("NEW_YORK", ses(20))
        assertEquals("LATE_NY", ses(21))
        assertEquals("LATE_NY", ses(23))
    }

    @Test
    fun `unknown entry time means unknown session, never a made-up one`() {
        assertEquals("", NeuralStateFeatures.sessionCodeOf(0L))
        assertEquals(0.0, NeuralStateFeatures.build(NeuralStateInputs(entryTimeMs = 0L))[6], 0.0)
    }

    // ── ۲. بردار حالت: اندازه، بازه و بی‌طرفی مقدار نامعلوم ────────────────────

    @Test
    fun `state vector has eight values inside the safe band`() {
        val extreme = NeuralStateInputs(
            trendPhaseCode = "GENESIS",
            er14 = 99.0,
            atrPctOfPrice = 99.0,
            distKijunAtr = 99.0,
            kumoThicknessAtr = 99.0,
            volumeRatio20 = 99.0,
            planRr = 99.0,
            entryTimeMs = 16 * 3_600_000L,
        )
        val v = NeuralStateFeatures.build(extreme)
        assertEquals(NeuralStateFeatures.COUNT, v.size)
        v.forEach { assertTrue("value out of band: $it", it in -1.0..1.0) }

        val negative = extreme.copy(
            er14 = 0.0,
            atrPctOfPrice = 0.0,
            distKijunAtr = 0.0,
            kumoThicknessAtr = 0.0,
            volumeRatio20 = 0.0,
            planRr = 0.0,
        )
        NeuralStateFeatures.build(negative).forEach { assertTrue(it in -1.0..1.0) }
    }

    @Test
    fun `missing values are neutral rather than guessed`() {
        val v = NeuralStateFeatures.build(NeuralStateInputs())
        assertEquals(NeuralStateFeatures.COUNT, v.size)
        // فاز/ER/ATR/کیجون/ابر/حجم/سشن/R:R — همه نامعلوم ⇒ همه صفر (بی‌طرف).
        v.forEach { assertEquals(0.0, it, 0.0) }
    }

    @Test
    fun `phase and volatility move the right direction`() {
        val genesis = NeuralStateFeatures.build(NeuralStateInputs(trendPhaseCode = "GENESIS"))
        val chop = NeuralStateFeatures.build(NeuralStateInputs(trendPhaseCode = "CHOP_RANGE"))
        assertTrue(genesis[0] > chop[0])

        val quiet = NeuralStateFeatures.build(NeuralStateInputs(atrPctOfPrice = 0.05))
        val wild = NeuralStateFeatures.build(NeuralStateInputs(atrPctOfPrice = 0.80))
        assertTrue(wild[2] > quiet[2])
    }

    @Test
    fun `unknown trade state stays unknown in the feature band`() {
        val trade = PaperTrade(
            id = "t1",
            symbol = "XAU/USD",
            interval = com.aurum.edge.domain.Interval.M15,
            action = com.aurum.edge.domain.SignalAction.BUY,
            entry = 2000.0,
            stopLoss = 1990.0,
            takeProfit = 2020.0,
            confidence = 80.0,
            riskReward = 2.0,
            openedAt = 7 * 3_600_000L,
            positionOz = 0.1,
            // state* پیش‌فرض = -1.0 ⇒ نامعلوم
        )
        val inputs = NeuralStateFeatures.fromTrade(trade)
        assertTrue(inputs.er14 == null)
        assertTrue(inputs.atrPctOfPrice == null)
        assertEquals("LONDON", inputs.sessionCode)
        val band = NeuralStateFeatures.build(inputs)
        assertEquals(0.0, band[1], 0.0)
        assertEquals(0.0, band[2], 0.0)
    }

    // ── ۳. سنجش: باید بتواند بگوید «یادگیری‌ای نیست» ───────────────────────────

    @Test
    fun `calibration refuses to publish metrics with too few samples`() {
        val points = List(NeuralCalibration.MIN_SAMPLES_FOR_METRICS - 1) {
            NeuralCalibrationPoint(predictedWinProb = 0.9, won = true, atMs = it.toLong())
        }
        val report = NeuralCalibration.evaluate(points)
        assertFalse(report.brier.isFinite())
        assertTrue(report.verdictFa.contains("نمونه کم است"))
    }

    @Test
    fun `a constant prediction matches the baseline brier exactly`() {
        // پیش‌بینی همیشه ۵۰٪ روی داده‌ای با نرخ برد ۵۰٪ ⇒ Brier مدل = Brier پایه (سود صفر).
        val points = List(40) { i ->
            NeuralCalibrationPoint(predictedWinProb = 0.5, won = i % 2 == 0, atMs = i.toLong())
        }
        val report = NeuralCalibration.evaluate(points)
        assertTrue(report.brier.isFinite())
        assertEquals(report.brierBaseline, report.brier, 0.0005)
        assertTrue(report.verdictFa.contains("بهتر نیست"))
    }

    @Test
    fun `overconfident predictions score worse than the baseline`() {
        // مدل می‌گوید ۱۰۰٪ برد ولی واقعیت ۵۰٪ است ⇒ باید از پایه بدتر باشد.
        val points = List(40) { i ->
            NeuralCalibrationPoint(predictedWinProb = 1.0, won = i % 2 == 0, atMs = i.toLong())
        }
        val report = NeuralCalibration.evaluate(points)
        assertTrue(report.brierGainVsBaseline < 0.0)
    }

    @Test
    fun `informative predictions beat the baseline`() {
        // پیش‌بینی ۰٫۹ برای بردهای واقعی و ۰٫۱ برای باخت‌ها ⇒ باید بهتر از پایه باشد.
        val points = (0 until 60).map { i ->
            val won = i % 2 == 0
            NeuralCalibrationPoint(
                predictedWinProb = if (won) 0.9 else 0.1,
                won = won,
                atMs = i.toLong(),
            )
        }
        val report = NeuralCalibration.evaluate(points)
        assertTrue(report.brierGainVsBaseline > 0.0)
        assertTrue(report.verdictFa.contains("بهتر از پایه"))
    }

    @Test
    fun `out of sample split is chronological`() {
        val points = (0 until 100).map { i ->
            NeuralCalibrationPoint(predictedWinProb = 0.6, won = i % 3 == 0, atMs = i.toLong())
        }
        val report = NeuralCalibration.evaluate(points, oosFraction = 0.30)
        assertEquals(100, report.samples)
        assertEquals(30, report.oosSamples)
    }

    @Test
    fun `regime bias reports optimism per state`() {
        val points = (0 until 30).map { i ->
            NeuralCalibrationPoint(
                predictedWinProb = 0.8,
                won = i < 15,
                stateCode = "ASIA · GENESIS",
                atMs = i.toLong(),
            )
        }
        val row = NeuralCalibration.evaluate(points).regimes.single()
        assertEquals(30, row.samples)
        assertEquals(50.0, row.actualWinRatePct, 0.05)
        // پیش‌بینی ۵۰٪ ... میانگین پیش‌بینی ۰٫۸ = ۸۰٪ و واقعی ۵۰٪ ⇒ خوش‌بینی ۳۰ واحد.
        assertEquals(30.0, row.biasPct, 0.05)
    }

    // ── ۴. بردار نهایی شبکه: ۱۶ + ۸ و ناسازگاری ابعاد ─────────────────────────

    @Test
    fun `network input dimension is sixteen plus eight state features`() {
        assertEquals(24, NeuralSelfLearningEngine.INPUT_DIM)
        assertEquals(16, NeuralSelfLearningEngine.LEGACY_INPUT_DIM)
        assertEquals(
            NeuralSelfLearningEngine.INPUT_DIM,
            NeuralSelfLearningEngine.LEGACY_INPUT_DIM + NeuralStateFeatures.COUNT,
        )
    }
}
