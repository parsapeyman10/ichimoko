import org.gradle.api.tasks.testing.Test
import org.gradle.api.attributes.Usage
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
// Locally built releases without a stable keystore fall back to a debug signature. CI requires
// the pinned updater key before publishing; signing is NOT Play certification
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
// Public certificate fingerprint only (NEVER the private key). Kept in Git for diagnostic display.
val updaterSigner = file("../../update/updater-signer.sha256").readText().trim()
if (!Regex("[0-9a-f]{64}").matches(updaterSigner)) {
    throw org.gradle.api.GradleException("Invalid updater certificate fingerprint")
}
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
        // Keep the published update channel upgradeable: 2.0.5 is code 677 after 2.0.4 (676).
        // 2.0.5 ships the per-symbol Twelve Data keys, the symbol→source map
        // (docs/SYMBOL_SOURCES_FA.md) and an updater that reports which published version it saw
        // (docs/UPDATER_RELEASE_FA.md). A versionCode must be bumped here before a release run,
        // because the workflow only publishes explicit `release: publish` commits.
        // 2.0.7 adds ALS-5S, the dedicated silver engine (XAG/USD M5): its own regimes, sweep and
        // range/breakout engines, cost gate and separate card in the trade tab. It also ships the
        // Pine v6 strategy and the offline replay harness. See docs/ALS5S_SILVER_FA.md.
        // 2.0.8 adds EUR-ARES, the dedicated euro/dollar engine (EUR/USD M5): pip-native economics,
        // DST-correct sessions, trend/sweep/range/session-breakout engines, a cost veto measured in
        // pips, its own card in the trade tab, the Pine v6 strategy and an offline harness.
        // See docs/EUR_ARES_FA.md.
        // 2.0.9 fixes the quote routes that made three engine families unreachable: EUR/USD had a
        // single-number Yahoo tick, silver was read from a one-price Gold-API feed, and every crypto
        // pair only had a trade print. The engines correctly refuse to enter without a real
        // bid/ask, so no auto paper trade could ever open. EUR/USD and silver now use the
        // Swissquote BBO, and crypto attaches the best bid/ask from the Nobitex order book.
        // 2.0.10 cuts data usage: the scanner reuses a complete candle bundle until the next M5
        // bar can exist (it used to download 950 candles per symbol five times per bar), the
        // passive quote stream polls every 5 s instead of 1 s, the Dow asks Yahoo for one bar
        // instead of a whole day of candles, and one process-wide quote cache merges the
        // duplicate reads of the service/UI/scanner loops. See docs/DATA_USAGE_FA.md.
        // 2.0.11 is the last 2.x code: it moved the shared market models into `core`, wired the
        // StrategyEvaluator into the scan path and kept the published updater channel upgradeable
        // (683 on top of 682). It is the direct predecessor of 3.0.0.
        // 3.0.0 (code 700) is the first COMPLETE 3.x source tree: every Kotlin/Compose file needed
        // to assemble the APK is present in the repository again (the 2.0.11 merge had left only
        // part of `android/` behind), the update screen shows the installed certificate next to the
        // pinned release channel before any download, and the in-app updater keeps its three
        // channels + 15-minute automatic-check floor. See docs/RELEASE_3_0_FA.md and
        // CHANGELOG_FA.md. A versionCode must be bumped here before a release run, because the
        // workflow only publishes explicit tags / `release: publish` commits / approved dispatch.
        // 3.9.31 (code 740) upgrades the NeoWave layer to the rule-complete Neely model described in
        // "Mastering Elliott Wave": the m2/m1 ratio now selects one of the seven retracement rules and
        // tells the app what the next wave must do; every monowave node carries a Neely structure label
        // (:5, :F3, :c3, :L3, :L5, :s5); an impulse is audited against 12 rules (61.8% wave-2 limit,
        // wave-4/wave-1 and wave-4/wave-2 non-overlap, wave-3 not shortest, 1.618 extension,
        // subdivision, time alternation, 0-2 channel break, six-point channel touch rule), zigzag/flat
        // against their 5-3-5 and 3-3-5 proportions, triangles via strict sequential overlap, and the
        // 7/9-wave Diametric/Symmetrical formations by time symmetry. Hard-rule violations invalidate a
        // count outright, and the post-constructive rule means a veto now requires the following wave to
        // CONFIRM the count (>= 23.6% of the pattern height in the expected direction). Still optional
        // and OFF by default; when off the engine output is bit-identical to 3.9.29.
        // 3.9.30 (code 739) adds NeoWave (Glenn Neely) as an OPTIONAL confirmation layer. It never
        // trades on its own: it decomposes price into ATR-adaptive monowaves, classifies the structure
        // (Impulse, Impulse-in-progress, ZigZag, Flat, Triangle, Extracting Triangle, Diametric) and
        // audits the mechanical Neely rules (wave-2 retracement limit, wave-3 not shortest, wave-4 /
        // wave-1 non-overlap, time limits, wave-2/4 alternation, 0-2 channel, Fibonacci projection).
        // A complete, high-confidence pattern that ALIGNS with the entry adds +8 confidence; a complete
        // pattern that OPPOSES the entry vetoes it outright (readiness BLOCKED, action NO_TRADE).
        // The switch lives in the strategy card (BaseStrategyConfig.neoWaveEnabled) and defaults to
        // OFF; when it is off no NeoWave maths runs and the engine output is bit-identical to 3.9.29
        // (covered by a regression test). Thresholds are user-tunable (min rules, min confidence).
        // See docs/NEOWAVE_OPTION_FA.md. Publish stamp: 3.9.31 / code 740 (rule-complete Neely model:
        // retracement rules 1-7, structure labels, 12-rule impulse audit, strict-overlap triangles,
        // post-constructive confirmation), released from the arena/f97d4078-ichimoko branch with the
        // pinned updater signing key.
        // 3.9.29 (code 738) corrects the session policy and adds the timing learner. 3.9.28 closed a
        // position as soon as its market was shut, using the last stale price; that trade could never
        // have been executed. The engine now (a) never closes on a closed market, (b) exits inside the
        // pre-close window while the market is still live, and (c) exits a carried-over position at the
        // first valid tick after the reopen. How early that window starts is learned per asset class by
        // an epsilon-greedy multi-armed bandit with a lower-confidence-bound guard, rewarded by the real
        // dollars a pre-close exit saved versus holding to the reopen (SessionExitPlanner,
        // SessionExitLearning, SessionExitLearningStore). The release also ships the stability pack:
        // coalesced UI publishing (250 ms), a memory-aware resource governor for the snapshot archive,
        // serialized bitmap rendering and a compact tabbed journal scorecard. See
        // docs/SESSION_TIMING_LEARNING_FA.md and docs/APP_PERFORMANCE_BUDGET_FA.md.
        // 3.9.28 (code 737) is the trading-hygiene + scorecard release: no trade may open on a
        // symbol whose session is closed (the DOW case), any open position left on a closed market
        // is flattened at its last valid price, and stale/open plans (the BNB 5m 1R = 5.66% case)
        // are re-anchored into the volatility corridor of their own timeframe by TradePlanGuard
        // without changing direction or R:R. The snapshot archive stops showing phantom records and
        // renders reconstructed charts from real recorded candles, the learned-trade counter is
        // rebuilt from the journal whenever it drifts, and the journal's "کارنامه کلی" is redesigned
        // around one pure analytics engine (equity curve, drawdown, expectancy, R distribution and
        // per-class/symbol/timeframe/direction breakdowns). See docs/TRADING_HYGIENE_FA.md and
        // docs/PERFORMANCE_SCORECARD_FA.md. Regression tests: TradingPerformanceAnalyticsTest
        // (scorecard maths, corridor-free verdicts) plus the existing TradePlanGuard suite, which
        // now also covers reviewStoredPlan repairing the legacy 5.66% stop without flipping direction.
        // 3.9.27 (code 736) ships the neural learning ledger: the self-learning brain now counts
        // every closed trade exactly once (a stable per-trade fingerprint instead of an ever-growing
        // counter), repairs the inflated legacy number without losing a single learned weight, and
        // activates its execution gate only when BOTH conditions hold — 10,000 learned trades AND the
        // user's explicit switch. Every correction the brain makes is written to a Persian knowledge
        // changelog and exported with a checksummed "knowledge bundle" (JSON + FA manifest) so the next
        // app version can read, restore and audit what the previous one learned.
        // See docs/NEURAL_LEARNING_LEDGER_FA.md. The ledger is authoritative for the gate: reaching
        // 10,000 learned trades never activates the block by itself — the user's own switch must be on
        // as well, and switching it off deactivates the gate immediately.
        // 3.9.26 (code 735) is the trade-plan guard release: stop-loss, take-profit and leverage are
        // no longer produced by arbitrary anchors. Every plan (strategy report, auto entry, sniper
        // fill and the manual calculator) passes one engine — TradePlanGuard — that keeps 1R inside
        // the volatility corridor of the entry timeframe, keeps the target reachable, re-anchors
        // stale plans (or rejects them) and derives leverage from the stop distance so liquidation
        // stays at least 2.5R away. See docs/TRADE_PLAN_GUARD_FA.md. The same guard is surfaced in
        // the UI (settings audit panel, position card and journal) so an off-corridor plan is visible
        // before it is taken, not only after it fails.
        // 3.9.32 (code 741) revisits *when* a crypto trade may open. The generic gate still needs
        // all 16 structural conditions, which on 24/7 crypto can stay unfulfilled for days (the
        // "no signals for a day" report). While the NeoWave layer is ON, a complete + confirmed +
        // direction-aligned NeoWave count is itself the crypto entry trigger — with the same
        // TradePlanGuard risk controls (R:R >= 1.5, valid stop/target, leverage from stop distance)
        // and no veto. The switch `neoWaveCryptoEntryEnabled` (ON by default) can turn the path off
        // at any moment; then crypto behaves exactly like 3.9.31 again. The NeoWave layer itself is
        // unchanged and still OFF by default, so a fresh install stays byte-identical in behaviour.
        versionCode = 741
        versionName = "3.9.32"
        resourceConfigurations += listOf("en", "fa")
        buildConfigField("String", "DEFAULT_TD_API_KEY", "\"\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "UPDATER_CERT_SHA256", "\"$updaterSigner\"")
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
    // weight on any given device. Keep existing ARM support for any remaining native
    // dependency. Shrinking remains disabled; device testing is still necessary.
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

    // JVM unit tests for the pure engine code and read-only data validation.
    // They never run inside the APK; release assembly requires them to pass below.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Install ALL Robolectric test-requirements through Gradle itself.
