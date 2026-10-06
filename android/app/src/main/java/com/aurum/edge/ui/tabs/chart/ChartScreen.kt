package com.aurum.edge.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.Candle
import com.aurum.edge.core.Interval
import com.aurum.edge.core.PaperTrade
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.StrategyKind
import com.aurum.edge.data.MarketState
import com.aurum.edge.engine.SignalEngine
import com.aurum.edge.data.WatchCatalog
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.data.SymbolSearch
import com.aurum.edge.data.TradingViewSymbols
import com.aurum.edge.data.CryptoCatalog
import com.aurum.edge.data.PairScanStatus
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pairScan by viewModel.pairScan.collectAsStateWithLifecycle()
    val openTrade = trades.firstOrNull { it.symbol == market.symbol && it.isOpen }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {

        // ── Best Condition / Golden Setup Banner ───────────────────────────
        BestConditionBanner(
            currentSymbol = market.symbol,
            bestPick = pairScan.bestPick,
            sweeping = pairScan.sweeping,
            onSelectBest = { symbol -> viewModel.selectChartSymbol(symbol) },
            onScan = { viewModel.scanPairs() },
        )

        // ── Symbol Search & Selector (50+ Assets) ──────────────────────────
        SymbolSearchRow(selected = market.symbol) { viewModel.selectChartSymbol(it) }

        // ── 5 Modular Strategy Selector ────────────────────────────────────
        StrategySelectorRow(
            activeStrategy = settings.activeStrategy,
            onSelect = { viewModel.setActiveStrategy(it) },
        )

        // ── Timeframe selector ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
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

        // ── Unified TradingView Live Streaming Chart (With Ichimoku Studies) ──
        SectionCard(
            title = "چارت آنلاین و زنده · ${market.symbol} (${market.interval.label})",
            subtitle = "جریان آنلاین داده‌ها، حجم و اندیکاتور ایچیموکو از مرجع رسمی TradingView",
            trailing = {
                Pill(
                    text = market.feed.mode.label,
                    color = if (market.feed.mode == com.aurum.edge.core.FeedMode.LIVE) AurumColors.Green else AurumColors.Gold,
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

        // ── Strategy Bar & Action Levels ───────────────────────────────────
        StrategyBar(viewModel, market)

        // ── Best Conditions Status & Confluence Checklist ──────────────────
        ConditionsChecklistCard(market)

        // ── Entry Score Progress ───────────────────────────────────────────
        EntryScoreCard(market)
    }
}

/**
 * Prominent banner showing the best opportunity across 50+ scanned markets
 * and allowing one-click selection to bring it immediately onto TradingView.
 */
@Composable
private fun BestConditionBanner(
    currentSymbol: String,
    bestPick: PairScanStatus?,
    sweeping: Boolean,
    onSelectBest: (String) -> Unit,
    onScan: () -> Unit,
) {
    SectionCard(
        title = "✨ برترین شرایط بازار (اسکنر همگانی)",
        subtitle = if (bestPick != null) "بالاترین شواهد تاییدشده و بهترین نسبت ریسک به ریوارد" else "پایش همزمان ۵۰ نماد برای کشف موقعیت‌های طلایی",
        trailing = {
            if (sweeping) {
                Pill("در حال اسکن...", AurumColors.Gold)
            } else if (bestPick != null) {
                val action = bestPick.action ?: SignalAction.NO_TRADE
                val tone = when (action) {
                    SignalAction.BUY -> AurumColors.Green
                    SignalAction.SELL -> AurumColors.Red
                    else -> AurumColors.Gold
                }
                Pill(
                    when (action) {
                        SignalAction.BUY -> "خرید ۹۵٪"
                        SignalAction.SELL -> "فروش ۹۵٪"
                        else -> "کاندیدا"
                    },
                    tone,
                )
            }
        },
    ) {
        if (bestPick != null) {
            val isCurrent = currentSymbol == bestPick.symbol
            val action = bestPick.action ?: SignalAction.NO_TRADE
            val color = when (action) {
                SignalAction.BUY -> AurumColors.Green
                SignalAction.SELL -> AurumColors.Red
                else -> AurumColors.Gold
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = bestPick.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AurumColors.Gold,
                        )
                        Pill(
                            text = "${bestPick.confidence?.toInt() ?: 95}٪ اطمینان",
                            color = color,
                        )
                        bestPick.riskReward?.let { rr ->
                            Pill("R:R 1:${String.format(java.util.Locale.US, "%.1f", rr)}", AurumColors.Cyan)
                        }
                    }
                    Text(
                        text = bestPick.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurumColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCurrent) {
                    Pill(
                        text = "✓ چارت روی بهترین شرایط تنظیم است",
                        color = AurumColors.Green,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Button(
                        onClick = { onSelectBest(bestPick.symbol) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AurumColors.Gold,
                            contentColor = AndroidColor.BLACK.let { androidx.compose.ui.graphics.Color(it) },
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("انتخاب ${bestPick.symbol} روی چارت", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onScan,
                    enabled = !sweeping,
                ) {
                    Text(if (sweeping) "در حال اسکن..." else "اسکن مجدد ۵۰ نماد")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "برای یافتن بهترین ستاپ معاملاتی از میان تمامی نمادها، دکمهٔ اسکن را بزنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onScan,
                    enabled = !sweeping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AurumColors.Gold,
                        contentColor = AndroidColor.BLACK.let { androidx.compose.ui.graphics.Color(it) },
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (sweeping) "در حال اسکن..." else "اسکن ۵۰ نماد")
                }
            }
        }
    }
}

/**
 * Detailed status of all 8 Ichimoku & Super Plus technical conditions for the current chart symbol.
 */
@Composable
private fun ConditionsChecklistCard(market: MarketState) {
    val signal = market.signal
    val confluence = signal?.confluence.orEmpty()
    var expanded by rememberSaveable { mutableStateOf(true) }

    SectionCard(
        title = "وضعیت شرایط استراتژی و شواهد (${market.symbol})",
        subtitle = "بررسی زندهٔ تمامی فیلترها، کراس تنکان/کیجون، ابر کومو، چیکواسپن و تراز چندتایم‌فریم",
        trailing = {
            val confirmedCount = confluence.count { it.ok }
            val totalCount = confluence.size
            if (totalCount > 0) {
                Pill(
                    text = "$confirmedCount از $totalCount تایید",
                    color = if (confirmedCount == totalCount) AurumColors.Green else AurumColors.Gold,
                )
            }
        },
    ) {
        if (confluence.isEmpty()) {
            Text(
                "در حال محاسبه و بررسی شواهد تکنیکال این نماد...",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                confluence.forEach { item ->
                    ConfluenceRow(item)
                }
            }
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
    val html = remember(tvSymbol, tvInterval) { tradingViewHtml(tvSymbol, tvInterval) }
    val loadKey = "$tvSymbol|$tvInterval"

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = false
                }
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowContentAccess = true
                    allowFileAccess = true
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                }
                tag = loadKey
                loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://s3.tradingview.com", html, "text/html", "UTF-8", "https://s3.tradingview.com")
            }
        },
    )
}

