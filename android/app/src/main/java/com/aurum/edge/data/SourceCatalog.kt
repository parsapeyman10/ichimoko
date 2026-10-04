package com.aurum.edge.data

/** Built-in public sources. Providers remain separate; conflicting values are never blended. */
object SourceCatalog {
    val yahoo = SourceDef(
        id = "stocks_yahoo",
        title = "Yahoo Finance",
        subtitle = "قیمت/جرقهٔ عمومی فارکس و طلا بدون کلید",
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
        symbols = listOf("XAUUSD=X", "EURUSD=X", "GBPUSD=X", "AUDUSD=X", "NZDUSD=X", "JPY=X", "CHF=X", "CAD=X").map { SymbolDef(it, it) },
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

    val bamaCars = SourceDef(
        id = "bama_car_price",
        title = "Bama · قیمت خودرو",
        subtitle = "قیمت بازار خودرو بدون کلید",
        kind = SourceKind.HTML_PAGE,
        urlTemplate = "https://bama.ir/price/{symbol}",
        unit = "تومان",
        symbols = listOf(
            SymbolDef("quick_gxr_lmt", "کوییک GXRL / GXR-L"),
            SymbolDef("peugeot_207_manualtu5p", "پژو ۲۰۷ دستی TU5P / TU5Plus"),
        ),
    )

    /**
     * Public TGJU board used only for the watchlist's Iran rows. TGJU publishes Rial strings;
     * scale=0.1 converts them to Toman for the UI. This is not used by the chart/signal engine.
     */
    val tgju = SourceDef(
        id = "tgju_public",
        title = "TGJU عمومی",
        subtitle = "دلار آزاد و طلای ایران بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://call1.tgju.org/ajax.json",
        batchTemplate = "https://call1.tgju.org/ajax.json",
        pricePath = "current.{symbol}.p",
        changePath = "current.{symbol}.dp",
        changeMode = ChangeMode.PERCENT,
        timestampPath = "current.{symbol}.ts",
        timestampMode = SourceTime.NONE,
        scale = 0.1,
        unit = "تومان",
        symbols = listOf(
            SymbolDef("geram18", "طلای ۱۸ عیار ایران"),
            SymbolDef("price_dollar_rl", "دلار آزاد ایران"),
        ),
    )

    // Only vetted built-ins can be selected from the UI.
    /**
     * Binance public 24h ticker, so crypto rows in the watchlist have a real source.
     *
     * Without this the crypto pairs added to [WatchCatalog] would render as permanently
     * failing rows: the catalog named a provider that did not exist. Market data only —
     * no key, no account, and nothing that can place an order.
     */
    val binance = SourceDef(
        id = "binance_public",
        title = "Binance · عمومی",
        subtitle = "قیمت و تغییر ۲۴ ساعتهٔ ارز دیجیتال، بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://data-api.binance.vision/api/v3/ticker/24hr?symbol={symbol}",
        pricePath = "lastPrice",
        changePath = "priceChangePercent",
        changeMode = ChangeMode.PERCENT,
        volumePath = "volume",
        timestampPath = "closeTime",
        timestampMode = SourceTime.UNIX_MILLIS,
        unit = "$",
    )

    val all = listOf(yahoo, tgju, bamaCars, twelveData, binance)
    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
}
