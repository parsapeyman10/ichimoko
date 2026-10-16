package com.aurum.edge.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEntryProofTest {
    private fun plan(worth: Boolean, answered: Boolean) = AiTradePlan(
        entry = 101.0, stopLoss = 100.0, takeProfit = 103.0,
        riskReward = 2.0, confidence = 75.0, summary = "actual response",
        model = "model-1", generatedAt = 1234L,
        worth = worth, worthReason = if (worth) "acceptable" else "too extended",
        worthAnswered = answered,
    )

    @Test fun `connection alone is never an AI verdict`() {
        assertEquals(AiEntryEffect.UNAVAILABLE, AiEntryEffect.fromResponse(false, null))
        assertEquals(AiEntryEffect.INVALID, AiEntryEffect.fromResponse(true, null))
        assertNull(AiEntryEffect.fromAction("BUY"))
    }

    @Test fun `only explicit negative verdict has veto effect`() {
        assertEquals(AiEntryEffect.VETO, AiEntryEffect.fromResponse(true, plan(false, true)))
        assertEquals(AiEntryEffect.NON_VETO, AiEntryEffect.fromResponse(true, plan(true, true)))
        assertEquals(AiEntryEffect.NO_VERDICT, AiEntryEffect.fromResponse(true, plan(true, false)))
        assertEquals(AiEntryEffect.NO_VERDICT, AiEntryEffect.fromResponse(true, plan(false, false)))
        assertEquals(AiEntryEffect.VETO, AiEntryEffect.fromAction("ai_veto"))
    }

    @Test fun `missing or string verdict cannot become a model approval or veto`() {
        assertNull(TraderAdvisor.parseWorthAnswer(Json.parseToJsonElement("""{}""").jsonObject).worth)
        assertNull(TraderAdvisor.parseWorthAnswer(Json.parseToJsonElement(
            """{"worth":"false","worth_reason":"plausible but not boolean"}""").jsonObject).worth)
        assertEquals(false, TraderAdvisor.parseWorthAnswer(Json.parseToJsonElement(
            """{"worth":false,"worth_reason":"price is stretched from the mean"}""").jsonObject).worth)
        assertThrows(IllegalStateException::class.java) {
            TraderAdvisor.parseWorthAnswer(Json.parseToJsonElement(
                """{"worth":false,"worth_reason":""}""").jsonObject)
        }
    }

    @Test fun `older decision records remain readable without AI metadata`() {
        val old = Json.decodeFromString<DecisionRecord>(
            """{"symbol":"BTC/USDT","interval":"M5","checkedAt":1000,"barTime":500,"action":"BUY","score":80.0,"reason":"technical"}"""
        )
        assertNull(old.aiModel)
        assertTrue(old.layers.isEmpty())
    }
}
