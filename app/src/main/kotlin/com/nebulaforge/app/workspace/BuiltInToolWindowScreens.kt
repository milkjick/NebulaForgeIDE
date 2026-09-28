package com.nebulaforge.app.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import com.nebulaforge.core.session.SessionKind
import com.nebulaforge.core.session.SessionState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun StructureToolWindow(path: String?) {
    val file = path?.let(::File)
    val lines = remember(file?.absolutePath, file?.lastModified()) {
        file?.takeIf { it.isFile }?.useLines { seq -> seq.take(300).mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("class ") || t.startsWith("interface ") || t.startsWith("object ") || t.startsWith("fun ") || t.startsWith("data class ")) "${i + 1}: $t" else null
        }.toList() }.orEmpty()
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 600.dp).padding(12.dp)) {
        Text("结构", style = MaterialTheme.typography.titleMedium)
        Text(file?.name ?: "未选择文件", style = MaterialTheme.typography.labelSmall)
        LazyColumn { items(lines) { Text(it, Modifier.padding(vertical = 4.dp)) } }
    }
}

@Composable
fun LogcatToolWindow() {
    val context = LocalContext.current.applicationContext as NebulaForgeApplication
    val items by context.sessionStateProjection.items.collectAsState()
    val logs = items.filter { it.kind == SessionKind.LOGCAT }
    Column(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 620.dp).padding(12.dp)) {
        Text("Logcat", style = MaterialTheme.typography.titleMedium)
        if (logs.isEmpty()) Text("暂无 Logcat 会话。", Modifier.padding(top = 12.dp))
        logs.forEach { s ->
            Text("${stateLabel(s.state)}  ${s.device.orEmpty()}", style = MaterialTheme.typography.labelSmall)
            Text(s.lastOutput.orEmpty(), maxLines = 6, modifier = Modifier.padding(vertical = 4.dp))
            HorizontalDivider()
        }
    }
}

@Composable
fun NotificationsToolWindow() {
    val context = LocalContext.current.applicationContext as NebulaForgeApplication
    val items by context.sessionStateProjection.items.collectAsState()
    val failures = items.filter { it.state is SessionState.Failed }.take(20)
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 500.dp).padding(12.dp)) {
        Text("通知", style = MaterialTheme.typography.titleMedium)
        if (failures.isEmpty()) Text("暂无失败会话。", Modifier.padding(top = 12.dp))
        failures.forEach { s -> ListItem(headlineContent = { Text("${s.kind.name} 失败") }, supportingContent = { Text(stateLabel(s.state)) }) }
    }
}

@Composable
fun GradleToolWindow() {
    val context = LocalContext.current.applicationContext
    val root = Environment.homeRoot(context)
    val gradle = File(root, ".gradle")
    val wrapper = File(root, "gradle/wrapper/gradle-wrapper.properties")
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 500.dp).padding(12.dp)) {
        Text("Gradle", style = MaterialTheme.typography.titleMedium)
        Text("Gradle 用户目录：${gradle.absolutePath}", style = MaterialTheme.typography.bodySmall)
        Text("Wrapper 配置：${if (wrapper.isFile) "存在" else "未找到"}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun VersionControlToolWindow(project: File?) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var output by remember(project?.absolutePath) { mutableStateOf("未检测") }
    LaunchedEffect(project?.absolutePath) {
        val p = project ?: return@LaunchedEffect
        if (!File(p, ".git").exists()) { output = "当前项目不是 Git 仓库"; return@LaunchedEffect }
        output = runCatching {
            val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
            val lines = mutableListOf<String>()
            var exit = 0
            kotlinx.coroutines.runBlocking {
                executor.execute("git status --short --branch", p, Environment.buildTerminalEnv(context)).collect { event ->
                    when (event) {
                        is TermuxCommandExecutor.Event.Line -> lines += event.text
                        is TermuxCommandExecutor.Event.Finished -> exit = event.exitCode
                    }
                }
            }
            if (exit == 0) lines.joinToString("\n").ifBlank { "工作区干净" } else lines.joinToString("\n").ifBlank { "Git 退出码：$exit" }
        }.getOrElse { "Git 检测失败：${it.message}" }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp).padding(12.dp)) {
        Text("版本控制", style = MaterialTheme.typography.titleMedium)
        Text(output, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun McpLogToolWindow() {
    val context = LocalContext.current.applicationContext as NebulaForgeApplication
    val items by context.sessionStateProjection.items.collectAsState()
    val mcp = items.filter { it.kind == SessionKind.TOOLCHAIN }
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 500.dp).padding(12.dp)) {
        Text("MCP 日志", style = MaterialTheme.typography.titleMedium)
        Text(if (mcp.isEmpty()) "暂无 MCP 会话事件。" else mcp.joinToString("\n") { it.lastOutput.orEmpty() })
    }
}

private fun stateLabel(state: SessionState) = when (state) {
    SessionState.Idle -> "空闲"
    is SessionState.Preparing -> "准备中：${state.message}"
    is SessionState.Running -> "运行中：${state.message}"
    is SessionState.Succeeded -> "成功：${state.message}"
    is SessionState.Failed -> "失败：${state.message}"
    SessionState.Cancelled -> "已取消"
}
