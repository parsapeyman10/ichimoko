package com.aurum.edge.data

import com.aurum.edge.core.PriceTick
import com.aurum.edge.core.TradeQuotePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Public /v2/trades/SYMBOL: exchange time is in epoch milliseconds, never receipt time.
 * Only a same-pair, recently executed trade can authorize a paper entry or exit.
 */
internal object NobitexTradeQuote {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, now: Long = System.currentTimeMillis()): PriceTick {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("پاسخ معاملات نوبیتکس معتبر نیست")
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok")
            throw DataFeedException("نوبیتکس فهرست معاملات معتبر نداد")
        val trades = root["trades"] as? JsonArray
            ?: throw DataFeedException("فهرست معاملات نوبیتکس خالی است")
        val latest = trades.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val at = entry["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            val price = entry["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            PriceTick(price, at)
        }.maxByOrNull { it.at } ?: throw DataFeedException("نوبیتکس معاملهٔ زمان‌دار برنگرداند")
        if (!TradeQuotePolicy.accepts(latest, now))
            throw DataFeedException("آخرین معاملهٔ نوبیتکس دیررس یا با قیمت/زمان نامعتبر است")
        return latest
    }
}
