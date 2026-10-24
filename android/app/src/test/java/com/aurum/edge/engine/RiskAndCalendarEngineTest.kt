package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RiskAndCalendarEngineTest {

    @Test
    fun `position calculator computes units, standard lots, risk dollar and R-R for gold and forex`() {
        val goldCalc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1000.0,
            riskPercent = 1.0,
            entry = 2650.0,
            stopLoss = 2640.0,
            takeProfit = 2670.0,
        )
        assertNotNull(goldCalc)
        goldCalc!!
        assertEquals(SignalAction.BUY, goldCalc.action)
        assertEquals(10.0, goldCalc.riskBudgetUsd, 0.001)
        assertEquals(1.0, goldCalc.positionUnits, 0.001) // $10 risk / $10 stop distance = 1 oz
        assertEquals(0.01, goldCalc.standardLots, 0.0001) // 1 oz / 100 oz per lot = 0.01 lot
        assertEquals(2.0, goldCalc.riskReward, 0.001)
        assertEquals(20.0, goldCalc.grossProfitAtTpUsd, 0.001)

        val fxShort = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "EUR/USD",
            accountBalance = 2000.0,
            riskPercent = 2.0,
            entry = 1.0850,
            stopLoss = 1.0870,
            takeProfit = 1.0800,
        )
        assertNotNull(fxShort)
        fxShort!!
        assertEquals(SignalAction.SELL, fxShort.action)
        assertEquals(40.0, fxShort.riskBudgetUsd, 0.001)
        assertEquals(20_000.0, fxShort.positionUnits, 0.1) // $40 / 0.0020 = 20,000 EUR
        assertEquals(0.2, fxShort.standardLots, 0.001) // 20,000 / 100,000 = 0.20 lot
        assertEquals(2.5, fxShort.riskReward, 0.001)
    }

    @Test
    fun `auto exit closes open BUY and SELL trades when live price hits TP or SL and updates journal and calendar`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val journalFile = File(context.filesDir, "test_user_journal_v1.json")
        journalFile.delete()
        File(journalFile.path + ".bak").delete()

        val settingsStore = SettingsStore(context)
        val journalStore = JournalStore(context, file = journalFile, settingsStore = settingsStore)

        val day1Millis = LocalDate.of(2026, 10, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val calcBuy = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1000.0,
            riskPercent = 1.0,
            entry = 2600.0,
            stopLoss = 2590.0,
            takeProfit = 2620.0,
        )!!
        val openedBuy = journalStore.openTrade(calcBuy, note = "تست خرید طلا", now = day1Millis)
        assertTrue(openedBuy.isOpen)

        // Price between SL and TP -> remains open
        val midHits = journalStore.onLiveTick("XAU/USD", PriceTick(price = 2612.0, at = day1Millis + 5000L))
        assertTrue(midHits.isEmpty())
        assertEquals(1, journalStore.trades.value.count { it.isOpen })

        // Price reaches TP -> auto-closed at TP!
        val tpHits = journalStore.onLiveTick("XAU/USD", PriceTick(price = 2620.5, at = day1Millis + 10_000L))
        assertEquals(1, tpHits.size)
        assertEquals(AutoExitKind.TAKE_PROFIT, tpHits.first().kind)
        assertEquals(0, journalStore.trades.value.count { it.isOpen })
        assertTrue(settingsStore.read().accountBalance > 1000.0)

        // Open a SELL trade on Day 2 and hit SL
        val day2Millis = LocalDate.of(2026, 10, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3600_000L
        val calcSell = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = settingsStore.read().accountBalance,
            riskPercent = 1.0,
            entry = 65000.0,
            stopLoss = 65500.0,
            takeProfit = 64000.0,
        )!!
        journalStore.openTrade(calcSell, note = "تست فروش بیت‌کوین", now = day2Millis)
        val slHits = journalStore.onLiveTick("BTC/USDT", PriceTick(price = 65510.0, at = day2Millis + 15_000L))
        assertEquals(1, slHits.size)
        assertEquals(AutoExitKind.STOP_LOSS, slHits.first().kind)

        // Verify persistence across new JournalStore instance and Monthly PnL Calendar aggregation
        val reopenedStore = JournalStore(context, file = journalFile, settingsStore = settingsStore)
        assertEquals(2, reopenedStore.trades.value.size)

        val calendar = RiskAndCalendarEngine.buildMonthlyCalendar(
            year = 2026,
            month = 10,
            trades = reopenedStore.trades.value,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(31, calendar.daysInMonth)
        assertEquals(2, calendar.totalClosedTrades)
        assertEquals(1, calendar.winningDays)
        assertEquals(1, calendar.losingDays)
        assertEquals(50.0, calendar.monthWinRate ?: 0.0, 0.01)
        assertTrue(calendar.days[9].netPnlUsd > 0.0) // Oct 10 (index 9) is green
        assertTrue(calendar.days[11].netPnlUsd < 0.0) // Oct 12 (index 11) is red
        assertNull(RiskAndCalendarEngine.evaluateAutoExit(reopenedStore.trades.value.first(), PriceTick(2650.0, day2Millis)))
    }

    @Test
    fun `offline 6-hour gap backfill replays OHLC candles chronologically and closes trade at exact candle time`() {
        val context = RuntimeEnvironment.getApplication()
        val journalFile = File(context.filesDir, "test_backfill_journal.json")
        journalFile.delete()
        val settingsStore = SettingsStore(context)
        val journalStore = JournalStore(context, file = journalFile, settingsStore = settingsStore)

        val openedAt = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "XAU/USD",
            accountBalance = 1000.0,
            riskPercent = 1.0,
            entry = 2650.0,
            stopLoss = 2640.0,
            takeProfit = 2670.0,
        )!!
        journalStore.openTrade(calc, note = "معامله قبل از ۶ ساعت قطعی", now = openedAt)

        // Simulate 6 hours of candles where hour 3 spiked to 2672 (hitting TP=2670),
        // even though by hour 6 price came back to 2655!
        val hour1 = openedAt + 1 * 3600_000L
        val hour2 = openedAt + 2 * 3600_000L
        val hour3 = openedAt + 3 * 3600_000L
        val hour6 = openedAt + 6 * 3600_000L
        val gapCandles = listOf(
            Candle(time = hour1, open = 2650.0, high = 2658.0, low = 2645.0, close = 2655.0),
            Candle(time = hour2, open = 2655.0, high = 2665.0, low = 2652.0, close = 2662.0),
            Candle(time = hour3, open = 2662.0, high = 2672.5, low = 2660.0, close = 2664.0),
            Candle(time = hour6, open = 2664.0, high = 2665.0, low = 2653.0, close = 2655.0),
        )

        val summary = RiskAndCalendarEngine.summarizeBackfill(
            symbolInput = "XAU/USD",
            requestedFromMillis = openedAt,
            candles = gapCandles,
            provider = "Yahoo Finance (1m)",
            syncedAt = hour6,
        )
        assertNotNull(summary)
        assertEquals(4, summary!!.candlesCount)
        assertEquals(2672.5, summary.periodHigh, 0.001)
        assertEquals(2645.0, summary.periodLow, 0.001)

        val hits = journalStore.onBackfillCandles("XAU/USD", gapCandles)
        assertEquals(1, hits.size)
        val hit = hits.first()
        assertTrue(hit.fromBackfill)
        assertEquals(AutoExitKind.TAKE_PROFIT, hit.kind)
        assertEquals(2670.0, hit.exitPrice, 0.001)
        assertEquals(hour3, hit.closedAt) // Closed at the exact hour-3 candle when TP was touched!
        assertFalse(journalStore.trades.value.first().isOpen)
    }
}
