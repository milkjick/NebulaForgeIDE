package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.ProjectResolver
import com.nebulaforge.core.projectmodel.TaskDefinition
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import java.io.File

/** One execution entry point for Android, Flutter and Web Run Configurations. */
class UnifiedRunController(
    private val context: Context,
    private val bus: IdeSessionBus,
    private val stackController: StackRunController
) {
    private val android = RealAndroidBuildRunController(context.applicationContext, bus)

    private val stateMachine = RunExecutionStateMachine()

    fun run(spec: RunConfigurationSpec): Flow<IdeEvent> = flow {
        val validation = RunConfigurationValidator.validate(spec)
        if (!validation.ok) {
            emit(IdeEvent.State("validation-${System.currentTimeMillis()}", SessionState.Failed(validation.message ?: "运行配置无效")))
            return@flow
        }
        val resolved = RunPortCoordinator.resolve(spec).getOrElse {
            emit(IdeEvent.State("validation-${System.currentTimeMillis()}", SessionState.Failed(it.message ?: "端口分配失败")))
            return@flow
        }
        val command = runCatching { RunConfigurationEngine().command(resolved) }.getOrElse { "" }
        if (command.isBlank()) {
            emit(IdeEvent.State("validation-${System.currentTimeMillis()}", SessionState.Failed("无法生成运行命令")))
            return@flow
        }
        when (resolved.typeId) {
            "android" -> android.buildAndRun(File(resolved.projectPath), deviceSerial = resolved.deviceId).collect { emit(it) }
            else -> stackController.start(resolved).collect { emit(it) }
        }
    }

    fun build(spec: RunConfigurationSpec): Flow<IdeEvent> = flow {
        val validation = RunConfigurationValidator.validate(spec)
        if (!validation.ok) {
            emit(IdeEvent.State("validation-${System.currentTimeMillis()}", SessionState.Failed(validation.message ?: "运行配置无效")))
            return@flow
        }
        when (spec.typeId) {
            "android" -> android.buildOnly(File(spec.projectPath)).collect { emit(it) }
            else -> buildScript(spec).collect { emit(it) }
        }
    }

    /** 停止一次构建会话（构建页「停止」按钮走这里）。 */
    fun cancelBuild(sessionId: String): Boolean = android.cancelBuild(sessionId)

    fun stop(sessionId: String): Boolean = stackController.stop(sessionId) || android.cancelRun(sessionId) || android.stopLogcat(sessionId)

    fun restart(spec: RunConfigurationSpec): Flow<IdeEvent> = flow {
        emitAll(run(spec))
    }

    private fun buildScript(spec: RunConfigurationSpec): Flow<IdeEvent> = channelFlow {
        val id = "build-${System.currentTimeMillis()}-${spec.id}"
        val session = IdeSession(id, SessionKind.BUILD)
        bus.register(session, spec.projectPath)
        // Flutter/Web 有固定的构建入口；其余技术栈（Go/Rust/PHP/Python/C++/Lua）的"构建"
        // 就是各自 CLI 的一条命令，交给它们的 BuildSystem 决定，而不是在这里硬编码到只认
        // flutter/web 的 when 里——否则这些模板"能新建、不能构建"。
        val projectDir = File(spec.projectPath)
        val command = when (spec.typeId) {
            // 平台目录自愈：模板工程没有 android/ 骨架，直接 build apk 必失败（见 TaskDefinition.flutterInvocation）。
            "flutter" -> when (spec.mode) {
                "web" -> "flutter build web"
                "release" -> TaskDefinition.flutterInvocation("build apk --release", needsAndroidPlatform = true)
                else -> TaskDefinition.flutterInvocation("build apk --debug", needsAndroidPlatform = true)
            }
            "web-frontend", "web-backend" -> "npm run build"
            else -> ProjectResolver().resolve(projectDir)?.buildSystem?.listAvailableTasks(projectDir)?.firstOrNull()
        }
        if (command.isNullOrBlank()) {
            trySend(IdeEvent.State(id, SessionState.Failed("不支持的构建技术栈：${spec.typeId}（未解析到可执行构建任务）"))); close(); return@channelFlow
        }
        val env = when (spec.typeId) {
            "flutter" -> Environment.buildFlutterEnv(context)
            "web-frontend", "web-backend" -> Environment.buildNodeEnv(context)
            else -> Environment.buildTerminalEnv(context)
        }.toMutableMap().apply {
            putAll(spec.environment)
            if (spec.port != null && spec.typeId.startsWith("web-")) putIfAbsent("PORT", spec.port.toString())
        }
        val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
        bus.state(session, SessionState.Running(command), spec.projectPath)
        executor.execute(command, File(spec.projectPath), env).collect { e ->
            when (e) {
                is TermuxCommandExecutor.Event.Line -> bus.emit(IdeEvent.Output(id, e.text))
                is TermuxCommandExecutor.Event.Finished -> bus.state(session, if (e.exitCode == 0) SessionState.Succeeded("构建完成") else SessionState.Failed("构建退出码 ${e.exitCode}", e.exitCode), spec.projectPath)
            }
        }
    }
}
