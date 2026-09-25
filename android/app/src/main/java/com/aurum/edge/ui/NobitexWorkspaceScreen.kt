package com.aurum.edge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Its own read-only exchange data and paper journal; never the global-crypto or XAU panel. */
@Composable
fun NobitexWorkspaceScreen(viewModel: AurumViewModel) {
    val browser = LocalUriHandler.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        ReadOnlyMonitorCard(viewModel, settings)
        SectionCard("اتصال رسمی نوبیتکس", "راهنمای دریافت دسترسی از سایت خود صرافی · بدون درخواست کلید در این اپ") {
            Text("در سایت رسمی حساب خود وارد «پروفایل ← خدمات API» شوید و مستندات سطح دسترسی را بخوانید. برای آمار عمومی این صفحه هیچ کلیدی لازم نیست. در حال حاضر اتصال حساب، برداشت و سفارش واقعی غیرفعال‌اند؛ کلید معاملاتی را اینجا یا در چت وارد نکنید.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("طبق صفحهٔ رسمی API، درخواست‌ها ممکن است به IP ایران محدود باشند؛ خطای شبکه را دادهٔ سالم فرض نکنید.",
                style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = { runCatching { browser.openUri("https://nobitex.ir/panel/profile/api-services/") } }) {
                Text("مدیریت کلید در سایت نوبیتکس")
            }
            OutlinedButton(onClick = { runCatching { browser.openUri("https://nobitex.ir/api-docs/") } }) {
                Text("مستندات رسمی و محیط آزمایشی")
            }
        }
        NobitexTrainingSection(viewModel) // includes the separate USDT/IRR scanner and practice store
    }
}
