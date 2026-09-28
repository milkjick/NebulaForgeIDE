package com.nebulaforge.stack.android

import android.content.Context
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.gradlebridge.GradleToolingBridgeClient
import com.nebulaforge.core.projectmodel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import java.io.File

/**
 * Android Gradle BuildSystem.
 *
 * Preferred path (v2 §3.2): Termux-userland Gradle Tooling API bridge.
 * Fallback: the same embedded PTY runtime executes the real Gradle wrapper/installed Gradle,
 * never Android ProcessBuilder. This keeps the Way-B architecture honest while the bridge jar
 * is being provisioned.
 */
class GradleAndroidBuildSystem(private val context: Context? = null) : BuildSystem {
    override val id = "gradle"

    override fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent> = channelFlow {
        val app = context
        if (app != null) {
            val bridge = GradleToolingBridgeClient(app)
            var emittedFinished = false
            bridge.build(task, workingDir, env).collect { event ->
                if (event is BuildEvent.Finished) emittedFinished = true
                send(event)
            }
            if (emittedFinished) return@channelFlow
        }
        // Runtime-safe fallback: embedded PTY, wrapper-first.
        val shell = if (app != null) Environment.resolveShell(app) else "/system/bin/sh"
        val executor = TermuxCommandExecutor(shell, guestAware = true)
        val wrapper = File(workingDir, "gradlew")
        val realWrapper = wrapper.isFile &&
            File(workingDir, "gradle/wrapper/gradle-wrapper.jar").isFile &&
            File(workingDir, "gradle/wrapper/gradle-wrapper.properties").isFile
        val executable = if (realWrapper) "./gradlew" else "gradle"
        send(BuildEvent.Progress(0, "Gradle bridge 不可用，使用 embedded PTY ${if (realWrapper) "Wrapper" else "Gradle fallback"}"))
        val started = System.currentTimeMillis()
        var exit = 1
        executor.execute("$executable ${shellQuote(task)}", workingDir, env).collect { e ->
            when (e) {
                is TermuxCommandExecutor.Event.Line -> send(BuildEvent.LogLine(e.text, false))
                is TermuxCommandExecutor.Event.Finished -> { exit = e.exitCode; send(BuildEvent.Finished(e.exitCode == 0, e.durationMs)) }
            }
        }
        if (exit == 1) {
            // If the command never reached the sentinel, still provide a deterministic terminal event.
            send(BuildEvent.Finished(false, System.currentTimeMillis() - started))
        }
    }

    override suspend fun listAvailableTasks(workingDir: File): List<String> {
        val app = context ?: return emptyList()
        val env = Environment.buildGradleEnv(app, 17)
        return GradleToolingBridgeClient(app).listTasks(workingDir, env)
    }

    override fun parseErrors(rawOutput: String): List<BuildError> {
        val patterns = listOf(
            Regex("(?:e: )?(.+?):(\\d+):(\\d+):\\s*(.*)"),
            Regex("(.+?)\\((\\d+),(\\d+)\\):\\s*(.*)"),
            Regex("(.+?):(\\d+):\\s*(.*)")
        )
        return rawOutput.lineSequence().mapNotNull { line ->
            patterns.asSequence().mapNotNull { p ->
                val m = p.find(line) ?: return@mapNotNull null
                val groups = m.groupValues
                BuildError(
                    groups.getOrNull(1),
                    groups.getOrNull(2)?.toIntOrNull(),
                    groups.getOrNull(3)?.toIntOrNull(),
                    groups.lastOrNull().orEmpty(),
                    BuildError.Severity.ERROR
                )
            }.firstOrNull()
        }.toList()
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
