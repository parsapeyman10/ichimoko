package com.aurum.edge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.components.FeedBanner
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

enum class AurumTab(val label: String, val icon: ImageVector) {
    Home("خانه", Icons.Filled.Home),
    Chart("چارت", Icons.Filled.ShowChart),
    Signal("معامله", Icons.Filled.Bolt),
    Watch("دیده‌بان", Icons.Filled.ViewList),
    News("خبر", Icons.Filled.Article),
    Crypto("رمزارز", Icons.Filled.TrendingUp),
    CryptoNews("خبر کریپتو", Icons.Filled.Article),
    Nobitex("نوبیتکس", Icons.Filled.ViewList),
    NobitexNews("خبر نوبیتکس", Icons.Filled.Article),
    Stocks("بورس ایران", Icons.Filled.ShowChart),
    IranNews("خبر بورس", Icons.Filled.Article),
    IranPrices("ریالی", Icons.Filled.ViewList),
    IranWatchSettings("منابع ریالی", Icons.Filled.Settings),
    Agah("آگاه", Icons.Filled.Bookmarks),
    Learn("یادگیری", Icons.Filled.School),
    Journal("ژورنال", Icons.Filled.Bookmarks),
    Settings("تنظیمات", Icons.Filled.Settings),
    Api("APIها", Icons.Filled.Settings),
}

/** No tab from another workspace is reachable from the current bottom navigation. */
internal fun primaryTabsFor(space: Workspace): List<AurumTab> = when (space) {
    Workspace.FOREX -> listOf(AurumTab.Home, AurumTab.Chart, AurumTab.Signal, AurumTab.News, AurumTab.Watch)
    Workspace.CRYPTO -> listOf(AurumTab.Crypto, AurumTab.CryptoNews)
    Workspace.NOBITEX -> listOf(AurumTab.Nobitex, AurumTab.NobitexNews)
    Workspace.IRAN_STOCKS -> listOf(AurumTab.Stocks, AurumTab.IranPrices, AurumTab.IranNews, AurumTab.Agah)
}

internal fun moreTabsFor(space: Workspace): List<AurumTab> = when (space) {
    Workspace.FOREX -> listOf(AurumTab.Learn, AurumTab.Journal, AurumTab.Settings, AurumTab.Api)
    Workspace.IRAN_STOCKS -> listOf(AurumTab.IranWatchSettings, AurumTab.Api)
    Workspace.CRYPTO, Workspace.NOBITEX -> listOf(AurumTab.Api)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurumRoot(viewModel: AurumViewModel) {
    // Choose on every cold launch; only rotation/process recreation preserves the current screen.
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(AurumTab.Home.name) }
    var showMore by remember { mutableStateOf(false) }
    var selectionError by remember { mutableStateOf(false) }
    var activatedHere by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val market by viewModel.market.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val workspace = Workspace.entries.firstOrNull { it.id == workspaceId && it.id == settings.workspaceId }
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    fun leave() {
        if (viewModel.leaveWorkspace(context)) {
            workspaceId = null
            activatedHere = false
            tab = AurumTab.Home.name
            showMore = false
        } else selectionError = true
    }
    BackHandler(enabled = workspace != null) { leave() }
    // After process recreation Compose may restore a visible workspace, but the new app
    // container has no running feed. Re-activate it only after that workspace is visible.
    LaunchedEffect(workspace?.id) {
        if (workspace != null && !activatedHere) {
            if (viewModel.enterWorkspace(context, workspace)) activatedHere = true
            else { workspaceId = null; selectionError = true }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast!!)
            viewModel.consumeToast()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (workspace == Workspace.FOREX) viewModel.pauseInvisibleForexFeed()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (workspace == Workspace.FOREX) viewModel.resumeVisibleForexFeed()
    }
    if (workspace == null) {
        WorkspaceChooser(selectionError) { selected ->
            if (viewModel.enterWorkspace(context, selected)) {
                selectionError = false
                activatedHere = true
                workspaceId = selected.id
                tab = primaryTabsFor(selected).first().name
            } else selectionError = true
        }
        return
    }
    val primary = primaryTabsFor(workspace)
    val more = moreTabsFor(workspace)
    val selectedTab = AurumTab.entries.firstOrNull { it.name == tab && it in primary + more } ?: primary.first()
    fun open(destination: AurumTab) { if (destination in primary + more) tab = destination.name }

    Scaffold(containerColor = AurumColors.Bg, snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = AurumColors.Surface) {
                primary.forEach { entry ->
                    NavigationBarItem(selected = selectedTab == entry, onClick = { open(entry) },
                        icon = { Icon(entry.icon, contentDescription = entry.label, modifier = Modifier.size(20.dp)) },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        alwaysShowLabel = true)
                }
                if (more.isNotEmpty()) NavigationBarItem(selected = selectedTab in more,
                    onClick = { showMore = true },
                    icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "بخش‌های دیگر", modifier = Modifier.size(20.dp)) },
                    label = { Text("بیشتر", style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true)
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().background(AurumColors.SurfaceAlt).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("فضای ${workspace.label}", style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold, modifier = Modifier.weight(1f))
                TextButton(onClick = ::leave) { Text("تغییر فضا") }
            }
            if (workspace == Workspace.FOREX && (selectedTab == AurumTab.Chart || selectedTab == AurumTab.Signal)) {
                AppHeader(symbol = market.symbol, price = market.lastPrice, interval = market.interval,
                    lastUpdate = market.feed.lastSuccessAt, onRefresh = viewModel::refreshNow)
                FeedBanner(status = market.feed, lastPrice = market.lastPrice,
                    lastBarTime = market.candles.lastOrNull()?.time, showingCache = market.showingCachedData)
            }
            Box(Modifier.fillMaxSize()) {
                when (selectedTab) {
                    AurumTab.Home -> HomeScreen(viewModel, market,
                        onChart = { open(AurumTab.Chart) }, onSignal = { open(AurumTab.Signal) },
                        onNews = { open(AurumTab.News) }, onLearn = { open(AurumTab.Learn) },
                        onJournal = { open(AurumTab.Journal) }, onSettings = { open(AurumTab.Settings) })
                    AurumTab.Chart -> ChartScreen(viewModel, market,
                        onOpenSettings = { open(AurumTab.Settings) }, onOpenJournal = { open(AurumTab.Journal) })
                    AurumTab.Signal -> SignalScreen(viewModel, market, onOpenNews = { open(AurumTab.News) })
                    AurumTab.Watch -> MarketWatchScreen(viewModel, onOpenSettings = { open(AurumTab.Settings) })
                    AurumTab.News -> PersianNewsScreen(viewModel, onOpenSettings = { open(AurumTab.Settings) })
                    AurumTab.Crypto -> CryptoScreen(viewModel, onOpenSettings = { open(AurumTab.CryptoNews) })
                    AurumTab.CryptoNews -> CryptoNewsScreen(viewModel)
                    AurumTab.Nobitex -> NobitexWorkspaceScreen(viewModel)
                    AurumTab.NobitexNews -> NobitexNewsScreen(viewModel)
                    AurumTab.Stocks -> IranStocksScreen(viewModel)
                    AurumTab.IranNews -> IranNewsScreen(viewModel)
                    AurumTab.IranPrices -> IranPricesScreen(viewModel, onOpenSettings = { open(AurumTab.IranWatchSettings) })
                    AurumTab.IranWatchSettings -> IranWatchSettingsScreen(viewModel)
                    AurumTab.Agah -> AgahGuideScreen()
                    AurumTab.Learn -> LearnScreen(viewModel)
                    AurumTab.Journal -> JournalScreen(viewModel, market)
                    AurumTab.Settings -> SettingsScreen(viewModel, settings)
                    AurumTab.Api -> ApiMenuScreen(settings, workspace,
                        onForexSettings = { open(AurumTab.Settings) },
                        onIranStocks = { open(AurumTab.Stocks) },
                        onCrypto = { open(AurumTab.Crypto) })
                }
            }
        }
    }
    if (showMore && more.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, containerColor = AurumColors.Surface) {
            Text("فقط بخش‌های ${workspace.label}", style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            more.forEach { destination ->
                ListItem(headlineContent = { Text(destination.label) },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { open(destination); showMore = false })
            }
        }
    }
}

