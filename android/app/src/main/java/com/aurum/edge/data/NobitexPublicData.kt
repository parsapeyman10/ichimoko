package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Fixed allowlist, public GET endpoints only; no exchange credentials or order endpoints. */
enum class NobitexMarket(val code: String, val srcCurrency: String, val statsKey: String,
                         val destination: String, val quoteUnit: String, val supportsPractice: Boolean) {
    BTC_USDT("BTCUSDT", "btc", "btc-usdt", "usdt", "USDT", true),
    // Empirical live check on 2026-09-23: BTCIRT UDF close ~19.5bn vs stats ~195bn.
    // OHLC docs do not define the unit. Display raw values only; NEVER mix with RLS fills.
    BTC_IRT("BTCIRT", "btc", "btc-rls", "rls", "ریال (فقط آمار)", false),
    // Same USDT catalog already vetted for the read-only scanner (NobitexSpotCatalog.bases);
    // OHLC/stats/order-book unit agreement re-checked per market by [NobitexSnapshot.practiceBlocker].
    ETH_USDT("ETHUSDT", "eth", "eth-usdt", "usdt", "USDT", true),
    SOL_USDT("SOLUSDT", "sol", "sol-usdt", "usdt", "USDT", true),
    XRP_USDT("XRPUSDT", "xrp", "xrp-usdt", "usdt", "USDT", true),
    DOGE_USDT("DOGEUSDT", "doge", "doge-usdt", "usdt", "USDT", true),
    ADA_USDT("ADAUSDT", "ada", "ada-usdt", "usdt", "USDT", true),
}

data class NobitexQuote(
    val market: NobitexMarket,
    val latest: Double,
    val bestBuy: Double,
    val bestSell: Double,
    val dayChangePct: Double?,
    val isClosed: Boolean,
    /** Local receipt time, NOT an exchange-origin timestamp. */
    val receivedAt: Long,
)

data class NobitexBookTop(
    val market: NobitexMarket,
    val bestBid: Double,
    val bestAsk: Double,
    /** Exchange-origin timestamp of the public order book, unlike stats' local receipt time. */
    val updatedAt: Long,
    val receivedAt: Long,
)

data class NobitexSnapshot(
    val market: NobitexMarket,
    val interval: Interval,
    val candles: List<Candle>,
    val quote: NobitexQuote,
    val downloadedAt: Long,
    val book: NobitexBookTop? = null,
    val bookError: String? = null,
) {
    val lastClosed: Candle? get() = candles.lastOrNull { it.closed }

    /** Never grant this to BTCIRT until the documented OHLC/quote unit discrepancy is resolved. */
    fun practiceBlocker(now: Long = System.currentTimeMillis()): String? {
        if (!market.supportsPractice) return "واحد قیمت BTCIRT در تاریخچه و آمار با هم تطبیق ندارد؛ معاملهٔ کاغذی مسدود است"
        if (quote.market != market || quote.isClosed || !quote.latest.isFinite() ||
            quote.receivedAt > now || now - quote.receivedAt > 60_000L)
            return "بازار بسته است یا قیمت آمار نوبیتکس قدیمی شده است"
        val orderBook = book ?: return "دفتر سفارش با زمان معتبر دریافت نشد؛ تمرین بدون شاهد تازه متوقف است"
        if (orderBook.market != market || now - orderBook.updatedAt !in 0L..60_000L ||
            now - orderBook.receivedAt !in 0L..60_000L)
            return "زمان مستقل دفتر سفارش با ساعت گوشی یا تازگی داده سازگار نیست"
        val bar = lastClosed ?: return "کندل بستهٔ معتبر دریافت نشد"
        if (candles.count { it.closed } < 210 || now - (bar.time + interval.millis) !in 0L..(interval.millis * 2))
            return "برای تمرین، دست‌کم ۲۱۰ کندل و آخرین کندل بستهٔ تازه لازم است"
        if (!orderBook.bestBid.isFinite() || !orderBook.bestAsk.isFinite() ||
            orderBook.bestBid <= 0 || orderBook.bestAsk <= 0 ||
            orderBook.bestBid > orderBook.bestAsk ||
            (orderBook.bestAsk - orderBook.bestBid) / orderBook.bestAsk > 0.02 ||
            quote.bestBuy <= 0 || quote.bestSell <= 0 ||
            abs(quote.bestBuy / orderBook.bestBid - 1.0) > 0.01 ||
            abs(quote.bestSell / orderBook.bestAsk - 1.0) > 0.01 ||
            abs(quote.latest / orderBook.bestAsk - 1.0) > 0.05 ||
            abs(bar.close / quote.latest - 1.0) > 0.05)
            return "آمار، دفتر سفارش، اسپرد یا کندل‌ها با هم سازگار نیستند"
        return null
    }
}

