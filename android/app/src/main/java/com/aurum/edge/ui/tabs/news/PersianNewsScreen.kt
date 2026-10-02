package com.aurum.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.data.ForexEvent
import com.aurum.edge.data.NewsClassifier
import com.aurum.edge.data.NewsDirection
import com.aurum.edge.data.NewsImportance
import com.aurum.edge.data.PublicHeadline
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun PersianNewsScreen(viewModel: AurumViewModel, onOpenSettings: () -> Unit) {
    val web by viewModel.publicWebNews.collectAsStateWithLifecycle()
    val calendar by viewModel.forexCalendar.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        viewModel.refreshPublicWebNews()
        viewModel.refreshForexCalendar()
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("خبرهای مهم بازار", "اهمیت، اثر احتمالی و توضیح کوتاه به‌صورت خودکار از تیتر/تقویم واقعی") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refreshPublicWebNews, enabled = !web.loading, modifier = Modifier.weight(1f)) {
                    Text(if (web.loading) "دریافت خبر…" else "به‌روزرسانی خبر")
                }
                Button(onClick = viewModel::refreshForexCalendar, enabled = !calendar.loading, modifier = Modifier.weight(1f)) {
                    Text(if (calendar.loading) "تقویم…" else "تقویم اقتصادی")
                }
            }
            Text(
                "آخرین خبر: ${relativeTime(web.lastAttemptAt, now)} · آخرین تقویم: ${relativeTime(calendar.checkedAt, now)}",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            calendar.error?.let { Text("تقویم در دسترس نیست: $it", color = AurumColors.Gold, style = MaterialTheme.typography.bodySmall) }
        }

        calendar.events
            .filter { abs(it.at - now) <= 6 * 60 * 60_000L || it.at in now..(now + 24 * 60 * 60_000L) }
            .take(8)
            .forEach { event -> CalendarImpactCard(event, now) }

        val headlines = web.headlines.take(18)
        if (headlines.isEmpty()) {
            SectionCard("تیتر قابل نمایش نیست", "نبود تیتر به معنی نبود خبر یا امن بودن بازار نیست") {
                Text("خوراک‌های عمومی هنوز تیتر تازه نداده‌اند یا اتصال برقرار نشده است.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
        } else {
            headlines.forEach { item ->
                val classification = NewsClassifier.classify(item.title, item.excerpt)
                NewsImpactCard(item, classification, onOpen = { runCatching { uriHandler.openUri(item.url) } })
            }
        }
    }
}

