package com.aurum.edge.data

import kotlin.math.min

import com.aurum.edge.data.CryptoCatalog.CryptoSymbol

/**
 * Finding a coin by a fragment of its name, in Persian or English.
 *
 * A plain `contains` is not good enough for a 400-row list: typing "bitcoin" matched
 * nothing because the ticker is BTC, "sol" put SOLVUSDT above SOLUSDT, and a typo
 * returned an empty screen with no hint about what was meant.
 *
 * This ranks matches by how a person actually searches — exact ticker first, then a
 * prefix, then a known name, then anything containing the text — and when nothing
 * matches it offers the closest tickers by edit distance instead of giving up.
 *
 * Pure functions, so the behaviour is unit-tested without a network or a screen.
 */
object SymbolSearch {

    /**
     * Common names mapped to their base asset. Persian spellings are included because the
     * app's users type them; the list only needs to cover the coins people name in words
     * rather than tickers.
     */
    val ALIASES: Map<String, String> = mapOf(
        "bitcoin" to "BTC", "بیتکوین" to "BTC", "بیت‌کوین" to "BTC", "بیت کوین" to "BTC",
        "ethereum" to "ETH", "اتریوم" to "ETH", "اتر" to "ETH",
        "binancecoin" to "BNB", "بایننس" to "BNB", "بی ان بی" to "BNB",
        "solana" to "SOL", "سولانا" to "SOL",
        "ripple" to "XRP", "ریپل" to "XRP",
        "cardano" to "ADA", "کاردانو" to "ADA",
        "dogecoin" to "DOGE", "دوج" to "DOGE", "دوج‌کوین" to "DOGE", "دوج کوین" to "DOGE",
        "shiba" to "SHIB", "شیبا" to "SHIB", "شیب" to "SHIB",
        "avalanche" to "AVAX", "آوالانچ" to "AVAX",
        "chainlink" to "LINK", "چین‌لینک" to "LINK", "چین لینک" to "LINK",
        "polkadot" to "DOT", "پولکادات" to "DOT",
        "tron" to "TRX", "ترون" to "TRX",
        "litecoin" to "LTC", "لایت‌کوین" to "LTC", "لایت کوین" to "LTC",
        "polygon" to "POL", "پالیگان" to "POL", "ماتیک" to "MATIC",
        "pepe" to "PEPE", "پپه" to "PEPE",
        "toncoin" to "TON", "تون" to "TON",
        "near" to "NEAR", "نیر" to "NEAR",
        "uniswap" to "UNI", "یونی‌سواپ" to "UNI",
        "aptos" to "APT", "آپتوس" to "APT",
        "arbitrum" to "ARB", "آربیتروم" to "ARB",
        "optimism" to "OP", "اپتیمیسم" to "OP",
        "injective" to "INJ", "اینجکتیو" to "INJ",
        "filecoin" to "FIL", "فایل‌کوین" to "FIL",
        "stellar" to "XLM", "استلار" to "XLM",
        "monero" to "XMR", "مونرو" to "XMR",
        "bitcoincash" to "BCH", "worldcoin" to "WLD", "ورلدکوین" to "WLD",
        "sui" to "SUI", "سویی" to "SUI",
        "sei" to "SEI", "سی" to "SEI",
        "fetch" to "FET", "فچ" to "FET",
        "render" to "RENDER", "رندر" to "RENDER",
        "cosmos" to "ATOM", "کازماس" to "ATOM", "اتم" to "ATOM",
        "algorand" to "ALGO", "الگورند" to "ALGO",
        "hedera" to "HBAR", "هدرا" to "HBAR",
        "vechain" to "VET", "وی‌چین" to "VET", "وی چین" to "VET",
        "internetcomputer" to "ICP", "اینترنت کامپیوتر" to "ICP",
        "ethereumclassic" to "ETC", "اتریوم کلاسیک" to "ETC",
        "بیت‌کوین کش" to "BCH", "بیت کوین کش" to "BCH",
        "aave" to "AAVE", "آوه" to "AAVE",
        "celestia" to "TIA", "سلستیا" to "TIA",
        "lido" to "LDO", "لیدو" to "LDO",
        "graph" to "GRT", "گراف" to "GRT",
        "sandbox" to "SAND", "سندباکس" to "SAND",
        "decentraland" to "MANA", "دیسنترالند" to "MANA",
        "axie" to "AXS", "اکسی" to "AXS",
        "thorchain" to "RUNE", "تورچین" to "RUNE",
        "ethena" to "ENA", "اتنا" to "ENA",
        "dogwifhat" to "WIF", "ویف" to "WIF",
        "shibainu" to "SHIB", "شیبا اینو" to "SHIB",
        "gold" to "XAU", "طلا" to "XAU", "انس" to "XAU",
        "euro" to "EUR", "یورو" to "EUR",
        "dollar" to "USD", "دلار" to "USD",
        "yen" to "JPY", "ین" to "JPY",
        "pound" to "GBP", "پوند" to "GBP",
    )

