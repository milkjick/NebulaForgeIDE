package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.toolchain.ProjectEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class RunSessionStatus { QUEUED, RESOLVING_DEVICE, INSTALLING, INSTALLED, LAUNCHING, RUNNING, DEVICE_DISCONNECTED, PROCESS_EXITED, STOPPING, STOPPED, SUCCEEDED, FAILED, CANCELLED }

data class RunSessionRecord(
    val id: String, val projectPath: String, val apkPath: String?, val deviceSerial: String?,
    val status: RunSessionStatus, val startedAt: Long, val finishedAt: Long? = null,
    val lastMessage: String = "", val logcatSessionId: String? = null,
    val packageName: String? = null, val pid: String? = null, val exitCode: Int? = null
)

/** One authoritative Android install/launch/process/device/logcat lifecycle. */
class RunSessionManager(context: Context, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), private val workspace: WorkspaceStateStore? = null) {
    private val file = File(context.applicationContext.filesDir, "sessions/run.json")
    private val jobs = mutableMapOf<String, Job>()
    private val deviceMonitor = RunDeviceEventMonitor(TermuxCommandExecutor("/system/bin/sh"))
    private val machine = RunExecutionStateMachine()
    private val runtime = mutableMapOf<String, RuntimeContext>()
    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<RunSessionRecord>> = _sessions.asStateFlow()

    fun start(project: File, apk: File, environment: ProjectEnvironment, packageName: String?, bus: IdeSessionBus, preferredDeviceSerial: String? = null): String {
        val id = UUID.randomUUID().toString()
        val session = IdeSession(id, SessionKind.RUN)
        bus.register(session, project.absolutePath)
        workspace?.selectProject(project.absolutePath); workspace?.recordRun(id)
        runtime[id] = RuntimeContext(environment, bus, project.absolutePath, packageName)
        update(RunSessionRecord(id, project.absolutePath, apk.absolutePath, null, RunSessionStatus.QUEUED, System.currentTimeMillis(), packageName = packageName))
        jobs[id] = scope.launch {
            transition(id, RunSessionStatus.RESOLVING_DEVICE, "查找设备", session, bus, project.absolutePath)
            val device = adbDevices(environment).let { list -> if (!preferredDeviceSerial.isNullOrBlank()) list.firstOrNull { it.serial == preferredDeviceSerial } else list.firstOrNull() }
            if (device == null) return@launch fail(id, session, bus, if (preferredDeviceSerial.isNullOrBlank()) "没有可用 Android 设备" else "指定 Android 设备不存在或未授权：$preferredDeviceSerial", project.absolutePath)
            update(current(id).copy(deviceSerial = device.serial, lastMessage = "安装 ${device.serial}")); bus.emit(IdeEvent.Device(id, device.serial, device.state))
            transition(id, RunSessionStatus.INSTALLING, "安装 APK", session, bus, project.absolutePath)
            val install = adb(environment, device.serial, listOf("install", "-r", apk.absolutePath))
            bus.emit(IdeEvent.Output(id, install.output, install.code != 0))
            if (install.code != 0) return@launch fail(id, session, bus, "ADB install 失败", project.absolutePath, install.code)
            transition(id, RunSessionStatus.INSTALLED, "APK 已安装", session, bus, project.absolutePath)
            if (packageName.isNullOrBlank()) {
                transition(id, RunSessionStatus.SUCCEEDED, "APK 已安装，未提供包名，运行结束", session, bus, project.absolutePath)
                return@launch
            }
            transition(id, RunSessionStatus.LAUNCHING, "启动 $packageName", session, bus, project.absolutePath)
            val launch = adb(environment, device.serial, listOf("shell", "monkey", "-p", packageName, "1"))
            bus.emit(IdeEvent.Output(id, launch.output, launch.code != 0))
            if (launch.code != 0) return@launch fail(id, session, bus, "应用启动失败", project.absolutePath, launch.code)
            val pid = waitForPid(environment, device.serial, packageName)
            if (pid.isNullOrBlank()) return@launch fail(id, session, bus, "应用已启动但无法获取 PID", project.absolutePath)
            update(current(id).copy(pid = pid, lastMessage = "运行中 PID=$pid"))
            transition(id, RunSessionStatus.RUNNING, "运行中 PID=$pid", session, bus, project.absolutePath)
            startDeviceMonitor(id, session, environment, device.serial, bus, project.absolutePath)
            monitorProcess(id, session, environment, device.serial, packageName, bus, project.absolutePath)
        }
        return id
    }

