package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.flow.collect
import java.io.File

/** Final-B 真机构建前置检查：所有检查都执行真实命令，不以文件存在代替可用性。 */
data class ToolchainPreflight(
    val ready: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val commands: List<String>
)

class ToolchainDoctor(private val context: Context) {
    /**
     * 构建前置检查。
     *
     * @param statuses 已经探测好的工具链快照。全量探测会为**每个**组件起一次 guest 进程，
     *   真机上要几秒到几十秒；调用方（例如首次启动向导）若刚做过 `ToolchainManager.refresh()`，
     *   必须把结果传进来，否则同一次用户操作会白白重探一遍。
     *   传 null 时本方法自行探测，保持既有调用方式可用。
     */
    suspend fun preflight(
        projectDir: File? = null,
        statuses: List<ToolchainStatus>? = null
    ): ToolchainPreflight {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val statuses = statuses ?: ToolchainManager(context).refresh()
        fun requireReady(component: ToolchainComponent, label: String) {
            val status = statuses.firstOrNull { it.component == component }
            if (status?.state != ToolchainState.READY) blockers += "$label：${status?.detail ?: "未安装"}"
        }
        requireReady(ToolchainComponent.BOOTSTRAP, "内嵌运行时")
        requireReady(ToolchainComponent.JDK17, "JDK 17")
        requireReady(ToolchainComponent.ANDROID_CLI, "Android SDK CLI")
        requireReady(ToolchainComponent.PLATFORM_TOOLS, "ADB")
        requireReady(ToolchainComponent.BUILD_TOOLS, "Build Tools")
        if (projectDir != null) {
            val wrapper = File(projectDir, "gradlew")
            val wrapperBat = File(projectDir, "gradlew.bat")
            if (!wrapper.isFile && !wrapperBat.isFile && statuses.none { it.component == ToolchainComponent.GRADLE && it.state == ToolchainState.READY }) {
                blockers += "项目没有 Gradle Wrapper，且内嵌 Gradle 不可用"
            }
            if (wrapper.isFile) {
                if (!wrapper.canExecute()) warnings += "gradlew 没有执行权限，构建前将尝试修复权限"
                commands += "./gradlew assembleDebug"
            } else commands += "gradle assembleDebug"
        }
        val sdk = Environment.androidSdkRoot(context)
        if (!File(sdk, "platform-tools/adb").canExecute()) blockers += "ADB 不可执行：${File(sdk, "platform-tools/adb").absolutePath}"
        if (Environment.latestBuildToolsDir(context) == null) blockers += "没有可用 Build Tools 版本"
        return ToolchainPreflight(blockers.isEmpty(), blockers, warnings, commands)
    }

    /** 真机安装 APK；只允许调用 ADB，不通过 Android PackageInstaller 伪造成功状态。 */
    suspend fun installApk(apk: File, serial: String? = null): CommandResult {
        require(apk.isFile) { "APK 不存在：${apk.absolutePath}" }
        require(apk.length() > 0) { "APK 为空" }
        val prefix = serial?.let { "adb -s ${quote(it)}" } ?: "adb"
        return execute("$prefix install -r ${quote(apk.absolutePath)}", Environment.buildSdkEnv(context), 180)
    }

    suspend fun launchPackage(serial: String?, packageName: String, activity: String? = null): CommandResult {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "包名非法" }
        val prefix = serial?.let { "adb -s ${quote(it)}" } ?: "adb"
        val command = activity?.takeIf { it.isNotBlank() }?.let {
            "$prefix shell am start -W -n ${quote("$packageName/$it")}"
        } ?: "$prefix shell monkey -p ${quote(packageName)} 1"
        return execute(command, Environment.buildSdkEnv(context), 30)
    }

    data class CommandResult(val exitCode: Int, val output: String) { val ok get() = exitCode == 0 }

    private suspend fun execute(command: String, env: Map<String, String>, timeoutSeconds: Long): CommandResult {
        val lines = StringBuilder(); var code = -1
        kotlinx.coroutines.withTimeoutOrNull(timeoutSeconds * 1000L) {
            TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true).execute(command, Environment.ensureHome(context), env).collect {
                when (it) {
                    is TermuxCommandExecutor.Event.Line -> lines.append(it.text).append('\n')
                    is TermuxCommandExecutor.Event.Finished -> code = it.exitCode
                }
            }
        }
        if (code < 0) return CommandResult(-1, lines.toString().trimEnd() + "\ntimeout")
        return CommandResult(code, lines.toString().trimEnd())
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
