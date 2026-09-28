package com.aurum.edge.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.aurum.edge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Sideload-friendly updater. Android still requires the system package installer confirmation;
 * no app can silently replace its own code outside managed/Play channels. The repository only
 * downloads real HTTPS APKs/artifacts and then hands them to PackageInstaller.
 */
class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(true).build(),
) {
    enum class SourceKind { MANIFEST_APK, ACTIONS_ZIP }

    data class UpdateInfo(
        val source: SourceKind,
        val sourceLabel: String,
        val versionName: String,
        val versionCode: Long?,
        val commitSha: String?,
        val notes: String,
        val downloadUrl: String,
        val expectedSha256: String? = null,
        val artifactName: String? = null,
        val sizeBytes: Long? = null,
    ) {
        val displayVersion: String get() = buildString {
            append(versionName)
            versionCode?.let { append(" ($it)") }
            commitSha?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(7)) }
        }
    }

    data class State(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val installing: Boolean = false,
        val progressPercent: Int? = null,
        val available: UpdateInfo? = null,
        val downloadedApkPath: String? = null,
        val downloadedVersionName: String? = null,
        val downloadedVersionCode: Long? = null,
        val needsInstallPermission: Boolean = false,
        val message: String = "برای بررسی نسخهٔ جدید، دکمهٔ بررسی را بزنید.",
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun checkForUpdate() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(checking = true, error = null,
            message = "در حال بررسی بروزرسانی امن…")
        val manifest = runCatching { checkManifest() }.getOrNull()
        val ci = runCatching { checkLatestSuccessfulCi() }.getOrNull()
        val update = listOfNotNull(manifest, ci).firstOrNull()
        _state.value = if (update == null) {
            State(message = "نسخهٔ نصب‌شده فعلاً آخرین نسخهٔ قابل دریافت است. نسخه ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA.take(7)}")
        } else {
            State(available = update,
                message = "نسخهٔ جدید پیدا شد: ${update.displayVersion} از ${update.sourceLabel}")
        }
    }

    suspend fun downloadAvailable() = withContext(Dispatchers.IO) {
        val info = _state.value.available ?: run {
            _state.value = _state.value.copy(error = "ابتدا بروزرسانی را بررسی کنید")
            return@withContext
        }
        _state.value = _state.value.copy(downloading = true, progressPercent = 0,
            error = null, message = "در حال دانلود ${info.sourceLabel}…")
        runCatching {
            val payload = download(info)
            val apk = when (info.source) {
                SourceKind.MANIFEST_APK -> payload
                SourceKind.ACTIONS_ZIP -> extractMatchingApk(payload)
            }
            val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: throw DataFeedException("APK دانلودشده قابل خواندن نیست")
            if (packageInfo.packageName != context.packageName) {
                throw DataFeedException("APK دانلودشده برای نصب فعلی نیست (${packageInfo.packageName})")
            }
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            if (versionCode < BuildConfig.VERSION_CODE) {
                throw DataFeedException("این فایل از نسخهٔ نصب‌شده قدیمی‌تر است و نصب نمی‌شود")
            }
            _state.value = _state.value.copy(
                downloading = false,
                progressPercent = 100,
                downloadedApkPath = apk.absolutePath,
                downloadedVersionName = packageInfo.versionName ?: info.versionName,
                downloadedVersionCode = versionCode,
                message = "دانلود آماده است؛ نصب از داخل برنامه شروع می‌شود اما تأیید نهایی با Android است.",
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(downloading = false, progressPercent = null,
                error = error.message ?: "دانلود یا آماده‌سازی بروزرسانی ناموفق بود")
        }
    }

    fun installDownloaded() {
        val apk = _state.value.downloadedApkPath?.let(::File)
        if (apk == null || !apk.isFile) {
            _state.value = _state.value.copy(error = "فایل APK آماده نیست؛ دوباره دانلود کنید")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(needsInstallPermission = true,
                message = "برای نصب درون‌برنامه‌ای باید اجازهٔ نصب از این برنامه را در Android بدهید.")
            openInstallPermissionSettings()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(context.contentResolver, "Aurum Edge update", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _state.value = _state.value.copy(installing = true,
            message = "نصاب Android باز شد؛ نصب را تأیید کنید. اگر امضا متفاوت باشد Android اجازهٔ بروزرسانی نمی‌دهد.")
        context.startActivity(intent)
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    private fun checkManifest(): UpdateInfo? {
        val url = BuildConfig.UPDATE_MANIFEST_URL.takeIf { it.startsWith("https://") } ?: return null
        val obj = requestJson(url) as? JsonObject ?: return null
        val versionCode = obj.long("versionCode") ?: return null
        if (versionCode <= BuildConfig.VERSION_CODE.toLong()) return null
        val applicationId = obj.string("applicationId")
        if (!applicationId.isNullOrBlank() && applicationId != context.packageName) return null
        val apkUrl = obj.string("apkUrl")?.takeIf { it.startsWith("https://") } ?: return null
        return UpdateInfo(
            source = SourceKind.MANIFEST_APK,
            sourceLabel = "انتشار پایدار",
            versionName = obj.string("versionName") ?: versionCode.toString(),
            versionCode = versionCode,
            commitSha = obj.string("commitSha"),
            notes = obj.string("notes") ?: "انتشار پایدار مالک پروژه",
            downloadUrl = apkUrl,
            expectedSha256 = obj.string("sha256"),
            sizeBytes = obj.long("sizeBytes"),
        )
    }

    private fun checkLatestSuccessfulCi(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        val branch = BuildConfig.UPDATE_BRANCH
        val runs = requestJson("https://api.github.com/repos/$repo/actions/runs?branch=$branch&status=success&per_page=10")
            .jsonObject["workflow_runs"]?.jsonArray ?: return null
        val run = runs.mapNotNull { it as? JsonObject }.firstOrNull() ?: return null
        if (run.string("head_sha")?.startsWith(BuildConfig.GIT_SHA) == true) return null
        val runId = run.long("id") ?: return null
        val artifacts = requestJson("https://api.github.com/repos/$repo/actions/runs/$runId/artifacts")
            .jsonObject["artifacts"]?.jsonArray ?: return null
        val artifact = artifacts.mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("name") == "aurum-edge-apk" && it.boolean("expired") != true }
            ?: return null
        val downloadUrl = artifact.string("archive_download_url") ?: return null
        val sha = run.string("head_sha")
        return UpdateInfo(
            source = SourceKind.ACTIONS_ZIP,
            sourceLabel = "بیلد آزمایشی GitHub Actions",
            versionName = "Preview CI",
            versionCode = null,
            commitSha = sha,
            notes = "بیلد خودکار شاخه $branch. برای بروزرسانی بدون حذف نصب، امضای APK باید با نسخهٔ نصب‌شده یکی باشد.",
            downloadUrl = downloadUrl,
            expectedSha256 = artifact.string("digest")?.removePrefix("sha256:"),
            artifactName = artifact.string("name"),
            sizeBytes = artifact.long("size_in_bytes"),
        )
    }

    private fun requestJson(url: String): kotlinx.serialization.json.JsonElement {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "AurumEdge/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("پاسخ بروزرسانی معتبر نیست (${response.code})")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw DataFeedException("پاسخ بروزرسانی خالی است")
            return json.parseToJsonElement(body)
        }
    }

    private fun download(info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete() }
        val extension = if (info.source == SourceKind.ACTIONS_ZIP) "zip" else "apk"
        val file = File(dir, "aurum-update-${info.commitSha?.take(12) ?: info.versionCode ?: System.currentTimeMillis()}.$extension")
        val request = Request.Builder().url(info.downloadUrl)
            .header("Accept", if (extension == "zip") "application/zip" else "application/vnd.android.package-archive")
            .header("User-Agent", "AurumEdge/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("دانلود بروزرسانی ناموفق بود (${response.code})")
            val body = response.body ?: throw DataFeedException("فایل بروزرسانی خالی است")
            val total = body.contentLength().takeIf { it > 0 }
            FileOutputStream(file).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        out.write(buffer, 0, read)
                        done += read
                        total?.let { length ->
                            val pct = ((done * 100) / length).toInt().coerceIn(0, 100)
                            if (pct != _state.value.progressPercent) {
                                _state.value = _state.value.copy(progressPercent = pct)
                            }
                        }
                    }
                }
            }
        }
        info.expectedSha256?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete()
                throw DataFeedException("اثر انگشت فایل بروزرسانی با منبع هم‌خوان نیست")
            }
        }
        return file
    }

    private fun extractMatchingApk(zipFile: File): File {
        val dir = File(context.cacheDir, "updates/extracted-${zipFile.nameWithoutExtension}").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        val candidates = mutableListOf<File>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = File(entry.name).name
                if (!entry.isDirectory && safeName.lowercase(Locale.ROOT).endsWith(".apk")) {
                    val outFile = File(dir, safeName)
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                    candidates += outFile
                }
                zip.closeEntry()
            }
        }
        if (candidates.isEmpty()) throw DataFeedException("داخل artifact هیچ APK پیدا نشد")
        val matching = candidates.mapNotNull { file ->
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return@mapNotNull null
            if (info.packageName == context.packageName) file to PackageInfoCompat.getLongVersionCode(info) else null
        }.maxByOrNull { it.second }
        return matching?.first ?: throw DataFeedException("هیچ APK سازگار با ${context.packageName} داخل artifact نبود")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String): Long? = when (val value = this[name]) {
        is JsonPrimitive -> value.contentOrNull?.toLongOrNull()
        else -> null
    }
    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}
