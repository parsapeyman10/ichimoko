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

    // Only vetted built-ins can be selected from the UI.
    val all = listOf(yahoo, twelveData)
    fun find(id: String): SourceDef? = all.firstOrNull { it.id == id }
}
