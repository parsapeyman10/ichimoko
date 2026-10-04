package com.aurum.edge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.edge.core.AppSettings
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Directory of the sources this app actually talks to.
 * Read-only credentials are entered only in Settings; no trade secret is accepted.
 */
@Composable
fun ApiMenuScreen(settings: AppSettings, onForexSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        AiStatusCard(settings)
        SectionCard("منابع و APIهای فارکس", "فقط منابع تعریف‌شده، نه «همهٔ سایت‌ها»") {
            Text("کلید API اختصاصیِ دستیار وجود ندارد. کلید خواندنی خود را فقط در تنظیمات وارد کنید؛ کلید معاملاتی یا کلید افشاشده را وارد نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        SectionCard("Twelve Data · قیمت", "نماد و کلید خواندنی جدا از کلید معامله") {
            Text("چارت ${settings.symbol}: ${if (settings.hasKey) "کلید محلی ثبت شده؛ اتصال نیازمند دادهٔ تازه است" else "کلید وارد نشده"}",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
            Text("نمادهای دیده‌بان می‌توانند کلید مختص خود داشته باشند. انتخاب نماد چارت و کلید اختصاصی دیده‌بان در تنظیمات است.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            OutlinedButton(onClick = onForexSettings, modifier = Modifier.fillMaxWidth()) {
                Text("مدیریت کلید و نماد")
            }
        }
        SectionCard("خبر · Forex Factory", "تقویم عمومی بی‌کلید؛ FXStreet/BLS مکمل") {
            Text("ترجمهٔ درون‌اپ روی گوشی انجام می‌شود. AI خبر اگر سرور/کلید و شاهد واقعی داشته باشد فقط تأیید سبز کمکی می‌دهد؛ نبود آن امتیاز ۸ شرط فنی را منفی نمی‌کند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
        }
        Text("۲۰ تا ۶۰ درخواست وب در دقیقه در پلن‌های عمومی پشتیبانی نمی‌شود؛ فید WebSocket در صورت دسترسی و بازبودن بازار به‌کار می‌رود؛ اگر کلید/پلن WebSocket ندهد، فید زندهٔ جایگزینِ برچسب‌دار استفاده می‌شود. سهمیه/۴۲۹ یا قطع منبع هرگز قیمت یا مجوز ساختگی تولید نمی‌کند.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}

/**
 * What the AI is for, whether it is switched on, and why it is not answering.
 *
 * Previously the AI simply produced nothing when unconfigured or unreachable, with no
 * statement anywhere about which tasks it performs or what it needs — indistinguishable
 * from a broken feature. Every condition that can stop it is listed here explicitly.
 */
@Composable
private fun AiStatusCard(settings: AppSettings) {
    val configured = settings.hasClientNewsAi
    val host = runCatching { java.net.URI(settings.newsAiBaseUrl.trim()).host }.getOrNull().orEmpty()
    val directProvider = host.endsWith("openai.com") || host.endsWith("anthropic.com")

    SectionCard(
        "هوش مصنوعی — وضعیت و وظایف",
        "اختیاری است؛ موتور ایچیموکو بدون آن کامل کار می‌کند",
        trailing = {
            Pill(
                if (configured) "فعال" else "خاموش",
                if (configured) AurumColors.Green else AurumColors.TextMuted,
            )
        },
    ) {
        Text("AI در این اپ فقط این کارها را انجام می‌دهد:",
            style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary)
        listOf(
            "۱) تحلیل تیتر خبر فارکس و تعیین جهت/شدت اثر آن",
            "۲) نظر مشورتی «تریدر» دربارهٔ وضعیت فعلی بازار",
        ).forEach {
            Text("  • $it", style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "AI هیچ‌وقت سیگنال نمی‌سازد و هیچ معامله‌ای باز نمی‌کند. ورود و خروج فقط با ۸ شرط فنی ایچیموکو تعیین می‌شود.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text("چرا ممکن است جواب ندهد",
            style = MaterialTheme.typography.labelMedium, color = AurumColors.TextPrimary,
            modifier = Modifier.padding(top = 12.dp))

        @Composable
        fun check(ok: Boolean, label: String, detail: String) {
            Text(
                "${if (ok) "✓" else "✕"} $label — $detail",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) AurumColors.Green else AurumColors.Red,
                modifier = Modifier.padding(top = 5.dp),
            )
        }

        check(settings.newsAiApiKey.isNotBlank(), "کلید",
            if (settings.newsAiApiKey.isNotBlank()) "ثبت شده روی همین گوشی" else "وارد نشده — در تنظیمات وارد کنید")
        check(settings.newsAiBaseUrl.isNotBlank(), "نشانی سرویس",
            if (host.isNotBlank()) host else "وارد نشده")
        check(settings.newsAiModel.isNotBlank(), "نام مدل",
            settings.newsAiModel.ifBlank { "وارد نشده" })

        if (directProvider) {
            Text(
                "⚠ $host معمولاً از ایران مسدود است و خطای ۴۰۳ می‌دهد. برای کار کردن، نشانی یک " +
                    "سرویس واسط سازگار با OpenAI را وارد کنید، نه نشانی مستقیم.",
                style = MaterialTheme.typography.bodySmall,
                color = AurumColors.Orange,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            "کلید روی همین گوشی می‌ماند و هرگز در لاگ نوشته نمی‌شود. فقط متن تیتر خبر برای سرویس فرستاده می‌شود.",
            style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
