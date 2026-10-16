package com.aurum.edge.data

import android.app.Application
import android.content.ContextWrapper
import com.aurum.edge.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end replay of what a phone sees on the public update channel, using the real
 * [AppUpdateRepository] with a stubbed HTTP layer. No network, no APK install, no signing keys.
 *
 * Regression targets for "the in-app update cannot be installed":
 *  - a newer published release must still be found when the main-branch manifest is stale/blocked;
 *  - a notes-only release must not hide the next release that carries an APK;
 *  - a reachable channel that has nothing newer is "you are current", not "check failed";
 *  - automatic checks keep a quota gap, the button always checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UpdaterChannelOutcomeTest {

    private val newerCode = BuildConfig.VERSION_CODE + 1
    private val digest = "b".repeat(64)
    private val apkName = "AurumEdge-v9.9.9.apk"
    private val tag = "v9.9.9"
    private val apkUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/$tag/$apkName"
    private val releaseManifestUrl =
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/aurum-edge.json"

    private var sidecarRequests = 0
    private var releaseListRequests = 0

    private fun context(): ContextWrapper = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName(): String = "com.aurum.edge"
    }

    /**
     * @param mainManifestStatus status for the manifest on the default branch (200/403/404…)
     * @param releaseManifestStatus status for the manifest next to the newest release asset
     * @param apiStatus status for the anonymous Releases API
     * @param adCode versionCode published in the release sidecar
     * @param notesOnlyFirstRelease reproduce a release that has notes but no APK asset
     */
    private fun repository(
        mainManifestStatus: Int = 200,
        releaseManifestStatus: Int = 404,
        apiStatus: Int = 200,
        adCode: Int = newerCode,
        notesOnlyFirstRelease: Boolean = false,
    ): AppUpdateRepository {
        val releases = buildString {
            append("[")
            if (notesOnlyFirstRelease) {
                append("""{"tag_name":"v9.9.10","name":"v9.9.10","draft":false,"prerelease":false,"assets":[]},""")
            }
            append(
                """{"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,
                    "assets":[
                      {"name":"$apkName","browser_download_url":"$apkUrl","size":40000000,"digest":"sha256:$digest"},
                      {"name":"$apkName.json","browser_download_url":"$apkUrl.json"}]},
                   {"tag_name":"v1.3.55","name":"v1.3.55","draft":false,"prerelease":false,
                    "assets":[{"name":"AurumEdge-v1.3.55.apk",
                      "browser_download_url":"https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/v1.3.55/AurumEdge-v1.3.55.apk"}]}"""
            )
            append("]")
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull("the phone carries no GitHub credentials", chain.request().header("Authorization"))
            val url = chain.request().url.toString()
            val (status, body) = when {
                url == releaseManifestUrl -> releaseManifestStatus to
                    """{"versionCode":$adCode,"versionName":"9.9.9","applicationId":"com.aurum.edge"}"""
                url == BuildConfig.UPDATE_MANIFEST_URL -> mainManifestStatus to
                    """{"versionCode":353,"versionName":"1.3.0","applicationId":"com.aurum.edge"}"""
                url.startsWith("https://api.github.com/repos/") -> {
                    releaseListRequests++
                    apiStatus to releases
                }
                url == "$apkUrl.json" -> {
                    sidecarRequests++
                    200 to """{"applicationId":"com.aurum.edge","versionCode":$adCode,"versionName":"9.9.9",
                        "sha256":"$digest","sizeBytes":40000000,
                        "signerSha256":"${BuildConfig.UPDATER_CERT_SHA256}"}"""
                }
                else -> error("Unexpected update request: $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AppUpdateRepository(context(), http)
    }

    @Test fun `a newer published release is found while the main manifest is stale`() = runBlocking {
        val repo = repository(mainManifestStatus = 200, releaseManifestStatus = 404)
        repo.checkForUpdate(force = true)
        val update = repo.state.value.available
        assertNotNull("the published release must be offered", update)
        assertEquals(AppUpdateRepository.SourceKind.RELEASE_APK, update!!.source)
        assertTrue(update.canDownload)
        assertEquals(apkUrl, update.downloadUrl)
        assertEquals(digest, update.expectedSha256)
        assertEquals(40_000_000L, update.sizeBytes)
        assertNull(repo.state.value.error)
        assertTrue(repo.state.value.diagnostics.contains("releases"))
    }

    @Test fun `a blocked main manifest reports the reason instead of failing the whole check`() = runBlocking {
        val repo = repository(mainManifestStatus = 403, releaseManifestStatus = 404)
        repo.checkForUpdate(force = true)
        assertNotNull("a blocked main-branch manifest must not abort discovery", repo.state.value.available)
        assertNull("the release channel still answered, so this is not a failed check", repo.state.value.error)
        assertTrue(repo.state.value.diagnostics.isNotBlank())
    }

    @Test fun `a notes-only release does not hide the next release that has an APK`() = runBlocking {
        val repo = repository(notesOnlyFirstRelease = true)
        repo.checkForUpdate(force = true)
        assertNotNull(repo.state.value.available)
        assertEquals(tag, repo.state.value.available!!.tagName)
    }

    @Test fun `an install already on the newest code is told it is current, not that the check failed`() =
        runBlocking {
            val repo = repository(adCode = BuildConfig.VERSION_CODE)
            repo.checkForUpdate(force = true)
            assertNull(repo.state.value.available)
            assertNull("a reachable channel without a newer build is not an error", repo.state.value.error)
            assertTrue(repo.state.value.message.contains("نسخهٔ جدیدتری"))
        }

    @Test fun `only the newest release sidecar is fetched`() = runBlocking {
        val repo = repository()
        repo.checkForUpdate(force = true)
        assertNotNull(repo.state.value.available)
        assertEquals("older releases must not be probed with extra requests",
            1, sidecarRequests)
    }

    @Test fun `automatic checks keep an API-quota gap while the button still forces a check`() = runBlocking {
        val repo = repository()
        repo.checkForUpdate(force = true)
        val afterFirst = releaseListRequests
        repo.checkForUpdate(force = false)
        assertEquals("an automatic re-check inside the gap must not spend API quota",
            afterFirst, releaseListRequests)
        assertTrue(repo.state.value.message.contains("بررسی فوری"))
        repo.checkForUpdate(force = true)
        assertTrue("the check button always checks now", releaseListRequests > afterFirst)
    }

    @Test fun `both channels down is reported as a failed check with host reasons`() = runBlocking {
        val repo = repository(mainManifestStatus = 403, releaseManifestStatus = 404, apiStatus = 403)
        repo.checkForUpdate(force = true)
        assertNull(repo.state.value.available)
        assertNotNull("with every channel down the user must see why", repo.state.value.error)
        assertTrue(repo.state.value.diagnostics.contains("releases"))
    }
}
