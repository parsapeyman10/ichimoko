package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * «عمق یادگیری» باید همهٔ حالت‌ها را بشمارد و در برابر قانون تنظیمات داوری کند — با عدد، نه ادعا.
 */
class NeuralDepthAuditTest {

    private val targets = NeuralDepthTargets(riskBudgetUsd = 5.0, dailyLossLimitUsd = 500.0)
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun sample(
        won: Boolean,
        r: Double,
        phase: String = "GENESIS",
        session: String = "LONDON",
        vol: String = "NORMAL",
        rr: String = "MID",
        feeShare: Double = 8.0,
        ageDays: Long = 1,
    ) = NeuralDepthSample(
        phaseCode = phase,
        sessionCode = session,
        volBand = vol,
        rrBand = rr,
        won = won,
        rMultiple = r,
        feeSharePct = feeShare,
        atMs = now - ageDays * day,
        weight = 0.5.pow((ageDays.toDouble()) / NeuralDepthReport.DEFAULT_HALF_LIFE_DAYS),
    )

    @Test
    fun `without samples nothing is claimed`() {
        val report = NeuralDepthAudit.evaluate(emptyList(), targets, nowMs = now)
        assertEquals(0, report.scoredTrades)
        assertEquals(NeuralDepthAudit.LATTICE_SIZE, report.latticeSize)
        assertTrue("بدون داده باید صریح گفته شود", report.verdictFa.contains("هیچ"))
        assertEquals(0.0, report.coveragePct, 1e-9)
    }

    @Test
    fun `breaks even win rate comes from the real average win and loss`() {
        // میانگین برد ۲R، میانگین باخت ۱R ⇒ نرخ برد لازم ۳۳٫۳٪
        assertEquals(1.0 / 3.0, NeuralDepthAudit.requiredWinRate(2.0, 1.0), 1e-9)
        assertTrue(NeuralDepthAudit.requiredWinRate(0.0, 1.0).isNaN())
        assertTrue(NeuralDepthAudit.requiredWinRate(2.0, 1.0).isFinite())
    }

    @Test
    fun `a proven losing state is blocked and a proven winning state is allowed`() {
        val losingRows = (1..20).map { i ->
            if (i <= 4) sample(true, 2.0, phase = "CHOP_RANGE", session = "ASIA", vol = "HIGH", rr = "TIGHT")
            else sample(false, -1.0, phase = "CHOP_RANGE", session = "ASIA", vol = "HIGH", rr = "TIGHT")
        }
        val winningRows = (1..20).map { i ->
            if (i <= 12) sample(true, 2.2, phase = "GENESIS", session = "NEW_YORK", vol = "NORMAL", rr = "MID")
            else sample(false, -1.0, phase = "GENESIS", session = "NEW_YORK", vol = "NORMAL", rr = "MID")
        }
        val report = NeuralDepthAudit.evaluate(losingRows + winningRows, targets, nowMs = now)

        val losing = report.worstCells.first { it.phaseCode == "CHOP_RANGE" }
        assertEquals(NeuralStateVerdict.LOSING, losing.verdict)
        assertEquals("حجم این حالت باید صفر شود", 0.0, losing.sizeMultiplier, 1e-9)

        val winning = report.bestCells.first { it.phaseCode == "GENESIS" }
        assertEquals(NeuralStateVerdict.PROFITABLE, winning.verdict)
        assertEquals(1.0, winning.sizeMultiplier, 1e-9)

        assertEquals(1, report.losingCells)
        assertEquals(1, report.provenCells)
        assertEquals(0.0, report.sizeMultiplierFor("CHOP_RANGE", "ASIA", "HIGH", "TIGHT"), 1e-9)
        assertEquals(1.0, report.sizeMultiplierFor("GENESIS", "NEW_YORK", "NORMAL", "MID"), 1e-9)
        // حالت ندیده = بی‌جریمه (شبکه نباید از روز اول همه‌چیز را ببندد)
        assertEquals(1.0, report.sizeMultiplierFor("LATE_EXHAUSTION", "LATE_NY", "LOW", "WIDE"), 1e-9)
    }

