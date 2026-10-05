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
            title = "چارت رسمی TradingView · ${market.symbol} (${market.interval.label})",
            subtitle = if (tradingViewBlocked && market.candles.isNotEmpty())
                "${market.candles.size} کندل واقعی بومی از گذشته تا لحظهٔ حال"
            else
                "ویجت رسمی تریدینگ‌ویو با دیتای کامل چندماهه و اندیکاتور ایچیموکو",
        ) {
            Box(Modifier.fillMaxWidth().height(540.dp)) {
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
            }

            EngineOverlay(
                market = market,
                openTrade = openTrade,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
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
    Row(
        modifier = modifier
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("ایچیموکو (${setting.tenkan}/${setting.kijun}/${setting.spanB}) · وضعیت موتور: ${action.name}",
                style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
            if (signal?.isActionable == true) {
                Text("ورود: ${formatPrice(signal.entry)} · حد ضرر: ${formatPrice(signal.stopLoss)} · حد سود: ${formatPrice(signal.takeProfit)}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            }
        }
        Pill("امتیاز ${(signal?.confidence ?: 0.0).toInt()}%", color)
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
                setBackgroundColor(AndroidColor.parseColor("#0b0e13"))
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
                webChromeClient = WebChromeClient()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                tag = loadKey
                loadDataWithBaseURL("https://www.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://www.tradingview.com", html, "text/html", "UTF-8", null)
            }
        },
    )
}

private fun tradingViewHtml(symbol: String, interval: Interval): String {
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

    return """
        <!DOCTYPE html>
        <html lang="fa">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { width: 100%; height: 100%; overflow: hidden; background: #0b0e13; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
            .tradingview-widget-container { width: 100% !important; height: 100% !important; position: relative; }
            .tradingview-widget-container__widget { width: 100% !important; height: calc(100% - 24px) !important; }
            .tradingview-widget-copyright { height: 24px; line-height: 24px; font-size: 11px; text-align: center; color: #787b86; background: #0b0e13; }
            .tradingview-widget-copyright a { color: #2962ff; text-decoration: none; font-weight: 500; }
          </style>
        </head>
        <body>
          <div class="tradingview-widget-container">
            <div class="tradingview-widget-container__widget"></div>
            <div class="tradingview-widget-copyright">
              <a href="https://www.tradingview.com/" rel="noopener nofollow" target="_blank">
                <span class="blue-text">نمای رسمی TradingView</span>
              </a>
            </div>
            <script type="text/javascript" src="https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js" async>
            {
              "autosize": true,
              "symbol": "$tvSymbol",
              "interval": "$tvInterval",
              "timezone": "Etc/UTC",
              "theme": "dark",
              "style": "1",
              "locale": "en",
              "enable_publishing": false,
              "allow_symbol_change": true,
              "withdateranges": true,
              "hide_side_toolbar": false,
              "details": true,
              "hotlist": false,
              "calendar": false,
              "studies": [
                "STD;Ichimoku%1Cloud"
              ],
              "support_host": "https://www.tradingview.com"
            }
            </script>
          </div>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Chart symbol browser across 50+ global instruments:
 * Metals, Commodities, Forex pairs, Top Global Shares/Stocks, and Cryptos.
 */
@Composable
internal fun SymbolSearchRow(selected: String, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("همه") }
    var open by rememberSaveable { mutableStateOf(false) }

    val allSymbols = remember { WatchCatalog.chartSymbols }
    val commodities = remember { listOf("XAU/USD", "XAG/USD", "USOIL", "UKOIL", "COPPER") }
    val stocks = remember { listOf("AAPL", "TSLA", "NVDA", "MSFT", "AMZN", "GOOGL", "META", "AMD", "NFLX", "INTC", "SPY", "QQQ", "PLTR", "COIN", "BABA") }
    val forex = remember { allSymbols.filter { !CryptoCatalog.isCrypto(it) && it !in commodities && it !in stocks } }
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
