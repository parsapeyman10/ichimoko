package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Exactly what apiv2.nobitex.ir reported for one order — never fabricated, never guessed.
 * Every field is copied verbatim (as strings, like Nobitex's own "monetary" type) from the
 * exchange's JSON response, plus [raw] so the UI can show the untouched payload on request.
 */
data class NobitexOrderResult(
    val id: Long?,
    val clientOrderId: String?,
    val status: String,
    val type: String,
    val amount: String?,
    val price: String?,
    val matchedAmount: String?,
    val totalPrice: String?,
    val raw: String,
)

/** Carries Nobitex's own error code/message through untouched; never rewritten or softened. */
class NobitexTradingException(message: String, val code: String? = null) : Exception(message)

/**
 * REAL trading against the user's own Nobitex account, using the personal API token the user
 * types into the live-trading section themselves (nobitex.ir/panel/profile/api-services/).
 * This class places, checks and cancels REAL spot orders with REAL money. It never invents a
 * balance, price, precision or fill — every value returned to the caller is exactly what
 * apiv2.nobitex.ir sent back, and any rejection/error is surfaced with Nobitex's own text.
 */
class NobitexTradingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val prefix = "https://apiv2.nobitex.ir"

    /** POST /users/wallets/balance — the user's real, current balance for one currency. */
    suspend fun walletBalance(token: String, currency: String): Double = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("currency", currency.lowercase()) }
        val root = post("$prefix/users/wallets/balance", token, body)
        requireOk(root)
        root["balance"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw NobitexTradingException("پاسخ موجودی نوبیتکس ساختار معتبر ندارد")
    }

    /**
     * POST /market/orders/add — places a REAL spot order. [price] is required for limit orders;
     * for market orders it is the exchange-recommended price-bound guard, still strongly advised
     * even though optional, to avoid an unbounded fill during a sudden move.
     */
    suspend fun placeOrder(
        token: String,
        type: String,
        srcCurrency: String,
        dstCurrency: String,
        amount: String,
        price: String?,
        execution: String,
        clientOrderId: String,
    ): NobitexOrderResult = withContext(Dispatchers.IO) {
        require(type == "buy" || type == "sell") { "نوع سفارش باید buy یا sell باشد" }
        require(execution == "market" || execution == "limit") { "این نسخه فقط سفارش market یا limit می‌فرستد" }
        require(amount.isNotBlank()) { "حجم سفارش لازم است" }
        require(clientOrderId.isNotBlank() && clientOrderId.length <= 32) { "شناسه سفارش نامعتبر است" }
        val body = buildJsonObject {
            put("type", type)
            put("srcCurrency", srcCurrency.lowercase())
            put("dstCurrency", dstCurrency.lowercase())
            put("amount", amount)
            put("execution", execution)
            if (!price.isNullOrBlank()) put("price", price)
            put("clientOrderId", clientOrderId)
        }
        parseOrder(post("$prefix/market/orders/add", token, body))
    }

    /** POST /market/orders/status — real, current status of one previously placed order. */
    suspend fun orderStatus(token: String, orderId: Long): NobitexOrderResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("id", orderId) }
        parseOrder(post("$prefix/market/orders/status", token, body))
    }

    /** POST /market/orders/update-status — cancels a real open order. */
    suspend fun cancelOrder(token: String, orderId: Long): NobitexOrderResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("order", orderId); put("status", "canceled") }
        parseOrder(post("$prefix/market/orders/update-status", token, body))
    }

    private fun requireOk(root: JsonObject) {
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != "ok") {
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            val message = root["message"]?.jsonPrimitive?.contentOrNull
                ?: "نوبیتکس درخواست را رد کرد (status=$status)"
            throw NobitexTradingException(message, code)
        }
    }

    private fun parseOrder(root: JsonObject): NobitexOrderResult {
        requireOk(root)
        val order = root["order"]?.jsonObject
            ?: throw NobitexTradingException("پاسخ سفارش نوبیتکس ساختار معتبر ندارد")
        return NobitexOrderResult(
            id = order["id"]?.jsonPrimitive?.longOrNull,
            clientOrderId = order["clientOrderId"]?.jsonPrimitive?.contentOrNull,
            status = order["status"]?.jsonPrimitive?.contentOrNull ?: "نامشخص",
            type = order["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            amount = order["amount"]?.jsonPrimitive?.contentOrNull,
            price = order["price"]?.jsonPrimitive?.contentOrNull,
            matchedAmount = order["matchedAmount"]?.jsonPrimitive?.contentOrNull,
            totalPrice = order["totalPrice"]?.jsonPrimitive?.contentOrNull,
            raw = root.toString(),
        )
    }

    private fun post(url: String, token: String, body: JsonObject): JsonObject {
        require(token.isNotBlank()) { "کلید API نوبیتکس وارد نشده است" }
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody(mediaType))
            .header("Authorization", "Token $token")
            .header("User-Agent", "TraderBot/AurumEdge-1.0.0")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.peekBody(512_001L).string()
            require(text.isNotBlank() && text.length <= 512_000) { "پاسخ نوبیتکس خالی یا بیش‌ازحد بزرگ است" }
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw NobitexTradingException("ساختار JSON نوبیتکس معتبر نیست (HTTP ${response.code})")
            val status = parsed["status"]?.jsonPrimitive?.contentOrNull
            if (!response.isSuccessful && status != "failed" && status != "ok") {
                throw NobitexTradingException("پاسخ نوبیتکس نامعتبر است (HTTP ${response.code})")
            }
            return parsed
        }
    }
}
