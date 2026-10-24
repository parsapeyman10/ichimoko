package com.aurum.edge.data

import com.aurum.edge.domain.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.domain.Interval
import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Binance SPOT market-data-only venue. Candles, entry and exit must all use this venue.
 * aggTrade is one aggregate per taker order/price, not every constituent fill. No API key.
 */
class BinanceSpotClient(
    private val http: OkHttpClient = AppNetwork.builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).build(),
    private val host: String = "https://data-api.binance.vision",
) {
    companion object {
        const val PROVIDER = "Binance Spot / USDT"
        const val VENUE = "BINANCE_SPOT"
        fun ticker(symbol: String): String = CryptoCatalog.find(symbol)?.binance
            ?: throw DataFeedException("نماد Binance Spot پشتیبانی نمی‌شود: $symbol")

        fun parseAggregate(body: String, symbol: String, now: Long = System.currentTimeMillis()): Aggregate {
            val obj = Json.parseToJsonElement(body) as? JsonObject
                ?: throw DataFeedException("پیام aggTrade معتبر نیست")
            return parseAggregate(obj, symbol, now, stream = true)
        }

        internal fun parseAggregate(obj: JsonObject, symbol: String, now: Long, stream: Boolean): Aggregate {
            if (stream && (obj["e"] as? JsonPrimitive)?.contentOrNull != "aggTrade")
                throw DataFeedException("رویداد Binance از نوع معامله نیست")
            val expected = ticker(symbol)
            if (stream && (obj["s"] as? JsonPrimitive)?.contentOrNull != expected)
                throw DataFeedException("شناسهٔ جفت معامله با پوزیشن یکسان نیست")
            fun field(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            val id = field("a")?.toLongOrNull() ?: throw DataFeedException("شناسهٔ معامله نامعتبر است")
            val price = field("p")?.toDoubleOrNull() ?: throw DataFeedException("قیمت معامله نامعتبر است")
            val quantity = field("q")?.toDoubleOrNull() ?: throw DataFeedException("حجم معامله نامعتبر است")
            val at = field("T")?.toLongOrNull() ?: throw DataFeedException("زمان معامله نامعتبر است")
            if (id < 0 || !quantity.isFinite() || quantity <= 0.0 || !price.isFinite() || price <= 0.0 ||
                at <= 0 || at > now + 10_000L)
                throw DataFeedException("معاملهٔ Binance دارای قیمت/زمان/حجم نامعتبر است")
            return Aggregate(id, PriceTick(price, at))
        }

        fun parsePage(body: String, symbol: String, now: Long = System.currentTimeMillis()): List<Aggregate> {
            val rows = Json.parseToJsonElement(body) as? JsonArray
                ?: throw DataFeedException("صفحهٔ معاملات Binance معتبر نیست")
            if (rows.size > 1000) throw DataFeedException("صفحهٔ معاملات بیش از حد مجاز است")
            val trades = rows.map { parseAggregate(it as? JsonObject
                ?: throw DataFeedException("ردیف معامله نامعتبر است"), symbol, now, stream = false) }
            if (trades.zipWithNext().any { (a, b) -> b.id != a.id + 1L || b.tick.at < a.tick.at })
                throw DataFeedException("شناسه/زمان معاملات Binance پیوسته نیست")
            return trades
        }

        fun parseCandles(body: String, interval: Interval, now: Long = System.currentTimeMillis()): List<Candle> {
            val rows = Json.parseToJsonElement(body) as? JsonArray
                ?: throw DataFeedException("کندل‌های Binance معتبر نیست")
            return rows.map { row ->
                val a = row as? JsonArray ?: throw DataFeedException("ردیف کندل نامعتبر است")
                fun num(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                    ?: throw DataFeedException("ستون کندل نامعتبر است")
                val t = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    ?: throw DataFeedException("زمان کندل نامعتبر است")
                val o = num(1); val h = num(2); val l = num(3); val c = num(4); val v = num(5)
                if (t < 0 || listOf(o,h,l,c,v).any { !it.isFinite() } || minOf(o,h,l,c) <= 0 ||
                    v < 0 || h < maxOf(o,c) || l > minOf(o,c) || h < l)
                    throw DataFeedException("OHLC واقعی معتبر نیست")
                Candle(time = t, open = o, high = h, low = l, close = c, volume = v,
                    closed = t + interval.millis <= now)
            }.filter { it.closed }.also { candles ->
                if (candles.zipWithNext().any { (a,b) -> b.time != a.time + interval.millis })
                    throw DataFeedException("تاریخچهٔ Binance کندل گمشده دارد")
            }
        }

        fun resolution(interval: Interval): String = when (interval) {
            Interval.M1 -> "1m"; Interval.M3 -> "3m"; Interval.M5 -> "5m"
            Interval.M15 -> "15m"; Interval.M30 -> "30m"; Interval.H1 -> "1h"
            Interval.H4 -> "4h"; Interval.D1 -> "1d"
        }
    }

    data class Aggregate(val id: Long, val tick: PriceTick)

    private suspend fun get(path: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val base = host.toHttpUrl()
        require(base.isHttps && base.encodedPath == "/" && base.query == null &&
            (base.host == "data-api.binance.vision" || base.host == "localhost"))
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("Binance Spot HTTP ${response.code}؛ فید هم‌صرافی در دسترس نیست")
            val bytes = response.peekBody(2_000_001L).bytes()
            if (bytes.size > 2_000_000) throw DataFeedException("پاسخ Binance بیش از حد بزرگ است")
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun fetchAggregates(symbol: String, fromId: Long? = null, startTime: Long? = null,
                                limit: Int = 1000): List<Aggregate> {
        require(limit in 1..1000 && !(fromId != null && startTime != null))
        val params = mutableMapOf("symbol" to ticker(symbol), "limit" to limit.toString())
        if (fromId != null) params["fromId"] = fromId.toString()
        if (startTime != null) params["startTime"] = startTime.toString()
        val rows = parsePage(get("api/v3/aggTrades", params), symbol)
        if (fromId != null && rows.isNotEmpty() && rows.first().id != fromId)
            throw DataFeedException("بازیابی Binance از شناسهٔ مورد انتظار شروع نشد")
        if (startTime != null && rows.any { it.tick.at < startTime })
            throw DataFeedException("بازیابی Binance پیش از زمان ورود است")
        return rows
    }

    suspend fun fetchQuote(symbol: String): PriceTick = fetchAggregates(symbol, limit = 1)
        .lastOrNull()?.tick?.takeIf { TradeQuotePolicy.accepts(it) }
        ?: throw DataFeedException("معاملهٔ تازهٔ Binance Spot برای $symbol در دسترس نیست")

    suspend fun fetchCandles(symbol: String, interval: Interval,
                             desiredSize: Int = HistoryPolicy.LIVE_FETCH_CANDLES,
                             minimumSize: Int = HistoryPolicy.LIVE_MIN_CANDLES): PublicCandleHistoryClient.Result {
        val count = desiredSize.coerceAtLeast(minimumSize).coerceAtMost(HistoryPolicy.MAX_CACHED_CANDLES)
        val firstLimit = (count + 1).coerceAtMost(1000)
        val firstPage = parseCandles(get("api/v3/klines", mapOf(
            "symbol" to ticker(symbol),
            "interval" to resolution(interval),
            "limit" to firstLimit.toString(),
        )), interval)
        val rows = if (firstPage.size >= minimumSize || firstPage.isEmpty() || count <= 1000) {
            firstPage
        } else {
            val needMore = (count - firstPage.size + 5).coerceIn(1, 1000)
            val olderPage = parseCandles(get("api/v3/klines", mapOf(
                "symbol" to ticker(symbol),
                "interval" to resolution(interval),
                "endTime" to (firstPage.first().time - 1L).toString(),
                "limit" to needMore.toString(),
            )), interval)
            (olderPage + firstPage).distinctBy { it.time }.sortedBy { it.time }.also { combined ->
                if (combined.zipWithNext().any { (a, b) -> b.time != a.time + interval.millis })
                    throw DataFeedException("تاریخچهٔ Binance کندل گمشده دارد")
            }
        }
        if (rows.size < minimumSize) throw DataFeedException("Binance فقط ${rows.size} کندل بستهٔ واقعی داد؛ حداقل $minimumSize لازم است")
        return PublicCandleHistoryClient.Result(rows.takeLast(count), PROVIDER)
    }
}
