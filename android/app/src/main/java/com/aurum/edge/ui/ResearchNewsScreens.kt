package com.aurum.edge.ui

import androidx.compose.foundation.layout.Column
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
import com.aurum.edge.data.NewsResearch
import com.aurum.edge.data.PublicWebNewsState
import com.aurum.edge.data.ResearchSpace
import com.aurum.edge.data.ResearchState
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.formatDateTime
import com.aurum.edge.ui.components.relativeTime
import com.aurum.edge.ui.theme.AurumColors
import kotlinx.coroutines.delay

@Composable
fun NobitexNewsScreen(viewModel: AurumViewModel) {
    val news by viewModel.cryptoWebNews.collectAsStateWithLifecycle()
    ResearchNewsScreen(news, ResearchSpace.NOBITEX, viewModel::refreshCryptoWebNews,
        "زمینهٔ جهانی رمزارز", "CoinDesk · نه اطلاعیهٔ رسمی نوبیتکس، نه خبر اختصاصی جفت ریالی/USDT",
        "اطلاعیه‌ها و وضعیت خدمات نوبیتکس فقط در سایت رسمی خود صرافی قابل بررسی‌اند؛ این صفحه خوراک رسمی اطلاعیهٔ نوبیتکس ندارد.",
        "اطلاعیه‌های رسمی نوبیتکس", "https://nobitex.ir/announcement/")
}

@Composable
fun IranNewsScreen(viewModel: AurumViewModel) {
    val news by viewModel.iranWebNews.collectAsStateWithLifecycle()
    ResearchNewsScreen(news, ResearchSpace.IRAN_STOCKS, viewModel::refreshIranWebNews,
        "خبرهای عمومی اقتصاد ایران", "RSS ناشران عمومی · نه گزارش کدال/تابلوی آگاه",
        "تیتر اقتصاد جای صورت مالی نماد نیست؛ TTM و CAN SLIM فقط با اسناد هم‌دورهٔ کدال و تاریخ معتبر بررسی می‌شوند.",
        "جست‌وجوی گزارش‌های کدال", "https://www.codal.ir/Search.aspx")
}

@Composable
private fun ResearchNewsScreen(state: PublicWebNewsState, space: ResearchSpace, refresh: () -> Unit,
                               heading: String, description: String, warning: String,
                               officialLabel: String, officialUrl: String) {
    val browser = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(space) {
        refresh()
        while (true) { delay(15 * 60_000L); now = System.currentTimeMillis(); refresh() }
    }
    LaunchedEffect(space) { while (true) { delay(30_000L); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard(heading, description) {
            Text(warning, style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
            Text("آخرین تلاش ${relativeTime(state.lastAttemptAt, now)} · قطع/کهنگی منبع یعنی وضعیت خبر نامشخص، نه «خبری نیست».",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted)
            state.feeds.forEach { health ->
                Text("${health.feed.title}: ${if (health.online(now)) "دریافت اخیر" else health.detail} · بررسی ${relativeTime(health.checkedAt, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (health.online(now)) AurumColors.Cyan else AurumColors.Gold)
            }
            Button(onClick = refresh, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("تازه‌سازی خبر") }
            OutlinedButton(onClick = { runCatching { browser.openUri(officialUrl) } }) { Text("$officialLabel ↗") }
        }
        val visible = state.headlines.filter { now - it.publishedAt in -15 * 60_000L..(72 * 3_600_000L) }.take(14)
        if (visible.isEmpty()) SectionCard("خبر قابل نمایش نیست", "خوراک ممکن است قطع یا بی‌خبر باشد") {
            Text("تیتر ساختگی یا تحلیل جهت‌دار جایگزین دادهٔ ناشر نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
        }
        visible.forEach { item ->
            val note = NewsResearch.headline(item, state, space, now)
            SectionCard(item.title, "${item.feed.title} · انتشار ${formatDateTime(item.publishedAt)}") {
                Text("دریافت در گوشی ${formatDateTime(item.receivedAt)} · ${note.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (note.state == ResearchState.CONTEXT) AurumColors.Cyan else AurumColors.Gold)
                if (item.excerpt.isNotBlank()) Text(item.excerpt,
                    style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
                Text(note.detail, style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold)
                OutlinedButton(onClick = { runCatching { browser.openUri(item.url) } }) { Text("متن ناشر ↗") }
                if (item.feed.language == "en") translationSnippet(item.title, item.excerpt)?.let {
                    InlinePersianTranslation(it)
                }
            }
        }
    }
}
