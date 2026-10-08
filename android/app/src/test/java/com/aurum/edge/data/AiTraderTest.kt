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

    @Test fun `explicit format overrides host detection for relay services`() {
        // AUTO keeps host detection
        assertTrue(AiProvider.usesAnthropic("https://api.anthropic.com", "AUTO"))
        assertFalse(AiProvider.usesAnthropic("https://relay.example.com", "AUTO"))
        // ANTHROPIC forces the Messages API even on a relay domain (e.g. sk-cs4-… proxy keys)
        assertTrue(AiProvider.usesAnthropic("https://relay.example.com", "ANTHROPIC"))
        assertTrue(AiProvider.usesAnthropic("https://relay.example.com", "anthropic"))
        // OPENAI forces chat/completions even if the host LOOKS like Anthropic
        assertFalse(AiProvider.usesAnthropic("https://api.anthropic.com", "OPENAI"))
        // anything unknown falls back to AUTO behaviour (fail-safe, never crashes)
        assertFalse(AiProvider.usesAnthropic("https://relay.example.com", "nonsense"))
        assertTrue(AiProvider.usesAnthropic("https://api.anthropic.com", ""))
    }

    // ---------- endpoint URLs (relay gateways vs official hosts) ----------

    @Test fun `endpoint builders handle hosts with and without v1`() {
        // Anthropic format on ANY host: always /v1/messages, tolerating a base that ends in /v1
        assertEquals("https://api.anthropic.com/v1/messages", AiProvider.anthropicMessagesUrl("https://api.anthropic.com"))
        assertEquals("https://api.llmsrelay.com/v1/messages", AiProvider.anthropicMessagesUrl("https://api.llmsrelay.com"))
        assertEquals("https://api.anthropic.com/v1/messages", AiProvider.anthropicMessagesUrl("https://api.anthropic.com/v1"))
        // OpenAI format: hosts that already carry /v1 keep it; relay roots get /v1 appended
        assertEquals("https://api.openai.com/v1/chat/completions", AiProvider.openAiChatUrl("https://api.openai.com/v1"))
        assertEquals("https://api.llmsrelay.com/v1/chat/completions", AiProvider.openAiChatUrl("https://api.llmsrelay.com"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", AiProvider.openAiChatUrl("https://openrouter.ai/api/v1"))
        // Models catalogue: same rule
        assertEquals("https://api.llmsrelay.com/v1/models", AiProvider.modelsUrl("https://api.llmsrelay.com"))
        assertEquals("https://api.openai.com/v1/models", AiProvider.modelsUrl("https://api.openai.com/v1"))
    }

    @Test fun `model ids come from the catalogue in service order, deduped and bounded`() {
        val root = Json.parseToJsonElement(
            """{"object":"list","data":[{"id":"claude-sonnet-4.6"},{"id":"claude-opus-5"},
                {"id":"claude-sonnet-4.6"},{"id":"  "},{"type":"model","display_name":"x"}]}"""
        ).jsonObject
        assertEquals(listOf("claude-sonnet-4.6", "claude-opus-5"), AiProvider.parseModelIds(root))
        assertTrue(AiProvider.parseModelIds(Json.parseToJsonElement("""{"data":"nope"}""").jsonObject).isEmpty())
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
    private fun tradeReviewJson(
        verdict: String = "WORTHY",
        confidence: String = "81",
        summary: String = "همه شروط فنی و ICT برای ورود کاغذی ثبت شده‌اند.",
        reasons: String = """["۸ شرط فنی تایید شده","MTF همسو است"]""",
        cautions: String = """["خبر فقط زمینه ژورنال است"]""",
    ): JsonObject = Json.parseToJsonElement(
        """{"verdict":"$verdict","confidence":"$confidence","summary":"$summary",""" +
            """"reasons":$reasons,"cautions":$cautions}"""
    ).jsonObject

    @Test fun `paper entry ai review parses with bounded fields`() {
        val review = TraderAdvisor.parseTradeReview(tradeReviewJson(), "m", 123L)!!
        assertEquals("WORTHY", review.verdict)
        assertEquals(81, review.confidence)
        assertEquals(2, review.reasons.size)
        assertEquals("m", review.model)
        assertEquals(123L, review.checkedAt)
        val numeric = Json.parseToJsonElement(
            """{"verdict":"RISKY","confidence":64,"summary":"شرایط مرزی است اما ژورنال ثبت شد",
                "reasons":["ریسک ساختار نزدیک است"],"cautions":[]}"""
        ).jsonObject
        assertEquals(64, TraderAdvisor.parseTradeReview(numeric, "m", 2L)!!.confidence)
    }

    @Test fun `paper entry ai review invalid schema fails closed`() {
        assertNull(TraderAdvisor.parseTradeReview(tradeReviewJson(verdict = "BUY"), "m", 1L))
        assertNull(TraderAdvisor.parseTradeReview(tradeReviewJson(confidence = "101"), "m", 1L))
        assertNull(TraderAdvisor.parseTradeReview(tradeReviewJson(summary = "کوتاه"), "m", 1L))
        assertNull(TraderAdvisor.parseTradeReview(tradeReviewJson(reasons = "[]"), "m", 1L))
        assertNull(TraderAdvisor.parseTradeReview(tradeReviewJson(reasons = """["a",{"bad":true}]"""), "m", 1L))
    }

    private fun holdReviewJson(
        verdict: String = "HOLD",
        confidence: String = "78",
        summary: String = "ساختار ورود هنوز معتبر است و قیمت بالای کیجون مانده.",
        reasons: String = """["MTF همسو است","فاصله تا حد ضرر چند برابر اسپرد واقعی است"]""",
        cautions: String = """["خبر فقط زمینه ژورنال است"]""",
    ): JsonObject = Json.parseToJsonElement(
        """{"verdict":"$verdict","confidence":"$confidence","summary":"$summary",""" +
            """"reasons":$reasons,"cautions":$cautions}"""
    ).jsonObject

    @Test fun `open position hold review parses and keeps the real price context`() {
        val review = TraderAdvisor.parseHoldReview(holdReviewJson(), "m", 555L, 2654.31, 12.5)!!
        assertEquals("HOLD", review.verdict)
        assertEquals(78, review.confidence)
        assertEquals(2, review.reasons.size)
        assertEquals(1, review.cautions.size)
        assertEquals("m", review.model)
        assertEquals(555L, review.checkedAt)
        // The REAL numbers the model was shown are stored next to the verdict (auditable later).
        assertEquals(2654.31, review.lastPrice!!, 1e-9)
        assertEquals(12.5, review.unrealizedUsd!!, 1e-9)
        val numeric = Json.parseToJsonElement(
            """{"verdict":"do_not_continue","confidence":88,"summary":"تایم بالاتر برگشته و ساختار ورود شکسته است",
                "reasons":["MTF مخالف شد"],"cautions":[]}"""
        ).jsonObject
        val stop = TraderAdvisor.parseHoldReview(numeric, "m", 9L, 2600.0, -34.2)!!
        assertEquals("DO_NOT_CONTINUE", stop.verdict) // verdict is normalised to upper case
        assertEquals(88, stop.confidence)
        assertEquals(-34.2, stop.unrealizedUsd!!, 1e-9)
        assertTrue(TraderAdvisor.HOLD_VERDICTS == setOf("HOLD", "WATCH", "DO_NOT_CONTINUE"))
    }

    @Test fun `hold review invalid schema fails closed so no note is invented`() {
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(verdict = "CLOSE_IT"), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(verdict = ""), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(confidence = "101"), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(confidence = "NaN"), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(summary = "کوتاه"), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(holdReviewJson(reasons = "[]"), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(
            holdReviewJson(reasons = """["a",{"bad":true}]"""), "m", 1L, 1.0, 0.0))
        assertNull(TraderAdvisor.parseHoldReview(
            holdReviewJson(cautions = """["یک","دو","سه","چهار"]"""), "m", 1L, 1.0, 0.0))
        val missing = Json.parseToJsonElement("""{"verdict":"HOLD"}""").jsonObject
        assertNull(TraderAdvisor.parseHoldReview(missing, "m", 1L, 1.0, 0.0))
    }

    @Test fun `signal tuning plan toggles engine profile with strict schema`() {
        val root = Json.parseToJsonElement(
            """{
              "summary":"به‌خاطر دادهٔ محدود، فیلترهای احتیاطی فعال و چیکو روشن بماند.",
              "momentum_volume":true,
              "flat_span_b":false,
              "range_chop_filter":true,
              "higher_timeframe_filter":true,
              "fake_breakout_filter":true,
              "dynamic_spread_filter":false,
              "risky_timing_filter":true,
              "structure_risk_filter":true,
              "cooldown_filter":true,
              "chikou_confirmation":true,
              "changes":["فیلتر ضد رنج فعال شد","تایم بالاتر برای کاهش ورود فیک روشن شد"]
            }"""
        ).jsonObject
        val plan = TraderAdvisor.parseTuningPlan(root, "m", 7L)!!
        assertTrue(plan.profile.momentumVolume)
        assertTrue(plan.profile.rangeChopFilter)
        assertTrue(plan.profile.higherTimeframeFilter)
        assertFalse(plan.profile.dynamicSpreadFilter)
        assertTrue(plan.profile.chikouConfirmation)
        assertEquals(2, plan.changes.size)
        assertEquals("m", plan.model)
        assertEquals(7L, plan.generatedAt)
    }

    @Test fun `signal tuning plan rejects malformed or unbounded responses`() {
        val valid = """{
          "summary":"تحلیل کوتاه اما معتبر برای تنظیم محافظه‌کارانه موتور ایچیموکو.",
          "momentum_volume":false,"flat_span_b":false,"range_chop_filter":true,
          "higher_timeframe_filter":true,"fake_breakout_filter":true,"dynamic_spread_filter":false,
          "risky_timing_filter":true,"structure_risk_filter":true,"cooldown_filter":true,
          "chikou_confirmation":true,"changes":["کول‌داون فعال شد"]
        }"""
        assertNull(TraderAdvisor.parseTuningPlan(
            Json.parseToJsonElement(valid.replace("\"changes\":[\"کول‌داون فعال شد\"]", "\"changes\":[]")).jsonObject,
            "m", 1L,
        ))
        assertNull(TraderAdvisor.parseTuningPlan(
            Json.parseToJsonElement(valid.replace("true,\"fake_breakout_filter\"", "\"yes\",\"fake_breakout_filter\"")).jsonObject,
            "m", 1L,
        ))
        assertNull(TraderAdvisor.parseTuningPlan(
            Json.parseToJsonElement(valid.replace("تحلیل کوتاه اما معتبر برای تنظیم محافظه‌کارانه موتور ایچیموکو.", "کوتاه")).jsonObject,
            "m", 1L,
        ))
    }

}
