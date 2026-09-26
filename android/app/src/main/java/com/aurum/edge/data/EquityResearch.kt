package com.aurum.edge.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalTime

/** BrsApi's TSETMC snapshot has only an HH:mm:ss board clock, NOT a dated trade timestamp. */
data class EquityRow(
    val symbol: String,
    val name: String,
    val isin: String,
    val boardClock: String,
    val lastRial: Long?,
    val closeRial: Long?,
    val epsRial: Double?,
    val pe: Double?,
    val turnoverRial: Long?,
    val tradedShares: Long?,
    val retailBuyVolume: Long?,
    val retailSellVolume: Long?,
    val retailBuyCount: Long?,
    val retailSellCount: Long?,
    val bidRial: Long?,
    val askRial: Long?,
) {
    /** Mean purchase volume / mean sale volume; not a promise of future demand. */
    val retailPower: Double? get() {
        val bv = retailBuyVolume ?: return null
        val sv = retailSellVolume ?: return null
        val bc = retailBuyCount ?: return null
        val sc = retailSellCount ?: return null
        if (bv <= 0 || sv <= 0 || bc <= 0 || sc <= 0) return null
        return ((bv.toDouble() / bc) / (sv.toDouble() / sc)).takeIf { it.isFinite() && it in 0.0..1_000_000.0 }
    }
    val netRetailShares: Long? get() = retailBuyVolume?.let { buy ->
        retailSellVolume?.let { sell -> buy - sell }
    }
    val bestSpreadPct: Double? get() {
        val bid = bidRial ?: return null
        val ask = askRial ?: return null
        if (bid <= 0 || ask < bid) return null
        return ((ask - bid).toDouble() / ask * 100).takeIf { it.isFinite() && it in 0.0..100.0 }
    }
    /** NOT a CAN SLIM verdict: merely two documented, simultaneous numerical filters. */
    val basicValuePass: Boolean get() = epsRial?.let { it > 0 } == true && pe?.let { it in 0.01..15.0 } == true
    val boardPass: Boolean get() = turnoverRial?.let { it >= 10_000_000_000L } == true &&
        retailPower?.let { it >= 1.2 } == true && netRetailShares?.let { it > 0 } == true &&
        bestSpreadPct?.let { it <= 2.0 } == true
}

private fun JsonObject.raw(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Double? = raw(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun JsonObject.nonnegative(key: String): Long? = raw(key)?.toLongOrNull()?.takeIf { it >= 0L }
private fun JsonObject.price(key: String): Long? = nonnegative(key)?.takeIf { it > 0 && it < 1_000_000_000_000L }

/** Strict schema/size. Bad rows are discarded, never used as a false zero in the filters. */
internal fun parseEquityRows(root: JsonArray): List<EquityRow> {
    require(root.size in 1..8_000) { "شمار ردیف‌های تابلو معتبر نیست" }
    val seen = HashSet<String>()
    val rows = root.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val isin = obj.raw("isin")?.takeIf { it.matches(Regex("IRO[A-Z0-9]{9}")) } ?: return@mapNotNull null
        if (!seen.add(isin)) return@mapNotNull null
        val symbol = obj.raw("l18")?.trim()?.takeIf { it.length in 2..30 && it.none { ch -> Character.isISOControl(ch) } }
            ?: return@mapNotNull null
        val name = obj.raw("l30")?.trim()?.takeIf { it.length in 2..100 && it.none { ch -> Character.isISOControl(ch) } }
            ?: return@mapNotNull null
        val clock = obj.raw("time")?.takeIf { runCatching { LocalTime.parse(it) }.isSuccess }
            ?: return@mapNotNull null
        val close = obj.price("pc") ?: return@mapNotNull null
        EquityRow(symbol, name, isin, clock, obj.price("pl"), close,
            obj.number("eps"), obj.number("pe"), obj.nonnegative("tval"), obj.nonnegative("tvol"),
            obj.nonnegative("Buy_I_Volume"), obj.nonnegative("Sell_I_Volume"),
            obj.nonnegative("Buy_CountI"), obj.nonnegative("Sell_CountI"),
            obj.price("pd1"), obj.price("po1"))
    }
    require(rows.isNotEmpty()) { "هیچ سهام دارای شناسه/ساعت/قیمت معتبر در پاسخ پیدا نشد" }
    return rows
}

/** TTM = audited annual net profit + current interim YTD - previous-year matching interim YTD.
 * All profits are in million RIAL and shares in million SHARES, so the result is RIAL/share.
 * The caller must explicitly confirm matching fiscal period, accounting scope and share count.
 * A user-entered result is NEVER marked as verified Codal data or used for automatic trading.
 */
object TtmResearch {
    fun epsRial(annualProfitMillion: Double, currentYtdMillion: Double,
                priorYtdMillion: Double, sharesMillion: Double): Double? {
        if (listOf(annualProfitMillion, currentYtdMillion, priorYtdMillion, sharesMillion)
                .any { !it.isFinite() || kotlin.math.abs(it) > 1e15 } || sharesMillion <= 0) return null
        return ((annualProfitMillion + currentYtdMillion - priorYtdMillion) / sharesMillion)
            .takeIf { it.isFinite() }
    }
}
