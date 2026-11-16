package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمایشگاه استاپ (فاز ۰) — قواعد ریاضی باید بدون ابهام و بدون نگاه به آینده باشند.
 */
class StopAnchorCalibrationTest {

    private val interval = Interval.M15
    private val step = interval.millis
    private val entryIndex = 60
    private val entryTime = entryIndex * step

    private fun entry(
        entryPrice: Double = 100.0,
        engineStop: Double = 99.0,
        planR: Double = 2.0,
        isBuy: Boolean = true,
    ) = StopAnchorCalibration.Entry(
        tradeId = "T-1",
        tradeNumber = 1,
        strategyId = "BASE_ICHIMOKU",
        strategyTitleFa = "استراتژی بیس",
        symbol = "EUR/USD",
        interval = interval,
        entryTime = entryTime,
        entryPrice = entryPrice,
        isBuy = isBuy,
        plannedRiskReward = planR,
        engineStopLoss = engineStop,
        actualNetPnlUsd = 0.0,
        actualExitReasonFa = "",
    )

    /** کندل‌های آرام قبل از ورود: سقف ۱۰۰٫۴ و کف ۹۹٫۶. */
    private fun calmBars(count: Int = entryIndex): List<Candle> = (0 until count).map { i ->
        Candle(
            time = i * step,
            open = 100.0,
            high = 100.4,
            low = 99.6,
            close = 100.0,
            volume = 1_000.0,
            closed = true,
        )
    }

    @Test
    fun `kijun period follows the approved split of 26 intraday and 30 higher timeframes`() {
        assertEquals(26, StopAnchorCalibration.kijunPeriod(Interval.M5))
        assertEquals(26, StopAnchorCalibration.kijunPeriod(Interval.M15))
        assertEquals(30, StopAnchorCalibration.kijunPeriod(Interval.H1))
        assertEquals(30, StopAnchorCalibration.kijunPeriod(Interval.H4))
    }

    @Test
    fun `anchor stop never looks at candles that close after the entry`() {
        val before = calmBars()
        val withFuture = before + listOf(
            Candle(entryTime, 100.0, 130.0, 40.0, 120.0, 1_000.0, true),
            Candle(entryTime + step, 120.0, 200.0, 10.0, 190.0, 1_000.0, true),
        )
        val clean = StopAnchorCalibration.anchorStopDistance(
            entry = entry(),
            bars = before,
            mode = StopAnchorCalibration.AnchorMode.KIJUN_PLUS_FRACTAL_PLUS_WICK,
            bufferAtr = 0.38,
        )
        val polluted = StopAnchorCalibration.anchorStopDistance(
            entry = entry(),
            bars = withFuture,
            mode = StopAnchorCalibration.AnchorMode.KIJUN_PLUS_FRACTAL_PLUS_WICK,
            bufferAtr = 0.38,
        )
        assertNotNull(clean)
        assertEquals("کندل‌های آینده نباید در محاسبهٔ استاپ اثر بگذارند", clean!!, polluted!!, 1e-9)
    }

    @Test
    fun `bigger buffer pushes the stop further away in the sweep table`() {
        val bars = calmBars() + (0 until 100).map { i ->
            val t = entryTime + i * step
            Candle(t, 100.0, 100.5, 99.5, 100.0, 1_000.0, true)
        }
        val report = StopAnchorCalibration.sweep(
            entries = listOf(entry()),
            candlesBySymbol = { _, _ -> bars },
            buffers = listOf(0.26, 0.38, 0.52),
            modes = listOf(StopAnchorCalibration.AnchorMode.FIVE_BAR_FRACTAL),
        )
        val rows = report.rows
            .filter { it.anchor == StopAnchorCalibration.AnchorMode.FIVE_BAR_FRACTAL }
            .sortedBy { it.bufferAtr }
        assertEquals(3, rows.size)
        assertTrue(
            "با بافر بزرگ‌تر، فاصلهٔ استاپ (بر حسب ATR) باید بیشتر شود",
            rows[0].avgStopDistanceAtr < rows[1].avgStopDistanceAtr &&
                rows[1].avgStopDistanceAtr < rows[2].avgStopDistanceAtr,
        )
    }

