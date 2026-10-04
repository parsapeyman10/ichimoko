package com.aurum.edge.data

/**
 * Crypto instruments traded entirely ON THE PHONE.
 *
 * Binance's public market endpoints need no key and no account, so the app can chart,
 * signal and paper-trade crypto with nothing else running. There is no backend in this
 * path — the same way the forex side already works.
 *
 * Price scale is the thing that breaks naive ports: BTC ticks in cents around $80,000
 * while DOGE ticks in millionths. Anything that formats or rounds a crypto price has to
 * ask this catalog instead of assuming two decimals.
 */
object CryptoCatalog {

    data class CryptoSymbol(
        /** App-facing id, e.g. "BTC/USDT". */
        val id: String,
        /** Binance ticker, e.g. "BTCUSDT". */
        val binance: String,
        val label: String,
        /** Decimals used to display a price. Derived from the instrument's real tick size. */
        val digits: Int,
    )

    val symbols = listOf(
        CryptoSymbol("BTC/USDT", "BTCUSDT", "بیت‌کوین / تتر", 2),
        CryptoSymbol("ETH/USDT", "ETHUSDT", "اتریوم / تتر", 2),
        CryptoSymbol("BNB/USDT", "BNBUSDT", "بی‌ان‌بی / تتر", 2),
        CryptoSymbol("SOL/USDT", "SOLUSDT", "سولانا / تتر", 2),
        CryptoSymbol("XRP/USDT", "XRPUSDT", "ریپل / تتر", 4),
        CryptoSymbol("ADA/USDT", "ADAUSDT", "کاردانو / تتر", 4),
        CryptoSymbol("DOGE/USDT", "DOGEUSDT", "دوج‌کوین / تتر", 5),
        CryptoSymbol("AVAX/USDT", "AVAXUSDT", "آوالانچ / تتر", 3),
        CryptoSymbol("LINK/USDT", "LINKUSDT", "چین‌لینک / تتر", 3),
        CryptoSymbol("TRX/USDT", "TRXUSDT", "ترون / تتر", 5),
    )

    private val byId = symbols.associateBy { it.id }

    fun find(id: String): CryptoSymbol? = byId[id.trim().uppercase()]

    fun isCrypto(id: String): Boolean = find(id) != null

    val ids: List<String> = symbols.map { it.id }

    /**
     * Display decimals for any symbol, crypto or not.
     *
     * Forex keeps the existing behaviour (5 digits, 3 for JPY pairs); crypto uses its own
     * tick scale. Printing BTC to 5 decimals or DOGE to 2 are equally useless.
     */
    fun digitsFor(id: String): Int {
        find(id)?.let { return it.digits }
        val quote = id.substringAfter('/', "").uppercase()
        return when {
            id.startsWith("XAU") || id.startsWith("XPT") || id.startsWith("XPD") -> 2
            id.startsWith("XAG") -> 3
            quote == "JPY" -> 3
            quote.isNotEmpty() -> 5
            else -> 2
        }
    }

    /**
     * Crypto never closes, so the weekend gate must not apply to it. Treating a 24/7 venue
     * as shut would silence the engine for two days a week for no reason.
     */
    fun tradesAroundTheClock(id: String): Boolean = isCrypto(id)
}