/** Strict UDF/schema checks; errors and closed/current bars never turn into synthetic history. */
internal fun parseNobitexHistory(root: JsonObject, interval: Interval, now: Long): List<Candle> {
    require(root.primitive("s") == "ok") { "نوبیتکس کندل معتبر برنگرداند (${root.primitive("s") ?: "نامشخص"})" }
    val keys = listOf("t", "o", "h", "l", "c", "v")
    val arrays = keys.associateWith { (root[it] as? JsonArray) ?: error("آرایهٔ $it در پاسخ کندل نیست") }
    val count = arrays.getValue("t").size
    require(count in 1..500 && arrays.values.all { it.size == count }) { "تعداد آرایه‌های کندل برابر یا معتبر نیست" }
    var previous = 0L
    return (0 until count).map { i ->
        val seconds = (arrays.getValue("t")[i] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: error("زمان کندل معتبر نیست")
        require(seconds > previous && seconds in 1_550_000_000L..((now / 1000L) + 60L)) {
            "زمان کندل نامرتب یا آینده‌دار است"
        }
        previous = seconds
        fun value(key: String): Double {
            val num = (arrays.getValue(key)[i] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: error("عدد کندل $key معتبر نیست")
            require(num.isFinite() && num >= 0) { "عدد کندل $key معتبر نیست" }
            return num
        }
        val open = value("o")
        val high = value("h")
        val low = value("l")
        val close = value("c")
        require(open > 0 && close > 0 && low > 0 &&
            high >= maxOf(open, close) && low <= minOf(open, close)) { "ساختار OHLC معتبر نیست" }
        Candle(time = seconds * 1000L, open = open, high = high, low = low, close = close,
            volume = value("v"), closed = seconds * 1000L + interval.millis <= now)
    }
}

internal fun parseNobitexQuote(root: JsonObject, market: NobitexMarket, receivedAt: Long): NobitexQuote {
    require(root.primitive("status") == "ok") { "پاسخ آمار بازار نوبیتکس معتبر نیست" }
    val stats = (root["stats"] as? JsonObject)?.get(market.statsKey) as? JsonObject
        ?: error("آمار ${market.code} در پاسخ پیدا نشد")
    val closed = stats.primitive("isClosed")
    require(closed in setOf("true", "false")) { "وضعیت باز بودن بازار مشخص نیست" }
    fun price(key: String): Double {
        val number = stats.primitive(key)?.toDoubleOrNull()
        require(number != null && number.isFinite() && number > 0) { "قیمت $key معتبر نیست" }
        return number
    }
    val bid = price("bestBuy")
    val ask = price("bestSell")
    require(bid <= ask) { "بهترین قیمت خرید بالاتر از فروش گزارش شده است" }
    val change = stats.primitive("dayChange")?.toDoubleOrNull()?.takeIf { it.isFinite() }
    return NobitexQuote(market, price("latest"), bid, ask, change, closed == "true", receivedAt)
}

/** V3 order book is public GET and supplies the exchange's lastUpdate (milliseconds). */
internal fun parseNobitexBook(root: JsonObject, market: NobitexMarket, receivedAt: Long): NobitexBookTop {
    require(root.primitive("status") == "ok") { "وضعیت دفتر سفارش نوبیتکس نامعتبر است" }
    val timestamp = root.primitive("lastUpdate")?.toLongOrNull()
    require(timestamp != null && timestamp in 1_550_000_000_000L..(receivedAt + 5_000L)) {
        "زمان دفتر سفارش ناموجود یا آینده‌دار است"
    }
    fun top(key: String): Double {
        val levels = root[key] as? JsonArray ?: error("سطوح $key موجود نیست")
        val level = levels.firstOrNull() as? JsonArray ?: error("سطح $key خالی است")
        require(level.size == 2) { "ساختار سطح $key نامعتبر است" }
        val price = (level[0] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val amount = (level[1] as? JsonPrimitive)?.content?.toDoubleOrNull()
        require(price != null && price.isFinite() && price > 0 &&
            amount != null && amount.isFinite() && amount > 0) { "قیمت/حجم دفتر سفارش نامعتبر است" }
        return price
    }
    val bid = top("bids")
    val ask = top("asks")
    require(bid <= ask) { "بهترین خرید دفتر سفارش بالاتر از فروش است" }
    return NobitexBookTop(market, bid, ask, timestamp, receivedAt)
}

private fun JsonObject.primitive(key: String) = (this[key] as? JsonPrimitive)?.content

class NobitexPublicData(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS).followRedirects(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun download(market: NobitexMarket, interval: Interval): NobitexSnapshot = mutex.withLock {
        val now = clock()
        require(interval in setOf(Interval.M5, Interval.M15, Interval.H1, Interval.H4)) {
            "بازهٔ نوبیتکس پشتیبانی نمی‌شود"
        }
        require(now - lastRequestAt >= 20_000L) { "برای رعایت سهمیه، حداقل ۲۰ ثانیه بین دریافت‌ها صبر کنید" }
        lastRequestAt = now
        val resolution = when (interval) {
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            else -> error("بازهٔ نوبیتکس پشتیبانی نمی‌شود")
        }
        val prefix = "https://apiv2.nobitex.ir"
        val history = get("$prefix/market/udf/history?symbol=${market.code}&resolution=$resolution&to=${now / 1000}&countback=320")
        val candles = parseNobitexHistory(history, interval, clock())
        val stats = get("$prefix/market/stats?srcCurrency=${market.srcCurrency}&dstCurrency=${market.destination}")
        val statsReceivedAt = clock()
        // Book failure must not erase readable OHLC/stats, but it MUST block spot practice.
        val bookResult = runCatching {
            val root = get("$prefix/v3/orderbook/${market.code}")
            parseNobitexBook(root, market, clock())
        }
        val downloadedAt = clock()
        NobitexSnapshot(market, interval, candles, parseNobitexQuote(stats, market, statsReceivedAt),
            downloadedAt, bookResult.getOrNull(), bookResult.exceptionOrNull()?.message?.take(140))
    }

    private suspend fun get(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get()
            .header("User-Agent", "TraderBot/AurumEdge-1.0.0")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) error("سهمیهٔ API عمومی نوبیتکس تمام شده است (۴۲۹)")
            require(response.isSuccessful) { "پاسخ نوبیتکس نامعتبر است (HTTP ${response.code})" }
            val body = response.peekBody(512_001L).string()
            require(body.isNotBlank() && body.length <= 512_000) { "پاسخ نوبیتکس خالی یا بزرگ است" }
            json.parseToJsonElement(body) as? JsonObject ?: error("ساختار JSON نوبیتکس معتبر نیست")
        }
    }

    companion object {
        fun csv(snapshot: NobitexSnapshot): String = buildString {
            append("source,symbol,interval,time_utc,open,high,low,close,volume,closed,history_price_unit\n")
            snapshot.candles.forEach { bar ->
                append("Nobitex,${snapshot.market.code},${snapshot.interval.label},${Instant.ofEpochMilli(bar.time)},")
                append("${bar.open},${bar.high},${bar.low},${bar.close},${bar.volume},${bar.closed},")
                append(if (!snapshot.market.supportsPractice) "unverified_${snapshot.market.code}_history_unit" else snapshot.market.quoteUnit)
                append('\n')
            }
        }
    }
}
