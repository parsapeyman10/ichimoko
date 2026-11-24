package com.aurum.edge.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aurum.edge.service.DailyGithubBackupWorker
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object DailyGithubBackup {
    // Only the session branch is modified. Public repository: payload is always encrypted first.
    const val BRANCH = "arena/7db61d5d-ichimoko"
    private const val REPO_API = "https://api.github.com/repos/parsapeyman10/ichimoko/contents"
    private const val PERIODIC = "daily-github-backup"
    private const val MANUAL = "daily-github-backup-now"
    private val client by lazy { AppNetwork.builder().build() }
    private val mutex = Any()
    data class DeviceCode(val code: String, val deviceCode: String, val interval: Long, val expiresIn: Long)

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: throw IOException("GitHub empty response"))
        }
    }
    suspend fun begin(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        require(clientId.matches(Regex("[A-Za-z0-9._-]{10,100}"))) { "شناسهٔ برنامه نامعتبر است" }
        val obj = postForm("https://github.com/login/device/code", mapOf("client_id" to clientId))
        DeviceCode(obj.getString("user_code"), obj.getString("device_code"), obj.optLong("interval", 5).coerceAtLeast(5), obj.optLong("expires_in", 900))
    }
    suspend fun authorize(context: Context, clientId: String, password: String, code: DeviceCode) = withContext(Dispatchers.IO) {
        require(password.length >= 12) { "عبارت رمزگذاری باید حداقل ۱۲ نویسه باشد" }
        var seconds = code.interval
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(seconds * 1000)
            val result = postForm("https://github.com/login/oauth/access_token", mapOf(
                "client_id" to clientId, "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            val error = result.optString("error")
            if (error == "authorization_pending") continue
            if (error == "slow_down") { seconds += 5; continue }
            if (error.isNotEmpty()) throw IOException("تأیید گیت‌هاب انجام نشد ($error)")
            val access = result.optString("access_token")
            if (access.isBlank()) throw IOException("پاسخ تأیید گیت‌هاب ناقص است")
            synchronized(mutex) {
                val old = runCatching { DailyBackupSecrets(context).read() }.getOrNull()
                val record = JSONObject().put("clientId", clientId).put("password", password)
                    .put("access", access).put("refresh", result.optString("refresh_token"))
                    .put("expiresAt", if (result.has("expires_in")) System.currentTimeMillis() + result.optLong("expires_in") * 1000 else 0L)
                    .put("deviceId", old?.optString("deviceId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
                DailyBackupSecrets(context).write(record)
            }
            status(context, "اتصال ثبت شد؛ ارسال نخست در صف است")
            schedule(context)
            return@withContext
        }
        throw IOException("زمان تأیید گیت‌هاب تمام شد")
    }
    private fun refreshIfNeeded(context: Context, record: JSONObject): String {
        val expires = record.optLong("expiresAt", 0)
        if (expires == 0L || expires > System.currentTimeMillis() + 120_000L) return record.getString("access")
        val refresh = record.optString("refresh")
        if (refresh.isBlank()) throw IOException("اتصال گیت‌هاب منقضی شده؛ دوباره متصل شوید")
        val result = postForm("https://github.com/login/oauth/access_token", mapOf(
            "client_id" to record.getString("clientId"), "refresh_token" to refresh, "grant_type" to "refresh_token"))
        if (!result.has("access_token")) throw IOException("نوسازی اتصال گیت‌هاب ناموفق؛ اتصال را بررسی کنید")
        record.put("access", result.getString("access_token")).put("refresh", result.optString("refresh_token"))
            .put("expiresAt", System.currentTimeMillis() + result.optLong("expires_in", 0) * 1000)
        DailyBackupSecrets(context).write(record)
        return record.getString("access")
    }
    private fun api(method: String, url: String, token: String, body: String? = null): Pair<Int, String> {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token").header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    }
    suspend fun send(context: Context) = withContext(Dispatchers.IO) {
        val payload = DailyBackupSnapshot.make(context)
        synchronized(mutex) {
            val secrets = DailyBackupSecrets(context)
            val record = secrets.read() ?: throw IOException("اتصال و رمزگذاری را در تنظیمات فعال کنید")
            val pass = record.getString("password").toCharArray()
            val encrypted = try { DailyBackupCrypto.encrypt(payload, pass) } finally { pass.fill('\u0000') }
            if (encrypted.size > 20_000_000) throw IOException("حجم بستهٔ رمزگذاری‌شده بیش از ۲۰ مگابایت است")
            val token = refreshIfNeeded(context, record)
            val date = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString()
            val id = record.getString("deviceId")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            val url = "$REPO_API/daily-backups/$id/$date.json"
            val query = url.toHttpUrl().newBuilder().addQueryParameter("ref", BRANCH).build().toString()
            val (getCode, getBody) = api("GET", query, token)
            val sha = when (getCode) {
                200 -> JSONObject(getBody).getString("sha")
                404 -> null
                else -> throw IOException("خواندن مقصد گیت‌هاب ناموفق: HTTP $getCode")
            }
            val body = JSONObject().put("message", "Encrypted daily backup $date")
                .put("branch", BRANCH).put("content", Base64.getEncoder().encodeToString(encrypted))
            if (sha != null) body.put("sha", sha)
            val (putCode, _) = api("PUT", url, token, body.toString())
            if (putCode !in 200..299) throw IOException("ارسال گیت‌هاب ناموفق: HTTP $putCode؛ نصب برنامه و دسترسی نوشتن را بررسی کنید")
            status(context, "آخرین ارسال موفق UTC: $date · ${Instant.now()}")
        }
    }
    fun status(context: Context, message: String) {
        context.getSharedPreferences("daily_backup_status", Context.MODE_PRIVATE).edit().putString("message", message).apply()
    }
    fun currentStatus(context: Context): String = context.getSharedPreferences("daily_backup_status", Context.MODE_PRIVATE)
        .getString("message", "هنوز راه‌اندازی نشده") ?: "هنوز راه‌اندازی نشده"
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<DailyGithubBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        sendNow(context)
    }
    fun sendNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<DailyGithubBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, req)
    }
    fun disconnect(context: Context) {
        synchronized(mutex) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL)
            DailyBackupSecrets(context).clear()
            status(context, "ارسال خودکار غیرفعال شد")
        }
    }
}
