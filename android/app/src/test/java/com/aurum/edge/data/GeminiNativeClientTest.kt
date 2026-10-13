package com.aurum.edge.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GeminiNativeClientTest {
    private val base = GeminiAiStudio.BASE_URL
    private val fakeKey = "synthetic-gemini-key"
    private fun root(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun reply(text: String, finish: String = "STOP"): String = buildJsonObject {
        put("candidates", buildJsonArray { add(buildJsonObject {
            put("finishReason", finish)
            put("content", buildJsonObject { put("parts", buildJsonArray {
                add(buildJsonObject { put("text", text) })
            }) })
        }) })
    }.toString()
    private fun client(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, json) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun `native generation sends header authentication and documented contents only`() = runBlocking {
        var sent: Request? = null
        val http = client { sent = it; 200 to reply("{\"decision\":\"NO_TRADE\"}") }
        val result = AiProvider.completeJson(http, base, fakeKey, "models/gemini-test",
            "System instruction", "Market evidence", maxTokens = 400, format = "GEMINI")
        assertEquals("NO_TRADE", result["decision"]!!.jsonPrimitive.content)
        val request = sent!!
        assertEquals("$base/models/gemini-test:generateContent", request.url.toString())
        assertEquals(fakeKey, request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("x-api-key"))
        assertNull(request.url.query)
        val body = root(Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
        assertEquals("System instruction", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("Market evidence", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(config["maxOutputTokens"]!!.jsonPrimitive.int > 16)
        assertFalse(body.toString().contains(fakeKey))
        listOf("messages", "model", "tools", "cachedContent", "labels", "response_format").forEach { assertFalse(body.containsKey(it)) }
    }

    @Test fun `AUTO on official native endpoint probes real text not thinking content`() = runBlocking {
        val response = """{"candidates":[{"finishReason":"STOP","content":{"parts":[
            {"thought":true,"text":"private thought"},{"text":"O"},{"text":"K"}]}}]}"""
        val http = client {
            val body = root(Buffer().apply { it.body!!.writeTo(this) }.readUtf8())
            assertFalse(body["generationConfig"]!!.jsonObject.containsKey("responseMimeType"))
            200 to response
        }
        assertEquals("OK", AiProvider.probe(http, "$base/models/", fakeKey, "gemini-test", "AUTO"))
    }

    @Test fun `catalog follows nextPageToken and filters generation support without hardcoded model IDs`() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = client {
            requests += it
            200 to if (requests.size == 1) """{"models":[
                {"name":"models/embedding-test","supportedGenerationMethods":["embedContent"]},
                {"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]}],
                "nextPageToken":"page+two/="}"""
            else """{"models":[
                {"name":"models/gemini-test","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-future","supportedGenerationMethods":["generateContent"]},
                {"name":"models/../../bad","supportedGenerationMethods":["generateContent"]}]}"""
        }
        assertEquals(listOf("gemini-test", "gemini-future"), AiProvider.listModels(http, base, fakeKey, "GEMINI"))
        assertEquals(2, requests.size)
        assertNull(requests[0].url.queryParameter("pageToken"))
        assertEquals("page+two/=", requests[1].url.queryParameter("pageToken"))
        requests.forEach {
            assertEquals("GET", it.method)
            assertEquals("/v1beta/models", it.url.encodedPath)
            assertEquals("100", it.url.queryParameter("pageSize"))
            assertEquals(fakeKey, it.header("x-goog-api-key"))
            assertNull(it.header("Authorization"))
            assertFalse(it.url.toString().contains(fakeKey))
        }
    }

    @Test fun `unsafe endpoint or model never receives the key`() = runBlocking {
        var calls = 0
        val http = client { calls++; 200 to reply("OK") }
        listOf("http://generativelanguage.googleapis.com/v1beta",
            "https://generativelanguage.googleapis.com.evil.example/v1beta", "$base?key=bad",
            "$base#bad", "https://user@generativelanguage.googleapis.com/v1beta").forEach { bad ->
            assertTrue(runCatching { AiProvider.probe(http, bad, fakeKey, "gemini-test", "GEMINI") }.isFailure)
        }
        listOf("../bad", "models/models/test", "test?key=bad", "", "test:generateContent").forEach { bad ->
            assertTrue(runCatching { AiProvider.probe(http, base, fakeKey, bad, "GEMINI") }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test fun `truncated blocked empty and tool-only responses cannot become successful advice`() {
        val responses = listOf(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            reply("{\"safe\":true}", "MAX_TOKENS"), reply("OK", "SAFETY"), reply("OK", "CONTINUATION"),
            """{"candidates":[]}""", """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"secret thought"}]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"trade"}}]}}]}"""
        )
        responses.forEach { json ->
            val error = runCatching { GeminiNativeClient.extractText(root(json)) }.exceptionOrNull()
            assertTrue(error is GeminiNativeClient.GeminiResponseException)
            assertFalse(AiConnectionDiagnostics.describe(error!!).contains("secret thought"))
        }
    }

    @Test fun `errors are sanitized and neither HTTP failures nor invalid JSON trigger paid retries`() = runBlocking {
        for (code in listOf(400, 401, 403, 429, 500, 503)) {
            var calls = 0
            val http = client { calls++; code to "echo $fakeKey" }
            val error = runCatching { AiProvider.completeJson(http, base, fakeKey, "gemini-test", "s", "u", format = "GEMINI") }.exceptionOrNull()!!
            assertEquals(1, calls)
            assertTrue(error.message!!.contains("HTTP $code"))
            assertFalse(AiConnectionDiagnostics.describe(error).contains(fakeKey))
        }
        val http = client { 200 to reply("prefix {\"ok\":true}") }
        assertTrue(runCatching { AiProvider.completeJson(http, base, fakeKey, "gemini-test", "s", "u", format = "GEMINI") }.isFailure)
    }

    @Test fun `repeated pagination token fails instead of looping or returning partial list`() = runBlocking {
        var calls = 0
        val http = client { calls++; 200 to """{"models":[],"nextPageToken":"same"}""" }
        assertTrue(runCatching { AiProvider.listModels(http, base, fakeKey, "GEMINI") }.isFailure)
        assertEquals(2, calls)
    }
}
