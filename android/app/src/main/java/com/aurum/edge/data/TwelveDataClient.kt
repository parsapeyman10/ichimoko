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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.LocalDateTime
import java.time.OffsetDateTime
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
        .followRedirects(false) // the URL contains a read-only key; do not forward it to another host
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
                if (!response.isSuccessful) throw DataFeedException(describeError(response.code.toString()))
                val bytes = response.peekBody(2_000_001L).bytes()
                if (bytes.size > 2_000_000) throw DataFeedException("پاسخ کندل بیش از حد بزرگ است")
                val text = bytes.toString(Charsets.UTF_8)
                if (text.isBlank()) throw DataFeedException("پاسخ خالی از سرویس‌دهنده (HTTP ${response.code})")
                text
            }
        } catch (e: DataFeedException) {
            throw e
        } catch (e: Exception) {
            // Never include OkHttp's URL: it contains the read-only API key.
            throw DataFeedException("اتصال به Twelve Data برقرار نشد — اینترنت را بررسی کنید")
        }
        return parseTimeSeries(body, symbol, interval)
    }

    /** Reject mismatched assets, malformed OHLC and timeless/future bars before the chart/engine sees them. */
    internal fun parseTimeSeries(body: String, expectedSymbol: String, interval: Interval,
                                 now: Long = System.currentTimeMillis()): List<Candle> {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ نامعتبر از سرویس‌دهنده")
        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        val code = obj.text("code")
        if (code != null && code != "200") throw DataFeedException(describeError(code))
        val meta = obj["meta"] as? JsonObject ?: throw DataFeedException("هویت نماد پاسخ مشخص نیست")
        if (meta.text("symbol")?.equals(expectedSymbol.trim(), ignoreCase = true) != true ||
            meta.text("interval") != interval.api) {
            throw DataFeedException("نماد/بازهٔ کندل با درخواست یکسان نیست")
        }
        val quoteCurrency = expectedSymbol.substringAfter('/', "")
        if (quoteCurrency.isNotEmpty() && meta.text("currency")?.let {
                !it.equals(quoteCurrency, ignoreCase = true)
            } == true) throw DataFeedException("واحد قیمت کندل با نماد درخواست‌شده یکسان نیست")
        if (meta.text("timezone")?.let { it !in setOf("UTC", "Etc/UTC") } == true)
            throw DataFeedException("منطقهٔ زمانی کندل UTC نیست")
        val values = obj["values"] as? JsonArray
            ?: throw DataFeedException("فهرست کندل سرویس‌دهنده نامعتبر است")
        if (values.isEmpty() || values.size > 5000) throw DataFeedException("تعداد کندل سرویس‌دهنده معتبر نیست")
        val candles = values.map { element ->
            val item = element as? JsonObject ?: throw DataFeedException("ساختار کندل نامعتبر است")
            val time = item.text("datetime")?.let(::parseTime) ?: 0L
            if (time <= 0L || time > now + 60_000L || time % interval.millis != 0L)
                throw DataFeedException("زمان کندل نامعتبر/آینده یا با بازه ناسازگار است")
            fun price(key: String): Double = item.text(key)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: throw DataFeedException("قیمت $key در کندل معتبر نیست")
            val open = price("open")
            val high = price("high")
            val low = price("low")
            val close = price("close")
            if (low > minOf(open, close) || high < maxOf(open, close) || low > high)
                throw DataFeedException("ساختار OHLC کندل نامعتبر است")
            val rawVolume = item.text("volume")
            val volume = rawVolume?.toDoubleOrNull() ?: if (rawVolume == null) 0.0 else
                throw DataFeedException("حجم کندل معتبر نیست")
            if (!volume.isFinite() || volume < 0.0) throw DataFeedException("حجم کندل معتبر نیست")
            Candle(time, open, high, low, close, volume, closed = true)
        }.sortedBy { it.time }
        if (candles.distinctBy { it.time }.size != candles.size)
            throw DataFeedException("کندل‌های تکراری در پاسخ وجود دارند")
        return candles
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
                        "price" -> parsePriceEvent(event, symbol, System.currentTimeMillis())?.let { tick ->
                            trySend(tick)
                        }
                        "heartbeat" -> webSocket.send("""{"action":"heartbeat"}""")
                        "error", "disconnect" -> {
                            // Provider text might echo the request URL (with the API key).
                            close(DataFeedException("اتصال زنده توسط سرویس‌دهنده رد یا قطع شد"))
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(DataFeedException("قطع اتصال زنده — اینترنت/دسترسی فید را بررسی کنید"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(DataFeedException("اتصال زنده توسط سرویس‌دهنده بسته شد ($code)"))
            }
        }
        val socket = client.newWebSocket(request, listener)
        awaitClose { runCatching { socket.close(1000, "client closed") } }
    }

    /** Never relabel another symbol's, timeless, or delayed event as a fresh paper quote. */
    internal fun parsePriceEvent(event: JsonObject, expectedSymbol: String, receivedAt: Long): PriceTick? {
        if (event["event"]?.jsonPrimitive?.contentOrNull != "price" ||
            event["symbol"]?.jsonPrimitive?.contentOrNull?.equals(expectedSymbol, ignoreCase = true) != true) return null
        val seconds = event["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000L } ?: return null
        val at = seconds * 1000L
        if ((receivedAt - at) !in -30_000L..90_000L) return null
        val price = event["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return PriceTick(price, at)
    }

    // Provider-supplied messages may echo the request URL, which contains the API key.
    private fun describeError(code: String?): String = when (code) {
        "401", "403" -> "کلید Twelve Data نامعتبر یا غیرفعال است"
        "429" -> "سهمیه درخواست Twelve Data تمام شد (محدودیت پلن رایگان)"
        "404" -> "نماد مورد نظر در Twelve Data پیدا نشد"
        else -> "خطای سرویس‌دهنده Twelve Data (${code?.takeIf { it.matches(Regex("[0-9]{3}")) } ?: "نامشخص"})"
    }

    companion object {
        private val INTRADAY_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}:\\d{2})?")
        /** For intraday prices, a date without a clock is NOT a provider update time. */
        internal fun parseTime(raw: String): Long {
            val iso = raw.trim().replace(' ', 'T')
            if (!INTRADAY_TIME.matches(iso)) return 0L
            val offset = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            return offset ?: runCatching { LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
                .getOrDefault(0L)
        }
    }
}
