package com.aurum.edge.engine

import com.aurum.edge.core.AppSettings
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
}
