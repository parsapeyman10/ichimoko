package com.aurum.edge.data

import com.aurum.edge.core.Candle
import com.aurum.edge.core.HistoryPolicy
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Keyless SPOT OHLC for read-only radar. Never used for entry, fills or old positions.
 * Exact USDT pair is checked against the venue's live SPOT listing, not a guessed ticker.
 * A failure/429 falls through to another venue; bar histories are NEVER stitched together.
 */
class CryptoRadarCandleClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    enum class Venue(val provider: String, val host: String) {
        GATE("Gate Spot / USDT (radar only)", "https://api.gateio.ws"),
        OKX("OKX Spot / USDT (radar only)", "https://www.okx.com"),
        KRAKEN("Kraken Spot / USDT (radar only)", "https://api.kraken.com"),
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val listing = ConcurrentHashMap<String, Long>()
    private val krakenPairs = ConcurrentHashMap<String, String>()
    private val cooldown = ConcurrentHashMap<Venue, Long>()

    private suspend fun get(venue: Venue, path: String): String = withContext(Dispatchers.IO) {
        val until = cooldown[venue] ?: 0L
        if (System.currentTimeMillis() < until) throw DataFeedException("${venue.provider}: سهمیه/دسترسی موقتاً محدود است")
        val request = Request.Builder().url(venue.host + path)
            .header("Accept", "application/json").header("User-Agent", "AurumEdge-radar/1")
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code == 418 || response.code >= 500)
                    cooldown[venue] = System.currentTimeMillis() + 60_000L
                if (!response.isSuccessful) throw DataFeedException("${venue.provider} HTTP ${response.code}")
                val body = response.peekBody(2_000_001L).bytes()
                if (body.size > 2_000_000) throw DataFeedException("پاسخ ${venue.provider} بیش از حد بزرگ است")
                body.toString(Charsets.UTF_8)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: DataFeedException) { throw error }
        catch (_: Exception) { throw DataFeedException("${venue.provider} پاسخ نداد") }
    }

    private fun pair(symbol: String, venue: Venue): String {
        if (CryptoCatalog.find(symbol) == null || !symbol.matches(Regex("[A-Z0-9]+/USDT")))
            throw DataFeedException("جفت دقیق USDT برای رادار تعریف نشده است")
        return when (venue) {
            Venue.GATE -> symbol.replace('/', '_')
            Venue.OKX -> symbol.replace('/', '-')
            Venue.KRAKEN -> symbol.replace("BTC/", "XBT/").replace("DOGE/", "XDG/").replace("/", "")
        }
    }

    private suspend fun checkListing(symbol: String, venue: Venue, pair: String): String {
        val key = "${venue.name}:$symbol"
        if (System.currentTimeMillis() - (listing[key] ?: 0L) in 0L..3_600_000L)
            return krakenPairs[key] ?: pair
        val base = symbol.substringBefore('/')
        val body = when (venue) {
            Venue.GATE -> get(venue, "/api/v4/spot/currency_pairs/$pair")
            Venue.OKX -> get(venue, "/api/v5/public/instruments?instType=SPOT&instId=$pair")
            Venue.KRAKEN -> get(venue, "/0/public/AssetPairs?pair=$pair")
        }
        val root = json.parseToJsonElement(body)
        val valid = when (venue) {
            Venue.GATE -> (root as? JsonObject)?.let {
                it.text("id") == pair && it.text("base") == base && it.text("quote") == "USDT" &&
                    it.text("type") == "normal" && it.text("trade_status") == "tradable"
            } == true
            Venue.KRAKEN -> {
                val obj = root as? JsonObject
                val results = obj?.obj("result")
                val alias = symbol.replace("BTC/", "XBT/").replace("DOGE/", "XDG/")
                val matched = results?.entries?.singleOrNull { (_, entry) ->
                    val data = entry as? JsonObject
                    data != null && data.text("wsname") == alias && data.text("status") == "online"
                }
                if (obj?.array("error")?.isEmpty() == true && matched != null) {
                    krakenPairs[key] = matched.key
                    true
                } else false
            }
            Venue.OKX -> (root as? JsonObject)?.takeIf { it.text("code") == "0" }
                ?.array("data")?.singleOrNull()?.let { it as? JsonObject }?.let {
                    it.text("instId") == pair && it.text("instType") == "SPOT" &&
                        it.text("baseCcy") == base && it.text("quoteCcy") == "USDT" &&
                        it.text("state") == "live"
                } == true
        }
        if (!valid) throw DataFeedException("${venue.provider}: جفت دقیق $symbol فعال/تأییدشده نیست")
        listing[key] = System.currentTimeMillis()
        return krakenPairs[key] ?: pair
    }

    suspend fun fetch(symbol: String, interval: Interval, venue: Venue,
                      now: Long = System.currentTimeMillis()): PublicCandleHistoryClient.Result {
        val requestedPair = pair(symbol, venue)
        if (interval !in listOf(Interval.M5, Interval.M15, Interval.H1))
            throw DataFeedException("رادار صرافی فقط M5/M15/H1 را پشتیبانی می‌کند")
        val pair = checkListing(symbol, venue, requestedPair)
        val bar = when (interval) {
            Interval.M5 -> "5m"; Interval.M15 -> "15m"
            else -> if (venue == Venue.GATE) "1h" else "1H"
        }
        val rows = when (venue) {
            Venue.GATE -> parseGate(get(venue, "/api/v4/spot/candlesticks?currency_pair=$pair&interval=$bar&limit=330"), interval, now)
            Venue.KRAKEN -> parseKraken(get(venue,
                "/0/public/OHLC?pair=$pair&interval=${interval.minutes}"), interval, now)
            Venue.OKX -> {
                val all = mutableListOf<Candle>()
                var after: Long? = null
                repeat(2) { // OKX 300/page; 320 CLOSED candles need two pages
                    val page = parseOkx(get(venue, "/api/v5/market/history-candles?instId=$pair&bar=$bar&limit=300" +
                        (after?.let { "&after=$it" } ?: "")), interval, now)
                    if (page.isNotEmpty()) {
                        val oldest = page.minOf { it.time }
                        if (after != null && oldest >= after!!) throw DataFeedException("صفحه‌های OKX پیش نمی‌روند")
                        after = oldest
                        all += page
                    }
                }
                all
            }
        }
        val tail = rows.filter { it.closed && it.time + interval.millis <= now }
            .distinctBy { it.time }.sortedBy { it.time }.takeLast(HistoryPolicy.LIVE_REQUEST_CANDLES)
        if (!CandleSourceHealth.ready(tail, interval, now, requireContiguous = true, requireVolume = true))
            throw DataFeedException("${venue.provider}: ۳۲۰ کندل بستهٔ پیوسته/تازهٔ $bar موجود نیست")
        return PublicCandleHistoryClient.Result(tail, venue.provider)
    }

    /** Last PUBLIC execution is a watch quote only. No stable replay cursor or
     * bid/ask is asserted here; never pass it into an entry/exit journal.
     */
    suspend fun fetchWatchTrade(symbol: String, venue: Venue,
                                now: Long = System.currentTimeMillis()): PriceTick {
        val id = checkListing(symbol, venue, pair(symbol, venue))
        val body = when (venue) {
            Venue.GATE -> get(venue, "/api/v4/spot/trades?currency_pair=$id&limit=1")
            Venue.OKX -> get(venue, "/api/v5/market/trades?instId=$id&limit=1")
            Venue.KRAKEN -> get(venue, "/0/public/Trades?pair=$id&count=1")
        }
        val tick = when (venue) {
            Venue.GATE -> parseGateTrade(body, id)
            Venue.OKX -> parseOkxTrade(body, id)
            Venue.KRAKEN -> parseKrakenTrade(body)
        }
        if (tick.at !in (now - 90_000L)..(now + 10_000L))
            throw DataFeedException("${venue.provider}: آخرین معامله تازه نیست")
        return tick
    }

    internal fun parseKrakenTrade(body: String): PriceTick {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw DataFeedException("معاملهٔ Kraken نامعتبر است")
        if (root.array("error")?.isEmpty() != true) throw DataFeedException("Kraken معامله را رد کرد")
        val result = root.obj("result") ?: throw DataFeedException("Kraken معامله ندارد")
        val rows = result.entries.singleOrNull { it.key != "last" }?.value as? JsonArray
            ?: throw DataFeedException("هویت جفت معاملات Kraken مبهم است")
        val row = rows.lastOrNull() as? JsonArray
            ?: throw DataFeedException("معاملهٔ Kraken موجود نیست")
        if (row.size < 3) throw DataFeedException("ستون معاملهٔ Kraken ناقص است")
        val t = row[2].string().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?.times(1000.0)?.toLong()
        return checkedTrade(row[0].string(), row[1].string(), t?.toString())
    }

    internal fun parseKraken(body: String, interval: Interval, now: Long): List<Candle> {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw DataFeedException("کندل Kraken نامعتبر است")
        if (root.array("error")?.isEmpty() != true) throw DataFeedException("Kraken کندل را رد کرد")
        val result = root.obj("result") ?: throw DataFeedException("Kraken کندل ندارد")
        val rows = result.entries.singleOrNull { it.key != "last" }?.value as? JsonArray
            ?: throw DataFeedException("هویت جفت کندل Kraken مبهم است")
        return rows.mapNotNull { item ->
            val row = item as? JsonArray ?: throw DataFeedException("ساختار کندل Kraken نامعتبر است")
            if (row.size < 7) throw DataFeedException("ستون کندل Kraken ناقص است")
            val time = row[0].string().toLongOrNull()?.times(1000L)
                ?: throw DataFeedException("زمان کندل Kraken نامعتبر است")
            if (time + interval.millis > now) return@mapNotNull null // current forming bar
            checkedCandle(time, row[1].string(), row[2].string(), row[3].string(),
                row[4].string(), row[6].string(), interval, now)
        }
    }

    internal fun parseGateTrade(body: String, id: String): PriceTick {
        val rows = json.parseToJsonElement(body) as? JsonArray
            ?: throw DataFeedException("معاملات Gate نامعتبرند")
        val row = rows.singleOrNull() as? JsonObject
            ?: throw DataFeedException("معاملهٔ Gate موجود نیست")
        if (row.text("currency_pair") != id) throw DataFeedException("معاملهٔ Gate با جفت درخواستی یکی نیست")
        return checkedTrade(row.text("price"), row.text("amount"), row.text("create_time_ms"))
    }

    internal fun parseOkxTrade(body: String, id: String): PriceTick {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw DataFeedException("معاملات OKX نامعتبرند")
        if (root.text("code") != "0") throw DataFeedException("معاملات OKX رد شد")
        val row = root.array("data")?.singleOrNull() as? JsonObject
            ?: throw DataFeedException("معاملهٔ OKX موجود نیست")
        if (row.text("instId") != id) throw DataFeedException("معاملهٔ OKX با جفت درخواستی یکی نیست")
        return checkedTrade(row.text("px"), row.text("sz"), row.text("ts"))
    }

    private fun checkedTrade(price: String?, size: String?, at: String?): PriceTick {
        val p = price?.toDoubleOrNull(); val amount = size?.toDoubleOrNull()
        val t = at?.toLongOrNull()
        if (p == null || !p.isFinite() || p <= 0 || amount == null ||
            !amount.isFinite() || amount <= 0 || t == null || t <= 0)
            throw DataFeedException("قیمت/حجم/زمان معامله معتبر نیست")
        return PriceTick(p, t)
    }

    internal fun parseGate(body: String, interval: Interval, now: Long): List<Candle> {
        val rows = json.parseToJsonElement(body) as? JsonArray ?: throw DataFeedException("کندل Gate نامعتبر است")
        return rows.mapNotNull { item ->
            val row = item as? JsonArray ?: throw DataFeedException("ساختار کندل Gate نامعتبر است")
            if (row.size < 8) throw DataFeedException("ستون‌های کندل Gate ناقص‌اند")
            if (row[7].string().lowercase() != "true") return@mapNotNull null
            checkedCandle(row[0].string().toLongOrNull()?.times(1000L), row[5].string(),
                row[3].string(), row[4].string(), row[2].string(), row[6].string(), interval, now)
        }
    }

    internal fun parseOkx(body: String, interval: Interval, now: Long): List<Candle> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: throw DataFeedException("کندل OKX نامعتبر است")
        if (root.text("code") != "0") throw DataFeedException("OKX دریافت کندل را رد کرد")
        return root.array("data")?.mapNotNull { item ->
            val row = item as? JsonArray ?: throw DataFeedException("ساختار کندل OKX نامعتبر است")
            if (row.size < 9) throw DataFeedException("ستون‌های کندل OKX ناقص‌اند")
            if (row[8].string() != "1") return@mapNotNull null
            checkedCandle(row[0].string().toLongOrNull(), row[1].string(),
                row[2].string(), row[3].string(), row[4].string(), row[5].string(), interval, now)
        } ?: throw DataFeedException("دادهٔ کندل OKX موجود نیست")
    }

    private fun checkedCandle(t: Long?, o: String, h: String, l: String, c: String, v: String,
                              interval: Interval, now: Long): Candle {
        val prices = listOf(o,h,l,c,v).map { it.toDoubleOrNull() ?: throw DataFeedException("عدد کندل معتبر نیست") }
        val (open, high, low, close, volume) = prices
        if (t == null || t <= 0 || t % interval.millis != 0L || t + interval.millis > now ||
            prices.any { !it.isFinite() } || minOf(open,high,low,close) <= 0 ||
            high < maxOf(open,close) || low > minOf(open,close) || volume <= 0)
            throw DataFeedException("کندل بستهٔ صرافی دارای زمان، قیمت یا حجم نامعتبر است")
        return Candle(t,open,high,low,close,volume,closed = true)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun kotlinx.serialization.json.JsonElement.string(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: throw DataFeedException("فیلد کندل نامعتبر است")
}
