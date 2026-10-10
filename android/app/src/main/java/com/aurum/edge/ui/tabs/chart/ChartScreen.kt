package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPriceFor
import com.aurum.edge.ui.theme.AurumColors

/**
 * تب «چارت» — **فقط TradingView**.
 *
 * قانونِ صریح این صفحه:
 *  ۱. چارت و کندل‌ها همیشه از ویدجت رسمی TradingView می‌آیند، با همان مشخصاتِ خودِ TradingView
 *     (کندل‌ها، تایم‌فریم، مطالعهٔ ایچیموکو، تم و ابزارهای خودش). هیچ چارتِ داخلیِ اپ، هیچ
 *     لایه/خطِ دست‌ساز و هیچ HUD روی چارت کشیده نمی‌شود.
 *  ۲. به‌محض ثبت یک معامله، **پنجرهٔ همان معامله** در همین صفحه باز می‌شود و چارت TradingViewِ
 *     **همان نماد** را نشان می‌دهد. چهار معاملهٔ باز ⇒ چهار پنجره، هر چهار تا هم‌زمان و همیشه باز.
 *  ۳. هیچ سوئیچ، حالت جایگزین، دکمهٔ «تلاش دوباره/چارت داخلی» یا تایمرِ نگهبانی روی این تب نیست:
 *     فقط TradingView اجرا می‌شود. عددهای واقعی معامله در نوارِ بالای همان پنجره از ژورنال دستگاه
 *     خوانده می‌شوند، نه روی کندل‌ها.
 *  ۴. نمادی که در نگاشت رسمی TradingViewِ اپ نیست، با پیامِ صریح «نگاشت نشده» نشان داده می‌شود و
 *     هرگز نمادِ دیگری (مثلاً طلا) جایش نمایش داده نمی‌شود.
 */
