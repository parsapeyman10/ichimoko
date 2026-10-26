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
import kotlinx.serialization.json.jsonArray
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
)

object AvalAiAdvisor {

    const val DEFAULT_BASE_URL = "https://api.avalai.ir/v1"
    const val API_KEYS_PORTAL_URL = "https://chat.avalai.ir/platform/api-keys"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    val POPULAR_MODELS = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gemini-2.0-flash",
        "deepseek-chat",
        "claude-3-5-haiku-latest",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        AppNetwork.builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
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
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("کلید API هوش مصنوعی (AvalAI) وارد نشده است")
            )
        }

        val symbol = V1Universe.normalize(symbolInput)
        val effectiveModel = model.trim().ifBlank { DEFAULT_MODEL }
        val endpoint = resolveChatCompletionsUrl(baseUrl)

        val candleContext = if (recentCandles.isNotEmpty()) {
            val tail = recentCandles.takeLast(12)
            val high = tail.maxOf { it.high }
            val low = tail.minOf { it.low }
            val firstOpen = tail.first().open
            val lastClose = tail.last().close
            val changePct = if (firstOpen > 0.0) (lastClose - firstOpen) / firstOpen * 100.0 else 0.0
            val barsText = tail.takeLast(6).joinToString(" | ") { b ->
                String.format(Locale.US, "O=%.2f H=%.2f L=%.2f C=%.2f", b.open, b.high, b.low, b.close)
            }
            "خلاصه ۱۲ کندل اخیر: سقف=$high، کف=$low، تغییر=${String.format(Locale.US, "%.2f%%", changePct)} · آخرین کندل‌ها: [$barsText]"
        } else {
            "کندل تاریخی ارسال نشده؛ بر اساس ساختار قیمت ورود، حد ضرر، حد سود و نسبت R:R بررسی کن."
        }

        val directionLabel = if (action == SignalAction.BUY) "خرید (BUY / Long)" else "فروش (SELL / Short)"

        val systemPrompt = """
            تو یک مدیر ریسک و تحلیلگر حرفه‌ای بازارهای مالی (رمزارز، طلا و فارکس) هستی.
            قبل از باز شدن هر معامله، باید بررسی کنی که آیا الان زمان مناسبی برای ورود به این معامله هست یا خیر.
            پاسخ تو باید فقط و فقط یک JSON معتبر با ساختار زیر باشد (بدون هیچ متن اضافی):
            {
              "approve": true,
              "confidence": 80,
              "summary_fa": "تایید ورود / رد ورود در یک جمله کوتاه فارسی",
              "reason_fa": "توضیح دقیق و کاربردی به زبان فارسی درباره اینکه چرا الان وقت مناسبی برای این معامله هست یا نیست"
            }
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("مشخصات معامله پیشنهادی:")
            appendLine("- نماد: $symbol")
            appendLine("- جهت معامله: $directionLabel")
            appendLine("- قیمت ورود (Entry): $entry")
            appendLine("- حد ضرر (SL): $stopLoss")
            appendLine("- حد سود (TP): $takeProfit")
            appendLine("- نسبت ریسک به ریوارد (R:R): 1:${String.format(Locale.US, "%.2f", riskReward)}")
            appendLine("- وضعیت کندل‌های اخیر: $candleContext")
            if (strategyContext.isNotBlank()) {
                appendLine("- جزئیات استراتژی / ایچیموکو: $strategyContext")
            }
            appendLine("آیا الان زمان مناسبی برای باز کردن این معامله است؟ فقط JSON برگردان.")
        }

        val payload = buildJsonObject {
            put("model", effectiveModel)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        }
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
            val confidence = ((parsedObj["confidence"] as? JsonPrimitive)?.intOrNull ?: 75).coerceIn(0, 100)
            val summary = (parsedObj["summary_fa"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: if (approve) "تایید زمان ورود توسط هوش مصنوعی" else "رد ورود توسط هوش مصنوعی"
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

        // Fallback if model returned plain text instead of JSON
        val lower = content.lowercase(Locale.ROOT)
        val approve = !("رد" in content || "مناسب نیست" in content || "نه" in content || "false" in lower || "reject" in lower)
        return AiTradeVerdict(
            approved = approve,
            confidence = 70,
            summaryFa = if (approve) "تایید ورود توسط هوش مصنوعی" else "رد زمان ورود توسط هوش مصنوعی",
            reasonFa = content.take(300),
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
