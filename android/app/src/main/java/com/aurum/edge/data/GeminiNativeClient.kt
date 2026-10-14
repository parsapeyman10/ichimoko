package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Stateless native Gemini REST. Uses the caller's shared proxy/TLS transport, but never
 * follows redirects with x-goog-api-key. No key in URLs, diagnostics, prompts or bundled APK.
 * Contract: https://ai.google.dev/api and https://ai.google.dev/api/models
 */
internal object GeminiNativeClient {
    private const val MAX_RESPONSE_BYTES = 1_048_576L
    private const val MAX_MODEL_PAGES = 20

    private fun transport(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun request(url: String, apiKey: String): Request.Builder {
        require(apiKey.isNotBlank() && apiKey.none { it.isWhitespace() || it.code < 32 }) {
            "کلید Gemini معتبر نیست؛ آن را فقط در تنظیمات گوشی وارد کنید"
        }
        return Request.Builder().url(url).header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
    }

    private suspend fun execute(client: OkHttpClient, request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.peekBody(65_536L).string()
                throw GeminiHttpDiagnostics.failure(response.code, errorBody)
            }
            val body = response.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
            check(body.size <= MAX_RESPONSE_BYTES) { "پاسخ JSON مدل بیش از اندازه بزرگ است" }
            runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException("پاسخ Gemini ساختار JSON معتبر ندارد")
        }
    }

    suspend fun listModels(client: OkHttpClient, baseUrl: String, apiKey: String): List<String> {
        val base = GeminiAiStudio.nativeBase(baseUrl)
        val http = transport(client)
        val models = linkedSetOf<String>()
        val tokens = mutableSetOf<String>()
        var pageToken: String? = null
        repeat(MAX_MODEL_PAGES) {
            val url = "$base/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()
            val root = execute(http, request(url.toString(), apiKey).get().build())
            val rows = root["models"] as? JsonArray
                ?: throw IllegalStateException("فهرست مدل‌های Gemini ساختار JSON معتبر ندارد")
            rows.forEach { row ->
                val model = row as? JsonObject ?: return@forEach
                val methods = model["supportedGenerationMethods"] as? JsonArray ?: return@forEach
                if (methods.none { (it as? JsonPrimitive)?.contentOrNull == "generateContent" }) return@forEach
                val name = (model["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                if (!name.startsWith("models/")) return@forEach
                runCatching { GeminiAiStudio.modelId(name) }.getOrNull()?.let(models::add)
            }
            pageToken = (root["nextPageToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (pageToken == null) return models.toList().ifEmpty {
                throw IllegalStateException("مدلی با قابلیت generateContent در پاسخ Gemini موجود نیست")
            }
            check(tokens.add(pageToken!!)) { "صفحه‌بندی فهرست مدل‌های Gemini تکراری است" }
        }
        throw IllegalStateException("فهرست مدل‌های Gemini کامل دریافت نشد؛ دوباره تلاش کنید")
    }

    internal fun body(system: String, user: String, maxTokens: Int, requireJson: Boolean): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
        })
        put("contents", buildJsonArray { add(buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
        }) })
        put("generationConfig", buildJsonObject {
            // A 16-token legacy probe can exhaust a thinking model before any visible reply.
            // Keep a bounded allowance; do not guess model-specific thinking parameters.
            put("maxOutputTokens", maxTokens.coerceIn(4096, 8192))
            put("candidateCount", 1)
            if (requireJson) put("responseMimeType", "application/json")
            // No tools, session IDs, file uploads or unsupported per-model sampling overrides.
        })
    }

    suspend fun generate(client: OkHttpClient, baseUrl: String, apiKey: String, model: String,
                         system: String, user: String, maxTokens: Int, requireJson: Boolean): String {
        val base = GeminiAiStudio.nativeBase(baseUrl)
        val id = GeminiAiStudio.modelId(model)
        val payload = body(system, user, maxTokens, requireJson)
        val request = request("$base/models/$id:generateContent", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        // No paid retry that silently drops JSON mode after an HTTP 400.
        return extractText(execute(transport(client), request))
    }

    internal fun extractText(root: JsonObject): String {
        val feedback = root["promptFeedback"] as? JsonObject
        val blocked = (feedback?.get("blockReason") as? JsonPrimitive)?.contentOrNull
        if (blocked != null && blocked != "BLOCK_REASON_UNSPECIFIED") throw GeminiResponseException(Block.BLOCKED)
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw GeminiResponseException(Block.EMPTY)
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
        if (finish == "MAX_TOKENS") throw GeminiResponseException(Block.TRUNCATED)
        if (finish != "STOP") throw GeminiResponseException(Block.INCOMPLETE)
        val ratings = candidate["safetyRatings"] as? JsonArray
        if (ratings?.any { ((it as? JsonObject)?.get("blocked") as? JsonPrimitive)?.booleanOrNull == true } == true)
            throw GeminiResponseException(Block.BLOCKED)
        val content = candidate["content"] as? JsonObject
        val parts = content?.get("parts") as? JsonArray ?: throw GeminiResponseException(Block.EMPTY)
        val text = parts.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if ((obj["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.joinToString("")
        if (text.isBlank()) throw GeminiResponseException(Block.EMPTY)
        return text
    }

    enum class Block(val message: String) {
        BLOCKED("Gemini پاسخ را مسدود کرد؛ تأیید AI صادر نشد"),
        TRUNCATED("پاسخ Gemini به سقف توکن رسید و ناقص است؛ مدل دیگری انتخاب کنید"),
        INCOMPLETE("Gemini پاسخ کامل با وضعیت STOP نداد؛ تأیید AI صادر نشد"),
        EMPTY("Gemini متن پاسخ قابل استفاده نداد؛ فهرست مدل‌ها و قابلیت مدل را بررسی کنید"),
    }
    class GeminiResponseException(val reason: Block) : IllegalStateException(reason.message)
}
