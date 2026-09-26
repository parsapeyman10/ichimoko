package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.PaperTicket
import com.aurum.edge.core.SignalAction
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.theme.AurumColors

private data class PendingTicket(
    val side: SignalAction, val stop: Double, val target: Double, val price: Double,
    val symbol: String, val preview: PaperTicket,
)

@Composable
fun PaperTicketSection(viewModel: AurumViewModel, market: MarketState) {
    var side by remember(market.symbol) { mutableStateOf(SignalAction.BUY) }
    var stopText by remember(market.symbol) { mutableStateOf("") }
    var targetText by remember(market.symbol) { mutableStateOf("") }
    var pending by remember(market.symbol) { mutableStateOf<PendingTicket?>(null) }
    val stop = stopText.trim().toDoubleOrNull()
    val target = targetText.trim().toDoubleOrNull()
    val result = viewModel.previewManualTicket(side, stop, target)
    val preview = result.getOrNull()

    SectionCard(
        title = "برگهٔ معاملهٔ دوطرفه · فقط کاغذی",
        subtitle = "ورود دستی بدون سیگنال؛ لانگ/شورت با قیمت دریافتی همین نماد، بدون ارسال سفارش",
    ) {
        Text("${market.symbol} · ورود تقریبی ${formatPrice(market.lastPrice)} · فقط جفت‌ارزهای /USD یا /USDT",
            style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = side == SignalAction.BUY, onClick = { side = SignalAction.BUY; pending = null },
                label = { Text("لانگ · BUY") }, modifier = Modifier.weight(1f))
            FilterChip(selected = side == SignalAction.SELL, onClick = { side = SignalAction.SELL; pending = null },
                label = { Text("شورت · SELL") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = stopText, onValueChange = { stopText = it; pending = null },
                label = { Text("حد ضرر SL") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = targetText, onValueChange = { targetText = it; pending = null },
                label = { Text("حد سود TP") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (preview == null) {
            Text("ورود متوقف: ${result.exceptionOrNull()?.message ?: "قیمت و حدود را وارد کنید"}",
                modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Red)
        } else {
            Text("حجم فرضی ${String.format("%.6f", preview.quantity)} ${preview.unit} · ارزش ${formatPrice(preview.notionalUsd)}$ · نسبت سود/ضرر ۱:${String.format("%.2f", preview.rewardRisk)}",
                modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextPrimary)
            Text("ریسک تا استاپ ${formatPrice(preview.actualRiskUsd)}$ از سقف ${formatPrice(preview.riskBudgetUsd)}$؛ مجموع ریسک پوزیشن‌ها حداکثر ۵٪ موجودی است.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        Button(onClick = {
            val price = market.lastPrice
            if (preview != null && stop != null && target != null && price != null) {
                pending = PendingTicket(side, stop, target, price, market.symbol, preview)
            }
        }, enabled = preview != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (side == SignalAction.BUY) "بررسی و ثبت لانگ کاغذی" else "بررسی و ثبت شورت کاغذی",
                fontWeight = FontWeight.Bold)
        }
        Text("حجم کسری صرفاً فرض پژوهشی است؛ حداقل لات، کارمزد، اسلیپیج و مارجین بروکر بررسی نشده‌اند. شورت spot Nobitex امکان‌پذیر فرض نمی‌شود. سقف ارزش فرضی: ۳× موجودی.",
            modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted)
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("تأیید ${if (request.side == SignalAction.BUY) "لانگ" else "شورت"} کاغذی") },
            text = { Text("${request.symbol} · قیمت تقریبی ${formatPrice(request.price)}$ · ${String.format("%.6f", request.preview.quantity)} ${request.preview.unit}\n" +
                "SL ${formatPrice(request.stop)} · TP ${formatPrice(request.target)} · ریسک ${formatPrice(request.preview.actualRiskUsd)}$.\n" +
                "اگر نماد تغییر کند یا قیمت بیش از ۰٫۱٪ جابه‌جا شود، ثبت لغو می‌شود. این سفارش واقعی نیست.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    viewModel.openManualPaperTrade(request.side, request.stop, request.target,
                        request.price, request.symbol)
                }) { Text("ثبت در ژورنال کاغذی") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("انصراف") } },
        )
    }
}
