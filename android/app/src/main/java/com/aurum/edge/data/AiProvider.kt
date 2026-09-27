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

    /** One completion whose textual output must be a JSON object. Throws on any transport error. */
    suspend fun completeJson(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                              system: String, user: String, maxTokens: Int = 1024,
                              format: String = "AUTO"): JsonObject {
        val content = completeText(httpClient, baseUrl, apiKey, model, system, user, maxTokens, format)
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
            "ping", 16, format).take(60)

    private suspend fun completeText(httpClient: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                                     system: String, user: String, maxTokens: Int, format: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443)) { "نشانی سرویس مدل معتبر نیست (فقط HTTPS معمول)" }
        val anthropic = usesAnthropic(base, format)
        val url = if (anthropic) base.removeSuffix("/v1") + "/v1/messages" else "$base/chat/completions"
        val body = if (anthropic) buildJsonObject {
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
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        val response = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
                .header("Content-Type", "application/json")
            if (anthropic) builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            else builder.header("Authorization", "Bearer $apiKey")
            val request = builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("سرویس مدل پاسخ معتبر نداد (HTTP ${resp.code})")
                val text = resp.peekBody(64_000L).string()
                if (text.isBlank()) throw IllegalStateException("پاسخ سرویس مدل خالی بود")
                text
            }
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
