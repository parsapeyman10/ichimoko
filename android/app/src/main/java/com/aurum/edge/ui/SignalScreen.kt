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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.SignalAction
import com.aurum.edge.core.IctEntryRules
import com.aurum.edge.core.PaperOrderRules
import com.aurum.edge.data.MarketState
import com.aurum.edge.data.NewsGate
import com.aurum.edge.engine.MtfAnalyzer
import com.aurum.edge.ui.components.ConfluenceRow
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.SignalSummaryCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.components.formatPrice
import com.aurum.edge.ui.components.formatTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun SignalScreen(viewModel: AurumViewModel, market: MarketState) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mtf by viewModel.mtf.collectAsStateWithLifecycle()
    val news by viewModel.news.collectAsStateWithLifecycle()
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
        SectionCard("گیت رنج و زمان خرید/فروش کاغذی",
            "افزوده بر ۸ شرط فنی + خبر AI؛ خط S/R یا طرح سیگنال، پوزیشن ثبت‌شده نیست") {
            val reason = IctEntryRules.assess(market).reason
            Text(reason ?: "رنج، جاروب/بازپس‌گیری، MSS، FVG، بازآزمایی، جلسهٔ نیویورک و فضای کافی تأیید شدند؛ ۹/۹ و ریسک همچنان جداگانه لازم‌اند.",
                style = MaterialTheme.typography.bodySmall,
                color = if (reason == null) AurumColors.Green else AurumColors.Gold)
            Text("دکمهٔ ورود سیگنالی نیز پیش از ذخیره دوباره بررسی می‌شود؛ ورود دستیِ جداگانه ادعای تأیید این گیت ندارد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }
        SectionCard("خبر وب: شرط نهم سیگنال و وتوی اختیاری دستی", "برای ورود خودکار، خبر AI هم‌جهت همیشه الزامی است؛ سفارش واقعی نداریم") {
            val blocked = settings.pauseOnNews && (news.gate != NewsGate.CLEAR || news.lastCheckedAt == null ||
                System.currentTimeMillis() - news.lastCheckedAt!! > 180_000L)
            Text(if (!settings.pauseOnNews) "وتوی ورود دستی خاموش است؛ شرط نهم خبر AI برای ورود سیگنالی/خودکار همچنان لازم است."
                else if (blocked) "ورود کاغذی متوقف: ${news.reason}" else "فقط در منابع RSS بررسی‌شده فعلاً خبر پراثر تازه پیدا نشد؛ تقویم کامل نیست.",
                style = MaterialTheme.typography.bodySmall, color = if (blocked) AurumColors.Red else AurumColors.TextSecondary)
            Text("آخرین بررسی: ${relativeTime(news.lastCheckedAt)} · خبر ناقص/قدیمی اجازهٔ ورود نمی‌دهد.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
        }

        PaperTicketSection(viewModel, market)

        signal?.let { s ->
            SectionCard(
                title = "همگرایی ۹ شرط (۸ فنی + خبر AI)",
                subtitle = "ورود خودکار کاغذی فقط با تأیید هر ۹ شرط؛ امتیاز فنی: ${s.confidence.toInt()} از ۱۰۰ · کندل ${s.interval.label} · ${formatTime(s.barTime)}",
            ) {
                if (s.confluence.isEmpty()) {
                    Text("داده کافی برای نمایش جزئیات نیست", style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted)
                } else {
                    s.confluence.take(9).forEach { ConfluenceRow(it) }
                    if (s.confluence.size > 9) {
                        Text("کنترل‌های اضافه (امتیاز همگرایی نیستند):", style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
                        s.confluence.drop(9).forEach { ConfluenceRow(it) }
                    }
                }
            }

            if (s.isActionable) {
                SectionCard(
                    title = "حجم فرضی سیگنال (همان قواعد برگهٔ کاغذی)",
                    subtitle = "موجودی ${formatPrice(settings.accountBalance)}$ · سقف ریسک ${settings.riskPercent}%",
                ) {
                    val ticket = runCatching {
                        PaperOrderRules.preview(s.action, market.symbol, market.lastPrice ?: 0.0,
                            s.stopLoss ?: 0.0, s.takeProfit ?: 0.0,
                            settings.accountBalance, settings.riskPercent)
                    }
                    ticket.getOrNull()?.let { draft ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatTile("حجم کاغذی", "${String.format("%.6f", draft.quantity)} ${draft.unit}", AurumColors.TextPrimary, Modifier.weight(1f))
                            StatTile("ریسک تا SL", "${formatPrice(draft.actualRiskUsd)}$", AurumColors.Gold, Modifier.weight(1f))
                            StatTile("ارزش فرضی", "${formatPrice(draft.notionalUsd)}$", AurumColors.TextSecondary, Modifier.weight(1f))
                        }
                        Text("این حجم کسری ممکن است در بروکر قابل اجرا نباشد؛ حداقل لات، مارجین، کارمزد و لغزش هنوز تأیید نشده‌اند.",
                            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                            modifier = Modifier.padding(top = 8.dp))
                    } ?: Text("ورود کاغذی با این قیمت/استاپ امکان ندارد: ${ticket.exceptionOrNull()?.message ?: "حجم نامعتبر"}",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.Red)
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

        mtf?.let { snapshot -> MtfCard(snapshot) }

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

@Composable
private fun MtfCard(snapshot: MtfAnalyzer.Snapshot) {
    val tone = when {
        snapshot.veto -> AurumColors.Red
        snapshot.bias == SignalAction.BUY -> AurumColors.Green
        snapshot.bias == SignalAction.SELL -> AurumColors.Red
        else -> AurumColors.TextSecondary
    }
    SectionCard(
        title = "تراز چندتایم‌فریم (محاسبه روی گوشی)",
        subtitle = "تایم‌فریم‌های بالاتر از همان کندل‌های واقعیِ دریافتی ساخته می‌شوند · آخرین کندل بسته: ${formatTime(snapshot.barTime)}",
        trailing = {
            Pill(
                text = if (snapshot.veto) "وتو" else snapshot.bias.name,
                color = tone,
            )
        },
    ) {
        Text(
            snapshot.advisory,
            style = MaterialTheme.typography.bodySmall,
            color = if (snapshot.veto) AurumColors.Red else AurumColors.TextSecondary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            StatTile("هم‌جهتی", "${(snapshot.alignment * 100).toInt()}%", tone, Modifier.weight(1f))
            StatTile("صعودی", "${snapshot.buyCount}", AurumColors.Green, Modifier.weight(1f))
            StatTile("نزولی", "${snapshot.sellCount}", AurumColors.Red, Modifier.weight(1f))
            StatTile("خنثی", "${snapshot.neutralCount}", AurumColors.TextSecondary, Modifier.weight(1f))
        }
        snapshot.frames.forEach { frame ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    frame.interval.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.TextPrimary,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    frame.bias.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (frame.bias) {
                        SignalAction.BUY -> AurumColors.Green
                        SignalAction.SELL -> AurumColors.Red
                        else -> AurumColors.TextMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "قدرت ${frame.strength}% · وزن ${(frame.weight * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.Gold,
                    modifier = Modifier.weight(1.6f),
                )
            }
            Text(
                frame.detail,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        if (snapshot.skippedFrames.isNotEmpty()) {
            Text(
                "تایم‌فریم‌های ${snapshot.skippedFrames.joinToString("، ")} هنوز تاریخ واقعی کافی ندارند؛ درباره‌شان حدس نمی‌زنیم.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
