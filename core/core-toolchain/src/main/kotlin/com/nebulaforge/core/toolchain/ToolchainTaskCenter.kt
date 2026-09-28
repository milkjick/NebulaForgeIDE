package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persistent toolchain task center. A task is a real process/install operation, not a UI spinner.
 * State is recoverable after Activity recreation; a running PID is never falsely restored as running.
 */
enum class ToolchainTaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class ToolchainTask(
    val id: String,
    val title: String,
    val component: ToolchainComponent?,
    val state: ToolchainTaskState,
    val progress: Int,
    val message: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val logs: List<String> = emptyList()
)

class ToolchainTaskCenter(private val context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile = File(Environment.homeRoot(app), ".nebulaforge/toolchain/tasks.json")
    private val jobs = mutableMapOf<String, Job>()
    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<ToolchainTask>> = _tasks.asStateFlow()

    fun enqueueInstall(component: ToolchainComponent, installer: suspend (suspend (Int, String) -> Unit) -> Unit): String {
        // 同一组件只保留一个未结束任务：重复点击复用旧任务，而不是再排一个。
        // 否则两个 apt 会争 dpkg 锁，后启动的必然 EXIT=100（真机上就是这么「安装失败」的）。
        activeTaskFor(component.name)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        update(ToolchainTask(id, component.title, component, ToolchainTaskState.QUEUED, 0, "等待执行", now))
        val job = scope.launch {
            update(current(id).copy(state = ToolchainTaskState.RUNNING, message = "开始安装…"))
            try {
                installer { percent, message ->
                    coroutineContext.ensureActive()
                    update(current(id).copy(progress = percent.coerceIn(0, 100), message = message, logs = (current(id).logs + message).takeLast(120)))
                }
                update(current(id).copy(state = ToolchainTaskState.SUCCEEDED, progress = 100, message = "安装并验证完成", finishedAt = System.currentTimeMillis()))
            } catch (e: CancellationException) {
                update(current(id).copy(state = ToolchainTaskState.CANCELLED, message = "已取消", finishedAt = System.currentTimeMillis()))
                throw e
            } catch (t: Throwable) {
                update(current(id).copy(state = ToolchainTaskState.FAILED, message = t.message ?: t.javaClass.simpleName, finishedAt = System.currentTimeMillis(), logs = (current(id).logs + (t.message ?: t.javaClass.simpleName)).takeLast(120)))
            } finally { jobs.remove(id) }
        }
        jobs[id] = job
        return id
    }

    fun enqueueCustom(title: String, installer: suspend (suspend (Int, String) -> Unit) -> Unit): String {
        activeTaskFor(title)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        update(ToolchainTask(id, title, null, ToolchainTaskState.QUEUED, 0, "等待执行", now))
        val job = scope.launch {
            update(current(id).copy(state = ToolchainTaskState.RUNNING, message = "开始执行…"))
            try {
                installer { percent, message ->
                    coroutineContext.ensureActive()
                    update(current(id).copy(progress = percent.coerceIn(0, 100), message = message, logs = (current(id).logs + message).takeLast(120)))
                }
                update(current(id).copy(state = ToolchainTaskState.SUCCEEDED, progress = 100, message = "完成", finishedAt = System.currentTimeMillis()))
            } catch (e: CancellationException) {
                update(current(id).copy(state = ToolchainTaskState.CANCELLED, message = "已取消", finishedAt = System.currentTimeMillis()))
                throw e
            } catch (t: Throwable) {
                update(current(id).copy(state = ToolchainTaskState.FAILED, message = t.message ?: t.javaClass.simpleName, finishedAt = System.currentTimeMillis(), logs = (current(id).logs + (t.message ?: t.javaClass.simpleName)).takeLast(120)))
            } finally { jobs.remove(id) }
        }
        jobs[id] = job
        return id
    }

    /** 该组件是否已有排队中/运行中的安装任务（UI 用它把提示从「已加入」改成「已在运行，已复用」）。 */
    fun hasActiveTask(component: ToolchainComponent): Boolean = activeTaskFor(component.name) != null

    /**
     * 组内是否已有未结束任务。
     *
     * Android SDK 的三个组件（cmdline-tools / platform-tools / build-tools）最终都走同一个
     * sdkmanager，重复排队只会互相等待，界面上还会出现「ADB 出现两次」这种重复任务。
     * 于是用一组 key 做去重，命中即复用。
     */
    fun activeTaskInGroup(keys: Collection<String>): ToolchainTask? =
        keys.firstNotNullOfOrNull { activeTaskFor(it) }

    /** 未结束（排队中或运行中）且 key 相同的任务；key 优先用组件名，自定义任务退化为标题。 */
    private fun activeTaskFor(key: String): ToolchainTask? = _tasks.value.firstOrNull { task ->
        (task.state == ToolchainTaskState.QUEUED || task.state == ToolchainTaskState.RUNNING) &&
            (task.component?.name ?: task.title) == key
    }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel(CancellationException("user cancelled"))
        return true
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.state == ToolchainTaskState.QUEUED || it.state == ToolchainTaskState.RUNNING }
        persist()
    }

    private fun current(id: String): ToolchainTask = _tasks.value.first { it.id == id }
    private fun update(task: ToolchainTask) {
        _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
        persist()
    }

    private fun persist() = runCatching {
        stateFile.parentFile?.mkdirs()
        val a = JSONArray()
        _tasks.value.takeLast(80).forEach { t ->
            a.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("component", t.component?.name)
                put("state", t.state.name); put("progress", t.progress); put("message", t.message)
                put("startedAt", t.startedAt); put("finishedAt", t.finishedAt ?: JSONObject.NULL)
                put("logs", JSONArray(t.logs))
            })
        }
        stateFile.writeText(a.toString())
    }

    private fun load(): List<ToolchainTask> = runCatching {
        if (!stateFile.exists()) return@runCatching emptyList()
        val a = JSONArray(stateFile.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val persistedState = ToolchainTaskState.valueOf(o.optString("state", "FAILED"))
                val state = if (persistedState == ToolchainTaskState.RUNNING || persistedState == ToolchainTaskState.QUEUED) ToolchainTaskState.FAILED else persistedState
                val logsJson = o.optJSONArray("logs")
                val logs = buildList { if (logsJson != null) for (j in 0 until logsJson.length()) add(logsJson.optString(j)) }
                add(ToolchainTask(o.getString("id"), o.getString("title"), o.optString("component").takeIf { it.isNotBlank() && it != "null" }?.let { ToolchainComponent.valueOf(it) }, state, o.optInt("progress"), if (state == ToolchainTaskState.FAILED && persistedState == ToolchainTaskState.RUNNING) "IDE 进程重启，任务未继续执行" else o.optString("message"), o.optLong("startedAt"), if (o.isNull("finishedAt")) null else o.optLong("finishedAt"), logs))
            }
        }
    }.getOrDefault(emptyList())
}

