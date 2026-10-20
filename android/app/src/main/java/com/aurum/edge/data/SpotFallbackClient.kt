package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Automatic real spot-price source: Swissquote's public BBO for FX and both metals,
 * gold-api.com for spot gold and silver, Nobitex executed trades as the crypto REFERENCE,
 * and the per-symbol exchange (Binance/OKX/Gate/MEXC/Kraken) only as a read-only fallback
 * for crypto. Legacy Binance/Nobitex positions retain their dedicated same-venue replay.
 * MarketRepository tries this public source before its final Twelve Data live fallback for
 * every market except spot gold, where Twelve Data is the reference publisher and these
 * public feeds are the fallback instead.
 *
 * This is not a mock/synthetic generator: every value returned is parsed from a live provider
 * response, and any parsing/staleness problem raises [DataFeedException] — never a fabricated
 * price. It fetches *ticks* only; [MarketRepository.onTick] already builds/persists real candles
 * from a (price, time) tick regardless of which provider produced it, so no separate candle
 * aggregator is needed here.
 */
class SpotFallbackClient(
    private val client: OkHttpClient = AppNetwork.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val binance = BinanceSpotClient()

    // Instances used by the screen, service and scanner share a rate budget. Paper
    // positions get a reserved high-priority lane; low-priority scans cannot exhaust it.
    private object CryptoTradeCache {
        // A slow BTC request must not lock ETH. Only the short rate budget is global.
        val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val responses = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
        val lastAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val budget = NobitexTradeBudget()
        // The order book changes less often than the tape; 15 s keeps the spread honest without
        // spending the public request budget twice per tick.
        val bookLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val books = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, NobitexBook>>()
        const val MIN_CRYPTO_FETCH_MS = 10_000L
        const val OPEN_POSITION_FETCH_MS = 3_000L
        const val BOOK_CACHE_MS = 15_000L
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("خطای HTTP ${response.code} از فید رایگان")
            val bytes = response.peekBody(500_000L).bytes()
            if (bytes.size > 500_000) throw DataFeedException("پاسخ فید رایگان بیش از حد بزرگ است")
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) throw DataFeedException("پاسخ خالی از فید رایگان")
            return text
        }
    }

    /** Swissquote's public retail BBO feed; works for gold and most major FX pairs, no key. */
    private fun fetchSwissquote(symbol: String): PriceTick {
        val parts = symbol.trim().uppercase(Locale.ROOT).split("/").map { it.trim() }
        if (parts.size != 2 || parts.any { it.isEmpty() }) {
            throw DataFeedException("این نماد برای فید رایگان Swissquote پشتیبانی نمی‌شود")
        }
        val url = "https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/${parts[0]}/${parts[1]}"
        val body = try {
            get(url)
        } catch (e: DataFeedException) {
            throw e
        } catch (e: Exception) {
            throw DataFeedException("اتصال به Swissquote برقرار نشد")
        }
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
        val quote = root?.firstOrNull() as? JsonObject ?: throw DataFeedException("پاسخ Swissquote معتبر نیست")
        val profiles = quote["spreadProfilePrices"] as? JsonArray
            ?: throw DataFeedException("پاسخ Swissquote فاقد قیمت است")
        if (profiles.isEmpty()) throw DataFeedException("پاسخ Swissquote فاقد قیمت است")
        val prime = profiles.firstOrNull { entry ->
            (entry as? JsonObject)?.get("spreadProfile")?.jsonPrimitive?.contentOrNull == "prime"
        } as? JsonObject ?: profiles.first() as? JsonObject
            ?: throw DataFeedException("پاسخ Swissquote فاقد قیمت است")
        val bid = prime["bid"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val ask = prime["ask"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val tsRaw = quote["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (bid == null || ask == null || tsRaw == null) {
            throw DataFeedException("bid/ask یا زمان Swissquote معتبر نیست")
        }
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid) {
            throw DataFeedException("bid/ask دریافتی از Swissquote نامعتبر است")
        }
        val at = tsRaw.toLong()
        val age = System.currentTimeMillis() - at
        if (age !in -10_000L..MAX_QUOTE_AGE_MS) throw DataFeedException("قیمت Swissquote قدیمی یا با زمان نامعتبر است")
        return PriceTick((bid + ask) / 2.0, at, bid = bid, ask = ask, provider = "Swissquote")
    }

    /** Watch-only, exact Swissquote bid/ask; does NOT silently relabel Gold-API. */
    suspend fun fetchSwissquoteOnly(symbol: String): PriceTick = withContext(Dispatchers.IO) {
        fetchSwissquote(symbol)
    }

    /**
     * gold-api.com: a second, independent, keyless source for BOTH spot metals.
     *
     * The endpoint is `/price/{metal}`: asking for XAU and accepting it for a silver request
     * (the previous behaviour) always failed for XAG/USD. The requested metal is now part of
     * the URL AND of the response check, so a silver tick can never be a gold price.
     */
    private fun fetchGoldApi(symbol: String): PriceTick {
        val parts = symbol.trim().uppercase(Locale.ROOT).split("/").map { it.trim() }
        val metal = parts.getOrNull(0).orEmpty()
        if (parts.size != 2 || parts[1] != "USD" || metal !in METALS) {
            throw DataFeedException("Gold-API فقط XAU/USD و XAG/USD را پوشش می‌دهد")
        }
        val body = try {
            get("https://api.gold-api.com/price/$metal")
        } catch (e: DataFeedException) {
            throw e
        } catch (e: Exception) {
            throw DataFeedException("اتصال به Gold-API برقرار نشد")
        }
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ Gold-API معتبر نیست")
        val answered = obj["symbol"]?.jsonPrimitive?.contentOrNull?.uppercase(Locale.ROOT)
        if (answered != metal) {
            throw DataFeedException("پاسخ Gold-API برای $metal معتبر نیست")
        }
        val price = obj["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: throw DataFeedException("قیمت Gold-API معتبر نیست")
        val updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
            ?: throw DataFeedException("زمان Gold-API معتبر نیست")
        val at = runCatching { Instant.parse(updatedAt.replace(" ", "T")).toEpochMilli() }.getOrNull()
            ?: throw DataFeedException("زمان Gold-API معتبر نیست")
        if (!price.isFinite() || price <= 0.0) throw DataFeedException("قیمت Gold-API نامعتبر است")
        val age = System.currentTimeMillis() - at
        if (age !in -10_000L..MAX_QUOTE_AGE_MS) throw DataFeedException("قیمت Gold-API قدیمی است")
        return PriceTick(price, at, provider = "Gold-API")
    }

    private suspend fun cryptoTradeResponse(symbol: String, openPosition: Boolean = false,
                                            openedAt: Long = 0L): String {
        val code = NobitexHistoryClient.ticker(symbol)
            ?: throw DataFeedException("نماد USDT برای نوبیتکس معتبر نیست")
        val lock = CryptoTradeCache.locks.computeIfAbsent(symbol) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            val minInterval = if (openPosition) CryptoTradeCache.OPEN_POSITION_FETCH_MS
                else CryptoTradeCache.MIN_CRYPTO_FETCH_MS
            CryptoTradeCache.responses[symbol]?.takeIf { (fetchedAt, _) ->
                now - fetchedAt in 0L..minInterval && (!openPosition || fetchedAt >= openedAt)
            }?.let { return@withLock it.second }
            if (now - (CryptoTradeCache.lastAttempt[symbol] ?: 0L) in 0L until minInterval)
                throw DataFeedException("فاصلهٔ مجاز درخواست تیک نوبیتکس؛ پاسخ قبلی معتبر نیست")
            // Reserve capacity BEFORE the request so failures and HTTP 429 do not cause
            // retries that crowd out a currently open position.
            if (!CryptoTradeCache.budget.tryAcquire(System.nanoTime() / 1_000_000L, openPosition))
                throw DataFeedException("سهمیهٔ تیک عمومی پر است؛ پوزیشن باز اولویت دارد")
            CryptoTradeCache.lastAttempt[symbol] = now
            val body = withContext(Dispatchers.IO) { get("${NobitexHistoryClient.HOST}/v2/trades/$code") }
            CryptoTradeCache.responses[symbol] = now to body
            body
        }
    }

    /**
     * The trade print stays the reference price; the best bid/ask is attached from the same
     * venue's order book. If the book is unavailable the tick is returned **without** a spread and
     * the engines keep refusing — an estimated spread is never substituted for a measured one.
     * The usable time is the older of the two timestamps, so a stale book cannot pass as fresh.
     */
    private suspend fun nobitexTwoSided(symbol: String, trade: PriceTick): PriceTick {
        val book = try {
            nobitexBook(symbol)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            return trade.copy(sourceNote = "معاملهٔ اجراشدهٔ نوبیتکس — دفتر سفارش در دسترس نبود؛ " +
                "اسپرد واقعی تأیید نشده")
        }
        val at = minOf(trade.at, book.at)
        if (System.currentTimeMillis() - at > MAX_QUOTE_AGE_MS)
            return trade.copy(sourceNote = "معاملهٔ اجراشدهٔ نوبیتکس — دفتر سفارش کهنه؛ اسپرد تأیید نشد")
        return trade.copy(bid = book.bid, ask = book.ask, at = at,
            sourceNote = "معاملهٔ اجراشده + بهترین خرید/فروش دفتر سفارش نوبیتکس")
    }

    private suspend fun nobitexBook(symbol: String): NobitexBook {
        val code = NobitexHistoryClient.ticker(symbol)
            ?: throw DataFeedException("نماد USDT برای دفتر سفارش نوبیتکس معتبر نیست")
        val lock = CryptoTradeCache.bookLocks.computeIfAbsent(symbol) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            CryptoTradeCache.books[symbol]?.takeIf { (fetchedAt, _) ->
                now - fetchedAt in 0L..CryptoTradeCache.BOOK_CACHE_MS
            }?.let { return@withLock it.second }
            // The book is an extra request: it yields to the trade budget instead of crowding it.
            if (!CryptoTradeCache.budget.tryAcquire(now, false))
                throw DataFeedException("سهمیهٔ درخواست عمومی پر است؛ دفتر سفارش خوانده نشد")
            val body = withContext(Dispatchers.IO) { get("${NobitexHistoryClient.HOST}/v2/orderbook/$code") }
            val book = NobitexOrderBook.parse(body)
            CryptoTradeCache.books[symbol] = now to book
            book
        }
    }

    /** The only priority lane: an actually OPEN paper position, still capped at 3s/pair.
     * A display quote and a scanner candidate never acquire this reservation.
     */
    internal suspend fun fetchCryptoTrades(symbol: String, openedAt: Long): List<NobitexTradeBatch.Trade> {
        if (!CryptoCatalog.isCrypto(symbol) || openedAt <= 0L)
            throw DataFeedException("پوزیشن باز معتبر برای فید اولویت‌دار وجود ندارد")
        return NobitexTradeBatch.parse(cryptoTradeResponse(symbol, openPosition = true, openedAt = openedAt))
    }

    private object GoldBudget {
        val mutex = Mutex()
        var cached: PriceTick? = null
        var receivedAt = 0L
        var revision = -1L
    }

    private suspend fun compareGold(): PriceTick = GoldBudget.mutex.withLock {
        val route = AppNetwork.route.value
        if (!route.ready) throw DataFeedException("پروکسی آماده نیست؛ قیمت زنده تأیید نشد")
        val gate = SourceIntegrity.shared
        val token = gate.begin("XAU/USD", SourceIntegrity.Channel.GOLD_QUOTE)
        val now = System.currentTimeMillis()
        GoldBudget.cached?.takeIf { !gate.channelBlocked("XAU/USD", SourceIntegrity.Channel.GOLD_QUOTE) && GoldBudget.revision == route.revision &&
            now - GoldBudget.receivedAt in 0L..5_000L && now - it.at in -10_000L..45_000L
        }?.let { return@withLock it }
        val quotes = coroutineScope {
            listOf<suspend () -> PriceTick>(
                { withContext(Dispatchers.IO) { fetchSwissquote("XAU/USD") } },
                { withContext(Dispatchers.IO) { fetchGoldApi("XAU/USD") } },
            ).map { read -> async {
                try { read() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            } }.awaitAll().filterNotNull()
        }
        if (route != AppNetwork.route.value) throw DataFeedException("مسیر قیمت طلا تغییر کرد؛ دریافت دوباره لازم است")
        GoldBudget.cached = null // a new conflict must not reuse a previous agreement
        val selected = try { SpotPriceSelection.gold(quotes, System.currentTimeMillis()) }
        catch (failure: SourceDisagreement) {
            gate.reject("XAU/USD", SourceIntegrity.Channel.GOLD_QUOTE, failure.message.orEmpty())
            throw failure
        }
        if (gate.accept("XAU/USD", SourceIntegrity.Channel.GOLD_QUOTE, token, selected.sourceConfirmations) == null)
            throw DataFeedException("اختلاف قیمت طلا هنوز رفع نشده؛ دو منبع همخوان لازم است")
        selected.also {
            GoldBudget.cached = it; GoldBudget.receivedAt = System.currentTimeMillis(); GoldBudget.revision = route.revision
        }
    }

    /**
     * Spot metal quote: Gold-API first, Swissquote BBO second. Both publish the requested
     * metal only; a failure is reported instead of relabelling the other metal's price.
     */
    private fun fetchMetalQuote(symbol: String): PriceTick {
        // Gold-API publishes one price; Swissquote publishes a two-sided BBO. ALS-5S refuses to
        // enter without a real bid/ask (that refusal is the cost gate, not a bug), so silver asks
        // the BBO first and keeps Gold-API as the fallback. Gold is unchanged: Gold-API first.
        val order: List<() -> PriceTick> = if (symbol == "XAG/USD")
            listOf({ fetchSwissquote(symbol) }, { fetchGoldApi(symbol) })
        else
            listOf({ fetchGoldApi(symbol) }, { fetchSwissquote(symbol) })
        val failures = mutableListOf<String>()
        for (read in order) {
            try {
                return read()
            } catch (e: DataFeedException) {
                failures += e.message.orEmpty()
            }
        }
        throw DataFeedException("قیمت لحظه‌ای $symbol از Gold-API و Swissquote دریافت نشد: " +
            failures.joinToString("؛ ").take(160))
    }

    /**
     * Crypto reference quote. Nobitex executed trades are the primary and the only feed that
     * can confirm a Nobitex-labelled paper entry; the symbol's dedicated exchange is a
     * read-only fallback that keeps its OWN provider label (so the venue gate still fails).
     */
    private suspend fun fetchCryptoQuote(symbol: String): PriceTick {
        var nobitexFailure: String? = null
        if (NobitexReference.serves(symbol)) {
            try {
                val body = cryptoTradeResponse(symbol)
                val trade = NobitexTradeQuote.parse(body)
                return nobitexTwoSided(symbol, trade).copy(
                    provider = NobitexReference.TICK_PROVIDER,
                    sourceConfirmations = 1,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                nobitexFailure = (error.message ?: "بدون پاسخ").take(100)
            }
        }
        val venue = SourceRouting.cryptoVenueFor(symbol)
            ?: throw DataFeedException("منبع رمزارز تعریف نشده: $symbol" +
                (nobitexFailure?.let { " · نوبیتکس: $it" } ?: ""))
        val fallback = try {
            if (venue == CryptoRadarCandleClient.Venue.BINANCE) {
                binance.fetchQuote(symbol)
            } else {
                CryptoRadarCandleClient(client).fetchWatchTrade(symbol, venue)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw DataFeedException("قیمت ${symbol} از نوبیتکس و ${venue.provider} دریافت نشد: " +
                listOfNotNull(nobitexFailure, error.message).joinToString("؛ ").take(140))
        }
        return fallback.copy(
            provider = venue.provider,
            sourceNote = "جایگزین فقط‌خواندنی؛ تاریخچه/ورود نوبیتکس با این تیک تأیید نمی‌شود",
        )
    }

    /** Single dedicated route per symbol (1-to-1). Never fabricated. */
    suspend fun fetchQuote(symbol: String): PriceTick {
        val canonical = com.aurum.edge.core.V1Universe.normalize(symbol)
        if (CryptoCatalog.isCrypto(canonical)) return fetchCryptoQuote(canonical)
        val plan = tickPlanFor(canonical)
        return withContext(Dispatchers.IO) {
            when (plan.route) {
                TickRoute.METAL -> fetchMetalQuote(canonical)
                TickRoute.YAHOO_INDEX -> fetchYahooIndexQuote(plan.yahooSymbol
                    ?: throw DataFeedException("نماد Yahoo برای $canonical تعریف نشده است"))
                TickRoute.SWISSQUOTE -> fetchSwissquote(canonical)
            }
        }
    }

    private fun fetchYahooIndexQuote(yahooSymbol: String): PriceTick {
        val encoded = java.net.URLEncoder.encode(yahooSymbol, "UTF-8").replace("%3D", "=")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=5m&range=1d&includePrePost=false"
        val body = get(url)
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ Yahoo معتبر نیست")
        val meta = ((root["chart"] as? JsonObject)?.get("result") as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val metaObj = meta?.get("meta") as? JsonObject
            ?: throw DataFeedException("متای قیمت Yahoo موجود نیست")
        val price = metaObj["regularMarketPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw DataFeedException("قیمت لحظه‌ای Yahoo معتبر نیست")
        val tsSec = metaObj["regularMarketTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw DataFeedException("زمان قیمت Yahoo موجود نیست")
        val at = tsSec * 1000L
        return PriceTick(
            price = price,
            at = at,
            provider = "Yahoo Dedicated ($yahooSymbol)",
            sourceConfirmations = 1,
        )
    }

    /** Infinite real-tick stream, polled every [POLL_INTERVAL_MS]. A failure closes the flow. */
    fun streamQuotes(symbol: String): Flow<PriceTick> = flow {
        while (true) {
            emit(fetchQuote(symbol))
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private val METALS = setOf("XAU", "XAG")
        private const val MAX_QUOTE_AGE_MS = 45_000L
        const val POLL_INTERVAL_MS = 1_000L
    }
}

/** Where a keyless/keyed live price for a non-crypto symbol is read from. Pure + testable. */
internal enum class TickRoute { METAL, YAHOO_INDEX, SWISSQUOTE }

internal data class TickPlan(val route: TickRoute, val yahooSymbol: String? = null)

/**
 * The tick table of docs/SYMBOL_SOURCES_FA.md, in code. Metals use Gold-API then Swissquote;
 * the Dow and two FX pairs are quoted by Yahoo (same family as their candle source);
 * everything else keeps the Swissquote BBO. A symbol without a route never becomes another
 * instrument's price.
 */
internal fun tickPlanFor(canonical: String): TickPlan = when (canonical) {
    "XAU/USD", "XAG/USD" -> TickPlan(TickRoute.METAL)
    "DOW" -> TickPlan(TickRoute.YAHOO_INDEX, "^DJI")
    // EUR/USD is quoted by Swissquote, not Yahoo: EUR-ARES prices its spread before every entry and
    // Yahoo publishes a single number with no bid/ask, so the pair could never pass its own cost
    // gate. Candles still come from Yahoo/Dukascopy; only the tick moved. USD/JPY and GBP/JPY stay
    // on Yahoo because the four-layer model does not need a two-sided quote.
    "USD/JPY" -> TickPlan(TickRoute.YAHOO_INDEX, "JPY=X")
    "GBP/JPY" -> TickPlan(TickRoute.YAHOO_INDEX, "GBPJPY=X")
    else -> TickPlan(TickRoute.SWISSQUOTE)
}
