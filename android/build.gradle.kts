// Trading — Android (Kotlin + Jetpack Compose)
// Root build file: only plugin declarations. Module config lives in app/build.gradle.kts
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    println("::notice::aurum trace 3/4 · root build script evaluated (gradle " + gradle.gradleVersion + ")")
}

// ---------------------------------------------------------------------------------------
// Trading — CI diagnostics, step 2 (pure Kotlin; this repository has no shell helpers).
//
// When a task fails on GitHub, Actions shows one generic annotation and the job log lives on
// storage that is not reachable from every environment. So the failing task's own output is
// captured line by line while it runs, and on failure the lines that actually name a problem
// (Kotlin/Java compiler errors, "What went wrong", causes) are republished as annotations.
//
// Nothing here changes the build result: the listener only reads, it never re-runs a task, and
// every step is guarded so a diagnostics problem can never become a build problem.
// ---------------------------------------------------------------------------------------
if (System.getenv("GITHUB_ACTIONS") == "true") {
    val captured = java.util.Collections.synchronizedList(ArrayList<String>())
    val aurumDiagnosed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun keep(line: String) {
        val text = line.trim()
        if (text.isEmpty() || captured.size >= 80) return
        val interesting = text.startsWith("e: ") || text.startsWith("w: ") ||
            text.startsWith("error:") || text.startsWith("FAILURE") ||
            text.startsWith("What went wrong") || text.startsWith("Caused by") ||
            text.startsWith("> ") || text.contains("unresolved reference") ||
            text.contains("Compilation failed") || text.contains("failing test(s)")
        if (interesting) captured.add(text.take(400))
    }

    gradle.taskGraph.addTaskExecutionListener(object : org.gradle.api.execution.TaskExecutionListener {
        override fun beforeExecute(task: org.gradle.api.Task) {
            captured.clear()
            try {
                task.logging.addStandardOutputListener { chunk -> chunk.toString().lines().forEach { keep(it) } }
                task.logging.addStandardErrorListener { chunk -> chunk.toString().lines().forEach { keep(it) } }
            } catch (error: Exception) {
                println("::warning::aurum: build output capture unavailable: " + error.message)
            }
        }

        override fun afterExecute(task: org.gradle.api.Task, state: org.gradle.api.tasks.TaskState) {
            if (state.failure == null) return
            System.setProperty("aurum.failedTask", task.path)
            if (!aurumDiagnosed.compareAndSet(false, true)) return
            fun emit(text: String) {
                val line = text.trim().take(400)
                if (line.isNotBlank()) println("::error::" + line.replace("%", "%25"))
            }
            println("::warning::aurum: ${task.path} failed — captured build output and failure chain follow")
            synchronized(captured) { captured.toList() }.forEach { emit(it) }
            var cause: Throwable? = state.failure
            var depth = 0
            while (cause != null && depth < 4) {
                cause.message?.lines()?.forEach { emit(it) }
                cause = cause.cause
                depth += 1
            }
        }
    })
}
