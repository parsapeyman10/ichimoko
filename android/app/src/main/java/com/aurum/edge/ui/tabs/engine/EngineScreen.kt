package com.aurum.edge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AppSettings
import com.aurum.edge.data.EngineScalp
import com.aurum.edge.data.EngineUiState
import com.aurum.edge.ui.components.EmptyState
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors

/**
 * OPTIONAL server engine.
 *
 * Everything on this screen comes from a backend the user runs themselves. It is additive:
 * the phone's own Ichimoku engine on the other tabs is untouched and keeps working with no
 * server at all. What the server adds is what the phone genuinely cannot do on its own —
 * the full multi-symbol universe, position sizing that knows each venue's real lot step and
 * minimum notional, and an autonomous paper trader that keeps running when the app is shut.
 *
 * The autopilot here opens PAPER positions on the server. The backend's real-order endpoint
 * is fail-closed, so nothing on this screen can reach a broker.
 */

private val TIMEFRAMES = listOf("1m", "3m", "5m", "15m")

@Composable
fun EngineScreen(viewModel: AurumViewModel, settings: AppSettings) {
    val state by viewModel.engine.collectAsStateWithLifecycle()
    val configured = settings.engineBaseUrl.isNotBlank()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServerCard(viewModel, settings, state)
        if (!configured) {
            EmptyState(
                title = "موتور سرور خاموش است",
                message = "این بخش اختیاری است. بدون آن، موتور داخلی گوشی مثل همیشه کار می‌کند. " +
                    "برای روشن‌کردنش، بک‌اند پایتون این پروژه را اجرا کنید و نشانی https آن را بالا وارد کنید.",
            )
            return@Column
        }
        state.error?.let { message ->
            SectionCard(title = "خطا") {
                Text(message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
                OutlinedButton(onClick = viewModel::engineClearError, modifier = Modifier.padding(top = 8.dp)) {
                    Text("بستن")
                }
            }
        }
        SignalCard(viewModel, state)
        AutopilotCard(viewModel, state)
    }
}

@Composable
private fun ServerCard(viewModel: AurumViewModel, settings: AppSettings, state: EngineUiState) {
    var url by rememberSaveable(settings.engineBaseUrl) { mutableStateOf(settings.engineBaseUrl) }
    SectionCard(title = "اتصال به سرور") {
        Text(
            "نشانی باید https باشد و بدون مسیر یا پارامتر. برای اجرای محلی روی رایانه، از یک " +
                "تونل https (مثل cloudflared یا ngrok) استفاده کنید؛ http فقط برای localhost پذیرفته می‌شود.",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("https://my-host.example") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { viewModel.saveEngineBaseUrl(url) }, enabled = !state.connecting) {
                Text(if (state.connecting) "در حال بررسی…" else "ذخیره و اتصال")
            }
            if (settings.engineBaseUrl.isNotBlank()) {
                OutlinedButton(onClick = { url = ""; viewModel.saveEngineBaseUrl("") }) { Text("قطع") }
            }
            if (state.connected) Pill("متصل", AurumColors.Green)
        }
    }
}

@Composable
private fun SignalCard(viewModel: AurumViewModel, state: EngineUiState) {
    var symbol by rememberSaveable { mutableStateOf("BTCUSDT") }
    var timeframe by rememberSaveable { mutableStateOf("5m") }
    var query by rememberSaveable { mutableStateOf("") }

    SectionCard(title = "سیگنال چندنمادی") {
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("جستجوی نماد") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { viewModel.engineSearchInstruments("all", query) }) { Text("جستجو") }
        }
        if (state.loadingInstruments) {
            CircularProgressIndicator(Modifier.padding(top = 8.dp))
        }
        if (state.instruments.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 190.dp).padding(top = 8.dp)) {
                items(state.instruments, key = { it.symbol }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { symbol = item.symbol }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.display, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.symbol == symbol) AurumColors.Gold else AurumColors.TextPrimary,
                        )
                        Text(item.kind, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                        Pill(
                            if (item.scalpEnabled) "اسکلپ" else "فقط نمایش",
                            if (item.scalpEnabled) AurumColors.Green else AurumColors.TextMuted,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = symbol, onValueChange = { symbol = it.uppercase() },
            label = { Text("نماد انتخاب‌شده") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TIMEFRAMES.forEach { frame ->
                FilterChip(
                    selected = frame == timeframe,
                    onClick = { timeframe = frame },
                    label = { Text(frame) },
                )
            }
        }
        Button(
            onClick = { viewModel.engineLoadSignal(symbol, timeframe) },
            enabled = !state.loadingSignal && symbol.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (state.loadingSignal) "در حال محاسبه…" else "دریافت سیگنال و حجم") }

        state.scalp?.let { ScalpDetail(it) }
    }
}

