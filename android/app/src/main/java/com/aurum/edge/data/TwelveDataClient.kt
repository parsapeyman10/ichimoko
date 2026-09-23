package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

/** Raised for provider errors; the message is already user-facing (Persian). */
class DataFeedException(message: String) : Exception(message)

/**
 * Thin, honest client for Twelve Data.
 *
 * Both REST and WebSocket paths return *provider* data. Any failure surfaces as
 * [DataFeedException] / a closed flow so the UI can show an offline state.
 */
class TwelveDataClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCandles(
        apiKey: String,
        symbol: String,
        interval: Interval,
        outputSize: Int = 1500,
    ): List<Candle> {
        if (apiKey.isBlank()) throw DataFeedException("کلید Twelve Data وارد نشده است")
        val url = buildString {
            append("https://api.twelvedata.com/time_series?symbol=")
            append(URLEncoder.encode(symbol, "UTF-8").replace("%2F", "/"))
            append("&interval=").append(interval.api)
            append("&outputsize=").append(outputSize.coerceIn(10, 5000))
            append("&order=ASC&timezone=UTC&apikey=")
            append(URLEncoder.encode(apiKey, "UTF-8"))
        }
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw DataFeedException("پاسخ خالی از سرویس‌دهنده (HTTP ${response.code})")
                text
            }
        } catch (e: DataFeedException) {
            throw e
        } catch (e: Exception) {
            throw DataFeedException("اتصال به Twelve Data برقرار نشد — اینترنت را بررسی کنید")
        }
        return parseTimeSeries(body)
    }

    internal fun parseTimeSeries(body: String): List<Candle> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw DataFeedException("پاسخ نامعتبر از سرویس‌دهنده")
        val obj = root as? JsonObject ?: throw DataFeedException("پاسخ نامعتبر از سرویس‌دهنده")
        val code = obj["code"]?.jsonPrimitive?.contentOrNull
        if (code != null && code != "200") {
            throw DataFeedException(describeError(code, obj["message"]?.jsonPrimitive?.contentOrNull))
        }
        val values = obj["values"] as? JsonArray ?: JsonArray(emptyList())
        if (values.isEmpty()) throw DataFeedException("سرویس‌دهنده کندلی برای این نماد/تایم‌فریم برنگرداند")
        val candles = values.mapNotNull { element ->
            val item = element.jsonObject
            val rawTime = item["datetime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val open = item["open"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val high = item["high"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val low = item["low"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val close = item["close"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val volume = item["volume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            Candle(
                time = parseTime(rawTime),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                closed = true,
            )
        }
        if (candles.isEmpty()) throw DataFeedException("کندل قابل‌استفاده‌ای در پاسخ نبود")
        return candles.sortedBy { it.time }
    }

    /** Real-time price stream. The flow closes on any connection problem. */
    fun streamPrice(apiKey: String, symbol: String): Flow<PriceTick> = callbackFlow {
        val request = Request.Builder()
            .url("wss://ws.twelvedata.com/v1/quotes/price?apikey=" + URLEncoder.encode(apiKey, "UTF-8"))
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"action":"subscribe","params":{"symbols":"$symbol"}}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
                val events = when (root) {
                    is JsonArray -> root.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(root)
                    else -> emptyList()
                }
                events.forEach { event ->
                    when (event["event"]?.jsonPrimitive?.contentOrNull) {
                        "price" -> {
                            val price = event["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                            if (price != null && price > 0) {
                                val at = event["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                    ?.times(1000L) ?: System.currentTimeMillis()
                                trySend(PriceTick(price, at))
                            }
                        }
                        "heartbeat" -> webSocket.send("""{"action":"heartbeat"}""")
                        "error", "disconnect" -> {
                            val message = event["message"]?.jsonPrimitive?.contentOrNull
                            close(DataFeedException(message ?: "اتصال WebSocket قطع شد"))
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(DataFeedException("قطع اتصال زنده — ${t.message ?: "خطای شبکه"}"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(DataFeedException("اتصال زنده توسط سرویس‌دهنده بسته شد ($code)"))
            }
        }
        val socket = client.newWebSocket(request, listener)
        awaitClose { runCatching { socket.close(1000, "client closed") } }
    }

    private fun describeError(code: String?, message: String?): String = when (code) {
        "401", "403" -> "کلید Twelve Data نامعتبر یا غیرفعال است"
        "429" -> "سهمیه درخواست Twelve Data تمام شد (محدودیت پلن رایگان)"
        "404" -> "نماد مورد نظر در Twelve Data پیدا نشد"
        else -> message?.takeIf { it.isNotBlank() } ?: "خطای سرویس‌دهنده Twelve Data ($code)"
    }

    companion object {
        private val FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH",
        )

        internal fun parseTime(raw: String): Long {
            val cleaned = raw.trim().replace('T', ' ').removeSuffix("Z")
            FORMATS.forEach { pattern ->
                if (cleaned.length >= pattern.length) {
                    val parsed = runCatching {
                        LocalDateTime.parse(cleaned.substring(0, pattern.length), DateTimeFormatter.ofPattern(pattern))
                    }.getOrNull()
                    if (parsed != null) return parsed.toInstant(ZoneOffset.UTC).toEpochMilli()
                }
            }
            val dateOnly = runCatching {
                LocalDateTime.parse(cleaned.substring(0, 10) + " 00:00:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            }.getOrNull()
            return dateOnly?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L
        }
    }
}
