package com.nebulaforge.core.gradlebridge

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.BuildEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID

/**
 * Android-side client for the Termux-userland Gradle Tooling API bridge.
 * The Tooling API itself never runs inside the Android app process; the client starts the
 * prebuilt bridge JAR through the embedded PTY runtime and consumes structured events.
 */
class GradleToolingBridgeClient(
    context: Context,
    private val bridgeJar: File = File(context.filesDir, "runtime/tools/gradle-tooling-bridge.jar")
) {
    private val app = context.applicationContext
    private val shell = Environment.resolveShell(app)
    private val executor = TermuxCommandExecutor(shell, guestAware = true)

    fun build(task: String, workingDir: File, environment: Map<String, String>): Flow<BuildEvent> = channelFlow {
        if (!bridgeJar.isFile) {
            send(BuildEvent.LogLine("Gradle Tooling API bridge 未安装：${bridgeJar.absolutePath}", true))
            return@channelFlow
        }
        val requestDir = File(app.filesDir, "runtime/bridge/requests").apply { mkdirs() }
        val request = File(requestDir, "${UUID.randomUUID()}.properties")
        val props = linkedMapOf(
            "project" to workingDir.absolutePath,
            "task" to task,
            "javaHome" to (environment["JAVA_HOME"] ?: ""),
            "gradleUserHome" to (environment["GRADLE_USER_HOME"] ?: ""),
            "androidSdk" to (environment["ANDROID_SDK_ROOT"] ?: "")
        )
        request.writeText(props.entries.joinToString("\n") { "${escape(it.key)}=${escape(it.value)}" } + "\n")
        val command = "java -jar ${quote(bridgeJar.absolutePath)} --request ${quote(request.absolutePath)}"
        var finishedSeen = false
        try {
            executor.execute(command, workingDir, environment).collect { event ->
                when (event) {
                    is TermuxCommandExecutor.Event.Line -> {
                        val parsed = parse(event.text)
                        if (parsed != null) {
                            if (parsed is BuildEvent.Finished) finishedSeen = true
                            send(parsed)
                        } else send(BuildEvent.LogLine(event.text, false))
                    }
                    is TermuxCommandExecutor.Event.Finished -> {
                        // The bridge emits its own FINISHED record with the Gradle result. The shell
                        // exit code is only a transport-level fallback.
                        if (!finishedSeen) send(BuildEvent.Finished(event.exitCode == 0, event.durationMs))
                    }
                }
            }
        } finally {
            request.delete()
        }
    }

    suspend fun selfTest(environment: Map<String, String>): Result<String> = runCatching {
        val bridge = bridgeJar
        require(bridge.isFile) { "bridge JAR 不存在: ${bridge.absolutePath}" }
        val javaHome = environment["JAVA_HOME"]?.takeIf { it.isNotBlank() } ?: error("JAVA_HOME missing")
        val java = File(javaHome, "bin/java")
        require(java.isFile) { "java 不存在: ${java.absolutePath}" }
        val output = mutableListOf<String>()
        var exit = -1
        executor.execute("${quote(java.absolutePath)} -jar ${quote(bridge.absolutePath)} --self-test", bridge.parentFile ?: app.filesDir, environment).collect { event ->
            when (event) {
                is TermuxCommandExecutor.Event.Line -> output += event.text
                is TermuxCommandExecutor.Event.Finished -> exit = event.exitCode
            }
        }
        check(exit == 0) { output.takeLast(10).joinToString("\n") }
        output.takeLast(10).joinToString("\n")
    }

    suspend fun listTasks(workingDir: File, environment: Map<String, String>): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!bridgeJar.isFile) return@withContext emptyList()
        val requestDir = File(app.filesDir, "runtime/bridge/requests").apply { mkdirs() }
        val request = File(requestDir, "${UUID.randomUUID()}.properties")
        val props = linkedMapOf(
            "project" to workingDir.absolutePath,
            "javaHome" to (environment["JAVA_HOME"] ?: ""),
            "gradleUserHome" to (environment["GRADLE_USER_HOME"] ?: "")
        )
        request.writeText(props.entries.joinToString("\n") { "${escape(it.key)}=${escape(it.value)}" } + "\n")
        val result = mutableListOf<String>()
        try {
            executor.execute("java -jar ${quote(bridgeJar.absolutePath)} --tasks ${quote(request.absolutePath)}", workingDir, environment).collect { event ->
                if (event is TermuxCommandExecutor.Event.Line && event.text.startsWith("NEBULA_EVENT\tTASK\t")) {
                    val p = event.text.split('\t', limit = 4)
                    if (p.size >= 3) result += p[2]
                }
            }
        } finally { request.delete() }
        result.distinct().sorted()
    }

    private fun parse(line: String): BuildEvent? {
        if (!line.startsWith("NEBULA_EVENT\t")) return null
        val p = line.split('\t', limit = 4)
        if (p.size < 2) return null
        return when (p[1]) {
            "PROGRESS" -> BuildEvent.Progress(p.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100) ?: 0, p.getOrNull(3).orEmpty())
            "LOG" -> BuildEvent.LogLine(p.getOrNull(3).orEmpty(), false)
            "ERR" -> BuildEvent.LogLine(p.getOrNull(3).orEmpty(), true)
            "FINISHED" -> {
                BuildEvent.Finished(p.getOrNull(2) == "0", p.getOrNull(3)?.toLongOrNull() ?: 0L)
            }
            else -> null
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=")
}
