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
     * Spot gold, from the same keyless feed the live chart already trusts.
     *
     * Yahoo's XAUUSD=X row returns no usable quote for this workspace, so the watchlist
     * showed global gold as permanently empty. gold-api serves a plain price with its own
     * update timestamp and needs no key.
     */
    val goldApi = SourceDef(
        id = "gold_api_public",
        title = "Gold-API · طلای جهانی",
        subtitle = "قیمت لحظه‌ای انس طلا، بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.gold-api.com/price/{symbol}",
        pricePath = "price",
        changeMode = ChangeMode.NONE,
        timestampPath = "updatedAt",
        timestampMode = SourceTime.UTC_DATETIME,
        unit = "$",
        symbols = listOf(SymbolDef("XAU", "طلای جهانی (هر انس)"), SymbolDef("XAG", "نقره (هر انس)")),
    )

    /**
     * TradingView's public symbol scanner.
     *
     * One source for gold, FX and crypto alike, and reachable from networks that
     * geo-block the exchanges directly — which is why the previous crypto rows returned
     * nothing at all. The symbol code is the same "EXCHANGE:TICKER" the chart already
     * uses, so the watchlist and the chart can never disagree about which venue a price
     * came from.
     *
     * Quote data only; it cannot place an order.
     */
    val tradingView = SourceDef(
        id = "tradingview_scanner",
        title = "TradingView · عمومی",
        subtitle = "قیمت و تغییر روزانه برای طلا، جفت‌ارز و ارز دیجیتال، بدون کلید",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://scanner.tradingview.com/symbol?symbol={symbol}" +
            "&fields=close,change,high,low,volume&no_404=true",
        pricePath = "close",
        changePath = "change",
        changeMode = ChangeMode.PERCENT,
        volumePath = "volume",
        unit = "$",
    )

    val all = listOf(yahoo, tgju, bamaCars, goldApi, twelveData, tradingView)
    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
}
