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
        // Moved to the TOP on purpose: this is the real-money section with the API-key field,
        // it must be the first thing visible after opening the Nobitex workspace — no scrolling
        // past the practice/training cards to find it.
        NobitexLiveTradingSection(viewModel) // REAL orders, REAL money — visually isolated (red border)
        NobitexJournalSignalSection(viewModel) // same SignalEngine + same JournalStore as gold
        SectionCard("اتصال رسمی نوبیتکس (اطلاعات)", "کلید حساب فقط برای بخش قرمز «معاملهٔ واقعی نوبیتکس» در بالای همین صفحه لازم است") {
            Text("کارت‌های آموزشی/تمرینی پایین‌تر به هیچ کلیدی نیاز ندارند و کاملاً کاغذی‌اند.",
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

