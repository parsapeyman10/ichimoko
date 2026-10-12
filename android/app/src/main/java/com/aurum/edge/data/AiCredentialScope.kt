package com.aurum.edge.data

import com.aurum.edge.core.AppSettings

/** A blank edit-field may reuse a saved secret ONLY for the same endpoint and wire protocol.
 * Switching to a GLM profile must never forward an existing Claude/OpenAI key to LLMsRelay.
 */
internal object AiCredentialScope {
    fun format(input: String, saved: AppSettings, usingSavedEndpoint: Boolean): String =
        if (usingSavedEndpoint && input == "AUTO") saved.newsAiFormatNormalized else input

    fun key(input: String, saved: AppSettings, url: String, format: String,
            model: String = saved.newsAiModel): String {
        val typed = input.trim()
        if (typed.isNotBlank()) return typed
        val sameRelayGroup = !AiProvider.isLlmRelay(url) ||
            model.trim() == saved.newsAiModel ||
            (model.trim() in setOf("glm-5-3-uncensored", "glm-5-3-uncensored-1m") &&
                saved.newsAiModel in setOf("glm-5-3-uncensored", "glm-5-3-uncensored-1m"))
        return saved.newsAiApiKey.takeIf {
            it.isNotBlank() && sameRelayGroup &&
                url.trim().trimEnd('/') == saved.newsAiBaseUrl.trim().trimEnd('/') &&
                format.trim().uppercase(java.util.Locale.ROOT) == saved.newsAiFormatNormalized
        }.orEmpty()
    }
}
