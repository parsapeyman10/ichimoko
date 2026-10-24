package com.aurum.edge.engine

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.core.TechnicalEvidence
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

class Als5GoldEngineTest {
    private val now = Instant.parse("2026-10-14T14:00:00Z").toEpochMilli()
    private val step = Interval.M5.millis
    private val totalBars = HistoryPolicy.LIVE_MIN_CANDLES

    /** Independently generated M15 series (ALS-5 never derives it from M5). */
    private fun m15Bars(): List<Candle> = (0 until 120).map { i ->
        val time = now - (120 - i) * Interval.M15.millis
        val price = 2400.0 + sin(i / 2.0) * 3.0
        Candle(time, price, price + 1.2, price - 1.2, price + 0.2, 100.0, true)
    }

    @Test fun `confirmed pivots never use future bars before rightBars close`() {
        val bars = (0 until 20).map { i ->
            val high = if (i == 10) 2450.0 else 2400.0 + (i % 2)
            val low = if (i == 10) 2390.0 else 2395.0 - (i % 2)
            Candle(now - (20 - i) * step, 2398.0, high, low, 2399.0, 100.0, true)
        }
        // At index 12 (only 2 right bars after index 10), a 3-right-bar pivot at index 10 must NOT be known
        val beforeConfirm = Als5GoldEngine.confirmedSwings(bars, leftBars = 3, rightBars = 3, upToIndex = 12)
        assertTrue(beforeConfirm.none { it.index == 10 })

        // At index 13 (3 right bars closed), pivot at index 10 is confirmed
        val afterConfirm = Als5GoldEngine.confirmedSwings(bars, leftBars = 3, rightBars = 3, upToIndex = 13)
        val highPivot = afterConfirm.firstOrNull { it.index == 10 && it.isHigh }
        assertNotNull(highPivot)
        assertEquals(2450.0, highPivot!!.price, 1e-6)
        assertEquals(13, highPivot.confirmedAtIndex)
    }

    @Test fun `position sizing uses 0_25 percent equity risk and respects broker contract specs`() {
        val config = Als5GoldEngine.Config(
            accountEquityUsd = 10_000.0,
            riskPerTradePct = 0.25, // $25 risk budget
            contractSizeOz = 100.0,
            minLot = 0.01,
            lotStep = 0.01,
            spreadUsdPerOz = 0.16,
            commissionPerLotSideUsd = 3.50, // $0.07/oz round-trip
            slippageUsdPerOz = 0.05,
        )
        // Stop distance = $2.00/oz + $0.28/oz costs = $2.28/oz -> $25 / $2.28 = 10.96 oz -> 0.10 lots (10 oz)
        val (lots, oz) = Als5GoldEngine.calculatePositionSize(2400.0, 2398.0, config)
        assertEquals(0.10, lots, 1e-6)
        assertEquals(10.0, oz, 1e-6)

        // Excessively wide stop ($50/oz) on $10,000 equity with 0.25% ($25) risk -> < 0.01 minLot -> 0.0
        val (tooWideLots, _) = Als5GoldEngine.calculatePositionSize(2400.0, 2350.0, config)
        assertEquals(0.0, tooWideLots, 1e-6)
    }

    @Test fun `daily loss cap and consecutive loss guard block new entries`() {
        val bars = (0 until totalBars).map { i ->
            val t = now - (totalBars - i) * step
            val p = 2400.0 + sin(i / 6.0) * 3.0
            Candle(t, p, p + 1.2, p - 1.2, p + 0.2, 100.0, true)
        }
        val dailyCapped = Als5GoldEngine.evaluate(
            bars, Interval.M5, "XAU/USD", 80.0,
            external = mapOf(Interval.M15 to m15Bars()),
            config = Als5GoldEngine.Config(dailyRealizedLossPct = 1.0, maxDailyLossPct = 1.0),
            now = now,
        )
        assertEquals(SignalAction.NO_TRADE, dailyCapped.action)
        assertTrue(dailyCapped.blockers.any { it.contains("سقف ضرر روزانه") })

        val consecBlocked = Als5GoldEngine.evaluate(
            bars, Interval.M5, "XAU/USD", 80.0,
            external = mapOf(Interval.M15 to m15Bars()),
            config = Als5GoldEngine.Config(consecutiveLosses = 3, maxConsecutiveLosses = 3),
            now = now,
        )
        assertEquals(SignalAction.NO_TRADE, consecBlocked.action)
        assertTrue(consecBlocked.blockers.any { it.contains("ضرر متوالی") })

    }

