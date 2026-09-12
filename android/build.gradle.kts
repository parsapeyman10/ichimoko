// Aurum Edge — Android (Kotlin + Jetpack Compose)
// Root build file: only plugin declarations. Module config lives in app/build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

// ---------------------------------------------------------------------------------------
// CI diagnostics.
//
// Actions reports a failed task as a single generic annotation and keeps the real text in a
// job log that is not reachable from every environment. On GitHub, when a task fails, this
// hook re-runs that task in a scratch copy of the project through ./gradlew (whose wrapper
// republishes compiler messages as annotations), so the failure names itself. It never runs
// for successful builds, never runs inside the diagnostic run itself, and never changes the
// outcome of the build it belongs to — see android/tools/ci-diagnose.sh.
// ---------------------------------------------------------------------------------------
val aurumRoot: String = rootDir.absolutePath
val aurumDiagnosed = java.util.concurrent.atomic.AtomicBoolean(false)

if (System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("AURUM_DIAGNOSE") != "1") {
    gradle.taskGraph.addTaskExecutionListener(object : org.gradle.api.execution.TaskExecutionListener {
        override fun beforeTask(task: org.gradle.api.Task) = Unit

        override fun afterTask(task: org.gradle.api.Task, state: org.gradle.api.TaskState) {
            if (state.failure == null || !aurumDiagnosed.compareAndSet(false, true)) return
            try {
                val process = ProcessBuilder("sh", "$aurumRoot/tools/ci-diagnose.sh", task.path)
                process.inheritIO()
                process.start().waitFor()
            } catch (error: Exception) {
                println("::warning::Aurum diagnostics could not run: ${error.message}")
            }
        }
    })
}
