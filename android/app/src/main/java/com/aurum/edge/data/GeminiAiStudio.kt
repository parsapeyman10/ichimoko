package com.aurum.edge.data

import java.net.URI

/** Gemini is optional AI advice, not an OHLC, quote or executable-trade provider. */
object GeminiAiStudio {
    const val KEYS_URL = "https://aistudio.google.com/api-keys"
    // Google's OpenAI-compatible endpoint, not its native /v1beta/models endpoint.
    const val OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val WIRE_FORMAT = "OPENAI"

    /** Never rewrite user-supplied model IDs for lookalike hosts or other wire protocols. */
    fun isOfficialOpenAiEndpoint(url: String): Boolean = runCatching { URI(url.trim()) }.getOrNull()?.let { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("generativelanguage.googleapis.com", ignoreCase = true) &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.trimEnd('/') in setOf("/v1beta/openai", "/v1beta/openai/chat/completions")
    } == true

    /** Native Gemini model names start with `models/`; OpenAI compatibility requires the bare ID. */
    fun openAiModelId(baseUrl: String, model: String): String {
        val id = model.trim()
        return if (isOfficialOpenAiEndpoint(baseUrl)) id.removePrefix("models/") else id
    }
}
