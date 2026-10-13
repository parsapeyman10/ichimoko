package com.aurum.edge.data

import android.content.Context
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
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeminiAiStudioTest {
    @Test fun `Gemini preset uses Google's OpenAI compatible routes`() {
        assertEquals("https://aistudio.google.com/api-keys", GeminiAiStudio.KEYS_URL)
        assertEquals("OPENAI", GeminiAiStudio.WIRE_FORMAT)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiProvider.openAiChatUrl(GeminiAiStudio.OPENAI_BASE_URL))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models",
            AiProvider.modelsUrl(GeminiAiStudio.OPENAI_BASE_URL))
    }

    @Test fun `native model prefix is removed only for the official compatible endpoint`() {
        assertEquals("gemini-2.5-pro", GeminiAiStudio.openAiModelId(GeminiAiStudio.OPENAI_BASE_URL,
            "models/gemini-2.5-pro"))
        assertEquals("gemini-3.8-flash", GeminiAiStudio.openAiModelId(GeminiAiStudio.OPENAI_BASE_URL,
            "gemini-3.8-flash"))
        listOf("https://generativelanguage.googleapis.com.evil.example/v1beta/openai",
            "http://generativelanguage.googleapis.com/v1beta/openai",
            "https://generativelanguage.googleapis.com:8443/v1beta/openai",
            "https://api.openai.com/v1").forEach { url ->
            assertEquals("models/gemini-2.5-pro", GeminiAiStudio.openAiModelId(url,
                "models/gemini-2.5-pro"))
        }
    }

    @Test fun `probe and model list send bare IDs to Gemini without leaking a key`() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val body = if (chain.request().method == "GET")
                """{"data":[{"id":"models/gemini-3.8-flash"},{"id":"gemini-3.8-flash"}]}"""
                else """{"choices":[{"message":{"content":"OK"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals("OK", AiProvider.probe(http, GeminiAiStudio.OPENAI_BASE_URL,
            "fake-user-key", "models/gemini-2.5-pro", "OPENAI"))
        val post = requests.single()
        assertEquals("Bearer fake-user-key", post.header("Authorization"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            post.url.toString())
        val buffer = Buffer()
        post.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("gemini-2.5-pro", body["model"]!!.jsonPrimitive.content)
        assertEquals(listOf("gemini-3.8-flash"), AiProvider.listModels(http,
            GeminiAiStudio.OPENAI_BASE_URL, "fake-user-key", "OPENAI"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models",
            requests.last().url.toString())
    }

    @Test fun `saved Gemini model IDs are canonical while older saved IDs remain executable`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.saveNewsAiConfig("fake-key", GeminiAiStudio.OPENAI_BASE_URL,
            "models/gemini-2.5-pro", "OPENAI"))
        assertEquals("gemini-2.5-pro", store.read().newsAiModel)
        // Earlier app versions may have persisted the native-prefixed name already.
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit()
            .putString("news_ai_client_model", "models/gemini-2.5-pro").commit()
        assertEquals("gemini-2.5-pro", GeminiAiStudio.openAiModelId(store.read().newsAiBaseUrl,
            store.read().newsAiModel))
    }

    @Test fun `switching to Gemini cannot silently reuse an earlier provider key`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.saveNewsAiConfig("old-provider-secret", "https://api.openai.com/v1",
            "previous-model", "OPENAI"))
        val saved = store.read()
        assertEquals("", AiCredentialScope.key("", saved, GeminiAiStudio.OPENAI_BASE_URL,
            GeminiAiStudio.WIRE_FORMAT, "gemini-model"))
        assertFalse(store.saveNewsAiConfig("", GeminiAiStudio.OPENAI_BASE_URL,
            "gemini-model", GeminiAiStudio.WIRE_FORMAT))
        assertEquals("old-provider-secret", store.read().newsAiApiKey)
        assertTrue(store.saveNewsAiConfig("separate-gemini-secret", GeminiAiStudio.OPENAI_BASE_URL,
            "gemini-model", GeminiAiStudio.WIRE_FORMAT))
        assertEquals("separate-gemini-secret", store.read().newsAiApiKey)
    }
}
