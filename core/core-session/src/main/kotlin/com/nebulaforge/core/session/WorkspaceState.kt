package com.nebulaforge.core.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 输出字号（px）可调范围：终端与构建面板共用同一份设置。 */
const val MIN_TERMINAL_FONT = 9
const val MAX_TERMINAL_FONT = 28

/**
 * Persistent IDE workspace state. It stores editor/workspace metadata, never source contents.
 * This is deliberately separate from process/session resurrection: dead processes are not revived.
 */
data class OpenDocumentState(
    val path: String,
    val dirty: Boolean,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0
)

data class WorkspaceDiagnostic(
    val sessionId: String,
    val file: String?,
    val line: Int?,
    val column: Int?,
    val message: String,
    val severity: Severity,
    val timeMs: Long
)

data class WorkspaceState(
    val projectPath: String? = null,
    val activeFile: String? = null,
    val openDocuments: List<OpenDocumentState> = emptyList(),
    val lastBuildSessionId: String? = null,
    val lastRunSessionId: String? = null,
    val lastArtifact: String? = null,
    val diagnostics: List<WorkspaceDiagnostic> = emptyList(),
    val selectedToolWindow: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    // 项目树分栏布局：宽度（dp）、字号缩放、是否展开。与设备屏幕相关，随工作区状态一起持久化。
    val treeWidthDp: Int = 260,
    val treeFontScale: Float = 1.0f,
    val treeVisible: Boolean = true,
    // 底部工具窗口（终端/构建/问题/运行/Logcat…）：是否展开 + 面板高度（dp）。
    // 与项目树布局同理，随工作区持久化，重启后保持上次的 IDE 形态。
    val bottomPanelVisible: Boolean = false,
    val bottomPanelHeightDp: Int = 240,
    // 终端 / 构建输出的字号（px，Termux TerminalView 的 setTextSize 单位）。
    // 之前字号只存在于「终端」页与「构建」页各自的 remember 里：调了不持久化、两个面板还会互相打架，
    // 而构建面板工具条在窄屏上又会把 −/+ 挤出屏幕，用户根本没有可用的调整入口。
    // 现在是随工作区持久化的一份共享设置，两个面板共用、所有输出标签同步生效。
    val terminalFontSize: Int = 13,
    /**
     * 构建面板「选中的任务」按工程记忆：key = 工程根目录，value = taskId。
     *
     * 这个选择以前只存在于 BuildCenterScreen 的 `remember(root)` 里：切换文件引起面板重建、
     * 或切到别的工程再切回来，选择就丢了，面板退回「第一个 build 任务」——
     * 用户看到的现象就是「无法自由选择要编译什么」（刚选的「assembleRelease 全量包」一刷新就没了）。
     */
    val buildTaskByProject: Map<String, String> = emptyMap()
) {
    val dirtyDocumentCount: Int get() = openDocuments.count { it.dirty }
}

class WorkspaceStateStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "workspace/state.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    @Synchronized fun selectProject(path: String?) = update { it.copy(projectPath = path, updatedAt = now()) }

    @Synchronized fun openDocument(path: String, dirty: Boolean = false, line: Int = 0, column: Int = 0) = update { s ->
        val normalized = File(path).absolutePath
        val old = s.openDocuments.firstOrNull { it.path == normalized }
        val docs = s.openDocuments.filterNot { it.path == normalized } + OpenDocumentState(normalized, dirty, line, column)
        s.copy(activeFile = normalized, openDocuments = docs.takeLast(64), updatedAt = now())
    }

    @Synchronized fun markDirty(path: String, dirty: Boolean = true) = update { s ->
        val normalized = File(path).absolutePath
        val docs = if (s.openDocuments.any { it.path == normalized }) {
            s.openDocuments.map { if (it.path == normalized) it.copy(dirty = dirty) else it }
        } else s.openDocuments + OpenDocumentState(normalized, dirty)
        s.copy(activeFile = normalized, openDocuments = docs.takeLast(64), updatedAt = now())
    }

    @Synchronized fun updateCursor(path: String, line: Int, column: Int) = update { s ->
        s.copy(openDocuments = s.openDocuments.map { if (it.path == File(path).absolutePath) it.copy(cursorLine = line, cursorColumn = column) else it }, updatedAt = now())
    }

    @Synchronized fun closeDocument(path: String) = update { s ->
        val normalized = File(path).absolutePath
        val docs = s.openDocuments.filterNot { it.path == normalized }
        s.copy(activeFile = if (s.activeFile == normalized) docs.lastOrNull()?.path else s.activeFile, openDocuments = docs, updatedAt = now())
    }

    /**
     * 把某个**已打开**的文档设为活动文件（用户切换编辑器标签）。
     *
     * 为什么需要单独一个方法：`activeFile` 是「构建目标跟随当前文件」的唯一依据
     * （BuildCenterScreen → TasksJson.load(root, activeFile)）。而切换标签以前只改了
     * WorkspaceScreen 的本地 `activePath`，状态里的 `activeFile` 仍停在上一个文件 ——
     * 真机现象就是「我明明选中的是 greeter.py，编译的却是 main.py」。
     *
     * 与 [markDirty]/[openDocument] 的区别：**不动 dirty 标记**。用 openDocument 切标签会把
     * 未保存文档的 dirty 置回 false，重启后「未保存」提示就丢了。
     */
    @Synchronized fun selectDocument(path: String) = update { s ->
        val normalized = File(path).absolutePath
        val docs = if (s.openDocuments.any { it.path == normalized }) s.openDocuments
        else s.openDocuments + OpenDocumentState(normalized, dirty = false)
        s.copy(activeFile = normalized, openDocuments = docs.takeLast(64), updatedAt = now())
    }

    @Synchronized fun selectToolWindow(id: String?) = update { it.copy(selectedToolWindow = id, updatedAt = now()) }

    @Synchronized fun recordBuild(sessionId: String) = update { it.copy(lastBuildSessionId = sessionId, updatedAt = now()) }
    @Synchronized fun recordRun(sessionId: String) = update { it.copy(lastRunSessionId = sessionId, updatedAt = now()) }
    @Synchronized fun recordArtifact(path: String) = update { it.copy(lastArtifact = path, updatedAt = now()) }

    @Synchronized fun replaceDiagnostics(sessionId: String, diagnostics: List<IdeEvent.Diagnostic>) = update { s ->
        val remaining = s.diagnostics.filterNot { it.sessionId == sessionId }
        val added = diagnostics.map { WorkspaceDiagnostic(it.sessionId, it.file, it.line, it.column, it.message, it.severity, it.timeMs) }
        s.copy(diagnostics = (remaining + added).takeLast(300), updatedAt = now())
    }

    @Synchronized fun addDiagnostic(event: IdeEvent.Diagnostic) = update { s ->
        s.copy(diagnostics = (s.diagnostics + WorkspaceDiagnostic(event.sessionId, event.file, event.line, event.column, event.message, event.severity, event.timeMs)).takeLast(300), updatedAt = now())
    }

    @Synchronized fun clearDiagnostics(sessionId: String? = null) = update { s ->
        s.copy(diagnostics = if (sessionId == null) emptyList() else s.diagnostics.filterNot { it.sessionId == sessionId }, updatedAt = now())
    }

    /** 项目树分栏布局：宽度（dp）/ 字号缩放 / 展开状态，均可单独更新。 */
    @Synchronized fun updateTreeLayout(widthDp: Int? = null, fontScale: Float? = null, visible: Boolean? = null) = update { s ->
        s.copy(
            treeWidthDp = widthDp?.coerceIn(160, 560) ?: s.treeWidthDp,
            treeFontScale = fontScale?.coerceIn(0.7f, 1.8f) ?: s.treeFontScale,
            treeVisible = visible ?: s.treeVisible,
            updatedAt = now()
        )
    }

    /** 底部工具窗口布局：展开状态 / 面板高度（dp）。 */
    @Synchronized fun updateBottomPanel(visible: Boolean? = null, heightDp: Int? = null) = update { s ->
        s.copy(
            bottomPanelVisible = visible ?: s.bottomPanelVisible,
            bottomPanelHeightDp = heightDp?.coerceIn(120, 720) ?: s.bottomPanelHeightDp,
            updatedAt = now()
        )
    }

    /** 输出字号（px）：终端与构建面板共用；两个面板都可改，改动随工作区持久化。 */
    @Synchronized fun updateTerminalFont(size: Int? = null) = update { s ->
        s.copy(
            terminalFontSize = size?.coerceIn(MIN_TERMINAL_FONT, MAX_TERMINAL_FONT) ?: s.terminalFontSize,
            updatedAt = now()
        )
    }

    /** 记住某工程构建面板选中的任务；taskId 传 null 表示清除该工程的记忆。 */
    @Synchronized fun updateBuildTask(projectRoot: String, taskId: String?) = update { s ->
        val next = s.buildTaskByProject.filterKeys { it != projectRoot } +
            (if (taskId.isNullOrBlank()) emptyMap() else mapOf(projectRoot to taskId))
        s.copy(buildTaskByProject = next, updatedAt = now())
    }

    private fun update(transform: (WorkspaceState) -> WorkspaceState) {
        _state.value = transform(_state.value)
        persist(_state.value)
    }

    private fun now() = System.currentTimeMillis()

    private fun persist(s: WorkspaceState) = runCatching {
        file.parentFile?.mkdirs()
        val o = JSONObject().apply {
            put("projectPath", s.projectPath ?: JSONObject.NULL)
            put("activeFile", s.activeFile ?: JSONObject.NULL)
            put("openDocuments", JSONArray().apply { s.openDocuments.forEach { d -> put(JSONObject().apply { put("path", d.path); put("dirty", d.dirty); put("line", d.cursorLine); put("column", d.cursorColumn) }) } })
            put("lastBuildSessionId", s.lastBuildSessionId ?: JSONObject.NULL)
            put("lastRunSessionId", s.lastRunSessionId ?: JSONObject.NULL)
            put("lastArtifact", s.lastArtifact ?: JSONObject.NULL)
            put("selectedToolWindow", s.selectedToolWindow ?: JSONObject.NULL)
            put("updatedAt", s.updatedAt)
            put("treeWidthDp", s.treeWidthDp)
            put("treeFontScale", s.treeFontScale.toDouble())
            put("treeVisible", s.treeVisible)
            put("bottomPanelVisible", s.bottomPanelVisible)
            put("bottomPanelHeightDp", s.bottomPanelHeightDp)
            put("terminalFontSize", s.terminalFontSize)
            put("buildTaskByProject", JSONObject().apply { s.buildTaskByProject.forEach { (k, v) -> put(k, v) } })
            put("diagnostics", JSONArray().apply { s.diagnostics.forEach { d -> put(JSONObject().apply { put("sessionId", d.sessionId); put("file", d.file ?: JSONObject.NULL); put("line", d.line ?: JSONObject.NULL); put("column", d.column ?: JSONObject.NULL); put("message", d.message); put("severity", d.severity.name); put("timeMs", d.timeMs) }) } })
        }
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "workspace state commit failed" } }
    }

    private fun load(): WorkspaceState = runCatching {
        if (!file.isFile) return@runCatching WorkspaceState()
        val o = JSONObject(file.readText())
        val docs = buildList {
            val a = o.optJSONArray("openDocuments") ?: JSONArray()
            for (i in 0 until a.length()) { val d = a.getJSONObject(i); add(OpenDocumentState(d.optString("path"), d.optBoolean("dirty"), d.optInt("line"), d.optInt("column"))) }
        }
        val diagnostics = buildList {
            val a = o.optJSONArray("diagnostics") ?: JSONArray()
            for (i in 0 until a.length()) { val d = a.getJSONObject(i); add(WorkspaceDiagnostic(d.optString("sessionId"), d.optString("file").takeIf { it.isNotBlank() }, if (d.isNull("line")) null else d.optInt("line"), if (d.isNull("column")) null else d.optInt("column"), d.optString("message"), runCatching { Severity.valueOf(d.optString("severity")) }.getOrDefault(Severity.INFO), d.optLong("timeMs"))) }
        }
        WorkspaceState(o.optString("projectPath").takeIf { it.isNotBlank() }, o.optString("activeFile").takeIf { it.isNotBlank() }, docs, o.optString("lastBuildSessionId").takeIf { it.isNotBlank() }, o.optString("lastRunSessionId").takeIf { it.isNotBlank() }, o.optString("lastArtifact").takeIf { it.isNotBlank() }, diagnostics, o.optString("selectedToolWindow").takeIf { it.isNotBlank() }, o.optLong("updatedAt", System.currentTimeMillis()), o.optInt("treeWidthDp", 260), o.optDouble("treeFontScale", 1.0).toFloat(), o.optBoolean("treeVisible", true), o.optBoolean("bottomPanelVisible", false), o.optInt("bottomPanelHeightDp", 240), o.optInt("terminalFontSize", 13).coerceIn(MIN_TERMINAL_FONT, MAX_TERMINAL_FONT), buildMap {
            o.optJSONObject("buildTaskByProject")?.let { m ->
                m.keys().forEach { k -> m.optString(k).takeIf { it.isNotBlank() }?.let { v -> put(k, v) } }
            }
        })
    }.getOrDefault(WorkspaceState())
}