// By default Robolectric downloads its android-all runtime jars from Maven Central the
// first time a test class runs — outside Gradle's dependency graph — so a transient
// network failure on the CI runner kills the unit-test step (and with it the APK build).
// Declaring the exact artifacts here means they are downloaded and cached together with
// every other build prerequisite, then handed to Robolectric in OFFLINE mode.
// SDKs used by the suite: 30 and 33 (explicit @Config) + the targetSdk default (35).
// IMPORTANT: one configuration PER VERSION. All three are the same module
// (org.robolectric:android-all-instrumented) at different versions, so a single
// configuration would conflict-resolve down to only the newest jar.
val robolectricJarFiles: FileCollection = files(
    listOf(
        "11-robolectric-6757853-i7",
        "13-robolectric-9030017-i7",
        "15-robolectric-12650502-i7",
    ).map { version ->
        val config = configurations.create("robolectricRuntime_${version.substringBefore('-')}") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            }
        }
        dependencies.add(config.name, "org.robolectric:android-all-instrumented:$version")
        config.incoming.artifactView { }.files
    },
)

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")

tasks.withType<Test>().configureEach {
    inputs.files(robolectricJarFiles)
    // Robolectric loads a full android-all runtime per SDK and keeps it for the whole worker,
    // so the default 512 MB test heap ran the engine/keystore suites into OutOfMemoryError
    // long before they could fail on an assertion.
    maxHeapSize = "2g"
    doFirst {
        val dir = robolectricDepsDir.get().asFile
        dir.mkdirs()
        val jars = robolectricJarFiles.files
        check(jars.size == 3) {
            "expected 3 Robolectric runtime jars (SDK 30/33/35) but resolved ${jars.size}: $jars"
        }
        jars.forEach { jar -> jar.copyTo(dir.resolve(jar.name), overwrite = true) }
        logger.lifecycle("Robolectric offline deps installed: " + jars.joinToString { it.name })
    }
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
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