    @Test
    fun `same-bar ambiguity is resolved as stop loss`() {
        val bars = calmBars() + listOf(
            Candle(entryTime + step, 100.0, 120.0, 80.0, 100.0, 1_000.0, true),
        )
        val outcome = StopAnchorCalibration.simulate(
            entry = entry(engineStop = 99.0),
            bars = bars,
            stopDistance = 1.0,
            planR = 2.0,
        )
        assertEquals(StopAnchorCalibration.OutcomeKind.STOPPED, outcome.kind)
        assertEquals(-1.0, outcome.rMultiple, 1e-9)
    }

    @Test
    fun `a trade rescued by the wider anchor is counted and a lost target is not`() {
        // استاپ فعلی موتور خیلی تنگ است (۰٫۲) و کندل بعدی آن را می‌زند.
        val bars = calmBars() + listOf(
            Candle(entryTime + step, 100.0, 100.2, 99.75, 99.9, 1_000.0, true),
            Candle(entryTime + 2 * step, 99.9, 100.6, 99.9, 100.5, 1_000.0, true),
            Candle(entryTime + 3 * step, 100.5, 103.0, 100.4, 102.9, 1_000.0, true),
        )
        val report = StopAnchorCalibration.sweep(
            entries = listOf(entry(engineStop = 99.8)),
            candlesBySymbol = { _, _ -> bars },
            buffers = listOf(0.38),
            modes = listOf(StopAnchorCalibration.AnchorMode.KIJUN_PLUS_FRACTAL_PLUS_WICK),
        )
        val baseline = report.rows.first { it.anchor == StopAnchorCalibration.AnchorMode.ENGINE_CURRENT }
        assertEquals("استاپ تنگ باید بخورد", 1, baseline.stopped)
        val wider = report.rows.first { it.anchor == StopAnchorCalibration.AnchorMode.KIJUN_PLUS_FRACTAL_PLUS_WICK }
        assertEquals(1, wider.samples)
        assertEquals("این معامله با لنگر پهن‌تر نجات پیدا می‌کند", 1, wider.rescuedFromStop)
        assertEquals("هدف از دست نرفته", 0, wider.lostTargets)
        assertTrue(wider.expectancyUsd > baseline.expectancyUsd)
    }

    @Test
    fun `metrics follow the five dollar contract`() {
        // دو معامله: یکی هدف ۲R و یکی استاپ ⇒ امید ریاضی (۲−۱)/۲ = ۰٫۵R = ۲٫۵ دلار.
        val winBars = calmBars() + listOf(
            Candle(entryTime + step, 100.0, 103.0, 99.9, 102.9, 1_000.0, true),
        )
        val loseBars = calmBars() + listOf(
            Candle(entryTime + step, 100.0, 100.1, 98.5, 98.6, 1_000.0, true),
        )
        val provider: (String, Interval) -> List<Candle> = { symbol, _ ->
            if (symbol == "EUR/USD") winBars else loseBars
        }
        val entries = listOf(
            entry(),
            entry().copy(tradeId = "T-2", symbol = "GBP/USD"),
        )
        val report = StopAnchorCalibration.sweep(
            entries = entries,
            candlesBySymbol = provider,
            buffers = listOf(0.0),
            modes = listOf(StopAnchorCalibration.AnchorMode.FIVE_BAR_FRACTAL),
        )
        val row = report.rows.first { it.anchor == StopAnchorCalibration.AnchorMode.FIVE_BAR_FRACTAL }
        assertEquals(2, row.samples)
        assertEquals(50.0, row.winRatePct, 0.01)
        assertEquals(2.0, row.profitFactor, 0.01)
        assertEquals(0.5, row.expectancyR, 0.001)
        assertEquals("۰٫۵R × ۵ دلار", 2.5, row.expectancyUsd, 0.01)
    }

    @Test
    fun `missing data produces no fabricated row`() {
        val report = StopAnchorCalibration.sweep(
            entries = listOf(entry()),
            candlesBySymbol = { _, _ -> emptyList() },
        )
        assertEquals(0, report.rows.size)
        assertEquals(1, report.entriesSkipped)
        assertEquals(0, report.entriesUsed)
        assertNull(report.rows.firstOrNull())
    }
}