    fun cancel(id: String): Boolean = stop(id, runtime[id]?.environment, runtime[id]?.bus, runtime[id]?.project)

    fun stop(id: String, environment: ProjectEnvironment? = null, bus: IdeSessionBus? = null, project: String? = null): Boolean {
        val record = _sessions.value.firstOrNull { it.id == id } ?: return false
        jobs[id]?.cancel(); jobs.remove(id)
        jobs.remove("$id:device-monitor")?.cancel(); jobs.remove("$id:process")?.cancel(); jobs.remove("$id:logcat")?.cancel(); jobs.remove("$id:logcat-wait")?.cancel()
        if (environment != null && bus != null && !record.deviceSerial.isNullOrBlank() && !record.packageName.isNullOrBlank()) {
            update(record.copy(status = RunSessionStatus.STOPPING, lastMessage = "停止应用")); bus.state(IdeSession(id, SessionKind.RUN), SessionState.Running("停止应用"), project ?: record.projectPath)
            scope.launch { val r = adb(environment, record.deviceSerial, listOf("shell", "am", "force-stop", record.packageName)); bus.emit(IdeEvent.Output(id, r.output, r.code != 0)); update(current(id).copy(status = if (r.code == 0) RunSessionStatus.STOPPED else RunSessionStatus.FAILED, lastMessage = if (r.code == 0) "已停止" else "停止失败", finishedAt = System.currentTimeMillis(), exitCode = r.code.takeIf { it != 0 })); bus.state(IdeSession(id, SessionKind.RUN), if (r.code == 0) SessionState.Succeeded("已停止") else SessionState.Failed("停止失败", r.code), project ?: record.projectPath) }
        } else {
            update(record.copy(status = RunSessionStatus.CANCELLED, lastMessage = "已取消", finishedAt = System.currentTimeMillis()))
        }
        runtime.remove(id)
        return true
    }

    fun stopLogcat(id: String): Boolean = jobs.remove("$id:logcat")?.let { it.cancel(); true } ?: false

    fun startLogcatWhenReady(id: String, environment: ProjectEnvironment, executor: TermuxCommandExecutor, bus: IdeSessionBus): String {
        val logcatId = "$id:logcat"; jobs["$id:logcat-wait"]?.cancel()
        jobs["$id:logcat-wait"] = scope.launch { repeat(100) { val r = currentOrNull(id); if (r?.deviceSerial != null && r.status in setOf(RunSessionStatus.RUNNING, RunSessionStatus.LAUNCHING, RunSessionStatus.INSTALLED)) { startLogcat(id, r.deviceSerial, environment, executor, bus); return@launch }; if (r == null || r.status in setOf(RunSessionStatus.FAILED, RunSessionStatus.CANCELLED, RunSessionStatus.STOPPED, RunSessionStatus.SUCCEEDED)) return@launch; delay(100) } }
        return logcatId
    }

    private fun startDeviceMonitor(id: String, session: IdeSession, environment: ProjectEnvironment, serial: String, bus: IdeSessionBus, project: String) {
        jobs["$id:device-monitor"]?.cancel()
        val monitor = RunDeviceEventMonitor(TermuxCommandExecutor(environment.variables["SHELL"] ?: "/system/bin/sh"))
        jobs["$id:device-monitor"] = scope.launch { monitor.events(environment.project.root, environment.variables).collect { event ->
            if (event.serial != serial) return@collect
            bus.emit(IdeEvent.Device(id, event.serial, event.state.name.lowercase()))
            if (event.state != RunDeviceStatus.ONLINE) {
                val current = currentOrNull(id) ?: return@collect
                if (current.status == RunSessionStatus.RUNNING || current.status == RunSessionStatus.LAUNCHING || current.status == RunSessionStatus.INSTALLED) {
                    transition(id, RunSessionStatus.DEVICE_DISCONNECTED, "设备 ${event.state.name.lowercase()}", session, bus, project)
                    update(currentOrNull(id)!!.copy(status = RunSessionStatus.FAILED, lastMessage = "设备连接状态变为 ${event.state.name.lowercase()}", finishedAt = System.currentTimeMillis()))
                    bus.state(session, SessionState.Failed("设备已断开或未授权：${event.state.name.lowercase()}"), project); jobs["$id:logcat"]?.cancel(); jobs["$id:process"]?.cancel()
                }
            }
        } }
    }

