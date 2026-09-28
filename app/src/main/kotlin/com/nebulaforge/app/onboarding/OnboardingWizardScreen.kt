package com.nebulaforge.app.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebulaforge.app.R
import com.nebulaforge.app.common.WizardBottomBar
import com.nebulaforge.app.common.WizardStepIndicator
import com.nebulaforge.core.environment.BootstrapInstaller
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.OnboardingCheckResult
import com.nebulaforge.core.environment.OnboardingChecker
import com.nebulaforge.core.environment.OnboardingStep
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次启动环境自检向导（开发方案第 1.7 节）。
 *
 * 布局遵循「5.3 全屏路由页 + 顶部步骤指示器」硬约束：不使用弹窗式向导。
 *
 * ## 判定逻辑的位置
 *  - [OnboardingStep.EMBEDDED_RUNTIME] / [OnboardingStep.JDK17] / [OnboardingStep.ANDROID_SDK] /
 *    [OnboardingStep.NETWORK_MIRROR]：判定在 core 的 [OnboardingChecker] 中，本文件只呈现。
 *  - [OnboardingStep.BUILD_TOOLCHAIN]：**全部工具链清单 + 完成度**落在 app 层的
 *    [ToolchainInventoryChecker]（core-environment 不能依赖 core-toolchain，否则成环）。
 *
 * ## 与外部 Termux 的关系
 * 本 IDE 使用 App 私有存储下的内嵌用户态，**不再需要外部 Termux 应用**，
 * 因此向导里不存在「检测/安装 Termux」这一步；第 1 步检测的是内嵌运行时本体，
 * 失败时直接给「安装内嵌运行时」按钮（不再出现「请去 F-Droid 装 Termux」这种无效引导）。
 */
