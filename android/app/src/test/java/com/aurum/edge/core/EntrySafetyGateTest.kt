package com.aurum.edge.core

import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست‌های سیاست گیت عصبی پیش‌رونده و دروازهٔ ایمنی ورود.
 *
 * ریشهٔ این تست‌ها گزارش واقعی ۱۶۰ معامله است: گیت با شرط ۱۰٬۰۰۰ معامله هرگز وارد مدار نشد،
 * هیچ سقف ریسک روزانه‌ای در مسیر معامله نبود و ماشه‌های کهنه هفته‌ها بعد پر می‌شدند.
 */
class EntrySafetyGateTest {

    private fun trade(
        id: String,
        symbol: String = "BTC/USDT",
        open: Boolean = true,
    ) = PaperTrade(
        id = id,
        symbol = symbol,
        interval = Interval.M15,
        action = SignalAction.BUY,
        entry = 90_000.0,
        stopLoss = 89_400.0,
        takeProfit = 91_400.0,
        riskReward = 2.0,
        confidence = 85.0,
        openedAt = 1_760_000_000_000L,
        closedAt = if (open) null else 1_760_000_600_000L,
        pnlUsd = if (open) null else -5.0,
        fixedRiskUsd = 5.0,
    )

    private val now = 1_760_000_000_000L

    // ─────────────────────────── سیاست گیت ───────────────────────────

    @Test
    fun `gate stage escalates with sample count`() {
        assertEquals(NeuralGatePolicy.Stage.PROTECTIVE, NeuralGatePolicy.Stage.forSamples(1))
        assertEquals(NeuralGatePolicy.Stage.PROTECTIVE, NeuralGatePolicy.Stage.forSamples(29))
        assertEquals(NeuralGatePolicy.Stage.BALANCED, NeuralGatePolicy.Stage.forSamples(30))
        assertEquals(NeuralGatePolicy.Stage.BALANCED, NeuralGatePolicy.Stage.forSamples(149))
        assertEquals(NeuralGatePolicy.Stage.STRICT, NeuralGatePolicy.Stage.forSamples(150))
        assertEquals(NeuralGatePolicy.Stage.STRICT, NeuralGatePolicy.Stage.forSamples(10_000))
    }

    @Test
    fun `gate is inert before any sample exists even with user consent`() {
        val d = NeuralGatePolicy.decide(
            learnedTotal = 0,
            userActivationEnabled = true,
            winProbabilityPct = 2.0,
            expectedR = -1.0,
            clusterFlaggedNoTrade = true,
            clusterSamples = 50,
            tunedGatePct = 62.0,
        )
        assertFalse(d.isActive)
        assertFalse(d.blocksTrade)
    }

    @Test
    fun `unsigned gate only warns and never blocks`() {
        val d = NeuralGatePolicy.decide(
            learnedTotal = 160,
            userActivationEnabled = false,
            winProbabilityPct = 1.0,
            expectedR = -1.0,
            clusterFlaggedNoTrade = true,
            clusterSamples = 20,
            tunedGatePct = 62.0,
        )
        assertFalse(d.isActive)
        assertFalse(d.blocksTrade)
    }

    @Test
    fun `blocked cluster blocks from the very first sample once consented`() {
        val d = NeuralGatePolicy.decide(
            learnedTotal = 1,
            userActivationEnabled = true,
            winProbabilityPct = 60.0,
            expectedR = 0.40,
            clusterFlaggedNoTrade = true,
            clusterSamples = NeuralGatePolicy.MIN_CLUSTER_SAMPLES,
            tunedGatePct = 62.0,
        )
        assertTrue(d.isActive)
        assertTrue("خوشهٔ ممنوعه باید با شواهد کافی مسدود شود", d.blocksTrade)
        assertTrue(d.reasonFa.contains("منطقهٔ ممنوعه"))
    }

    @Test
    fun `cluster flag without enough evidence does not block`() {
        val d = NeuralGatePolicy.decide(
            learnedTotal = 1,
            userActivationEnabled = true,
            winProbabilityPct = 90.0,
            expectedR = 1.0,
            clusterFlaggedNoTrade = true,
            clusterSamples = 1,
            tunedGatePct = 62.0,
        )
        assertFalse(d.blocksTrade)
    }

