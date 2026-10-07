package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
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
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPriceFor
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
    // Reset the probe whenever the instrument or timeframe changes.
    var tradingViewBlocked by remember(market.symbol, market.interval) { mutableStateOf(false) }
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
            title = if (tradingViewBlocked) "چارت داخلی · ${market.symbol}"
            else "چارت TradingView · ${market.symbol}",
            subtitle = if (tradingViewBlocked)
                "TradingView از این شبکه در دسترس نیست؛ چارت خود اپ روی همان دیتای واقعی نمایش داده می‌شود"
            else null,
        ) {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                // TradingView may be blocked/slow in some networks. Keep the app's own verified
                // candle chart behind the WebView so the chart area is never an empty black panel.
                // The app's own candles are the FALLBACK, drawn only when TradingView is
                // unavailable. Previously both rendered and the WebView sat on top, so a
                // blocked widget hid a working chart.
                if (tradingViewBlocked && market.candles.isNotEmpty()) {
                    CandleChart(
                        candles = market.candles.takeLast(800),
                        interval = market.interval,
                        signal = market.signal,
                        modifier = Modifier.fillMaxSize(),
                        showVolume = false,
                    )
                }
                if (!tradingViewBlocked) {
                    TradingViewWidget(
                        symbol = market.symbol,
                        interval = market.interval,
                        entry = market.signal?.entry,
                        stopLoss = market.signal?.stopLoss,
                        takeProfit = market.signal?.takeProfit,
                        action = market.signal?.action,
                        modifier = Modifier.fillMaxSize(),
                        onFailed = { tradingViewBlocked = it },
                    )
                }
                EngineOverlay(
                    market = market,
                    openTrade = openTrade,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }

        StrategyBar(viewModel, market)

        EntryScoreCard(market)
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
        market.lastPrice?.let { price ->
            Text(
                "آخرین قیمت: ${formatPriceFor(market.symbol, price)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
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
private fun TradingViewWidget(
    symbol: String,
    interval: Interval,
    widgetId: String = "chart_primary",
    entry: Double? = null,
    stopLoss: Double? = null,
    takeProfit: Double? = null,
    action: SignalAction? = null,
    leverage: Int? = null,
    riskReward: Double? = null,
    modifier: Modifier = Modifier,
    onFailed: (Boolean) -> Unit = {},
) {
    val tvSymbol = remember(symbol) { TradingViewSymbols.of(symbol) }
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

    val html = remember(tvSymbol, tvInterval, widgetId, entry, stopLoss, takeProfit, action) {
        tradingViewHtml(
            symbol = symbol,
            tvSymbol = tvSymbol,
            tvInterval = tvInterval,
            widgetId = widgetId,
            entry = entry,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            action = action,
            leverage = leverage,
            riskReward = riskReward,
        )
    }

    // Reload only when the instrument/timeframe/level set really changed: an unconditional
    // reload on every recomposition is what kept TradingView stuck on "loading".
    val loadKey = "$tvSymbol|$tvInterval|$widgetId|$entry|$stopLoss|$takeProfit"
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
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = WebChromeClient()
                // Where TradingView is geo-blocked it answers 451 and a blank error page would
                // sit on top of the app's own verified candles. Report the failure so the
                // native chart is used instead of covering good data.
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onFailed(false)
                    }
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) onFailed(true)
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) onFailed(true)
                    }
                }
                tag = loadKey
                loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            // update runs on every recomposition; only a real instrument/level change reloads.
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
    )
}

