package com.nebulaforge.app.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainDoctor
import com.nebulaforge.core.toolchain.ToolchainManager
import com.nebulaforge.core.toolchain.ToolchainState
import com.nebulaforge.core.toolchain.ToolchainStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 「工具链全量检测」——把宿主支持的**全部**工具链组件列成一张可核对的完成清单。
 *
 * ## 为什么单独做这一层
 * 真机反馈：「一开始装了工具链，但检测界面里看不到」。原因有两层：
 *  1. 首次启动向导只有 5 个**粗粒度**步骤（外部 Termux / 外部命令权限 / JDK / SDK / 网络），
 *     根本没有工具链清单，装了 Go/Rust/Flutter/Apktool 也不会出现在任何地方；
 *  2. 判定与呈现分属两个模块：判定在 core-environment，清单数据在 core-toolchain。
 *     core-environment **不能**依赖 core-toolchain（core-toolchain 已经依赖 core-environment，
 *     否则成环），所以「列全 + 完成度」这一层必须落在同时依赖两者的 app 模块。
 *
 * ## 数据来源
 * [ToolchainManager.statuses] 是工具链状态的唯一真源（每项都是**真实执行探测**，
 * 不以「文件存在」冒充 READY），本文件只负责分组、统计与渲染，不重复实现探测逻辑。
 */

/** 清单分组。用于把 29 个组件按用途归类展示，而不是一团平铺的列表。 */
enum class ToolchainGroup(val title: String) {
    RUNTIME("运行时与基础环境"),
    JAVA_ANDROID("Java 与 Android 构建链"),
    NATIVE("C/C++ 与构建工具"),
    LANGUAGE("语言运行时与框架"),
    REVERSE("逆向工程"),
    LSP("语言服务 (LSP)")
}

/**
 * 组件 → 分组。
 *
 * 故意写成**穷尽的 `when` 表达式**：以后往 [ToolchainComponent] 里加组件时，
 * 这里会直接编译不过，从而强制「新组件必须出现在清单里」——
 * 这正是「检测界面要显示全部工具链」这条需求的编译期保险。
 */
fun ToolchainComponent.group(): ToolchainGroup = when (this) {
    ToolchainComponent.BOOTSTRAP,
    ToolchainComponent.GIT,
    ToolchainComponent.PYTHON,
    ToolchainComponent.NODE -> ToolchainGroup.RUNTIME

    ToolchainComponent.JDK17,
    ToolchainComponent.JDK21_LSP,
    ToolchainComponent.GRADLE,
    ToolchainComponent.ANDROID_CLI,
    ToolchainComponent.PLATFORM_TOOLS,
    ToolchainComponent.BUILD_TOOLS,
    ToolchainComponent.GRADLE_TOOLING_BRIDGE -> ToolchainGroup.JAVA_ANDROID

    ToolchainComponent.MAVEN,
    ToolchainComponent.CMAKE,
    ToolchainComponent.C_COMPILER,
    ToolchainComponent.NINJA -> ToolchainGroup.NATIVE

    ToolchainComponent.GO,
    ToolchainComponent.RUST,
    ToolchainComponent.PHP,
    ToolchainComponent.COMPOSER,
    ToolchainComponent.LUA,
    ToolchainComponent.DART,
    ToolchainComponent.FLUTTER,
    ToolchainComponent.TYPESCRIPT -> ToolchainGroup.LANGUAGE

    ToolchainComponent.APKTOOL,
    ToolchainComponent.JADX -> ToolchainGroup.REVERSE

    ToolchainComponent.LSP_GOPLS,
    ToolchainComponent.LSP_RUST_ANALYZER,
    ToolchainComponent.LSP_LUA,
    ToolchainComponent.LSP_TYPESCRIPT -> ToolchainGroup.LSP
}

/**
 * 完成度统计（纯数据，便于单测与复用）。
 *
 * [essentialTotal] 只统计「Android 项目真的构建不动就报错」的那几项，
 * 因为把 Go/Rust/Flutter 也算进完成度会让只做 Android 工程的用户永远看到「未完成」。
 */
data class ToolchainProgress(
    val readyCount: Int,
    val total: Int,
    val essentialReady: Int,
    val essentialTotal: Int
) {
    val percent: Int get() = if (total == 0) 0 else readyCount * 100 / total
    val allEssentialReady: Boolean get() = essentialReady == essentialTotal

    companion object {
        /** 构建 Android 项目的最小必需集合（与 [ToolchainDoctor.preflight] 的判定口径一致）。 */
        val ESSENTIAL: List<ToolchainComponent> = listOf(
            ToolchainComponent.BOOTSTRAP,
            ToolchainComponent.JDK17,
            ToolchainComponent.ANDROID_CLI,
            ToolchainComponent.PLATFORM_TOOLS,
            ToolchainComponent.BUILD_TOOLS
        )

        fun of(statuses: List<ToolchainStatus>): ToolchainProgress {
            val ready = statuses.filter { it.state == ToolchainState.READY }.map { it.component }.toSet()
            return ToolchainProgress(
                readyCount = ready.size,
                total = ToolchainComponent.entries.size,
                essentialReady = ESSENTIAL.count { it in ready },
                essentialTotal = ESSENTIAL.size
            )
        }
    }
}

/**
 * 工具链全量检测器（app 层）。
 *
 * 只做三件事：全量探测、构建前置判定、按依赖顺序修复；探测本身完全复用 [ToolchainManager]。
 */
