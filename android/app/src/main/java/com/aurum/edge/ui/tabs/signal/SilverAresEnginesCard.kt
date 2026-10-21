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
import com.aurum.edge.domain.SignalAction
import com.aurum.edge.core.XagAresEngineReport
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/**
 * ALS-5S on the trade tab: regime, cost line, and **one card per engine**.
 *
 * Silver's card differs from gold's and BTC's on purpose:
 *  - the execution line shows cost per ounce, cost ÷ stop distance, and the *net* R:R, because
 *    silver's spread is what decides whether a setup is tradeable at all;
 *  - the session and the silver/gold module are their own rows; the module can say UNAVAILABLE
 *    without stopping the system;
 *  - engine C appears twice (range reversion and volatility breakout), because the two behaviours
 *    have different invalidation and must never be collapsed into one number.
 *
 * The 0–10 score is a deterministic quality score. It is not a probability of winning.
 */
@Composable
internal fun SilverAresEnginesCard(status: PairScanStatus?, sweeping: Boolean) {
    val record = status?.als5s
    val headline = when {
        record == null && sweeping -> "در حال ارزیابی نقره"
        record == null -> "بدون دادهٔ ALS-5S"
        record.isActionable -> "${record.action.name} · ${record.scoreOutOf10}/10"
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
        title = "ALS-5S · نقره XAG/USD",
        subtitle = "دو موتور مستقل و دو حالت جدا برای C · امتیاز کیفیت ۰–۱۰ (نه احتمال برد) · هزینهٔ رفت‌وبرگشت گیت ورود است",
        trailing = { Pill(headline, headlineColor) },
    ) {
        if (record == null) {
            Text(
                status?.detail ?: if (sweeping) "اسکن XAG/USD در حال دریافت کندل بستهٔ M5 و قاب M15/H1 است."
                else "هنوز نتیجهٔ معتبر ALS-5S موجود نیست؛ عدد ساختگی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
            )
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("رژیم: ${record.regimeLabelFa}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan)
            Text("نوسان: ${volLabel(record.volatilityState)} · ATR×${"%.2f".format(record.atrRatio)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextSecondary)
            Text("گسترش ${"%.2f".format(record.rangeExpansion)}×",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        Text("سشن: ${record.sessionLabelFa} · صدک ATR ${"%.0f".format(record.atrPercentile * 100)}٪",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 2.dp))
        if (!record.higherTimeframeAvailable) {
            Text("قاب M15 کامل نیست؛ موتور A غیرفعال است و حدس زده نمی‌شود.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (!record.goldContext.available) {
            Text("ماژول طلا/نقره: خاموش — ${record.goldContext.noteFa}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (record.isActionable) {
            Text(
                "ورود ${record.entry?.let(::formatPrice) ?: "—"} · SL ${record.stopLoss?.let(::formatPrice) ?: "—"} · " +
                    "TP ${record.takeProfit?.let(::formatPrice) ?: "—"} · " +
                    "RR خام ${"%.2f".format(record.rewardToRisk ?: 0.0)} · " +
                    "RR خالص ${"%.2f".format(record.netRewardRisk ?: 0.0)}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Green,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text("حجم ${"%.2f".format(record.positionOz)} اونس · ریسک ${
                "%.2f".format(record.riskUsd)} دلار " +
                "(سقف ۰٫۲۵٪ سرمایه) · کلید ستاپ ${record.setupKey.takeLast(22)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 2.dp))
        }
        Text("هزینهٔ رفت‌وبرگشت ${"%.3f".format(record.costPerOzUsd)} دلار/انس · " +
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
private fun EngineRow(engine: XagAresEngineReport, chosen: Boolean) {
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
                        "${stateLabel(engine.state)} · ${engine.direction.name}"
                    else -> stateLabel(engine.state)
                },
                color,
            )
        }
        Text("امتیاز ${engine.scoreOutOf10}/10" +
            " (${engine.regimeScore}+${engine.structureScore}+${engine.confirmationScore}+" +
            "${engine.rrScore}+${engine.executionScore})" +
            if (chosen) " · موتور انتخاب‌شده" else "",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        if (engine.entry != null && engine.state == "READY") {
            Text("ورود ${formatPrice(engine.entry!!)} · SL ${engine.stopLoss?.let(::formatPrice) ?: "—"} · " +
                "TP ${engine.takeProfit?.let(::formatPrice) ?: "—"} · " +
                "RR خام ${"%.2f".format(engine.rewardToRisk ?: 0.0)} · " +
                "خالص ${"%.2f".format(engine.netRewardRisk ?: 0.0)}",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
        }
        if (engine.costPerOzUsd > 0.0) {
            Text("هزینه ${"%.3f".format(engine.costPerOzUsd)} دلار/انس = " +
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
