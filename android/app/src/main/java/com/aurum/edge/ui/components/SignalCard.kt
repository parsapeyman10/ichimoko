package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.Signal
import com.aurum.edge.core.SignalAction
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SignalSummaryCard(
    signal: Signal?,
    onOpenPaperTrade: () -> Unit,
    modifier: Modifier = Modifier,
    showBlockers: Boolean = true,
    entryBlocker: String? = null,
    allowManualPaperTrade: Boolean = true,
) {
    val action = signal?.action ?: SignalAction.NO_TRADE
    val color = when (action) {
        SignalAction.BUY -> AurumColors.Green
        SignalAction.SELL -> AurumColors.Red
        SignalAction.NO_TRADE -> AurumColors.TextSecondary
    }
    val title = when (action) {
        SignalAction.BUY -> "خرید (BUY)"
        SignalAction.SELL -> "فروش (SELL)"
        SignalAction.NO_TRADE -> "عدم ورود (NO TRADE)"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(AurumColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("موتور تلفیقی ایچیموکو", style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
                Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            }
            Pill(
                text = if (signal == null) "بدون داده" else "امتیاز ${signal.confidence.toInt()}/100",
                color = color,
            )
        }

        if (signal == null) {
            Text("—", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp))
            return@Column
        }

        LinearProgressIndicator(
            progress = { (signal.confidence / 100.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            trackColor = AurumColors.SurfaceAlt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )

        if (signal.isActionable) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("ورود", formatPrice(signal.entry), AurumColors.Gold, Modifier.weight(1f))
                StatTile("حد ضرر", formatPrice(signal.stopLoss), AurumColors.Red, Modifier.weight(1f))
                StatTile("حد سود", formatPrice(signal.takeProfit), AurumColors.Green, Modifier.weight(1f))
                StatTile("R:R", "1:${String.format("%.1f", signal.riskReward ?: 0.0)}", AurumColors.TextPrimary, Modifier.weight(1f))
            }
            if (signal.reasons.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    signal.reasons.take(4).forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall, color = AurumColors.Green)
                    }
                }
            }
            entryBlocker?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Red, modifier = Modifier.padding(top = 8.dp))
            }
            if (allowManualPaperTrade) {
                Button(
                    onClick = onOpenPaperTrade,
                    enabled = entryBlocker == null,
                    colors = ButtonDefaults.buttonColors(containerColor = AurumColors.Gold, contentColor = Color(0xFF14100A)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text("باز کردن پوزیشن کاغذی روی قیمت واقعی", fontWeight = FontWeight.Bold)
                }
                Text(
                    "این «دمو» روی قیمت واقعی بازار اجرا می‌شود؛ سود/زیان با قیمت‌های بعدی همان بازار تسویه می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
