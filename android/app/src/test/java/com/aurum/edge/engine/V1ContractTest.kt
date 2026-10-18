package com.aurum.edge.engine

import com.aurum.edge.core.Candle
import com.aurum.edge.core.CategoryStrategy
import com.aurum.edge.core.ConfluenceItem
import com.aurum.edge.core.ConfluenceStatus
import com.aurum.edge.core.TechnicalEvidence
import com.aurum.edge.core.AssetClass
import com.aurum.edge.core.SignalProfile
import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.StrategyKind
import com.aurum.edge.core.Signal
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperPortfolioPolicy
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.PaperTrailing
import com.aurum.edge.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class V1ContractTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val totalBars = com.aurum.edge.core.HistoryPolicy.LIVE_MIN_CANDLES
    private fun bars(interval: Interval): List<Candle> = (0 until totalBars + 20).map { i ->
        val start = now - (totalBars + 20 - i) * interval.millis
        val price = 100.0 + i * .02 + (i % 5) * .08
        Candle(start, price, price + .25, price - .25, price + .03, 100.0, true)
    }

    @Test fun independentFramesAreUsedDirectlyAnd513And1024LinesAreComputedOnLongSeries() {
        // Frames are independent series now (never derived from M5).
        val m15 = bars(Interval.M15)
        val h1 = bars(Interval.H1)
        assertEquals(totalBars + 20, m15.size)
        assertEquals(totalBars + 20, h1.size)
        // The 513/1024 Donchian math still works when a long series is supplied.
        val long = (0 until 1100).map { i ->
            val price = 100.0 + i * .02 + (i % 5) * .08
            Candle(now - (1100 - i) * Interval.M5.millis, price, price + .25, price - .25, price + .03, 100.0, true)
        }
        val ichi = Ichimoku.compute(long, 8, 24, 72, 24)
        assertNotNull(ichi.line513.last())
        assertNotNull(ichi.line1024.last())
        assertEquals(SignalAction.BUY, ichi.higherTimeframeBiasAt(long.lastIndex, long.last().close))
    }

    @Test fun threeRealFrameStackIsScoredAndOldFutureBarsCannotParticipate() {
        val data = V1Scoring.intervals.associateWith(::bars)
        val signal = SignalEngine.evaluate(data.getValue(Interval.M5), Interval.M5, 85.0,
            symbol = "EUR/USD", timeframes = data)
        assertEquals(4, signal.confluence.size)
        assertEquals(listOf(Interval.M5, Interval.M15, Interval.H1), V1Scoring.intervals)
        assertTrue(signal.confluence.last().detail.contains("1H:"))
        assertFalse(signal.blockers.any { it.contains("پشتهٔ ۳") })
        val future = data.toMutableMap()
        // Supply only a short block of M15 bars shifted after the M5 base close. Older
        // history must not leave enough bars to accidentally satisfy the 500-bar gate.
        future[Interval.M15] = data.getValue(Interval.M15).takeLast(100).map {
            it.copy(time = it.time + 2L * Interval.D1.millis)
        }
        val rejected = SignalEngine.evaluate(data.getValue(Interval.M5), Interval.M5, 60.0,
            symbol = "EUR/USD", timeframes = future)
        assertEquals(SignalAction.NO_TRADE, rejected.action)
        // A future-dated M15 frame must become UNKNOWN rather than contributing a vote.
        val stackEvidence = rejected.confluence.single { it.name == "پشتهٔ ۳ تایم‌فریمی" }
        assertEquals(ConfluenceStatus.UNKNOWN, stackEvidence.status)
        assertTrue(stackEvidence.detail.contains("15m:نامعلوم"))
    }

    @Test fun oneShortOrUnclosedFrameBlocksThreeFrameAlignment() {
        val complete = V1Scoring.intervals.associateWith(::bars)
        val short = complete.toMutableMap()
        short[Interval.M15] = complete.getValue(Interval.M15).takeLast(499)
        val rejected = SignalEngine.evaluate(complete.getValue(Interval.M5), Interval.M5, 60.0,
            symbol = "EUR/USD", timeframes = short)
        assertEquals(SignalAction.NO_TRADE, rejected.action)
        assertTrue(rejected.blockers.any { it.contains("15m(499/500)") })
        assertTrue(rejected.confluence.last().detail.contains("499/500 کندل"))

        val open = complete.toMutableMap()
        open[Interval.H1] = complete.getValue(Interval.H1).takeLast(125)
            .mapIndexed { index, candle -> if (index == 124) candle.copy(closed = false) else candle }
        val blocked = SignalEngine.evaluate(complete.getValue(Interval.M5), Interval.M5, 60.0,
            symbol = "EUR/USD", timeframes = open)
        assertEquals(SignalAction.NO_TRADE, blocked.action)
        assertTrue(blocked.blockers.any { it.contains("1H(124/125)") })
    }

    @Test fun equalDirectionalScoresNeverDefaultBuyAndRangeNeedsDirectionalFrames() {
        assertEquals(SignalAction.NO_TRADE, V1Scoring.chooseSide(75, 75))
        assertEquals(SignalAction.BUY, V1Scoring.chooseSide(76, 75))
        assertEquals(SignalAction.SELL, V1Scoring.chooseSide(74, 75))
        val flat = List(3) { SignalAction.NO_TRADE }
        assertEquals(0, V1Scoring.directionalAlignment(flat, SignalAction.BUY))
        assertEquals(0, V1Scoring.directionalAlignment(flat, SignalAction.SELL))
        assertEquals(0, V1Scoring.directionalAlignment(List(2) { SignalAction.BUY } + null, SignalAction.BUY))
        assertEquals(25, V1Scoring.directionalAlignment(List(3) { SignalAction.BUY }, SignalAction.BUY))
        assertEquals(20, V1Scoring.directionalAlignment(List(2) { SignalAction.BUY } + SignalAction.NO_TRADE, SignalAction.BUY))
    }

    @Test fun partialScoreCannotMasqueradeAsFullConfirmation() {
        val labels = listOf("ساختار روند · EMA + ایچیموکو", "مومنتوم · RSI + MACD",
            "پرایس‌اکشن · حمایت/مقاومت", "پشتهٔ ۳ تایم‌فریمی")
        val items = labels.mapIndexed { index, name ->
            if (index == 0) ConfluenceItem(name, false, "partial", ConfluenceStatus.PARTIAL, 12)
            else ConfluenceItem(name, true, "full", ConfluenceStatus.CONFIRMED, 25)
        }
        assertTrue(TechnicalEvidence.confirmed(items)) // 87 points; partial is not full
        assertFalse(items.first().ok)
        assertFalse(TechnicalEvidence.confirmed(items.toMutableList().also {
            it[0] = it[0].copy(status = ConfluenceStatus.CONFIRMED)
        }))
        assertFalse(TechnicalEvidence.confirmed(items.toMutableList().also {
            it[1] = it[1].copy(ok = false)
        }))
        // Existing journal records retain their original seven-frame label for display;
        // accepting the persisted record does NOT let an old seven-frame signal authorize
        // a new three-frame paper entry.
        val old = items.map { com.aurum.edge.core.PaperConditionRecord.from(it) }.toMutableList()
        old[3] = old[3].copy(name = "پشتهٔ ۷ تایم‌فریمی")
        assertTrue(TechnicalEvidence.confirmedRecords(old))
        assertFalse(TechnicalEvidence.confirmed(items.toMutableList().also {
            it[3] = it[3].copy(name = "پشتهٔ ۷ تایم‌فریمی")
        }))
    }

    @Test fun slashlessForexIsNotClassifiedAsStockAndLegacyProfilesAreNotV1Modes() {
        assertEquals(AssetClass.FOREX, AssetClass.of("EURUSD"))
        assertEquals(AssetClass.FOREX, AssetClass.of("GBPJPY"))
        assertEquals(AssetClass.STOCK, AssetClass.of("AAPL"))
        assertEquals(AssetClass.CRYPTO, AssetClass.of("BTCUSDT"))
        assertEquals(StrategyKind.ICHIMOKU_PRICE_ACTION, StrategyKind.fromId("ICT_SMC"))
        assertEquals(null, StrategyKind.fromId("unknown-pretend-strategy"))
        assertFalse(SignalProfile.BASE.fakeBreakoutFilter)
    }

    @Test fun chartAndScannerUseOneSettingsToEngineAdapter() {
        val data = V1Scoring.intervals.associateWith(::bars)
        val base = data.getValue(Interval.M5)
        listOf(CategoryStrategy.TREND, CategoryStrategy.RANGE, CategoryStrategy.HYBRID).forEach { mode ->
            val settings = AppSettings(minConfidence = 85.0,
                categoryStrategies = AssetClass.entries.associateWith { mode })
            val chart = SignalEngine.evaluateLive(base, Interval.M5, settings, "EUR/USD", data)
            val scanner = SignalEngine.evaluateLive(base, Interval.M5, settings, "EUR/USD", data)
            val direct = SignalEngine.evaluate(base, Interval.M5, 85.0,
                symbol = "EUR/USD", mode = mode, timeframes = data)
            assertEquals(chart.action, scanner.action)
            assertEquals(chart.blockers, scanner.blockers)
            assertEquals(chart.confluence, direct.confluence)
            assertEquals(chart.action, direct.action)
        }
        val incomplete = data - Interval.M15
        val blocked = SignalEngine.evaluateLive(base, Interval.M5, AppSettings(), "EUR/USD", incomplete)
        assertFalse(blocked.isActionable)
        assertTrue(blocked.blockers.any { it.contains("پشتهٔ ۳") })
    }

    @Test fun liveAndDisplayedIchimokuShareTheSamePeriods() {
        V1Scoring.intervals.forEach { interval ->
            val setting = SignalEngine.ichimokuSetting(interval)
            assertEquals(8, setting.tenkan)
            assertEquals(24, setting.kijun)
            assertEquals(72, setting.spanB)
            val sample = bars(interval).takeLast(totalBars)
            val visible = Ichimoku.compute(sample, setting.tenkan, setting.kijun, setting.spanB,
                setting.kijun).cloudAt(sample.lastIndex)
            assertNotNull(visible)
        }
    }

    private fun trade(id: String, symbol: String, opened: Long, closed: Long? = null,
                      pnl: Double? = null) = PaperTrade(id, symbol, Interval.M5, SignalAction.BUY,
        100.0, 99.0, 102.0, 85.0, 2.0, opened, closedAt = closed, pnlUsd = pnl,
        positionOz = 5.0, initialStopLoss = 99.0)

    @Test fun riskSlotsAndDailyLossRemainButThreeConsecutiveLossesDoNotHaltTesting() {
        val open = listOf(trade("1", "EUR/USD", 1L), trade("2", "GBP/USD", 2L))
        assertNotNull(PaperPortfolioPolicy.blocker(open, "USD/JPY", 1000.0, 5.0, now = now))
        assertNotNull(PaperPortfolioPolicy.blocker(open.take(1), "AAPL", 1000.0, 5.01, now = now))
        val losses = (1..3).map { trade("x$it", "AAPL", now - it * 1000L, now - it * 500L, -5.0) }
        // Three losses alone are no longer an entry veto; today's 2% risk cap still applies.
        assertEquals(null, PaperPortfolioPolicy.blocker(losses, "EUR/USD", 985.0, 4.9, now = now))
        val winning = trade("win", "TSLA", now, now - 1L, 1.0)
        assertTrue(PaperPortfolioPolicy.blocker(losses + winning + open.take(1),
            "AAPL", 986.0, 4.9, now = now)!!.contains("سقف ضرر روزانه"))
        // Even a longer run of losses from a previous UTC day cannot freeze test entries.
        val olderLosses = (1..5).map { i ->
            trade("old$i", "AAPL", now - 2 * Interval.D1.millis - i * 1000L,
                now - 2 * Interval.D1.millis - i * 500L, -5.0)
        }
        assertEquals(null, PaperPortfolioPolicy.blocker(olderLosses, "EUR/USD", 975.0, 4.8,
            now = now, openingBalance = 975.0))
    }

    @Test fun shortTrailingLocksTheFirstRAfterTwoR() {
        val short = trade("short", "AAPL", now).copy(action = SignalAction.SELL,
            stopLoss = 101.0, initialStopLoss = 101.0)
        val one = PaperTrailing.advance(short, 100.0, 99.0)
        assertEquals(100.0, one.stopLoss, .00001)
        val two = PaperTrailing.advance(one, 99.0, 98.0)
        assertEquals(99.0, two.stopLoss, .00001)
        assertEquals(99.0, PaperTrailing.advance(two, 99.5, 99.0).stopLoss, .00001)
    }

    @Test fun trailingMovesAtOneAndTwoRAndNeverShrinksTheLock() {
        val original = trade("1", "AAPL", now)
        assertEquals(5.0, original.riskUsd, .00001)
        val atOne = PaperTrailing.advance(original, 101.0, 100.0)
        assertEquals(100.0, atOne.stopLoss, .00001)
        val atTwo = PaperTrailing.advance(atOne, 102.0, 101.0)
        assertEquals(101.0, atTwo.stopLoss, .00001)
        val pastThree = PaperTrailing.advance(atTwo, 103.0, 102.0)
        assertEquals(102.0, pastThree.stopLoss, .00001)
        assertEquals(pastThree.stopLoss, PaperTrailing.advance(pastThree, 102.1, 101.5).stopLoss, .00001)
        assertEquals(5.0, pastThree.riskUsd, .00001) // original immutable risk in USD
    }
}