class ToolchainInventoryChecker(context: Context) {
    private val appContext = context.applicationContext
    private val manager = ToolchainManager(appContext)

    /** 工具链状态唯一真源（真实执行探测）。 */
    val statuses: StateFlow<List<ToolchainStatus>> = manager.statuses

    /** 全量重探。为每个组件起一次 guest 进程，真机上需要几秒到几十秒，务必在 IO 协程里调用。 */
    suspend fun refresh(): List<ToolchainStatus> = manager.refresh()

    /**
     * 构建前置判定。
     *
     * 传入刚探测好的 [snapshot]，避免 [ToolchainDoctor] 内部再全量探一遍。
     */
    suspend fun preflight(snapshot: List<ToolchainStatus>) =
        ToolchainDoctor(appContext).preflight(projectDir = null, statuses = snapshot)

    /** 一键修复：按依赖顺序安装缺失的构建前置组件。 */
    suspend fun installPrerequisites(): List<ToolchainStatus> = manager.installBuildPrerequisites()

    /** 安装单个组件（Gradle Tooling Bridge 需要用户选 JAR，不在此列）。 */
    suspend fun install(component: ToolchainComponent): ToolchainStatus = manager.install(component)
}

/**
 * 工具链完成清单卡片：完成度汇总 + 按分组的**全部**组件状态。
 *
 * 组件数量固定来自 [ToolchainComponent.entries]，不依赖任何"已安装列表"——
 * 因此「装了但没显示」在结构上不可能再发生。
 */
@Composable
fun ToolchainInventoryCard(
    statuses: List<ToolchainStatus>,
    refreshing: Boolean,
    installing: ToolchainComponent?,
    fixMessage: String?,
    onRefresh: () -> Unit,
    onInstall: (ToolchainComponent) -> Unit,
    onInstallPrerequisites: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    val byComponent = statuses.associateBy { it.component }
    val progress = ToolchainProgress.of(statuses)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("全部工具链检测", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "共 ${progress.total} 个组件，已就绪 ${progress.readyCount} 个（完成度 ${progress.percent}%）；" +
                            "构建必需 ${progress.essentialReady}/${progress.essentialTotal}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (refreshing) "探测中…" else "全部重探")
                }
            }

            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )

            if (refreshing) {
                Text(
                    "正在对每个组件执行真实探测（会逐个起一次内嵌用户态进程），请稍候…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (!progress.allEssentialReady || !fixMessage.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (progress.allEssentialReady) {
                        "构建必需的 5 项已全部就绪，Android 工程可以直接构建。"
                    } else {
                        "构建必需组件尚未齐全（缺 ${progress.essentialTotal - progress.essentialReady} 项）。可一键按依赖顺序安装：内嵌运行时 → JDK 17 → Git → Gradle → ADB → Build Tools。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.allEssentialReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            Button(
                onClick = onInstallPrerequisites,
                enabled = installing == null,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text(
                    if (installing == ToolchainComponent.BOOTSTRAP) "正在修复构建前置…" else "一键安装缺失的构建前置"
                )
            }

            fixMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            onOpenSettings?.let { open ->
                TextButton(onClick = open, modifier = Modifier.padding(top = 2.dp)) {
                    Text("打开设置页管理全部工具链")
                }
            }

            // ---- 全量清单：按分组列出每一个组件 ----
            ToolchainGroup.entries.forEach { group ->
                val members = ToolchainComponent.entries.filter { it.group() == group }
                if (members.isEmpty()) return@forEach
                val groupReady = members.count { byComponent[it]?.state == ToolchainState.READY }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${group.title}（$groupReady/${members.size}）",
                    style = MaterialTheme.typography.titleSmall
                )
                members.forEach { component ->
                    ToolchainRow(
                        component = component,
                        status = byComponent[component],
                        installing = installing == component,
                        onInstall = onInstall
                    )
                }
            }
        }
    }
}

/** 清单里的一行：组件名 + 状态 + 版本/原因 + 缺失时的安装入口。 */
@Composable
private fun ToolchainRow(
    component: ToolchainComponent,
    status: ToolchainStatus?,
    installing: Boolean,
    onInstall: (ToolchainComponent) -> Unit
) {
    val state = status?.state ?: ToolchainState.MISSING
    val ready = state == ToolchainState.READY
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(component.title, style = MaterialTheme.typography.bodyMedium)
            val stateLabel = when (state) {
                ToolchainState.MISSING -> "未安装"
                ToolchainState.INSTALLING -> "安装中…"
                ToolchainState.READY -> "已就绪"
                ToolchainState.FAILED -> "已安装但不可用"
                ToolchainState.BLOCKED -> "被阻塞"
            }
            val secondary = status?.version?.takeIf { it.isNotBlank() } ?: stateLabel
            val reason = status?.detail?.takeIf { !ready && it.isNotBlank() }
            Text(
                if (reason != null) "$secondary · $reason" else secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (ready) {
                status?.path?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Gradle Tooling Bridge 需要用户提供 JAR（SHA-256 + self-test），不能一键装。
        if (!ready && component != ToolchainComponent.GRADLE_TOOLING_BRIDGE) {
            TextButton(onClick = { onInstall(component) }, enabled = !installing) {
                Text(if (installing) "安装中" else "安装")
            }
        }
    }
}
