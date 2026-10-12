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
 * chosen by the explicitly configured wire format (AUTO falls back to host detection):
 *  - Anthropic Messages: POST /v1/messages with `x-api-key` and `anthropic-version`,
 *    `system` parameter and a `content[]` response.
 *  - OpenAI-compatible Chat: POST /v1/chat/completions (without doubling an existing /v1)
 *    with a Bearer token and a `choices[0].message.content` response.
 */
internal object AiProvider {
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /** Exact host match: a user-controlled lookalike must never receive relay credentials. */
    internal fun isLlmRelay(baseUrl: String): Boolean = runCatching { URI(baseUrl.trim()) }
        .getOrNull()?.let { it.scheme == "https" && it.host.equals("api.llmsrelay.com", true) } == true

    private fun providerError(code: Int): String = when (code) {
        400 -> "پارامتر درخواست یا مدل با سرویس سازگار نیست (HTTP 400)"
        401 -> "کلید API نامعتبر است یا منقضی شده (HTTP 401)"
        402 -> "اعتبار کافی نیست؛ برای GLM هم موجودی نقدی GLM و هم سهمیهٔ خود کلید را بررسی کنید (HTTP 402)"
        403 -> "کلید برای این مدل مجاز نیست یا دسترسی سرویس/منطقه محدود شده (HTTP 403)"
        404 -> "آدرس اندپوینت یا مدل یافت نشد (HTTP 404)"
        429 -> "محدودیت سهمیه/نرخ فراخوانی؛ بعداً دوباره امتحان کنید (HTTP 429)"
        else -> "خطای سرویس هوش مصنوعی (HTTP $code)"
    }

    fun isAnthropic(baseUrl: String): Boolean =
        runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?.host?.contains("anthropic", ignoreCase = true) == true

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
        if (base.endsWith("/messages")) base
        else if (base.endsWith("/v1")) "$base/messages"
        else "$base/v1/messages"

    /** OpenAI-compatible chat endpoint. */
    internal fun openAiChatUrl(base: String): String =
        if (base.endsWith("/chat/completions")) base
        else if (base.endsWith("/v1") || base.endsWith("/v1beta/openai")) "$base/chat/completions"
        else "$base/v1/chat/completions"

    /** Models catalogue endpoint both protocols expose as {data:[{id:…}]}. */
    internal fun modelsUrl(base: String): String =
        if (base.endsWith("/chat/completions")) base.removeSuffix("/chat/completions") + "/models"
        else if (base.endsWith("/models")) base
        else if (base.endsWith("/v1") || base.endsWith("/v1beta/openai")) "$base/models"
        else "$base/v1/models"

    /**
     * Model IDs this key may actually use (the catalogue is key-filtered on relay gateways,
     * so it is the source of truth - no guessing model names).
     */
    suspend fun listModels(httpClient: OkHttpClient, baseUrl: String, apiKey: String,
                           format: String = "AUTO"): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "نشانی سرویس هوش مصنوعی باید HTTPS معتبر باشد"
        }
        val anthropic = usesAnthropic(base, format)
        val response = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(modelsUrl(base))
                .header("Accept", "application/json")
                .header("HTTP-Referer", "https://aurum.edge")
                .header("X-Title", "Aurum Edge")
            // LLMsRelay documents GET /v1/models with Bearer even when subsequent
            // completions use Messages (x-api-key). Do not send both credentials.
            if (isLlmRelay(base) || !anthropic) builder.header("Authorization", "Bearer $apiKey")
            else builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            httpClient.newCall(builder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException(providerError(resp.code))
                resp.peekBody(512_000L).string()
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
        }.filter { it.isNotBlank() }.distinct().take(60)
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
     * Returns the model's short reply so settings can show proof of life.
     */
    suspend fun probe(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                      format: String = "AUTO"): String {
        val reply = completeText(httpClient, baseUrl, apiKey, model,
            "You are a connectivity test. Reply with the single word: OK",
            "ping", if (isLlmRelay(baseUrl) && model.startsWith("glm-5-3-uncensored")) 128 else 16,
            format, requireJson = false).trim()
        require(reply.isNotBlank()) { "پاسخ مدل خالی است" }
        return reply.take(60)
    }

    private suspend fun completeText(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                                     system: String, user: String, maxTokens: Int, format: String,
                                     requireJson: Boolean): String {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "نشانی سرویس هوش مصنوعی باید HTTPS معتبر باشد"
        }
        val anthropic = usesAnthropic(base, format)
        val glm = isLlmRelay(base) && model in setOf("glm-5-3-uncensored", "glm-5-3-uncensored-1m")
        val url = if (anthropic) anthropicMessagesUrl(base) else openAiChatUrl(base)
        fun requestBody(useJsonMode: Boolean) = if (anthropic) buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (!glm) put("temperature", 0)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        } else buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (!glm) put("temperature", 0)
            // The documented GLM structured mode is json_schema, not json_object.
            // App-specific schemas are validated after plain text; do not send an
            // undocumented mode or pay for a guaranteed 400+retry on every call.
            if (useJsonMode && !glm) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        suspend fun post(bodyText: String): String = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("HTTP-Referer", "https://aurum.edge")
                .header("X-Title", "Aurum Edge")
            if (anthropic) builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            else builder.header("Authorization", "Bearer $apiKey")
            val request = builder.post(bodyText.toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Never surface raw provider error bodies: some echo request fields or keys.
                    throw IllegalStateException(providerError(resp.code))
                }
                val text = resp.peekBody(64_000L).string()
                if (text.isBlank()) throw IllegalStateException("پاسخ دریافتی از هوش مصنوعی خالی بود")
                text
            }
        }
        val response = if (!anthropic && requireJson && !glm) {
            try {
                post(requestBody(useJsonMode = true).toString())
            } catch (ex: IllegalStateException) {
                if (ex.message?.contains("400") == true || ex.message?.contains("json") == true) {
                    post(requestBody(useJsonMode = false).toString())
                } else throw ex
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
     * {...} block.
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
