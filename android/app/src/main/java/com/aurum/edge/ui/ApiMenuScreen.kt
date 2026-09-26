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

/** Workspace-specific directory, NOT an inventory of invented/embedded assistant API keys.
 * Read-only credentials are entered only in the owning space; no trade secret is accepted.
 */
@Composable
fun ApiMenuScreen(settings: AppSettings, workspace: Workspace,
                  onForexSettings: () -> Unit, onIranStocks: () -> Unit, onCrypto: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("منابع و APIهای ${workspace.label}", "فقط منابع تعریف‌شده، نه «همهٔ سایت‌ها»") {
            Text("کلید API اختصاصیِ دستیار وجود ندارد. کلید خواندنی خود را فقط در فضای مربوط وارد کنید؛ کلید معاملاتی یا کلید افشاشده را وارد نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        when (workspace) {
            Workspace.FOREX -> {
                SectionCard("Twelve Data · قیمت", "نماد و کلید خواندنی جدا از کلید معامله") {
                    Text("چارت ${settings.symbol}: ${if (settings.hasKey) "کلید محلی ثبت شده؛ اتصال نیازمند دادهٔ تازه است" else "کلید وارد نشده"}",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextPrimary)
                    Text("XAU/USD دیده‌بان می‌تواند کلید مختص خود داشته باشد. انتخاب نماد چارت و کلید اختصاصی دیده‌بان در تنظیمات است.",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                    OutlinedButton(onClick = onForexSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("مدیریت کلید و نماد")
                    }
                }
                SectionCard("خبر · Forex Factory", "تقویم عمومی بی‌کلید؛ FXStreet/BLS مکمل") {
                    Text("ترجمهٔ درون‌اپ روی گوشی انجام می‌شود. شرط نهم AI نیازمند سرور HTTPS و مدل/شواهد واقعی است؛ بدون آن ورود خودکار کاغذی بسته می‌ماند.",
                        style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                }
            }
            Workspace.CRYPTO -> SectionCard("CoinGecko · بازار جهانی", "فید عمومی بی‌کلید و تک‌منبعی") {
                Text("۲۰ دارایی برتر USD هنگام مشاهده با فاصلهٔ دست‌کم سه دقیقه تازه می‌شوند؛ Binance/غربال دومنبعی فقط با سرور اختیاری جدا. قیمت این فضا سفارش نیست.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                OutlinedButton(onClick = onCrypto, modifier = Modifier.fillMaxWidth()) { Text("بازار و تنظیم سرور اختیاری") }
            }
            Workspace.NOBITEX -> SectionCard("نوبیتکس · دادهٔ رسمی عمومی", "BTC/USDT و BTC/IRT · بدون کلید") {
                Text("آمار/دفتر/کندل و تمرین دستی کاغذی از API عمومی؛ سفارش واقعی خاموش است. کلید حساب/برداشت را داخل اپ نگذارید.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            }
            Workspace.IRAN_STOCKS -> SectionCard("BrsApi · تابلوی بورس", "واسطهٔ خواندنی TSETMC · کلید شخصی") {
                Text("ورود کلید در تب بورس؛ دادهٔ نمادها فقط در ساعت معمول تابلو خودکار دریافت می‌شود. قیمت ریالی TGJU/Navasan مستقل است؛ آگاه در این نسخه اتصال سفارش ندارد.",
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                OutlinedButton(onClick = onIranStocks, modifier = Modifier.fillMaxWidth()) { Text("ورود کلید تابلو") }
            }
        }
        Text("۲۰ تا ۶۰ درخواست وب در دقیقه در پلن‌های عمومی پشتیبانی نمی‌شود؛ فید WebSocket در صورت دسترسی و بازبودن بازار به‌کار می‌رود. سهمیه/۴۲۹ یا قطع منبع هرگز قیمت یا مجوز ساختگی تولید نمی‌کند.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
    }
}