private fun tradingViewHtml(
    symbol: String,
    tvSymbol: String,
    tvInterval: String,
    widgetId: String,
    entry: Double? = null,
    stopLoss: Double? = null,
    takeProfit: Double? = null,
    action: SignalAction? = null,
    leverage: Int? = null,
    riskReward: Double? = null,
): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tv_$widgetId" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"

    val hudHtml = if (entry != null && stopLoss != null && takeProfit != null) {
        val entryFormatted = formatPriceFor(symbol, entry)
        val slFormatted = formatPriceFor(symbol, stopLoss)
        val tpFormatted = formatPriceFor(symbol, takeProfit)
        val isBuy = action == SignalAction.BUY

        val tpY = if (isBuy) "20%" else "75%"
        val entryY = "48%"
        val slY = if (isBuy) "75%" else "20%"

        """
        <!-- Sleek Floating HUD & Thin Dynamic Dashed Lines -->
        <div style="position:absolute; top:8px; right:8px; z-index:900; pointer-events:none; display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end;">
          <span style="background:rgba(255,255,255,0.92); color:#0b0e13; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">ورود: $$entryFormatted</span>
          <span style="background:rgba(0,230,118,0.92); color:#0b0e13; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">TP: $$tpFormatted</span>
          <span style="background:rgba(255,82,82,0.92); color:#ffffff; font-size:11px; font-weight:bold; font-family:tahoma,sans-serif; padding:3px 8px; border-radius:4px; box-shadow:0 2px 4px rgba(0,0,0,0.5);">SL: $$slFormatted</span>
        </div>
        <svg style="position:absolute; top:0; left:0; width:100%; height:100%; pointer-events:none; z-index:850; opacity:0.85;">
          <!-- Take Profit: Green Thin Dashed Line -->
          <line x1="0" y1="$tpY" x2="100%" y2="$tpY" stroke="#00e676" stroke-width="1.2" stroke-dasharray="4,4" />
          <!-- Entry Price: White Thin Dashed Line -->
          <line x1="0" y1="$entryY" x2="100%" y2="$entryY" stroke="#ffffff" stroke-width="1.2" stroke-dasharray="4,4" />
          <!-- Stop Loss: Red Thin Dashed Line -->
          <line x1="0" y1="$slY" x2="100%" y2="$slY" stroke="#ff5252" stroke-width="1.2" stroke-dasharray="4,4" />
        </svg>
        """.trimIndent()
    } else ""

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
              position: relative;
            }
            iframe {
              width: 100%;
              height: 100%;
              border: none;
            }
          </style>
          <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
        </head>
        <body>
          $hudHtml
          <iframe src="$iframeUrl" allowtransparency="true" frameborder="0"></iframe>
        </body>
        </html>
    """.trimIndent()
}

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

/**
 * Live strategy readout, directly under the chart.
 *
 * The engine was already evaluating every closed bar, but the chart only showed a score
 * with no explanation, so a screen that sat at "no entry" for hours was indistinguishable
 * from a broken one. This states three things at all times: where the score stands against
 * the threshold, the single condition currently blocking an entry, and — when one is live
 * — the exact levels the trade would use.
 *
 * Read-only. It reports what the automatic engine decided; it cannot open anything.
 */
@Composable
private fun StrategyBar(viewModel: AurumViewModel, market: MarketState) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()

    val signal = market.signal
    val action = signal?.action ?: SignalAction.NO_TRADE
    val score = signal?.confidence ?: 0.0
    val threshold = settings.minConfidence
    val digits = CryptoCatalog.digitsFor(market.symbol)
    val open = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }

    val tone = when {
        open != null -> AurumColors.Cyan
        action == SignalAction.BUY -> AurumColors.Green
        action == SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.TextSecondary
    }

    // The first unmet condition is far more useful than a list of twelve.
    val firstBlocker = signal?.blockers?.firstOrNull()
        ?: signal?.confluence?.firstOrNull { !it.ok }?.let { "${it.name} — ${it.detail}" }

    SectionCard(
        title = "استراتژی روی ${market.symbol}",
        subtitle = "ایچیموکو · ${market.interval.label} · خودکار و کاغذی",
        trailing = {
            Pill(
                when {
                    open != null -> "پوزیشن باز"
                    action == SignalAction.BUY -> "خرید"
                    action == SignalAction.SELL -> "فروش"
                    else -> "منتظر"
                },
                tone,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("امتیاز", "${score.toInt()} / ${threshold.toInt()}", tone, Modifier.weight(1f))
            StatTile("تایم‌فریم", market.interval.label, modifier = Modifier.weight(1f))
            StatTile(
                "کندل بسته",
                market.candles.count { it.closed }.toString(),
                modifier = Modifier.weight(1f),
            )
        }

        LinearProgressIndicator(
            progress = { (score / 100.0).toFloat().coerceIn(0f, 1f) },
            color = tone,
            trackColor = AurumColors.Line,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        // ── the levels, whether live or hypothetical ──
        val entry = open?.entry ?: signal?.entry
        val stop = open?.stopLoss ?: signal?.stopLoss
        val target = open?.takeProfit ?: signal?.takeProfit
        if (entry != null && stop != null && target != null) {
            Text(
                if (open != null) "حدود پوزیشن باز" else "اگر وارد شود، با این حدود",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("ورود", formatPriceFor(market.symbol, entry), modifier = Modifier.weight(1f))
                StatTile("حد ضرر", formatPriceFor(market.symbol, stop), AurumColors.Red, Modifier.weight(1f))
                StatTile("حد سود", formatPriceFor(market.symbol, target), AurumColors.Green, Modifier.weight(1f))
            }
            val risk = kotlin.math.abs(entry - stop)
            val reward = kotlin.math.abs(target - entry)
            if (risk > 0) {
                Text(
                    "نسبت سود به ضرر ${String.format(java.util.Locale.US, "%.2f", reward / risk)} " +
                        "· فاصلهٔ حد ضرر ${String.format(java.util.Locale.US, "%,.${digits}f", risk)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── why it is not entering ──
        if (open == null) {
            Text(
                "چرا وارد نشده",
                style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                firstBlocker ?: when {
                    signal == null -> "هنوز سیگنالی برای این کندل محاسبه نشده است."
                    score < threshold -> "امتیاز ${score.toInt()} هنوز به آستانهٔ ${threshold.toInt()} نرسیده است."
                    else -> "همهٔ شرط‌ها برقرار است؛ منتظر تأیید کندل بسته."
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
            // The auto-trader has its own gate, separate from the signal score.
            Text(
                "وضعیت موتور خودکار: $autoStatus",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── remaining conditions, compact ──
        val pending = signal?.confluence?.filter { !it.ok }.orEmpty()
        if (pending.isNotEmpty()) {
            Text(
                "${pending.size} شرط باقی‌مانده: " + pending.take(4).joinToString("، ") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
