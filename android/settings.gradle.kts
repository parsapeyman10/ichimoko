pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Trading"
include(":app")

// ---------------------------------------------------------------------------------------
// Trading — CI diagnostics, step 1.
//
// Actions reports a failed job as a single generic annotation, and the job log lives on
// storage that is not reachable from every environment. A build that dies *before* any task
// runs — unresolvable plugins, a build script that does not compile, a configuration that
// throws — publishes its own exception chain as annotations from here, because those
// messages name the problem precisely. Compiler errors inside tasks are handled in
// build.gradle.kts, which re-runs the failing task and captures the compiler output.
//
// Nothing here changes the build: the listener only reads the result.
// ---------------------------------------------------------------------------------------
System.clearProperty("aurum.failedTask")

val aurumTrace = System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("AURUM_DIAGNOSE") != "1"

if (aurumTrace) {
    println("::notice::aurum trace 1/4 · settings evaluated (gradle " + gradle.gradleVersion +
        ", jvm " + System.getProperty("java.version") + ", sdk " + System.getenv("ANDROID_HOME") + ")")
}

if (aurumTrace) {
    gradle.addBuildListener(object : org.gradle.BuildListener {
        override fun settingsEvaluated(settings: org.gradle.api.initialization.Settings) = Unit

        override fun projectsLoaded(gradle: org.gradle.api.invocation.Gradle) = Unit

        override fun projectsEvaluated(gradle: org.gradle.api.invocation.Gradle) = Unit

        override fun buildFinished(result: org.gradle.BuildResult) {
            val failure: Throwable = result.failure ?: return
            // A failed task is already diagnosed in build.gradle.kts; do not spend the
            // annotation budget twice on the same incident.
            if (System.getProperty("aurum.failedTask") != null) return
            println("::warning::aurum: the build stopped outside task execution — Gradle reports:")
            var cause: Throwable? = failure
            var depth = 0
            while (cause != null && depth < 5) {
                val message = cause.message
                if (message != null) {
                    message.lines().forEach { line -> if (line.isNotBlank()) println("::error::" + line.trim()) }
                }
                cause = cause.cause
                depth += 1
            }
        }
    })
    println("::notice::aurum trace 2/4 · diagnostics listener attached")
}
