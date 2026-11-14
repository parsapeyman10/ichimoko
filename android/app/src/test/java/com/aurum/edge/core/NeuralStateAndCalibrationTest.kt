package com.aurum.edge.core

import java.io.File
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
        assertEquals("", NeuralStateFeatures.sessionCodeOf(-1L))
        assertEquals(0.0, NeuralStateFeatures.build(NeuralStateInputs(entryTimeMs = -1L))[6], 0.0)
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
        assertTrue("گزارش باید بگوید مدل از پایه بهتر است، بود: ${report.verdictFa}",
            report.verdictFa.contains("بهتر از پایه"))
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

    // ── ۴. اعتبارسنجی گام‌به‌گام ───────────────────────────────────────────────

    private fun foldPoints(seed: Int, size: Int, predictedForWin: Double, predictedForLoss: Double): List<NeuralCalibrationPoint> =
        (0 until size).map { i ->
            val won = (i + seed) % 2 == 0
            NeuralCalibrationPoint(
                predictedWinProb = if (won) predictedForWin else predictedForLoss,
                won = won,
                stateCode = "LONDON · CORE_EXPANSION",
                atMs = (seed * 10_000L) + i,
            )
        }

    @Test
    fun `walk forward aggregates only folds with enough test samples`() {
        val folds = listOf(
            foldPoints(0, 20, 0.85, 0.15),
            foldPoints(1, 20, 0.85, 0.15),
            foldPoints(2, 20, 0.85, 0.15),
            foldPoints(3, 5, 0.85, 0.15), // نمونهٔ کم ⇒ باید کنار گذاشته شود
        )
        val report = NeuralCalibration.evaluateWalkForward(folds)
        assertEquals(3, report.usableFolds)
        assertEquals(3, report.folds.size)
        assertTrue("پیش‌بینی آموزنده باید از پایه بهتر باشد", report.meanGain > 0.0)
        assertEquals(3, report.winsAgainstBaseline)
        assertTrue("گزارش باید بگوید در همهٔ فولدها بهتر از پایه بود، بود: ${report.verdictFa}",
            report.verdictFa.contains("بهتر بود"))
    }

    @Test
    fun `walk forward says the result is unstable when a fold fails`() {
        val folds = listOf(
            foldPoints(0, 20, 0.85, 0.15),   // خوب
            foldPoints(1, 20, 1.0, 1.0),     // بدتر از پایه (اطمینان کاذب)
        )
        val report = NeuralCalibration.evaluateWalkForward(folds)
        assertEquals(2, report.usableFolds)
        assertEquals(1, report.winsAgainstBaseline)
        assertTrue("بدترین فولد باید منفی باشد", report.worstGain < 0.0)
        assertTrue(report.verdictFa.contains("پایدار نیست"))
    }

    @Test
    fun `walk forward publishes nothing when no fold is big enough`() {
        val report = NeuralCalibration.evaluateWalkForward(listOf(foldPoints(0, 4, 0.9, 0.1)))
        assertEquals(0, report.usableFolds)
        assertFalse(report.meanBrier.isFinite())
        assertTrue(report.verdictFa.contains("نمونهٔ کافی"))
    }

    // ── ۵. مسیر کامل: آموزش از ژورنال + پایدارسازی سنجش ──────────────────────

    private fun journalEngine(tag: String): NeuralSelfLearningEngine {
        val dir = File(System.getProperty("java.io.tmpdir"), "aurum-journal-$tag-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return NeuralSelfLearningEngine(context = null, storageFile = File(dir, "neural_v1.json"))
    }

    private fun closedTrade(
        index: Int,
        won: Boolean,
        withMarketState: Boolean,
        baseMs: Long = 1_700_000_000_000L,
    ): PaperTrade = PaperTrade(
        id = "J-$index",
        symbol = if (index % 2 == 0) "XAU/USD" else "EUR/USD",
        interval = com.aurum.edge.domain.Interval.M15,
        action = com.aurum.edge.domain.SignalAction.BUY,
        entry = 2000.0,
        stopLoss = 1990.0,
        takeProfit = 2020.0,
        confidence = 82.0,
        riskReward = 2.0,
        openedAt = baseMs + index * 900_000L,
        closedAt = baseMs + index * 900_000L + 600_000L,
        exitPrice = if (won) 2020.0 else 1990.0,
        exitReason = if (won) "TAKE_PROFIT" else "STOP_LOSS",
        pnlUsd = if (won) 1.8 else -1.0,
        fixedRiskUsd = 1.0,
        initialStopLoss = 1990.0,
        optimalRiskReward = 2.0,
        // نیمی از نمونه‌ها حالت بازار واقعی دارند و نیمی ندارند؛ عدد صفر یعنی «بی‌طرف».
        stateEr14 = if (withMarketState) 0.62 else -1.0,
        stateAtrPctOfPrice = if (withMarketState) 0.42 else -1.0,
        stateVolumeRatio20 = if (withMarketState) 1.35 else -1.0,
    )

    @Test
    fun `journal training learns from wins and losses and persists the measurement`() {
        val engine = journalEngine("full")
        val trades = (0 until 40).map { i -> closedTrade(i, won = i % 2 == 0, withMarketState = i % 2 == 0) }
        val report = engine.trainFromJournal(trades, nowMs = 1_760_000_000_000L)

        assertEquals(40, report.usableSamples)
        assertEquals(20, report.inSample.wins)
        assertEquals("نیمی از نمونه‌ها حالت بازار دارند", 20, report.withStateData)
        assertTrue("یادگیری واقعی باید شمارنده را جلو ببرد", report.newlyLearned > 0)
        assertEquals(3, report.walkForward.usableFolds)

        // سنجش باید در حافظهٔ شبکه پایدار شده باشد، نه فقط در حافظهٔ صفحه.
        val saved = engine.state.value.lastJournalCalibration
        assertTrue(saved.isMeasured)
        assertEquals(40, saved.samples)
        assertEquals(20, saved.wins)
        assertEquals(1_760_000_000_000L, saved.measuredAtMs)
        assertTrue(saved.verdictFa.isNotBlank())
        assertTrue(saved.walkForwardVerdictFa.isNotBlank())
        // عدد نامعلوم هرگز با NaN در فایل ذخیره نمی‌شود؛ پرچم `has*` جایش را می‌گیرد.
        assertTrue(saved.brier.isFinite())
        assertTrue(saved.walkForwardGain.isFinite())
    }

    @Test
    fun `journal training refuses metrics when the journal is tiny`() {
        val engine = journalEngine("tiny")
        val trades = (0 until 7).map { i -> closedTrade(i, won = i % 2 == 0, withMarketState = true) }
        val report = engine.trainFromJournal(trades, nowMs = 1_760_000_000_000L)

        assertEquals(7, report.usableSamples)
        assertFalse("با ۷ نمونه هیچ Brier اعلام نمی‌شود", report.inSample.brier.isFinite())
        val saved = engine.state.value.lastJournalCalibration
        assertTrue(saved.isMeasured)
        assertFalse("پرچم Brier باید صریحاً نامعلوم باشد", saved.hasBrier)
        assertFalse(saved.brierOrNaN.isFinite())
        assertEquals(0, report.walkForward.usableFolds)
        assertTrue("خلاصه باید تعداد واقعی نمونه را داشته باشد، بود: ${report.summaryFa}",
            report.summaryFa.contains("7 نمونه"))
    }

    // ── ۶. بردار نهایی شبکه: ۱۶ + ۸ و ناسازگاری ابعاد ─────────────────────────

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
