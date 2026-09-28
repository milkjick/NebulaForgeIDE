package com.nebulaforge.stack.cpp

import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.BuildEvent
import com.nebulaforge.core.projectmodel.BuildSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import java.io.File

/** CMake/NDK 技术栈的真实命令适配层，执行仍由统一命令执行管道负责。 */
class CmakeBuildSystem(private val command: (String, File, Map<String, String>) -> Flow<String>) : BuildSystem {
    override val id = "cmake"
    override fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent> = flow {
        val started = System.currentTimeMillis(); var failed = false
        command(task, workingDir, env).collect { line ->
            failed = failed || line.contains("error:", true) || line.contains("CMake Error", true)
            emit(BuildEvent.LogLine(line, failed))
        }
        emit(BuildEvent.Finished(!failed, System.currentTimeMillis() - started))
    }
    override suspend fun listAvailableTasks(workingDir: File): List<String> = listOf("configure", "build", "clean")
    override fun parseErrors(rawOutput: String): List<BuildError> = rawOutput.lineSequence().mapNotNull { line ->
        Regex("^(.+?):(\\d+):(\\d+):\\s*(error|warning):\\s*(.+)$").find(line)?.let { m -> BuildError(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[5], if (m.groupValues[4] == "warning") BuildError.Severity.WARNING else BuildError.Severity.ERROR) }
    }.toList()
}

object CppProjectDetector { fun isCmakeProject(root: File): Boolean = File(root, "CMakeLists.txt").isFile || root.walkTopDown().take(100).any { it.name == "CMakeLists.txt" } }
