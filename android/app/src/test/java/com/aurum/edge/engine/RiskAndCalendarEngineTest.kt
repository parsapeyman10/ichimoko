package com.aurum.edge.engine

import android.content.Context
import com.aurum.edge.core.AutoExitKind
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.CustomStrategySlot
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.RiskAndCalendarEngine
import com.aurum.edge.core.Smart15mSyncPlanner
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.SymbolPollTier
import com.aurum.edge.core.V1Universe
import com.aurum.edge.data.AvalAiAdvisor
import com.aurum.edge.data.JournalStore
import com.aurum.edge.data.SettingsStore
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
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

    @Test
    fun `avalai pre-trade gate parses approval and rejection verdicts and persists toggle in SettingsStore`() {
        assertEquals(
            "https://api.avalai.ir/v1/chat/completions",
            AvalAiAdvisor.resolveChatCompletionsUrl("https://api.avalai.ir/v1"),
        )
        assertEquals(
            "https://api.avalai.ir/v1/chat/completions",
            AvalAiAdvisor.resolveChatCompletionsUrl("https://api.avalai.ir"),
        )

        val approvedPayload = """
            {
              "choices": [
                {
                  "message": {
                    "content": "```json\n{\"approve\": true, \"confidence\": 88, \"summary_fa\": \"تایید ورود در روند صعودی\", \"reason_fa\": \"قیمت بالای کیجون‌سن و نسبت R:R برابر ۱:۲ است.\"}\n```"
                  }
                }
              ]
            }
        """.trimIndent()
        val approved = AvalAiAdvisor.parseCompletionResponse(
            rawResponseJson = approvedPayload,
            modelUsed = "gpt-4o-mini",
            symbol = "BTC/USDT",
            action = SignalAction.BUY,
        )
        assertTrue(approved.approved)
        assertEquals(88, approved.confidence)
        assertEquals("تایید ورود در روند صعودی", approved.summaryFa)

        val rejectedPayload = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"approve\": false, \"confidence\": 91, \"summary_fa\": \"رد ورود — بازار رنج و پرریسک است\", \"reason_fa\": \"الان وقت معامله نیست چون قیمت داخل ابر کومو گیر کرده است.\"}"
                  }
                }
              ]
            }
        """.trimIndent()
        val rejected = AvalAiAdvisor.parseCompletionResponse(
            rawResponseJson = rejectedPayload,
            modelUsed = "gpt-4o",
            symbol = "BTC/USDT",
            action = SignalAction.SELL,
        )
        assertFalse(rejected.approved)
        assertEquals(91, rejected.confidence)

        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        store.update {
            it.copy(
                avalAiEnabled = true,
                newsAiApiKey = "aa-test-key-123",
                newsAiModel = "gemini-2.0-flash",
                newsAiBaseUrl = "https://api.avalai.ir/v1",
            )
        }
        val reloaded = SettingsStore(context).read()
        assertTrue(reloaded.avalAiEnabled)
        assertEquals("aa-test-key-123", reloaded.newsAiApiKey)
        assertEquals("gemini-2.0-flash", reloaded.newsAiModel)
    }

    @Test
    fun `base ichimoku strategy evaluates 3 timeframes hosoda targets preserve mode and enforces single active strategy`() {
        // 1. Verify exclusive single-active strategy selection in SettingsStore
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsStore(context)
        assertEquals(BitcoinStrategyModel.BASE_ICHIMOKU.id, store.read().selectedBitcoinStrategyId)

        // Activate Patel model -> Base is no longer the active ID
        store.update {
            it.copy(
                selectedBitcoinStrategyId = BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id,
                autoPaperTrading = true,
            )
        }
        assertEquals(BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id, store.read().selectedBitcoinStrategyId)
        assertEquals(BitcoinStrategyModel.PATEL_KIJUN_PULLBACK, store.read().activeBitcoinStrategyModel())

        // Add a custom strategy slot and activate it -> replaces previous active strategy
        val customSlot = CustomStrategySlot(
            id = "CUSTOM_TEST_1",
            titleFa = "استراتژی شخصی تست ۱",
            summaryFa = "نسخه سفارشی بر پایه استراتژی بیس",
            baseModelId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
            config = BaseStrategyConfig(minRiskReward = 2.0),
        )
        store.update {
            it.copy(
                customStrategies = listOf(customSlot),
                selectedBitcoinStrategyId = customSlot.id,
            )
        }
        val reloaded = SettingsStore(context).read()
        assertEquals("CUSTOM_TEST_1", reloaded.selectedBitcoinStrategyId)
        assertEquals("استراتژی شخصی تست ۱", reloaded.activeBitcoinStrategyTitleFa())
        assertEquals(2.0, reloaded.activeBitcoinStrategyConfig().minRiskReward, 0.001)

        // Switch back to BASE_ICHIMOKU -> custom strategy is automatically deactivated
        store.update { it.copy(selectedBitcoinStrategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id) }
        assertEquals(BitcoinStrategyModel.BASE_ICHIMOKU.id, store.read().selectedBitcoinStrategyId)

        // 2. Build a realistic 90-bar bullish trend + pullback bounce series for BTC/USDT
        val baseTime = 1_760_000_000_000L
        fun buildBullishPullbackSeries(stepMs: Long): List<Candle> {
            val list = mutableListOf<Candle>()
            for (i in 0 until 90) {
                val base = 60_000.0 + i * 35.0
                val (o, h, l, c) = when {
                    // Bars 85..87: controlled pullback toward Tenkan/Kijun without breaking 9-bar low
                    i in 85..87 -> {
                        listOf(base - 40.0, base + 200.0, base - 240.0, base - 110.0)
                    }
                    // Bar 88: stabilize near Tenkan
                    i == 88 -> {
                        listOf(base - 90.0, base + 250.0, base - 220.0, base + 30.0)
                    }
                    // Bar 89 (latest closed): bullish bounce closing back above Tenkan with fresh high
                    i == 89 -> {
                        listOf(base - 80.0, base + 320.0, base - 200.0, base + 180.0)
                    }
                    else -> {
                        listOf(base - 60.0, base + 300.0, base - 300.0, base + 120.0)
                    }
                }
                list += Candle(
                    time = baseTime + i * stepMs,
                    open = o,
                    high = h,
                    low = l,
                    close = c,
                    volume = 120.0 + i,
                    closed = true,
                )
            }
            return list
        }

        val macroBars = buildBullishPullbackSeries(Interval.H4.millis)
        val stratBars = buildBullishPullbackSeries(Interval.H1.millis)
        val trigBars = buildBullishPullbackSeries(Interval.M15.millis)
        val trig5mBars = buildBullishPullbackSeries(Interval.M5.millis)

        val config = BaseStrategyConfig()
        val dualReport = IchimokuStrategyEngine.evaluateDualTimeframeStrategy(
            activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
            config = config,
            candles4h = macroBars,
            candles1h = stratBars,
            candles15m = trigBars,
            candles5m = trig5mBars,
        )
        assertEquals(Interval.M15, dualReport.report15m.triggerInterval)
        assertEquals(Interval.M5, dualReport.report5m.triggerInterval)
        assertTrue(dualReport.report15m.summaryFa.contains("[تایم 15m]"))
        assertTrue(dualReport.report5m.summaryFa.contains("[تایم 5m]"))

        val report = dualReport.report15m

        assertEquals(IchimokuStrategyEngine.BTC_SYMBOL, report.symbol)
        assertEquals(16, report.conditions.size)
        assertTrue(
            report.conditions.joinToString(" | ") { "${it.index}:${it.passed}(${it.detailFa})" },
            report.conditions.all { it.passed }
        )
        assertEquals(StrategyReadinessState.SIGNAL_BUY, report.readiness)
        assertEquals(SignalAction.BUY, report.action)
        assertTrue(report.coreTrendScore >= 80.0)
        assertNotNull(report.sniperOptimalEntry)
        assertTrue(report.sniperEntryScore >= 50.0)
        assertNotNull(report.waveProjection)
        assertTrue((report.targetN ?: 0.0) > report.livePrice)
        assertTrue((report.suggestedStopLoss ?: Double.MAX_VALUE) < report.livePrice)

        // Verify native Ichimoku chart series & Future Kumo projection
        val chartSeries = IchimokuStrategyEngine.computeChartSeries(trigBars)
        assertNotNull(chartSeries)
        assertEquals(25, chartSeries!!.futureKumo.size)
        assertEquals(trigBars.size, chartSeries.bars.size)

        // 3. Test Break-Even and Preserve Mode on an open BTC/USDT trade
        val journal = JournalStore(context, File(context.filesDir, "test_btc_preserve.json"), store)
        journal.clear()
        val calc = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = 5_000.0,
            riskPercent = 1.0,
            entry = 68_000.0,
            stopLoss = 67_000.0,
            takeProfit = 72_000.0,
            preferredAction = SignalAction.BUY,
        )!!
        val opened = journal.openTrade(calc, note = "تست استراتژی بیس")
        val preserveEval = IchimokuStrategyEngine.evaluateOpenTradeManagement(
            trade = opened,
            report = report.copy(livePrice = 71_200.0), // > 3R profit
            config = config,
        )
        assertNotNull(preserveEval)
        assertTrue(preserveEval!!.newStopLoss > opened.entry)

        // 4. Verify all 15 symbols in V1Universe are supported on 15m timeframe + Smart15mSyncPlanner
        assertEquals(15, V1Universe.allSymbols.size)
        val eurusdReport = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.BASE_ICHIMOKU,
            config = config,
            macroCandles = macroBars.map { it.copy(open = it.open / 55_000.0, high = it.high / 55_000.0, low = it.low / 55_000.0, close = it.close / 55_000.0) },
            strategyCandles = stratBars.map { it.copy(open = it.open / 55_000.0, high = it.high / 55_000.0, low = it.low / 55_000.0, close = it.close / 55_000.0) },
            triggerCandles = trigBars.map { it.copy(open = it.open / 55_000.0, high = it.high / 55_000.0, low = it.low / 55_000.0, close = it.close / 55_000.0) },
            symbolInput = "EUR/USD",
        )
        assertEquals("EUR/USD", eurusdReport.symbol)
        assertEquals(16, eurusdReport.conditions.size)
        assertTrue(eurusdReport.livePrice in 1.0..1.5)

        // Verify Smart15mSyncPlanner candle-boundary alignment and delta fetch sizing
        val sampleNow = 1_760_000_120_000L // inside a 15m bucket
        val bucket15m = Smart15mSyncPlanner.currentCandleBucket(sampleNow, Interval.M15)
        assertFalse(
            Smart15mSyncPlanner.shouldFetchCandlesFromNetwork(
                lastCachedBucket = bucket15m,
                cachedBarCount = 110,
                nowMillis = sampleNow + 60_000L,
                interval = Interval.M15,
            )
        )
        assertTrue(
            Smart15mSyncPlanner.shouldFetchCandlesFromNetwork(
                lastCachedBucket = bucket15m,
                cachedBarCount = 110,
                nowMillis = sampleNow + Interval.M15.millis,
                interval = Interval.M15,
            )
        )
        val deltaLimit = Smart15mSyncPlanner.computeOptimalFetchLimit(
            lastClosedBarTimeMs = sampleNow - Interval.M15.millis,
            cachedBarCount = 110,
            nowMillis = sampleNow,
            interval = Interval.M15,
            fullLimit = 110,
        )
        assertEquals(4, deltaLimit)

        val openTier = Smart15mSyncPlanner.classifySymbolTier(
            symbol = "BTC/USDT",
            hasOpenTrade = true,
            isFocusedSymbol = false,
            readiness = StrategyReadinessState.BLOCKED,
            nowMillis = sampleNow,
        )
        assertEquals(SymbolPollTier.OPEN_POSITION, openTier)
        assertEquals(15_000L, openTier.tickIntervalMs)
    }
}

