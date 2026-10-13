package com.aurum.edge.data

/** Gemini is optional AI advice, not an OHLC, quote or executable-trade provider. */
object GeminiAiStudio {
    const val KEYS_URL = "https://aistudio.google.com/api-keys"
    // Google's OpenAI-compatible endpoint, not its native /v1beta/models endpoint.
    const val OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val WIRE_FORMAT = "OPENAI"
}
