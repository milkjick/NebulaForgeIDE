package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.projectmodel.BuildEvent
import com.nebulaforge.core.projectmodel.BuildSystem
import com.nebulaforge.core.toolchain.ProjectEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Build Session 的唯一生命周期管理器。
 * 持久化的是状态/日志摘要，不伪造恢复一个已经死亡的 Gradle 进程。
 */
enum class BuildSessionStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class BuildSessionRecord(
    val id: String,
    val projectPath: String,
    val task: String,
    val status: BuildSessionStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val lastMessage: String = "",
    val logTail: List<String> = emptyList(),
    val jdkHome: String? = null,
    val sdkRoot: String? = null,
    val environmentIssues: List<String> = emptyList()
)

class BuildSessionManager(context: Context, private val bus: IdeSessionBus? = null) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(app.filesDir, "sessions/build.json")
    private val jobs = mutableMapOf<String, Job>()
    private val streams = mutableMapOf<String, MutableSharedFlow<BuildEvent>>()
    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<BuildSessionRecord>> = _sessions.asStateFlow()

    fun start(projectPath: File, task: String, system: BuildSystem, env: Map<String, String>): String =
        startInternal(projectPath, task, system, env, null)

    /** Preferred entry point: one resolved ProjectEnvironment is the source of truth. */
    fun start(projectPath: File, task: String, system: BuildSystem, environment: ProjectEnvironment): String =
        startInternal(projectPath, task, system, environment.variables, environment)

    private fun startInternal(projectPath: File, task: String, system: BuildSystem, env: Map<String, String>, environment: ProjectEnvironment?): String {
        val id = UUID.randomUUID().toString()
        streams[id] = MutableSharedFlow(extraBufferCapacity = 512, replay = 64)
        update(BuildSessionRecord(id, projectPath.absolutePath, task, BuildSessionStatus.QUEUED, System.currentTimeMillis(), jdkHome = environment?.jdkHome?.absolutePath, sdkRoot = environment?.sdkRoot?.absolutePath, environmentIssues = environment?.issues.orEmpty()))
        val ideSession = IdeSession(id, SessionKind.BUILD)
        bus?.register(ideSession, projectPath.absolutePath)
        bus?.state(ideSession, SessionState.Preparing("排队：$task"), projectPath.absolutePath)
        jobs[id] = scope.launch {
            update(current(id).copy(status = BuildSessionStatus.RUNNING, lastMessage = "开始 $task"))
            bus?.state(ideSession, SessionState.Running("$task"), projectPath.absolutePath)
            try {
                system.build(task, projectPath, env).collect { event ->
                    streams[id]?.tryEmit(event)
                    when (event) {
                        is BuildEvent.Progress -> {
                            update(current(id).copy(lastMessage = "${event.percent}% ${event.message}"))
                            bus?.emit(IdeEvent.Output(id, "[${event.percent}%] ${event.message}"))
                        }
                        is BuildEvent.LogLine -> {
                            update(current(id).copy(lastMessage = event.text, logTail = (current(id).logTail + event.text).takeLast(160)))
                            // stderr 标记随事件一起下发，构建页终端据此把错误行标红。
                            bus?.emit(IdeEvent.Output(id, event.text, stderr = event.isError))
                        }
                        is BuildEvent.Finished -> {
                            update(current(id).copy(
                                status = if (event.success) BuildSessionStatus.SUCCEEDED else BuildSessionStatus.FAILED,
                                lastMessage = if (event.success) "构建完成" else "构建失败",
                                finishedAt = System.currentTimeMillis()
                            ))
                            bus?.state(ideSession, if (event.success) SessionState.Succeeded("构建完成") else SessionState.Failed("构建失败"), projectPath.absolutePath)
                        }
                    }
                }
            } catch (e: CancellationException) {
                streams[id]?.tryEmit(BuildEvent.Finished(false, 0))
                update(current(id).copy(status = BuildSessionStatus.CANCELLED, lastMessage = "已取消", finishedAt = System.currentTimeMillis()))
                bus?.state(ideSession, SessionState.Cancelled, projectPath.absolutePath)
                throw e
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                update(current(id).copy(status = BuildSessionStatus.FAILED, lastMessage = message, finishedAt = System.currentTimeMillis()))
                bus?.state(ideSession, SessionState.Failed(message), projectPath.absolutePath)
            } finally { jobs.remove(id) }
        }
        return id
    }

    fun events(id: String): SharedFlow<BuildEvent> = streams.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 128, replay = 64) }.asSharedFlow()

    /**
     * 停止构建。
     *
     * 真机根因（2.12.95 用户实测「卡死了也不会停止构建」）：这里以前**只取消协程**，
     * 而真正的构建进程是 guest 侧的一整棵树（PTY shell → proot → flutter → dart/java），
     * 不属于本进程，取消协程对它毫无作用 —— 面板上点「停止」没有任何效果。
     * 现在补一手文件型握手：往构建脚本的哨兵目录写一个 `<工程名>.kill`，
     * guest 侧心跳循环每 20 秒检查一次，看到就杀掉整棵构建树。
     */
    fun cancel(id: String): Boolean {
        val job = jobs.remove(id) ?: return false
        runCatching {
            val name = _sessions.value.firstOrNull { it.id == id }?.projectPath
                ?.let { File(it).name }
            if (!name.isNullOrBlank()) {
                val dir = File(app.filesDir, "home/.nebulaforge/build")
                if (dir.isDirectory) File(dir, "$name.kill").writeText("cancel ${System.currentTimeMillis()}\n")
            }
        }
        job.cancel(CancellationException("user cancelled"))
        return true
    }

    fun clearFinished() {
        _sessions.value = _sessions.value.filterNot { it.status == BuildSessionStatus.SUCCEEDED || it.status == BuildSessionStatus.FAILED || it.status == BuildSessionStatus.CANCELLED }
        persist()
    }

    private fun current(id: String) = _sessions.value.first { it.id == id }
    private fun update(record: BuildSessionRecord) {
        _sessions.value = _sessions.value.filterNot { it.id == record.id } + record
        persist()
    }
    private fun persist() = runCatching {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        _sessions.value.takeLast(80).forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id); put("projectPath", s.projectPath); put("task", s.task); put("status", s.status.name)
                put("startedAt", s.startedAt); put("finishedAt", s.finishedAt ?: JSONObject.NULL); put("lastMessage", s.lastMessage); put("logTail", JSONArray(s.logTail)); put("jdkHome", s.jdkHome ?: JSONObject.NULL); put("sdkRoot", s.sdkRoot ?: JSONObject.NULL); put("environmentIssues", JSONArray(s.environmentIssues))
            })
        }
        file.writeText(a.toString())
    }
    private fun load(): List<BuildSessionRecord> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val a = JSONArray(file.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val persisted = BuildSessionStatus.valueOf(o.optString("status", "FAILED"))
                val status = if (persisted == BuildSessionStatus.RUNNING || persisted == BuildSessionStatus.QUEUED) BuildSessionStatus.FAILED else persisted
                val logs = o.optJSONArray("logTail")?.let { arr -> buildList { for (j in 0 until arr.length()) add(arr.optString(j)) } }.orEmpty()
                val issues = o.optJSONArray("environmentIssues")?.let { arr -> buildList { for (j in 0 until arr.length()) add(arr.optString(j)) } }.orEmpty()
                add(BuildSessionRecord(o.getString("id"), o.getString("projectPath"), o.getString("task"), status, o.optLong("startedAt"), if (o.isNull("finishedAt")) null else o.optLong("finishedAt"), if (status == BuildSessionStatus.FAILED && persisted == BuildSessionStatus.RUNNING) "IDE 重启，原构建进程已不存在" else o.optString("lastMessage"), logs, o.optString("jdkHome").takeIf { it.isNotBlank() }, o.optString("sdkRoot").takeIf { it.isNotBlank() }, issues))
            }
        }
    }.getOrDefault(emptyList())
}
