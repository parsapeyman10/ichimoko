package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Recent *executed trades* on a single Nobitex pair. No sequence/ID is published by this
 * endpoint: an overlapping page proves coverage only while the client remains connected.
 */
internal object NobitexTradeBatch {
    data class Trade(val at: Long, val price: Double, val volume: Double, val side: String) {
        val tick: PriceTick get() = PriceTick(price, at)
    }

    fun parse(body: String, now: Long = System.currentTimeMillis()): List<Trade> {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("فهرست تیک نوبیتکس معتبر نیست")
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok")
            throw DataFeedException("فهرست تیک نوبیتکس پاسخ معتبر نداد")
        val list = root["trades"] as? JsonArray ?: throw DataFeedException("فهرست تیک نوبیتکس موجود نیست")
        if (list.isEmpty()) throw DataFeedException("فهرست تیک نوبیتکس خالی است")
        val trades = list.map { item ->
            val row = item as? JsonObject ?: throw DataFeedException("تیک ناقص است")
            val at = row["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw DataFeedException("زمان تیک موجود نیست")
            val price = row["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: throw DataFeedException("قیمت تیک موجود نیست")
            val volume = row["volume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: throw DataFeedException("حجم تیک موجود نیست")
            val side = row["type"]?.jsonPrimitive?.contentOrNull
                ?: throw DataFeedException("جهت تیک موجود نیست")
            if (at <= 0L || at > now + 10_000L || !price.isFinite() || price <= 0.0 ||
                !volume.isFinite() || volume < 0.0 || side !in setOf("buy", "sell"))
                throw DataFeedException("تیک زمان‌دار/حجم‌دار معتبر نیست")
            Trade(at, price, volume, side)
        }.sortedBy { it.at } // stable sort preserves provider order within the same millisecond
        if (!TradeQuotePolicy.accepts(trades.last().tick, now))
            throw DataFeedException("آخرین تیک نوبیتکس تازه نیست")
        return trades
    }
}

/** The page contains no cursor/ID. If pages do not overlap, lost executions cannot be
 * reconstructed from this endpoint, so mark a persistent gap and NEVER call it gapless.
 */
internal class TradePageCursor {
    data class Update(val newlySeen: List<NobitexTradeBatch.Trade>, val gap: Boolean)
    private var previous: List<NobitexTradeBatch.Trade>? = null

    fun advance(page: List<NobitexTradeBatch.Trade>, openedAt: Long): Update {
        require(page.isNotEmpty())
        val old = previous
        previous = page.toList()
        // Two different prices with the same millisecond have unknown execution order;
        // identical rows cannot be distinguished without trade IDs.
        val ambiguous = page.distinct().size != page.size ||
            page.zipWithNext().any { (a, b) -> a.at == b.at && a.price != b.price }
        if (old == null) {
            // On service restart, the page must reach the moment of entry. Otherwise
            // an unknown stretch of the open position cannot be declared monitored.
            val gap = page.first().at > openedAt || ambiguous
            return Update(if (gap) emptyList() else page.filter { it.at >= openedAt }, gap)
        }
        if (ambiguous || old.distinct().size != old.size)
            return Update(emptyList(), true)
        val overlap = (minOf(old.size, page.size) downTo 1).firstOrNull { length ->
            old.takeLast(length) == page.take(length)
        } ?: 0
        if (overlap == 0) return Update(emptyList(), true)
        return Update(page.drop(overlap).filter { it.at >= openedAt }, false)
    }
}
