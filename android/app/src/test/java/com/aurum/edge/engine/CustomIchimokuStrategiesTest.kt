package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
import com.aurum.edge.core.HiddenCrossEngine
import com.aurum.edge.core.BaseStrategyConfig
import com.aurum.edge.core.BitcoinStrategyModel
import com.aurum.edge.core.IchimokuStrategyEngine
import com.aurum.edge.core.NeuralSelfLearningEngine
import com.aurum.edge.core.StrategyReadinessState
import com.aurum.edge.core.driveBookPlaybook
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.core.PaperTrade
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomIchimokuStrategiesTest {
    private fun flatBars(count: Int, baseTime: Long = 1_700_000_000_000L): List<Candle> =
        (0 until count).map { index ->
            Candle(
                time = baseTime + index * Interval.M15.millis,
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.0,
                volume = 100.0,
                closed = true,
            )
        }

    @Test
    fun `custom methods are explicit opt-in and isolated from neural tuning`() {
        val btc = BitcoinStrategyModel.BTC_52_CANDLE_REGIME
        val parsa = BitcoinStrategyModel.PARSA_52_LINE_TURN

        assertTrue(btc.requiresExplicitOptIn)
        assertTrue(parsa.requiresExplicitOptIn)
        assertFalse(btc.id in BitcoinStrategyModel.activeStrategyIds)
        assertFalse(parsa.id in BitcoinStrategyModel.activeStrategyIds)
        assertFalse(btc.id in AppSettings().enabledStrategyIds)
        assertFalse(parsa.id in AppSettings().enabledStrategyIds)
        assertFalse(btc.neuralLearningEnabled)
        assertFalse(parsa.neuralLearningEnabled)
        assertFalse(btc in BitcoinStrategyModel.neuralLearningModels)
        assertFalse(parsa in BitcoinStrategyModel.neuralLearningModels)
        assertEquals(16, NeuralSelfLearningEngine.INPUT_DIM)
        assertTrue(BitcoinStrategyModel.DRIVE_BOOKS_1_TO_9_UNIFIED_MASTER.isNeoWaveElliottStrategy)
        assertFalse(btc.isNeoWaveElliottStrategy)
        assertFalse(parsa.isNeoWaveElliottStrategy)

        val parsaPlaybook = parsa.driveBookPlaybook()
        assertTrue(parsaPlaybook.ichimokuSettingsFa.contains("Senkou Span B"))
        assertTrue(parsaPlaybook.entryChecklistStepsFa.first().contains("۱۸"))
        assertTrue(parsaPlaybook.specialEdgeAdvantageFa.contains("مستثناست"))
        assertTrue(btc.driveBookPlaybook().entryChecklistStepsFa.any { it.contains("۵۲") })
    }

    @Test
    fun `snapshot reports 52 bar context and true 18 plus SSB plateau`() {
        val snap = IchimokuStrategyEngine.computeSnapshot(flatBars(140))

        assertNotNull(snap)
        snap!!
        assertTrue(snap.senkouSpanBFlatBars >= 18)
        assertEquals(0.0, snap.regressionSlope52AtrPerBar, 1e-6)
        assertTrue(snap.prior52High > snap.prior52Low)
        assertTrue(snap.range52Atr > 0.0)
        assertEquals(Int.MAX_VALUE, snap.bullishTenkanKijunCrossAgeBars)
        assertEquals(Int.MAX_VALUE, snap.bearishTenkanKijunCrossAgeBars)
    }

    @Test
    fun `BTC strategy blocks other symbols and Parsa requires its named checklist`() {
        val bars = flatBars(160)
        val btcWrongSymbol = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.BTC_52_CANDLE_REGIME,
            config = BaseStrategyConfig(),
            macroCandles = bars,
            strategyCandles = bars,
            triggerCandles = bars,
            symbolInput = "ETH/USDT",
        )
        assertEquals(SignalAction.NO_TRADE, btcWrongSymbol.action)
        assertEquals(StrategyReadinessState.BLOCKED, btcWrongSymbol.readiness)
        assertTrue(btcWrongSymbol.summaryFa.contains("فقط روی BTC/USDT"))

        val parsa = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.PARSA_52_LINE_TURN,
            config = BaseStrategyConfig(),
            macroCandles = bars,
            strategyCandles = bars,
            triggerCandles = bars,
            symbolInput = "BTC/USDT",
        )
        assertEquals(SignalAction.NO_TRADE, parsa.action)
        assertEquals(9, parsa.conditions.size)
        assertTrue(parsa.conditions.first().titleFa.contains("Senkou Span B"))
        assertTrue(parsa.conditions.first().detailFa.contains("۱۸ کندل"))
        assertTrue(parsa.conditions.any { it.titleFa.contains("آزادی Chikou") })
        assertTrue(parsa.conditions.any { it.titleFa.contains("حرکت شارپ") })
    }

    @Test
    fun `closed trade from exempt strategy never enters neural ledger`() {
        val file = File.createTempFile("neural-exemption-test", ".json").apply { delete() }
        val engine = NeuralSelfLearningEngine(context = null, storageFile = file)
        val closedParsaTrade = PaperTrade(
            id = "PARSA_EXEMPT_TRADE",
            symbol = "BTC/USDT",
            interval = Interval.M15,
            action = SignalAction.BUY,
            entry = 100.0,
            stopLoss = 98.0,
            takeProfit = 104.0,
            confidence = 80.0,
            riskReward = 2.0,
            openedAt = 1_700_000_000_000L,
            closedAt = 1_700_000_900_000L,
            exitPrice = 104.0,
            pnlUsd = 10.0,
            strategyId = BitcoinStrategyModel.PARSA_52_LINE_TURN.id,
            strategyTitleFa = BitcoinStrategyModel.PARSA_52_LINE_TURN.titleFa,
        )

        engine.recordCompletedTrade(closedParsaTrade)
        engine.syncWithClosedJournalTrades(listOf(closedParsaTrade))

        assertEquals(0, engine.state.value.totalLearnedTrades)
        assertEquals(0, engine.state.value.learnedLedgerRows)
        val neuralEvaluation = engine.evaluateMarketConditions(
            symbolInput = "BTC/USDT",
            action = SignalAction.BUY,
            interval = Interval.M15,
            strategyId = BitcoinStrategyModel.PARSA_52_LINE_TURN.id,
        )
        assertEquals(50.0, neuralEvaluation.winProbabilityPct, 0.0)
        assertFalse(neuralEvaluation.isBlockingTradeNow)
        assertTrue(neuralEvaluation.currentConditionSummaryFa.contains("مستثناست"))
        assertTrue(engine.state.value.autoTunedParameters.perStrategyProfiles.keys.none {
            it == BitcoinStrategyModel.PARSA_52_LINE_TURN.id || it == BitcoinStrategyModel.BTC_52_CANDLE_REGIME.id
        })
        file.delete()
    }

    // ─────────────────────────── هیدن کراس (Hidden Cross) ───────────────────────────

    /** سری با نوار تنکان/کیجون باریک و یک بازهٔ هم‌پوشانی واقعی (کراس برابری). */
    private fun hiddenCrossSeries(count: Int = 160, baseTime: Long = 1_700_000_000_000L): List<Candle> {
        val bars = mutableListOf<Candle>()
        var price = 100.0
        for (i in 0 until count) {
            // فاز آرام (ساخت هم‌پوشانی تنکان/کیجون) و فاز روند ملایم
            val inQuietPhase = i in 100..112
            val drift = if (inQuietPhase) 0.0 else 0.12
            val open = price
            val close = price + drift
            val halfRange = if (inQuietPhase) 0.10 else 0.35
            bars += Candle(
                time = baseTime + i * Interval.M15.millis,
                open = open,
                high = maxOf(open, close) + halfRange,
                low = minOf(open, close) - halfRange,
                close = close,
                volume = 100.0 + (i % 7) * 3.0,
                closed = true,
            )
            price = close
        }
        return bars
    }

    @Test
    fun `hidden cross method is explicit opt-in and isolated from neural tuning`() {
        val hc = BitcoinStrategyModel.HIDDEN_CROSS
        assertEquals("هیدن کراس · کراس برابری، تاج و ابر مخفی", hc.titleFa)
        assertTrue(hc.requiresExplicitOptIn)
        assertFalse(hc.neuralLearningEnabled)
        assertFalse(hc.id in BitcoinStrategyModel.activeStrategyIds)
        assertFalse(hc.id in AppSettings().enabledStrategyIds)
        assertFalse(hc in BitcoinStrategyModel.neuralLearningModels)
        assertFalse(hc.isNeoWaveElliottStrategy)
        assertFalse(hc.isDrivePdfStrategy)
        assertFalse(hc.isTemporarilyDisabled)
        assertEquals(BitcoinStrategyModel.HIDDEN_CROSS, BitcoinStrategyModel.fromId("HIDDEN_CROSS"))

        val playbook = hc.driveBookPlaybook()
        assertEquals(6, playbook.entryChecklistStepsFa.size)
        assertTrue(playbook.entryChecklistStepsFa.any { it.contains("ابر مخفی") })
        assertTrue(playbook.entryChecklistStepsFa.any { it.contains("کراس برابری") })
        assertTrue(playbook.entryChecklistStepsFa.any { it.contains("آنالوگ") })
        assertTrue(playbook.takeProfitAndExitRulesFa.contains("تاج"))
        assertTrue(playbook.stopLossFormulaFa.contains("نوار"))
        assertTrue(playbook.specialEdgeAdvantageFa.contains("اندازه‌گیری"))
    }

    @Test
    fun `hidden cross evaluation exposes hidden cloud and ten unambiguous conditions`() {
        val bars = hiddenCrossSeries()
        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.HIDDEN_CROSS,
            config = BaseStrategyConfig(),
            macroCandles = bars,
            strategyCandles = bars,
            triggerCandles = bars,
            symbolInput = "BTC/USDT",
        )
        assertEquals(10, report.conditions.size)
        assertEquals((1..10).toList(), report.conditions.map { it.index })
        val snap = report.triggerSnapshot!!
        // ابر مخفی = نوار تنکان/کیجون و همیشه سقف >= کف
        assertTrue(snap.hiddenCloudTop >= snap.hiddenCloudBottom)
        assertTrue(snap.hiddenCloudThicknessAtr >= 0.0)
        assertTrue(snap.hiddenCloudThicknessAtr < 5.0)
        assertTrue(snap.hiddenCloudTop > 0.0)
        // شرایط کلیدی روش در چک‌لیست حاضرند
        assertTrue(report.conditions.any { it.titleFa.contains("ابر مخفی") })
        assertTrue(report.conditions.any { it.titleFa.contains("کراس برابری") })
        assertTrue(report.conditions.any { it.titleFa.contains("آنالوگ") })
        assertTrue(report.conditions.any { it.titleFa.contains("تاج") })
        assertTrue(report.conditions.any { it.titleFa.contains("Chikou") })
        // R:R هرگز زیر ۱:۱٫۵ نیست حتی وقتی پلنی ساخته نشود
        assertTrue((report.riskReward ?: 0.0) == 0.0 || (report.riskReward ?: 0.0) >= 1.5)
        assertEquals("HIDDEN_CROSS", report.strategyId)
    }

    @Test
    fun `hidden cross engine finds equal cross and derives crown half of its size`() {
        val bars = hiddenCrossSeries()
        val evaluation = HiddenCrossEngine.evaluate(bars)
        assertTrue(evaluation.atr14 > 0.0)
        assertTrue(evaluation.latestTenkan > 0.0 && evaluation.latestKijun > 0.0)
        // در فاز آرام، تنکان و کیجون روی هم می‌افتند و کراس برابری باید پیدا شود.
        assertTrue("کراس برابری یافت نشد", evaluation.equalCrosses.isNotEmpty())
        val cross = evaluation.activeCross!!
        assertTrue(cross.bars >= HiddenCrossEngine.MIN_EQUAL_CROSS_BARS)
        assertTrue(cross.levelPrice > 0.0)
        // تاج = سطح برابری ± نصف اندازهٔ کراس، در جهت شکست (علامت هم‌جهت با جهت شکست)
        val extension = cross.crownPrice - cross.levelPrice
        if (cross.breakoutDirection == SignalAction.NO_TRADE) {
            // بدون جهت شکست، تاج روی سطح برابری می‌نشیند
            assertEquals(0.0, extension, 1e-9)
        } else {
            assertEquals(cross.breakoutDirection == SignalAction.SELL, extension < 0.0)
            assertTrue(abs(extension) > 0.0)
        }
        // اندازهٔ کراس هرگز صفر نمی‌شود (نسبت‌ها با چهار رقم اعشار نگه داشته می‌شوند)
        assertTrue(cross.crossSizeAtr > 0.0)
        // خروجی آنالوگ یا اندازه‌گیری‌شده است یا صریحاً «نمونهٔ کافی نیست»
        if (!evaluation.analogues.isMeasured) {
            assertTrue(evaluation.analogues.noteFa.contains("کافی نیست"))
        } else {
            assertTrue(evaluation.analogues.agreementShare in 0.0..1.0)
            assertTrue(evaluation.analogues.matchedAtBarsAgo.isNotEmpty())
        }
        assertTrue(evaluation.reasonFa.isNotBlank())
    }

    @Test
    fun `hidden cross refuses to trade with insufficient data`() {
        val short = flatBars(20)
        val evaluation = HiddenCrossEngine.evaluate(short)
        assertEquals(SignalAction.NO_TRADE, evaluation.direction)
        assertEquals(0, evaluation.analogues.samples)
        assertTrue(evaluation.reasonFa.contains("ناکافی"))

        val report = IchimokuStrategyEngine.evaluateBitcoinStrategy(
            activeModel = BitcoinStrategyModel.HIDDEN_CROSS,
            config = BaseStrategyConfig(),
            macroCandles = short,
            strategyCandles = short,
            triggerCandles = short,
            symbolInput = "BTC/USDT",
        )
        assertEquals(SignalAction.NO_TRADE, report.action)
        assertTrue(
            report.readiness == StrategyReadinessState.INSUFFICIENT_DATA ||
                report.readiness == StrategyReadinessState.BLOCKED,
        )
    }
}
