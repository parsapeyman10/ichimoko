package com.aurum.edge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AppSettings
import com.aurum.edge.service.SignalMonitorService
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Opt-in for read-only public feeds in the selected workspace; never starts Forex paper trading. */
@Composable
fun ReadOnlyMonitorCard(viewModel: AurumViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val running by SignalMonitorService.running.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val started = granted && SignalMonitorService.start(context)
        viewModel.setMonitorFlag(started)
    }
    SectionCard("پایش پژوهشی هنگام قفل گوشی", "فقط همین فضا · اعلان ثابت · بدون معامله یا سیگنال ورود") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (settings.backgroundMonitor && running) "سرویس فعال" else if (settings.backgroundMonitor) "در انتظار شروع سرویس" else "خاموش"} · تازه‌سازی با محدودیت سهمیهٔ هر منبع",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (settings.backgroundMonitor && running) AurumColors.Cyan else AurumColors.Gold)
            Switch(checked = settings.backgroundMonitor, onCheckedChange = { enabled ->
                if (!enabled) {
                    SignalMonitorService.stop(context)
                    viewModel.setMonitorFlag(false)
                } else {
                    val missing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                    if (missing) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.setMonitorFlag(SignalMonitorService.start(context))
                }
            })
        }
        Text("دادهٔ عمومی و تیتر تازهٔ همان فضا دوره‌ای بررسی می‌شود؛ اگر تیتر منتشرشده و خوراک تازه باشد، اعلان مستقل «خبر پژوهشی · نه معامله» ممکن است برسد. Doze، شبکه/سهمیهٔ ناشر و Android 15 (محدودیت dataSync حدود ۶ ساعت مجموع در ۲۴ ساعت پس‌زمینه) می‌توانند پایش را متوقف/عقب بیندازند. پاسخ قدیمی آنلاین یا قابل معامله نیست.",
            modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall,
            color = AurumColors.TextMuted)
    }
}
