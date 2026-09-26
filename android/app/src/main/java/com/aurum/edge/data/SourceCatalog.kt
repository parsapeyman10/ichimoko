package com.aurum.edge.data

/** Built-in public sources. Providers remain separate; conflicting values are never blended. */
object SourceCatalog {
    val coinGecko = SourceDef(
        id = "crypto_coingecko",
        title = "CoinGecko",
        subtitle = "قیمت و حجم واقعی رمزارز",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={symbol}&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true&include_last_updated_at=true",
        batchTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={symbols}&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true&include_last_updated_at=true",
        pricePath = "{symbol}.usd",
        changePath = "{symbol}.usd_24h_change",
        changeMode = ChangeMode.PERCENT,
        volumePath = "{symbol}.usd_24h_vol",
        timestampPath = "{symbol}.last_updated_at",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "$",
        symbols = listOf(
            SymbolDef("bitcoin", "بیت‌کوین"), SymbolDef("ethereum", "اتریوم"),
            SymbolDef("solana", "سولانا"), SymbolDef("ripple", "ریپل"),
            SymbolDef("dogecoin", "دوج‌کوین"), SymbolDef("binancecoin", "بایننس‌کوین"),
        ),
    )

    val yahoo = SourceDef(
        id = "stocks_yahoo",
        title = "Yahoo Finance",
        subtitle = "قیمت واقعی سهام و رمزارز",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=5m&range=1d&includePrePost=false",
        pricePath = "chart.result[0].meta.regularMarketPrice",
        changePath = "chart.result[0].meta.chartPreviousClose",
        changeMode = ChangeMode.PREV_CLOSE,
        sparkPath = "chart.result[0].indicators.quote[0].close",
        volumePath = "chart.result[0].indicators.quote[0].volume",
        timestampPath = "chart.result[0].meta.regularMarketTime",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "$",
        symbols = listOf("AAPL", "TSLA", "MSFT", "NVDA", "GOOGL", "AMZN", "BTC-USD").map { SymbolDef(it, it) },
    )

    val navasanFiat = SourceDef(
        id = "iran_navasan_fiat",
        title = "Navasan",
        subtitle = "آینهٔ عمومی نرخ ارز",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/fiat.json",
        batchTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/fiat.json",
        pricePath = "{symbol}.value",
        changePath = "{symbol}.change_pct",
        changeMode = ChangeMode.PERCENT,
        timestampPath = "{symbol}.date",
        timestampMode = SourceTime.UNIX_SECONDS,
        unit = "تومان",
        symbols = listOf("usd" to "دلار", "eur" to "یورو", "gbp" to "پوند", "aed" to "درهم", "try" to "لیر").map { SymbolDef(it.first, it.second) },
    )

    val navasanGold = navasanFiat.copy(
        id = "iran_navasan_gold",
        title = "Navasan Gold",
        subtitle = "آینهٔ عمومی نرخ طلای ایران (زمان Unix منتشرشده)",
        urlTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/gold.json",
        batchTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/gold.json",
        symbols = listOf("18ayar" to "طلای ۱۸ عیار", "gerami" to "سکه گرمی", "sekkeh" to "سکه", "bahar" to "بهار آزادی", "nim" to "نیم سکه", "rob" to "ربع سکه").map { SymbolDef(it.first, it.second) },
    )

    /** Public HTML market rows, read-only. TGJU lists *rials*; normalize to toman.
     * The row's time is only a clock (no date), so a scrape is NOT evidence of a fresh trade.
     * One page per refresh, never a login, anti-bot bypass or arbitrary selector/URL.
     */
    val tgju = SourceDef(
        id = "iran_tgju_web",
        title = "TGJU · وب",
        subtitle = "تابلوی عمومی طلا/سکه/دلار؛ زمان معامله فاقد تاریخ کامل",
        kind = SourceKind.HTML_CSS,
        urlTemplate = "https://www.tgju.org/",
        batchTemplate = "https://www.tgju.org/",
        cssSelector = "tr[data-market-nameslug=\"{symbol}\"]",
        cssAttr = "data-price",
        scale = 0.1,
        unit = "تومان",
    )

    /** Read-only quote endpoint. The user's API key is inserted only into this HTTPS request. */
    val twelveData = SourceDef(
        id = "twelve_data_quote",
        title = "Twelve Data",
        subtitle = "آخرین قیمت و زمان به‌روزرسانی ارائه‌دهنده",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.twelvedata.com/quote?symbol={symbol}&timezone=UTC&apikey={api_key}",
        pricePath = "close|price",
        changePath = "percent_change",
        changeMode = ChangeMode.PERCENT,
        timestampPath = "datetime",
        timestampMode = SourceTime.UTC_DATETIME,
        unit = "$",
        requiresKey = true,
    )

    // Only vetted built-ins can be selected from the UI. TSETMC is intentionally not listed:
    // the old TSE_TSETMC branch has no verified instrument/endpoint contract yet.
    val all = listOf(coinGecko, yahoo, navasanFiat, navasanGold, tgju, twelveData)
    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
}