@Composable
fun ChartScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onOpenSettings: () -> Unit,
    onOpenJournal: () -> Unit,
    onOpenSignal: () -> Unit,
) {
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val livePrices by viewModel.livePrices.collectAsStateWithLifecycle()
    val openTrades = trades.filter { it.isOpen }.sortedByDescending { it.openedAt }

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

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSignal) { Text("سیگنال ${market.symbol}") }
            OutlinedButton(onClick = onOpenJournal) { Text("ژورنال") }
            OutlinedButton(onClick = onOpenSettings) { Text("تنظیمات فید") }
        }

        SectionCard(
            title = "چارت TradingView · ${market.symbol}",
            subtitle = "ویدجت رسمی TradingView با همان مشخصات خودش — کندل‌ها، تایم‌فریم و ایچیموکو " +
                "مستقیم از خود TradingView؛ هیچ خط یا لایهٔ دست‌سازی روی چارت کشیده نمی‌شود",
            trailing = {
                Pill(
                    if (openTrades.isEmpty()) "معاملهٔ باز ندارد" else "${openTrades.size} پنجرهٔ معاملهٔ باز",
                    if (openTrades.isEmpty()) AurumColors.TextMuted else AurumColors.Cyan,
                )
            },
        ) {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                TradingViewWidget(
                    symbol = market.symbol,
                    interval = market.interval,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text("نکته: ایچیموکوِ ویدجت TradingView مستقل از موتور V1 است؛ برای مقایسهٔ بصری " +
                "پارامترهای آن را در خود ویدجت روی ۸/۲۴/۷۲ و جابه‌جایی ۲۴ بگذارید. " +
                "سیگنال از کندل‌های تأییدشدهٔ فید اپ محاسبه می‌شود، نه از خطوط TradingView.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

        OpenTradeWindows(openTrades = openTrades, livePrices = livePrices)
    }
}

/**
 * پنجرهٔ معاملات باز: به ازای **هر** معاملهٔ باز یک پنجره، و داخل هر پنجره چارت TradingViewِ
 * همان نماد و همان تایم‌فریمِ خودِ معامله. هیچ‌کدام تاشو نیستند؛ اگر چهار معامله باز باشد،
 * چهار چارت TradingView هم‌زمان روی همین صفحه اجرا می‌شود.
 */
@Composable
private fun OpenTradeWindows(openTrades: List<PaperTrade>, livePrices: Map<String, Double>) {
    if (openTrades.isEmpty()) {
        SectionCard(
            title = "پنجرهٔ معاملات باز",
            subtitle = "به‌محض ثبت ورود، پنجرهٔ همان معامله با چارت TradingView همین‌جا باز می‌شود",
        ) {
            Text("الان معاملهٔ بازی در ژورنال نیست. به‌محض اینکه ورودی ثبت شود (خودکار یا دستی)، " +
                    "پنجرهٔ مخصوص همان معامله با چارت TradingViewِ همان نماد در همین صفحه باز می‌شود؛ " +
                    "چند معاملهٔ باز یعنی چند پنجره، همه هم‌زمان.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
        return
    }
    openTrades.forEach { trade ->
        TradeWindowCard(trade = trade, livePrice = livePrices[trade.symbol])
    }
}

@Composable
private fun TradeWindowCard(trade: PaperTrade, livePrice: Double?) {
    val isBuy = trade.action == SignalAction.BUY
    val pnlUsd = livePrice?.let { price ->
        val perUnit = if (isBuy) price - trade.entry else trade.entry - price
        kotlin.math.round(
            PaperOrderRules.quotePnlToUsd(trade.symbol, perUnit * trade.positionOz, price) * 100.0
        ) / 100.0
    }
    val ageMinutes = ((System.currentTimeMillis() - trade.openedAt) / 60_000L).coerceAtLeast(0L)

    SectionCard(
        title = "پنجرهٔ معاملهٔ ${trade.symbol} · ${if (isBuy) "خرید LONG" else "فروش SHORT"}",
        subtitle = "چارت و کندل‌ها فقط از ویدجت رسمی TradingView با همان مشخصات خودش؛ " +
            "عددهای معامله از ژورنال واقعی دستگاه",
        trailing = {
            Pill(if (isBuy) "LONG" else "SHORT", if (isBuy) AurumColors.Green else AurumColors.Red)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("ورود", formatPriceFor(trade.symbol, trade.entry), AurumColors.Gold, Modifier.weight(1f))
            StatTile("حد ضرر", formatPriceFor(trade.symbol, trade.stopLoss), AurumColors.Red, Modifier.weight(1f))
            StatTile("حد سود", formatPriceFor(trade.symbol, trade.takeProfit), AurumColors.Green, Modifier.weight(1f))
            StatTile("R:R", String.format(java.util.Locale.US, "%.2f", trade.riskReward),
                AurumColors.Cyan, Modifier.weight(1f))
        }
        Text(
            "حجم ${String.format(java.util.Locale.US, "%.6f", trade.positionOz)} ${trade.unit} · " +
                "باز شده ${formatDateTime(trade.openedAt)} " +
                "(${if (ageMinutes < 60) "$ageMinutes دقیقه" else "${ageMinutes / 60} ساعت و ${ageMinutes % 60} دقیقه"} پیش) · " +
                if (trade.autoOpened) "ورود خودکار کاغذی" else "ورود دستی کاغذی",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (livePrice != null && livePrice > 0.0) {
            Text(
                "آخرین قیمت ${formatPriceFor(trade.symbol, livePrice)} · سود/زیان باز " +
                    "${String.format(java.util.Locale.US, "%.2f", pnlUsd ?: 0.0)}$",
                style = MaterialTheme.typography.labelSmall,
                color = if ((pnlUsd ?: 0.0) >= 0.0) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text("قیمت زندهٔ این نماد الان در دسترس نیست؛ سود/زیان باز فقط با قیمت واقعی نشان داده " +
                    "می‌شود، نه با حدس.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }

        // چارتِ خودِ معامله: همان نماد، همان تایم‌فریمِ ثبت‌شده در ژورنال، فقط TradingView.
        Box(Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp)) {
            key(trade.id) {
                TradingViewWidget(
                    symbol = trade.symbol,
                    interval = trade.interval,
                    widgetId = "trade_" + trade.id.takeLast(8),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * خودِ ویدجت TradingView: یک WebView که فقط iframe رسمی `s.tradingview.com/widgetembed` را
 * بارگذاری می‌کند. هیچ چیزِ دیگری داخلش نیست و هیچ چیزی رویش کشیده نمی‌شود.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    widgetId: String = "chart_primary",
    modifier: Modifier = Modifier,
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.find(symbol) }
    if (tvSymbol == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("نماد $symbol در نگاشت رسمی TradingView اپ نیست؛ هیچ نماد جایگزینی (مثلاً طلا) " +
                    "نشان داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
                modifier = Modifier.padding(16.dp))
        }
        return
    }
    val tvInterval = remember(interval) {
        when (interval) {
            Interval.M1 -> "1"
            Interval.M5 -> "5"
            Interval.M15 -> "15"
            Interval.M30 -> "30"
            Interval.H1 -> "60"
            Interval.H4 -> "240"
            Interval.D1 -> "D"
        }
    }
    val html = remember(tvSymbol, tvInterval, widgetId) { tradingViewHtml(tvSymbol, tvInterval, widgetId) }

    // Reload only when the instrument/timeframe really changed: an unconditional reload on every
    // recomposition is what kept TradingView stuck on "loading".
    val loadKey = "$tvSymbol|$tvInterval|$widgetId"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // The widget renders its full desktop layout (side toolbar, studies, scales)
                    // for a desktop agent; a mobile agent gets a stripped chart. Same TradingView,
                    // same data — only the layout it serves changes.
                    userAgentString = DESKTOP_USER_AGENT
                    textZoom = 100
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?,
                    ) {
                        // A certificate problem is never ignored: cancel the load.
                        handler?.cancel()
                    }
                }
                tag = loadKey
                loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // update runs on every recomposition; only a real instrument/timeframe change reloads.
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
    )
}

/**
 * ONLY the official TradingView widget: its own candles, its own scales, its own Ichimoku study.
 * هیچ خط، لایه یا عددِ دست‌سازی داخل این HTML نیست.
 */
private fun tradingViewHtml(tvSymbol: String, tvInterval: String, widgetId: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tv_$widgetId" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background-color: #0b0e13;
            }
            iframe { width: 100%; height: 100%; border: none; display: block; }
          </style>
        </head>
        <body>
          <iframe id="tv_frame" src="$iframeUrl" allowtransparency="true" frameborder="0"
                  allow="fullscreen"></iframe>
        </body>
        </html>
    """.trimIndent()
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

@Composable
internal fun SymbolSearchRow(selected: String, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("طلا و فارکس") }
    var open by rememberSaveable { mutableStateOf(false) }

    val forexIds = remember { WatchCatalog.chartSymbols.filter { !CryptoCatalog.isCrypto(it) } }
    val cryptoList = remember { CryptoCatalog.symbols }

    val forexShown = remember(query) {
        val needle = SymbolSearch.normalize(query).replace(" ", "").uppercase()
        if (needle.isEmpty()) forexIds
        else forexIds.filter { it.replace("/", "").contains(needle, ignoreCase = true) }
    }
    val cryptoShown = remember(query) { SymbolSearch.rank(query, cryptoList, limit = 300) }
    val suggestions = remember(query, forexShown.size, cryptoShown.size) {
        if (query.isBlank() || forexShown.isNotEmpty() || cryptoShown.isNotEmpty()) emptyList()
        else SymbolSearch.suggest(query, cryptoList)
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
                "${forexIds.size + cryptoList.size} نماد",
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
            label = { Text("جستجو — طلا، EUR، بیت‌کوین، PEPE…", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("طلا و فارکس", "ارز دیجیتال").forEach { option ->
                FilterChip(
                    selected = group == option,
                    onClick = { group = option },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Gold.copy(alpha = 0.18f),
                        selectedLabelColor = AurumColors.Gold,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 6.dp)) {
            if (group == "طلا و فارکس") {
                items(forexShown, key = { "f-$it" }) { id ->
                    SymbolRow(id, id == selected) { onSelect(id); open = false; query = "" }
                }
            } else {
                items(cryptoShown, key = { "c-" + it.id }) { coin ->
                    SymbolRow(coin.id, coin.id == selected) { onSelect(coin.id); open = false; query = "" }
                }
            }
            if (suggestions.isNotEmpty()) {
                item {
                    Text(
                        "«$query» پیدا نشد. منظورتان این بود؟",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(suggestions, key = { "s-" + it.id }) { coin ->
                    SymbolRow(coin.id, false, AurumColors.Cyan) {
                        onSelect(coin.id); open = false; query = ""
                    }
                }
            }
        }
    }
    SymbolPickerRow(selected, onSelect)
}

@Composable
private fun SymbolRow(
    id: String,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    Text(
        id,
        style = MaterialTheme.typography.bodyMedium,
        color = tint ?: if (selected) AurumColors.Gold else AurumColors.TextPrimary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
    )
}

@Composable
internal fun SymbolPickerRow(selected: String, onSelect: (String) -> Unit) {
    // Gold first, then the FX pairs, then crypto — the order asked for.
    val quick = remember(selected) {
        val forex = WatchCatalog.chartSymbols.filter { !CryptoCatalog.isCrypto(it) }
        val crypto = WatchCatalog.chartSymbols.filter { CryptoCatalog.isCrypto(it) }
        (listOf("XAU/USD") + forex + crypto + selected).distinct()
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
