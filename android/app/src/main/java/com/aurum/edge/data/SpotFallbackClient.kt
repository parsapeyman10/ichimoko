package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * Automatic, keyless real spot-price fallback used only when no Twelve Data key is configured
 * on this installation. Mirrors the backend's app/services/spot_feed.py: Swissquote's public
 * BBO quote feed first, then gold-api.com (gold only) as a second real source.
 *
 * This is not a mock/synthetic generator: every value returned is parsed from a live provider
 * response, and any parsing/staleness problem raises [DataFeedException] — never a fabricated
 * price. It fetches *ticks* only; [MarketRepository.onTick] already builds/persists real candles
 * from a (price, time) tick regardless of which provider produced it, so no separate candle
 * aggregator is needed here.
 */
class SpotFallbackClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

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
        return PriceTick((bid + ask) / 2.0, at)
    }

    /** gold-api.com: a second, independent, keyless source — gold (XAU/USD) only. */
    private fun fetchGoldApi(symbol: String): PriceTick {
        if (!symbol.trim().equals("XAU/USD", ignoreCase = true)) {
            throw DataFeedException("Gold-API فقط برای XAU/USD در دسترس است")
        }
        val body = try {
            get("https://api.gold-api.com/price/XAU")
        } catch (e: DataFeedException) {
            throw e
        } catch (e: Exception) {
            throw DataFeedException("اتصال به Gold-API برقرار نشد")
        }
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ Gold-API معتبر نیست")
        if (obj["symbol"]?.jsonPrimitive?.contentOrNull?.uppercase(Locale.ROOT) != "XAU") {
            throw DataFeedException("پاسخ Gold-API معتبر نیست")
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
        return PriceTick(price, at)
    }

    /** One real quote: Swissquote first, gold-api.com only as a gold-only backup. Never fabricated. */
    suspend fun fetchQuote(symbol: String): PriceTick {
        return try {
            fetchSwissquote(symbol)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (primary: Exception) {
            try {
                fetchGoldApi(symbol)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (secondary: Exception) {
                throw DataFeedException(
                    "منابع رایگان قیمت لحظه‌ای (Swissquote/Gold-API) در دسترس نیستند"
                )
            }
        }
    }

    /** Infinite real-tick stream, polled every [POLL_INTERVAL_MS]. A failure closes the flow. */
    fun streamQuotes(symbol: String): Flow<PriceTick> = flow {
        while (true) {
            emit(fetchQuote(symbol))
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val MAX_QUOTE_AGE_MS = 45_000L
        const val POLL_INTERVAL_MS = 5_000L
    }
}
