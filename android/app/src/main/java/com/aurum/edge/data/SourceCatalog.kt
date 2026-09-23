package com.aurum.edge.data

/** Built-in public sources. Providers remain separate; conflicting values are never blended. */
object SourceCatalog {
    val coinGecko = SourceDef(
        id = "crypto_coingecko",
        title = "CoinGecko",
        subtitle = "قیمت و حجم واقعی رمزارز",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={symbol}&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true",
        batchTemplate = "https://api.coingecko.com/api/v3/simple/price?ids={symbols}&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true",
        pricePath = "{symbol}.usd",
        changePath = "{symbol}.usd_24h_change",
        changeMode = ChangeMode.PERCENT,
        volumePath = "{symbol}.usd_24h_vol",
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
        unit = "$",
        symbols = listOf("AAPL", "TSLA", "MSFT", "NVDA", "GOOGL", "AMZN", "BTC-USD").map { SymbolDef(it, it) },
    )

    val navasanFiat = SourceDef(
        id = "iran_navasan_fiat",
        title = "Navasan",
        subtitle = "آینهٔ عمومی نرخ ارز",
        kind = SourceKind.JSON_REST,
        urlTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/fiat.json",
        pricePath = "{symbol}.value",
        changePath = "{symbol}.change_pct",
        changeMode = ChangeMode.PERCENT,
        unit = "تومان",
        symbols = listOf("usd" to "دلار", "eur" to "یورو", "gbp" to "پوند", "aed" to "درهم", "try" to "لیر").map { SymbolDef(it.first, it.second) },
    )

    val navasanGold = navasanFiat.copy(
        id = "iran_navasan_gold",
        title = "Navasan Gold",
        urlTemplate = "https://raw.githubusercontent.com/HosseinOdd/Navasan-API/main/data/gold.json",
        symbols = listOf("18ayar" to "طلای ۱۸ عیار", "gerami" to "مثقال", "sekkeh" to "سکه", "bahar" to "بهار آزادی", "nim" to "نیم سکه", "rob" to "ربع سکه").map { SymbolDef(it.first, it.second) },
    )

    val all = listOf(coinGecko, yahoo, navasanFiat, navasanGold)
}
