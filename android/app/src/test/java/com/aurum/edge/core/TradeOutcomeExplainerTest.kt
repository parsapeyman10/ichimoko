package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «دلیل برد و دلیل باخت» برای هر معامله.
 *
 * خواستهٔ کاربر: هوش عصبی باید دلیل هر برد و ضرر را بنویسد. این تست‌ها قفل می‌کنند که متن از
 * دادهٔ واقعی ساخته شود و حالت‌های شناخته‌شده (ابر ضخیم، ورود دیرهنگام، بی‌روندی، برگشت از سود)
 * درست تشخیص داده شوند.
 */
class TradeOutcomeExplainerTest {

    private fun trade(
        action: SignalAction = SignalAction.BUY,
        entry: Double = 100.0,
        stop: Double = 99.0,
        exit: Double = 102.0,
        pnl: Double = 12.0,
        openedAt: Long = 1_000_000L,
        closedAt: Long = 1_000_000L + 15 * 900_000L,
        exitReason: String = "حد سود",
        kumoThicknessAtr: Double = -1.0,
        distKijunAtr: Double = -1.0,
        er14: Double = -1.0,
        volumeRatio: Double = -1.0,
    ) = PaperTrade(
        id = "t1",
        symbol = "BTC/USDT",
        interval = Interval.M15,
        action = action,
        entry = entry,
        stopLoss = stop,
        takeProfit = exit,
        confidence = 70.0,
        riskReward = 2.0,
        openedAt = openedAt,
        closedAt = closedAt,
        exitPrice = exit,
        exitReason = exitReason,
        pnlUsd = pnl,
        stateKumoThicknessAtr = kumoThicknessAtr,
        stateDistKijunAtr = distKijunAtr,
        stateEr14 = er14,
        stateVolumeRatio20 = volumeRatio,
    )

    private fun series(
        from: Long,
        bars: Int,
        stepMs: Long,
        step: Double,
        start: Double,
        spikeAt: Int = -1,
        spikeHigh: Double = 0.0,
    ): List<Candle> {
        val out = mutableListOf<Candle>()
        var price = start
        for (i in 0 until bars) {
            val open = price
            val close = price + step
            var high = maxOf(open, close) + 0.05
            if (i == spikeAt && spikeHigh > 0) high = spikeHigh
            val low = minOf(open, close) - 0.05
            out += Candle(from + i * stepMs, open, high, low, close, 1_000.0, true)
            price = close
        }
        return out
    }

    @Test
    fun aWinReportsThePathAndTheResult() {
        val t = trade()
        val candles = series(t.openedAt, 15, 900_000L, step = 0.15, start = 100.0)
        val explanation = TradeOutcomeExplainer.explain(t, candles)
        assertTrue(explanation.headlineFa.startsWith("برد"))
        assertTrue(explanation.headlineFa.contains("دلار"))
        assertTrue(explanation.reasonsFa.any { it.contains("بیشترین حرکت مساعد") })
        assertTrue(explanation.mfeR > 0.0)
        assertTrue(explanation.barsHeld > 0)
        assertTrue(explanation.textFa().contains("•"))
    }

    @Test
    fun aLossThatWentIntoSixTenthsOfRIsTaggedAsGaveBack() {
        // قیمت اول ۰٫۹R بالا رفت و بعد تا حد ضرر برگشت.
        val t = trade(exit = 99.0, pnl = -5.0, exitReason = "حد ضرر")
        val up = series(t.openedAt, 6, 900_000L, step = 0.15, start = 100.0)
        val down = series(t.openedAt + 6 * 900_000L, 6, 900_000L, step = -0.25, start = 100.9)
        val explanation = TradeOutcomeExplainer.explain(t, up + down)
        assertTrue(explanation.headlineFa.startsWith("باخت"))
        assertTrue(
            "باید برچسب «سود رفت و برگشت» بخورد: ${explanation.tags}",
            TradeOutcomeExplainer.Tags.GAVE_BACK_PROFIT in explanation.tags,
        )
    }

