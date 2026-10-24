package com.aurum.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.BtcAresEngineReport
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * BTC-ARES on the trade tab: the regime line, the risk line, and then **one card per engine**.
 *
 * The engines are deliberately not merged into a single score. Each card says which engine spoke,
 * in which state of the opportunity machine it is, how many of its own conditions passed, and what
 * it is still waiting for — so a WAIT from Engine A cannot look like a READY from Engine B, and an
 * engine with no data (F) says so instead of showing a zero.
 *
 * The score is a deterministic quality score out of 100. It is not a probability of winning.
 */
@Composable
internal fun BtcAresEnginesCard(status: PairScanStatus?, sweeping: Boolean) {
    val record = status?.btcAres
    val headline = when {
        record == null && sweeping -> "در حال ارزیابی بیت‌کوین"
        record == null -> "بدون دادهٔ BTC-ARES"
        record.isActionable -> "${record.action.name} · ${record.score}/100"
        record.conflicts.isNotEmpty() -> "تعارض موتورها — بدون ورود"
        else -> "بدون ورود · ${record.regimeLabelFa}"
    }
    val headlineColor = when {
        record?.action == com.aurum.edge.domain.SignalAction.BUY -> AurumColors.Green
        record?.action == com.aurum.edge.domain.SignalAction.SELL -> AurumColors.Red
        record?.conflicts?.isNotEmpty() == true -> AurumColors.Red
        record != null -> AurumColors.Gold
        else -> AurumColors.TextMuted
    }
    SectionCard(
        title = "BTC-ARES · بیت‌کوین BTC/USDT",
        subtitle = "پنج موتور قیمتی، جدا و مستقل · هر موتور وضعیت خودش را دارد · امتیاز کیفیت ۰–۱۰۰، نه احتمال برد",
        trailing = { Pill(headline, headlineColor) },
    ) {
        if (record == null) {
            Text(
                status?.detail ?: if (sweeping) "اسکن BTC/USDT در حال دریافت کندل بستهٔ M5 و قاب M15/H1 است."
                else "هنوز نتیجهٔ معتبر BTC-ARES موجود نیست؛ عدد ساختگی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
            )
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("رژیم: ${record.regimeLabelFa}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan)
            Text("نوسان: ${volLabel(record.volatilityState)} · ATR×${"%.2f".format(record.atrRatio)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("صدک ATR: ${"%.0f".format(record.atrPercentile * 100)}٪",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        if (!record.higherTimeframeAvailable) {
            Text("قاب M15/H1 کامل نیست؛ موتورهایی که به آن نیاز دارند غیرفعال‌اند (حدس زده نمی‌شود).",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (record.isActionable) {
            Text(
                "ورود ${record.entry?.let(::formatPrice) ?: "—"} · SL ${record.stopLoss?.let(::formatPrice) ?: "—"} · " +
                    "TP ${record.takeProfit?.let(::formatPrice) ?: "—"} · " +
                    "RR خام ${"%.2f".format(record.rewardToRisk ?: 0.0)} · " +
                    "خالص ${"%.2f".format(record.netRewardRisk ?: 0.0)} (پس از هزینه)",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Green,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text("حجم ${"%.5f".format(record.positionQty)} BTC · ریسک ${"%.2f".format(record.riskUsd)} دلار " +
                "(۰٫۲۵٪ سرمایه) · کلید ستاپ ${record.setupKey.takeLast(24)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 2.dp))
        }
        if (record.conflicts.isNotEmpty()) {
            Text(record.conflicts.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red, modifier = Modifier.padding(top = 4.dp))
        }
        if (!record.isActionable && record.noTradeReasons.isNotEmpty()) {
            Text("دلیل عدم ورود: ${record.noTradeReasons.joinToString(" · ").take(260)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp))
        }

        record.engines.forEach { engine ->
            EngineRow(engine, chosen = engine.engine == record.chosenEngine)
        }

        record.dataNotes.forEach {
            Text("• $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EngineRow(engine: BtcAresEngineReport, chosen: Boolean) {
    val color = when {
        !engine.dataAvailable -> AurumColors.TextMuted
        engine.state == "READY" || engine.state == "ACTIVE" -> when (engine.direction) {
            com.aurum.edge.domain.SignalAction.BUY -> AurumColors.Green
            com.aurum.edge.domain.SignalAction.SELL -> AurumColors.Red
            else -> AurumColors.Gold
        }
        engine.state == "INVALIDATED" -> AurumColors.Red
        engine.state == "FORMING" || engine.state == "CONFIRMATION" -> AurumColors.Gold
        else -> AurumColors.TextSecondary
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(AurumColors.SurfaceAlt, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(engine.labelFa, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal, color = color)
            Pill(
                when {
                    !engine.dataAvailable -> "داده نیست"
                    engine.state == "READY" && engine.direction != com.aurum.edge.domain.SignalAction.NO_TRADE ->
                        "${stateLabel(engine.state)} · ${engine.direction.name}"
                    else -> stateLabel(engine.state)
                },
                color,
            )
        }
        Text("امتیاز ${engine.score}/100" + if (chosen) " · موتور انتخاب‌شده" else "",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        if (engine.entry != null && engine.state == "READY") {
            Text("ورود ${formatPrice(engine.entry!!)} · SL ${engine.stopLoss?.let(::formatPrice) ?: "—"} · " +
                "TP ${engine.takeProfit?.let(::formatPrice) ?: "—"} · RR خام ${"%.2f".format(engine.rewardToRisk ?: 0.0)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
        }
        if (engine.satisfied.isNotEmpty()) {
            Text("✓ ${engine.satisfied.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Green)
        }
        if (engine.missing.isNotEmpty()) {
            Text("… ${engine.missing.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold)
        }
        if (engine.detail.isNotBlank()) {
            Text(engine.detail, style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    "IDLE" -> "بی‌کار"
    "WATCH" -> "زیر نظر"
    "FORMING" -> "در حال شکل‌گیری"
    "CONFIRMATION" -> "در انتظار تأیید"
    "READY" -> "آماده"
    "ACTIVE" -> "فعال"
    "INVALIDATED" -> "باطل شد"
    "EXPIRED" -> "منقضی شد"
    "UNAVAILABLE" -> "غیرفعال (بدون داده)"
    else -> state
}

private fun volLabel(state: String): String = when (state) {
    "LOW" -> "کم"
    "NORMAL" -> "عادی"
    "ELEVATED" -> "بالا"
    "EXTREME" -> "بحرانی"
    else -> state
}
