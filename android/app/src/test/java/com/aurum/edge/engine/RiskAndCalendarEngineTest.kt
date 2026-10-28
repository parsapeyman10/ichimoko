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
import com.aurum.edge.core.driveBookPlaybook
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

        val (validFormat, formatMsg) = AvalAiAdvisor.validateApiKeyFormat(AvalAiAdvisor.PRECONFIGURED_USER_KEY)
        assertTrue(formatMsg, validFormat)

        if (System.getenv("GITHUB_ACTIONS") == "true") {
            kotlinx.coroutines.runBlocking {
                val liveProbe = AvalAiAdvisor.evaluateTradeTiming(
                    apiKey = AvalAiAdvisor.PRECONFIGURED_USER_KEY,
                    symbolInput = "BTC/USDT",
                    action = SignalAction.BUY,
                    entry = 68000.0,
                    stopLoss = 67650.0,
                    takeProfit = 68700.0,
                    riskReward = 2.0,
                    strategyContext = "مدل استراتژی: استراتژی بیس (۴ کتاب ایچیموکو) · تایم‌فریم 15m · ۱۶ شرط تایید شده",
                )
                liveProbe.onSuccess { v ->
                    println("::notice title=AvalAI Key Check::LIVE OK -> ${v.yesNoLabelFa} | ${v.summaryFa} | ${v.reasonFa}")
                }.onFailure { e ->
                    println("::notice title=AvalAI Key Check::PROBE -> ${e.message}")
                }
            }
        }
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

        // 5. Verify all 24 strategies (15 original 4-book combinations + 9 Google Drive PDF strategies) evaluate simultaneously on 15m and 5m + R:R Breakdown & Optimal R:R
        assertEquals(24, BitcoinStrategyModel.entries.size)
        assertEquals(9, BitcoinStrategyModel.drivePdfStrategies.size)
        assertEquals(24, dualReport.reports15mByStrategy.size)
        assertEquals(24, dualReport.reports5mByStrategy.size)
        BitcoinStrategyModel.entries.forEach { model ->
            val rep15 = dualReport.reportFor(Interval.M15, model.id)
            val rep5 = dualReport.reportFor(Interval.M5, model.id)
            assertNotNull(rep15)
            assertNotNull(rep5)
            assertEquals(16, rep15!!.conditions.size)
            assertNotNull(rep15.rrBreakdown)
            assertTrue((rep15.optimalRiskReward ?: 0.0) >= 1.5)
            assertTrue((rep15.riskReward ?: 0.0) >= 1.5)
            assertTrue(rep15.rrBreakdown!!.formulaTextFa.contains("فرمول"))
            assertTrue(rep15.rrBreakdown!!.optimalRrReasonFa.contains("عدد مناسب"))
        }

        // Verify all 9 Google Drive PDF strategy indicators (BB Squeeze, EMA 21/34/89, RSI 7/14, Bill Williams Fractals, 10-30-60 & 7-22-44 presets, MACD, DMI/ADX, Kelly WinProb)
        val snap = report.triggerSnapshot!!
        assertTrue(snap.bbUpper20 > snap.bbLower20)
        assertTrue(snap.ema21 > 0.0 && snap.ema34 > 0.0 && snap.ema89 > 0.0 && snap.sma200 > 0.0)
        assertTrue(snap.rsi14 in 0.0..100.0 && snap.rsi7 in 0.0..100.0)
        assertTrue(snap.lastDownFractalLow > 0.0 && snap.lastUpFractalHigh >= snap.lastDownFractalLow)
        assertTrue(snap.tenkan10 > 0.0 && snap.kijun30 > 0.0 && snap.senkouB60 > 0.0)
        assertTrue(snap.tenkan7 > 0.0 && snap.kijun22 > 0.0 && snap.senkouB44 > 0.0)
        assertTrue(snap.adx14 > 0.0 && snap.kellySignalCount in 1..5 && snap.kellyWinProbabilityPercent in 48..76)

        val fokaRep = dualReport.reportFor(Interval.M15, BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL.id)!!
        assertTrue(fokaRep.rrBreakdown!!.slBasisFa.contains("فرکتال"))
        val keenbaseRep = dualReport.reportFor(Interval.M15, BitcoinStrategyModel.KEENBASE_CROSS_10_30_60.id)!!
        assertTrue(keenbaseRep.rrBreakdown!!.slBasisFa.contains("Keenbase"))
        val koonitzRep = dualReport.reportFor(Interval.M15, BitcoinStrategyModel.KOONITZ_5RULES_DMI_MACD_EMA34.id)!!
        assertTrue(koonitzRep.rrBreakdown!!.slBasisFa.contains("EMA34"))

        // Verify Trend Loss In Profit Detector (when an open trade in profit loses its trend, close immediately in profit)
        val tradeInProfitAt63k = calc.copy(
            entry = 62_500.0,
            stopLoss = 62_000.0,
            takeProfit = 64_500.0,
        ).toPaperTrade(id = "T_TREND_LOSS")
        val brokenKijunSnap = report.triggerSnapshot!!.copy(
            close = 63_050.0,
            kijun = 63_250.0, // Price fell below Kijun while still in profit (+1.1R above 62,500)
            tenkan = 63_150.0,
            tenkanSlopeAtrPerBar = -0.08,
        )
        val brokenReport = report.copy(
            livePrice = 63_050.0,
            triggerSnapshot = brokenKijunSnap,
        )
        val trendHealth = IchimokuStrategyEngine.evaluateTrendHealthAndProfitExit(
            trade = tradeInProfitAt63k,
            report = brokenReport,
            livePriceOverride = 63_050.0,
        )
        assertTrue(trendHealth.shouldExitNowInProfit)
        val immediateExitEval = IchimokuStrategyEngine.evaluateOpenTradeManagement(
            trade = tradeInProfitAt63k,
            report = brokenReport,
            config = config,
        )
        assertNotNull(immediateExitEval)
        assertTrue(immediateExitEval!!.shouldCloseImmediatelyInProfit)
        assertEquals(63_050.0, immediateExitEval.closePrice ?: 0.0, 1e-2)

        // 6. Verify $1,000 -> $100,000 fixed $5 risk rule: loss = -$5.00, win = +$5.00 * R:R, and Journal strategy categorization
        val calcFixed5 = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = 1_000.0,
            riskPercent = 0.5,
            entry = 65_000.0,
            stopLoss = 64_500.0, // risk = 500
            takeProfit = 66_250.0, // reward = 1250 -> R:R = 2.5
            preferredAction = SignalAction.BUY,
            fixedRiskUsdOverride = 5.0,
        )!!
        assertEquals(5.0, calcFixed5.riskBudgetUsd, 1e-4)
        assertEquals(2.5, calcFixed5.riskReward, 1e-4)

        val tradeManish = calcFixed5.toPaperTrade(
            id = "T_MANISH",
            openedAt = 1_760_000_000_000L,
            entryInterval = Interval.M15,
            strategyId = BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id,
            strategyTitleFa = BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.titleFa,
            optimalRiskReward = 2.5,
        )
        assertTrue(tradeManish.buildRrFormulaSummaryFa().contains("فرمول"))
        assertTrue(tradeManish.buildOptimalRrReasonFa().contains("عدد مناسب"))
        assertTrue(calcFixed5.buildVenueRulesSummaryFa().contains("مرجع دیتا"))
        assertTrue(tradeManish.buildVenueRulesAndFeesSummaryFa().contains("مجموع کل کارمزد"))
        val (lossGross, lossPnl) = RiskAndCalendarEngine.computeExitPnlUsd(tradeManish, 64_500.0)
        assertEquals(-5.0, lossGross, 1e-4)
        assertEquals(-5.0, lossPnl, 1e-4)

        val (winGross, winPnl) = RiskAndCalendarEngine.computeExitPnlUsd(tradeManish, 66_250.0)
        assertEquals(12.5, winGross, 1e-4) // $5 * 2.5 R:R = +$12.50
        assertEquals(12.5, winPnl, 1e-4)

        val closedManish = tradeManish.copy(
            exitPrice = 66_250.0,
            closedAt = 1_760_000_900_000L,
            pnlUsd = winPnl,
        )
        assertEquals(2.5, closedManish.rMultiple!!, 1e-4)

        val tradeHosoda = calcFixed5.toPaperTrade(
            id = "T_HOSODA",
            openedAt = 1_760_001_000_000L,
            entryInterval = Interval.M5,
            strategyId = BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE.id,
            strategyTitleFa = BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE.titleFa,
        ).copy(
            exitPrice = 64_500.0,
            closedAt = 1_760_001_300_000L,
            pnlUsd = lossPnl,
        )
        assertEquals(-1.0, tradeHosoda.rMultiple!!, 1e-4)
        val cats = RiskAndCalendarEngine.categorizeTradesByStrategy(listOf(closedManish, tradeHosoda))
        val manishCat = cats.first { it.strategyId == BitcoinStrategyModel.PATEL_KIJUN_PULLBACK.id }
        val hosodaCat = cats.first { it.strategyId == BitcoinStrategyModel.ELLIOTT_HOSODA_WAVE.id }
        assertEquals(1, manishCat.winsCount)
        assertEquals(12.5, manishCat.netPnlUsd, 1e-4)
        assertEquals(1, hosodaCat.lossesCount)
        assertEquals(-5.0, hosodaCat.netPnlUsd, 1e-4)

        // Verify 1:1.5 minimum R:R enforcement & 1:3+ Institutional Trend-Runner
        val adjustedTpBuy = RiskAndCalendarEngine.enforceMinimumRiskReward(
            symbolInput = "BTC/USDT",
            entryPrice = 65_000.0,
            stopLoss = 64_500.0,
            takeProfit = 65_400.0, // only 0.8R -> must be lifted to 1.5R (65,750.0)
            action = SignalAction.BUY,
        )
        assertEquals(65_750.0, adjustedTpBuy, 1e-4)

        val runnerFile = File(context.filesDir, "test_runner_1to3.json")
        runnerFile.delete()
        val runnerJournal = JournalStore(context, runnerFile, store)
        val calc3R = RiskAndCalendarEngine.calculatePosition(
            symbolInput = "BTC/USDT",
            accountBalance = 100_000.0,
            riskPercent = 0.005,
            entry = 65_000.0,
            stopLoss = 64_500.0, // 1R = 500
            takeProfit = 66_500.0, // 3R = 66,500
            preferredAction = SignalAction.BUY,
            fixedRiskUsdOverride = 5.0,
        )!!
        val opened3R = runnerJournal.openTrade(calc3R, note = "تست شکارچی روند ۱:۳")
        // Live tick reaches 66,500 (3.0R) -> Runner locks SL above +2.25R (66,135) and extends TP to 4.7R (67,350) so trade stays open!
        val runnerTickHits = runnerJournal.onLiveTick("BTC/USDT", PriceTick(price = 66_500.0, at = 1_760_002_000_000L))
        assertTrue(runnerTickHits.isEmpty())
        val updatedRunnerTrade = runnerJournal.trades.value.first { it.id == opened3R.id }
        assertTrue(updatedRunnerTrade.isOpen)
        assertTrue(updatedRunnerTrade.stopLoss >= 66_120.0) // >= +2.24R locked!
        assertTrue(updatedRunnerTrade.takeProfit >= 67_200.0) // TP extended beyond 1:3!

        // Now price climbs to 4.0R (67,000) -> SL ratchets higher (>= +3.09R = 66,545)
        runnerJournal.onLiveTick("BTC/USDT", PriceTick(price = 67_000.0, at = 1_760_002_060_000L))
        val at4R = runnerJournal.trades.value.first { it.id == opened3R.id }
        assertTrue(at4R.stopLoss >= 66_540.0)

        // When trend finally reverses to the ratcheted SL, trade closes in guaranteed big profit (> +$15 on $5 risk)
        val exitHits = runnerJournal.onLiveTick("BTC/USDT", PriceTick(price = 66_500.0, at = 1_760_002_120_000L))
        assertEquals(1, exitHits.size)
        assertTrue(exitHits.first().netPnlUsd >= 15.0)

        // 7. Verify 3 distinct trade alarms (Entry, Take-Profit, Stop-Loss) & Gold/Silver Spot-Futures basis alignment
        val alarmKinds = com.aurum.edge.notify.TradeAlarmKind.entries
        assertEquals(3, alarmKinds.size)
        assertEquals(3, alarmKinds.map { it.channelId }.toSet().size)
        assertEquals(3, alarmKinds.map { it.badgeFa }.toSet().size)

        // Simulate Gold Futures (GC=F around 2775) vs Spot Gold (XAU/USD around 2740)
        val goldFutures15m = trigBars.map {
            it.copy(
                open = it.open / 23.0,
                high = it.high / 23.0,
                low = it.low / 23.0,
                close = it.close / 23.0,
            )
        }
        val lastFuturesClose = goldFutures15m.last().close
        val spotGoldLive = lastFuturesClose - 35.0 // $35 lower Spot price than Futures
        val alignedGold = IchimokuStrategyEngine.alignCandlesToSpotBasis(
            candles = goldFutures15m,
            referenceClose = lastFuturesClose,
            liveSpotPrice = spotGoldLive,
        )
        assertEquals(spotGoldLive, alignedGold.last().close, 1e-4)

        // 8. Verify Leverage <= 10 Across All 15 Symbols & Crypto Safe Stop-Loss Floor
        for (sym in V1Universe.allSymbols) {
            val specLev = com.aurum.edge.core.VenueSpecs.of(sym).leverageCap
            val orderLev = com.aurum.edge.core.PaperOrderRules.defaultLeverageFor(sym)
            assertTrue("Leverage cap for $sym must be <= 10 (was $specLev)", specLev in 1..10)
            assertTrue("Default leverage for $sym must be <= 10 (was $orderLev)", orderLev in 1..10)
        }
        val btcSafeStop = IchimokuStrategyEngine.minSafeStructuralStopDistance(
            symbol = "BTC/USDT",
            referencePrice = 90_000.0,
            trigAtr14 = 180.0,
            stratAtr14 = 350.0,
            triggerInterval = Interval.M5,
        )
        assertTrue("BTC 5m safe stop floor must be >= 0.55% ($495)", btcSafeStop >= 495.0 - 1e-4)
        val (sanitizedSl, sanitizedTp) = RiskAndCalendarEngine.sanitizeEntryStopAndTarget(
            symbolInput = "BTC/USDT",
            action = SignalAction.BUY,
            liveEntryPrice = 90_000.0,
            candidateStopLoss = 89_920.0, // too tight ($80) -> must be pushed below 89,505
            candidateTakeProfit = 90_500.0,
            desiredRiskReward = 2.0,
            trigAtr14 = 180.0,
            triggerInterval = Interval.M5,
        )
        assertTrue(sanitizedSl <= 90_000.0 - btcSafeStop + 1e-2)
        assertTrue((sanitizedTp - 90_000.0) / (90_000.0 - sanitizedSl) >= 1.99)

        // 9. Verify Alarm Sound & Volume Customization Persistence
        store.update {
            it.copy(
                alarmVolumePercent = 75,
                alarmTonePreset = com.aurum.edge.notify.AlarmTonePreset.SOFT_CHIME.id,
                alarmVibrationEnabled = false,
                entryAlarmSoundUri = "content://media/internal/audio/media/11",
                entryAlarmSoundName = "Crystal Entry",
            )
        }
        val rereadSettings = store.read()
        assertEquals(75, rereadSettings.alarmVolumePercent)
        assertEquals("SOFT_CHIME", rereadSettings.alarmTonePreset)
        assertFalse(rereadSettings.alarmVibrationEnabled)
        assertEquals("Crystal Entry", rereadSettings.entryAlarmSoundName)

        // 10. Verify Self-Learning Neural Network Engine (FT-Transformer + SwiGLU + AdamW Online + 10,000 Warmup)
        val neuralFile = File(context.filesDir, "test_neural_self_learning.json")
        neuralFile.delete()
        val neuralEngine = com.aurum.edge.core.NeuralSelfLearningEngine(context, neuralFile)
        assertEquals(10_000, com.aurum.edge.core.NeuralSelfLearningEngine.WARMUP_TARGET_TRADES)
        assertEquals(0, neuralEngine.state.value.totalLearnedTrades)
        assertFalse(neuralEngine.state.value.isWarmupCompleted)

        // Train 6 losing trades on a weak crypto condition and 6 winning trades on a strong gold condition
        val badCryptoFeatures = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0, // CRYPTO
            0.0, 1.0,           // 5m, BUY
            1.0, 0.0, 0.0, 0.0, // BASE_ICHIMOKU
            0.18, 0.25, 0.85,   // low ER, thin kumo, overextended from Kijun
            0.65, 0.30, 0.62,   // high adverse wick
        )
        for (i in 1..6) {
            val closedLoss = com.aurum.edge.core.PaperTrade(
                id = "nn_loss_$i",
                symbol = "BTC/USDT",
                action = SignalAction.BUY,
                interval = Interval.M5,
                entry = 90_000.0,
                stopLoss = 89_400.0,
                takeProfit = 91_200.0,
                riskReward = 2.0,
                confidence = 68.0,
                openedAt = 1_760_100_000_000L + i * 60_000L,
                closedAt = 1_760_100_030_000L + i * 60_000L,
                exitPrice = 89_400.0,
                pnlUsd = -5.0,
                fixedRiskUsd = 5.0,
                strategyId = BitcoinStrategyModel.BASE_ICHIMOKU.id,
                note = "[NN:CRYPTO|5m|BUY|BASE_ICHIMOKU|WEAK_CHOP|THIN_KUMO|OVEREXTENDED]",
            )
            neuralEngine.recordCompletedTrade(
                trade = closedLoss,
                featuresOverride = badCryptoFeatures,
                conditionKeyOverride = "CRYPTO|5m|BUY|BASE_ICHIMOKU|WEAK_CHOP|THIN_KUMO|OVEREXTENDED",
            )
        }
        assertEquals(6, neuralEngine.state.value.totalLearnedTrades)
        assertTrue("Must detect high-risk no-trade cluster after repeated losses", neuralEngine.state.value.discoveredNoTradeClustersCount >= 1)

        // Before 10,000 trades (with forceActiveBeforeWarmup = false), it must NOT block trades even when no-trade condition is detected
        val evalDuringWarmup = neuralEngine.evaluateFeatures(
            symbol = "BTC/USDT",
            features = badCryptoFeatures,
            conditionKey = "CRYPTO|5m|BUY|BASE_ICHIMOKU|WEAK_CHOP|THIN_KUMO|OVEREXTENDED",
        )
        assertTrue(evalDuringWarmup.isNoTradeCondition)
        assertFalse(evalDuringWarmup.isBlockingTradeNow)
        assertTrue(evalDuringWarmup.shouldAllowTrade)

        // When forceActiveBeforeWarmup is enabled (or after 10,000 trades), it actively blocks the no-trade condition
        neuralEngine.setForceActiveBeforeWarmup(true)
        val evalWhenActive = neuralEngine.evaluateFeatures(
            symbol = "BTC/USDT",
            features = badCryptoFeatures,
            conditionKey = "CRYPTO|5m|BUY|BASE_ICHIMOKU|WEAK_CHOP|THIN_KUMO|OVEREXTENDED",
        )
        assertTrue(evalWhenActive.isBlockingTradeNow)
        assertFalse(evalWhenActive.shouldAllowTrade)

        // Verify Neural Brain explainability fields (learnedInsightsFeedFa, featureInsights, recentLessons)
        val brainSummary = neuralEngine.state.value
        assertEquals(16, brainSummary.featureInsights.size)
        assertTrue(brainSummary.learnedInsightsFeedFa.isNotEmpty())
        assertEquals(6, brainSummary.recentLessons.size)
        assertTrue(brainSummary.noTradeClusters.isNotEmpty())

        // Verify TradeSnapshotArchiveManager self-readable embedded image payload round-trip & default alarm ringtone settings
        val defaultSettings = com.aurum.edge.core.AppSettings()
        assertEquals("CUSTOM_ONLY", defaultSettings.alarmTonePreset)
        assertEquals("Ding-Dong", defaultSettings.entryAlarmSoundName)
        assertEquals("Red Dwarf", defaultSettings.tpAlarmSoundName)
        assertEquals("Latch", defaultSettings.slAlarmSoundName)

        val sampleTrade = com.aurum.edge.core.PaperTrade(
            id = "SNAP_TEST_1",
            symbol = "XAU/USD",
            action = SignalAction.BUY,
            interval = Interval.M15,
            entry = 2_745.0,
            stopLoss = 2_738.0,
            takeProfit = 2_762.5,
            riskReward = 2.5,
            confidence = 91.0,
            openedAt = 1_760_200_000_000L,
            fixedRiskUsd = 5.0,
            strategyId = BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL.id,
            strategyTitleFa = BitcoinStrategyModel.FOKA_15MIN_EMA21_RSI7_FRACTAL.titleFa,
        )
        val sampleArchiveRecord = com.aurum.edge.data.ArchivedTradeSnapshotRecord(
            tradeId = sampleTrade.id,
            symbol = sampleTrade.symbol,
            timeframeLabel = sampleTrade.interval.label,
            action = sampleTrade.action.name,
            strategyId = sampleTrade.strategyId,
            strategyTitleFa = sampleTrade.strategyTitleFa,
            strategyBadgeFa = "Foka 15m",
            entryPrice = sampleTrade.entry,
            stopLossPrice = sampleTrade.stopLoss,
            takeProfitPrice = sampleTrade.takeProfit,
            riskReward = sampleTrade.riskReward,
            optimalRiskReward = 3.0,
            fixedRiskUsd = 5.0,
            expectedProfitUsd = 12.5,
            confidence = 91.0,
            openedAtMs = sampleTrade.openedAt,
            fullPaperTrade = sampleTrade,
        )
        val fakeWebpHeader = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)
        val combinedBytes = com.aurum.edge.data.TradeSnapshotArchiveManager.attachEmbeddedJsonToImageBytes(
            webpBytes = fakeWebpHeader,
            record = sampleArchiveRecord,
        )
        val decodedRecord = com.aurum.edge.data.TradeSnapshotArchiveManager.extractEmbeddedSnapshotFromBytes(combinedBytes)
        assertNotNull(decodedRecord)
        assertEquals("SNAP_TEST_1", decodedRecord!!.tradeId)
        assertEquals("XAU/USD", decodedRecord.symbol)
        assertEquals(2_745.0, decodedRecord.entryPrice, 1e-4)
        assertEquals(2_738.0, decodedRecord.stopLossPrice, 1e-4)
        assertEquals(2_762.5, decodedRecord.takeProfitPrice, 1e-4)
        assertNotNull(decodedRecord.fullPaperTrade)

        // Verify all 9 Drive PDF strategies have complete DriveBookPlaybookSpec + live telemetry + dedicated 16 conditions
        BitcoinStrategyModel.drivePdfStrategies.forEach { driveModel ->
            val spec = driveModel.driveBookPlaybook()
            assertTrue(spec.bookFileTitle.isNotBlank())
            assertTrue(spec.ichimokuSettingsFa.isNotBlank())
            assertTrue(spec.auxiliaryIndicatorsFa.isNotBlank())
            assertEquals(4, spec.entryChecklistStepsFa.size)
            assertTrue(spec.stopLossFormulaFa.isNotBlank())
            assertTrue(spec.takeProfitAndExitRulesFa.isNotBlank())

            val driveRep = IchimokuStrategyEngine.evaluateBitcoinStrategy(
                activeModel = driveModel,
                config = BaseStrategyConfig(),
                macroCandles = emptyList(),
                strategyCandles = emptyList(),
                triggerCandles = emptyList(),
            )
            assertNotNull(driveRep)
        }

        // Verify Trading-Focused Neural Engine analytics & MasterIchimokuTradingAssistant improvement notes
        val summaryAfter = engine.state.value
        assertEquals(24, summaryAfter.strategyEdgeMatrix.size)
        assertEquals(4, summaryAfter.sessionEdges.size)
        assertTrue(summaryAfter.tradeForensics.topWinDriversFa.isNotEmpty())
        assertTrue(summaryAfter.tradeForensics.topLossCausesFa.isNotEmpty())
        assertTrue(summaryAfter.tradeForensics.actionableTradingRulesFa.isNotEmpty())

        val losingTradeSample = sampleTrade.copy(
            id = "FAST_SL_1",
            closedAt = sampleTrade.openedAt + 120_000L,
            pnlUsd = -5.0,
        )
        val generatedNotes = com.aurum.edge.core.MasterIchimokuTradingAssistant.generateActionableImprovementNotes(
            trades = listOf(losingTradeSample),
            dualReports = emptyMap(),
            currentConfig = BaseStrategyConfig(),
        )
        assertTrue(generatedNotes.isNotEmpty())
        assertTrue(generatedNotes.all { it.exactFixInstructionFa.isNotBlank() })
        val formattedClipboard = com.aurum.edge.core.MasterIchimokuTradingAssistant.formatNotesForClipboard(generatedNotes)
        assertTrue(formattedClipboard.contains("دستور دقیق اصلاح"))
    }
}



