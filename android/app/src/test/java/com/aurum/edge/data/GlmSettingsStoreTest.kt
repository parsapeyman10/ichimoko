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
class GlmSettingsStoreTest {
    @Test fun `saving another Relay model group with blank key leaves saved provider unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aurum_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SettingsStore(context)
        assertTrue(store.saveNewsAiConfig("old-claude-key", "https://api.llmsrelay.com",
            "claude-sonnet-4.6", "ANTHROPIC"))
        assertFalse(store.saveNewsAiConfig("", "https://api.llmsrelay.com",
            "glm-5-3-uncensored", "ANTHROPIC"))
        assertEquals("old-claude-key", store.read().newsAiApiKey)
        assertEquals("claude-sonnet-4.6", store.read().newsAiModel)
        assertTrue(store.saveNewsAiConfig("new-glm-key", "https://api.llmsrelay.com",
            "glm-5-3-uncensored", "ANTHROPIC"))
        assertTrue(store.saveNewsAiConfig("", "https://api.llmsrelay.com/",
            "glm-5-3-uncensored-1m", "ANTHROPIC"))
        assertEquals("new-glm-key", store.read().newsAiApiKey)
        assertFalse(store.saveNewsAiConfig("", "https://api.llmsrelay.com/v1",
            "glm-5-3-uncensored", "OPENAI"))
        assertEquals("glm-5-3-uncensored-1m", store.read().newsAiModel)
        assertTrue(store.clearNewsAiConfig())
        assertFalse(store.read().hasClientNewsAi)
    }
}