    @Test
    fun `low measured win probability blocks once consented`() {
        // همان چیزی که در گزارش دیده شد: احتمال برد ۲٪ و انتظار منفی
        val d = NeuralGatePolicy.decide(
            learnedTotal = 160,
            userActivationEnabled = true,
            winProbabilityPct = 2.0,
            expectedR = -0.88,
            clusterFlaggedNoTrade = false,
            clusterSamples = 0,
            tunedGatePct = 62.0,
        )
        assertTrue(d.blocksTrade)
        assertTrue(d.reasonFa.contains("احتمال برد"))
    }

    @Test
    fun `tuned gate percentage raises the floor but never lowers the stage floor`() {
        val strict = NeuralGatePolicy.decide(
            learnedTotal = 500,
            userActivationEnabled = true,
            winProbabilityPct = 50.0,
            expectedR = 0.30,
            clusterFlaggedNoTrade = false,
            clusterSamples = 0,
            tunedGatePct = 62.0,
        )
        // ۶۲ × ۰٫۸۴ = ۵۲٫۰۸ ⇒ کف بالاتر از کف پلهٔ سخت‌گیر (۴۶)
        assertEquals(52.08, strict.winProbFloorPct, 0.01)
        assertTrue(strict.blocksTrade)

        val withLowTuning = NeuralGatePolicy.decide(
            learnedTotal = 500,
            userActivationEnabled = true,
            winProbabilityPct = 50.0,
            expectedR = 0.30,
            clusterFlaggedNoTrade = false,
            clusterSamples = 0,
            tunedGatePct = 10.0,
        )
        assertEquals(NeuralGatePolicy.Stage.STRICT.winProbFloorPct, withLowTuning.winProbFloorPct, 0.0001)
        assertFalse(withLowTuning.blocksTrade)
    }

    // ─────────────────────────── دروازهٔ ایمنی ورود ───────────────────────────

    @Test
    fun `clean setup passes the safety gate`() {
        val d = EntrySafetyGate.evaluate(
            symbol = "BTC/USDT",
            interval = Interval.M15,
            openTrades = emptyList(),
            nowMillis = now,
            signalBarTime = now - Interval.M15.millis / 2,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.2,
            riskStatus = null,
            enforceSession = false,
        )
        assertTrue(d.allowed)
    }

