package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.BuildEvent
import com.nebulaforge.core.projectmodel.ProjectResolver
import com.nebulaforge.core.toolchain.ProjectToolchainResolver
import com.nebulaforge.core.toolchain.ProjectEnvironment
import com.nebulaforge.core.toolchain.ToolchainManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android 实机连续工作流：
 * ProjectEnvironment -> BuildSession -> APK -> RunSession/ADB -> Logcat。
 * Build/Run/Logcat 均由同一 IdeSessionBus 事件源驱动，不再各自维护独立输出循环。
 */
class RealAndroidBuildRunController(
    private val context: Context,
    private val bus: IdeSessionBus = IdeSessionBus(SessionEventJournal(context), SessionRegistry(context), WorkspaceStateStore(context)),
    @Suppress("unused") private val toolchain: ToolchainManager = ToolchainManager(context.applicationContext),
    private val projectResolver: ProjectResolver = ProjectResolver(),
    private val projectToolchain: ProjectToolchainResolver = ProjectToolchainResolver(context.applicationContext),
    private val buildSessions: BuildSessionManager = BuildSessionManager(context.applicationContext, bus),
    private val workspace: WorkspaceStateStore = WorkspaceStateStore(context.applicationContext),
    private val runSessions: RunSessionManager = RunSessionManager(context.applicationContext, workspace = workspace)
) {
    fun events() = bus.events

    fun recentEvents(limit: Int = 200) = SessionEventJournal(context).recent(limit)
    fun sessionRegistry() = SessionRegistry(context).records

    fun buildAndRun(projectDir: File, jdkMajor: Int = 17, deviceSerial: String? = null): Flow<IdeEvent> = buildInternal(projectDir, jdkMajor, runAfterBuild = true, deviceSerial = deviceSerial)

    /** Build only: returns after Gradle finishes, without starting ADB/run/logcat. */
    fun buildOnly(projectDir: File, jdkMajor: Int = 17): Flow<IdeEvent> = buildInternal(projectDir, jdkMajor, runAfterBuild = false, deviceSerial = null)

    private fun buildInternal(projectDir: File, jdkMajor: Int, runAfterBuild: Boolean, deviceSerial: String?): Flow<IdeEvent> = channelFlow {
        val forwarder = launch { bus.events.collect { trySend(it) } }
        try {
            val resolved = projectResolver.resolve(projectDir)
            if (resolved == null || resolved.type.id != "android") {
                val s = IdeSession(kind = SessionKind.BUILD)
                bus.state(s, SessionState.Failed("无法识别为 Android Gradle 项目"))
                return@channelFlow
            }

            val root: File = resolved.root
            val metadata = resolved.metadata
            val buildSystem = resolved.buildSystem
            val environment = projectToolchain.resolve(resolved, jdkMajor)
            if (!environment.ready) {
                val s = IdeSession(kind = SessionKind.BUILD)
                bus.state(s, SessionState.Failed("项目工具链未就绪：${environment.issues.joinToString("；")}"))
                return@channelFlow
            }
            projectToolchain.syncLocalProperties(environment)

            workspace.selectProject(root.absolutePath)
            val buildSessionId = buildSessions.start(root, "assembleDebug", buildSystem, environment)
            workspace.recordBuild(buildSessionId)
            val build = IdeSession(id = buildSessionId, kind = SessionKind.BUILD)
            // BuildSessionManager 已经是 Build 生命周期的唯一事件源；这里仅维护诊断投影。
            bus.replaceDiagnostics(build.id, emptyList())
            val output = StringBuilder()
            var success = false

            buildSessions.events(buildSessionId).collect { event ->
                when (event) {
                    is BuildEvent.Progress -> output.appendLine("[${event.percent}%] ${event.message}")
                    is BuildEvent.LogLine -> output.appendLine(event.text)
                    is BuildEvent.Finished -> success = event.success
                }
                if (event is BuildEvent.Finished) return@collect
            }

            if (!success) {
                val parsed = buildSystem.parseErrors(output.toString()).map { d ->
                    IdeEvent.Diagnostic(build.id, d.filePath, d.line, d.column, d.message, when (d.severity) {
                        BuildError.Severity.ERROR -> Severity.ERROR
                        BuildError.Severity.WARNING -> Severity.WARNING
                    })
                }
                bus.replaceDiagnostics(build.id, parsed)
                parsed.forEach { bus.emit(it) }
                return@channelFlow
            }

            val apk = findDebugApk(root) ?: run {
                bus.emit(IdeEvent.State(build.id, SessionState.Failed("构建成功但没有找到 debug APK")))
                return@channelFlow
            }
            workspace.recordArtifact(apk.absolutePath)
            bus.emit(IdeEvent.Artifact(build.id, apk.absolutePath, "apk"))
            // BuildSessionManager 已经发布 Succeeded；这里只补充 Artifact。

            if (!runAfterBuild) {
                return@channelFlow
            }

            val appRoot: File = root
            val packageName: String? = metadata.applicationId
            val runId = runSessions.start(appRoot, apk, environment, packageName, bus, deviceSerial)
            bus.emit(IdeEvent.Relation(runId, build.id, "build"))
            bus.emit(IdeEvent.Relation(build.id, runId, "run"))
            workspace.recordRun(runId)
            // RunSessionManager owns install/launch lifecycle. Run/Logcat continue on the
            // shared event bus, so the build flow can finish and callers can inspect failures.
            val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
            runSessions.startLogcatWhenReady(runId, environment, executor, bus)
            return@channelFlow
        } finally {
            forwarder.cancel()
        }
    }

    fun cancelBuild(sessionId: String): Boolean = buildSessions.cancel(sessionId)
    fun cancelRun(sessionId: String): Boolean = runSessions.cancel(sessionId)
    fun stopLogcat(sessionId: String): Boolean = runSessions.stopLogcat(sessionId)

    private fun findDebugApk(project: File): File? = project.walkTopDown()
        .firstOrNull { it.isFile && it.name.endsWith("-debug.apk") }
        ?: project.walkTopDown().firstOrNull { it.isFile && it.extension == "apk" && it.path.contains("outputs") }
}
