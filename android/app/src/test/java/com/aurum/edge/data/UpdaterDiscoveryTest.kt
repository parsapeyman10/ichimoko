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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** No APK install or network access. Proves that the installed update-channel design can
 * discover a public sidecar even when the old main-branch manifest is stale/unreachable. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class UpdaterDiscoveryTest {
    private val apkName = "AurumEdge-v1.3.51.apk"
    private val apkUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/v1.3.51/$apkName"
    private val digest = "a".repeat(64)
    private val nextCode = BuildConfig.VERSION_CODE + 1

    private fun repository(manifestCode: Int, publicCode: Int = nextCode, draft: Boolean = false,
                           prerelease: Boolean = false): AppUpdateRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization")) // no GitHub credentials on the phone
            val url = chain.request().url.toString()
            val (status, body) = when {
                url == BuildConfig.UPDATE_MANIFEST_URL -> manifestCode to """{"versionCode":353,"versionName":"1.3.0"}"""
                url == "$apkUrl.json" -> 200 to """{"applicationId":"com.aurum.edge","versionCode":$publicCode,
                    "versionName":"1.3.51","sha256":"$digest","sizeBytes":40000000,
                    "signerSha256":"${BuildConfig.UPDATER_CERT_SHA256}","minSdk":26,"supportedAbis":["arm64-v8a","armeabi-v7a"]}"""
                url.startsWith("https://api.github.com/repos/") -> 200 to """[
                    {"tag_name":"v1.3.51","name":"v1.3.51","draft":$draft,"prerelease":$prerelease,
                    "assets":[{"name":"$apkName","browser_download_url":"$apkUrl","size":40000000,"digest":"sha256:$digest"},
                    {"name":"$apkName.json","browser_download_url":"$apkUrl.json"}]}]"""
                else -> error("Unexpected update request")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName(): String = "com.aurum.edge"
        }
        return AppUpdateRepository(context, http)
    }

    @Test fun `stale or failed legacy manifest does not hide a valid public release`() = runBlocking {
        for (status in listOf(200, 401, 404, 503)) {
            val repo = repository(status)
            repo.checkForUpdate()
            val update = repo.state.value.available!!
            assertEquals(AppUpdateRepository.SourceKind.RELEASE_APK, update.source)
            assertTrue(update.canDownload)
            assertEquals(nextCode.toLong(), update.versionCode)
            assertEquals(apkUrl, update.downloadUrl)
            assertEquals(digest, update.expectedSha256)
            assertEquals(40_000_000L, update.sizeBytes)
            assertNull(repo.state.value.error)
        }
    }

    @Test fun `same version drafts and prereleases are never offered as upgrades`() = runBlocking {
        for (repo in listOf(repository(200, BuildConfig.VERSION_CODE), repository(200, draft = true),
            repository(200, prerelease = true))) {
            repo.checkForUpdate()
            assertNull(repo.state.value.available)
        }
    }
}