@Composable
private fun WorkspaceChooser(error: Boolean, onChoose: (Workspace) -> Unit) {
    Column(Modifier.fillMaxSize().background(AurumColors.Bg).verticalScroll(rememberScrollState())
        .padding(vertical = 18.dp)) {
        Text("AURUM / EDGE", style = MaterialTheme.typography.labelLarge, color = AurumColors.Gold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Text("انتخاب فضای کار", style = MaterialTheme.typography.headlineMedium, color = AurumColors.TextPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp))
        Text("پیش از نمایش بازار، یکی از چهار فضای مستقل را انتخاب کنید. این مرحله ورود به حساب صرافی یا کارگزاری نیست.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        if (error) Text("تغییر فضا روی دستگاه ذخیره نشد؛ برای جلوگیری از پایش هم‌زمان، دوباره تلاش کنید.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.Red,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Workspace.entries.forEach { space ->
            SectionCard(space.label, space.sources) {
                Text(when (space) {
                    Workspace.FOREX -> "چارت و پژوهش طلا، تقویم خبر و ژورنال کاغذی مخصوص فارکس."
                    Workspace.CRYPTO -> "رصد جهانی و خبر رمزارز؛ نه قیمت ریالی یا ژورنال فارکس."
                    Workspace.NOBITEX -> "بازار USDT و ریال جدا؛ تمرین اسپات در ژورنال مستقل، بدون کلید معاملاتی."
                    Workspace.IRAN_STOCKS -> "تابلوخوانی و غربال عددی بورس؛ TTM کدال و مسیر رسمی آساتریدر."
                }, style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                Button(onClick = { onChoose(space) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("ورود به ${space.label}")
                }
            }
        }
        Text("تغییر فضا پایش خودکار کاغذی فارکس را خاموش می‌کند. هیچ سفارشی به بروکر ارسال نمی‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun AppHeader(symbol: String, price: Double?, interval: com.aurum.edge.core.Interval,
                      lastUpdate: Long?, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(AurumColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(34.dp).background(AurumColors.Gold.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text("Au", color = AurumColors.Gold, style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.weight(1f)) {
            Text(symbol, style = MaterialTheme.typography.titleMedium, color = AurumColors.TextPrimary)
            Text("${interval.label} · آخرین دریافت ${relativeTime(lastUpdate)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatPrice(price), style = MaterialTheme.typography.titleMedium,
                color = AurumColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(if (price == null) "بدون دادهٔ تازه" else "آخرین مشاهده · وضعیت فید پایین صفحه",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی", tint = AurumColors.Gold)
        }
    }
}

@Composable
fun ModePill(text: String, color: Color) = Pill(text = text, color = color)
