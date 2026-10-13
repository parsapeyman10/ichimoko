package com.aurum.edge.data

import java.net.URI

/** Gemini is optional AI advice, not an OHLC, quote or executable-trade provider. */
object GeminiAiStudio {
    const val KEYS_URL = "https://aistudio.google.com/apikey"
    // Google's OpenAI-compatible endpoint, not its native /v1beta/models endpoint.
    const val OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val WIRE_FORMAT = "GEMINI"

    fun isOfficialNativeEndpoint(url: String): Boolean = runCatching { URI(url.trim()) }.getOrNull()?.let { uri ->
        uri.scheme.equals("https", true) && uri.host.equals("generativelanguage.googleapis.com", true) &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.trimEnd('/') in setOf("/v1beta", "/v1beta/models", "/v1", "/v1/models")
    } == true

    fun nativeBase(url: String): String {
        require(isOfficialNativeEndpoint(url)) { "نشانی رسمی Gemini معتبر نیست" }
        return url.trim().trimEnd('/').removeSuffix("/models")
    }

    fun modelId(model: String): String = model.trim().removePrefix("models/").also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "شناسهٔ مدل Gemini معتبر نیست" }
    }

    /** Explicit opt-in only for official native REST; compatible endpoints retain their protocol. */
    fun usesNative(url: String, format: String): Boolean =
        format.trim().equals(WIRE_FORMAT, true) ||
            (format.trim().equals("AUTO", true) && isOfficialNativeEndpoint(url))

    /** Migrate ONLY the known Google compatibility preset; never redirect another provider's key. */
    fun migratePreset(url: String, format: String): Pair<String, String> =
        if (isOfficialOpenAiEndpoint(url) && format in setOf("AUTO", "OPENAI")) BASE_URL to WIRE_FORMAT
        else if (isOfficialNativeEndpoint(url) && format in setOf("AUTO", WIRE_FORMAT)) nativeBase(url) to WIRE_FORMAT
        else url to format


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
