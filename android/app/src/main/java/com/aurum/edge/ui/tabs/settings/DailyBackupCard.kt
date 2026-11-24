package com.aurum.edge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aurum.edge.data.DailyGithubBackup
import com.aurum.edge.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DailyBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(DailyGithubBackup.currentStatus(context)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!busy) message = DailyGithubBackup.currentStatus(context)
            delay(2_000)
        }
    }
    SectionCard(title = "🔐 ارسال روزانهٔ رمزگذاری‌شده به گیت", initiallyExpanded = false) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("هر ۲۴ ساعت با اینترنت؛ ممکن است اندروید اجرا را عقب بیندازد. معاملات، حافظهٔ هوش و تنظیمات بدون کلیدهای خدمات ارسال می‌شوند. اجرای اول فوری است. مقصد: شاخهٔ همین درخواست؛ مخزن عمومی است و تنها متن رمزگذاری‌شده منتشر می‌شود.")
            Text("یک GitHub App اختصاصی با Device Flow و مجوز Contents: Read and write بسازید و فقط روی همین مخزن نصب کنید؛ شناسهٔ عمومی Client ID آن را اینجا وارد کنید. هیچ رمز یا توکن گیت‌هاب در برنامه وارد نکنید.")
            OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() },
                label = { Text("Client ID عمومی GitHub App") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = passphrase, onValueChange = { passphrase = it },
                label = { Text("عبارت رمزگذاری جدید، حداقل ۱۲ نویسه") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("عبارت را نزد خود نگه دارید؛ بدون آن نسخه‌ها قابل بازیابی یا تحلیل نیستند. زمان دقیق و یکپارچگی هم‌زمان همهٔ اجزا تضمین نمی‌شود.")
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        require(passphrase.length >= 12) { "عبارت رمزگذاری باید حداقل ۱۲ نویسه باشد" }
                        val code = DailyGithubBackup.begin(clientId)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("GitHub device code", code.code))
                        message = "کد ${code.code} کپی شد؛ آن را در مرورگر وارد و اتصال را تأیید کنید."
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device")))
                        DailyGithubBackup.authorize(context, clientId, passphrase, code)
                        passphrase = ""
                        message = DailyGithubBackup.currentStatus(context)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        message = "اتصال ناموفق: ${e.message ?: "خطا"}"
                        DailyGithubBackup.status(context, message)
                    }
                    finally { busy = false }
                }
            }) { Text(if (busy) "در انتظار تأیید…" else "اتصال و فعال‌سازی") }
            Button(enabled = !busy, onClick = {
                DailyGithubBackup.sendNow(context)
                message = "ارسال فوری در صف است"
                DailyGithubBackup.status(context, message)
            }) { Text("ارسال فوری") }
            Button(enabled = !busy, onClick = {
                DailyGithubBackup.disconnect(context)
                message = DailyGithubBackup.currentStatus(context)
            }) { Text("قطع ارسال خودکار") }
            Text(message)
        }
    }
}
