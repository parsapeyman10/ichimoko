package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.domain.Candle
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

        // استراتژی دیگر آزاد است.
        val other = journal.openTrade(calc, note = "استراتژی دیگر", strategyId = BitcoinStrategyModel.PITCHFORK_DEROOS.id)
        assertNotNull(other)
        assertEquals(2, journal.trades.value.count { it.isOpen })

        // با روشن شدن تکلیف معاملهٔ اول، همان استراتژی دوباره مجاز می‌شود.
        journal.onLiveTick("BTC/USDT", PriceTick(price = first.takeProfit + 5.0, at = first.openedAt + 5_000L))
        assertNull(journal.openTradeForStrategy(BitcoinStrategyModel.BASE_ICHIMOKU.id))
        assertNull(journal.openTradeBlockedByFa(BitcoinStrategyModel.BASE_ICHIMOKU.id))
        val reopened = journal.openTrade(calc, note = "بعد از بسته شدن", strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id)
        assertNotNull(reopened)
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
        val fourHourDown = trendingSeries(90, step = -4.0, stepMs = 4 * 3_600_000L, start = 4_000.0)

        val trade = journal.openTrade(
            calc = calc,
            note = "سنجش لحظهٔ ورود",
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
        journal.onLiveTick("ETH/USDT", PriceTick(price = trade.takeProfit + 10.0, at = trade.openedAt + 60_000L))
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
}