private val Steps: List<OnboardingStep> = OnboardingStep.entries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingWizardScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val checker = remember { OnboardingChecker(appContext) }
    val inventory = remember { ToolchainInventoryChecker(appContext) }
    val bootstrapInstaller = remember { BootstrapInstaller(appContext) }
    val scope = rememberCoroutineScope()

    var stepIndex by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<OnboardingCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var rerunToken by remember { mutableStateOf(0) }
    var fixLog by remember { mutableStateOf<String?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }

    /** 内嵌运行时安装进度（第 1 步的「安装内嵌运行时」按钮驱动），直接复用 SettingsViewModel 的同一安装器。 */
    var bootstrapProgress by remember { mutableStateOf<BootstrapInstaller.InstallProgress?>(null) }
    /** 工具链清单的实时状态：唯一真源是 ToolchainManager.statuses，探测/安装后自动刷新。 */
    val inventoryStatuses by inventory.statuses.collectAsStateWithLifecycle()
    var inventoryRefreshing by remember { mutableStateOf(false) }
    var inventoryInstalling by remember { mutableStateOf<ToolchainComponent?>(null) }
    var inventoryMessage by remember { mutableStateOf<String?>(null) }

    val currentStep = Steps[stepIndex]

    /** 全量重探所有工具链组件（逐个推送状态，清单会逐行亮起）。 */
    suspend fun refreshInventory(): List<ToolchainStatus> {
        inventoryRefreshing = true
        return try {
            inventory.refresh()
        } catch (t: Throwable) {
            emptyList<ToolchainStatus>().also {
                inventoryMessage = "工具链探测失败：${t.message ?: t.javaClass.simpleName}"
            }
        } finally {
            inventoryRefreshing = false
        }
    }

    /** 构建工具链步骤的判定：全量清单 + 构建前置阻塞项。 */
    suspend fun checkBuildToolchain(): OnboardingCheckResult {
        val snapshot = refreshInventory()
        val progress = ToolchainProgress.of(snapshot)
        val preflight = runCatching { inventory.preflight(snapshot) }.getOrNull()
        val blockers = preflight?.blockers.orEmpty()
        val summary = "共 ${progress.total} 个工具链组件，已就绪 ${progress.readyCount} 个（完成度 ${progress.percent}%）" +
            "；构建必需 ${progress.essentialReady}/${progress.essentialTotal}"
        return if (blockers.isEmpty()) {
            OnboardingCheckResult(OnboardingStep.BUILD_TOOLCHAIN, true, summary, "")
        } else {
            OnboardingCheckResult(
                OnboardingStep.BUILD_TOOLCHAIN, false,
                "$summary。阻塞项：${blockers.joinToString("；")}",
                "可点下方「一键安装缺失的构建前置」，或对清单里任意缺失组件点「安装」。安装顺序已按依赖关系固定：内嵌运行时 → JDK 17 → Git → Gradle → ADB → Build Tools。"
            )
        }
    }

    // 进入某一步（或点"重新检查"）即执行该步检查；切步时清空上一步的修复日志
    LaunchedEffect(stepIndex, rerunToken) {
        checking = true
        fixLog = null
        skipped = false
        result = null
        result = runStepCheck(checker, Steps[stepIndex], ::checkBuildToolchain)
        checking = false
    }

    val isLast = stepIndex == Steps.lastIndex

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_wizard_title)) },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                }
            )
        },
        bottomBar = {
            WizardBottomBar(
                nextLabel = if (isLast) {
                    stringResource(R.string.onboarding_wizard_finish)
                } else if (result?.ok == false && !skipped) {
                    stringResource(R.string.onboarding_wizard_skip)
                } else {
                    stringResource(R.string.onboarding_wizard_next)
                },
                onNext = {
                    when {
                        isLast -> onFinished()
                        result?.ok == false && !skipped -> {
                            // 允许暂时跳过，但明确告知用户当前未通过，避免"静默通过"
                            skipped = true
                            stepIndex += 1
                        }
                        else -> stepIndex += 1
                    }
                },
                backLabel = if (stepIndex > 0) stringResource(R.string.onboarding_wizard_back) else null,
                onBack = if (stepIndex > 0) {
                    { stepIndex -= 1 }
                } else null,
                leading = {
                    TextButton(onClick = { rerunToken += 1 }, enabled = !checking && !fixing) {
                        Text(stringResource(R.string.onboarding_wizard_recheck))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.onboarding_wizard_step_of, stepIndex + 1, Steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentStep.titleZh,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            WizardStepIndicator(currentStep = stepIndex, totalSteps = Steps.size)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.onboarding_wizard_checking))
                        } else {
                            val ok = result?.ok == true
                            Icon(
                                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (ok) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = if (ok) {
                                    stringResource(R.string.onboarding_wizard_passed)
                                } else {
                                    stringResource(R.string.onboarding_wizard_failed)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (ok) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }

                    result?.let { r ->
                        Spacer(Modifier.height(12.dp))
                        Text(r.detail, style = MaterialTheme.typography.bodyMedium)
                        if (r.guidance.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                r.guidance,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        // 跨模块属性无法智能转换，先取局部变量
                        val autoFixCommand = r.autoFixCommand
                        val autoFixLabel = r.autoFixLabel
                        // 修复入口的显示条件只看「这一步没过」，不再要求 autoFixCommand != null：
                        // 旧实现把「安装内嵌运行时」这种没有 shell 命令的修复动作直接过滤掉，
                        // 用户在向导里**根本看不到安装按钮**（真机反馈「一键安装内嵌运行时无效/不存在」）。
                        val hasFix = r.autoFixIsBootstrapInstall || autoFixCommand != null
                        if (!r.ok && hasFix) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    fixing = true
                                    fixLog = null
                                    bootstrapProgress = null
                                    scope.launch {
                                        if (r.autoFixIsBootstrapInstall) {
                                            // 内嵌运行时：走真正的安装器（解压 bootstrap / 补权限 / 重建符号链接），
                                            // 而不是把一条 shell 命令丢给一个尚不存在的用户态。
                                            var failure: String? = null
                                            bootstrapInstaller.install().collect { progress ->
                                                bootstrapProgress = progress
                                                if (progress is BootstrapInstaller.InstallProgress.Failed) {
                                                    failure = progress.reason
                                                }
                                            }
                                            fixing = false
                                            fixLog = if (failure == null) {
                                                context.getString(R.string.onboarding_wizard_fix_ok)
                                            } else {
                                                "内嵌运行时安装失败：$failure"
                                            }
                                            // 安装完成后立刻重跑本步检查，让用户看到真实结果（不靠"成功文案"自证）
                                            rerunToken += 1
                                        } else {
                                            val (exit, output) = runShellCommand(appContext, autoFixCommand!!)
                                            fixing = false
                                            fixLog = if (exit == 0) {
                                                context.getString(R.string.onboarding_wizard_fix_ok) +
                                                    if (output.isNotBlank()) "\n$output" else ""
                                            } else {
                                                context.getString(R.string.onboarding_wizard_fix_fail, exit) +
                                                    if (output.isNotBlank()) "\n$output" else ""
                                            }
                                        }
                                    }
                                },
                                enabled = !fixing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (fixing) {
                                        stringResource(R.string.onboarding_wizard_fixing)
                                    } else {
                                        autoFixLabel ?: stringResource(R.string.onboarding_wizard_run_fix)
                                    }
                                )
                            }
                            if (autoFixLabel != null && autoFixLabel != r.autoFixLabel) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    autoFixLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 内嵌运行时安装进度（bootstrap 解压是几十秒级操作，必须有可见进度）
                    bootstrapProgress?.let { p ->
                        Spacer(Modifier.height(12.dp))
                        when (p) {
                            is BootstrapInstaller.InstallProgress.Failed -> Text(
                                "安装失败：${p.reason}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            else -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    bootstrapProgressText(p),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ---- 全部工具链清单（仅构建工具链步骤）----
            if (currentStep == OnboardingStep.BUILD_TOOLCHAIN) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ToolchainInventoryCard(
                        statuses = inventoryStatuses,
                        refreshing = inventoryRefreshing,
                        installing = inventoryInstalling,
                        fixMessage = inventoryMessage,
                        onRefresh = {
                            scope.launch {
                                val snapshot = refreshInventory()
                                val progress = ToolchainProgress.of(snapshot)
                                result = OnboardingCheckResult(
                                    OnboardingStep.BUILD_TOOLCHAIN,
                                    progress.allEssentialReady,
                                    "共 ${progress.total} 个工具链组件，已就绪 ${progress.readyCount} 个（完成度 ${progress.percent}%）；构建必需 ${progress.essentialReady}/${progress.essentialTotal}",
                                    ""
                                )
                            }
                        },
                        onInstall = { component ->
                            if (inventoryInstalling == null) {
                                scope.launch {
                                    inventoryInstalling = component
                                    inventoryMessage = "正在安装 ${component.title}…"
                                    runCatching { inventory.install(component) }
                                        .onSuccess { inventoryMessage = "${component.title}：${it.state}" }
                                        .onFailure { inventoryMessage = "${component.title} 安装失败：${it.message ?: it.javaClass.simpleName}" }
                                    inventoryInstalling = null
                                    refreshInventory()
                                }
                            }
                        },
                        onInstallPrerequisites = {
                            if (inventoryInstalling == null) {
                                scope.launch {
                                    inventoryInstalling = ToolchainComponent.BOOTSTRAP
                                    inventoryMessage = "正在按依赖顺序修复构建环境（内嵌运行时 → JDK 17 → Git → Gradle → ADB → Build Tools）…"
                                    runCatching { inventory.installPrerequisites() }
                                        .onSuccess { inventoryMessage = "构建前置修复完成，已重新探测全部组件" }
                                        .onFailure { inventoryMessage = "修复中断：${it.message ?: it.javaClass.simpleName}" }
                                    inventoryInstalling = null
                                    refreshInventory()
                                }
                            }
                        }
                    )
                }
            }

            fixLog?.let { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        log,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (skipped) {
                Text(
                    stringResource(R.string.onboarding_wizard_skipped_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                stringResource(R.string.onboarding_wizard_close_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 内嵌运行时安装器上报的阶段 → 用户可读文案。 */
private fun bootstrapProgressText(p: BootstrapInstaller.InstallProgress): String = when (p) {
    is BootstrapInstaller.InstallProgress.Downloading -> if (p.totalBytes > 0) {
        val mb = { v: Long -> "%.1f".format(v / 1048576.0) }
        "正在释放内嵌用户态 ${mb(p.bytesDownloaded)}/${mb(p.totalBytes)}MB（${(p.bytesDownloaded * 100 / p.totalBytes).coerceIn(0, 100)}%）"
    } else {
        "正在准备内嵌用户态…"
    }
    is BootstrapInstaller.InstallProgress.Extracting -> "正在解压用户态：${p.currentEntry}"
    BootstrapInstaller.InstallProgress.SettingPermissions -> "正在补齐可执行权限…"
    BootstrapInstaller.InstallProgress.LinkingSymlinks -> "正在重建符号链接…"
    is BootstrapInstaller.InstallProgress.Verifying -> p.step
    BootstrapInstaller.InstallProgress.Completed -> "内嵌运行环境就绪，可直接使用终端与构建工具链"
    is BootstrapInstaller.InstallProgress.Failed -> "失败：${p.reason}"
}

/** 按步骤分发到具体检查实现；[buildToolchain] 由 app 层注入（依赖 core-toolchain）。 */
private suspend fun runStepCheck(
    checker: OnboardingChecker,
    step: OnboardingStep,
    buildToolchain: suspend () -> OnboardingCheckResult
): OnboardingCheckResult = when (step) {
    OnboardingStep.EMBEDDED_RUNTIME -> checker.checkEmbeddedRuntime()
    OnboardingStep.JDK17 -> checker.checkJdk17()
    OnboardingStep.BUILD_TOOLCHAIN -> buildToolchain()
    OnboardingStep.ANDROID_SDK -> checker.checkAndroidSdk()
    OnboardingStep.NETWORK_MIRROR -> checker.checkNetworkAndMirror()
}

/**
 * 在内嵌运行时中执行一键修复命令，返回 (exitCode, 合并输出)。
 * 与向导的"内嵌运行时"检查使用同一 shell 与同一套环境变量，保证修复结果可被下一步检查复现。
 */
private suspend fun runShellCommand(context: Context, command: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        // 自动修复命令（如 pkg install -y openjdk-17）依赖 Termux 的前缀语义，
        // 必须在 proot 对齐的 guest 里执行；否则会因前缀错位 / apt 拒绝 root 而失败。
        val guestReady = runCatching {
            TermuxGuest.ensureSetup(context)
            TermuxGuest.isReady(context)
        }.getOrDefault(false)

        val executable: String
        val args: List<String>
        val env: Map<String, String>
        if (guestReady) {
            TermuxGuest.ensureMirror(context)
            executable = TermuxGuest.prootBinary(context).absolutePath
            args = TermuxGuest.prootArgs(context, command)
            env = TermuxGuest.guestEnv(context)
        } else {
            val shell = File(Environment.binDir(context), "sh")
            if (!shell.isFile) return@withContext -1 to "内嵌运行时不可用：找不到 ${shell.absolutePath}"
            executable = shell.absolutePath
            args = listOf("-lc", command)
            val base = Environment.buildTerminalEnv(context).toMutableMap()
            base["PATH"] = "${Environment.binDir(context)}:${base["PATH"].orEmpty()}"
            env = base
        }

        val output = StringBuilder()
        var exit = -1
        try {
            val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            TermuxCommandExecutor(executable)
                .execute(quoted, Environment.ensureHome(context), env)
                .collect { event ->
                    when (event) {
                        is TermuxCommandExecutor.Event.Line ->
                            if (output.length < 4000) output.appendLine(event.text)
                        is TermuxCommandExecutor.Event.Finished -> exit = event.exitCode
                    }
                }
        } catch (t: Throwable) {
            return@withContext exit to "执行异常：${t.message ?: t.javaClass.simpleName}"
        }
        exit to output.toString().trim()
    }
