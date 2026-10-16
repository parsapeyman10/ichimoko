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
import com.aurum.edge.data.GeminiAiStudio
import com.aurum.edge.ui.components.Pill
import com.aurum.edge.ui.components.SectionCard
import com.aurum.edge.ui.theme.AurumColors

/** Credentials remain read-only and are never prefilled into editable fields. */
@Composable
fun ApiMenuScreen(viewModel: AurumViewModel, settings: AppSettings, onForexSettings: () -> Unit) {
    val context = LocalContext.current
    var twelveKey by remember { mutableStateOf("") }
    LaunchedEffect(settings.apiKey) { twelveKey = "" }
    val probe by viewModel.aiProbe.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionCard("AI · LLMsRelay / Gemini",
            trailing = { Pill(if (settings.hasClientNewsAi) "فعال" else "خاموش",
                if (settings.hasClientNewsAi) AurumColors.Green else AurumColors.TextMuted) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onForexSettings, modifier = Modifier.weight(1f)) { Text("مدیریت مدل") }
                Button(onClick = { viewModel.testNewsAiConnection("", "", "") },
                    enabled = !probe.running && settings.hasClientNewsAi,
                    modifier = Modifier.weight(1f)) { Text(if (probe.running) "در حال تست…" else "تست اتصال") }
            }
            probe.message?.let { Text(it, color = if (it.startsWith("اتصال تأیید")) AurumColors.Green else AurumColors.Red,
                style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("Gemini · API Keys") {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiAiStudio.KEYS_URL))) }
            }, modifier = Modifier.fillMaxWidth()) { Text("ساخت کلید Gemini") }
        }
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
        }
    }
}
