package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AssetClass
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.theme.AurumColors

/**
 * بخش مستقل «🌊 شرایط باز شدن ترید رمزارز».
 *
 * چرا جدا شد؟ در ۳.۹.۳۲ این تنظیمات داخل کارت نئوویو و **فقط وقتی آن کلید روشن بود** نمایش داده
 * می‌شد؛ نتیجه این بود که کاربر اصلاً آن را نمی‌دید. این بخش همیشه در تب «استراتژی» دیده می‌شود،
 * مستقل از خاموش/روشن بودن نئوویو، و خودش کلید لازم را هم در اختیار می‌گذارد.
 *
 * محتوا: کلید مسیر، جدول شروطِ باز شدن (همان روش نیلی)، آستانه‌های قابل تنظیم و شمارش زندهٔ
 * نمادهای رمزارزی که همین حالا الگوی تأییدشده و هم‌جهت دارند.
 */
@Composable
fun CryptoEntryPathCard(
    viewModel: AurumViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dualReports by viewModel.dualSymbolStrategyReports.collectAsStateWithLifecycle()
    val cfg = settings.baseStrategyConfig

    // ── شمارش زندهٔ رمزارزها ────────────────────────────────────────────────
    val cryptoReports = dualReports.values
        .filter { AssetClass.of(it.symbol) == AssetClass.CRYPTO }
        .map { d -> d to (d.reports15mByStrategy.values + d.reports5mByStrategy.values) }
    val totalCrypto = cryptoReports.size
    val readyRows = cryptoReports.mapNotNull { (d, reps) ->
        val ready = reps.firstOrNull { it.cryptoNeoWaveEntry }
        if (ready != null) d.symbol to (ready.entryPathFa.ifBlank { "الگوی تأییدشده" }) else null
    }
    val blockedRows = cryptoReports.mapNotNull { (d, reps) ->
        val hint = reps.firstOrNull { it.entryPathFa.isNotBlank() }?.entryPathFa
        if (hint != null && readyRows.none { it.first == d.symbol }) d.symbol to hint else null
    }

    SectionCard(
        title = "🌊 شرایط باز شدن ترید رمزارز",
        subtitle = "روش، همان نئوویو است — این بخش فقط تعیین می‌کند «کِی» باز شود",
        // عمداً باز و بدون تاشو: این بخش باید همیشه دیده شود (دلیل شکایت نسخهٔ قبل).
        collapsible = false,
        modifier = modifier,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (cfg.neoWaveCryptoEntryEnabled) "فعال" else "خاموش",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cfg.neoWaveCryptoEntryEnabled) AurumColors.Green else AurumColors.TextMuted,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Switch(
                    checked = cfg.neoWaveCryptoEntryEnabled,
                    onCheckedChange = { on ->
                        viewModel.updateBaseStrategyConfig(
                            cfg.copy(neoWaveCryptoEntryEnabled = on),
                        )
                    },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── کلید لازم: لایهٔ نئوویو ──────────────────────────────────────
            if (!cfg.neoWaveEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AurumColors.Orange.copy(alpha = 0.12f))
                        .border(1.dp, AurumColors.Orange.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "لایهٔ نئوویو خاموش است",
                            style = MaterialTheme.typography.labelMedium,
                            color = AurumColors.Orange,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "این مسیر تا روشن‌شدن لایهٔ نئوویو کاری نمی‌کند. با کلید روبه‌رو روشنش کن؟",
                            style = MaterialTheme.typography.labelSmall,
                            color = AurumColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = false,
                        onCheckedChange = { on ->
                            viewModel.updateBaseStrategyConfig(cfg.copy(neoWaveEnabled = on))
                        },
                    )
                }
            }

            Text(
                text = if (cfg.neoWaveCryptoEntryEnabled && cfg.neoWaveEnabled) {
                    "✅ فعال: ورود رمزارز با الگوی کامل و تأییدشدهٔ نئوویو باز می‌شود."
                } else if (cfg.neoWaveCryptoEntryEnabled) {
                    "⏸ روشن است ولی بی‌اثر: اول لایهٔ نئوویو را روشن کن."
                } else {
                    "⏹ خاموش: رمزارز هم مثل قبل فقط با عبور هم‌زمان هر ۱۶ شرط وارد می‌شود."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "چرا این بخش وجود دارد؟ شرط عمومی «هم‌زمان هر ۱۶ شرط» در رمزارزِ ۲۴ ساعته می‌تواند " +
                    "روزها برقرار نشود و بازار از دست برود. در این مسیر، شمارش نئوویو خودش ماشهٔ ورود است؛ " +
                    "حد ضرر، حد سود، حجم و لوریج همان قواعد قبلی را دارند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )

            // ── جدول شروطِ باز شدن ───────────────────────────────────────────
            Text(
                text = "شروط باز شدن (همه باید هم‌زمان برقرار باشند):",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                fontWeight = FontWeight.Bold,
            )
            ConditionLine("۱", "نماد از خانوادهٔ رمزارز باشد", "خودکار")
            ConditionLine("۲", "الگوی نئوویو کامل شده باشد (هیچ مونوویوی باز نماند)", "الزامی")
            ConditionLine("۳", "تأیید پس‌ساختاری رخ داده باشد", "الزامی")
            ConditionLine("۴", "جهت الگو با جهت ورود یکی باشد", "الزامی")
            ConditionLine(
                "۵",
                "قواعد پاس‌شده ≥ ${cfg.neoWaveCryptoMinRulesPassed} و ≥ ۷۵٪ قواعد همان الگو",
                "${cfg.neoWaveCryptoMinRulesPassed} قاعده",
            )
            ConditionLine(
                "۶",
                "اطمینان شمارش ≥ ${cfg.neoWaveCryptoMinConfidencePct.toInt()}٪",
                "${cfg.neoWaveCryptoMinConfidencePct.toInt()}٪",
            )
            ConditionLine("۷", "پلن معتبر با R:R ≥ ۱:۱.۵ و بدون وتوی نئوویو", "الزامی")

            // ── آستانه‌ها ────────────────────────────────────────────────────
            Text(
                text = "حداقل قواعد نئوویو:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(6, 8, 9).forEach { value ->
                    Pill(
                        text = "$value قاعده",
                        color = if (cfg.neoWaveCryptoMinRulesPassed == value) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable {
                            viewModel.updateBaseStrategyConfig(cfg.copy(neoWaveCryptoMinRulesPassed = value))
                        },
                    )
                }
            }
            Text(
                text = "حداقل اطمینان:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(55, 65, 70).forEach { value ->
                    Pill(
                        text = "$value٪",
                        color = if (cfg.neoWaveCryptoMinConfidencePct.toInt() == value) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable {
                            viewModel.updateBaseStrategyConfig(cfg.copy(neoWaveCryptoMinConfidencePct = value.toDouble()))
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(1.dp)
                    .background(AurumColors.Border),
            )

            // ── وضعیت زنده ───────────────────────────────────────────────────
            Text(
                text = if (totalCrypto == 0) {
                    "شمارش رمزارزها هنوز آماده نیست (منتظر کندل‌ها)."
                } else if (!cfg.neoWaveEnabled || !cfg.neoWaveCryptoEntryEnabled) {
                    "$totalCrypto نماد رمزارز زیر نظر است؛ برای شمارش زنده این مسیر را روشن کن."
                } else {
                    "هم‌اکنون ${readyRows.size} از $totalCrypto نماد رمزارز واجد شرط است."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (readyRows.isNotEmpty()) AurumColors.Purple else AurumColors.TextMuted,
                fontWeight = FontWeight.Bold,
            )

            readyRows.take(6).forEach { (symbol, hint) ->
                SymbolLine(symbol, "✅ واجد شرط", hint, AurumColors.Green)
            }
            if (readyRows.isEmpty() && cfg.neoWaveEnabled && cfg.neoWaveCryptoEntryEnabled) {
                blockedRows.take(4).forEach { (symbol, hint) ->
                    SymbolLine(symbol, "⏳ در انتظار", hint, AurumColors.TextMuted)
                }
                Text(
                    text = "وقتی هیچ نمادی واجد شرط نیست، برنامه عمداً هیچ ورودی را به‌زور باز نمی‌کند؛ " +
                        "یا آستانه را کمی نرم‌تر کن یا منتظر تکمیل الگو بمان.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ConditionLine(number: String, text: String, valueFa: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Gold,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueFa,
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
    }
}

@Composable
private fun SymbolLine(symbol: String, statusFa: String, detailFa: String, color: androidx.compose.ui.graphics.Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = statusFa,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (detailFa.isNotBlank()) {
            Text(
                text = detailFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        }
    }
}
