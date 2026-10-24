package com.aurum.edge.data

import com.aurum.edge.core.V1Universe

/**
 * What Twelve Data can and cannot serve for THIS app's 15-symbol catalog.
 *
 * This is deliberately not a promise. The free Basic plan meters each key separately
 * (8 credits/minute, 800/day, 1 credit per /time_series symbol) and covers three markets:
 * US equities, forex and crypto. Commodities (XAU/USD, XAG/USD) and indices (Dow) are paid
 * features, and the crypto ids Twelve Data publishes are usually USD-quoted while this app's
 * crypto symbols are USDT pairs. A price quoted in USD must never be relabelled as USDT, and
 * a Dow future/ETF must never be relabelled as the index, so those entries stay "unverified"
 * until a real key proves the exact same id.
 *
 * [requestSymbol] returns non-null ONLY when the app can ask for exactly the same instrument
 * id it uses internally. Anything else is left to the other publishers of that symbol.
 */
object TwelveDataCoverage {
    enum class Status(
        /** Same id, part of the free plan's forex market. */
        val label: String,
    ) {
        FREE_SAME_ID("پلن رایگان · شناسهٔ یکسان"),
        PAID_PLAN("نیازمند پلن پرداختی (کالا/شاخص)"),
        VERIFY_WITH_KEY("فقط با کلید خودتان قابل تأیید"),
        ID_MISMATCH("شناسهٔ متفاوت؛ جایگزینی ممنوع"),
    }

    data class Entry(val symbol: String, val status: Status, val note: String)

    private val forex = V1Universe.forexSymbols.toSet()

    /** True only when the same id can be requested and the free plan covers that market. */
    fun requestSymbol(symbol: String): String? {
        val id = V1Universe.normalize(symbol)
        return id.takeIf { it in forex }
    }

    fun statusOf(symbol: String): Status {
        val id = V1Universe.normalize(symbol)
        return when {
            id in forex -> Status.FREE_SAME_ID
            id == "XAU/USD" || id == "XAG/USD" -> Status.PAID_PLAN
            id == "DOW" -> Status.ID_MISMATCH
            CryptoCatalog.isCrypto(id) -> Status.VERIFY_WITH_KEY
            else -> Status.VERIFY_WITH_KEY
        }
    }

    fun note(symbol: String): String {
        val id = V1Universe.normalize(symbol)
        return when (statusOf(id)) {
            Status.FREE_SAME_ID ->
                "فارکس در پلن Basic هست؛ همان شناسه درخواست می‌شود (۱ کردیت برای هر بازه)."
            Status.PAID_PLAN ->
                "فلز در دستهٔ کالاست و در پلن Basic نیست؛ Twelve Data فقط با پلن Grow به بالا. " +
                    "بدون آن، تاریخچه از Dukascopy/Yahoo و تیک از Gold-API/Swissquote می‌آید."
            Status.ID_MISMATCH ->
                "شاخص داوجونز در Twelve Data شناسهٔ دیگری دارد و آتی/ETF آن هم همان شاخص نیست؛ " +
                    "این اپ چیزی را به‌جای DOW جا نمی‌زند."
            Status.VERIFY_WITH_KEY ->
                "کریپتوی Twelve Data معمولاً با USD کوتی می‌شود و نماد این اپ USDT است. " +
                    "جایگزینی BTC/USD با BTC/USDT ممنوع است؛ فقط اگر کلید خودتان دقیقاً همین شناسه را تأیید کند."
        }
    }

    val entries: List<Entry> = V1Universe.allSymbols.map { Entry(it, statusOf(it), note(it)) }

    /** Symbols whose dedicated key can be used without any relabeling. */
    val sameIdSymbols: List<String> = V1Universe.allSymbols.filter { requestSymbol(it) != null }
}
