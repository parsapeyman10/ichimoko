package com.aurum.edge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurum.edge.ui.theme.AurumColors
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Translation is for DISPLAY only; the original publisher text remains the trading evidence. */
internal fun translationSnippet(title: String, excerpt: String = ""): String? {
    val head = title.trim().takeIf { it.isNotBlank() && it.length <= 220 &&
        it.none { ch -> Character.isISOControl(ch) } } ?: return null
    val summary = excerpt.trim().takeIf { it.length <= 260 &&
        it.none { ch -> Character.isISOControl(ch) } }.orEmpty()
    return if (summary.isBlank()) head else "$head\n$summary"
}

private suspend fun <T> Task<T>.awaitTranslation(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}

/** ML Kit downloads a language model on first use (~30 MB). No article text leaves the phone.
 * Request Wi-Fi unless the user explicitly taps the mobile-data retry button.
 */
internal object OnDeviceTranslation {
    private val mutex = Mutex()

    suspend fun englishToPersian(source: String, allowMobileData: Boolean): String = mutex.withLock {
        require(source.isNotBlank() && source.length <= 500 &&
            source.none { it != '\n' && Character.isISOControl(it) }) { "متن قابل ترجمه نیست" }
        val persian = TranslateLanguage.fromLanguageTag("fa") ?: error("مدل زبان فارسی پشتیبانی نمی‌شود")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(persian).build()
        val translator = Translation.getClient(options)
        try {
            val download = DownloadConditions.Builder().apply { if (!allowMobileData) requireWifi() }.build()
            translator.downloadModelIfNeeded(download).awaitTranslation()
            translator.translate(source).awaitTranslation().trim().takeIf { it.isNotBlank() }
                ?: error("ترجمه‌ای از مدل دریافت نشد")
        } finally {
            translator.close()
        }
    }
}

/** A button now translates IN THIS card; it never opens Google Translate or an outside app. */
@Composable
fun InlinePersianTranslation(source: String) {
    var attempt by remember(source) { mutableIntStateOf(0) }
    var mobileData by remember(source) { mutableStateOf(false) }
    var busy by remember(source) { mutableStateOf(false) }
    var translated by remember(source) { mutableStateOf<String?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, attempt) {
        if (attempt == 0) return@LaunchedEffect
        busy = true
        failed = false
        try {
            translated = OnDeviceTranslation.englishToPersian(source, mobileData)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true // unavailable model or network is not a fabricated translation
        } finally {
            busy = false
        }
    }
    Column {
        if (translated == null) OutlinedButton(onClick = { attempt++ }, enabled = !busy) {
            Text(if (busy) "ترجمه در حال آماده‌شدن…" else "ترجمهٔ فارسی همین‌جا")
        }
        translated?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = AurumColors.Cyan, modifier = Modifier.padding(top = 5.dp)) }
        if (failed) Text("مدل ترجمهٔ روی گوشی هنوز آماده نیست؛ متن انگلیسی بالا محفوظ است.",
            style = MaterialTheme.typography.labelSmall, color = AurumColors.Gold)
        if (attempt > 0 && translated == null && !mobileData) OutlinedButton(
            onClick = { mobileData = true; attempt++ }) {
            Text("دانلود مدل با اینترنت همراه (چند ده مگابایت)")
        }
    }
}
