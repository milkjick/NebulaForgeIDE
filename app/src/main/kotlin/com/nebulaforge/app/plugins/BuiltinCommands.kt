package com.nebulaforge.app.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 上下文键存储：对应 VS Code 的 `setContext(key, value)` + `when` 子句。
 *
 * 为什么必须有：`setContext` 是**内置命令**，很多扩展（Claude Code、GitHub Copilot 等）
 * 在 activate 阶段就会调用它来切换视图可见性。宿主此前直接回
 * `IDE 未提供命令 setContext`，扩展侧记 error、`when` 表达式也永远读不到状态。
 */
class ContextKeyStore(private val stateFile: File? = null) {

    private val values = ConcurrentHashMap<String, Any?>()

    init {
        load()
    }

    fun set(key: String, value: Any?) {
        if (key.isBlank()) return
        if (value == null || value == JSONObject.NULL) values.remove(key) else values[key] = value
        persist()
    }

    fun get(key: String): Any? = values[key]

    /** 面板里展示用（只读快照）。 */
    fun snapshot(): Map<String, Any?> = values.toMap()

    /**
     * 极简 `when` 求值：支持 `key`、`!key`、`key == value`、`key != value`、
     * 以及 `&&` 连接。复杂表达式（正则、in、view 判定）一律按“无法判定 = 显示”处理，
     * 宁可多显示也不要把用户能用的命令藏掉。
     */
    fun evaluate(whenClause: String?): Boolean {
        if (whenClause.isNullOrBlank()) return true
        return try {
            whenClause.split("&&").all { evaluateClause(it.trim()) }
        } catch (t: Throwable) {
            true
        }
    }

    private fun evaluateClause(clause: String): Boolean {
        if (clause.isBlank()) return true
        val negate = clause.startsWith("!")
        val body = if (negate) clause.substring(1).trim() else clause
        val result = when {
            body.contains("==") -> {
                val (k, v) = body.split("==", limit = 2)
                val actual = values[k.trim()]?.toString() ?: "undefined"
                actual == v.trim()
            }
            body.contains("!=") -> {
                val (k, v) = body.split("!=", limit = 2)
                val actual = values[k.trim()]?.toString() ?: "undefined"
                actual != v.trim()
            }
            else -> truthy(values[body])
        }
        return if (negate) !result else result
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty() && value != "false"
        else -> true
    }

    private fun load() {
        val file = stateFile ?: return
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            json.keys().forEach { key -> values[key] = json.opt(key).takeIf { it != JSONObject.NULL } }
        }
    }

    private fun persist() {
        val file = stateFile ?: return
        runCatching {
            val json = JSONObject()
            values.forEach { (key, value) -> if (value != null) json.put(key, value) }
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
        }
    }
}

/**
 * IDE 动作回调：由 app 层注入真实实现。
 *
 * 未注入的动作不会硬失败，而是按「已识别但当前无动作」软处理并记录到缺口清单——
 * 这样扩展的 UI 流程可以继续走完，而不是在 `executeCommand` 处抛错中断。
 */
class IdeActions(
    val openEditor: ((uri: String) -> Boolean)? = null,
    val saveActive: (() -> Boolean)? = null,
    val saveAll: (() -> Boolean)? = null,
    val closeActiveEditor: (() -> Boolean)? = null,
    val splitEditor: ((direction: String) -> Boolean)? = null,
    val openView: ((viewId: String) -> Boolean)? = null,
    val quickOpen: ((mode: String) -> Boolean)? = null,
    val openSettings: ((section: String?) -> Boolean)? = null,
    val runEditorAction: ((action: String) -> Boolean)? = null,
    val reloadWindow: (() -> Unit)? = null,
)

/**
 * 宿主内置命令表。
 *
 * VS Code 里这些命令由 workbench 提供、**不属于任何扩展**，所以“谁都不注册它们”。
 * 宿主必须自己认识它们，否则每个依赖 UI 命令的扩展都会在日志里刷
 * `IDE 未提供命令 xxx`，严重时中断扩展的激活/交互流程。
 */
