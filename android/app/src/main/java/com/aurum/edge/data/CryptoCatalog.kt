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
        CryptoSymbol("TON/USDT", "TONUSDT", "تون‌کوین / تتر", 3),
        CryptoSymbol("DOT/USDT", "DOTUSDT", "پولکادات / تتر", 3),
        CryptoSymbol("LTC/USDT", "LTCUSDT", "لایت‌کوین / تتر", 2),
        CryptoSymbol("BCH/USDT", "BCHUSDT", "بیت‌کوین کش / تتر", 2),
        CryptoSymbol("SHIB/USDT", "SHIBUSDT", "شیبا اینو / تتر", 8),
        CryptoSymbol("UNI/USDT", "UNIUSDT", "یونی‌سواپ / تتر", 3),
        CryptoSymbol("ATOM/USDT", "ATOMUSDT", "کازماس / تتر", 3),
        CryptoSymbol("XLM/USDT", "XLMUSDT", "استلار / تتر", 5),
        CryptoSymbol("NEAR/USDT", "NEARUSDT", "نیر / تتر", 3),
        CryptoSymbol("APT/USDT", "APTUSDT", "آپتوس / تتر", 3),
        CryptoSymbol("ARB/USDT", "ARBUSDT", "آربیتروم / تتر", 4),
        CryptoSymbol("OP/USDT", "OPUSDT", "اپتیمیسم / تتر", 4),
        CryptoSymbol("FIL/USDT", "FILUSDT", "فایل‌کوین / تتر", 3),
        CryptoSymbol("ICP/USDT", "ICPUSDT", "اینترنت کامپیوتر / تتر", 3),
        CryptoSymbol("ETC/USDT", "ETCUSDT", "اتریوم کلاسیک / تتر", 2),
        CryptoSymbol("HBAR/USDT", "HBARUSDT", "هدرا / تتر", 5),
        CryptoSymbol("VET/USDT", "VETUSDT", "وی‌چین / تتر", 5),
        CryptoSymbol("INJ/USDT", "INJUSDT", "اینجکتیو / تتر", 3),
        CryptoSymbol("SUI/USDT", "SUIUSDT", "سویی / تتر", 4),
        CryptoSymbol("SEI/USDT", "SEIUSDT", "سی / تتر", 4),
        CryptoSymbol("AAVE/USDT", "AAVEUSDT", "آوه / تتر", 2),
        CryptoSymbol("ALGO/USDT", "ALGOUSDT", "الگورند / تتر", 4),
        CryptoSymbol("PEPE/USDT", "PEPEUSDT", "پپه / تتر", 8),
        CryptoSymbol("WIF/USDT", "WIFUSDT", "داگ‌ویف‌هت / تتر", 4),
        CryptoSymbol("FET/USDT", "FETUSDT", "فچ / تتر", 4),
        CryptoSymbol("RENDER/USDT", "RENDERUSDT", "رندر / تتر", 3),
        CryptoSymbol("TIA/USDT", "TIAUSDT", "سلستیا / تتر", 4),
        CryptoSymbol("LDO/USDT", "LDOUSDT", "لیدو / تتر", 4),
        CryptoSymbol("GRT/USDT", "GRTUSDT", "گراف / تتر", 5),
        CryptoSymbol("SAND/USDT", "SANDUSDT", "سندباکس / تتر", 4),
        CryptoSymbol("MANA/USDT", "MANAUSDT", "دیسنترالند / تتر", 4),
        CryptoSymbol("AXS/USDT", "AXSUSDT", "اکسی اینفینیتی / تتر", 3),
        CryptoSymbol("POL/USDT", "POLUSDT", "پالیگان / تتر", 4),
        CryptoSymbol("RUNE/USDT", "RUNEUSDT", "تورچین / تتر", 4),
        CryptoSymbol("ENA/USDT", "ENAUSDT", "اتنا / تتر", 4),
    )

    /**
     * The handful shown in the watchlist. Everything else stays reachable through the
     * chart's search; a 45-row watchlist is noise, not coverage, and polls the provider
     * for instruments nobody is looking at.
     */
    val watchlistSeed: List<CryptoSymbol> =
        listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT", "BNB/USDT")
            .mapNotNull { id -> symbols.firstOrNull { it.id == id } }

    private val byId = symbols.associateBy { it.id }

    /**
     * The curated list above is only the offline seed, so the picker is never empty on a
     * first launch with no network. Once [BinanceUniverse] has loaded exchangeInfo, the
     * full set of actively trading pairs is authoritative.
     */
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