@Composable
private fun CalendarImpactCard(event: ForexEvent, now: Long) {
    val high = event.impact == "High"
    val tone = when (event.impact) {
        "High" -> AurumColors.Red
        "Medium" -> AurumColors.Gold
        else -> AurumColors.TextSecondary
    }
    SectionCard(
        title = "${event.country} · ${event.title}",
        subtitle = "تقویم اقتصادی · ${formatDateTime(event.at)} · ${calendarTimeLabel(event.at, now)}",
        trailing = { Pill(if (high) "خیلی مهم" else event.impact, tone) },
    ) {
        Text(
            calendarExplanation(event),
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.TextSecondary,
        )
        val metrics = listOfNotNull(
            event.actual?.let { "Actual: $it" },
            event.forecast?.let { "Forecast: $it" },
            event.previous?.let { "Previous: $it" },
        )
        if (metrics.isNotEmpty()) Text(metrics.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun NewsImpactCard(item: PublicHeadline, classification: com.aurum.edge.data.NewsClassification, onOpen: () -> Unit) {
    val tone = when (classification.importance) {
        NewsImportance.HIGH -> AurumColors.Red
        NewsImportance.MEDIUM -> AurumColors.Gold
        NewsImportance.LOW -> AurumColors.TextSecondary
    }
    SectionCard(
        title = item.title,
        subtitle = "${item.feed.title} · ${formatDateTime(item.publishedAt)}",
        trailing = { Pill(importanceLabel(classification.importance), tone) },
    ) {
        Text(
            directionLine(classification.direction),
            style = MaterialTheme.typography.bodySmall,
            color = directionColor(classification.direction),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            impactExplanation(classification, item),
            style = MaterialTheme.typography.bodySmall,
            color = AurumColors.TextSecondary,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (item.excerpt.isNotBlank()) Text(item.excerpt,
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 5.dp))
        Button(onClick = onOpen, modifier = Modifier.padding(top = 7.dp)) { Text("مشاهده منبع") }
    }
}

private fun importanceLabel(value: NewsImportance): String = when (value) {
    NewsImportance.HIGH -> "مهم"
    NewsImportance.MEDIUM -> "متوسط"
    NewsImportance.LOW -> "کم‌اثر"
}

private fun directionLine(value: NewsDirection): String = when (value) {
    NewsDirection.BULLISH -> "اثر احتمالی: حمایتی / صعودی برای دارایی مرتبط"
    NewsDirection.BEARISH -> "اثر احتمالی: فشار فروش / نزولی برای دارایی مرتبط"
    NewsDirection.NEUTRAL -> "اثر احتمالی: نامشخص یا خنثی"
}

private fun directionColor(value: NewsDirection) = when (value) {
    NewsDirection.BULLISH -> AurumColors.Green
    NewsDirection.BEARISH -> AurumColors.Red
    NewsDirection.NEUTRAL -> AurumColors.TextSecondary
}

private fun impactExplanation(classification: com.aurum.edge.data.NewsClassification, item: PublicHeadline): String {
    val target = when {
        item.title.contains("gold", ignoreCase = true) || item.excerpt.contains("gold", ignoreCase = true) -> "طلا / XAUUSD"
        item.title.contains("dollar", ignoreCase = true) || item.excerpt.contains("dollar", ignoreCase = true) ||
            item.title.contains("fed", ignoreCase = true) -> "دلار آمریکا و جفت‌ارزهای اصلی"
        else -> "بازار فارکس و طلا"
    }
    val behavior = when (classification.direction) {
        NewsDirection.BULLISH -> "ممکن است تقاضا یا مومنتوم خرید را تقویت کند؛ منتظر تأیید چارت بمان."
        NewsDirection.BEARISH -> "ممکن است فشار فروش یا نوسان تند ایجاد کند؛ ورود خلاف خبر ریسک بیشتری دارد."
        NewsDirection.NEUTRAL -> "جهت روشن نیست؛ بیشتر به‌عنوان هشدار نوسان/ریسک زمانی دیده شود."
    }
    return "روی $target اثر احتمالی دارد. $behavior"
}

private fun calendarTimeLabel(at: Long, now: Long): String {
    val diffMinutes = (at - now) / 60_000L
    return when {
        diffMinutes > 60 -> "تا ${diffMinutes / 60} ساعت دیگر"
        diffMinutes > 0 -> "تا $diffMinutes دقیقه دیگر"
        diffMinutes in -59L..0L -> "${-diffMinutes} دقیقه پیش"
        else -> "${-diffMinutes / 60} ساعت پیش"
    }
}

private fun calendarExplanation(event: ForexEvent): String {
    val target = if (event.country == "USD") "طلا و همه جفت‌های دلاری" else "جفت‌ارزهای مرتبط با ${event.country}"
    return when (event.impact) {
        "High" -> "این رویداد بسیار مهم است و می‌تواند روی $target نوسان شدید، اسپرد بیشتر و شکست‌های فیک بسازد. تا انتشار/هضم خبر، ورود تازه پرریسک است."
        "Medium" -> "اثر متوسط دارد؛ ممکن است حرکت کوتاه‌مدت بسازد ولی تصمیم نهایی باید با چارت و امتیاز موتور باشد."
        else -> "اثر معمولاً محدود است، اما اگر بازار کم‌عمق باشد همچنان می‌تواند نویز ایجاد کند."
    }
}