    @Test
    fun heavyKumoAndLateEntryAreNamedExplicitly() {
        val t = trade(exit = 99.0, pnl = -5.0, kumoThicknessAtr = 1.2, distKijunAtr = 2.4)
        val explanation = TradeOutcomeExplainer.explain(t)
        assertTrue(TradeOutcomeExplainer.Tags.HEAVY_KUMO in explanation.tags)
        assertTrue(TradeOutcomeExplainer.Tags.LATE_ENTRY in explanation.tags)
        assertTrue(explanation.reasonsFa.any { it.contains("ابر لحظهٔ ورود ضخیم") })
        assertTrue(explanation.reasonsFa.any { it.contains("ورود دیرهنگام") })
        assertTrue(explanation.textFa().contains("دورهٔ خنک‌شدن"))
    }

    @Test
    fun choppyMarketIsNamedAsTheReason() {
        val t = trade(exit = 99.0, pnl = -5.0, er14 = 0.18, volumeRatio = 0.6)
        val explanation = TradeOutcomeExplainer.explain(t)
        assertTrue(TradeOutcomeExplainer.Tags.CHOP in explanation.tags)
        assertTrue(TradeOutcomeExplainer.Tags.THIN_VOLUME in explanation.tags)
        assertTrue(explanation.reasonsFa.any { it.contains("ER14") })
        assertTrue(explanation.reasonsFa.any { it.contains("حجم") })
    }

    @Test
    fun nothingIsInventedWhenThereIsNoData() {
        val t = trade()
        val explanation = TradeOutcomeExplainer.explain(t, emptyList())
        assertTrue(explanation.reasonsFa.isNotEmpty())
        assertFalse(explanation.reasonsFa.any { it.contains("بیشترین حرکت مساعد") })
        assertEquals(-1.0, explanation.mfeR, 1e-9)
    }

    @Test
    fun aggregateRanksThePatternsBehindTheLosses() {
        val losses = List(3) {
            trade(exit = 99.0, pnl = -5.0, kumoThicknessAtr = 1.2, distKijunAtr = 2.4).copy(id = "l$it")
        }
        val wins = List(2) {
            trade(er14 = 0.55).copy(id = "w$it")
        }
        val stats = TradeOutcomeExplainer.aggregate(losses + wins, minSamples = 2)
        assertTrue(stats.isNotEmpty())
        val heavy = stats.first { it.tag == TradeOutcomeExplainer.Tags.HEAVY_KUMO }
        assertEquals(3, heavy.losses)
        assertEquals(0, heavy.wins)
        val clean = stats.first { it.tag == TradeOutcomeExplainer.Tags.CLEAN_TREND }
        assertEquals(2, clean.wins)
        val summary = TradeOutcomeExplainer.summaryFa(losses + wins, minSamples = 2)
        assertTrue(summary.contains("همراهی با باخت"))
    }

    @Test
    fun openTradesAndBacktestsAreNotCounted() {
        val open = trade().copy(id = "o", closedAt = null, pnlUsd = null)
        val backtest = trade().copy(id = "b", origin = PaperTrade.ORIGIN_BACKTEST)
        assertTrue(TradeOutcomeExplainer.aggregate(listOf(open, backtest)).isEmpty())
    }
    @Test
    fun exitCandleIsNotUsedForMaximaAfterTheTradeClosed() {
        val t = trade(openedAt = 1_000_000L, closedAt = 2_350_000L, exit = 99.0, pnl = -5.0)
        val fullyInside = Candle(1_000_000L, 100.0, 100.2, 99.9, 100.1, closed = true)
        val overlapsExit = Candle(1_900_000L, 100.1, 120.0, 98.0, 119.0, closed = true)
        val result = TradeOutcomeExplainer.explain(t, listOf(fullyInside, overlapsExit))
        assertEquals(1, result.barsHeld)
        assertEquals(0.2, result.mfeR, 1e-9)
        assertTrue(result.reasonsFa.any { it.contains("UTC") && it.contains("کندل کامل") })
        assertFalse(result.tags.contains(TradeOutcomeExplainer.Tags.GAVE_BACK_PROFIT))
    }

}
