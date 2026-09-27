package com.aurum.edge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth tests for the on-device AI layer: provider detection, response extraction for BOTH
 * the Anthropic Messages API and OpenAI-compatible endpoints, fence-tolerant JSON parsing,
 * and the STRICT fail-closed schema of the trader opinion.
 */
class AiTraderTest {

    // ---------- provider detection ----------

    @Test fun `anthropic host is detected for exact and trailing-slash urls`() {
        assertTrue(AiProvider.isAnthropic("https://api.anthropic.com"))
        assertTrue(AiProvider.isAnthropic("https://api.anthropic.com/"))
        assertFalse(AiProvider.isAnthropic("https://api.openai.com/v1"))
        assertFalse(AiProvider.isAnthropic(""))
    }

    // ---------- response extraction ----------

    @Test fun `anthropic content array first text block is extracted`() {
        val root = Json.parseToJsonElement(
            """{"content":[{"type":"text","text":"{\"bias\":\"BUY\"}"},{"type":"text","text":"ignored"}]}"""
        ).jsonObject
        assertEquals("""{"bias":"BUY"}""", AiProvider.extractAnthropicText(root))
    }

    @Test fun `anthropic response without text block fails closed`() {
        val root = Json.parseToJsonElement("""{"content":[{"type":"tool_use","id":"x"}]}""").jsonObject
        assertNull(AiProvider.extractAnthropicText(root))
        assertNull(AiProvider.extractAnthropicText(Json.parseToJsonElement("""{"content":[]}""").jsonObject))
    }

    @Test fun `openai choices message content is extracted`() {
        val root = Json.parseToJsonElement(
            """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""
        ).jsonObject
        assertEquals("hello", AiProvider.extractOpenAiText(root))
    }

    @Test fun `openai response without choices fails closed`() {
        assertNull(AiProvider.extractOpenAiText(Json.parseToJsonElement("""{"error":"x"}""").jsonObject))
    }

    // ---------- loose JSON parsing (Claude has no json_object mode) ----------

    @Test fun `fenced and prose-wrapped json objects are recovered`() {
        val fenced = AiProvider.parseJsonObjectLoose("```json\n{\"bias\":\"SELL\"}\n```")
        assertEquals("SELL", (fenced!!["bias"] as JsonPrimitive).contentOrNull)
        val prose = AiProvider.parseJsonObjectLoose("Here is my view: {\"bias\":\"NEUTRAL\"} — good luck!")
        assertEquals("NEUTRAL", (prose!!["bias"] as JsonPrimitive).contentOrNull)
        val plain = AiProvider.parseJsonObjectLoose("""{"bias":"BUY"}""")
        assertEquals("BUY", (plain!!["bias"] as JsonPrimitive).contentOrNull)
    }

    @Test fun `non-object text fails closed instead of guessing`() {
        assertNull(AiProvider.parseJsonObjectLoose("sorry, I cannot answer that"))
        assertNull(AiProvider.parseJsonObjectLoose(""))
        assertNull(AiProvider.parseJsonObjectLoose("[1,2,3]"))
    }

    // ---------- strict opinion schema ----------

    private fun opinionJson(
        bias: String = "BUY",
        confidence: String = "72",
        summary: String = "روند صعودی با حمایت اخبار و اسپرد معقول دیده می‌شود.",
        levels: String = """["2380","2372"]""",
        risks: String = """["خبر مهم ساعت ۱۶"]""",
        invalidation: String = "بستن کندل زیر 2370",
    ): JsonObject = Json.parseToJsonElement(
        """{"bias":"$bias","confidence":"$confidence","summary":"$summary",""" +
            """"key_levels":$levels,"risks":$risks,"invalidation":"$invalidation"}"""
    ).jsonObject

    @Test fun `valid opinion parses with bounded fields`() {
        val o = TraderAdvisor.parseOpinion(opinionJson(), "XAU/USD", "claude-sonnet-4-6", 123L)!!
        assertEquals("XAU/USD", o.symbol)
        assertEquals("BUY", o.bias)
        assertEquals(72, o.confidence)
        assertEquals(2, o.keyLevels.size)
        assertEquals("claude-sonnet-4-6", o.model)
        assertEquals(123L, o.generatedAt)
        // numeric confidence (JSON number, not string) must also be accepted
        val numeric = Json.parseToJsonElement(
            """{"bias":"SELL","confidence":55,"summary":"تقویت دلار و افت طلا","key_levels":[],
                "risks":[],"invalidation":"بسته شدن بالای 2400"}"""
        ).jsonObject
        assertEquals(55, TraderAdvisor.parseOpinion(numeric, "XAU/USD", "m", 1L)!!.confidence)
    }

    @Test fun `unknown bias or out-of-range confidence fails closed`() {
        assertNull(TraderAdvisor.parseOpinion(opinionJson(bias = "HODL"), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(opinionJson(bias = ""), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(opinionJson(confidence = "140"), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(opinionJson(confidence = "-5"), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(opinionJson(confidence = "نامشخص"), "XAU/USD", "m", 1L))
    }

    @Test fun `unbounded or mistyped fields fail closed`() {
        assertNull(TraderAdvisor.parseOpinion(opinionJson(summary = "کوتاه"), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(opinionJson(invalidation = "x"), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(
            opinionJson(levels = """["a","b","c","d"]"""), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(
            opinionJson(risks = """["ک","درست","درست"]"""), "XAU/USD", "m", 1L))
        // nested objects in lists are rejected (mapNotNull drop makes sizes mismatch)
        assertNull(TraderAdvisor.parseOpinion(
            opinionJson(levels = """[{"a":1}]"""), "XAU/USD", "m", 1L))
        assertNull(TraderAdvisor.parseOpinion(
            opinionJson(risks = """["خبر",["nested"]]"""), "XAU/USD", "m", 1L))
        val missing = Json.parseToJsonElement("""{"bias":"BUY"}""").jsonObject
        assertNull(TraderAdvisor.parseOpinion(missing, "XAU/USD", "m", 1L))
    }
}
