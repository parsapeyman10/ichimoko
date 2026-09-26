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
// Trading — CI diagnostics, step 2.
//
// When a task fails on GitHub (a compiler error, a broken resource, a lint failure) the
// real messages live in a job log that is not reachable from everywhere, and Actions shows
// only one generic annotation. So the failing task is re-run in a scratch copy of the
// project through ./gradlew, whose wrapper republishes the compiler diagnostics as
// annotations; see android/tools/ci-diagnose.sh. The diagnosis never changes this build's
// own result, never runs when the build succeeds, and cannot recurse into itself.
// ---------------------------------------------------------------------------------------
val aurumRoot: String = rootDir.absolutePath
val aurumDiagnosed = java.util.concurrent.atomic.AtomicBoolean(false)

fun aurumDiagnose(root: String, task: String) {
    try {
        val builder = ProcessBuilder("sh", "$root/tools/ci-diagnose.sh", task)
        builder.redirectErrorStream(true)
        val process = builder.start()
        val tail = java.util.ArrayDeque<String>()
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                // Lines that are workflow commands go straight through; everything else is
                // kept as a short tail for the human reading the job log.
                if (line.startsWith("::")) {
                    println(line)
                } else {
                    tail.addLast(line)
                    if (tail.size > 40) tail.removeFirst()
                }
            }
        }
        process.waitFor()
        tail.forEach { line -> println(line) }
    } catch (error: Exception) {
        println("::warning::Aurum diagnostics could not run: ${error.message}")
    }
}

if (System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("AURUM_DIAGNOSE") != "1") {
    gradle.taskGraph.addTaskExecutionListener(object : org.gradle.api.execution.TaskExecutionListener {
        override fun beforeExecute(task: org.gradle.api.Task) = Unit

        override fun afterExecute(task: org.gradle.api.Task, state: org.gradle.api.tasks.TaskState) {
            if (state.failure == null) return
            System.setProperty("aurum.failedTask", task.path)
            if (!aurumDiagnosed.compareAndSet(false, true)) return
            println("::warning::Aurum diagnostics: ${task.path} failed — re-running it in a scratch copy to publish the real compiler messages")
            aurumDiagnose(aurumRoot, task.path)
        }
    })
}
