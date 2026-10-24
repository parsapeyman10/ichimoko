package com.aurum.edge.data

import java.util.Locale

/**
 * Pure install-gate rules for the in-app updater.
 *
 * Every rule lives here instead of inside [AppUpdateRepository] so that it can be exercised on a
 * plain JVM: no Android, no downloaded file, no installer. The repository only feeds the values in
 * and turns a non-null message into a [DataFeedException], so the message the user sees on the
 * update screen is exactly the rule that stopped the install.
 *
 * Two verification routes exist because Android's package parser can fail on a perfectly valid
 * APK that this app downloaded into its own private cache: `PackageManager.getPackageArchiveInfo`
 * is answered by the system package service, which is a different process from the app. A parse
 * failure says nothing about the bytes that were downloaded, and the size and SHA-256 were already
 * checked against the published release. The fallback route therefore trusts the signer that the
 * release itself published (an immutable part of the sidecar) and still requires it to equal both
 * the signer on this phone and the pinned distribution certificate; Android's installer performs
 * the final, non-bypassable signature check when the APK is installed. Routes that cannot name a
 * signer fail closed.
 */
internal object UpdateVerification {

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    const val NO_INSTALLED_SIGNER =
        "گواهی نصب فعلی خوانده نشد؛ سازگاری امضا نامعلوم است. نصب ادامه نمی‌یابد."

    private const val PIN_MISMATCH =
        "APK منتشرشده با گواهی pin‌شدهٔ کانال انتشار مطابقت ندارد؛ نصب متوقف شد."

    fun sha256OrNull(value: String?): String? {
        val normalized = value?.trim()
            ?.removePrefix("sha256:")
            ?.removePrefix("SHA256:")
            ?.trim()
            ?: return null
        return normalized.takeIf { SHA256.matches(it) }?.lowercase(Locale.ROOT)
    }

    /**
     * Gate for an APK whose own manifest and certificates could be read on this device.
     * Returns the message to show, or null when the file may be handed to the installer.
     */
    fun signerProblem(
        installed: Set<String>,
        downloaded: Set<String>,
        enforcePin: Boolean,
        pinned: String,
    ): String? {
        if (installed.isEmpty()) return NO_INSTALLED_SIGNER
        if (downloaded.isEmpty()) return "گواهی APK دانلودشده خوانده نشد؛ نصب ادامه نمی‌یابد."
        // A single overlapping signer is insufficient for a multi-signer package: Android may
        // reject it. This updater does not support key rotation; fail closed instead.
        if (!sameCurrentSigners(installed, downloaded)) {
            return "امضای APK با نصب فعلی یکسان نیست (نصب: ${installed.sorted().joinToString()}؛ " +
                "دانلود: ${downloaded.sorted().joinToString()}). Android بروزرسانی را رد می‌کند. " +
                "بدون پشتیبان‌گیریِ مطمئن برنامه را حذف نکنید؛ حذف نصب داده‌های محلی را از بین می‌برد."
        }
        if (enforcePin && !sameCurrentSigners(downloaded, setOf(pinned.lowercase(Locale.ROOT)))) {
            return PIN_MISMATCH
        }
        return null
    }

    /**
     * Fallback gate used only when the device could not parse the file it just downloaded.
     * [publishedSigner] is the `signerSha256` that the release published next to the APK.
     */
    fun metadataSignerProblem(
        installed: Set<String>,
        publishedSigner: String?,
        enforcePin: Boolean,
        pinned: String,
    ): String? {
        if (installed.isEmpty()) return NO_INSTALLED_SIGNER
        val published = sha256OrNull(publishedSigner)
            ?: return "این انتشار گواهی APK را اعلام نکرده و خواندن مستقیم فایل هم روی این گوشی ممکن " +
                "نشد؛ برای امنیت نصب متوقف شد. نسخهٔ بعدی باید sidecar با signerSha256 منتشر کند."
        if (!sameCurrentSigners(installed, setOf(published))) {
            return "گواهی اعلام‌شدهٔ انتشار با امضای نصب فعلی یکسان نیست (نصب: " +
                "${installed.sorted().joinToString()}؛ انتشار: $published). Android بروزرسانی را رد می‌کند."
        }
        if (enforcePin && published != pinned.lowercase(Locale.ROOT)) return PIN_MISMATCH
        return null
    }

    /** Outcome of the version gate: the code to report, plus the message when it is refused. */
    data class VersionVerdict(val code: Long?, val problem: String?)

    /**
     * @param downloadedCode versionCode read from the APK, or null when the file was unreadable
     * @param declaredCode versionCode the published metadata claims for the same file
     * @param installedCode BuildConfig.VERSION_CODE of the running app
     */
    fun versionVerdict(downloadedCode: Long?, declaredCode: Long?, installedCode: Int): VersionVerdict {
        val effective = downloadedCode ?: declaredCode
            ?: return VersionVerdict(
                null,
                "نسخهٔ فایل بروزرسانی نه از داخل APK و نه از انتشار مشخص نشد؛ نصب ادامه نمی‌یابد.",
            )
        if (downloadedCode != null && declaredCode != null && downloadedCode < declaredCode) {
            return VersionVerdict(effective, "APK دریافت‌شده از نسخهٔ اعلام‌شده قدیمی‌تر است")
        }
        if (effective <= installedCode.toLong()) {
            return VersionVerdict(effective, "این فایل نسخهٔ جدیدتری از نصب فعلی نیست")
        }
        return VersionVerdict(effective, null)
    }
}