    @Test
    fun `too few samples means gathering, never profitable`() {
        val rows = listOf(sample(true, 2.0), sample(true, 2.0))
        val report = NeuralDepthAudit.evaluate(rows, targets, nowMs = now)
        assertEquals(1, report.cellsObserved)
        assertEquals(0, report.provenCells)
        assertEquals(1, report.gatheringCells)
        val cell = report.bestCells.firstOrNull()
        assertTrue("خانهٔ کم‌نمونه نباید سودده اعلام شود", cell == null || cell.verdict == NeuralStateVerdict.GATHERING)
    }

    @Test
    fun `heavy fees are their own verdict even with a decent win rate`() {
        val rows = (1..16).map { i ->
            if (i <= 5) sample(true, 1.6, feeShare = 45.0, vol = "HIGH", rr = "TIGHT")
            else sample(false, -1.0, feeShare = 45.0, vol = "HIGH", rr = "TIGHT")
        }
        val report = NeuralDepthAudit.evaluate(rows, targets, nowMs = now)
        val cell = report.cellFor("GENESIS", "LONDON", "HIGH", "TIGHT")
        assertTrue("خانه باید ساخته شود", cell != null)
        assertEquals(NeuralStateVerdict.BLOCKED_BY_FEE, cell!!.verdict)
        assertEquals(0.0, cell.sizeMultiplier, 1e-9)
        assertTrue("کارمزد سنگین باید در راننده‌های باخت باشد",
            report.lossDriversFa.any { it.contains("کارمزد") })
    }

    @Test
    fun `recent trades outweigh old ones because of the half life`() {
        val oldLosers = (1..10).map { sample(false, -1.0, ageDays = 150) }
        val freshWinners = (1..10).map { sample(true, 2.0, ageDays = 0) }
        val report = NeuralDepthAudit.evaluate(oldLosers + freshWinners, targets, nowMs = now)
        assertTrue("نرخ برد وزنی باید بالای ۵۰٪ باشد، بود: ${report.overallWinRatePct}", report.overallWinRatePct > 50.0)
        assertTrue("امید ریاضی باید مثبت باشد", report.overallExpectancyR > 0.0)
    }

    @Test
    fun `trades outside the rolling window are ignored`() {
        val ancient = sample(true, 2.0, ageDays = NeuralDepthReport.DEFAULT_WINDOW_DAYS + 10L)
        val report = NeuralDepthAudit.evaluate(listOf(ancient), targets, nowMs = now)
        assertEquals(0, report.scoredTrades)
        assertTrue(report.verdictFa.contains("هیچ"))
    }

    @Test
    fun `coverage counts the lattice cells that were actually seen`() {
        val rows = listOf(
            sample(true, 2.0, phase = "GENESIS", session = "LONDON"),
            sample(false, -1.0, phase = "GENESIS", session = "LONDON"),
            sample(true, 2.0, phase = "CHOP_RANGE", session = "ASIA", vol = "LOW", rr = "WIDE"),
        )
        val report = NeuralDepthAudit.evaluate(rows, targets, nowMs = now)
        assertEquals(2, report.cellsObserved)
        assertEquals(2.0 / NeuralDepthAudit.LATTICE_SIZE * 100.0, report.coveragePct, 0.01)
        assertTrue("خانه‌های ندیده باید فهرست شوند", report.unseenExamplesFa.isNotEmpty())
        assertTrue(report.lawProgressFa.any { it.contains("$5.00") })
        assertTrue(report.lawProgressFa.any { it.contains("نرخ برد لازم") })
    }

