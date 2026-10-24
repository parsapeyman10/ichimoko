package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.edge.data.PairScanStatus
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

/** XAU/USD pre-confirmation candidate monitor; the quality score is not a win probability. */
@Composable
internal fun Als5OpportunityMonitorCard(status: PairScanStatus?, sweeping: Boolean) {
    val candidate = status?.als5Opportunity
    val state = candidate?.state ?: if (sweeping) "WAIT" else "NO_TRADE"
    val color = when (state) {
        "BUY" -> AurumColors.Green
        "SELL" -> AurumColors.Red
        "WAIT" -> AurumColors.Gold
        else -> AurumColors.TextMuted
    }
    val stateLabel = when (state) {
        "BUY" -> "خریدِ تأییدشده"
        "SELL" -> "فروشِ تأییدشده"
        "WAIT" -> "در انتظار تکمیل"
        else -> "بدون معامله"
    }

    SectionCard(
        title = "پایش فرصت ALS-5 · طلا XAU/USD",
        subtitle = "سه موتور مستقل؛ وضعیت نامزدها پیش از ورود · امتیاز کیفیت ۰–۱۰، نه احتمال برد",
        trailing = { Pill(stateLabel, color) },
    ) {
        if (candidate == null) {
            Text(
                status?.detail ?: if (sweeping) "اسکن مستقل طلا در حال دریافت تاریخچهٔ M5 است."
                else "هنوز نتیجهٔ معتبر اسکن طلا موجود نیست؛ وضعیت ساختگی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
            return@SectionCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "رژیم: ${candidate.regime} · موتور: ${candidate.setupType} · امتیاز کیفیت: ${candidate.scoreOutOf10}/۱۰",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
            Text(
                "امتیازها ساختار ${candidate.structureScore}/۲ · سطح ${candidate.setupLevelScore}/۲ · " +
                    "تأیید ${candidate.confirmationScore}/۲ · R:R ${candidate.rrScore}/۲ · اجرا ${candidate.executionScore}/۲",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("سطح ${candidate.structuralLevel?.let(::formatPrice) ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text("ورود ${candidate.candidateEntry?.let(::formatPrice) ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Cyan)
                Text("SL ${candidate.estimatedStopLoss?.let(::formatPrice) ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Red)
                Text("TP ${candidate.estimatedTakeProfit?.let(::formatPrice) ?: "—"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
            Text(
                "R:R ناخالص ${candidate.rewardToRisk?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—"} · " +
                    "حداقل RR و شروط اجباری بر امتیاز مقدم‌اند · انقضا: ${candidate.expirationBarsRemaining} کندل M5 · " +
                    "کلید ستاپ: ${candidate.setupKey.ifBlank { "—" }}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
            if (candidate.satisfiedConditions.isNotEmpty()) {
                Text("تأییدشده: ${candidate.satisfiedConditions.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Green)
            }
            if (candidate.missingConditions.isNotEmpty()) {
                Text("مانده/نقص: ${candidate.missingConditions.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
            }
            if (candidate.invalidationLevel != null) {
                Text("ابطال ساختاری: ${formatPrice(candidate.invalidationLevel)} · زمان نامزد: " +
                    (candidate.candidateSinceBarTime?.let { formatDateTime(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
            status.detail.takeIf { it.isNotBlank() }?.let {
                Text("وضعیت اسکن: $it", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            }
        }
    }
}