@Composable
private fun ScalpDetail(scalp: EngineScalp) {
    fun price(value: Double?): String =
        value?.let { String.format("%.${scalp.pricePrecision}f", it) } ?: "—"

    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!scalp.ok) {
            Text(scalp.error ?: "دادهٔ کافی نیست", style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scalp.display, style = MaterialTheme.typography.titleSmall, color = AurumColors.TextPrimary)
            Pill(
                when (scalp.action) { "BUY" -> "خرید"; "SELL" -> "فروش"; else -> "بدون ورود" },
                when (scalp.action) { "BUY" -> AurumColors.Green; "SELL" -> AurumColors.Red
                    else -> AurumColors.TextMuted },
            )
            Text(
                "امتیاز ${scalp.confidence.toInt()}" + (scalp.threshold?.let { " / ${it.toInt()}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            )
        }
        scalp.policyBlock?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AurumColors.Orange)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("ورود", price(scalp.entry), modifier = Modifier.weight(1f))
            StatTile("حد ضرر", price(scalp.stopLoss), AurumColors.Red, Modifier.weight(1f))
            StatTile("حد سود", price(scalp.takeProfit), AurumColors.Green, Modifier.weight(1f))
        }

        val sizing = scalp.sizing
        if (sizing != null && sizing.tradable) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("حجم", "${sizing.units ?: 0.0} ${sizing.unitLabel}", modifier = Modifier.weight(1f))
                StatTile("ریسک", "${sizing.riskCash ?: 0.0}$ (${sizing.riskPct ?: 0.0}%)",
                    AurumColors.Red, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("اهرم", "${sizing.leverage ?: 0.0}x", modifier = Modifier.weight(1f))
                StatTile("هزینهٔ اجرا", "${sizing.executionCost ?: 0.0}$", AurumColors.Orange, Modifier.weight(1f))
                StatTile("وین‌ریت سربه‌سر", "${sizing.breakevenWinRate ?: 0.0}%", modifier = Modifier.weight(1f))
            }
            sizing.warnings.forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.Orange)
            }
        } else if (sizing != null) {
            Text(
                sizing.reason ?: "با این موجودی قابل معامله نیست",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Orange,
            )
            sizing.minBalanceNeeded?.let {
                Text("حداقل موجودی لازم ≈ ${it}$", style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted)
            }
        }

        if (scalp.reasoning.isNotEmpty()) {
            Text("چرا این درصد ریسک؟", style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
            scalp.reasoning.forEach { step ->
                Text("• ${step.factor}: ${step.effect} — ${step.why}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
        if (scalp.blockers.isNotEmpty()) {
            Text("موانع", style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
            scalp.blockers.take(6).forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
        if (scalp.exitRules.isNotEmpty()) {
            Text("پلن خروج", style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
            scalp.exitRules.forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}

@Composable
private fun AutopilotCard(viewModel: AurumViewModel, state: EngineUiState) {
    val auto = state.autopilot
    SectionCard(title = "تریدر خودکار (کاغذی)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.engineAutopilotAction(if (auto?.running == true) "stop" else "start") },
                enabled = !state.loadingAutopilot) {
                Text(if (auto?.running == true) "توقف" else "شروع")
            }
            OutlinedButton(onClick = { viewModel.engineAutopilotAction("cycle") }, enabled = !state.loadingAutopilot) {
                Text("یک سیکل")
            }
            OutlinedButton(onClick = viewModel::engineRefreshAutopilot, enabled = !state.loadingAutopilot) {
                Text("تازه‌سازی")
            }
        }

        if (auto == null) {
            Text("برای دیدن وضعیت، «تازه‌سازی» را بزنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp))
            return@SectionCard
        }

        Text(
            "حالت اجرا: کاغذی — ${auto.executionNote}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Orange,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("موجودی", "${auto.balance}$", modifier = Modifier.weight(1f))
            StatTile("بازده", "${auto.returnPct}%",
                if (auto.returnPct >= 0) AurumColors.Green else AurumColors.Red, Modifier.weight(1f))
            StatTile("معاملات", "${auto.trades}", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("وین‌ریت", auto.winRatePct?.let { "$it%" } ?: "—", modifier = Modifier.weight(1f))
            StatTile("میانگین R", auto.avgR?.toString() ?: "—", modifier = Modifier.weight(1f))
            StatTile("حداکثر افت", "${auto.maxDrawdownPct}%", AurumColors.Red, Modifier.weight(1f))
        }
        if (auto.confidenceNote.isNotBlank()) {
            Text(auto.confidenceNote, style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
        }

        if (auto.positions.isNotEmpty()) {
            Text("پوزیشن‌های باز", style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(top = 10.dp))
            auto.positions.forEach { position ->
                Text(
                    "${position.display} · ${if (position.side == "BUY") "خرید" else "فروش"} · " +
                        "حجم ${position.qty} · ورود ${position.entry} · شناور ${position.unrealised}$",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                )
            }
        }
        if (auto.log.isNotEmpty()) {
            Text("گزارش تصمیم‌ها", style = MaterialTheme.typography.labelMedium,
                color = AurumColors.TextPrimary, modifier = Modifier.padding(top = 10.dp))
            auto.log.takeLast(12).reversed().forEach { line ->
                Text("• ${line.message}", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}