/** Android SDK package profiles used by the real sdkmanager installer. */
object AndroidSdkPackages {
    const val PLATFORM_TOOLS = "platform-tools"
    const val CMDLINE_TOOLS = "cmdline-tools;latest"
    const val BUILD_TOOLS_34 = "build-tools;34.0.0"
    const val BUILD_TOOLS_35 = "build-tools;35.0.0"
    const val BUILD_TOOLS_36 = "build-tools;36.0.0"
    const val PLATFORM_34 = "platforms;android-34"
    const val PLATFORM_35 = "platforms;android-35"
    const val PLATFORM_36 = "platforms;android-36"

    val supportedPlatformApis = listOf(34, 35, 36)
    val supportedBuildTools = listOf("34.0.0", "35.0.0", "36.0.0")
    const val NDK_27 = "ndk;27.2.12479018"

    /**
     * 最小 Android 构建 profile。
     *
     * 用 **34** 而不是 35：内置用户态预装的就是 android-34（项目模板、App 自身模块也都是 34），
     * profile 与模板/实际安装必须同版本，否则会出现「profile 装 35、模板写 35、设备只有 34」
     * 这类互相矛盾的组合（真机上表现为安卓空项目建出来就编译失败）。
     * 需要 35/36 的用户可用 [BUILD_TOOLS_35] / [PLATFORM_35] / [PLATFORM_36] 显式安装。
     */
    val minimalAndroidBuild = listOf(CMDLINE_TOOLS, PLATFORM_TOOLS, BUILD_TOOLS_34, PLATFORM_34)
}
