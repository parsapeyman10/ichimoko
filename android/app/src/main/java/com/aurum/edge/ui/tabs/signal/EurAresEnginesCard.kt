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
import com.aurum.edge.core.EurAresEngineReport
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * EUR-ARES on the trade tab: regime, session clocks, pip-based economics and **one card per engine**.
 *
 * The euro-dollar card differs from gold's, silver's and BTC's on purpose:
 *  - every distance is shown in **pips** (a 0.1-pip spread is 1 % of a 10-pip stop, so it is the
 *    number that decides the trade), together with the lot size and the pip value;
 *  - the session row shows the London/New York **local clocks** and whether the rollover window is
 *    active, because a fixed UTC label is wrong for half the year;
 *  - engine E says "no data" instead of inventing a news window, and module X stays off unless a
 *    measured macro series was actually supplied.
 *
 * The 0–100 score is a deterministic quality score. It is not a probability of winning.
 */
@Composable
internal fun EurAresEnginesCard(status: PairScanStatus?, sweeping: Boolean) {
    val record = status?.eurAres
    val headline = when {
        record == null && sweeping -> "در حال ارزیابی یورو/دلار"
        record == null -> "بدون دادهٔ EUR-ARES"
        record.isActionable -> "${record.action.name} · ${record.scoreOutOf100}/100"
        record.conflicts.isNotEmpty() -> "تعارض موتورها — بدون ورود"
        else -> "بدون ورود · ${record.regimeLabelFa}"
    }
    val headlineColor = when {
        record?.action == SignalAction.BUY -> AurumColors.Green
        record?.action == SignalAction.SELL -> AurumColors.Red
        record?.conflicts?.isNotEmpty() == true -> AurumColors.Red
        record != null -> AurumColors.Gold
        else -> AurumColors.TextMuted
    }
    SectionCard(
        title = "EUR-ARES · یورو/دلار EUR/USD",
        subtitle = "پنج موتور مستقل + ماژول کلان (خاموش تا داده نباشد) · امتیاز کیفیت ۰–۱۰۰ (نه احتمال برد) · " +
            "هزینه و حد ضرر پیپ‌محور",
        trailing = { Pill(headline, headlineColor) },
    ) {
        if (record == null) {
            Text(
                status?.detail ?: if (sweeping) "اسکن EUR/USD در حال دریافت کندل بستهٔ M5 و قاب H1/H4/D1 است."
                else "هنوز نتیجهٔ معتبر EUR-ARES موجود نیست؛ عدد ساختگی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
            )
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("رژیم: ${record.regimeLabelFa}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan)
            Text("نوسان: ${eurVolLabel(record.volatilityState)} · ATR×${"%.2f".format(record.atrRatio)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("ATR ${"%.1f".format(record.atrPips)} پیپ",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Text(
            "سشن: ${record.sessionLabelFa} · لندن ${record.sessionInfo.londonClock} · " +
                "نیویورک ${record.sessionInfo.newYorkClock}" +
                (if (record.sessionInfo.rolloverWindow) " · پنجرهٔ رول‌اور" else "") +
                " · نوسان همین سشن ${"%.2f".format(record.sessionInfo.sessionVolRatio)}×",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "قاب‌ها: ${if (record.htfAvailable) "H1 " + record.htfBias else "H1 بدون داده"} · " +
                "D1 ${if (record.d1Available) record.d1Bias else "بدون داده"}",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (!record.macro.available) {
            Text("ماژول کلان/کراس‌مارکت: خاموش — ${record.macro.noteFa}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (record.isActionable) {
            Text(
                "ورود ${record.entry?.let(::formatPrice) ?: "—"} · SL ${record.stopLoss?.let(::formatPrice) ?: "—"} · " +
                    "TP ${record.takeProfit?.let(::formatPrice) ?: "—"} · " +
                    "حد ضرر ${"%.1f".format(record.stopPips ?: 0.0)} پیپ · " +
                    "RR خام ${"%.2f".format(record.rewardToRisk ?: 0.0)} · " +
                    "خالص ${"%.2f".format(record.netRewardRisk ?: 0.0)}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Green,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "حجم ${"%.2f".format(record.positionLots)} لات (${"%.0f".format(record.positionUnits)} واحد) · " +
                    "ارزش هر پیپ ${"%.2f".format(record.pipValueUsd)} دلار · " +
                    "ریسک ${"%.2f".format(record.riskUsd)} دلار (سقف ۰٫۲۵٪) · " +
                    "کلید ستاپ ${record.setupKey.takeLast(22)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text("هزینهٔ رفت‌وبرگشت ${"%.2f".format(record.costPips)} پیپ · " +
            "هزینه÷ریسک ${"%.0f".format(record.costToRiskFraction * 100)}٪",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp))
        if (record.conflicts.isNotEmpty()) {
            Text(record.conflicts.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Red, modifier = Modifier.padding(top = 4.dp))
        }
        if (!record.isActionable && record.noTradeReasons.isNotEmpty()) {
            Text("دلیل عدم ورود: ${record.noTradeReasons.joinToString(" · ").take(280)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp))
        }

        record.engines.forEach { engine -> EngineRow(engine, chosen = engine.engine == record.chosenEngine) }

        record.dataNotes.forEach {
            Text("• $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun EngineRow(engine: EurAresEngineReport, chosen: Boolean) {
    val color = when {
        !engine.dataAvailable -> AurumColors.TextMuted
        engine.state == "READY" || engine.state == "ACTIVE" -> when (engine.direction) {
            SignalAction.BUY -> AurumColors.Green
            SignalAction.SELL -> AurumColors.Red
            else -> AurumColors.Gold
        }
        engine.state == "INVALIDATED" -> AurumColors.Red
        engine.state == "FORMING" || engine.state == "CONFIRMATION" -> AurumColors.Gold
        engine.state == "WATCH" -> AurumColors.Cyan
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
                    engine.state == "READY" && engine.direction != SignalAction.NO_TRADE ->
                        "${eurStateLabel(engine.state)} · ${engine.direction.name}"
                    else -> eurStateLabel(engine.state)
                },
                color,
            )
        }
        Text(
            "امتیاز ${engine.scoreOutOf100}/100 " +
                "(${engine.regimeScore}+${engine.structureScore}+${engine.entryScore}+" +
                "${engine.confirmationScore}+${engine.volatilityScore}+${engine.sessionScore}+" +
                "${engine.rrScore}+${engine.costScore})" +
                if (chosen) " · موتور انتخاب‌شده" else "",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
        )
        if (engine.entry != null && engine.state == "READY") {
            Text(
                "ورود ${formatPrice(engine.entry!!)} · SL ${engine.stopLoss?.let(::formatPrice) ?: "—"} · " +
                    "TP ${engine.takeProfit?.let(::formatPrice) ?: "—"} · " +
                    "حد ضرر ${"%.1f".format(engine.stopPips ?: 0.0)} پیپ · " +
                    "RR خام ${"%.2f".format(engine.rewardToRisk ?: 0.0)} · " +
                    "خالص ${"%.2f".format(engine.netRewardRisk ?: 0.0)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan,
            )
        }
        if (engine.costPips > 0.0) {
            Text("هزینه ${"%.2f".format(engine.costPips)} پیپ = " +
                "${"%.0f".format(engine.costToRiskFraction * 100)}٪ حد ضرر",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
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

private fun eurVolLabel(state: String): String = when (state) {
    "LOW" -> "کم"
    "NORMAL" -> "عادی"
    "TRANSITION" -> "در گذار"
    "ELEVATED" -> "بالا"
    "EXTREME" -> "بحرانی"
    else -> state
}

private fun eurStateLabel(state: String): String = when (state) {
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
