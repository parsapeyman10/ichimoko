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
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Directory of the sources this app actually talks to.
 * Read-only credentials are entered only in Settings; no trade secret is accepted.
 */
@Composable
fun ApiMenuScreen(settings: AppSettings, onForexSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
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
