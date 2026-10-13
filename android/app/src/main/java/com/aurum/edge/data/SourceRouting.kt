package com.aurum.edge.data

/** Exact history routes, not a promise that every provider is available. Yahoo's two
 * hosts are mirrors of ONE publisher, not a second independent market confirmation.
 * No futures/ETF/USD proxy is substituted for an oil, silver or USDT instrument.
 */
internal object SourceRouting {
    private val yahoo = PublicCandleHistoryClient()
    fun historyPlan(symbol: String, keyed: Boolean): String {
        if (CryptoCatalog.isCrypto(symbol))
            return "Gate Spot ← OKX Spot ← Kraken Spot (فقط رادار؛ ورود صرافی دیگر ممنوع)"
        val paths = mutableListOf<String>()
        if (yahoo.yahooSymbol(symbol) != null)
            paths += "Yahoo (دو میزبان، یک ناشر)"
        if (DukascopyHistoryClient.instrument(symbol) != null)
            paths += "Dukascopy"
        if (keyed) paths += "Twelve Data (کلیددار)"
        return if (paths.isEmpty()) "منبع تاریخچهٔ دقیق M5/M15/H1 تعریف نشده؛ بررسی فقط خطا نشان می‌دهد"
            else paths.joinToString(" ← ") + if (paths.size == 1) " · جایگزین مستقل ندارد" else ""
    }
}
