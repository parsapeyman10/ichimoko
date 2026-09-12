package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.MarketState
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.theme.AurumColors
import kotlin.math.abs

@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signal = market.signal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SignalSummaryCard(
            signal = signal,
            onOpenPaperTrade = { signal?.let(viewModel::openPaperTrade) },
        )

        signal?.let { s ->
            SectionCard(
                title = "همگرایی ۸ شرط مستقل",
                subtitle = "حداقل امتیاز قابل معامله: ${settings.minConfidence.toInt()} — کندل ${s.interval.label} · ${formatTime(s.barTime)}",
            ) {
                if (s.confluence.isEmpty()) {
                    Text("داده کافی برای نمایش جزئیات نیست", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                } else {
                    s.confluence.forEach { ConfluenceRow(it) }
                }
            }

            if (s.isActionable) {
                SectionCard(
                    title = "حجم پیشنهادی (بر پایه ریسک واقعی)",
                    subtitle = "موجودی ${formatPrice(settings.accountBalance)}$ · ریسک ${settings.riskPercent}%",
                ) {
                    val entry = s.entry ?: 0.0
                    val stop = s.stopLoss ?: 0.0
                    val stopDistance = abs(entry - stop)
                    val riskUsd = settings.accountBalance * settings.riskPercent / 100.0
                    val exactOz = if (stopDistance > 0) riskUsd / stopDistance else 0.0
                    val minLotOz = 1.0
                    val executableOz = kotlin.math.max(exactOz, minLotOz)
                    val actualRisk = executableOz * stopDistance
                    val actualRiskPercent = if (settings.accountBalance > 0) actualRisk / settings.accountBalance * 100.0 else 0.0

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatTile("فاصله استاپ", "${formatPrice(stopDistance)}$", AurumColors.TextPrimary, Modifier.weight(1f))
                        StatTile("ریسک هدف", "${formatPrice(riskUsd)}$", AurumColors.Gold, Modifier.weight(1f))
                        StatTile("حجم دقیق", "${String.format("%.3f", exactOz)} oz", AurumColors.TextSecondary, Modifier.weight(1f))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        StatTile("حداقل لات (1 oz)", "${String.format("%.2f", minLotOz)} oz", AurumColors.TextSecondary, Modifier.weight(1f))
                        StatTile("ریسک اجراشدنی", "${formatPrice(actualRisk)}$", AurumColors.Red, Modifier.weight(1f))
                        StatTile("درصد واقعی", "${String.format("%.2f", actualRiskPercent)}%", AurumColors.Red, Modifier.weight(1f))
                    }
                    Text(
                        if (exactOz < minLotOz) {
                            "هشدار صادقانه: با موجودی فعلی، حجم دقیق (${String.format("%.3f", exactOz)} انس) زیر حداقل لات بروکر (0.01 لات = 1 انس) است. پس کوچک‌ترین معامله ممکن ${formatPrice(actualRisk)}$ ریسک دارد که ${String.format("%.2f", actualRiskPercent)}% حساب است. یا موجودی را بیشتر کن یا ریسک را بپذیر — عدد جعلی نشان نمی‌دهیم."
                        } else {
                            "حجم محاسبه‌شده با حداقل لات بروکر سازگار است."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } ?: SectionCard(
            title = "سیگنال در دسترس نیست",
            subtitle = "موتور تنها روی کندل‌های واقعی بسته اجرا می‌شود",
        ) {
            Text(
                "تا وقتی تعداد کندل بسته واقعی کافی دریافت نشود (حداقل ۲۱۰ کندل)، هیچ سیگنالی ساخته نمی‌شود. عمداً هیچ سیگنال نمایشی تولید نمی‌کنیم.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary,
            )
        }

        SectionCard(
            title = "قواعد اجرا",
            subtitle = "قوانین ثابت موتور — بدون استثنا برای «زنده نگه داشتن» نمایش",
        ) {
            listOf(
                "ورود فقط روی کندل بسته؛ کراس تازه تنکان/کیجون الزامی است.",
                "قیمت باید حداقل 0.08×ATR فراتر از ابر کومو باشد و ابر آینده هم‌جهت باشد.",
                "چیکو، EMA200 و VWAP جلسه باید تایید کنند؛ RSI7 در ناحیه 52–72 (خرید) یا 28–48 (فروش).",
                "شوک نوسان (ATR بیش از ۲.۵ برابر میانه) یا اسپرد غیرعادی = توقف کامل ورود.",
                "حد ضرر ساختاری ۰.۹ تا ۱.۴ ATR و حد سود ۱.۸R (۲.۰R در امتیاز ۸۵+) — بدون جابه‌جایی استاپ.",
                "خروج: SL/TP، شکست کیجون روی کندل بسته، کراس مخالف، یا پایان زمان مجاز.",
            ).forEach { rule ->
                Text("• $rule", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
