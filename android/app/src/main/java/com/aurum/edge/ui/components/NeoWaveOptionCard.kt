package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.NeoWaveEngine
import com.aurum.edge.ui.theme.AurumColors

/**
 * کارت «🌊 نئوویو (NeoWave)» — لایهٔ تأییدِ اختیاری گلن نیلی.
 *
 * این کارت فقط یک کلید روشن/خاموش + دو آستانهٔ حساسیت را در اختیار کاربر می‌گذارد.
 * وقتی خاموش باشد، موتور استراتژی هیچ محاسبه‌ای انجام نمی‌دهد و رفتار برنامه دقیقاً مثل قبل است.
 */
@Composable
fun NeoWaveOptionCard(
    enabled: Boolean,
    minRulesPassed: Int,
    minConfidencePct: Double,
    statusBadge: String,
    onToggle: (Boolean) -> Unit,
    onRulesChange: (Int) -> Unit,
    onConfidenceChange: (Double) -> Unit,
    cryptoEntryEnabled: Boolean = true,
    cryptoMinRulesPassed: Int = 8,
    cryptoMinConfidencePct: Double = 65.0,
    cryptoReadyCount: Int = 0,
    cryptoTotalCount: Int = 0,
    onCryptoEntryToggle: (Boolean) -> Unit = {},
    onCryptoRulesChange: (Int) -> Unit = {},
    onCryptoConfidenceChange: (Double) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AurumColors.Surface)
            .border(
                width = 1.dp,
                color = if (enabled) AurumColors.Gold.copy(alpha = 0.55f) else AurumColors.Border,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🌊 نئوویو (NeoWave) — لایهٔ تأیید",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) AurumColors.Gold else AurumColors.TextMuted,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (enabled) "فعال · شمارش امواج نیلی روی ورودها داوری می‌کند" else "خاموش · رفتار برنامه کاملاً بدون تغییر",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        Text(
            text = "این ماژول مستقل معامله نمی‌کند؛ فقط ورودهای ۱۶ استراتژی را تقویت یا وتو می‌کند: " +
                "الگوی هم‌جهت و تکمیل‌شده اطمینان را بالا می‌برد و الگوی مخالفِ معتبر ورود را می‌بندد.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
        )

        if (statusBadge.isNotBlank()) {
            Text(
                text = statusBadge,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Purple,
            )
        }

        if (enabled) {
            Text(
                text = "حداقل قواعد نیلی برای پذیرش شمارش:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(4, NeoWaveEngine.MIN_RULE_PASS, 8).distinct().forEach { value ->
                    Pill(
                        text = "$value قاعده",
                        color = if (minRulesPassed == value) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onRulesChange(value) },
                    )
                }
            }
            Text(
                text = "حداقل اطمینان برای اجازهٔ وتو:",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50, 55, 65).forEach { value ->
                    Pill(
                        text = "$value٪",
                        color = if (minConfidencePct.toInt() == value) AurumColors.Gold else AurumColors.TextMuted,
                        modifier = Modifier.clickable { onConfidenceChange(value.toDouble()) },
                    )
                }
            }
            Text(
                text = "نکتهٔ ریسک: نئوویو داورِ قاعده‌محور است، نه تحلیلگر ذهنی. با آستانهٔ پایین‌تر، وتوهای بیشتری رخ می‌دهد.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )

            // ── مسیر ورود رمزارز بر پایهٔ نئوویو ─────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(1.dp)
                    .background(AurumColors.Border),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🌊 ورود رمزارز بر پایهٔ نئوویو",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (cryptoEntryEnabled) AurumColors.Gold else AurumColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (cryptoEntryEnabled) {
                            "فعال · الگوی کامل و تأییدشدهٔ نئوویو خودش ماشهٔ ورود رمزارز است"
                        } else {
                            "خاموش · رمزارز هم مثل قبل فقط با عبور هر ۱۶ شرط وارد می‌شود"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
                Switch(checked = cryptoEntryEnabled, onCheckedChange = onCryptoEntryToggle)
            }
            Text(
                text = "چرا؟ شرط «هم‌زمان هر ۱۶ شرط» در رمزارز می‌تواند روزها برقرار نشود و بازار را از دست بدهد. " +
                    "در این مسیر همان روش نئوویو ورود را باز می‌کند، ولی حد ضرر/سود، R:R ≥ ۱:۱.۵ و حجم و لوریج همان قواعد قبلی را دارند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
            if (cryptoEntryEnabled) {
                Text(
                    text = "حداقل قواعد نئوویو برای ورود رمزارز:",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 8, 9).forEach { value ->
                        Pill(
                            text = "$value قاعده",
                            color = if (cryptoMinRulesPassed == value) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable { onCryptoRulesChange(value) },
                        )
                    }
                }
                Text(
                    text = "حداقل اطمینان برای ورود رمزارز:",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(55, 65, 70).forEach { value ->
                        Pill(
                            text = "$value٪",
                            color = if (cryptoMinConfidencePct.toInt() == value) AurumColors.Gold else AurumColors.TextMuted,
                            modifier = Modifier.clickable { onCryptoConfidenceChange(value.toDouble()) },
                        )
                    }
                }
                Text(
                    text = if (cryptoTotalCount > 0) {
                        "هم‌اکنون $cryptoReadyCount از $cryptoTotalCount نماد رمزارز الگوی تأییدشده و هم‌جهت دارند."
                    } else {
                        "شمارش رمزارزها در حال آماده‌سازی است."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cryptoReadyCount > 0) AurumColors.Purple else AurumColors.TextMuted,
                )
                if (cryptoTotalCount > 0 && cryptoReadyCount == 0) {
                    Text(
                        text = "اگر شمارش صفر است، برنامه عمداً هیچ ورودی را به‌زور باز نمی‌کند؛ " +
                            "یا آستانه را کمی نرم‌تر کن یا منتظر تکمیل الگو بمان.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextSecondary,
                    )
                }
            }
        }
    }
}
