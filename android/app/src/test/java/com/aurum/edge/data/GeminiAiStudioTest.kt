package com.aurum.edge.data

import android.content.Context
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
