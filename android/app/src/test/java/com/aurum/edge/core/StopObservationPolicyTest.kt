package com.aurum.edge.core

import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.Interval
import com.aurum.edge.domain.SignalAction
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class StopObservationPolicyTest {
    private val dt = Interval.M5.millis
    private val start = 1_000_000L
    private fun trade(action: SignalAction = SignalAction.BUY) = PaperTrade(
        id = "t", symbol = "BTC/USDT", interval = Interval.M5, action = action,
        entry = 100.0, initialStopLoss = if (action == SignalAction.BUY) 90.0 else 110.0,
        stopLoss = if (action == SignalAction.BUY) 102.0 else 98.0,
        takeProfit = if (action == SignalAction.BUY) 160.0 else 40.0,
        confidence = 80.0, riskReward = 3.0, openedAt = start, fixedRiskUsd = 10.0,
        positionOz = 1.0, netSettlementEnabled = true, netBreakEvenObservedAt = start + 1,
    )
    private fun bars(buy: Boolean = true): List<Candle> = (0 until 100).map { i ->
        val x = if (buy) 105.0 + i * 0.2 else 95.0 - i * 0.2
        Candle(start + i * dt, x, x + 0.5, x - 0.5, x + if (buy) 0.1 else -0.1, closed = true)
    }
    private val now get() = start + 100 * dt + 1000

    @Test fun `fresh closed aligned trend yields only an observational improvement on both sides`() {
        for (buy in listOf(true, false)) {
            val t = trade(if (buy) SignalAction.BUY else SignalAction.SELL)
            val event = StopObservationPolicy.shadow(t, PriceTick(if (buy) 140.0 else 60.0, now - 100), bars(buy), now)!!
            assertEquals("CANDIDATE", event.status)
            assertNotNull(event.suggestedStop)
            assertTrue(if (buy) event.suggestedStop!! > t.stopLoss else event.suggestedStop!! < t.stopLoss)
            assertEquals(if (buy) 102.0 else 98.0, t.stopLoss, 0.0)
            assertNull(StopObservationPolicy.shadow(t.copy(stopObservations = listOf(event)), PriceTick(if (buy) 140.0 else 60.0, now - 100), bars(buy), now))
        }
    }
    @Test fun `never propose before protection and unconfirmed trend holds`() {
        val t = trade().copy(stopLoss = 90.0, netBreakEvenObservedAt = null)
        assertEquals("WAIT_PROTECTION", StopObservationPolicy.shadow(t, PriceTick(140.0, now - 100), bars(), now)!!.status)
        val against = StopObservationPolicy.shadow(trade(), PriceTick(140.0, now - 100), bars(false), now)!!
        assertEquals("TREND_UNCONFIRMED", against.status)
        assertNull(against.suggestedStop)
    }
    @Test fun `stale quote gaps insufficient candles and backtests are unknown`() {
        val t = trade()
        assertNull(StopObservationPolicy.shadow(t, PriceTick(140.0, now - 50_000), bars(), now))
        assertNull(StopObservationPolicy.shadow(t, PriceTick(140.0, now - 100), bars().filterIndexed { i, _ -> i != 80 }, now))
        assertNull(StopObservationPolicy.shadow(t, PriceTick(140.0, now - 100), bars().take(20), now))
        assertNull(StopObservationPolicy.shadow(t.copy(origin = PaperTrade.ORIGIN_BACKTEST), PriceTick(140.0, now - 100), bars(), now))
    }
    @Test fun `bounded history survives JSON and old data defaults empty`() {
        val t = trade()
        var current = t
        repeat(70) { current = current.copy(stopObservations = StopObservationPolicy.append(current, StopObservation("SHADOW", it.toLong()))) }
        assertEquals(64, current.stopObservations.size)
        assertEquals(6L, current.stopObservations.first().recordedAt)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(PaperTrade.serializer(), current)
        assertEquals(current.stopObservations, json.decodeFromString(PaperTrade.serializer(), encoded).stopObservations)
        val legacy = Json { ignoreUnknownKeys = true }.encodeToString(PaperTrade.serializer(), t)
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString(PaperTrade.serializer(), legacy).stopObservations.isEmpty())
    }
}
