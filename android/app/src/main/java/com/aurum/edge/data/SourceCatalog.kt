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
     * همراه مکانیک — daily used-car prices.
     *
     * The per-model pages are server-rendered (the brand and model/year routes carry the
     * price in the HTML), so the same Jsoup extractor used for Bama reads them. The
     * client-rendered routes are deliberately avoided: `/carprice/{brand}/{model}/` and
     * `/carprice/{brand}/{model}/{year}/` ship the number, the bare `/carprice/` tool does
     * not.
     *
     * Scraping reads a layout, not a contract. If the page changes the row reports
     * "unavailable" instead of showing a stale or invented price.
     */
    val hamrahMechanic = SourceDef(
        id = "hamrah_mechanic_car",
        title = "همراه مکانیک · قیمت خودرو",
        subtitle = "قیمت روز خودروی کارکرده بر پایهٔ معاملات واقعی، بدون کلید",
        kind = SourceKind.HTML_PAGE,
        urlTemplate = "https://www.hamrah-mechanic.com/carprice/{symbol}/",
        unit = "تومان",
        symbols = listOf(
            // Every slug below was verified against the live brand listings. Guessed slugs
            // would render as permanently failing rows, which is worse than omitting them.
            SymbolDef("saipa/quick", "سایپا کوییک"),
            SymbolDef("saipa/saina", "سایپا ساینا"),
            SymbolDef("saipa/tiba2", "سایپا تیبا ۲"),
            SymbolDef("saipa/atlas", "سایپا اطلس"),
            SymbolDef("saipa/pride131", "پراید ۱۳۱"),
            SymbolDef("saipa/pride111", "پراید ۱۱۱"),
            SymbolDef("saipa/pride151", "پراید ۱۵۱"),
            SymbolDef("irankhodro/peugeot207", "پژو ۲۰۷"),
            SymbolDef("irankhodro/peugeot206", "پژو ۲۰۶"),
            SymbolDef("irankhodro/peugeot206sedan", "پژو ۲۰۶ صندوقدار"),
            SymbolDef("irankhodro/peugeotpars", "پژو پارس"),
            SymbolDef("irankhodro/peugeot405", "پژو ۴۰۵"),
            SymbolDef("irankhodro/405slx", "پژو ۴۰۵ SLX"),
            SymbolDef("irankhodro/207sd", "پژو ۲۰۷ صندوقدار"),
        ),
    )

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

    val all = listOf(yahoo, tgju, bamaCars, hamrahMechanic, twelveData, binance)
    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
}
