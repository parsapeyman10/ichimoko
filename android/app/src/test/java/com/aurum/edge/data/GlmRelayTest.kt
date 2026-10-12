package com.aurum.edge.data

import com.aurum.edge.core.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No external calls or real credentials: captures the actual OkHttp requests. */
class GlmRelayTest {
    private fun client(requests: MutableList<Request>, code: Int = 200, body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("mock")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun `Messages preset posts exact URL and Anthropic headers and required token count`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests, body = """{"content":[{"type":"text","text":"OK"}]}""")
        assertEquals("OK", AiProvider.probe(client, "https://api.llmsrelay.com", "test-glm-key",
            "glm-5-3-uncensored", "ANTHROPIC"))
        val req = requests.single()
        assertEquals("https://api.llmsrelay.com/v1/messages", req.url.toString())
        assertEquals("POST", req.method)
        assertEquals("test-glm-key", req.header("x-api-key"))
        assertEquals("2023-06-01", req.header("anthropic-version"))
        assertEquals(null, req.header("Authorization"))
        val buffer = okio.Buffer()
        req.body!!.writeTo(buffer)
        val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("glm-5-3-uncensored", json["model"]!!.jsonPrimitive.content)
        assertEquals("128", json["max_tokens"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("temperature"))
        assertFalse(json.containsKey("response_format"))
        assertEquals("https://api.llmsrelay.com/v1/messages",
            AiProvider.anthropicMessagesUrl("https://api.llmsrelay.com/v1"))
    }

    @Test fun `Chat preset posts exact URL and Bearer with JSON schema left to app`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests, body = """{"choices":[{"message":{"content":"{\"answer\":\"OK\"}"}}]}""")
        val result = AiProvider.completeJson(client, "https://api.llmsrelay.com/v1", "test-glm-key",
            "glm-5-3-uncensored-1m", "system", "user", 512, "OPENAI")
        assertEquals("OK", result["answer"]!!.jsonPrimitive.content)
        val req = requests.single()
        assertEquals("https://api.llmsrelay.com/v1/chat/completions", req.url.toString())
        assertEquals("Bearer test-glm-key", req.header("Authorization"))
        assertEquals(null, req.header("x-api-key"))
        val buffer = okio.Buffer()
        req.body!!.writeTo(buffer)
        val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("glm-5-3-uncensored-1m", json["model"]!!.jsonPrimitive.content)
        assertEquals("512", json["max_tokens"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("response_format"))
        assertFalse(json.containsKey("temperature"))
        assertEquals(req.url.toString(), AiProvider.openAiChatUrl("https://api.llmsrelay.com/v1"))
    }

    @Test fun `models uses Bearer even when Messages selected and never doubles v1`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests, body = """{"data":[{"id":"glm-5-3-uncensored"},{"id":"glm-5-3-uncensored-1m"}]}""")
        val ids = AiProvider.listModels(client, "https://api.llmsrelay.com/v1", "test-glm-key", "ANTHROPIC")
        assertEquals(listOf("glm-5-3-uncensored", "glm-5-3-uncensored-1m"), ids)
        val req = requests.single()
        assertEquals("GET", req.method)
        assertEquals("https://api.llmsrelay.com/v1/models", req.url.toString())
        assertEquals("Bearer test-glm-key", req.header("Authorization"))
        assertEquals(null, req.header("x-api-key"))
        assertFalse(AiProvider.isLlmRelay("https://api.llmsrelay.com.evil.example"))
        assertFalse(AiProvider.isLlmRelay("http://api.llmsrelay.com"))
    }

    @Test fun `402 models error does not echo provider body or credential`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests, 402, """{"error":"test-glm-key"}""")
        val failure = runCatching {
            AiProvider.listModels(client, "https://api.llmsrelay.com", "test-glm-key", "OPENAI")
        }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("HTTP 402"))
        assertFalse(failure.message.orEmpty().contains("test-glm-key"))
        assertEquals("https://api.llmsrelay.com/v1/models", requests.single().url.toString())
    }

    @Test fun `blank edit field cannot send an existing provider or Relay group key to GLM`() {
        val saved = AppSettings(newsAiApiKey = "old-provider-key", newsAiBaseUrl = "https://api.anthropic.com",
            newsAiModel = "claude-sonnet-4.6", newsAiFormat = "ANTHROPIC")
        assertEquals("", AiCredentialScope.key("", saved, "https://api.llmsrelay.com", "ANTHROPIC", "glm-5-3-uncensored"))
        assertEquals("old-provider-key", AiCredentialScope.key("", saved,
            "https://api.anthropic.com", "ANTHROPIC", "claude-sonnet-4.6"))
        val relayClaude = saved.copy(newsAiBaseUrl = "https://api.llmsrelay.com")
        assertEquals("", AiCredentialScope.key("", relayClaude,
            "https://api.llmsrelay.com", "ANTHROPIC", "glm-5-3-uncensored"))
        assertEquals("", AiCredentialScope.key("", relayClaude,
            "https://api.llmsrelay.com/v1", "OPENAI", "glm-5-3-uncensored"))
        assertEquals("test-new-key", AiCredentialScope.key("test-new-key", relayClaude,
            "https://api.llmsrelay.com", "ANTHROPIC", "glm-5-3-uncensored"))
        val relayGlm = relayClaude.copy(newsAiApiKey = "glm-key", newsAiModel = "glm-5-3-uncensored")
        assertEquals("glm-key", AiCredentialScope.key("", relayGlm,
            "https://api.llmsrelay.com", "ANTHROPIC", "glm-5-3-uncensored-1m"))
        assertEquals("", AiCredentialScope.key("", relayGlm,
            "https://api.llmsrelay.com", "ANTHROPIC", "claude-sonnet-4.6"))
        assertEquals("ANTHROPIC", AiCredentialScope.format("AUTO", relayGlm, true))
    }
}
