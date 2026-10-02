package com.aurum.edge.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * The manifest is metadata, not an APK. When it has no apkUrl we still check public GitHub
 * Releases, so a missing/old manifest asset cannot mask a downloadable release.
 */
class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(true).build(),
) {
    companion object {
        // This is deliberately narrower than "any .apk". It prevents a debug APK, a random
        // third-party asset, or a similarly named file from becoming an install candidate.
        private val RELEASE_APK_NAME = Regex("^AurumEdge-v\\d+(?:\\.\\d+)*\\.apk$")
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }

    enum class SourceKind { MANIFEST_APK, MANIFEST_METADATA, RELEASE_APK }

    data class UpdateInfo(
        val source: SourceKind,
        val sourceLabel: String,
        val versionName: String,
        val versionCode: Long?,
        val commitSha: String?,
        val notes: String,
        val downloadUrl: String? = null,
        val expectedSha256: String? = null,
        val artifactName: String? = null,
        val sizeBytes: Long? = null,
        val tagName: String? = null,
        val publishedAt: String? = null,
    ) {
        val displayVersion: String get() = buildString {
            append(versionName)
            versionCode?.let { append(" ($it)") }
            commitSha?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(7)) }
        }
        val canDownload: Boolean get() = downloadUrl?.startsWith("https://") == true
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
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Checks both update channels. [autoDownload] only starts the download; the ViewModel opens
     * Android's installer after this returns so the app remains on the main thread for UI work.
     */
    suspend fun checkForUpdate(autoDownload: Boolean = false) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(
                checking = true,
                error = null,
                message = "در حال بررسی بروزرسانی امن…",
            )

            val manifestResult = runCatching { checkManifest() }
            val releaseResult = runCatching { checkLatestRelease() }
            val manifest = manifestResult.getOrNull()
            val release = releaseResult.getOrNull()
            val update = chooseUpdate(manifest, release)
            val failures = listOfNotNull(
                manifestResult.exceptionOrNull()?.message,
                releaseResult.exceptionOrNull()?.message,
            )

            when {
                update == null && failures.isNotEmpty() -> {
                    _state.value = State(
                        message = "بررسی کامل بروزرسانی انجام نشد؛ اینترنت/دسترسی manifest و GitHub را بررسی کنید.",
                        error = failures.joinToString(" · ").take(360),
                    )
                }
                update == null -> {
                    _state.value = State(
                        message = "نسخهٔ نصب‌شده فعلاً آخرین نسخهٔ عمومی قابل دریافت است. نسخه ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA.take(7)}. برای دانلود باید APK در manifest یا یک GitHub Release عمومی منتشر شده باشد.",
                    )
                }
                !update.canDownload -> {
                    _state.value = State(
                        available = update,
                        message = "نسخهٔ بالاتر دیده شد: ${update.displayVersion}، اما APK عمومی آن هنوز منتشر نشده است. apkUrl manifest یا asset یک GitHub Release لازم است؛ artifact خام Actions قابل دانلود عمومی نیست.",
                    )
                }
                else -> {
                    _state.value = State(
                        available = update,
                        message = "نسخهٔ جدید پیدا شد: ${update.displayVersion} از ${update.sourceLabel}",
                    )
                    if (autoDownload) downloadAvailableLocked()
                }
            }
        }
    }

    suspend fun downloadAvailable() = operationMutex.withLock {
        withContext(Dispatchers.IO) { downloadAvailableLocked() }
    }

    /** Must be called while [operationMutex] is held and on an IO dispatcher. */
    private fun downloadAvailableLocked() {
        val info = _state.value.available ?: run {
            _state.value = _state.value.copy(error = "ابتدا بروزرسانی را بررسی کنید")
            return
        }
        if (!info.canDownload) {
            _state.value = _state.value.copy(
                error = "برای این نسخه لینک مستقیم APK عمومی تنظیم نشده است؛ apkUrl manifest یا asset عمومی GitHub Release لازم است.",
                downloading = false,
                progressPercent = null,
            )
            return
        }
        _state.value = _state.value.copy(
            downloading = true,
            progressPercent = 0,
            error = null,
            message = "در حال دانلود ${info.sourceLabel}…",
        )
        runCatching {
            val apk = downloadApk(info)
            @Suppress("DEPRECATION")
            val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
                ?: throw DataFeedException("APK دانلودشده قابل خواندن نیست")
            if (packageInfo.packageName != context.packageName) {
                throw DataFeedException(
                    "این APK برای ${packageInfo.packageName} است، اما نصب فعلی ${context.packageName} است؛ نسخهٔ debug و release جدا هستند.",
                )
            }
            val installedInfo = context.packageManager.getPackageInfo(context.packageName, archiveFlags)
            val installedSigners = signingCertificateDigests(installedInfo)
            val downloadedSigners = signingCertificateDigests(packageInfo)
            if (installedSigners.isEmpty() || downloadedSigners.isEmpty() ||
                installedSigners.intersect(downloadedSigners).isEmpty()
            ) {
                throw DataFeedException(
                    "امضای نسخهٔ نصب‌شده با کانال Release یکی نیست. این نصب از artifact/کلید قدیمی آمده و Android اجازهٔ بروزرسانی مستقیم نمی‌دهد. راه درست: یک‌بار نسخهٔ فعلی را حذف کن و APK رسمی GitHub Release را نصب کن؛ از نصب بعدی، بروزرسانی داخل اپ با همین کلید ثابت انجام می‌شود.",
                )
            }
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            if (info.versionCode != null && versionCode < info.versionCode) {
                throw DataFeedException("APK دریافت‌شده از نسخهٔ اعلام‌شده قدیمی‌تر است")
            }
            if (versionCode <= BuildConfig.VERSION_CODE) {
                throw DataFeedException("این فایل نسخهٔ جدیدتری از نصب فعلی نیست")
            }
            _state.value = _state.value.copy(
                checking = false,
                downloading = false,
                progressPercent = 100,
                downloadedApkPath = apk.absolutePath,
                downloadedVersionName = packageInfo.versionName ?: info.versionName,
                downloadedVersionCode = versionCode,
                needsInstallPermission = false,
                message = "دانلود کامل شد؛ نصب خودکار آغاز می‌شود و تأیید نهایی با Android است.",
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                checking = false,
                downloading = false,
                progressPercent = null,
                error = if (error.message?.contains("401") == true)
                    "دانلود عمومی مجاز نبود (401). artifact خام GitHub Actions قابل استفاده نیست؛ APK را به Release عمومی منتقل کنید."
                else error.message ?: "دانلود یا آماده‌سازی بروزرسانی ناموفق بود",
                message = "بروزرسانی دانلود نشد؛ نسخهٔ فعلی همچنان حفظ شده است.",
            )
        }
    }

    fun installDownloaded() {
        val apk = _state.value.downloadedApkPath?.let(::File)
        if (apk == null || !apk.isFile) {
            _state.value = _state.value.copy(error = "فایل APK آماده نیست؛ دوباره دانلود کنید")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallUnknownSources()) {
            _state.value = _state.value.copy(
                needsInstallPermission = true,
                message = "برای نصب خودکار باید اجازهٔ نصب از این برنامه را در Android بدهید؛ بعد از بازگشت نصب دوباره ادامه پیدا می‌کند.",
            )
            openInstallPermissionSettings()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newUri(context.contentResolver, "Aurum Edge update", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(intent)
            _state.value = _state.value.copy(
                installing = true,
                needsInstallPermission = false,
                message = "نصاب Android باز شد؛ نصب را تأیید کنید. اگر امضا متفاوت باشد Android اجازهٔ بروزرسانی نمی‌دهد.",
            )
        }.onFailure { error ->
            _state.value = _state.value.copy(
                installing = false,
                error = error.message ?: "باز کردن نصاب Android ناموفق بود",
            )
        }
    }

    /** Called after the user returns from Android's unknown-app-source settings. */
    fun resumePendingInstall() {
        if (_state.value.needsInstallPermission && canInstallUnknownSources()) installDownloaded()
    }

    private fun canInstallUnknownSources(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    private fun chooseUpdate(manifest: UpdateInfo?, release: UpdateInfo?): UpdateInfo? {
        // Prefer a real downloadable APK over metadata-only manifest information. The previous
        // implementation selected metadata first, which disabled the download button even when
        // GitHub Releases already contained the correct public APK.
        val downloadable = listOfNotNull(manifest, release).filter { it.canDownload }
        if (downloadable.isNotEmpty()) {
            return downloadable.maxWithOrNull(compareBy<UpdateInfo> { versionRank(it) }
                .thenBy { it.source == SourceKind.MANIFEST_APK })
        }
        return manifest ?: release
    }

    private fun versionRank(info: UpdateInfo): Long {
        info.versionCode?.let { return it * 1_000_000L }
        val parts = semanticVersion(info.versionName) ?: return 0L
        return parts.fold(0L) { value, part -> value * 1_000L + part.toLong() }
    }

    private fun checkManifest(): UpdateInfo? {
        val url = BuildConfig.UPDATE_MANIFEST_URL.takeIf { it.startsWith("https://") } ?: return null
        val obj = requestJson(url) as? JsonObject ?: return null
        val versionCode = obj.long("versionCode") ?: return null
        if (versionCode <= BuildConfig.VERSION_CODE.toLong()) return null
        val applicationId = obj.string("applicationId")
        if (!applicationId.isNullOrBlank() && applicationId != context.packageName) return null
        val artifactName = obj.string("artifactName")?.trim()
        val apkUrl = obj.string("apkUrl")?.trim()?.takeIf {
            isTrustedReleaseAsset(it, artifactName ?: "")
        }
        val digest = normalizeSha256(obj.string("sha256"))
        return UpdateInfo(
            source = if (apkUrl == null) SourceKind.MANIFEST_METADATA else SourceKind.MANIFEST_APK,
            sourceLabel = if (apkUrl == null) "manifest نسخهٔ جدید بدون APK عمومی" else "انتشار پایدار",
            versionName = obj.string("versionName")?.takeIf { it.isNotBlank() } ?: versionCode.toString(),
            versionCode = versionCode,
            commitSha = obj.string("commitSha"),
            notes = obj.string("notes") ?: "انتشار پایدار مالک پروژه",
            downloadUrl = apkUrl,
            expectedSha256 = digest,
            artifactName = artifactName,
            sizeBytes = obj.long("sizeBytes")?.takeIf { it > 0L },
            tagName = obj.string("tagName") ?: obj.string("tag"),
            publishedAt = obj.string("publishedAt"),
        )
    }

    private fun checkLatestRelease(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        val releases = requestJson("https://api.github.com/repos/$repo/releases?per_page=10")
            .jsonArray.mapNotNull { it as? JsonObject }
            .filter { it.boolean("draft") != true && it.boolean("prerelease") != true }
        val currentVersion = semanticVersion(BuildConfig.VERSION_NAME)
        // Do not stop at a release that has no APK. A notes-only release must not hide the next
        // public release asset, and a debug APK must never be offered as an upgrade.
        return releases.asSequence().mapNotNull { release ->
            val tag = release.string("tag_name").orEmpty()
            val releaseName = release.string("name")?.takeIf { it.isNotBlank() } ?: tag
            val displayVersion = tag.ifBlank { releaseName.ifBlank { "GitHub Release" } }
            val assets = release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            val asset = assets.firstOrNull { candidate ->
                val name = candidate.string("name").orEmpty()
                val url = candidate.string("browser_download_url").orEmpty()
                isTrustedReleaseAsset(url, name)
            } ?: return@mapNotNull null
            val downloadUrl = asset.string("browser_download_url")!!
            val artifactName = asset.string("name")!!
            // The sidecar metadata is authoritative for rolling updater releases: the tag can stay
            // v1.2.9 while CI raises versionCode on every published build.
            val metadata = assets.firstOrNull { candidate ->
                val name = candidate.string("name").orEmpty()
                val url = candidate.string("browser_download_url").orEmpty()
                name == "$artifactName.json" && isTrustedMetadataAsset(url, name)
            }?.string("browser_download_url")?.let { metadataUrl ->
                runCatching { requestJson(metadataUrl).jsonObject }.getOrNull()
            }
            val metadataVersionCode = metadata?.long("versionCode")
            if (metadataVersionCode != null) {
                if (metadataVersionCode <= BuildConfig.VERSION_CODE.toLong()) return@mapNotNull null
            } else if (currentVersion != null && semanticVersion(displayVersion)?.let {
                    compareVersions(it, currentVersion) <= 0
                } == true) return@mapNotNull null
            val commitish = metadata?.string("commitSha")?.takeIf { it.isNotBlank() }
                ?: release.string("target_commitish")
            UpdateInfo(
                source = SourceKind.RELEASE_APK,
                sourceLabel = "GitHub Release عمومی",
                versionName = metadata?.string("versionName")?.takeIf { it.isNotBlank() } ?: displayVersion,
                versionCode = metadataVersionCode,
                commitSha = commitish,
                notes = release.string("body")?.take(500).orEmpty().ifBlank {
                    metadata?.string("notes")?.take(500).orEmpty().ifBlank {
                        "فایل APK عمومی از GitHub Releases. برای بروزرسانی بدون حذف نصب، امضا باید با نسخهٔ فعلی یکی باشد."
                    }
                },
                downloadUrl = downloadUrl,
                expectedSha256 = normalizeSha256(asset.string("digest"))
                    ?: normalizeSha256(metadata?.string("sha256")),
                artifactName = artifactName,
                sizeBytes = asset.long("size")?.takeIf { it > 0L }
                    ?: metadata?.long("sizeBytes")?.takeIf { it > 0L },
                tagName = tag,
                publishedAt = release.string("published_at"),
            )
        }.firstOrNull()
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
        dir.listFiles()?.forEach {
            if (it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000L) it.delete()
        }
        val token = safeFileToken(info.commitSha ?: info.versionCode?.toString() ?: info.versionName)
        val file = File(dir, "aurum-update-$token.apk")
        val partial = File(dir, "$token.part")
        partial.delete()
        val downloadUrl = info.downloadUrl ?: throw DataFeedException("برای این نسخه لینک مستقیم APK عمومی تنظیم نشده است")
        if (info.sizeBytes != null && info.sizeBytes > MAX_APK_BYTES) {
            throw DataFeedException("حجم APK منتشرشده از سقف مجاز بروزرسانی بیشتر است")
        }
        val request = Request.Builder().url(downloadUrl)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
            .header("User-Agent", "AurumEdge/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DataFeedException("دانلود بروزرسانی ناموفق بود (${response.code})")
            if (!response.request.url.isHttps) throw DataFeedException("دانلود فقط از HTTPS مجاز است")
            val body = response.body ?: throw DataFeedException("فایل بروزرسانی خالی است")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_APK_BYTES) {
                throw DataFeedException("حجم APK دانلودی از سقف مجاز بروزرسانی بیشتر است")
            }
            val total = declaredLength.takeIf { it > 0L }
            FileOutputStream(partial).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw DataFeedException("حجم APK دانلودی از سقف مجاز بروزرسانی بیشتر است")
                        total?.let { length ->
                            val pct = ((done * 100) / length).toInt().coerceIn(0, 100)
                            if (pct != _state.value.progressPercent)
                                _state.value = _state.value.copy(progressPercent = pct)
                        }
                    }
                    out.flush()
                    if (done == 0L) throw DataFeedException("فایل بروزرسانی خالی است")
                }
            }
        }
        if (file.exists()) file.delete()
        if (!partial.renameTo(file)) {
            partial.delete()
            throw DataFeedException("ذخیرهٔ امن فایل بروزرسانی ممکن نشد")
        }
        info.sizeBytes?.let { expectedSize ->
            if (file.length() != expectedSize) {
                file.delete()
                throw DataFeedException("حجم APK دریافت‌شده با metadata انتشار هم‌خوان نیست")
            }
        }
        info.expectedSha256?.let { expected ->
            val normalized = normalizeSha256(expected).orEmpty()
            if (normalized.isNotBlank()) {
                val actual = sha256(file)
                if (!actual.equals(normalized, ignoreCase = true)) {
                    file.delete()
                    throw DataFeedException("اثر انگشت فایل بروزرسانی با منبع هم‌خوان نیست")
                }
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

    /**
     * Check the signing certificate before opening Package Installer. Android performs the final
     * check too, but doing it here gives a useful error and prevents presenting a known-incompatible
     * debug/release APK to the user. Key rotation is intentionally conservative: at least one
     * current content signer must match.
     */
    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Only the configured repository's release-download route is accepted. */
    private fun isTrustedReleaseAsset(url: String, name: String): Boolean {
        if (!RELEASE_APK_NAME.matches(name)) return false
        val prefix = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/"
        return url.startsWith(prefix) &&
            url.substringAfterLast('/') == name &&
            '?' !in url && '#' !in url
    }

    private fun isTrustedMetadataAsset(url: String, name: String): Boolean {
        val prefix = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/"
        return name.matches(Regex("^AurumEdge-v\\d+(?:\\.\\d+)*\\.apk\\.json$")) &&
            url.startsWith(prefix) &&
            url.substringAfterLast('/') == name &&
            '?' !in url && '#' !in url
    }

    private fun normalizeSha256(value: String?): String? {
        val normalized = value?.trim()
            ?.removePrefix("sha256:")
            ?.removePrefix("SHA256:")
            ?.trim()
            ?: return null
        return normalized.takeIf { SHA256.matches(it) }?.lowercase(Locale.ROOT)
    }

    private fun safeFileToken(value: String): String = value
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(40)
        .ifBlank { System.currentTimeMillis().toString() }

    private fun semanticVersion(value: String): List<Int>? =
        Regex("(?<!\\d)(\\d+)(?:\\.(\\d+))(?:\\.(\\d+))?(?:\\.(\\d+))?")
            .find(value)?.groupValues?.drop(1)?.filter { it.isNotBlank() }?.map { it.toIntOrNull() ?: 0 }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val result = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String): Long? = when (val value = this[name]) {
        is JsonPrimitive -> value.contentOrNull?.toLongOrNull()
        else -> null
    }
    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}
