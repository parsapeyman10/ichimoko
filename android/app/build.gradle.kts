import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::notice::aurum trace 4/4 · app build script evaluated")
}

// Never bake provider credentials into an APK (even a GitHub Actions secret is extractable).
// Read-only market keys are entered on the device; trading keys must stay server-side.
// The CI artifact is a DEBUG-SIGNED preview. A repository owner can explicitly supply a
// stable, privately held signing key for sideloaded updates; signing is NOT Play certification
// and does not guarantee that Google Play Protect will stop warning on first sideload.
val ownerStorePath = System.getenv("AURUM_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val ownerStorePassword = System.getenv("AURUM_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val ownerKeyAlias = System.getenv("AURUM_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val ownerKeyPassword = System.getenv("AURUM_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val ownerStoreType = System.getenv("AURUM_RELEASE_STORE_TYPE")?.takeIf { it.isNotBlank() }
val signingValues = listOf(ownerStorePath, ownerStorePassword, ownerKeyAlias, ownerKeyPassword)
val ownerSigningReady = signingValues.all { it != null }
val requireOwnerSigning = providers.gradleProperty("aurumRequireReleaseSigning").orNull == "true"
fun gitOutput(vararg args: String): String = runCatching {
    val out = ByteArrayOutputStream()
    exec { commandLine("git", *args); standardOutput = out }
    out.toString().trim().ifBlank { "unknown" }
}.getOrDefault("unknown")
val gitSha = gitOutput("rev-parse", "--short=12", "HEAD")
if (!ownerSigningReady && (requireOwnerSigning || signingValues.any { it != null })) {
    throw org.gradle.api.GradleException(
        "Owner-signed release requires all four AURUM_RELEASE_* environment variables; refusing an incomplete signing setup."
    )
}
if (ownerSigningReady) {
    val keystore = file(ownerStorePath!!).canonicalFile
    if (!keystore.isFile) {
        throw org.gradle.api.GradleException("The release keystore must be an existing file. Not found at: ${keystore.absolutePath}")
    }
} else if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::warning title=Preview APK::The CI artifact named release is signed with an ephemeral DEBUG key. " +
        "It is not a trusted/published release; Play Protect may warn, and upgrades may fail.")
}
android {
    namespace = "com.aurum.edge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurum.edge"
        minSdk = 26
        targetSdk = 35
        versionCode = 442
        versionName = "1.3.10"
        resourceConfigurations += listOf("en", "fa")
        buildConfigField("String", "DEFAULT_TD_API_KEY", "\"\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "UPDATE_REPO", "\"parsapeyman10/ichimoko\"")
        // The updater must read the published manifest, not a short-lived Arena/PR branch.
        // Public releases are still checked separately, so a manifest without apkUrl cannot
        // hide a real downloadable GitHub Release.
        buildConfigField("String", "UPDATE_BRANCH", "\"main\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://raw.githubusercontent.com/parsapeyman10/ichimoko/main/update/aurum-edge.json\"")
    }

    signingConfigs {
        if (ownerSigningReady) create("ownerRelease") {
            storeFile = file(ownerStorePath!!)
            ownerStoreType?.let { storeType = it }
            storePassword = ownerStorePassword!!
            keyAlias = ownerKeyAlias!!
            keyPassword = ownerKeyPassword!!
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // Compatibility with the existing preview workflow. This is NOT a public release.
            // Owner-signed APKs use the same applicationId and a key kept outside this repository.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ownerSigningReady) "ownerRelease" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    // Ship only the CPU architectures real phones use. Without this every native library
    // is bundled four times (arm64, armeabi-v7a, x86, x86_64) and three of them are dead
    // weight on any given device. Code is NOT obfuscated or shrunk: isMinifyEnabled stays
    // false, so nothing can break at runtime that the JVM tests would not catch.
    defaultConfig {
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    lint {
        // Lint findings are uploaded as a report; tests/assembly remain the hard release gates.
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric: journal AtomicFile + app-private storage
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.tukaani:xz:1.10")
    implementation("org.jsoup:jsoup:1.18.3")

    // JVM unit tests for the pure engine code and read-only data validation.
    // They never run inside the APK; release assembly requires them to pass below.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// GitHub test logs and the HTML report are hosted on storage that is not reachable from
// every environment, and ::error:: workflow commands printed by a test listener do not
// reliably become annotations. The Gradle FAILURE MESSAGE does, so the authoritative list
// of failing tests is parsed out of the JUnit XML and thrown from the build itself.
tasks.withType<Test>().configureEach {
    if (System.getenv("GITHUB_ACTIONS") == "true") {
        // Let the task finish so every XML is written, then fail with a message that names
        // the tests. Without this, a red run says only "there were failing tests".
        ignoreFailures = true
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) = Unit
            override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
            override fun beforeTest(test: TestDescriptor) = Unit
            override fun afterTest(test: TestDescriptor, result: TestResult) {
                if (result.resultType == TestResult.ResultType.FAILURE) {
                    val detail = result.exceptions.firstOrNull()?.let {
                        "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                    }.orEmpty().replace('\n', ' ').replace('\r', ' ').take(300)
                    println("::error title=JVM test failed::${test.className}.${test.name}: $detail")
                }
            }
        })
        doLast {
            val resultsDir = reports.junitXml.outputLocation.get().asFile
            val failures = mutableListOf<String>()
            resultsDir.listFiles { file -> file.name.endsWith(".xml") }?.sortedBy { it.name }?.forEach { file ->
                val xml = file.readText()
                xml.split("<testcase ").drop(1).forEach { block ->
                    val body = block.substringBefore("</testsuite>")
                    if (!body.contains("<failure") && !body.contains("<error")) return@forEach
                    fun attr(name: String, from: String): String =
                        Regex("$name=\"([^\"]*)\"").find(from)?.groupValues?.get(1).orEmpty()
                    val testName = attr("name", body.substringBefore(">"))
                    val className = attr("classname", body.substringBefore(">"))
                    val marker = body.indexOf("<failure").takeIf { it >= 0 } ?: body.indexOf("<error")
                    val message = attr("message", body.substring(marker, minOf(body.length, marker + 1200)))
                        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&amp;", "&").replace('\n', ' ').replace('\r', ' ').trim().take(350)
                    failures += "$className.$testName :: $message"
                }
            }
            if (failures.isNotEmpty()) {
                failures.forEach { println("::error title=JVM test failed::$it") }
                // One line: annotations are truncated at the first newline, which would
                // hide the very names this exists to report.
                throw GradleException(
                    "${failures.size} failing test(s): " + failures.joinToString(" ||| ")
                )
            }
        }
    }
}

// Keep release assembly gated by the pure engine/unit suite as a second guard, even when
// a caller invokes assembleRelease directly outside the full GitHub Actions workflow.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
