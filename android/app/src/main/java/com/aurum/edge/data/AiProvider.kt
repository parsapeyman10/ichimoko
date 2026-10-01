package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

/**
 * Direct, on-device AI calls with the user's OWN key. Two request formats are supported and
 * chosen by the configured base URL:
 *  - Anthropic (Claude): host `api.anthropic.com` -> POST /v1/messages with `x-api-key` and
 *    `anthropic-version` headers, `system` parameter and a `content[]` response.
 *  - Anything else (OpenAI-compatible proxies): POST {base}/chat/completions with a Bearer
 *    token and a `choices[0].message.content` response.
 *
 * Both paths return ONLY strictly-JSON objects; callers validate the schema themselves and
 * fail closed. No key, URL or model output is ever logged.
 */
internal object AiProvider {
    private const val ANTHROPIC_HOST = "api.anthropic.com"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    fun isAnthropic(baseUrl: String): Boolean =
        runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?.host?.equals(ANTHROPIC_HOST, ignoreCase = true) == true

    /**
     * Which wire format to speak: an EXPLICIT "ANTHROPIC"/"OPENAI" wins (relay services host
     * either protocol on arbitrary domains), anything else falls back to host detection.
     */
    internal fun usesAnthropic(baseUrl: String, format: String): Boolean = when (format.trim().uppercase()) {
        "ANTHROPIC" -> true
        "OPENAI" -> false
        else -> isAnthropic(baseUrl)
    }

    /** Anthropic Messages endpoint on ANY host; tolerates a base that already ends in /v1. */
    internal fun anthropicMessagesUrl(base: String): String =
        base.removeSuffix("/v1") + "/v1/messages"

    /**
     * OpenAI-compatible chat endpoint. OpenAI-style bases usually already end in /v1
     * (https://api.openai.com/v1); Anthropic-relay gateways such as LLMsRelay do NOT
     * (https://api.llmsrelay.com hosts /v1/chat/completions), so the missing /v1 is added.
     */
    internal fun openAiChatUrl(base: String): String =
        if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"

    /** Models catalogue endpoint both protocols expose as {data:[{id:…}]}. */
    internal fun modelsUrl(base: String): String =
        if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"

    /**
     * Model IDs this key may actually use (the catalogue is key-filtered on relay gateways,
     * so it is the source of truth - no guessing model names). One GET, never logs the key.
     */
    suspend fun listModels(httpClient: OkHttpClient, baseUrl: String, apiKey: String,
                           format: String = "AUTO"): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443)) { "نشانی سرویس مدل معتبر نیست (فقط HTTPS معمول)" }
        val anthropic = usesAnthropic(base, format)
        val response = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(modelsUrl(base)).header("Accept", "application/json")
            if (anthropic) builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            else builder.header("Authorization", "Bearer $apiKey")
            httpClient.newCall(builder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("سرویس مدل پاسخ معتبر نداد (HTTP ${resp.code})")
                resp.peekBody(256_000L).string()
            }
        }
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("پاسخ فهرست مدل‌ها ساختار JSON ندارد")
        return parseModelIds(root).ifEmpty { throw IllegalStateException("فهرست مدل‌های این کلید خالی بود") }
    }

    /** Extract distinct, non-blank data[].id values in the service's own order. */
    internal fun parseModelIds(root: JsonObject): List<String> {
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
        }.filter { it.isNotBlank() }.distinct().take(40)
    }

    /** One completion whose textual output must be a JSON object. Throws on any transport error. */
    suspend fun completeJson(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                              system: String, user: String, maxTokens: Int = 1024,
                              format: String = "AUTO"): JsonObject {
        val content = completeText(httpClient, baseUrl, apiKey, model, system, user, maxTokens, format,
            requireJson = true)
        return parseJsonObjectLoose(content) ?: throw IllegalStateException("خروجی مدل JSON معتبر نیست")
    }

    /**
     * Minimal connectivity probe with the user's OWN key: one tiny prompt, no JSON contract.
     * Returns the model's short reply so settings can show proof of life. Never logs the key.
     */
    suspend fun probe(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                      format: String = "AUTO"): String =
        completeText(httpClient, baseUrl, apiKey, model,
            "You are a connectivity test. Reply with the single word: OK",
            "ping", 16, format, requireJson = false).take(60)

    private suspend fun completeText(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                                     system: String, user: String, maxTokens: Int, format: String,
                                     requireJson: Boolean): String {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443)) { "نشانی سرویس مدل معتبر نیست (فقط HTTPS معمول)" }
        val anthropic = usesAnthropic(base, format)
        val url = if (anthropic) anthropicMessagesUrl(base) else openAiChatUrl(base)
        fun requestBody(useJsonMode: Boolean) = if (anthropic) buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        } else buildJsonObject {
            put("model", model)
            put("temperature", 0)
            if (useJsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        suspend fun post(bodyText: String): String = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
                .header("Content-Type", "application/json")
            if (anthropic) builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            else builder.header("Authorization", "Bearer $apiKey")
            val request = builder.post(bodyText.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("سرویس مدل پاسخ معتبر نداد (HTTP ${resp.code})")
                val text = resp.peekBody(64_000L).string()
                if (text.isBlank()) throw IllegalStateException("پاسخ سرویس مدل خالی بود")
                text
            }
        }
        val response = if (!anthropic && requireJson) {
            try {
                post(requestBody(useJsonMode = true).toString())
            } catch (_: IllegalStateException) {
                // Some OpenAI-compatible gateways reject response_format=json_object, and the
                // connectivity probe must not use JSON mode at all. The caller still validates
                // strict JSON after this fallback, so unsupported JSON-mode does not become trust.
                post(requestBody(useJsonMode = false).toString())
            }
        } else {
            post(requestBody(useJsonMode = false).toString())
        }
        val root = runCatching { Json.parseToJsonElement(response) as? JsonObject }
            .getOrNull() ?: throw IllegalStateException("پاسخ سرویس مدل ساختار JSON ندارد")
        return (if (anthropic) extractAnthropicText(root) else extractOpenAiText(root))
            ?: throw IllegalStateException("متن پاسخ مدل نامعتبر است")
    }

    /** Anthropic Messages API: first text block of the `content` array. */
    internal fun extractAnthropicText(root: JsonObject): String? {
        val blocks = root["content"] as? JsonArray ?: return null
        for (block in blocks) {
            val obj = block as? JsonObject ?: continue
            if (obj["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "text") {
                return obj["text"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }
        return null
    }

    /** OpenAI-compatible: choices[0].message.content. */
    internal fun extractOpenAiText(root: JsonObject): String? {
        val choices = root["choices"] as? JsonArray ?: return null
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return null
        return message["content"]?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    /**
     * Models occasionally wrap JSON in markdown fences or short prose; extract the outermost
     * {...} block. Returns null when the text is not parseable as a JSON object at all —
     * callers must treat that as a failed (fail-closed) analysis.
     */
    internal fun parseJsonObjectLoose(text: String): JsonObject? {
        val trimmed = text.trim()
        val fenced = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").find(trimmed)?.groupValues?.get(1)
        val candidate = (fenced ?: trimmed).trim()
        runCatching { Json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()?.let { return it }
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(candidate.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }
}