    @Test
    fun `concurrent position ceiling blocks the fourth trade`() {
        val open = listOf(trade("a", "BTC/USDT"), trade("b", "ETH/USDT"), trade("c", "XAU/USD"))
        val d = EntrySafetyGate.evaluate(
            symbol = "SOL/USDT",
            interval = Interval.M15,
            openTrades = open,
            nowMillis = now,
            signalBarTime = now,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            enforceSession = false,
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.contains("سقف پوزیشن باز هم‌زمان") })
    }

    @Test
    fun `per-symbol ceiling blocks a second trade on the same symbol`() {
        val d = EntrySafetyGate.evaluate(
            symbol = "btc/usdt",
            interval = Interval.M5,
            openTrades = listOf(trade("a", "BTC/USDT")),
            nowMillis = now,
            signalBarTime = now,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            enforceSession = false,
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.contains("پوزیشن باز دارد") })
    }

    @Test
    fun `daily loss breach blocks entry`() {
        val status = RiskLimitStatus(
            accountBalance = 80_000.0,
            initialCapital = 100_000.0,
            dayOpeningEquity = 95_000.0,
            dailyLossLimitPercent = 5.0,
            dailyLossLimitUsd = 4_750.0,
            todayRealizedPnlUsd = -6_000.0,
            openTradesRiskUsd = 0.0,
            remainingDailyRiskUsd = 0.0,
            maxDrawdownLimitPercent = 20.0,
            maxDrawdownLimitUsd = 20_000.0,
            currentDrawdownUsd = 20_000.0,
            remainingDrawdownUsd = 0.0,
            isDailyLimitBreached = true,
            isOverallDrawdownBreached = false,
        )
        val d = EntrySafetyGate.evaluate(
            symbol = "BTC/USDT",
            interval = Interval.M15,
            openTrades = emptyList(),
            nowMillis = now,
            signalBarTime = now,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = status,
            enforceSession = false,
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.contains("سقف ضرر روزانه") })
    }

    @Test
    fun `stale trigger is rejected`() {
        val d = EntrySafetyGate.evaluate(
            symbol = "BTC/USDT",
            interval = Interval.M15,
            openTrades = emptyList(),
            nowMillis = now,
            // ۵ ساعت کهنه = ۲۰ کندل ۱۵ دقیقه‌ای
            signalBarTime = now - 5 * 3_600_000L,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            enforceSession = false,
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.contains("ماشه کهنه") })
    }

    @Test
    fun `stale detection tolerates unknown bar time`() {
        assertFalse(
            EntrySafetyGate.isSignalStale(
                signalBarTime = null,
                interval = Interval.M15,
                nowMillis = now,
            ),
        )
        assertFalse(
            EntrySafetyGate.isSignalStale(
                signalBarTime = 0L,
                interval = Interval.M15,
                nowMillis = now,
            ),
        )
        assertTrue(
            EntrySafetyGate.isSignalStale(
                signalBarTime = now - 4 * Interval.M15.millis,
                interval = Interval.M15,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `blocked asian session is rejected`() {
        val utcNow = 1_760_010_000_000L
        val hour = EntrySafetyGate.utcHourOf(utcNow)
        val expectedBlocked = hour in EntrySafetyGate.DEFAULT_BLOCKED_SESSIONS_UTC_HOURS
        val d = EntrySafetyGate.evaluate(
            symbol = "BTC/USDT",
            interval = Interval.M15,
            openTrades = emptyList(),
            nowMillis = utcNow,
            signalBarTime = utcNow,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            enforceSession = true,
        )
        assertEquals(!expectedBlocked, d.allowed)
    }

    @Test
    fun `fee that eats the risk budget is rejected`() {
        val d = EntrySafetyGate.evaluate(
            symbol = "DOW",
            interval = Interval.M5,
            openTrades = emptyList(),
            nowMillis = now,
            signalBarTime = now,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 2.0,
            riskStatus = null,
            enforceSession = false,
        )
        assertFalse(d.allowed)
        assertTrue(d.reasonsFa.any { it.contains("هزینهٔ رفت‌وبرگشت") })
    }

    @Test
    fun `restricted index is blocked on low timeframes only`() {
        fun run(interval: Interval) = EntrySafetyGate.evaluate(
            symbol = "DOW",
            interval = interval,
            openTrades = emptyList(),
            nowMillis = now,
            signalBarTime = now,
            proposedRiskUsd = 5.0,
            roundTripFeeUsd = 0.1,
            riskStatus = null,
            enforceSession = false,
        )
        assertFalse("DOW روی ۵ دقیقه نباید مجاز باشد", run(Interval.M5).allowed)
        assertFalse("DOW روی ۱۵ دقیقه نباید مجاز باشد", run(Interval.M15).allowed)
        assertTrue("DOW روی ۱ ساعت مجاز است", run(Interval.H1).allowed)
        // نمادهای دیگر محدود نیستند
        assertTrue(
            EntrySafetyGate.evaluate(
                symbol = "BTC/USDT",
                interval = Interval.M5,
                openTrades = emptyList(),
                nowMillis = now,
                signalBarTime = now,
                proposedRiskUsd = 5.0,
                roundTripFeeUsd = 0.1,
                riskStatus = null,
                enforceSession = false,
            ).allowed,
        )
    }

    @Test
    fun `session label maps utc hours to named sessions`() {
        assertEquals("هم‌پوشانی لندن–نیویورک", EntrySafetyGate.sessionLabelFa(13))
        assertEquals("لندن", EntrySafetyGate.sessionLabelFa(9))
        assertEquals("نیویورک", EntrySafetyGate.sessionLabelFa(18))
        assertEquals("آسیا", EntrySafetyGate.sessionLabelFa(3))
    }
}
