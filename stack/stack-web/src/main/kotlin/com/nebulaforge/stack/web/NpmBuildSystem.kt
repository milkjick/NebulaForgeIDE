package com.nebulaforge.stack.web

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class NpmBuildSystem(private val context: Context) : BuildSystem {
    override val id = "npm"
    override fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent> = flow {
        val npm = Environment.findExecutable(context, "npm")
        if (npm == null) { emit(BuildEvent.LogLine("Node.js/npm 未安装或不可执行", true)); emit(BuildEvent.Finished(false, 0)); return@flow }
        val started = System.currentTimeMillis()
        val command = if (task.startsWith("npm ")) task else "npm run $task"
        emit(BuildEvent.Progress(0, command))
        var code = 1
        TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true).execute(command, workingDir, env).collect { event ->
            when (event) {
                is TermuxCommandExecutor.Event.Line -> emit(BuildEvent.LogLine(event.text, false))
                is TermuxCommandExecutor.Event.Finished -> code = event.exitCode
            }
        }
        emit(BuildEvent.Progress(100, if (code == 0) "npm 完成" else "npm 失败"))
        emit(BuildEvent.Finished(code == 0, System.currentTimeMillis() - started))
    }
    override suspend fun listAvailableTasks(workingDir: File): List<String> = listOf("build", "test", "dev", "start")
    override fun parseErrors(rawOutput: String): List<BuildError> {
        val r = Regex("^(.+?):(\\d+):(\\d+):\\s*(.*)$")
        return rawOutput.lineSequence().mapNotNull { line -> r.find(line.trim())?.let { m -> BuildError(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull(), m.groupValues[4], BuildError.Severity.ERROR) } }.toList()
    }
}
