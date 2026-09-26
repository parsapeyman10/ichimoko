package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Fixed set confirmed in Nobitex's public /market/stats on 2026-09-24; not an order allowlist. */
object NobitexSpotCatalog {
    val bases = listOf("BTC", "ETH", "SOL", "XRP", "DOGE", "ADA")
    const val url = "https://apiv2.nobitex.ir/market/stats?srcCurrency=btc,eth,sol,xrp,doge,ada&dstCurrency=usdt,rls"
    const val document = "https://apidocs.nobitex.ir/market_data/%D8%A2%D9%85%D8%A7%D8%B1-%D8%A8%D8%A7%D8%B2%D8%A7%D8%B1"
}

data class SpotStat(
    val base: String,
    val quote: String,
    val bestBuy: Double?,
    val bestSell: Double?,
    val latest: Double?,
    val dayChangePct: Double?,
    val volumeQuote: Double?,
    val spreadPct: Double?,
    val observation: String,
    val candidate: Boolean,
)

data class SpotScan(val pairs: List<SpotStat>, val receivedAt: Long, val complete: Boolean) {
    val candidates: Int get() = pairs.count { it.candidate }
    /** Timestamp belongs to the *phone's receipt*, not the exchange's last matching trade. */
    fun fresh(now: Long = System.currentTimeMillis()): Boolean = complete && now - receivedAt in 0L..180_000L
}

/** Only observations from a real response; invalid/closed/missing pairs never become candidates. */
internal fun parseSpotStats(root: JsonObject, at: Long): SpotScan {
    fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    require(root.value("status") == "ok") { "آمار عمومی نوبیتکس معتبر نیست" }
    val rows = root["stats"] as? JsonObject ?: error("جدول بازار اسپات موجود نیست")
    val pairs = listOf("usdt", "rls").flatMap { quote ->
        NobitexSpotCatalog.bases.map { base ->
            val stat = rows["${base.lowercase()}-$quote"] as? JsonObject
            fun num(key: String) = stat?.value(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            val bid = num("bestBuy")
            val ask = num("bestSell")
            val latest = num("latest")
            val volume = num("volumeDst")
            val change = stat?.value("dayChange")?.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= 100.0 }
            val closed = stat?.value("isClosed")
            val spread = if (bid != null && ask != null && bid <= ask) (ask - bid) / ask * 100 else null
            val valid = stat != null && closed in setOf("true", "false") && bid != null && ask != null &&
                latest != null && volume != null && change != null && spread != null &&
                abs(latest / ask!! - 1.0) <= 0.05
            val minTurnover = if (quote == "usdt") 20_000.0 else 50_000_000_000.0
            val candidate = valid && closed == "false" && spread!! <= 0.8 && volume!! >= minTurnover &&
                change!! in 1.0..12.0
            val reason = when {
                !valid -> "آمار/حجم/قیمت این جفت ناقص یا ناسازگار است"
                closed == "true" -> "بازار بسته است"
                spread!! > 0.8 -> "اسپرد بیشتر از ۰٫۸٪"
                volume!! < minTurnover -> "ارزش مبادلهٔ ۲۴ساعته کمتر از آستانهٔ پژوهشی"
                change!! < 1.0 -> "تغییر ۲۴ساعته کمتر از ۱٪؛ شتاب گذشته مشاهده نشد"
                change!! > 12.0 -> "جهش بیش از ۱۲٪؛ ریسک تعقیب حرکت"
                else -> "فقط نامزدِ بررسی دستی؛ روند گذشته آینده را پیش‌بینی نمی‌کند"
            }
            SpotStat(base, if (quote == "usdt") "USDT" else "ریال", bid, ask, latest,
                change, volume, spread, reason, candidate = candidate)
        }
    }
    return SpotScan(pairs, at, complete = pairs.size == 12 && pairs.all {
        it.bestBuy != null && it.bestSell != null && it.latest != null &&
            it.dayChangePct != null && it.volumeQuote != null && it.spreadPct != null &&
            !it.observation.startsWith("آمار/حجم/قیمت")
    })
}

/** Single fixed public GET for both quote currencies, <1/min per phone; no keys or POST. */
class NobitexSpotScanner(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastAttemptAt: Long = 0L

    suspend fun scan(): SpotScan {
        val now = clock()
        require(lastAttemptAt == 0L || now - lastAttemptAt >= 60_000L) {
            "برای رعایت سهمیه، ۶۰ ثانیه بین غربال‌ها صبر کنید"
        }
        lastAttemptAt = now
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(NobitexSpotCatalog.url).get()
                .header("User-Agent", "TraderBot/AurumEdge-1.0.0")
                .header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                if (response.code == 429) error("سهمیهٔ عمومی نوبیتکس تمام شد (۴۲۹)")
                require(response.isSuccessful) { "دریافت آمار بازار ناموفق بود (HTTP ${response.code})" }
                val data = response.peekBody(256_001L).string()
                require(data.isNotBlank() && data.length <= 256_000) { "آمار بازار تهی یا بزرگ است" }
                val root = json.parseToJsonElement(data) as? JsonObject ?: error("پاسخ بازار JSON معتبر نیست")
                parseSpotStats(root, clock())
            }
        }
    }
}