    private fun monitorProcess(id: String, session: IdeSession, environment: ProjectEnvironment, serial: String, packageName: String, bus: IdeSessionBus, project: String) {
        jobs["$id:process"]?.cancel(); jobs["$id:process"] = scope.launch {
            while (isActive) {
                delay(1000)
                val record = currentOrNull(id) ?: break
                if (record.status != RunSessionStatus.RUNNING) break
                val pid = pid(environment, serial, packageName)
                if (pid.isNullOrBlank()) {
                    transition(id, RunSessionStatus.PROCESS_EXITED, "进程已退出", session, bus, project)
                    update(record.copy(status = RunSessionStatus.SUCCEEDED, lastMessage = "应用进程已退出", finishedAt = System.currentTimeMillis()))
                    bus.state(session, SessionState.Succeeded("应用进程已退出"), project)
                    jobs["$id:device-monitor"]?.cancel(); jobs["$id:logcat"]?.cancel(); break
                }
            }
        }
    }

    private suspend fun waitForPid(environment: ProjectEnvironment, serial: String, pkg: String): String? { repeat(15) { pid(environment, serial, pkg)?.let { return it }; delay(200) }; return null }
    private suspend fun pid(environment: ProjectEnvironment, serial: String, pkg: String): String? { val r = adb(environment, serial, listOf("shell", "pidof", pkg)); return if (r.code == 0) r.output.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.matches(Regex("[0-9]+")) } else null }
    private suspend fun adbDevices(environment: ProjectEnvironment): List<DeviceInfo> { val lines = mutableListOf<String>(); TermuxCommandExecutor(environment.variables["SHELL"] ?: "/system/bin/sh").execute("adb devices", environment.project.root, environment.variables).collect { if (it is TermuxCommandExecutor.Event.Line) lines += it.text }; return lines.drop(1).mapNotNull { line -> val p = line.trim().split(Regex("\\s+")); if (p.size >= 2) DeviceInfo(p[0], p[1]) else null }.filter { it.state == "device" } }
    private suspend fun adb(environment: ProjectEnvironment, serial: String, args: List<String>): Cmd { val lines = mutableListOf<String>(); var code = 1; TermuxCommandExecutor(environment.variables["SHELL"] ?: "/system/bin/sh").execute("adb -s ${quote(serial)} ${args.joinToString(" ") { quote(it) }}", environment.project.root, environment.variables).collect { e -> when (e) { is TermuxCommandExecutor.Event.Line -> lines += e.text; is TermuxCommandExecutor.Event.Finished -> code = e.exitCode } }; return Cmd(code, lines.joinToString("\n")) }
    private fun transition(id: String, status: RunSessionStatus, message: String, session: IdeSession, bus: IdeSessionBus, project: String) { update(current(id).copy(status = status, lastMessage = message)); bus.state(session, when(status) { RunSessionStatus.QUEUED -> SessionState.Preparing(message); RunSessionStatus.SUCCEEDED, RunSessionStatus.STOPPED, RunSessionStatus.PROCESS_EXITED -> SessionState.Succeeded(message); RunSessionStatus.FAILED, RunSessionStatus.DEVICE_DISCONNECTED -> SessionState.Failed(message); RunSessionStatus.CANCELLED -> SessionState.Cancelled; else -> SessionState.Running(message) }, project) }
    private fun fail(id: String, session: IdeSession, bus: IdeSessionBus, message: String, project: String, code: Int? = null) { update(current(id).copy(status = RunSessionStatus.FAILED, lastMessage = message, finishedAt = System.currentTimeMillis(), exitCode = code)); bus.state(session, SessionState.Failed(message, code), project) }
    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
    private data class Cmd(val code: Int, val output: String)
    private data class RuntimeContext(val environment: ProjectEnvironment, val bus: IdeSessionBus, val project: String, val packageName: String?)
    private fun current(id: String) = _sessions.value.first { it.id == id }
    private fun currentOrNull(id: String) = _sessions.value.firstOrNull { it.id == id }
    @Synchronized private fun update(r: RunSessionRecord) { _sessions.value = (_sessions.value.filterNot { it.id == r.id } + r).takeLast(64); persist() }
    private fun updateLogcatId(id: String, logcatId: String) { currentOrNull(id)?.let { update(it.copy(logcatSessionId = logcatId)) } }
    private fun persist() = runCatching { file.parentFile?.mkdirs(); val a=JSONArray(); _sessions.value.forEach { r -> a.put(JSONObject().apply { put("id",r.id);put("projectPath",r.projectPath);put("apkPath",r.apkPath ?: JSONObject.NULL);put("deviceSerial",r.deviceSerial ?: JSONObject.NULL);put("status",r.status.name);put("startedAt",r.startedAt);put("finishedAt",r.finishedAt ?: JSONObject.NULL);put("lastMessage",r.lastMessage);put("logcatSessionId",r.logcatSessionId ?: JSONObject.NULL);put("packageName",r.packageName ?: JSONObject.NULL);put("pid",r.pid ?: JSONObject.NULL);put("exitCode",r.exitCode ?: JSONObject.NULL) }) }; val tmp=File(file.parentFile,"run.json.tmp");tmp.writeText(a.toString());if(!tmp.renameTo(file)){file.delete();check(tmp.renameTo(file))} }
    private fun load(): List<RunSessionRecord> = runCatching { if(!file.isFile)return@runCatching emptyList(); val a=JSONArray(file.readText());buildList { for(i in 0 until a.length()){val o=a.getJSONObject(i);val persisted=runCatching{RunSessionStatus.valueOf(o.optString("status"))}.getOrDefault(RunSessionStatus.FAILED);val status=if(persisted in setOf(RunSessionStatus.QUEUED,RunSessionStatus.RESOLVING_DEVICE,RunSessionStatus.INSTALLING,RunSessionStatus.INSTALLED,RunSessionStatus.LAUNCHING,RunSessionStatus.RUNNING,RunSessionStatus.STOPPING))RunSessionStatus.FAILED else persisted;add(RunSessionRecord(o.getString("id"),o.getString("projectPath"),o.optString("apkPath").takeIf{it.isNotBlank()},o.optString("deviceSerial").takeIf{it.isNotBlank()},status,o.optLong("startedAt"),if(o.isNull("finishedAt"))null else o.optLong("finishedAt"),if(status==RunSessionStatus.FAILED&&persisted!=status)"IDE 重启，原运行进程已不存在" else o.optString("lastMessage"),o.optString("logcatSessionId").takeIf{it.isNotBlank()},o.optString("packageName").takeIf{it.isNotBlank()},o.optString("pid").takeIf{it.isNotBlank()},if(o.isNull("exitCode"))null else o.optInt("exitCode")))}}}.getOrDefault(emptyList())
    fun startLogcat(id:String,serial:String,environment:ProjectEnvironment,executor:TermuxCommandExecutor,bus:IdeSessionBus):String { val logcatId="$id:logcat";jobs[logcatId]?.cancel();val session=IdeSession(logcatId,SessionKind.LOGCAT);bus.register(session,environment.project.root.absolutePath);updateLogcatId(id,logcatId);bus.emit(IdeEvent.Relation(id, logcatId, "logcat"));jobs[logcatId]=scope.launch{bus.state(session,SessionState.Running("adb logcat"),environment.project.root.absolutePath);executor.execute("adb -s ${quote(serial)} logcat -v time",environment.project.root,environment.variables).collect{e->when(e){is TermuxCommandExecutor.Event.Line->bus.emit(IdeEvent.Output(logcatId,e.text));is TermuxCommandExecutor.Event.Finished->bus.state(session,if(e.exitCode==0)SessionState.Succeeded("Logcat 已停止") else SessionState.Failed("Logcat 已退出",e.exitCode),environment.project.root.absolutePath)}}};return logcatId }
}

data class DeviceInfo(val serial: String, val state: String)