    @Test
    fun `journal trades are labelled and trades without a known risk are skipped and counted`() {
        val closedWin = PaperTrade(
            id = "ND-1",
            symbol = "XAU/USD",
            interval = Interval.M15,
            action = SignalAction.BUY,
            entry = 2_600.0,
            stopLoss = 2_590.0,
            takeProfit = 2_620.0,
            confidence = 90.0,
            riskReward = 2.0,
            openedAt = now - 2 * day,
            closedAt = now - day,
            pnlUsd = 10.0,
            fixedRiskUsd = 5.0,
            commissionUsd = 0.4,
            spreadCostUsd = 0.1,
            stateTrendPhaseCode = "GENESIS",
            stateAtrPctOfPrice = 0.22,
        )
        val closedLoss = closedWin.copy(
            id = "ND-2",
            pnlUsd = -5.0,
            stateTrendPhaseCode = "CHOP_RANGE",
            stateAtrPctOfPrice = 0.4,
        )
        val openTrade = closedWin.copy(id = "ND-3", closedAt = null, pnlUsd = null)
        val noRisk = closedWin.copy(id = "ND-4", fixedRiskUsd = 0.0)

        val report = NeuralDepthAudit.fromTrades(
            trades = listOf(closedWin, closedLoss, openTrade, noRisk),
            targets = targets,
            nowMs = now,
        )
        assertEquals(4, report.journalTrades)
        assertEquals(2, report.scoredTrades)
        assertEquals("معاملهٔ بدون ریسک باید شمرده شود", 1, report.skippedWithoutRisk)
        assertEquals(2, report.cellsObserved)
        assertEquals(50.0, report.overallWinRatePct, 1.0)
        assertTrue(report.avgWinR > 1.9 && report.avgWinR < 2.1)
        assertTrue(report.avgLossR > 0.9 && report.avgLossR < 1.1)

        val winCell = report.cellFor("GENESIS", NeuralDepthAudit.sessionCodeOf(closedWin.openedAt), "NORMAL", "MID")
        assertTrue("خانهٔ برد باید پیدا شود", winCell != null)
        assertEquals("GENESIS", winCell!!.phaseCode)

        // کارمزد ۰٫۵ دلار از ۵ دلار ریسک = ۱۰٪
        assertEquals(10.0, winCell.avgFeeSharePct, 0.5)
    }

    @Test
    fun `bands are labelled from real values and unknown stays unknown`() {
        assertEquals("LOW", NeuralDepthAudit.volBandOf(0.10))
        assertEquals("NORMAL", NeuralDepthAudit.volBandOf(0.30))
        assertEquals("HIGH", NeuralDepthAudit.volBandOf(0.80))
        assertEquals("UNKNOWN", NeuralDepthAudit.volBandOf(null))
        assertEquals("UNKNOWN", NeuralDepthAudit.volBandOf(-1.0))
        assertEquals("TIGHT", NeuralDepthAudit.rrBandOf(1.6))
        assertEquals("MID", NeuralDepthAudit.rrBandOf(2.2))
        assertEquals("WIDE", NeuralDepthAudit.rrBandOf(2.9))
        assertEquals("UNKNOWN", NeuralDepthAudit.rrBandOf(null))
        assertEquals("UNKNOWN", NeuralDepthAudit.phaseCodeOf("WHATEVER"))
        assertEquals("GENESIS", NeuralDepthAudit.phaseCodeOf("genesis"))
        assertFalse(NeuralDepthAudit.sessionCodeOf(-1L).isBlank())
    }

    @Test
    fun `random sample streams never produce NaN or out of range numbers`() {
        val random = kotlin.random.Random(7)
        repeat(60) {
            val rows = (1..random.nextInt(1, 90)).map {
                sample(
                    won = random.nextBoolean(),
                    r = if (random.nextBoolean()) random.nextDouble(0.2, 3.2) else random.nextDouble(-1.2, -0.1),
                    phase = NeuralDepthAudit.PHASES.random(random),
                    session = NeuralDepthAudit.SESSIONS.random(random),
                    vol = NeuralDepthAudit.VOL_BANDS.random(random),
                    rr = NeuralDepthAudit.RR_BANDS.random(random),
                    feeShare = random.nextDouble(0.0, 60.0),
                    ageDays = random.nextLong(0L, 400L),
                )
            }
            val report = NeuralDepthAudit.evaluate(rows, targets, nowMs = now)
            assertTrue(report.coveragePct.isFinite() && report.coveragePct in 0.0..100.0)
            assertTrue(report.overallWinRatePct in 0.0..100.0)
            assertTrue(report.overallExpectancyR.isFinite())
            assertTrue(report.requiredWinRatePct in 0.0..100.0)
            (report.bestCells + report.worstCells).forEach { cell ->
                assertTrue(cell.expectancyR.isFinite())
                assertTrue(cell.sizeMultiplier in 0.0..1.0)
                assertTrue(cell.samples > 0)
            }
            assertTrue(report.verdictFa.isNotBlank())
        }
    }
}
