package com.nebulaforge.app.settings

import android.content.Context
import android.net.Uri
import com.nebulaforge.core.toolchain.GradleBridgeRuntimeManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nebulaforge.core.environment.BootstrapInstaller
import com.nebulaforge.core.environment.CheckResult
import com.nebulaforge.core.environment.EnvironmentChecker
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainManager
import com.nebulaforge.core.toolchain.ToolchainStatus
import com.nebulaforge.core.toolchain.ToolchainTask
import com.nebulaforge.core.toolchain.ToolchainTaskState
import com.nebulaforge.core.toolchain.AndroidSdkPackages
import com.nebulaforge.core.environment.Environment
import java.io.File
import com.nebulaforge.core.toolchain.LspArtifactManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class AndroidSdkVersionState(val api: Int, val platformInstalled: Boolean, val buildToolsInstalled: Boolean)

class SettingsViewModel(
    private val checker: EnvironmentChecker,
    private val installer: BootstrapInstaller,
    private val toolchain: ToolchainManager,
    private val appContext: Context
) : ViewModel() {
    private val _checkResults = MutableStateFlow<List<CheckResult>>(emptyList())
    val checkResults: StateFlow<List<CheckResult>> = _checkResults.asStateFlow()
    private val _installState = MutableStateFlow<BootstrapInstaller.InstallProgress?>(null)
    val installState: StateFlow<BootstrapInstaller.InstallProgress?> = _installState.asStateFlow()
    val toolchainStatuses: StateFlow<List<ToolchainStatus>> = toolchain.statuses
    val tasks: StateFlow<List<ToolchainTask>> = toolchain.taskCenter.tasks
    val lspStatuses: StateFlow<List<LspArtifactManager.Status>> = toolchain.lspStatuses
    /** 安装/探测的实时输出（toolchain.log 的内存镜像）。 */
    val toolchainLiveLog: StateFlow<List<String>> = toolchain.liveLog
    val androidSdkVersions: StateFlow<List<AndroidSdkVersionState>> = MutableStateFlow(emptyList())
    fun clearToolchainLiveLog() = toolchain.clearLiveLog()

    fun refreshAndroidSdkVersions() {
        val root = File(Environment.androidSdkRoot(appContext))
        (androidSdkVersions as MutableStateFlow).value = AndroidSdkPackages.supportedPlatformApis.map { api ->
            val platform = File(root, "platforms/android-$api").isDirectory
            val tools = File(root, "build-tools/$api.0.0").let { it.isDirectory && File(it, "aapt2").isFile }
            AndroidSdkVersionState(api, platform, tools)
        }
    }

    fun installAndroidSdkVersion(api: Int) {
        val taskId = toolchain.enqueueAndroidSdkVersion(api)
        _message.value = "已加入 Android SDK $api 安装任务：$taskId"
    }
    private val _busy = MutableStateFlow<ToolchainComponent?>(null)
    val busy: StateFlow<ToolchainComponent?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 已结束的工具链任务数：组件装完后据此自动重跑环境自检。 */
    private var lastFinishedTasks = 0

    init {
        refreshAndroidSdkVersions()
        refresh()
        // 组件「安装/修复」完成（成功/失败/取消）后必须重跑环境自检。
        // 否则 adb、aapt2 明明已经装好，设置页仍停留在旧快照显示
        // 「失败：adb binary unavailable」，用户会误判修复没生效。
        viewModelScope.launch {
            toolchain.taskCenter.tasks.collect { list ->
                val finished = list.count {
                    it.state == ToolchainTaskState.SUCCEEDED ||
                        it.state == ToolchainTaskState.FAILED ||
                        it.state == ToolchainTaskState.CANCELLED
                }
                if (finished != lastFinishedTasks) {
                    lastFinishedTasks = finished
                    refreshAndroidSdkVersions()
                    _checkResults.value = checker.runAllChecks()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _checkResults.value = checker.runAllChecks()
            toolchain.refresh()
            toolchain.refreshLspArtifacts()
        }
    }

    fun startBootstrapInstall() {
        viewModelScope.launch {
            installer.install().collect { progress ->
                _installState.value = progress
                if (progress is BootstrapInstaller.InstallProgress.Completed) refresh()
            }
        }
    }

    fun installBridge(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val cache = java.io.File(toolchainBridgeCacheDir(), "gradle-tooling-bridge.jar")
                cache.parentFile?.mkdirs()
                appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取所选 JAR" }
                    cache.outputStream().use { output -> input.copyTo(output) }
                }
                GradleBridgeRuntimeManager(appContext).installFromFile(cache) { percent, msg ->
                    _message.value = "$percent% · $msg"
                }
                toolchain.refresh()
                _message.value = "Gradle Tooling API Bridge 已安装并通过真实 self-test"
            }.onFailure { _message.value = "Bridge 安装失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun toolchainBridgeCacheDir(): java.io.File = java.io.File(appContext.cacheDir, "bridge")

    fun installLsp(id: String) {
        val spec = toolchain.lspSpecs().firstOrNull { it.id == id } ?: return
        val taskId = toolchain.enqueueLspInstall(spec)
        _message.value = "已加入 LSP 安装任务：$taskId"
    }

    fun refreshLsp() {
        viewModelScope.launch { toolchain.refreshLspArtifacts() }
    }

    fun install(component: ToolchainComponent) {
        if (_busy.value != null) return
        // 先问任务中心是否已有同组件的任务：重复点击会被复用，提示必须跟着变，
        // 否则用户以为又排了一个新任务（任务中心里其实只有一条）。
        val reused = toolchain.taskCenter.hasActiveTask(component)
        val taskId = toolchain.enqueueInstall(component)
        _message.value = if (reused) "该工具已有安装任务在运行，已复用任务：$taskId" else "已加入工具链任务：$taskId"
    }

    fun cancelTask(id: String) {
        if (toolchain.taskCenter.cancel(id)) _message.value = "已请求取消任务 $id"
    }

    fun installBuildPrerequisites() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = ToolchainComponent.BOOTSTRAP
            _message.value = "正在按依赖顺序修复构建环境…"
            toolchain.installBuildPrerequisites()
            _busy.value = null
            _message.value = "构建前置工具链检查完成，请查看每项真实探测结果"
            _checkResults.value = checker.runAllChecks()
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext
                val nebula = app as com.nebulaforge.app.NebulaForgeApplication
                return SettingsViewModel(EnvironmentChecker(app), BootstrapInstaller(app), nebula.toolchainManager, app) as T
            }
        }
    }
}
