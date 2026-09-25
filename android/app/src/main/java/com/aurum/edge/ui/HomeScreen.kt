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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AlertCheckKind
import com.aurum.edge.core.AlertDiagnostics
import com.aurum.edge.core.HomeReadout
import com.aurum.edge.data.MarketState
import com.aurum.edge.notify.Notifier
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** Local overview: no backend and no new market/news verdicts are inferred here. */
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val news by viewModel.news.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val mtf by viewModel.mtf.collectAsStateWithLifecycle()
    val opportunityError by viewModel.opportunityError.collectAsStateWithLifecycle()
    val journalError by viewModel.journalError.collectAsStateWithLifecycle()
    val monitorRunning by SignalMonitorService.running.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000L); now = System.currentTimeMillis() } }
    val price = HomeReadout.from(market, now)
    val checks = AlertDiagnostics.checks(market, settings, news, monitorRunning,
        Notifier.canNotifyVerified(context, settings.alertSoundUri), trades, mtf,
        opportunityError, journalError, now)
    val newsCheck = checks.first { it.kind == AlertCheckKind.AI_NEWS }
    val monitorCheck = checks.first { it.kind == AlertCheckKind.MONITOR }
    val notifyCheck = checks.first { it.kind == AlertCheckKind.ANDROID_ALERT }
    val closedPaper = trades.count { !it.isOpen && it.pnlUsd != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 14.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
                .background(Brush.horizontalGradient(listOf(AurumColors.SurfaceAlt, AurumColors.Surface)),
                    RoundedCornerShape(18.dp))
                .border(1.dp, AurumColors.Gold.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Text("AURUM  /  EDGE", color = AurumColors.Gold,
                style = MaterialTheme.typography.labelMedium)
            Text("نمای کلی", color = AurumColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 5.dp))
            Text("وضعیت واقعی داده، هشدار و پژوهش روی همین گوشی · بدون سرور",
                color = AurumColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 5.dp))
        }

        SectionCard("بازار · ${market.symbol}", "هر عددی قیمت قابل اجرا یا تضمین معامله نیست",
            trailing = { Pill(if (price.current) "دریافت تازه" else "آفلاین/قدیمی",
                if (price.current) AurumColors.Cyan else AurumColors.Gold) }) {
            Text(formatPrice(price.value), style = MaterialTheme.typography.headlineMedium,
                color = if (price.current) AurumColors.TextPrimary else AurumColors.TextMuted)
            Text(if (price.current) "${price.label} · دریافت ${formatDateTime(price.observedAt)}"
                else "${price.label} · آخرین کندل ${formatDateTime(price.observedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (price.current) AurumColors.Cyan else AurumColors.Gold)
            if (!price.current && market.feed.detail.isNotBlank()) Text(market.feed.detail,
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 5.dp))
            Button(onClick = if (settings.hasKey) onChart else onSettings,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(if (settings.hasKey) "دیدن چارت و داده‌ها" else "ورود کلید خواندنی در تنظیمات")
            }
        }

        SectionCard("هشدار و خبر", "وضعیت تب خبرِ عمومی با گیت AI ورود، یکی نیست",
            trailing = { Pill(if (newsCheck.ready) "بررسی‌شده" else "تأیید نشده",
                if (newsCheck.ready) AurumColors.Green else AurumColors.Gold) }) {
            Text(if (settings.newsBaseUrl.isBlank())
                "بدون سرور، تیترهای واقعی را می‌توانی بخوانی؛ شرط نهم AI نامشخص است و هشدار ۹/۹/ورود خودکار کاغذی صادر نمی‌شود."
                else newsCheck.detail,
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            Text("پایش: ${if (monitorCheck.ready) "در حال اجرا" else "غیرفعال/متوقف"} · اعلان: ${if (notifyCheck.ready) "کانال باز" else "نیاز به بررسی"}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSignal, modifier = Modifier.weight(1f)) { Text("علت بی‌هشداری") }
                OutlinedButton(onClick = onNews, modifier = Modifier.weight(1f)) { Text("خبر واقعی") }
            }
        }

        SectionCard("پژوهش و ژورنال", "فقط نتایج ثبت‌شده؛ نه ادعای سود آینده") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("بستهٔ کاغذی", "$closedPaper", modifier = Modifier.weight(1f))
                StatTile("گزارش خارج نمونه", "${reports.size}", modifier = Modifier.weight(1f))
            }
            Text("معاملهٔ دستی، سیگنال ۹/۹ و بک‌تست نباید در یک آمارِ «سوددهی استراتژی» مخلوط شوند. هزینه‌ها و تعداد نمونه را در گزارش پژوهش بررسی کن.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLearn, modifier = Modifier.weight(1f)) { Text("پژوهش") }
                OutlinedButton(onClick = onJournal, modifier = Modifier.weight(1f)) { Text("ژورنال") }
            }
        }
        Text("سفارش واقعی غیرفعال است. نبود سرور یا دادهٔ تازه نباید با سیگنال ساختگی جبران شود.",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}