    @Test fun `SignalEngine routes XAU_USD through Als5GoldEngine and produces ALS5 opportunity record`() {
        val bars = (0 until totalBars).map { i ->
            val t = now - (totalBars - i) * step
            val wave = sin(i / 4.0) * 2.5
            val base = 2400.0 + i * 0.03 + wave
            Candle(t, base - 0.3, base + 1.1, base - 1.1, base + 0.4, 150.0, true)
        }
        val signal = SignalEngine.evaluate(bars, Interval.M5, 80.0, "XAU/USD",
            timeframes = mapOf(Interval.M15 to m15Bars()))
        assertNotNull(signal.als5Opportunity)
        assertEquals("ALS-5", signal.als5Opportunity!!.strategyName)
        assertEquals(4, signal.confluence.size)
        if (signal.isActionable) {
            assertTrue(TechnicalEvidence.confirmed(signal))
        }
    }

    @Test fun `London and New York session windows follow daylight saving transitions`() {
        val config = Als5GoldEngine.Config(
            allowAsianSession = false,
            allowLondonSession = false,
            allowNewYorkSession = true,
            newYorkStartHourLocal = 8,
            newYorkEndHourLocal = 16,
        )
        val winter1230Utc = Instant.parse("2026-01-15T12:30:00Z").toEpochMilli()
        val summer1230Utc = Instant.parse("2026-06-15T12:30:00Z").toEpochMilli()
        assertFalse(Als5GoldEngine.sessionAllowed(winter1230Utc, config)) // New York 07:30 EST
        assertTrue(Als5GoldEngine.sessionAllowed(summer1230Utc, config))  // New York 08:30 EDT
    }

    @Test fun `paper policy enforces ALS5 one-trade and daily risk caps and setup de-duplication`() {
        val overTradeCap = PaperPortfolioPolicy.blocker(
            trades = emptyList(), symbol = "XAU/USD", balance = 1_000.0, addedRiskUsd = 2.51,
            maxRiskPerTradePct = 0.0025, maxDailyLossPct = 0.01,
        )
        assertTrue(overTradeCap.orEmpty().contains("ریسک هر معامله"))

        val priorLoss = PaperTrade("today-loss", "XAU/USD", Interval.M5, SignalAction.BUY,
            2_400.0, 2_398.0, 2_404.0, 100.0, 2.0, now - 60_000L,
            closedAt = now - 1_000L, pnlUsd = -8.0, positionOz = 1.0)
        val overDailyCap = PaperPortfolioPolicy.blocker(
            trades = listOf(priorLoss), symbol = "XAU/USD", balance = 1_000.0, addedRiskUsd = 2.1,
            now = now, maxRiskPerTradePct = 0.0025, maxDailyLossPct = 0.01, openingBalance = 1_000.0,
        )
        assertTrue(overDailyCap.orEmpty().contains("ضرر روزانه"))

        val prior = PaperTrade("als5", "XAU/USD", Interval.M5, SignalAction.BUY,
            2_400.0, 2_398.0, 2_404.0, 100.0, 2.0, now - 60_000L,
            closedAt = now - 1_000L, pnlUsd = 0.0,
            positionOz = 1.0, als5SetupKey = "A|BUY|confirmed-pivot")
        val duplicate = PaperPortfolioPolicy.blocker(
            trades = listOf(prior), symbol = "XAU/USD", balance = 10_000.0,
            addedRiskUsd = 10.0, als5SetupKey = "A|BUY|confirmed-pivot",
        )
        assertTrue(duplicate.orEmpty().contains("ستاپ ساختاری"))
    }
}
