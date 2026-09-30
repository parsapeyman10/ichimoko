package com.aurum.edge.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val session = MarketHours.sessionWindow(now)
    val price = HomeReadout.from(market, now)
    val spread = market.bid?.let { bid -> market.ask?.let { ask -> ask - bid } }

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
                StatTile("BID", formatQuotePrice(market.bid), if (price.current) AurumColors.Green else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("ASK", formatQuotePrice(market.ask), if (price.current) AurumColors.Red else AurumColors.TextMuted, Modifier.weight(1f))
                StatTile("SPREAD", formatSpread(spread), AurumColors.Gold, Modifier.weight(1f))
            }
            market.feed.detail.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
