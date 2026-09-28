package com.nebulaforge.stack.server

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.BuildEvent
import com.nebulaforge.core.projectmodel.BuildSystem
import com.nebulaforge.core.projectmodel.RunConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 通用「CLI 工具链」构建系统。
 *
 * Go / Maven / pip / Composer / Cargo 这类后端栈没有统一的等价 Gradle DSL，
 * 它们的构建就是「在项目目录里跑一条命令」。本类把这条命令交给统一命令执行管道
 * （Termux 内嵌用户态），从而与 NpmBuildSystem 共享同一套事件与错误解析约定。
 */
class ServerBuildSystem(private val context: Context, override val id: String) : BuildSystem {

    override fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent> = flow {
        val shell = Environment.resolveShell(context)
        val started = System.currentTimeMillis()
        emit(BuildEvent.Progress(0, task))
        var code = 1
        TermuxCommandExecutor(shell, guestAware = true).execute(task, workingDir, env).collect { event ->
            when (event) {
                is TermuxCommandExecutor.Event.Line -> emit(BuildEvent.LogLine(event.text, false))
                is TermuxCommandExecutor.Event.Finished -> code = event.exitCode
            }
        }
        emit(BuildEvent.Progress(100, if (code == 0) "完成" else "失败"))
        emit(BuildEvent.Finished(code == 0, System.currentTimeMillis() - started))
    }

    override suspend fun listAvailableTasks(workingDir: File): List<String> = when (id) {
        "go" -> listOf("go build ./...", "go test ./...", "go vet ./...")
        "lua" -> listOf(
            LuaCommands.SYNTAX_CHECKER + "lua_check main.lua",
            LuaCommands.RUNNER + "lua_run main.lua"
        )
        "maven" -> listOf("mvn -q package", "mvn -q test")
        "python" -> listOf("pip install -r requirements.txt", "python -m compileall .")
        "composer" -> listOf("composer install", "composer dump-autoload")
        "cargo" -> listOf("cargo build", "cargo test")
        // 独立语言项目：命令按项目里**真实存在的源文件**推导，见 StandaloneCommands。
        "java-console", "python-script", "javascript-node", "html-site", "css-project", "c-console" ->
            StandaloneCommands.tasksFor(id, workingDir)
        else -> listOf("build")
    }

    override fun parseErrors(rawOutput: String): List<BuildError> {
        val generic = Regex("^(.+?):(\\d+):(\\d+):\\s*(error|warning)?:?\\s*(.*)$")
        return rawOutput.lineSequence().mapNotNull { line ->
            val t = line.trim()
            generic.find(t)?.let { m ->
                BuildError(
                    filePath = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull(),
                    column = m.groupValues[3].toIntOrNull(),
                    message = m.groupValues[5].ifBlank { t },
                    severity = if (m.groupValues[4].equals("warning", ignoreCase = true)) BuildError.Severity.WARNING else BuildError.Severity.ERROR
                )
            }
        }.toList()
    }
}

/**
 * 后端栈统一的运行配置：命令由 [resolve] 依据项目实际文件结构决定
 * （例如 Python 项目里有没有 manage.py / main.py 对应不同的启动命令）。
 */
class ServerRunConfiguration(
    override val id: String,
    private val resolve: (projectRoot: File, mode: String) -> String
) : RunConfiguration {
    override fun command(projectRoot: File, mode: String): String = resolve(projectRoot, mode)
    override fun supports(mode: String): Boolean = mode == "run" || mode == "start" || mode == "dev" || mode == "build" || mode == "test"
}

/**
 * Lua 相关命令片段。
 *
 * Termux 的 lua54 包只提供 `lua5.4` / `luac5.4`（已核对 deb 内容），而 luarocks、系统 lua
 * 等来源可能提供 `lua` / `luajit`，所以这里用一段可复用的 shell 兜底，保证装了任意一种都能跑，
 * 而不是在模板里写死一个可能不存在的可执行名。
 */
internal object LuaCommands {
    val RUNNER: String = com.nebulaforge.core.projectmodel.LanguageCommands.LUA_RUNNER

    val SYNTAX_CHECKER: String = com.nebulaforge.core.projectmodel.LanguageCommands.LUA_SYNTAX_CHECKER
}
