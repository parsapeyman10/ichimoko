package com.aurum.edge.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های «زمان‌بندی سشن» و «یادگیری زمان خروج».
 *
 * این تست‌ها دقیقاً همان ایراد کاربر را قفل می‌کنند: نسخهٔ ۳.۹.۲۸ پوزیشن DOW را وقتی بازار بسته
 * بود با قیمت کهنه می‌بست. رفتار درست این است که:
 *  - در بازار باز و **پیش از** پایان سشن ببندد؛
 *  - در بازار بسته هیچ کاری نکند و تا بازگشایی صبر کند؛
 *  - زمان سربار را از نتیجهٔ واقعی بازار یاد بگیرد.
 */
class SessionExitTimingTest {

    private fun ny(y: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(y, month, day, hour, minute, 0, 0, ZoneId.of("America/New_York"))
            .toInstant()
            .toEpochMilli()

    // ─────────────── برنامه‌ریز سشن ───────────────

    @Test
    fun `open position closes inside the pre-close window while the market is still open`() {
        val plan = SessionExitPlanner.plan("DOW", ny(2026, 10, 26, 15, 50), hasOpenPosition = true)
        assertFalse(plan.closed)
        assertEquals(12, plan.leadMinutes)
        assertTrue("10 minutes to close is inside the 12 minute buffer", plan.shouldCloseNow)
        assertTrue("no new entries inside the exit window", plan.shouldBlockEntry)
    }

    @Test
    fun `open position is left alone earlier in the session`() {
        val plan = SessionExitPlanner.plan("DOW", ny(2026, 10, 26, 14, 0), hasOpenPosition = true)
        assertFalse(plan.closed)
        assertFalse(plan.shouldCloseNow)
        assertFalse(plan.shouldBlockEntry)
        assertEquals(120L, plan.minutesToClose)
        assertEquals(108L, plan.minutesToExit)
    }

    @Test
    fun `closed market never triggers a close - the engine waits for the reopen`() {
        val plan = SessionExitPlanner.plan(
            "DOW",
            ny(2026, 10, 26, 19, 0),
            hasOpenPosition = true,
            carriedOverClosure = true,
        )
        assertTrue(plan.closed)
        assertFalse("a closed market must never force a close at a stale price", plan.shouldCloseNow)
        assertTrue(plan.carriedOverClosure)
        assertTrue("entries are blocked while the market is closed", plan.shouldBlockEntry)
        assertNull(plan.minutesToClose)
    }

    @Test
    fun `crypto has no session buffer around the clock`() {
        val plan = SessionExitPlanner.plan("BTC/USDT", ny(2026, 10, 26, 19, 0), hasOpenPosition = true)
        assertFalse(plan.closed)
        assertFalse(plan.shouldCloseNow)
        assertFalse(plan.shouldBlockEntry)
        assertEquals(0, plan.leadMinutes)
        assertNull(plan.minutesToClose)
    }

    @Test
    fun `learned lead time replaces the default`() {
        // یک ساعت تا بسته شدن؛ با سربار آموخته‌شدهٔ ۳۰ دقیقه هنوز ۳۰ دقیقه فرصت دارد.
        val stillOpen = SessionExitPlanner.plan(
            "DOW",
            ny(2026, 10, 26, 15, 0),
            learnedLeadMinutes = 30,
            hasOpenPosition = true,
        )
        assertEquals(30, stillOpen.leadMinutes)
        assertEquals(30L, stillOpen.minutesToExit)
        assertFalse(stillOpen.shouldCloseNow)
        assertFalse(stillOpen.shouldBlockEntry)

        // ۲۰ دقیقه تا بسته شدن؛ با همان سربار ۳۰ دقیقه‌ای، پنجرهٔ خروج باز شده است.
        val inWindow = SessionExitPlanner.plan(
            "DOW",
            ny(2026, 10, 26, 15, 40),
            learnedLeadMinutes = 30,
            hasOpenPosition = true,
        )
        assertTrue(inWindow.shouldCloseNow)
        assertTrue(inWindow.shouldBlockEntry)
    }

    @Test
    fun `exit reason names the asset class and the live exit price`() {
        val plan = SessionExitPlanner.plan("DOW", ny(2026, 10, 26, 15, 55), hasOpenPosition = true)
        val reason = SessionExitPlanner.exitReasonFa(plan, 51_965.42)
        assertTrue(reason.contains("خروج خودکار"))
        assertTrue(reason.contains("51,965.42"))
    }

    // ─────────────── یادگیری زمان سربار ───────────────

    @Test
    fun `without observations the default lead time is returned`() {
        AssetClass.values().forEach { assetClass ->
            val estimate = SessionExitLearning.estimate(assetClass, emptyList())
            assertEquals(SessionExitPlanner.defaultLeadMinutes(assetClass), estimate.leadMinutes)
            assertEquals(0, estimate.samples)
        }
    }

    @Test
    fun `a single bad sample does not move the learned lead time`() {
        val observations = mutableListOf<SessionExitLearning.Observation>()
        repeat(6) {
            observations += SessionExitLearning.Observation("STOCK", 12, 25.0, 1_000L + it, "DOW")
        }
        observations += SessionExitLearning.Observation("STOCK", 30, -40.0, 2_000L, "DOW")
        val estimate = SessionExitLearning.estimate(AssetClass.STOCK, observations)
        assertEquals("the well-sampled default must win over one loud outlier", 12, estimate.leadMinutes)
    }

    @Test
    fun `a consistently better window is learned`() {
        val observations = mutableListOf<SessionExitLearning.Observation>()
        repeat(10) {
            observations += SessionExitLearning.Observation("STOCK", 20, 30.0, 1_000L + it, "DOW")
        }
        repeat(4) {
            observations += SessionExitLearning.Observation("STOCK", 12, 2.0, 500L + it, "DOW")
        }
        val estimate = SessionExitLearning.estimate(AssetClass.STOCK, observations)
        assertEquals(20, estimate.leadMinutes)
        assertEquals(14, estimate.samples)
        assertTrue(estimate.confidencePct in 1..100)
        assertTrue(estimate.noteFa.contains("20"))
    }

    @Test
    fun `estimates are per asset class`() {
        val observations = listOf(
            SessionExitLearning.Observation("STOCK", 20, 40.0, 10L, "DOW"),
            SessionExitLearning.Observation("STOCK", 20, 40.0, 11L, "DOW"),
            SessionExitLearning.Observation("STOCK", 20, 40.0, 12L, "DOW"),
            SessionExitLearning.Observation("FOREX", 5, 40.0, 13L, "EURUSD"),
            SessionExitLearning.Observation("FOREX", 5, 40.0, 14L, "EURUSD"),
            SessionExitLearning.Observation("FOREX", 5, 40.0, 15L, "EURUSD"),
        )
        assertEquals(20, SessionExitLearning.estimate(AssetClass.STOCK, observations).leadMinutes)
        assertEquals(5, SessionExitLearning.estimate(AssetClass.FOREX, observations).leadMinutes)
    }

    @Test
    fun `saved dollars have the correct sign for both directions`() {
        // فروش در ۵۲٬۰۰۰ بسته شد، بازار در ۵۱٬۸۰۰ باز شد ⇒ نگه‌داشتن بهتر بود
        assertEquals(-200.0, SessionExitLearning.savedUsd(52_000.0, 51_800.0, -1, 1.0), 1e-9)
        // خرید در ۱۰۰ بسته شد، بازار در ۹۵ باز شد ⇒ بستن بهتر بود
        assertEquals(10.0, SessionExitLearning.savedUsd(100.0, 95.0, 1, 2.0), 1e-9)
        assertEquals(0.0, SessionExitLearning.savedUsd(0.0, 95.0, 1, 1.0), 1e-9)
    }

    @Test
    fun `exploration can leave the current best arm and rewards are clamped`() {
        val observations = List(10) {
            SessionExitLearning.Observation("STOCK", 12, 30.0, 1_000L + it, "DOW")
        }
        assertEquals(12, SessionExitLearning.nextLeadMinutes(AssetClass.STOCK, observations, randomSample = 0.9))
        val explored = SessionExitLearning.nextLeadMinutes(AssetClass.STOCK, observations, randomSample = 0.01)
        assertTrue(SessionExitPlanner.LEAD_CANDIDATES.contains(explored))
        assertEquals(200.0, SessionExitLearning.clampReward(10_000.0, referenceUsd = 50.0), 1e-9)
        assertEquals(-200.0, SessionExitLearning.clampReward(-10_000.0, referenceUsd = 50.0), 1e-9)
    }

    @Test
    fun `observation memory is capped`() {
        var list = emptyList<SessionExitLearning.Observation>()
        repeat(600) {
            list = SessionExitLearning.appendObservation(
                list,
                SessionExitLearning.Observation("STOCK", 12, 1.0, it.toLong(), "DOW"),
                max = 100,
            )
        }
        assertEquals(100, list.size)
        assertEquals("newest observation survives", 599L, list.first().atMs)
    }

    @Test
    fun `candidate list always contains the default of the asset class`() {
        AssetClass.values().forEach { assetClass ->
            assertTrue(
                "default must be learnable, not invisible",
                SessionExitLearning.candidatesFor(assetClass)
                    .contains(SessionExitPlanner.defaultLeadMinutes(assetClass)),
            )
        }
    }

    // ─────────────── کنترل نرخ UI ───────────────

    @Test
    fun `ui coalescing publishes the first value and throttles the burst`() {
        val coalescer = UiUpdateCoalescer(minIntervalMs = 250L)
        assertTrue(coalescer.shouldPublish("DOW", 1_000L))
        assertFalse(coalescer.shouldPublish("DOW", 1_100L))
        assertFalse(coalescer.shouldPublish("DOW", 1_249L))
        assertTrue(coalescer.shouldPublish("DOW", 1_250L))
        assertTrue("independent keys do not starve each other", coalescer.shouldPublish("XAUUSD", 1_251L))
    }

    // ─────────────── بودجهٔ منابع ───────────────

    @Test
    fun `resource budget scales with device heap`() {
        val small = AppResourceGovernor.budgetFor(128L * 1024 * 1024)
        val large = AppResourceGovernor.budgetFor(512L * 1024 * 1024)
        assertTrue(small.maxArchiveRecords <= large.maxArchiveRecords)
        assertTrue(small.archiveBudgetBytes <= large.archiveBudgetBytes)
        assertEquals(1, small.maxConcurrentRenders)
        assertTrue(small.renderScale < 1.0f)
        assertEquals(1.0f, large.renderScale, 1e-6f)
    }

    @Test
    fun `records to keep respects the budget and the record cap`() {
        val budget = AppResourceGovernor.budgetFor(192L * 1024 * 1024)
        val keep = AppResourceGovernor.recordsToKeep(List(500) { 40_000L }, budget)
        assertTrue(keep in 1..budget.maxArchiveRecords)
        assertTrue(keep * 40_000L <= budget.archiveBudgetBytes)
        assertEquals(0, AppResourceGovernor.recordsToKeep(emptyList(), budget))
    }
}
