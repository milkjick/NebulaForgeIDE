package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.projectmodel.ProjectDescriptor
import com.nebulaforge.core.projectmodel.ProjectResolver
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Resident Run controller for Flutter/Web using the same embedded Way-B PTY execution path. */
class StackRunController(
    private val context: Context,
    private val bus: IdeSessionBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val jobs = mutableMapOf<String, Job>()
    private val sessions = mutableMapOf<String, IdeSession>()
    private val resolver = ProjectResolver()

    fun start(projectRoot: File, mode: String = "run"): Flow<IdeEvent> = channelFlow {
        val descriptor = resolver.resolve(projectRoot)
        val config = descriptor?.type?.createRunConfiguration()
        if (descriptor == null || config == null || !config.supports(mode)) {
            trySend(IdeEvent.State(UUID.randomUUID().toString(), SessionState.Failed("项目没有可用的运行配置")))
            close(); return@channelFlow
        }
        val env = when (descriptor.type.id) {
            "flutter" -> Environment.buildFlutterEnv(context)
            "web-frontend", "web-backend" -> Environment.buildNodeEnv(context)
            else -> Environment.buildTerminalEnv(context)
        }
        startInternal(descriptor, config.command(descriptor.root, mode), config.environment(env), this)
    }

    fun start(spec: RunConfigurationSpec): Flow<IdeEvent> = channelFlow {
        val descriptor = resolver.resolve(File(spec.projectPath))
        val config = descriptor?.type?.createRunConfiguration()
        if (descriptor == null || config == null || descriptor.type.id != spec.typeId || !config.supports(spec.mode)) {
            trySend(IdeEvent.State(UUID.randomUUID().toString(), SessionState.Failed("运行配置无效或项目类型不匹配")))
            close(); return@channelFlow
        }
        val baseEnv = when (descriptor.type.id) {
            "flutter" -> Environment.buildFlutterEnv(context)
            "web-frontend", "web-backend" -> Environment.buildNodeEnv(context)
            else -> Environment.buildTerminalEnv(context)
        }
        val env = config.environment(baseEnv).toMutableMap().apply {
            putAll(spec.environment)
            if (spec.port != null && descriptor.type.id.startsWith("web-")) putIfAbsent("PORT", spec.port.toString())
        }
        val extra = buildList {
            addAll(spec.arguments)
            if (descriptor.type.id == "flutter" && spec.mode == "web" && spec.port != null && spec.arguments.none { it == "--web-port" }) {
                add("--web-port=${spec.port}")
            }
            val deviceId = spec.deviceId
            if (descriptor.type.id == "flutter" && !deviceId.isNullOrBlank() && spec.arguments.none { it == "-d" || it == "--device-id" }) {
                add("-d"); add(deviceId)
            }
        }
        val args = extra.joinToString(" ") { shellQuote(it) }
        val command = config.command(descriptor.root, spec.mode) + if (args.isBlank()) "" else " $args"
        val work = spec.workingDirectory?.takeIf { it.isNotBlank() }?.let(::File) ?: descriptor.root
        startInternal(descriptor, command, env, this, work)
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<IdeEvent>.startInternal(
        descriptor: ProjectDescriptor,
        command: String,
        env: Map<String, String>,
        ignored: kotlinx.coroutines.channels.ProducerScope<IdeEvent>,
        workingDirectory: File = descriptor.root
    ) {
        val id = UUID.randomUUID().toString()
        val session = IdeSession(id, SessionKind.RUN)
        bus.register(session, descriptor.root.absolutePath)
        sessions[id] = session
        val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
        val job = scope.launch {
            bus.state(session, SessionState.Running(command), descriptor.root.absolutePath)
            trySend(IdeEvent.State(id, SessionState.Running(command)))
            try {
                executor.execute(command, workingDirectory, env).collect { event ->
                    when (event) {
                        is TermuxCommandExecutor.Event.Line -> {
                            val out = IdeEvent.Output(id, event.text)
                            bus.emit(out); trySend(out)
                        }
                        is TermuxCommandExecutor.Event.Finished -> {
                            val state = if (event.exitCode == 0) SessionState.Succeeded("运行进程已退出")
                            else SessionState.Failed("运行进程退出码 ${event.exitCode}", event.exitCode)
                            bus.state(session, state, descriptor.root.absolutePath)
                            trySend(IdeEvent.State(id, state))
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    val state = SessionState.Failed(t.message ?: "运行失败")
                    bus.state(session, state, descriptor.root.absolutePath)
                    trySend(IdeEvent.State(id, state))
                }
            }
        }
        jobs[id] = job
        try { job.join() } finally { jobs.remove(id); sessions.remove(id) }
    }

    fun stop(sessionId: String): Boolean {
        val job = jobs.remove(sessionId) ?: return false
        job.cancel()
        sessions.remove(sessionId)?.let { bus.state(it, SessionState.Cancelled, null) }
        return true
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
