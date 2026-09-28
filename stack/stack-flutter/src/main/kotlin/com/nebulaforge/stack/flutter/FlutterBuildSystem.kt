package com.nebulaforge.stack.flutter

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class FlutterBuildSystem(private val context: Context) : BuildSystem {
    override val id = "flutter"

    override fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent> = flow {
        val flutter = Environment.findExecutable(context, "flutter")
        if (flutter == null) { emit(BuildEvent.LogLine("Flutter SDK 未安装或 flutter 不可执行", true)); emit(BuildEvent.Finished(false, 0)); return@flow }
        val command = when (task) {
            "analyze" -> "flutter analyze"
            "test" -> "flutter test"
            "build-apk" -> "flutter build apk --debug"
            "build-web" -> "flutter build web"
            else -> "flutter $task"
        }
        val started = System.currentTimeMillis()
        emit(BuildEvent.Progress(0, command))
        val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
        var code = 1
        executor.execute(command, workingDir, flutterEnv(env)).collect { event ->
            when (event) {
                is TermuxCommandExecutor.Event.Line -> emit(BuildEvent.LogLine(event.text, false))
                is TermuxCommandExecutor.Event.Finished -> code = event.exitCode
            }
        }
        emit(BuildEvent.Progress(100, if (code == 0) "Flutter 构建完成" else "Flutter 构建失败"))
        emit(BuildEvent.Finished(code == 0, System.currentTimeMillis() - started))
    }

    override suspend fun listAvailableTasks(workingDir: File): List<String> = listOf("analyze", "test", "build-apk", "build-web")

    override fun parseErrors(rawOutput: String): List<BuildError> {
        val regex = Regex("^(.+?):(\\d+):(\\d+):\\s*(.*)$")
        return rawOutput.lineSequence().mapNotNull { line ->
            val m = regex.find(line.trim()) ?: return@mapNotNull null
            BuildError(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull(), m.groupValues[4], BuildError.Severity.ERROR)
        }.toList()
    }

    private fun flutterEnv(base: Map<String, String>): Map<String, String> {
        val flutterRoot = File(Environment.homeRoot(context), "flutter")
        val path = listOf(File(flutterRoot, "bin").absolutePath, Environment.binDir(context), base["PATH"].orEmpty()).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
        return base + mapOf("FLUTTER_ROOT" to flutterRoot.absolutePath, "PUB_CACHE" to File(Environment.homeRoot(context), ".pub-cache").absolutePath, "PATH" to path)
    }
}
