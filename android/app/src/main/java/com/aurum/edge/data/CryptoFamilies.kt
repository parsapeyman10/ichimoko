package com.aurum.edge.data

/** Display grouping only. Family labels do not attest to exchange listing or feed availability. */
object CryptoFamilies {
    fun label(symbol: String): String = when (symbol.substringBefore('/').uppercase()) {
        "BTC", "LTC", "BCH" -> "پرداخت / ذخیرهٔ ارزش"
        "ETH", "BNB", "SOL", "ADA", "AVAX", "DOT", "TON", "SUI", "NEAR",
        "TRX", "ATOM", "APT", "SEI", "ICP", "ALGO", "TIA", "INJ" -> "زیرساخت و لایهٔ یک"
        "ARB", "OP", "POL" -> "مقیاس‌پذیری / لایهٔ دو"
        "UNI", "AAVE", "LDO", "RUNE", "ENA" -> "دیفای"
        "DOGE", "SHIB", "PEPE", "WIF" -> "میم‌کوین"
        "FET", "RENDER", "GRT" -> "داده / محاسبات"
        "SAND", "MANA", "AXS" -> "بازی / متاورس"
        "FIL" -> "ذخیره‌سازی"
        else -> "سایر رمزارزها"
    }
}
