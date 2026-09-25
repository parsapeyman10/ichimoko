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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

/** CoinDesk-only news. It never reaches the XAU/USD ninth-confluence or Nobitex practice. */
@Composable
fun CryptoNewsScreen(viewModel: AurumViewModel) {
    val state by viewModel.cryptoWebNews.collectAsStateWithLifecycle()
    val browser = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        viewModel.refreshCryptoWebNews()
        while (true) { delay(15 * 60_000L); now = System.currentTimeMillis(); viewModel.refreshCryptoWebNews() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("خبر رمزارز", "RSS مستقیم CoinDesk · نه Forex Factory و نه خبر بورس ایران") {
            Text("فقط تیتر و چکیدهٔ ناشر با تاریخ و لینک اصلی؛ ترجمه در سرویس بیرونی و به درخواست شماست. این خبر مجوز معامله نیست.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Button(onClick = viewModel::refreshCryptoWebNews, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("تازه‌سازی") }
            state.feeds.forEach { health ->
                Text("${health.feed.title}: ${if (health.online(now)) "دریافت اخیر" else health.detail} · بررسی ${relativeTime(health.checkedAt, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (health.online(now)) AurumColors.Cyan else AurumColors.Gold)
            }
        }
        val visible = state.headlines.filter { item ->
            now - item.publishedAt in -15 * 60_000L..item.feed.maxAgeHours * 3_600_000L
        }.take(12)
        if (visible.isEmpty()) SectionCard("خبر تازه در دسترس نیست", "خطای خوراک یا نبود تیتر به معنی امن‌بودن بازار نیست") {
            Text("به وضعیت خوراک بالا نگاه کنید؛ هیچ خبر ساختگی یا کش بی‌زمان نمایش داده نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        visible.forEach { item ->
            val live = state.feeds.firstOrNull { it.feed.id == item.feed.id }?.online(now) == true && !state.loading
            SectionCard(item.title, "${item.feed.title} · انتشار ${formatDateTime(item.publishedAt)} · ${relativeTime(item.publishedAt, now)}") {
                Text(if (live) "خوراک دریافت شد · دریافت در گوشی ${formatDateTime(item.receivedAt)}"
                     else "کش قبلی / تازگی دوباره تأیید نشد · دریافت ${formatDateTime(item.receivedAt)}",
                    style = MaterialTheme.typography.labelSmall, color = if (live) AurumColors.Cyan else AurumColors.Gold)
                if (item.excerpt.isNotBlank()) Text(item.excerpt,
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { browser.openUri(item.url) } }, modifier = Modifier.weight(1f)) {
                        Text("متن ناشر")
                    }
                    OutlinedButton(onClick = {
                        TranslateLink.englishToPersian(item.title, item.excerpt)?.let { url ->
                            runCatching { browser.openUri(url) }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("ترنسلیت ↗") }
                }
            }
        }
    }
}
