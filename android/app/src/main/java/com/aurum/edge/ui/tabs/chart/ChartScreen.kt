package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.SignalEngine
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.data.BinanceUniverse
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val openTrade = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Interval.entries.forEach { interval ->
                FilterChip(
                    selected = market.interval == interval,
                    onClick = { viewModel.setInterval(interval) },
                    label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        SectionCard(
            title = "چارت TradingView · ${market.symbol}",
        ) {
            Box(Modifier.fillMaxWidth().height(460.dp)) {
                // TradingView may be blocked/slow in some networks. Keep the app's own verified
                // candle chart behind the WebView so the chart area is never an empty black panel.
                if (market.candles.isNotEmpty()) {
                    CandleChart(
                        candles = market.candles.takeLast(800),
                        interval = market.interval,
                        signal = market.signal,
                        modifier = Modifier.fillMaxSize(),
                        showVolume = false,
                    )
                }
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    modifier = Modifier.fillMaxSize(),
                )
                EngineOverlay(
                    market = market,
                    openTrade = openTrade,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }

        EntryScoreCard(market)

        // Trade from the chart itself: manual long/short with stop and target, paper only.
        PaperTicketSection(viewModel, market)
    }
}

@Composable
private fun EngineOverlay(market: MarketState, openTrade: PaperTrade?, modifier: Modifier = Modifier) {
    val setting = SignalEngine.ichimokuSetting(market.interval)
    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.Gold
    }
    Column(
        modifier = modifier
            .background(AurumColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("AURUM ICHIMOKU", style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        Text("${setting.tenkan}/${setting.kijun}/${setting.spanB} · امتیاز ${(signal?.confidence ?: 0.0).toInt()}/100",
            style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        Text(when (action) {
            SignalAction.BUY -> "سیگنال موتور: BUY"
            SignalAction.SELL -> "سیگنال موتور: SELL"
            SignalAction.NO_TRADE -> "سیگنال موتور: NO TRADE"
        }, style = MaterialTheme.typography.labelSmall, color = color)
        if (signal?.isActionable == true) {
            Text("E ${formatPrice(signal.entry)} · SL ${formatPrice(signal.stopLoss)} · TP ${formatPrice(signal.takeProfit)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
        }
        openTrade?.let { trade ->
            Text("Paper باز: ${trade.action} · ${formatDateTime(trade.openedAt)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
            Text("E ${formatPrice(trade.entry)} · SL ${formatPrice(trade.stopLoss)} · TP ${formatPrice(trade.takeProfit)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary)
        }
    }
}

@Composable
private fun EntryScoreCard(market: MarketState) {
    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.TextSecondary
    }
    val score = signal?.confidence ?: 0.0
    SectionCard(
        title = "امتیاز ورود به معامله",
        trailing = { Pill("${score.toInt()}/100", color) },
    ) {
        Text(
            when (action) {
                SignalAction.BUY -> "امتیاز خرید"
                SignalAction.SELL -> "امتیاز فروش"
                SignalAction.NO_TRADE -> "فعلاً ورود مجاز نیست"
            },
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        LinearProgressIndicator(
            progress = { (score / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            trackColor = AurumColors.SurfaceAlt,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        if (signal?.isActionable == true) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ورود", formatPrice(signal.entry), AurumColors.Gold, Modifier.weight(1f))
                StatTile("SL", formatPrice(signal.stopLoss), AurumColors.Red, Modifier.weight(1f))
                StatTile("TP", formatPrice(signal.takeProfit), AurumColors.Green, Modifier.weight(1f))
                StatTile("کندل", formatTime(signal.barTime), AurumColors.TextSecondary, Modifier.weight(1f))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(symbol: String, interval: Interval, modifier: Modifier = Modifier) {
    val html = tradingViewHtml(symbol, interval)
    val loadKey = "$symbol|${interval.label}"
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.userAgentString = settings.userAgentString + " AurumEdgeTradingView/1"
                tag = loadKey
                loadDataWithBaseURL("https://www.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // AndroidView.update runs on every Compose recomposition. Market prices can recompose
            // every second; reloading here kept TradingView in a permanent loading/no-data state.
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://www.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
    )
}

private fun tradingViewHtml(symbol: String, interval: Interval): String {
    // Crypto previously fell through to the gold default, so picking BTC drew XAU on the
    // TradingView pane while the app's own chart drew BTC — two different instruments on
    // top of each other. Crypto resolves to its real Binance ticker.
    val crypto = CryptoCatalog.find(symbol)
    val tvSymbol = when {
        crypto != null -> "BINANCE:${crypto.binance}"
        symbol == "XAU/USD" -> "OANDA:XAUUSD"
        symbol == "EUR/USD" -> "OANDA:EURUSD"
        symbol == "GBP/USD" -> "OANDA:GBPUSD"
        symbol == "AUD/USD" -> "OANDA:AUDUSD"
        symbol == "NZD/USD" -> "OANDA:NZDUSD"
        symbol == "USD/JPY" -> "OANDA:USDJPY"
        symbol == "USD/CHF" -> "OANDA:USDCHF"
        symbol == "USD/CAD" -> "OANDA:USDCAD"
        else -> "OANDA:XAUUSD"
    }
    val tvInterval = when (interval) {
        Interval.M1 -> "1"
        Interval.M5 -> "5"
        Interval.M15 -> "15"
        Interval.M30 -> "30"
        Interval.H1 -> "60"
        Interval.H4 -> "240"
        Interval.D1 -> "D"
    }
    val encodedSymbol = tvSymbol.replace(":", "%3A")
    val widgetUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart" +
        "&symbol=$encodedSymbol&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13&studies=IchimokuCloud%40tv-basicstudies" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1" />
          <style>
            html, body, #tradingview_chart, iframe {
              margin:0; padding:0; width:100%; height:100%; overflow:hidden;
              background:transparent; border:0;
            }
          </style>
        </head>
        <body>
          <div id="tradingview_chart">
            <iframe title="TradingView" src="$widgetUrl" allowtransparency="true" scrolling="no"></iframe>
          </div>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Symbol browser for the whole Binance spot universe plus the forex workspace.
 *
 * Several hundred pairs cannot live in a chip row, and they arrive asynchronously, so this
 * observes [BinanceUniverse.pairs] as state. Reading a plain field here was the bug that
 * made the app look like it only supported a handful of coins: the list loaded a second
 * after launch and Compose was never told.
 */
@Composable
internal fun SymbolSearchRow(selected: String, onSelect: (String) -> Unit) {
    val universe by BinanceUniverse.pairs.collectAsStateWithLifecycle()
    val loadError by BinanceUniverse.error.collectAsStateWithLifecycle()
    val loading by BinanceUniverse.loading.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var quote by rememberSaveable { mutableStateOf("USDT") }
    var open by rememberSaveable { mutableStateOf(false) }

    val pool = remember(universe, quote) {
        if (quote == "همه") universe else universe.filter { it.quote == quote }
    }
    val shown = remember(pool, query) { SymbolSearch.rank(query, pool, limit = 300) }
    // Only propose alternatives once the query has genuinely found nothing.
    val suggestions = remember(pool, query, shown.size) {
        if (shown.isNotEmpty() || query.isBlank()) emptyList()
        else SymbolSearch.suggest(query, if (pool.isEmpty()) universe else pool)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "نماد: $selected",
                style = MaterialTheme.typography.titleSmall,
                color = AurumColors.Gold,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    loading && universe.isEmpty() -> "در حال دریافت فهرست…"
                    universe.isEmpty() -> "فهرست خالی"
                    else -> "${universe.size} نماد"
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Text(
                if (open) "  بستن" else "  تغییر نماد",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                modifier = Modifier.clickable { open = !open }.padding(6.dp),
            )
        }

        if (!open) return@Column

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("جستجو — BTC، پپه، bitcoin، SOL…", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (BinanceUniverse.QUOTES + "همه").forEach { option ->
                FilterChip(
                    selected = quote == option,
                    onClick = { quote = option },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        loadError?.let { message ->
            Text(
                "$message — فهرست کریپتو در دسترس نیست؛ جفت‌ارزها مثل همیشه کار می‌کنند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp)) {
            items(shown, key = { it.id }) { pair ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(pair.id); open = false; query = "" }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pair.id,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pair.id == selected) AurumColors.Gold else AurumColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pair.quote,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                    )
                }
            }
            if (shown.isEmpty() && universe.isNotEmpty()) {
                item {
                    Text(
                        if (suggestions.isEmpty()) "نمادی با «$query» در $quote پیدا نشد."
                        else "«$query» پیدا نشد. منظورتان این بود؟",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(suggestions, key = { "s-" + it.id }) { pair ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelect(pair.id); open = false; query = "" }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pair.id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.Cyan,
                            modifier = Modifier.weight(1f),
                        )
                        Text("پیشنهاد", style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SymbolPickerRow(selected: String, onSelect: (String) -> Unit) {
    val quick = remember(selected) {
        (WatchCatalog.chartSymbols + selected).distinct()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quick.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { if (selected != id) onSelect(id) },
                label = { Text(id, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                    selectedLabelColor = AurumColors.Gold,
                    labelColor = AurumColors.TextSecondary,
                ),
            )
        }
    }
}
