package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurum.edge.ui.theme.AurumColors

/**
 * کارت «🌊 نئوویو–الیوت» — **فقط نمایش وضعیت، بدون کلید کاربر**.
 *
 * نئوویو–الیوت در «استراتژی ۱۶» ادغام شده است و همیشه در همان استراتژی اجرا می‌شود؛ در
 * هیچ روش دیگری محاسبه‌ای ندارد. آستانه‌های این لایه با هوش عصبی از نتیجهٔ واقعی
 * معاملات آموخته می‌شود، بنابراین کلید روشن/خاموش یا پیل تنظیم دستی وجود ندارد.
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
    autoTuneEnabled: Boolean = true,
    autoRules: Int = 0,
    autoConfidencePct: Double = 0.0,
    autoSamples: Int = 0,
    autoBasisFa: String = "",
    onAutoTuneToggle: (Boolean) -> Unit = {},
    onRetrainNeural: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // پارامترهای کلید/تنظیم دستی برای سازگاری امضا نگه داشته شده‌اند ولی هیچ‌کدام کلید کاربر
    // نمی‌سازند: لایهٔ نئوویو فقط داخل استراتژی ۱۶ و با آستانه‌های آموخته‌شده کار می‌کند.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AurumColors.Surface)
            .border(
                width = 1.dp,
                color = AurumColors.Gold.copy(alpha = 0.55f),
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
                    text = "🌊 نئوویو–الیوت — ادغام‌شده در استراتژی ۱۶",
                    style = MaterialTheme.typography.labelMedium,
                    color = AurumColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (enabled) {
                        "فعال · فقط در استراتژی ۱۶ اجرا می‌شود؛ سایر روش‌ها هیچ محاسبهٔ نئوویو ندارند"
                    } else {
                        "در استراتژی ۱۶ فعال است"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextSecondary,
                )
            }
            Text(
                text = "🔒 بدون کلید",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = "این لایه به‌تنهایی معامله نمی‌کند و روی بقیهٔ استراتژی‌ها اثری ندارد: در استراتژی ۱۶، " +
                "الگوی هم‌جهت و تکمیل‌شدهٔ نئوویو اطمینان ورود را بالا می‌برد، الگوی مخالفِ معتبر ورود را " +
                "می‌بندد و اهداف موجی الیوت به تارگت‌گذاری همان استراتژی اضافه می‌شود.",
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

        val rulesText = if (autoRules > 0) "$autoRules قاعده" else "در انتظار داده"
        val confText = if (autoConfidencePct > 0.0) "${autoConfidencePct.toInt()}٪" else "در انتظار داده"
        Text(
            text = "آستانهٔ آموخته‌شده: $rulesText · اطمینان $confText · نمونه: $autoSamples" +
                if (autoTuneEnabled) " · تنظیم خودکار روشن" else "",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Purple,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "آستانه‌های پیش‌فرض موتور: $minRulesPassed قاعده · ${minConfidencePct.toInt()}٪ اطمینان",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
        if (autoBasisFa.isNotBlank()) {
            Text(
                text = autoBasisFa,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextSecondary,
            )
        }
        Text(
            text = "🔄 آموزش دوباره با داده‌های فعلی",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.Cyan,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRetrainNeural() }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
