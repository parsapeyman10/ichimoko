package com.aurum.edge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best two-sided prices from Nobitex's public order book (`GET /v2/orderbook/{SYMBOL}`).
 *
 * Why this exists: a *trade* tick is a single printed price and carries no spread. Every engine
 * that must judge cost before entry (EUR-ARES, ALS-5S, BTC-ARES, compression) refuses to trade
 * without a real bid/ask, and it must keep refusing — an invented spread would silently pass a
 * cost gate that is supposed to protect the account. So the bid/ask is read from the venue's own
 * book instead of being guessed, and the trade print stays the reference price.
 *
 * The parser is deliberately strict and fails closed:
 *  - it takes the **best** prices (`max` bid, `min` ask) instead of trusting array order;
 *  - if the result is inverted (`ask < bid`) the book is rejected rather than relabelled. Nobitex
 *    has published payloads whose two arrays look swapped (the "bids" ladder ascending above the
 *    "asks" ladder), and a crossed book is indistinguishable from that bug; guessing which side is
 *    which would manufacture a spread nobody measured, so the honest answer is "no quote";
 *  - a stale `lastUpdate` is a failure, not a quote;
 *  - amounts are not used for anything, so a truncated book still gives honest best prices.
 */
internal data class NobitexBook(val bid: Double, val ask: Double, val at: Long) {
    val mid: Double get() = (bid + ask) / 2.0
    val spread: Double get() = ask - bid
}

internal object NobitexOrderBook {
    /** A book older than this is not a quote: the same 45 s freshness window as trade ticks. */
    const val MAX_AGE_MS = 45_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, now: Long = System.currentTimeMillis()): NobitexBook {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DataFeedException("دفتر سفارش نوبیتکس معتبر نیست")
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok")
            throw DataFeedException("دفتر سفارش نوبیتکس پاسخ معتبر نداد")
        val at = root["lastUpdate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw DataFeedException("زمان دفتر سفارش نوبیتکس موجود نیست")
        if (at <= 0L || now - at !in -10_000L..MAX_AGE_MS)
            throw DataFeedException("دفتر سفارش نوبیتکس قدیمی است")
        val bestBid = levels(root["bids"], "خرید").maxOrNull()
            ?: throw DataFeedException("سمت خرید دفتر سفارش نوبیتکس خالی است")
        val bestAsk = levels(root["asks"], "فروش").minOrNull()
            ?: throw DataFeedException("سمت فروش دفتر سفارش نوبیتکس خالی است")
        // Never "fix" an inverted book: the sides may be mislabelled upstream, and a silent swap
        // would turn a broken feed into a fake edge.
        if (bestAsk < bestBid)
            throw DataFeedException("دفتر سفارش نوبیتکس وارونه است (فروش < خرید)؛ اسپرد تأیید نشد")
        if (!bestBid.isFinite() || !bestAsk.isFinite() || bestBid <= 0.0)
            throw DataFeedException("قیمت دفتر سفارش نوبیتکس معتبر نیست")
        return NobitexBook(bestBid, bestAsk, at)
    }

    private fun levels(element: kotlinx.serialization.json.JsonElement?, side: String): List<Double> {
        val rows = element as? JsonArray ?: throw DataFeedException("سمت $side دفتر سفارش نوبیتکس موجود نیست")
        return rows.mapNotNull { row ->
            val pair = row as? JsonArray ?: return@mapNotNull null
            pair.firstOrNull()?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
        }
    }
}