class BuiltinCommandRegistry(
    private val actionsProvider: () -> IdeActions?,
    private val log: (level: String, message: String) -> Unit,
    /** 上下文键落盘位置；为 null 时仅内存保存。 */
    contextKeysFile: File? = null,
) {

    sealed class Outcome {
        /** 真正执行了，value 为返回给扩展的值（无返回值时传 null）。 */
        class Handled(val value: Any?) : Outcome()

        /** 已识别为内置/UI 类命令，但宿主当前无对应动作：软成功（等价 undefined）。 */
        class Declared(val message: String) : Outcome()

        /** 完全无法识别的命令。 */
        object Unknown : Outcome()
    }

    val contextKeys = ContextKeyStore(contextKeysFile)

    /** 被软处理的内置命令清单（面板里给用户看的真实缺口）。 */
    private val _gaps = MutableStateFlow<List<String>>(emptyList())
    val gaps: StateFlow<List<String>> = _gaps.asStateFlow()

    /** 已软处理过的命令集合，避免日志刷屏。 */
    private val declaredSeen = ConcurrentHashMap.newKeySet<String>()

    fun isUiCommand(command: String): Boolean =
        command.startsWith("workbench.") || command.startsWith("editor.action.") ||
            command.startsWith("notifications.") || command.startsWith("revealInExplorer") ||
            command.startsWith("vscode.") || command.startsWith("_")

    /**
     * 执行内置命令。
     *
     * @return null 表示「不是内置命令」，交由扩展命令分发继续处理。
     */
    fun execute(command: String, args: JSONArray?): Outcome {
        val actions = actionsProvider()
        fun argAt(index: Int): Any? = args?.opt(index)

        when (command) {
            // ---------------------------------------------------------- 上下文键
            "setContext" -> {
                val key = argAt(0)?.toString().orEmpty()
                contextKeys.set(key, argAt(1))
                return Outcome.Handled(null)
            }
            "setStatusBarEntry", "setStatusBarMessage" -> return Outcome.Handled(null)
        }

        // ---------------------------------------------------------- 编辑器/文件
        if (command == "vscode.open" || command == "vscode.openWith") {
            val uri = firstUriArg(args)
            if (uri == null) return Outcome.Declared("vscode.open 缺少 uri 参数")
            val open = actions?.openEditor
            return if (open != null) {
                Outcome.Handled(if (open(uri)) null else throw IllegalStateException("编辑器未能打开 $uri"))
            } else {
                declare(command, "宿主未注入编辑器动作，已忽略 vscode.open")
            }
        }
        if (command == "vscode.diff") {
            val left = firstUriArg(args)
            val run = actions?.openEditor
            if (left != null && run != null) {
                run(left)
                return Outcome.Handled(null)
            }
            return declare(command, "宿主暂不渲染 diff 视图")
        }
        if (command.startsWith("workbench.action.files.save")) {
            val fn = if (command.endsWith("saveAll")) actions?.saveAll else actions?.saveActive
            return if (fn != null) Outcome.Handled(if (fn() ) null else throw IllegalStateException("保存失败"))
            else declare(command, "宿主未注入保存动作")
        }
        if (command == "workbench.action.closeActiveEditor" || command == "workbench.action.closeAllEditors") {
            val fn = actions?.closeActiveEditor
            return if (fn != null) Outcome.Handled(if (fn()) null else null)
            else declare(command, "宿主未注入关闭编辑器动作")
        }

        // ---------------------------------------------------------- 布局/视图
        val split = when (command) {
            "workbench.action.newGroupRight", "workbench.action.splitEditor",
            "workbench.action.splitEditorRight" -> "right"
            "workbench.action.newGroupLeft", "workbench.action.splitEditorLeft" -> "left"
            "workbench.action.newGroupAbove", "workbench.action.splitEditorUp" -> "up"
            "workbench.action.newGroupBelow", "workbench.action.splitEditorDown" -> "down"
            else -> null
        }
        if (split != null) {
            val fn = actions?.splitEditor
            return if (fn != null) {
                fn(split)
                Outcome.Handled(null)
            } else declare(command, "宿主未注入分屏动作（已忽略）")
        }

        val view = when (command) {
            "workbench.view.explorer", "workbench.action.files.openFolder" -> "explorer"
            "workbench.view.search", "workbench.action.findInFiles" -> "search"
            "workbench.view.scm" -> "scm"
            "workbench.view.debug", "workbench.action.debug.start" -> "debug"
            "workbench.view.extensions" -> "extensions"
            "workbench.action.output.toggleOutput", "workbench.action.togglePanel" -> "output"
            "workbench.action.terminal.toggleTerminal", "workbench.action.terminal.new",
            "workbench.action.terminal.focus" -> "terminal"
            "workbench.panel.markers.view.focus" -> "problems"
            else -> null
        }
        if (view != null) {
            val fn = actions?.openView
            return if (fn != null) {
                fn(view)
                Outcome.Handled(null)
            } else declare(command, "宿主未注入视图切换动作（已忽略 $view）")
        }

        if (command == "workbench.action.quickOpen" || command == "workbench.action.showCommands") {
            val fn = actions?.quickOpen
            val mode = if (command == "workbench.action.showCommands") "commands" else "files"
            return if (fn != null) {
                fn(mode)
                Outcome.Handled(null)
            } else declare(command, "宿主未注入快速打开动作")
        }
        if (command == "workbench.action.openSettings") {
            val fn = actions?.openSettings
            return if (fn != null) {
                fn(argAt(0)?.toString())
                Outcome.Handled(null)
            } else declare(command, "宿主未注入设置页动作")
        }
        if (command == "workbench.action.reloadWindow" || command == "workbench.action.forceReloadWindow") {
            actions?.reloadWindow?.invoke()
            return Outcome.Handled(null)
        }
        if (command.startsWith("editor.action.")) {
            val fn = actions?.runEditorAction
            return if (fn != null && fn(command)) Outcome.Handled(null)
            else declare(command, "宿主暂未实现编辑器动作 $command")
        }
        if (command.startsWith("workbench.action.markdown.") || command.startsWith("markdown.")) {
            return declare(command, "宿主暂未实现 Markdown 动作")
        }

        // ---------------------------------------------------------- 兜底
        if (isUiCommand(command)) return declare(command, "宿主未实现的内置/UI 命令")
        return Outcome.Unknown
    }

    private fun declare(command: String, reason: String): Outcome.Declared {
        if (declaredSeen.add(command)) {
            log("info", "内置命令 $command 已识别但暂无动作：$reason")
            _gaps.value = (_gaps.value + command).takeLast(50)
        }
        return Outcome.Declared(reason)
    }

    private fun firstUriArg(args: JSONArray?): String? {
        val first = args?.opt(0) ?: return null
        return when (first) {
            is String -> first
            is JSONObject -> first.optString("external").takeIf { it.isNotBlank() }
                ?: first.optString("path").takeIf { it.isNotBlank() }
                ?: first.optString("fsPath").takeIf { it.isNotBlank() }
            else -> first.toString()
        }
    }
}
