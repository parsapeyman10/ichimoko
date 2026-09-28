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

/**
 * Sideload-friendly updater. Android still requires the system package installer confirmation;
 * no app can silently replace its own code outside managed/Play channels. The repository only
 * downloads public HTTPS APKs and then hands them to PackageInstaller.
 */
class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(true).build(),
) {
    enum class SourceKind { MANIFEST_APK, RELEASE_APK }

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
        val release = runCatching { checkLatestRelease() }.getOrNull()
        val update = listOfNotNull(manifest, release).firstOrNull()
        _state.value = if (update == null) {
            State(message = "نسخهٔ نصب‌شده فعلاً آخرین نسخهٔ عمومی قابل دریافت است. نسخه ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA.take(7)}. artifactهای خام GitHub Actions از داخل اپ استفاده نمی‌شوند چون برای دانلود عمومی گاهی 401/نیاز به ورود GitHub می‌دهند؛ مسیر عمومی امن، Release یا manifest پایدار است.")
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
            val apk = downloadApk(info)
            val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                ?: throw DataFeedException("APK دانلودشده قابل خواندن نیست")
            if (packageInfo.packageName != context.packageName) {
                throw DataFeedException("APK دانلودشده برای نصب فعلی نیست (${packageInfo.packageName})")
            }
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            if (versionCode <= BuildConfig.VERSION_CODE) {
                throw DataFeedException("این فایل نسخهٔ جدیدتری از نصب فعلی نیست")
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
                error = if (error.message?.contains("401") == true)
                    "دانلود عمومی مجاز نبود (401). این مسیر معمولاً artifact خام GitHub Actions است؛ نسخهٔ اصلاح‌شده فقط Release/manifest عمومی را پیشنهاد می‌کند."
                else error.message ?: "دانلود یا آماده‌سازی بروزرسانی ناموفق بود")
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

    private fun checkLatestRelease(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        val releases = requestJson("https://api.github.com/repos/$repo/releases?per_page=10")
            .jsonArray.mapNotNull { it as? JsonObject }
        val release = releases.firstOrNull { it.boolean("draft") != true } ?: return null
        val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val asset = assets.firstOrNull { asset ->
            val name = asset.string("name").orEmpty().lowercase(Locale.ROOT)
            name.endsWith(".apk") && ("release" in name || "aurum" in name)
        } ?: return null
        val downloadUrl = asset.string("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
        val tag = release.string("tag_name").orEmpty()
        val versionName = release.string("name")?.takeIf { it.isNotBlank() } ?: tag.ifBlank { "GitHub Release" }
        val commitish = release.string("target_commitish")
        if (tag.contains(BuildConfig.VERSION_NAME) && commitish?.startsWith(BuildConfig.GIT_SHA) == true) return null
        return UpdateInfo(
            source = SourceKind.RELEASE_APK,
            sourceLabel = if (release.boolean("prerelease") == true) "GitHub Release آزمایشی" else "GitHub Release عمومی",
            versionName = versionName,
            versionCode = null,
            commitSha = commitish,
            notes = release.string("body")?.take(500).orEmpty().ifBlank {
                "فایل APK عمومی از GitHub Releases. برای بروزرسانی بدون حذف نصب، امضا باید با نسخهٔ فعلی یکی باشد."
            },
            downloadUrl = downloadUrl,
            artifactName = asset.string("name"),
            sizeBytes = asset.long("size"),
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

    private fun downloadApk(info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete() }
        val file = File(dir, "aurum-update-${info.commitSha?.take(12) ?: info.versionCode ?: System.currentTimeMillis()}.apk")
        val request = Request.Builder().url(info.downloadUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
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
