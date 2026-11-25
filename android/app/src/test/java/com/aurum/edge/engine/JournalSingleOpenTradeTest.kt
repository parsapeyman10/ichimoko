package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * دو قاعدهٔ کاربر:
 * ۱) از هر استراتژی در هر لحظه فقط **یک** معاملهٔ باز مجاز است تا تکلیف آن روشن شود.
 * ۲) سنجش تکنیکال ۱ ساعته و ۴ ساعته در **لحظهٔ باز شدن** معامله ثبت و در ژورنال دیده می‌شود
 *    و بعد از آن تغییر نمی‌کند.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalSingleOpenTradeTest {

    private fun freshJournal(name: String): Pair<JournalStore, SettingsStore> {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val file = File(context.filesDir, name)
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".protection").delete()
        val settings = SettingsStore(context)
        return JournalStore(context, file = file, settingsStore = settings) to settings
    }

    private fun candle(index: Int, open: Double, high: Double, low: Double, close: Double, stepMs: Long) =
        Candle(index.toLong() * stepMs, open, high, low, close, 1_000.0, true)

    private fun trendingSeries(bars: Int, step: Double, stepMs: Long, start: Double = 100.0): List<Candle> {
        val out = mutableListOf<Candle>()
        var price = start
        for (i in 0 until bars) {
            val open = price
            price += step
            val close = price
            val wick = kotlin.math.abs(step) * 0.35 + 0.05
            out.add(candle(i, open, maxOf(open, close) + wick, minOf(open, close) - wick, close, stepMs))
        }
        return out
    }

    @Test
    fun onlyOneOpenTradeIsAllowedPerStrategy() {
        val (journal, _) = freshJournal("test_one_trade_per_strategy.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 68_000.0,
            stopLoss = 67_500.0,
            takeProfit = 69_000.0,
        )!!

        val first = journal.openTrade(calc, note = "اول", strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNotNull(first)
        assertEquals(first!!.id, journal.openTradeForStrategy(BitcoinStrategyModel.BASE_ICHIMOKU.id)?.id)

        // همان استراتژی، معاملهٔ دوم نمی‌گیرد تا تکلیف اولی روشن شود.
        val blocked = journal.openTrade(calc, note = "دوم", strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNull("معاملهٔ دوم همان استراتژی باید رد شود", blocked)
        assertEquals(1, journal.trades.value.count { it.isOpen })
        val reason = journal.openTradeBlockedByFa(BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNotNull(reason)
        assertTrue(reason!!.contains("تکلیف"))

        // استراتژی دیگر روی **همین نماد** هم آزاد نیست: تا تکلیف پوزیشن باز BTC روشن نشود،
        // هیچ استراتژی روی BTC وارد نمی‌شود (قاعدهٔ «یک معامله روی هر نماد»).
        val blockedSameSymbol = journal.openTrade(
            calc,
            note = "استراتژی دیگر روی همان نماد",
            strategyId = BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT.id,
        )
        assertNull("روی نمادِ دارای پوزیشن باز، معاملهٔ تازه نباید باز شود", blockedSameSymbol)
        val sameSymbolReason = journal.openTradeBlockedByFa("BTC/USDT", BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT.id)
        assertNotNull(sameSymbolReason)
        assertTrue(sameSymbolReason!!.contains("BTC/USDT"))

        // استراتژی دیگر روی نماد دیگر آزاد است.
        val ethCalc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "ETH/USDT",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 3_200.0,
            stopLoss = 3_180.0,
            takeProfit = 3_260.0,
        )!!
        val other = journal.openTrade(ethCalc, note = "استراتژی دیگر", strategyId = BitcoinStrategyModel.TUTTLE_KUMO_BREAKOUT.id)
        assertNotNull(other)
        assertEquals(2, journal.trades.value.count { it.isOpen })

        // با روشن شدن تکلیف معاملهٔ اول، پوزیشن باز نمی‌مانَد…
        journal.onLiveTick("BTC/USDT", PriceTick(price = first.takeProfit + 5.0, at = first.openedAt + 5_000L), nowMillis = first.openedAt + 5_000L)
        assertNull(journal.openTradeForStrategy(BitcoinStrategyModel.BASE_ICHIMOKU.id))
        // …ولی ورود فوری دوباره به همان (نماد، استراتژی) ممنوع است: دورهٔ خنک‌شدن.
        val blockRightAfterClose = journal.openTradeBlockedByFa("BTC/USDT", BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNotNull("بعد از بسته شدن باید دورهٔ خنک‌شدن فعال باشد", blockRightAfterClose)
        assertTrue(blockRightAfterClose!!.contains("خنک"))
        assertNull(
            journal.openTrade(calc, note = "بلافاصله بعد از بسته شدن", strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id),
        )
        // بعد از گذشتن پنجرهٔ خنک‌شدن، همان استراتژی آزاد است.
        val closedAt = journal.trades.value.first { !it.isOpen }.closedAt!!
        val later = closedAt + com.aurum.edge.core.ReentryGuard.cooldownMs(first.interval, 0) + 60_000L
        val reopened = journal.openTrade(
            calc,
            note = "بعد از دورهٔ خنک‌شدن",
            now = later + 1_000L,
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
        )
        assertNotNull("پس از پایان پنجره باید ورود مجاز باشد", reopened)
    }

    @Test
    fun noPathCanBypassTheOneTradeRule() {
        val (journal, _) = freshJournal("test_one_trade_no_bypass.json")
        val btc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 68_000.0,
            stopLoss = 67_500.0,
            takeProfit = 69_000.0,
        )!!
        val first = journal.openTrade(btc, note = "پوزیشن باز", strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNotNull(first)
        val openBefore = journal.trades.value.count { it.isOpen }

        // مسیر «واردکردن معامله» (import/restore) هم نمی‌تواند پوزیشن باز دوم روی همان نماد بسازد.
        val injected = first!!.copy(id = "injected-open", strategyId = BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id)
        journal.addTrade(injected)
        assertEquals(
            "addTrade نباید پوزیشن باز دوم روی یک نماد بسازد",
            openBefore,
            journal.trades.value.count { it.isOpen },
        )
        assertNull("پوزیشن تزریقی نباید در ژورنال باشد", journal.trades.value.firstOrNull { it.id == "injected-open" })

        // همان معامله با همان شناسه آزاد است (به‌روزرسانی، نه ورود تازه).
        journal.addTrade(first.copy(note = "یادداشت تازه همان معامله"))
        assertEquals(openBefore, journal.trades.value.count { it.isOpen })
        assertEquals("یادداشت تازه همان معامله", journal.trades.value.first { it.id == first.id }.note)

        // معاملهٔ بک‌تست (شبیه‌سازی) این قاعده را قفل نمی‌کند.
        journal.addTrade(injected.copy(id = "bt-1", origin = com.aurum.edge.core.PaperTrade.ORIGIN_BACKTEST))
        assertNotNull(journal.trades.value.firstOrNull { it.id == "bt-1" })
    }

    @Test
    fun profitLockNeverStartsBeforeOneAndHalfR() {
        val cases = listOf(
            Triple(0.05, 0.05, 0.05),
            Triple(2.0, 3.0, 3.0),
            Triple(0.9, 0.4, 0.2),
            Triple(1.9, 0.1, 1.4),
        )
        cases.forEach { (atrPct, spread, cloud) ->
            val r = com.aurum.edge.core.IchimokuStrategyEngine.adaptiveStopMoveThresholdR(
                atrPercentOfPrice = atrPct,
                tenkanKijunSpreadAtr = spread,
                cloudThicknessAtr = cloud,
                interval = Interval.M5,
            )
            assertTrue("قفل سود نباید پیش از ۱٫۵R شروع شود ($r)", r >= 1.5 - 1e-9)
            assertTrue("قفل سود نباید از ۲٫۲R دیرتر شود ($r)", r <= 2.2 + 1e-9)
        }
    }

    @Test
    fun signalTimeIsPreservedAndFutureTimeIsNotInvented() {
        val (journal, _) = freshJournal("test_signal_time.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "ETH/USDT", accountBalance = 1_000.0, riskPercent = 0.5,
            entry = 3_000.0, stopLoss = 2_960.0, takeProfit = 3_080.0,
        )!!
        val now = 2_000_000L
        val trade = journal.openTrade(calc, now = now, signalBarTime = now - 60_000L)!!
        assertEquals(now - 60_000L, trade.signalBarTime)
        val (other, _) = freshJournal("test_signal_future.json")
        val future = other.openTrade(calc, now = now, signalBarTime = now + 1L)!!
        assertNull(future.signalBarTime)
    }

    @Test
    fun gaugeSnapshotIsRecordedAtOpenForBothTimeframes() {
        val (journal, _) = freshJournal("test_gauge_snapshot.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "ETH/USDT",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 3_000.0,
            stopLoss = 2_960.0,
            takeProfit = 3_080.0,
        )!!
        val hourlyUp = trendingSeries(90, step = 1.6, stepMs = 3_600_000L, start = 100.0)
            .map { it.copy(time = it.time + 270 * 3_600_000L) }
        val fourHourDown = trendingSeries(90, step = -4.0, stepMs = 4 * 3_600_000L, start = 4_000.0)

        val trade = journal.openTrade(
            calc = calc,
            note = "سنجش لحظهٔ ورود",
            now = 360 * 3_600_000L,
            strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
            gaugeCandles1h = hourlyUp,
            gaugeCandles4h = fourHourDown,
        )
        assertNotNull(trade)
        trade!!

        // اسنپ‌شات ذخیره‌شده باید دقیقاً همان ارزیابی لحظهٔ ورود باشد (نه یک محاسبهٔ دیگر).
        val expected1h = com.aurum.edge.core.TechnicalRatingEngine.evaluate(hourlyUp)!!
        val expected4h = com.aurum.edge.core.TechnicalRatingEngine.evaluate(fourHourDown)!!
        assertTrue("سنجش باید رأی داشته باشد", expected1h.total > 0 && expected4h.total > 0)
        assertEquals(expected1h.buy, trade.tech1hBuy)
        assertEquals(expected1h.neutral, trade.tech1hNeutral)
        assertEquals(expected1h.sell, trade.tech1hSell)
        assertEquals(expected4h.buy, trade.tech4hBuy)
        assertEquals(expected4h.neutral, trade.tech4hNeutral)
        assertEquals(expected4h.sell, trade.tech4hSell)
        assertTrue("روند صعودی ۱ ساعته باید خرید بیشتری بدهد", trade.tech1hBuy > trade.tech1hSell)
        assertTrue("روند نزولی ۴ ساعته باید فروش بیشتری بدهد", trade.tech4hSell > trade.tech4hBuy)
        assertFalse(trade.techSnapshotLineFa.contains("ثبت نشد"))
        assertTrue(trade.techSnapshotLineFa.contains("۱ ساعته"))
        assertTrue(trade.techSnapshotLineFa.contains("۴ ساعته"))

        // اسنپ‌شات پس از بسته شدن معامله نباید عوض شود.
        journal.onLiveTick("ETH/USDT", PriceTick(price = trade.takeProfit + 10.0, at = trade.openedAt + 60_000L), nowMillis = trade.openedAt + 60_000L)
        val closed = journal.trades.value.first { it.id == trade.id }
        assertFalse(closed.isOpen)
        assertEquals(trade.tech1hBuy, closed.tech1hBuy)
        assertEquals(trade.tech4hSell, closed.tech4hSell)
    }

    @Test
    fun missingGaugeDataIsReportedHonestly() {
        val (journal, _) = freshJournal("test_gauge_missing.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 2_600.0,
            stopLoss = 2_590.0,
            takeProfit = 2_620.0,
        )!!
        val trade = journal.openTrade(calc, note = "بدون کندل سنجش")
        assertNotNull(trade)
        // بدون کندل ۱/۴ ساعته در کش، عدد ساختگی ساخته نمی‌شود و در ژورنال هم صریح می‌نویسد «ثبت نشد».
        assertEquals(-1, trade!!.tech1hBuy)
        assertTrue(trade.techSnapshotLineFa.contains("ثبت نشد"))
    }

    @Test
    fun unknownEntrySnapshotIsRebuiltOnlyFromCandlesClosedBeforeEntry() {
        val (journal, _) = freshJournal("test_gauge_backfill.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "EUR/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 1.1000,
            stopLoss = 1.0995,
            takeProfit = 1.1010,
        )!!
        val openedAt = 1_700_000_000_000L
        val trade = journal.openTrade(calc, note = "معاملهٔ قدیمی", now = openedAt)
        assertNotNull(trade)
        assertEquals("بدون کندل، سنجش نامعلوم است", -1, trade!!.tech1hBuy)

        // کندل‌های ۱ ساعته‌ای که همه پیش از لحظهٔ ورود بسته شده‌اند.
        val hourly = trendingSeries(90, step = 1.2, stepMs = 3_600_000L, start = 200.0)
        val provider: (String, Interval) -> List<Candle> = { _, interval ->
            if (interval == Interval.H1) hourly else emptyList()
        }
        assertEquals(1, journal.backfillGaugeSnapshots(provider))

        val rebuilt = journal.trades.value.first { it.id == trade.id }
        val expected = com.aurum.edge.core.TechnicalRatingEngine.evaluate(hourly)!!
        assertEquals(expected.buy, rebuilt.tech1hBuy)
        assertEquals(expected.neutral, rebuilt.tech1hNeutral)
        assertEquals(expected.sell, rebuilt.tech1hSell)
        assertEquals("۴ ساعته داده‌ای ندارد ⇒ نامعلوم می‌ماند", -1, rebuilt.tech4hBuy)
        assertTrue(rebuilt.techSnapshotRebuilt)
        assertTrue(rebuilt.techSnapshotLineFa.contains("بازسازی"))
        assertTrue(rebuilt.techSnapshotLineFa.contains("ثبت نشد"))
        // اجرای دوباره کاری برای انجام نمی‌دهد.
        assertEquals(0, journal.backfillGaugeSnapshots(provider))
    }

    @Test
    fun rebuiltSnapshotNeverUsesCandlesThatCloseAfterEntry() {
        val (journal, _) = freshJournal("test_gauge_backfill_causal.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "EUR/USD",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 1.1000,
            stopLoss = 1.0995,
            takeProfit = 1.1010,
        )!!
        // ورود درست بعد از بسته شدن کندل پنجاهم (۵۰ ساعت بعد از مبدأ) انجام شده است.
        val hourly = trendingSeries(90, step = 1.2, stepMs = 3_600_000L, start = 200.0)
        val openedAt = 50 * 3_600_000L
        val trade = journal.openTrade(calc, note = "ورود وسط سری", now = openedAt)!!
        val provider: (String, Interval) -> List<Candle> = { _, interval ->
            if (interval == Interval.H1) hourly else emptyList()
        }
        journal.backfillGaugeSnapshots(provider)
        val rebuilt = journal.trades.value.first { it.id == trade.id }
        assertEquals("کندل‌های بعد از ورود نباید در بازسازی دخالت کنند", -1, rebuilt.tech1hBuy)
        assertFalse(rebuilt.techSnapshotRebuilt)
    }
    @Test
    fun activeStopSurvivesReloadAndLiveExitUsesIt() {
        val (journal, settings) = freshJournal("active_stop_reload.json")
        val context = RuntimeEnvironment.getApplication()
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT", accountBalance = 100_000.0, riskPercent = 0.1,
            entry = 100.0, stopLoss = 90.0, takeProfit = 130.0,
        )!!
        val at = System.currentTimeMillis() - 60_000L
        val opened = journal.openTrade(calc, now = at)!!
        val moved = journal.updateTradeStopLoss(opened.id, 102.0, "🔒 STRUCT test", allowRatchet = true, updatedAt = at + 10_000L)!!
        val reload = JournalStore(context, File(context.filesDir, "active_stop_reload.json"), settings)
        val restored = reload.trades.value.single()
        assertEquals(moved.stopLoss, restored.stopLoss, 0.0)
        assertEquals(opened.initialStopLoss, restored.initialStopLoss)
        assertEquals(at + 10_000L, restored.stopUpdatedAt)
        val tick = PriceTick(101.99, at + 20_000L)
        val exits = reload.onLiveTick("BTC/USDT", tick, nowMillis = tick.at)
        assertEquals(1, exits.size)
        assertEquals(101.99, exits.single().exitPrice, 0.00001)
        assertFalse(reload.trades.value.single().isOpen)
    }

    @Test
    fun tickAtThresholdMovesTheStopImmediatelyAndNeverBackwards() {
        val (journal, _) = freshJournal("tick_break_even.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT", accountBalance = 100_000.0, riskPercent = 0.1,
            entry = 100.0, stopLoss = 90.0, takeProfit = 130.0,
        )!!
        val at = System.currentTimeMillis() - 60_000L
        val opened = journal.openTrade(calc, now = at)!!
        val fee = opened.settlementFeeUsd
        val thresholdPrice = opened.entry + (1.5 * opened.fixedRiskUsd + fee + 0.02) / opened.positionOz
        val tick = PriceTick(thresholdPrice, at + 20_000L)
        journal.onLiveTick("BTC/USDT", tick, nowMillis = tick.at)
        val protected = journal.trades.value.single()
        assertTrue(protected.stopLoss > opened.entry)
        assertTrue(RiskAndCalendarEngine.computeExitPnlUsd(protected, protected.stopLoss).second >= 0.01)
        assertNull(journal.updateTradeStopLoss(opened.id, opened.stopLoss, "backwards", allowRatchet = true))
        assertNull(journal.repairOpenTradePlan(opened.id, opened.stopLoss, opened.takeProfit, "repair"))
    }

    @Test
    fun olderQuoteCannotCloseAfterNewerQuote() {
        val (journal, _) = freshJournal("tick_order_guard.json")
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT", accountBalance = 100_000.0, riskPercent = 0.1,
            entry = 100.0, stopLoss = 90.0, takeProfit = 130.0,
        )!!
        val at = System.currentTimeMillis() - 30_000L
        journal.openTrade(calc, now = at)!!
        journal.onLiveTick("BTC/USDT", PriceTick(101.0, at + 20_000L), nowMillis = at + 20_000L)
        val obsolete = journal.onLiveTick("BTC/USDT", PriceTick(89.0, at + 10_000L), nowMillis = at + 21_000L)
        assertTrue(obsolete.isEmpty())
        assertTrue(journal.trades.value.single().isOpen)
    }

    @Test
    fun staleAndFutureQuotesCannotCloseOrLock() {
        val (journal, _) = freshJournal("stale_quote_guard.json")
        val calc = RiskAndCalendarEngine.calculatePosition(symbolInput = "BTC/USDT", accountBalance = 100_000.0,
            riskPercent = 0.1, entry = 100.0, stopLoss = 90.0, takeProfit = 130.0)!!
        val at = System.currentTimeMillis() - 120_000L
        val t = journal.openTrade(calc, now = at)!!
        assertTrue(journal.onLiveTick("BTC/USDT", PriceTick(80.0, at + 1_000L), nowMillis = at + 100_000L).isEmpty())
        assertTrue(journal.onLiveTick("BTC/USDT", PriceTick(80.0, at + 200_000L), nowMillis = at + 100_000L).isEmpty())
        assertEquals(t.stopLoss, journal.trades.value.single().stopLoss, 0.0)
        assertTrue(journal.trades.value.single().isOpen)
    }

    @Test
    fun durableCheckpointRestoresMonotonicLockWhenMainJournalIsOld() {
        val name = "checkpoint_lock_recovery.json"
        val (journal, settings) = freshJournal(name)
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, name)
        val calc = RiskAndCalendarEngine.calculatePosition(symbolInput = "BTC/USDT", accountBalance = 100_000.0,
            riskPercent = 0.1, entry = 100.0, stopLoss = 90.0, takeProfit = 130.0)!!
        val at = System.currentTimeMillis() - 60_000L
        val t = journal.openTrade(calc, now = at)!!
        val original = file.readText()
        val price = t.entry + (1.5 * t.fixedRiskUsd + t.settlementFeeUsd + 0.02) / t.positionOz
        journal.onLiveTick("BTC/USDT", PriceTick(price, at + 20_000L), nowMillis = at + 20_000L)
        val stop = journal.trades.value.single().stopLoss
        assertTrue(stop > t.stopLoss)
        assertFalse(journal.protectionStorageError.value)
        // Simulate recovery from an older journal while a complete checkpoint survived.
        Thread.sleep(200)
        file.writeText(original)
        val reload = JournalStore(context, file, settings)
        assertEquals(stop, reload.trades.value.single().stopLoss, 0.0)
    }

}