private fun tradingViewHtml(tvSymbol: String, tvInterval: String): String {
    val encoded = tvSymbol.replace(":", "%3A")
    val iframeUrl = "https://s.tradingview.com/widgetembed/?frameElementId=tradingview_chart" +
        "&symbol=$encoded&interval=$tvInterval&hidesidetoolbar=0&symboledit=1" +
        "&saveimage=0&toolbarbg=0b0e13" +
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=1&hideideas=1&locale=en" +
        "&studies=%5B%22STD%3BIchimoku%25Cloud%22%5D"

    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { width: 100%; height: 100%; background: #0b0e13; overflow: hidden; }
            #tv_chart_container { width: 100%; height: 100%; position: absolute; top: 0; left: 0; right: 0; bottom: 0; }
            iframe { width: 100%; height: 100%; border: 0; display: block; }
          </style>
          <script type="text/javascript" src="https://s3.tradingview.com/tv.js"></script>
        </head>
        <body>
          <div id="tv_chart_container"></div>
          <script type="text/javascript">
            function initWidget() {
              try {
                if (typeof TradingView !== 'undefined' && TradingView.widget) {
                  new TradingView.widget({
                    "autosize": true,
                    "symbol": "$tvSymbol",
                    "interval": "$tvInterval",
                    "timezone": "Etc/UTC",
                    "theme": "dark",
                    "style": "1",
                    "locale": "en",
                    "toolbar_bg": "#0b0e13",
                    "enable_publishing": false,
                    "hide_side_toolbar": false,
                    "allow_symbol_change": true,
                    "save_image": false,
                    "studies": [
                      "STD;Ichimoku%Cloud"
                    ],
                    "container_id": "tv_chart_container"
                  });
                  return;
                }
              } catch (e) {}
              // Direct fallback iframe if script execution is blocked
              var container = document.getElementById('tv_chart_container');
              if (container) {
                var iframe = document.createElement('iframe');
                iframe.id = 'tradingview_chart';
                iframe.src = '$iframeUrl';
                iframe.style.width = '100%';
                iframe.style.height = '100%';
                iframe.style.border = '0';
                iframe.setAttribute('allowtransparency', 'true');
                iframe.setAttribute('scrolling', 'no');
                iframe.setAttribute('allowfullscreen', 'true');
                container.appendChild(iframe);
              }
            }
            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', initWidget);
            } else {
              initWidget();
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun StrategySelectorRow(
    activeStrategy: StrategyKind,
    onSelect: (StrategyKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrategyKind.entries.forEach { strategy ->
                FilterChip(
                    selected = activeStrategy == strategy,
                    onClick = { onSelect(strategy) },
                    label = { Text(strategy.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AurumColors.Cyan.copy(alpha = 0.22f),
                        selectedLabelColor = AurumColors.Cyan,
                        labelColor = AurumColors.TextSecondary,
                    ),
                )
            }
        }
        Text(
            activeStrategy.description,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp),
        )
    }
}

/**
 * Chart symbol browser across 50+ global instruments:
 * Metals, Commodities, Forex pairs, Top Global Shares/Stocks, and Cryptos.
 */
@Composable
internal fun SymbolSearchRow(selected: String, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf("همه") }

    val allSymbols = remember { WatchCatalog.scannerSymbols }
    val commodities = remember { listOf("XAU/USD", "XAG/USD", "BRENT", "WTI", "NATGAS", "COPPER") }
    val forex = remember {
        listOf("EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "USD/CAD", "NZD/USD", "USD/CHF", "EUR/GBP", "EUR/JPY", "GBP/JPY")
    }
    val stocks = remember { listOf("AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "GOOGL", "META", "AMD", "NFLX", "INTC", "SPY", "QQQ", "PLTR", "COIN", "BABA") }
    val cryptoList = remember { CryptoCatalog.symbols }

    val activeList = remember(group, query) {
        val baseList = when (group) {
            "طلا و کالا" -> commodities
            "فارکس" -> forex
            "سهام برتر" -> stocks
            "کریپتو" -> cryptoList.map { it.id }
            else -> allSymbols
        }
        val needle = SymbolSearch.normalize(query).replace(" ", "").uppercase()
        if (needle.isEmpty()) baseList
        else baseList.filter { it.replace("/", "").contains(needle, ignoreCase = true) }
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
                label = { Text(if (open) "بستن جستجو" else "تغییر نماد (۵۰+ سهم و ارز)", style = MaterialTheme.typography.labelSmall) },
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
                label = { Text("جستجو میان ۵۰+ نماد — طلا، تسلا، ان‌ویدیا، EUR، بیت‌کوین…", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("همه", "طلا و کالا", "فارکس", "سهام برتر", "کریپتو").forEach { option ->
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
                items(activeList, key = { "item-$it" }) { id ->
                    SymbolRow(id, id == selected) { onSelect(id); open = false; query = "" }
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
            Text(
                "وضعیت موتور خودکار: $autoStatus",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

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
