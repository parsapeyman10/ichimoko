package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.core.MarketHours
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatQuotePrice
import com.aurum.edge.ui.components.formatSpread
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/** Home is intentionally minimal: market hours + current live/last price only. */
@Composable
fun HomeScreen(
    viewModel: AurumViewModel,
    market: MarketState,
    onChart: () -> Unit,
    onSignal: () -> Unit,
    onNews: () -> Unit,
    onLearn: () -> Unit,
    onJournal: () -> Unit,
    onSettings: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            now = System.currentTimeMillis()
        }
    }
    val session = MarketHours.sessionWindowFor(market.symbol, now)
    val price = HomeReadout.from(market, now)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val configuredSpread = settings.spreadPrice.takeIf { it.isFinite() && it > 0.0 && price.value != null }
    val displayBid = market.bid ?: configuredSpread?.let { spread -> price.value?.minus(spread / 2.0) }
    val displayAsk = market.ask ?: configuredSpread?.let { spread -> price.value?.plus(spread / 2.0) }
    val spread = displayBid?.let { bid -> displayAsk?.let { ask -> ask - bid } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
                .background(
                    Brush.horizontalGradient(listOf(AurumColors.SurfaceAlt, AurumColors.Surface)),
                    RoundedCornerShape(18.dp),
                )
                .border(
                    1.dp,
                    (if (session.closed) AurumColors.Red else AurumColors.Green).copy(alpha = 0.35f),
                    RoundedCornerShape(18.dp),
                )
                .padding(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text("بازار فارکس", color = AurumColors.Gold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (session.closed) "بسته است" else "باز است",
                        color = if (session.closed) AurumColors.Red else AurumColors.Green,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Pill(
                    text = if (session.closed) "CLOSED" else "OPEN",
                    color = if (session.closed) AurumColors.Red else AurumColors.Green,
                )
            }
            Text(
                "${session.nextChangeLabel}: ${formatDateTime(session.nextChangeAt)}",
                color = AurumColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                session.newYorkTimeLabel,
                color = AurumColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                session.detail,
                color = AurumColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        AutoPaperCard(viewModel, onJournal)

        SectionCard(
            title = "قیمت لحظه‌ای بازار · ${market.symbol}",
            subtitle = "فقط آخرین عدد واقعی دریافت‌شده؛ بدون سیگنال، خبر یا آمار اضافه",
            trailing = {
                Pill(
                    when {
                        price.current -> "زنده/تازه"
                        price.value != null -> "قبلی/کش"
                        else -> "بدون داده"
                    },
                    if (price.current) AurumColors.Cyan else AurumColors.Gold,
                )
            },
        ) {
            Text(
                formatPrice(price.value),
                style = MaterialTheme.typography.headlineMedium,
                color = if (price.current) AurumColors.TextPrimary else AurumColors.TextMuted,
            )
            Text(
                if (price.current) "${price.label} · دریافت ${formatDateTime(price.observedAt)}"
                else "${price.label} · آخرین مشاهده ${formatDateTime(price.observedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (price.current) AurumColors.Cyan else AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("BID", formatQuotePrice(displayBid), if (price.current) AurumColors.Green else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("ASK", formatQuotePrice(displayAsk), if (price.current) AurumColors.Red else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("SPREAD", formatSpread(spread), AurumColors.Gold, Modifier.weight(1f))
            }
            market.feed.detail.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}


/**
 * Automatic paper trading, front and centre.
 *
 * The engine was already opening paper trades on its own, but nothing on the home screen
 * said so, so the app looked idle while it was in fact working. Trust is built by showing
 * the running tally and, when nothing is being entered, the actual reason why.
 *
 * Everything here is simulated money against real prices. No broker is contacted.
 */
@Composable
private fun AutoPaperCard(viewModel: AurumViewModel, onJournal: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val status by viewModel.autoPaperStatus.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val enabled = settings.autoPaperTrading
    val lastTrade = trades.maxByOrNull { it.openedAt }

    SectionCard(
        title = "معاملهٔ خودکار کاغذی",
        subtitle = "با پول غیرواقعی روی قیمت واقعی — هیچ سفارشی به بروکر نمی‌رود",
        trailing = {
            Pill(
                if (enabled) "روشن" else "خاموش",
                if (enabled) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (enabled) "موتور خودش بازار را می‌بیند و معامله باز و بسته می‌کند."
                else "خاموش است؛ هیچ معاملهٔ خودکاری ثبت نمی‌شود.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            Switch(checked = enabled, onCheckedChange = viewModel::setAutoPaperTrading)
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("سرمایهٔ فرضی", "$" + settings.accountBalance.toInt(), modifier = Modifier.weight(1f))
            StatTile(
                "سود/ضرر",
                (if (stats.netPnl >= 0) "+" else "") + String.format("%.2f", stats.netPnl) + "$",
                if (stats.netPnl >= 0) AurumColors.Green else AurumColors.Red,
                Modifier.weight(1f),
            )
            StatTile("باز", stats.open.toString(), modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("معاملات", stats.total.toString(), modifier = Modifier.weight(1f))
            StatTile("برد/باخت", "${stats.wins}/${stats.losses}", modifier = Modifier.weight(1f))
            StatTile(
                "وین‌ریت",
                stats.winRate?.let { String.format("%.0f%%", it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        if (lastTrade != null) {
            Text(
                "آخرین معامله: ${lastTrade.symbol} · ${relativeTime(lastTrade.openedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // When the engine is NOT entering, say why. A silent screen is what makes an
        // automated system feel broken even when it is behaving correctly.
        if (enabled) {
            Text(
                "وضعیت الان: $status",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (stats.total < 100) {
            Text(
                "تا ${100 - stats.total} معاملهٔ دیگر، آمار بالا هنوز برای قضاوت دربارهٔ سودده بودن کافی نیست.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            "دیدن همهٔ معاملات در تب ژورنال",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Cyan,
            modifier = Modifier.padding(top = 8.dp).clickable { onJournal() },
        )
    }
}
