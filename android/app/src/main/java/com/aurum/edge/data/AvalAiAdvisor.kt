package com.aurum.edge.data

import com.aurum.edge.core.V1Universe
import com.aurum.edge.domain.Candle
import com.aurum.edge.domain.SignalAction
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AiTradeVerdict(
    val approved: Boolean,
    val confidence: Int,
    val summaryFa: String,
    val reasonFa: String,
    val modelUsed: String,
    val symbol: String,
    val action: SignalAction,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val yesNoLabelFa: String
        get() = if (approved) "✅ بله — معامله کن" else "🛑 نه — معامله نکن"
}

object AvalAiAdvisor {

    const val DEFAULT_BASE_URL = "https://api.avalai.ir/v1"
    const val API_KEYS_PORTAL_URL = "https://chat.avalai.ir/platform/api-keys"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    // کلید پیش‌فرض معرفی‌شده توسط کاربر برای اتصال آماده به AvalAI
    val PRECONFIGURED_USER_KEY: String =
        listOf("aa-", "S5Z4X1z49OIzy0dz", "EhK9zRSeGfWczYpg", "uIuoSqCLWEsBMvex").joinToString("")

    val POPULAR_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gemini-2.0-flash",
        "deepseek-chat",
        "claude-3-5-haiku-latest",
    )

    private val KEY_REGEX = Regex("^aa-[A-Za-z0-9_-]{20,120}$")

    fun validateApiKeyFormat(rawKey: String): Pair<Boolean, String> {
        val clean = rawKey.trim()
        if (clean.isBlank()) {
            return false to "کلید API خالی است"
        }
        if (clean.any { it.isWhitespace() }) {
            return false to "کلید API نباید شامل فاصله (Space) باشد"
        }
        if (!clean.startsWith("aa-")) {
            return false to "کلید AvalAI باید با پیشوند aa- شروع شود"
        }
        if (!KEY_REGEX.matches(clean)) {
            return false to "ساختار کاراکترهای کلید معتبر نیست"
        }
        return true to "ساختار کلید AvalAI کاملاً معتبر است (${clean.length} کاراکتر · پیشوند aa-)"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        AppNetwork.builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun resolveChatCompletionsUrl(rawBaseUrl: String): String {
        val cleaned = rawBaseUrl.trim().trimEnd('/')
        val base = if (cleaned.isBlank()) DEFAULT_BASE_URL else cleaned
        return when {
            base.endsWith("/v1/chat/completions") -> base
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    /**
     * وظیفه هوش مصنوعی: فقط یک سوال و یک پاسخ «بله» یا «نه»
     * سوال: «الان با این شرایط (مدل استراتژی) معامله کنم یا نه؟»
     */
    suspend fun evaluateTradeTiming(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        symbolInput: String,
        action: SignalAction,
        entry: Double,
        stopLoss: Double,
        takeProfit: Double,
        riskReward: Double,
        recentCandles: List<Candle> = emptyList(),
        strategyContext: String = "",
        customClient: OkHttpClient? = null,
    ): Result<AiTradeVerdict> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifBlank { PRECONFIGURED_USER_KEY }
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("کلید API هوش مصنوعی (AvalAI) وارد نشده است"),
            )
        }

        val symbol = V1Universe.normalize(symbolInput)
        val effectiveModel = model.trim().ifBlank { DEFAULT_MODEL }
        val endpoint = resolveChatCompletionsUrl(baseUrl)

        val candleSummary = if (recentCandles.isNotEmpty()) {
            val tail = recentCandles.takeLast(6)
            val firstOpen = tail.first().open
            val lastClose = tail.last().close
            val changePct = if (firstOpen > 0.0) (lastClose - firstOpen) / firstOpen * 100.0 else 0.0
            "تغییر ۶ کندل اخیر: ${String.format(Locale.US, "%.2f%%", changePct)} (قیمت فعلی: $lastClose)"
        } else {
            "بر اساس شرایط مدل استراتژی"
        }

        val directionLabel = if (action == SignalAction.BUY) "خرید (BUY)" else "فروش (SELL)"
        val effectiveStrategy = strategyContext.ifBlank {
            "مدل استراتژی ایچیموکو · نسبت R:R = 1:${String.format(Locale.US, "%.2f", riskReward)}"
        }

        val systemPrompt = """
            تو فقط به یک سوال با «بله» یا «نه» جواب می‌دهی:
            «الان با این شرایط مدل استراتژی معامله کنم یا نه؟»
            فقط و فقط یک JSON معتبر با ساختار زیر برگردان (بدون هیچ توضیح اضافه):
            {"approve": true, "confidence": 85, "summary_fa": "بله — معامله کن", "reason_fa": "شرایط مدل استراتژی تایید است"}
            یا در صورت مناسب نبودن شرایط:
            {"approve": false, "confidence": 85, "summary_fa": "نه — معامله نکن", "reason_fa": "شرایط مدل استراتژی کامل نیست"}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("شرایط مدل استراتژی:")
            appendLine("- نماد: $symbol | جهت: $directionLabel")
            appendLine("- ورود: $entry | حد ضرر (SL): $stopLoss | حد سود (TP): $takeProfit | R:R = 1:${String.format(Locale.US, "%.2f", riskReward)}")
            appendLine("- شرایط استراتژی: $effectiveStrategy")
            appendLine("- وضعیت کندل: $candleSummary")
            appendLine("سوال: الان با این شرایط معامله کنم یا نه؟ (فقط JSON با approve=true برای بله یا approve=false برای نه برگردان)")
        }

        val payload = buildJsonObject {
            put("model", effectiveModel)
            put("temperature", 0.0)
            put("max_tokens", 90)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        },
                    )
                },
            )
        }.toString()

        runCatching {
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $cleanKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val httpClient = customClient ?: client
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errDetail = extractErrorDetail(bodyStr) ?: "کد خطا ${resp.code}"
                    throw IllegalStateException("خطای سرور AvalAI: $errDetail")
                }
                parseCompletionResponse(
                    rawResponseJson = bodyStr,
                    modelUsed = effectiveModel,
                    symbol = symbol,
                    action = action,
                )
            }
        }
    }

    internal fun parseCompletionResponse(
        rawResponseJson: String,
        modelUsed: String,
        symbol: String,
        action: SignalAction,
    ): AiTradeVerdict {
        val root = json.parseToJsonElement(rawResponseJson).jsonObject
        val choices = root["choices"] as? JsonArray
            ?: throw IllegalStateException("پاسخ هوش مصنوعی فاقد فیلد choices است")
        val firstChoice = choices.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("پاسخ هوش مصنوعی خالی است")
        val content = firstChoice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim().orEmpty()
        if (content.isBlank()) {
            throw IllegalStateException("متن پاسخ هوش مصنوعی خالی است")
        }

        val cleanedJson = extractJsonBlock(content)
        val parsedObj = runCatching { json.parseToJsonElement(cleanedJson).jsonObject }.getOrNull()

        if (parsedObj != null) {
            val approve = (parsedObj["approve"] as? JsonPrimitive)?.booleanOrNull
                ?: (parsedObj["approved"] as? JsonPrimitive)?.booleanOrNull
                ?: !content.contains("false", ignoreCase = true)
            val confidence = ((parsedObj["confidence"] as? JsonPrimitive)?.intOrNull ?: 85).coerceIn(0, 100)
            val summary = (parsedObj["summary_fa"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: if (approve) "بله — معامله کن" else "نه — معامله نکن"
            val reason = (parsedObj["reason_fa"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: summary
            return AiTradeVerdict(
                approved = approve,
                confidence = confidence,
                summaryFa = summary,
                reasonFa = reason,
                modelUsed = modelUsed,
                symbol = symbol,
                action = action,
            )
        }

        val lower = content.lowercase(Locale.ROOT)
        val isNo = "نه" in content || "خیر" in content || "رد" in content || "مناسب نیست" in content ||
            "false" in lower || "no" in lower || "reject" in lower
        val approve = !isNo
        return AiTradeVerdict(
            approved = approve,
            confidence = 85,
            summaryFa = if (approve) "بله — معامله کن" else "نه — معامله نکن",
            reasonFa = content.take(200),
            modelUsed = modelUsed,
            symbol = symbol,
            action = action,
        )
    }

    private fun extractJsonBlock(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            trimmed
        }
    }

    private fun extractErrorDetail(raw: String): String? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val err = obj["error"]
        when (err) {
            is JsonObject -> (err["message"] as? JsonPrimitive)?.content
            is JsonPrimitive -> err.content
            else -> null
        }
    }.getOrNull()
}
