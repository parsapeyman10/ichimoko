package com.aurum.edge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Optional HTTPS reader for a *self-hosted* Nobitex-only archive. No positions are uploaded.
 * A local sequence is a persistence cursor, not an exchange trade ID; publisher gaps in the
 * response must be retained in the journal. Historical replay deliberately bypasses the
 * 45-second LIVE quote gate, but only for the archive's authenticated, ordered page.
 */
internal class NobitexTickArchiveClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).build(),
) {
    data class Row(val seq: Long, val at: Long, val price: Double)
    data class Gap(val at: Long, val afterSeq: Long)
    data class Page(val rows: List<Row>, val gaps: List<Gap>, val head: Long, val next: Long)

    suspend fun fetch(symbol: String, openedAt: Long, after: Long,
                      configuration: Pair<String, String>): Page = withContext(Dispatchers.IO) {
        require(CryptoCatalog.isCrypto(symbol) && openedAt > 0 && after >= 0)
        val ticker = NobitexHistoryClient.ticker(symbol) ?: throw DataFeedException("نماد آرشیو نامعتبر است")
        val url = configuration.first + "/v1/trades/$ticker?after=$after&limit=500" +
            if (after == 0L) "&from=$openedAt" else ""
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${configuration.second}")
            .header("Accept", "application/json").build()
        val body = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("آرشیو تیک در دسترس نیست: HTTP ${response.code}")
            response.body?.string()?.takeIf { it.length <= 500_000 }
                ?: throw DataFeedException("پاسخ آرشیو تیک بزرگ یا خالی است")
        }
        parse(body, ticker, after)
    }

    companion object {
        fun parse(body: String, expectedTicker: String, after: Long): Page {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: throw DataFeedException("پاسخ آرشیو تیک نامعتبر است")
            fun text(row: JsonObject, key: String): String? = row[key]?.jsonPrimitive?.contentOrNull
            if (text(root, "venue") != "nobitex" || text(root, "symbol") != expectedTicker)
                throw DataFeedException("بازار آرشیو با پوزیشن هم‌صرافی نیست")
            val head = text(root, "head")?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw DataFeedException("سر آرشیو معتبر نیست")
            val next = text(root, "next")?.toLongOrNull()?.takeIf { it >= after && it <= head }
                ?: throw DataFeedException("دنبالهٔ آرشیو معتبر نیست")
            val trades = root["trades"] as? JsonArray ?: throw DataFeedException("ردیف‌های آرشیو موجود نیست")
            if (trades.size > 500) throw DataFeedException("صفحهٔ آرشیو بیش از حد است")
            val rows = trades.map { item ->
                val row = item as? JsonObject ?: throw DataFeedException("تیک آرشیو ناقص است")
                val seq = text(row, "seq")?.toLongOrNull() ?: throw DataFeedException("دنباله نامعتبر است")
                val at = text(row, "time")?.toLongOrNull() ?: throw DataFeedException("زمان نامعتبر است")
                val price = text(row, "price")?.toDoubleOrNull() ?: throw DataFeedException("قیمت نامعتبر است")
                if (seq <= after || seq > head || at <= 0 || !price.isFinite() || price <= 0 ||
                    at > System.currentTimeMillis() + 10_000L)
                    throw DataFeedException("ردیف آرشیو نامعتبر است")
                Row(seq, at, price)
            }
            if (rows.zipWithNext().any { (a, b) -> a.seq >= b.seq } ||
                (rows.isNotEmpty() && rows.last().seq != next))
                throw DataFeedException("ترتیب آرشیو نامعتبر است")
            val gaps = (root["gaps"] as? JsonArray)?.map { item ->
                val gap = item as? JsonObject ?: throw DataFeedException("گسست ناقص است")
                val at = text(gap, "at")?.toLongOrNull() ?: throw DataFeedException("زمان گسست نامعتبر است")
                val seq = text(gap, "afterSeq")?.toLongOrNull() ?: throw DataFeedException("مکان گسست نامعتبر است")
                if (at <= 0 || seq < 0 || seq > head) throw DataFeedException("گسست نامعتبر است")
                Gap(at, seq)
            } ?: throw DataFeedException("وضعیت پیوستگی آرشیو موجود نیست")
            Page(rows, gaps, head, next)
        }
    }
}
