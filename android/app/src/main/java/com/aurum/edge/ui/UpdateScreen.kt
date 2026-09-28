package com.aurum.edge.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.BuildConfig
import com.aurum.edge.data.AppUpdateRepository
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.components.StatTile
import com.aurum.edge.ui.theme.AurumColors

@Composable
fun UpdateScreen(viewModel: AurumViewModel) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        SectionCard("بروزرسانی برنامه", "بررسی، دانلود و نصب نسخهٔ جدید از داخل خود اپ") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("نسخه فعلی", BuildConfig.VERSION_NAME, AurumColors.Gold, Modifier.weight(1f))
                StatTile("کد نسخه", BuildConfig.VERSION_CODE.toString(), AurumColors.Cyan, Modifier.weight(1f))
                StatTile("Commit", BuildConfig.GIT_SHA.take(7), AurumColors.TextSecondary, Modifier.weight(1f))
            }
            Text("شناسه نصب: ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = AurumColors.Red, modifier = Modifier.padding(top = 8.dp))
            }
            state.available?.let { info -> UpdateInfoCard(info) }
            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { (state.progressPercent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text("دانلود: ${state.progressPercent?.let { "$it٪" } ?: "در حال دریافت…"}",
                    style = MaterialTheme.typography.labelSmall, color = AurumColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::checkForAppUpdate,
                    enabled = !state.checking && !state.downloading,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.checking) "در حال بررسی…" else "بررسی نسخه جدید") }
                Button(
                    onClick = viewModel::downloadAppUpdate,
                    enabled = state.available?.canDownload == true && !state.checking && !state.downloading,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.downloadedApkPath == null) "دانلود و نصب" else "نصب دوباره") }
            }
            if (state.downloadedApkPath != null) {
                OutlinedButton(onClick = viewModel::installDownloadedUpdate,
                    enabled = !state.downloading,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("باز کردن نصب‌کننده Android")
                }
            }
            if (state.needsInstallPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                OutlinedButton(onClick = viewModel::openUpdateInstallPermission,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("اجازه نصب از Aurum Edge در Android")
                }
            }
        }

        SectionCard("سازگاری و محدودیت Android", "برای بیشترین سازگاری، اپ چند مسیر بروزرسانی را امتحان می‌کند") {
            Text("• اول manifest پایدار مالک پروژه و سپس GitHub Releases عمومی بررسی می‌شود. artifact خام GitHub Actions از داخل اپ دانلود نمی‌شود چون معمولاً بدون ورود GitHub خطای 401 می‌دهد.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary)
            Text("• Android اجازهٔ نصب بی‌صدا نمی‌دهد؛ اپ فایل را از داخل خودش می‌گیرد، اما نصب نهایی باید با صفحهٔ Package Installer تأیید شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextSecondary,
                modifier = Modifier.padding(top = 5.dp))
            Text("• برای اینکه بروزرسانی روی نصب قبلی بنشیند، امضای APK باید همان امضای نسخهٔ نصب‌شده باشد. بیلدهای CI اگر با کلید موقت متفاوت باشند، Android ممکن است نصب را رد کند.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.Gold,
                modifier = Modifier.padding(top = 5.dp))
            Text("• کد برنامه در Android به‌صورت امن با APK بروزرسانی می‌شود؛ فقط تنظیمات/داده‌های قابل پیکربندی می‌توانند بدون نصب APK تغییر کنند. قیمت، حجم یا سیگنال ساختگی برای بروزرسانی ساخته نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AurumColors.TextMuted,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun UpdateInfoCard(info: AppUpdateRepository.UpdateInfo) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("نسخه آماده: ${info.displayVersion}", style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.Gold, fontWeight = FontWeight.Bold)
        Text("منبع: ${info.sourceLabel}", style = MaterialTheme.typography.bodySmall,
            color = AurumColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        if (!info.canDownload) {
            Text("لینک مستقیم APK عمومی برای این نسخه هنوز تنظیم نشده؛ دکمهٔ دانلود تا زمان انتشار asset/manifest واقعی غیرفعال می‌ماند.",
                style = MaterialTheme.typography.labelSmall,
                color = AurumColors.Gold,
                modifier = Modifier.padding(top = 3.dp))
        }
        if (info.notes.isNotBlank()) {
            Text(info.notes, style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted, modifier = Modifier.padding(top = 3.dp))
        }
        info.sizeBytes?.takeIf { it > 0L }?.let { bytes ->
            Text("حجم تقریبی: ${formatUpdateBytes(bytes)}", style = MaterialTheme.typography.labelSmall,
                color = AurumColors.TextMuted, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

private fun formatUpdateBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "${(bytes + 524_287L) / 1_048_576L} MB"
    bytes >= 1_024L -> "${(bytes + 1_023L) / 1_024L} KB"
    else -> "$bytes B"
}
