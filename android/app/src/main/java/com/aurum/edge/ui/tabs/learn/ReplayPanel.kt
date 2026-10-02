package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.ReplayDecision
import com.aurum.edge.engine.ReplayEngine
import com.aurum.edge.engine.ReplayEvaluation
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/** Native historical bar replay controls; every output ends at the cursor. */
@Composable
internal fun ReplayPanel(state: ReplayState, viewModel: AurumViewModel, decisions: List<ReplayDecision>) {
    when (state) {
        ReplayState.Idle -> Unit
        ReplayState.Loading -> SectionCard("آماده‌سازی Bar Replay", "دریافت و اعتبارسنجی همان دیتای بک‌تست…") {
            Text("تا پایان اعتبارسنجی، هیچ کندل تخمینی یا سیگنال موقت نمایش داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
        is ReplayState.Failed -> SectionCard("Replay آماده نشد", "منبع داده معتبر نیست") {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
        }
        is ReplayState.Ready -> ReadyReplayPanel(state.snapshot, viewModel, decisions)
    }
}

@Composable
private fun ReadyReplayPanel(snapshot: ReplayEngine.Snapshot, viewModel: AurumViewModel, decisions: List<ReplayDecision>) {
    val session = snapshot.session
    val report = snapshot.report
    SectionCard(
        title = "Bar Replay · ${session.interval.label} · ${session.symbol}",
        subtitle = "${session.dataSource} · دریافت ${session.providerFetchedAt.takeIf { it > 0L }?.let(::formatDateTime) ?: "—"} · gap واقعی ${session.observedGapCount} · فقط کندل‌های صفر تا cursor فعلی قابل مشاهده‌اند",
    ) {
        Text(
            "این آزمایشگاه آموزشی و paper-only است. با play، step یا seek، موتور SignalEngine همان لحظه فقط prefix داده را می‌بیند؛ کندل آینده، سیگنال آینده و fill قطعی به عقب برنمی‌گردد.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.TextSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { viewModel.resetReplay() }, modifier = Modifier.weight(1f)) {
                Text("Reset")
            }
            OutlinedButton(onClick = { viewModel.stepReplay(-1) }, modifier = Modifier.weight(1f)) {
                Text("قبلی")
            }
            Button(
                onClick = { viewModel.setReplayPlaying(!session.playing) },
                modifier = Modifier.weight(1.25f),
            ) {
                Text(if (session.playing) "Pause" else "Play")
            }
            OutlinedButton(onClick = { viewModel.stepReplay(1) }, modifier = Modifier.weight(1f)) {
                Text("گام +۱")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0.5f, 1.0f, 2.0f, 4.0f).forEach { speed ->
                FilterChip(
                    selected = session.speed == speed,
                    onClick = { viewModel.setReplaySpeed(speed) },
                    label = { Text("${speed}x") },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(onClick = { viewModel.setReplayStartCursor() }, modifier = Modifier.weight(1.8f)) {
                Text("شروع از اینجا")
            }
        }
        Slider(
            value = session.cursor.toFloat(),
            onValueChange = { viewModel.seekReplay(it.toInt()) },
            valueRange = 0f..session.lastCursor.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            "cursor ${session.cursor + 1}/${session.allBars.size} · شروع ${session.startCursor + 1} · ${snapshot.currentBar?.let { formatDateTime(it.time) } ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Cyan,
        )
        CandleChart(
            candles = snapshot.visibleBars,
            interval = session.interval,
            signal = snapshot.signal,
            modifier = Modifier.fillMaxWidth().height(310.dp).padding(top = 8.dp),
            showIchimoku = true,
            showLevels = true,
            showVolume = true,
        )
        Text(
            "۵ خط: T ${colorName("cyan")} · K ${colorName("purple")} · Span A سبز · Span B قرمز · Chikou طلایی؛ مقدار ناکافی null می‌ماند.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatTile("کندل قابل مشاهده", "${snapshot.visibleBars.size}", AurumColors.TextPrimary, Modifier.weight(1f))
            StatTile("معاملات بسته", "${report?.trades?.size ?: 0}", AurumColors.Green, Modifier.weight(1f))
            StatTile("پوزیشن باز", if (report?.openAtEnd == true) "۱" else "۰", AurumColors.Gold, Modifier.weight(1f))
        }
        snapshot.signal?.let { signal ->
            Text(
                "تصمیم cursor: ${signal.action.name} · اطمینان ${String.format("%.1f", signal.confidence)} · ${signal.entry?.let(::formatPrice) ?: "بدون ورود"}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = when (signal.action) {
                    com.aurum.edge.core.SignalAction.BUY -> AurumColors.Green
                    com.aurum.edge.core.SignalAction.SELL -> AurumColors.Red
                    com.aurum.edge.core.SignalAction.NO_TRADE -> AurumColors.TextSecondary
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                (signal.blockers.ifEmpty { signal.reasons }).take(3).joinToString(" · ").ifBlank { "در این cursor توضیح اضافی ثبت نشده است" },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        } ?: Text(
            "برای تصمیم‌گیری، حداقل ${com.aurum.edge.engine.SignalEngine.minBars(session.interval)} کندل بسته لازم است؛ warm-up بدون مقدار تخمینی ادامه دارد.",
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.Gold,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (snapshot.signal?.isActionable == true) {
            OutlinedButton(
                onClick = { viewModel.recordReplayDecision() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("ثبت تصمیم paper این کندل در ژورنال آموزشی")
            }
        }
        Text(
            "${if (snapshot.warmedUp) "نتیجهٔ قابل بررسی است" else "در warm-up هستیم"} · پایان replay ${if (snapshot.isAtEnd) "رسید" else "نرسیده"}. هیچ سفارش واقعی یا قیمت live از این پنل ارسال نمی‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "تست تعاملی paper-only: تصمیم روی cursor ثبت می‌شود، اما fill و نتیجه فقط با آشکارشدن کندل‌های بعدی تعیین می‌شوند؛ از دادهٔ آینده در لحظهٔ ثبت استفاده نمی‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Cyan,
            modifier = Modifier.padding(top = 6.dp),
        )
        decisions
            .filter { it.symbol == session.symbol && it.interval == session.interval.label && it.dataSource == session.dataSource }
            .take(5)
            .forEach { decision ->
                Text(
                    "${decision.action} · ${formatDateTime(decision.barTime)} · ${replayOutcomeLabel(decision.outcomeStatus)}" +
                        (decision.outcomePrice?.let { " · ${formatPrice(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (decision.outcomeStatus) {
                        ReplayEvaluation.WIN -> AurumColors.Green
                        ReplayEvaluation.LOSS -> AurumColors.Red
                        ReplayEvaluation.DATA_GAP -> AurumColors.Gold
                        else -> AurumColors.TextMuted
                    },
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
    }
}

private fun replayOutcomeLabel(status: String): String = when (status) {
    ReplayEvaluation.DECISION_ONLY -> "فقط تصمیم"
    ReplayEvaluation.PENDING_ENTRY -> "منتظر کندل ورود"
    ReplayEvaluation.OPEN -> "باز / در انتظار خروج"
    ReplayEvaluation.OPEN_AT_END -> "باز در پایان داده"
    ReplayEvaluation.WIN -> "موفق"
    ReplayEvaluation.LOSS -> "ناموفق"
    ReplayEvaluation.DATA_GAP -> "دادهٔ ناقص"
    ReplayEvaluation.NO_LEVELS -> "بدون سطوح خروج"
    else -> status
}

private fun colorName(value: String): String = value
