import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::warning::aurum trace 4/4 · app build script evaluated")
}

// Never bake provider credentials into an APK (even a GitHub Actions secret is extractable).
// Read-only market keys are entered on the device; trading keys must stay server-side.
android {
    namespace = "com.aurum.edge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurum.edge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "fa")
        buildConfigField("String", "DEFAULT_TD_API_KEY", "\"\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // The APK produced by CI is installable as-is (no signing secrets required).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("org.jsoup:jsoup:1.18.3")

    // JVM unit tests for the pure engine code and read-only data validation.
    // They never run inside the APK; release assembly requires them to pass below.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// GitHub test logs are hosted externally and may be inaccessible. Surface the failed
// test and its exception as a check annotation so a broken APK cannot be mistaken for green.
tasks.withType<Test>().configureEach {
    if (System.getenv("GITHUB_ACTIONS") == "true") {
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
    }
}

// The published workflow currently makes its separate JVM-test step non-blocking.
// Enforce a green test suite at the release task itself so it cannot upload an APK
// after a failed test (including when the workflow patch cannot be pushed).
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("testDebugUnitTest")
}