    /** Normalise for comparison: case, Arabic/Persian letter variants and separators. */
    fun normalize(raw: String): String =
        raw.trim().lowercase()
            .replace('ي', 'ی').replace('ك', 'ک')
            .replace('\u200c', ' ')       // zero-width non-joiner
            .replace(Regex("[\\s/_-]+"), " ")
            .trim()

    /** Base asset implied by a written name, if we know one. */
    fun aliasFor(query: String): String? {
        val key = normalize(query)
        ALIASES[key]?.let { return it }
        ALIASES[key.replace(" ", "")]?.let { return it }
        return null
    }

    /**
     * Relevance score. Higher is better; null means no match at all.
     *
     * The ordering is the point: searching "sol" must surface SOL/USDT before SOLV/USDT,
     * and "bitcoin" must find BTC even though the two share no letters in that order.
     */
    fun score(query: String, pair: CryptoSymbol): Int? {
        val needle = normalize(query).replace(" ", "")
        if (needle.isEmpty()) return 0
        val base = pair.id.substringBefore('/').lowercase()
        val ticker = pair.binance.lowercase()
        val alias = aliasFor(query)?.lowercase()

        return when {
            base == needle -> 1000
            alias != null && base == alias -> 900
            ticker == needle -> 850
            base.startsWith(needle) -> 700 - (base.length - needle.length)
            ticker.startsWith(needle) -> 600 - (ticker.length - needle.length)
            base.contains(needle) -> 400
            ticker.contains(needle) -> 300
            else -> null
        }
    }

    /**
     * Ranked matches. Ties break on liquidity, so the pair a person actually means comes
     * first instead of whichever happened to sort alphabetically.
     */
    fun rank(
        query: String,
        pairs: List<CryptoSymbol>,
        limit: Int = 300,
    ): List<CryptoSymbol> {
        if (normalize(query).isEmpty()) return pairs.take(limit)
        // The catalog is already ordered by importance, so a stable sort on relevance
        // keeps the majors ahead without needing a turnover feed.
        return pairs.asSequence()
            .mapNotNull { pair -> score(query, pair)?.let { pair to it } }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** Levenshtein distance, used only to propose alternatives for a typo. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    /**
     * "Did you mean…" candidates for a query that matched nothing.
     *
     * An empty result with no explanation is the worst outcome; offering the nearest few
     * tickers turns a dead end into one more tap.
     */
    fun suggest(
        query: String,
        pairs: List<CryptoSymbol>,
        limit: Int = 5,
    ): List<CryptoSymbol> {
        val needle = normalize(query).replace(" ", "")
        if (needle.length < 2) return emptyList()
        return pairs.asSequence()
            .map { it to distance(needle, it.id.substringBefore('/').lowercase()) }
            // Allow roughly one edit per two characters, so "bitcon" reaches BTC-length
            // tickers without returning the entire exchange for a one-letter query.
            .filter { it.second <= maxOf(1, needle.length / 2) }
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { it.id.substringBefore('/') }
            .take(limit)
            .toList()
    }
}
