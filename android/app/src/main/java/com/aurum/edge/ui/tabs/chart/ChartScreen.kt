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
    var tradingViewBlocked by remember(market.symbol, market.interval) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

        // ── Timeframe selector ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
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

        // ── Unified Trading Chart (Past to Present) ────────────────────────
        SectionCard(
            title = "چارت معاملاتی · ${market.symbol} (${market.interval.label})",
            subtitle = if (tradingViewBlocked && market.candles.isNotEmpty())
                "${market.candles.size} کندل واقعی از گذشته تا لحظهٔ حال"
            else
                "دیتای کامل از گذشته تا لحظهٔ حال با تیک زنده",
        ) {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                if (tradingViewBlocked && market.candles.isNotEmpty()) {
                    CandleChart(
                        candles = market.candles,
                        interval = market.interval,
                        signal = market.signal,
                        modifier = Modifier.fillMaxSize(),
                        showIchimoku = true,
                        showLevels = true,
                        showVolume = true,
                    )
                } else if (!tradingViewBlocked) {
                    TradingViewWidget(
                        symbol = market.symbol,
                        interval = market.interval,
                        modifier = Modifier.fillMaxSize(),
                        onFailed = { tradingViewBlocked = it },
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(AurumColors.ChartBg, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "در حال دریافت دیتای کندل‌های واقعی ${market.symbol}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurumColors.TextSecondary,
                        )
                    }
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
    modifier: Modifier = Modifier,
    onFailed: (Boolean) -> Unit = {},
) {
    val html = tradingViewHtml(symbol, interval)
    val loadKey = "$symbol|${interval.label}"
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // The widget is drawn OVER the app's own candles. Where TradingView is
                // geo-blocked it answers 451 and the blank error page hid a chart that
                // was working perfectly underneath. Report the failure so it can be
                // removed from the stack instead of covering good data.
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onFailed(false)
                    }
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?,
                    ) {
                        // Only the main document matters; a blocked tracker is irrelevant.
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
    val tvSymbol = TradingViewSymbols.of(symbol)
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
        "&saveimage=1&toolbarbg=0b0e13&studies=IchimokuCloud%40tv-basicstudies" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1"
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
          <style>
            html, body, #tradingview_chart, iframe {
              margin:0; padding:0; width:100%; height:100%; overflow:hidden;
              background:#0b0e13; border:0;
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
 * Chart symbol browser: gold first, then the FX pairs, then every crypto symbol.
 *
 * The list is static and needs no network call, so it can never be emptied by a blocked
 * exchange API — the previous version fetched the universe from Binance, which answers
 * 451 here, leaving the picker silently empty. TradingView renders all of these itself,
 * so what is listed is exactly what the chart can draw.
 */
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
                "نماد فعال: $selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AurumColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = open,
                onClick = { open = !open },
                label = { Text(if (open) "بستن جستجو" else "تغییر نماد", style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AurumColors.Gold.copy(alpha = 0.20f),
                    selectedLabelColor = AurumColors.Gold,
                    labelColor = AurumColors.TextSecondary,
                ),
            )
        }

        if (open) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("جستجو — طلا، EUR، بیت‌کوین، PEPE…", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
