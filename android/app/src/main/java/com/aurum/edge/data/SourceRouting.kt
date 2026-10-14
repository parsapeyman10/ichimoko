package com.aurum.edge.data

/** Exact history routes, not a promise that every provider is available. Yahoo's two
 * hosts are mirrors of ONE publisher, not a second independent market confirmation.
 * No futures/ETF/USD proxy is substituted for an oil, silver or USDT instrument.
 */
internal object SourceRouting {
    enum class HistorySource { YAHOO, DUKASCOPY, TWELVE_DATA }
    private val yahoo = PublicCandleHistoryClient()
    fun historySources(symbol: String, keyed: Boolean): List<HistorySource> {
        if (CryptoCatalog.isCrypto(symbol)) return emptyList() // separate spot-venue bundle
        return buildList {
            if (yahoo.yahooSymbol(symbol) != null) add(HistorySource.YAHOO)
            if (DukascopyHistoryClient.instrument(symbol) != null) add(HistorySource.DUKASCOPY)
            if (keyed) add(HistorySource.TWELVE_DATA)
        }
    }

    fun fromProvider(provider: String?): HistorySource? = when {
        provider?.startsWith("Yahoo Finance") == true -> HistorySource.YAHOO
        provider?.startsWith("Dukascopy") == true -> HistorySource.DUKASCOPY
        provider?.startsWith("Twelve Data") == true -> HistorySource.TWELVE_DATA
        else -> null
    }

    fun historyPlan(symbol: String, keyed: Boolean): String {
        if (CryptoCatalog.isCrypto(symbol))
            return "مقایسهٔ Gate / OKX / Kraken؛ انتخاب بستهٔ سالم همخوان (فقط رادار، نه قیمت اجرای صرافی دیگر)"
        val paths = historySources(symbol, keyed).map { when (it) {
            HistorySource.YAHOO -> "Yahoo (دو میزبان، یک ناشر)"
            HistorySource.DUKASCOPY -> "Dukascopy"
            HistorySource.TWELVE_DATA -> "Twelve Data (کلیددار)"
        } }
        return if (paths.isEmpty()) "منبع تاریخچهٔ دقیق M5/M15/H1 تعریف نشده؛ بررسی فقط خطا نشان می‌دهد"
            else "مقایسهٔ " + paths.joinToString(" / ") + if (paths.size == 1) " · جایگزین مستقل ندارد" else ""
    }
}
