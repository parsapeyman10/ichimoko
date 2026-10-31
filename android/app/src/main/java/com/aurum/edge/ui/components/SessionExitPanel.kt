package com.aurum.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.SessionExitLearning
import com.aurum.edge.ui.theme.AurumColors
import java.util.Locale

/**
 * پنل «زمان‌بندی سشن و خروج هوشمند».
 *
 * به کاربر نشان می‌دهد سامانه برای هر دستهٔ دارایی چند دقیقه پیش از بسته شدن سشن خارج می‌شود،
 * این عدد از چند مشاهدهٔ واقعی یاد گرفته شده و آخرین سنجش بازگشایی بازار چه بوده است.
 */
@Composable
fun SessionExitPanel(
    estimates: List<SessionExitLearning.Estimate>,
    lastLearningNote: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AurumColors.Surface)
            .border(1.dp, AurumColors.Purple.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🕒 زمان‌بندی سشن و خروج هوشمند",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Purple,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Pill(text = "یادگیری فعال", color = AurumColors.Green)
        }
        Text(
            text = "قاعده: هیچ معامله‌ای با «قیمت کهنهٔ بازار بسته» بسته نمی‌شود. خروج پیش از پایان سشن " +
                "در بازار زنده انجام می‌شود و اگر پوزیشنی از سشن قبل باز مانده باشد، در اولین قیمت " +
                "معتبر پس از بازگشایی بسته می‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextSecondary,
        )
        if (estimates.isEmpty()) {
            Text(
                text = "در حال آماده‌سازی تخمین‌ها…",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
            )
        } else {
            estimates.forEach { estimate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AurumColors.SurfaceAlt.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = estimate.labelFa,
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        text = if (estimate.leadMinutes == 0) "۲۴ ساعته" else "${estimate.leadMinutes} دقیقه قبل",
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.Gold,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1.1f),
                    )
                    // نوار اطمینان
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AurumColors.Background),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((estimate.confidencePct / 100f).coerceIn(0.02f, 1f))
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AurumColors.Green.copy(alpha = 0.75f)),
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "%d نمونه", estimate.samples),
                        style = MaterialTheme.typography.labelSmall,
                        color = AurumColors.TextMuted,
                        maxLines = 1,
                        modifier = Modifier.width(58.dp),
                    )
                }
                Text(
                    text = estimate.noteFa,
                    style = MaterialTheme.typography.labelSmall,
                    color = AurumColors.TextMuted,
                )
            }
        }
        if (lastLearningNote.isNotBlank()) {
            Text(
                text = lastLearningNote,
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Cyan,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "روش: باندیت چندبازویی ε-greedy روی گزینه‌های ۰ تا ۴۵ دقیقه، با prior پیش‌فرض هر دسته " +
                "و پاداش واقعی «دلار نجات‌یافته» نسبت به نگه‌داشتن پوزیشن تا بازگشایی.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
        )
    }
}
