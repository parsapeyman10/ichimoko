package com.aurum.edge.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurum.edge.core.AppSettings
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Credentials remain read-only and are never prefilled into editable fields. */
@Composable
fun ApiMenuScreen(viewModel: AurumViewModel, settings: AppSettings, onForexSettings: () -> Unit) {
    val context = LocalContext.current
    var twelveKey by remember { mutableStateOf("") }
    LaunchedEffect(settings.apiKey) { twelveKey = "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("Twelve Data") {
            Text(if (settings.hasKey) "کلید ثبت شده" else "کلید ثبت نشده",
                color = AurumColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = twelveKey, onValueChange = { twelveKey = it },
                label = { Text("کلید خواندنی Twelve Data") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                viewModel.saveMarketCredentials(twelveKey, settings.symbol)
                twelveKey = ""
            }, enabled = twelveKey.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("ذخیرهٔ کلید") }
            Text(
                "کلید عمومی بالا سهمیهٔ مشترک دارد (پلن رایگان: ۸ درخواست در دقیقه و ۸۰۰ در روز). " +
                    "برای اینکه یک نماد کل سهمیه را نخورد، پایین برای همان نماد کلید جدا بگذارید.",
                color = AurumColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionCard("کلید اختصاصی هر نماد") {
            Text(
                "هر کلید سهمیهٔ مستقل خودش را دارد؛ نمادی که کلید جدا دارد از کلید عمومی استفاده نمی‌کند. " +
                    "کلیدها فقط خواندنی‌اند و بعد از ذخیره نمایش داده نمی‌شوند.",
                color = AurumColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            TwelveKeyRows(viewModel = viewModel, settings = settings)
        }
    }
}

/**
 * One credential slot per catalog symbol, with the real coverage status of Twelve Data.
 * Typing a blank value and saving removes the dedicated key (the global one is used again).
 */
@Composable
private fun TwelveKeyRows(viewModel: AurumViewModel, settings: AppSettings) {
    com.aurum.edge.data.TwelveDataCoverage.entries.forEach { entry ->
        var input by remember(entry.symbol) { mutableStateOf("") }
        val dedicated = settings.twelveDataKeys[entry.symbol]?.isNotBlank() == true
        val state = when {
            dedicated -> "کلید اختصاصی دارد"
            settings.apiKey.isNotBlank() -> "از کلید عمومی استفاده می‌کند"
            else -> "بدون کلید"
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(entry.symbol, style = MaterialTheme.typography.titleSmall)
                Pill(entry.status.label,
                    color = if (entry.status == com.aurum.edge.data.TwelveDataCoverage.Status.FREE_SAME_ID)
                        AurumColors.Green else AurumColors.Orange)
            }
            Text(entry.note, color = AurumColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall)
            Text(state, color = AurumColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("کلید اختصاصی ${entry.symbol}") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { viewModel.saveDedicatedKey(entry.symbol, input); input = "" },
                    enabled = input.isNotBlank()) { Text("ذخیره") }
                if (dedicated) OutlinedButton(onClick = {
                    viewModel.saveDedicatedKey(entry.symbol, "")
                    input = ""
                }) { Text("حذف کلید اختصاصی") }
            }
        }
    }
}
