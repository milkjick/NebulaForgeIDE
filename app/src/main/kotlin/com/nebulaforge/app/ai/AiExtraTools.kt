package com.nebulaforge.app.ai

import org.json.JSONObject
import java.io.File

/**
 * AI 工具台的「增量能力」实现：代码编辑 / 文件管理 / Git / HTTP / 协作 / 逆向。
 *
 * 为什么要单独一个文件：这些工具是本次补齐的短板（此前 AI 只能整份重写文件、不能删/移动文件、
 * 不能 git、不能发 HTTP、够不到逆向引擎），逻辑量比较大，集中在这里便于维护；
 * [AiTaskToolHost] 只负责分发和转调，保持原文件聚焦在「协议 + 审批 + 提示」上。
 *
 * 安全策略与宿主一致：写类操作一律先过 [TaskApprovalBroker]；越界路径一律拒绝。
 */
internal class AiExtraTools(private val runtime: TaskToolRuntime) {

    // ------------------------------------------------------------------ 基础设施

    private fun fail(call: AiToolCall, message: String): AiToolResult =
        AiToolResult(call.id, call.name, false, output = message)

    private fun done(call: AiToolCall, message: String, stateChanged: Boolean = false): AiToolResult =
        AiToolResult(call.id, call.name, true, stateChanged = stateChanged, output = message)

    private fun projectRootOrNull(): File? =
        runtime.currentProjectPath()?.let { runCatching { File(it) }.getOrNull() }

    private fun resolve(path: String): File? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        val root = projectRootOrNull()
        return if (File(raw).isAbsolute) File(raw) else File(root ?: File("."), raw)
    }

    /** 相对项目根的显示名（不在项目内则给绝对路径）。 */
    private fun relative(file: File): String {
        val root = projectRootOrNull() ?: return file.absolutePath
        return runCatching {
            val rootPath = root.canonicalPath + File.separator
            val target = file.canonicalPath
            if (target.startsWith(rootPath)) target.removePrefix(rootPath) else file.absolutePath
        }.getOrDefault(file.absolutePath)
    }

    private val excluded = Regex("(^|/)(build|\\.git|\\.gradle|node_modules|\\.dart_tool|\\.idea|\\.nebula-reverse)(/|$)")

    private fun excluded(file: File): Boolean = excluded.containsMatchIn(file.absolutePath)

    /** 粗判二进制：按扩展名 + 前 512 字节是否含 NUL。 */
    private fun isBinary(file: File): Boolean {
        val name = file.name.lowercase()
        val binaryExt = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".ttf", ".otf", ".woff", ".woff2",
            ".so", ".dex", ".jar", ".apk", ".aar", ".zip", ".tar", ".gz", ".7z", ".mp3", ".mp4", ".wav"
        )
        if (binaryExt.any { name.endsWith(it) }) return true
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(512)
                val read = input.read(buffer)
                read > 0 && buffer.take(read).any { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    /** 解析「可写」路径：当前项目内、/sdcard 或应用私有目录。 */
    private fun resolveWritable(path: String): File? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        val root = projectRootOrNull()
        val file = if (File(raw).isAbsolute) File(raw) else File(root ?: File("."), raw)
        val target = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val inProject = root != null &&
            target.startsWith(runCatching { root.canonicalPath }.getOrDefault(root.absolutePath))
        val inSd = target == "/sdcard" || target.startsWith("/sdcard/") || target.startsWith("/storage/emulated/0")
        val inFiles = target.startsWith(runtime.toolContext.filesDir.absolutePath)
        return if (inProject || inSd || inFiles) file else null
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    /** 极简 glob → 正则（支持 ** / * / ?）。 */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var index = 0
        while (index < glob.length) {
            val c = glob[index]
            when {
                c == '*' && index + 1 < glob.length && glob[index + 1] == '*' -> {
                    sb.append(".*")
                    index++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c.isLetterOrDigit() || c == '/' || c == '_' || c == '-' || c == ' ' -> sb.append(c)
                else -> sb.append(Regex.escape(c.toString()))
            }
            index++
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private suspend fun approve(call: AiToolCall, title: String, detail: String, patch: AiPendingPatch? = null): Boolean =
        runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.WRITE_FILE,
                title = title, detail = detail, patch = patch
            )
        )

    // ------------------------------------------------------------------ 代码编辑

    /**
     * 精确替换文件片段。
     *
     * 存在的理由：以前 AI 只能 write_file 整份覆盖 —— 改一个 2000 行的文件要回传全文，
     * 既慢又容易被输出上限截断，最后一整份文件写坏。edit_file 只传「要改的那几行」。
     */
    suspend fun editFile(call: AiToolCall): AiToolResult {
        val path = call.arguments.optString("path")
        if (path.isBlank()) return fail(call, "缺少参数 path")
        val oldStr = call.arguments.optString("old_string").ifBlank { call.arguments.optString("oldString") }
        if (oldStr.isEmpty()) return fail(call, "缺少参数 old_string（要被替换掉的原文片段）")
        val newStr = call.arguments.optString("new_string").ifBlank { call.arguments.optString("newString") }
        val file = resolveWritable(path)
            ?: return fail(call, "只允许编辑项目内（或 /sdcard、应用私有目录）的文件：$path")
        if (!file.isFile) return fail(call, "文件不存在：$path（新建文件请用 write_file）")
        val replaceAll = call.arguments.optBoolean("replace_all", false)
        val original = runCatching { file.readText() }
            .getOrElse { return fail(call, "读取失败：${it.message ?: "未知错误"}") }
        val occurrences = countOccurrences(original, oldStr)
        if (occurrences == 0) {
            return fail(
                call,
                "没找到 old_string：请先 read_file 核对原文 —— 缩进、空行、标点必须完全一致才能定位。" +
                    "如果文件是新建的或内容差异很大，改用 write_file 整份写入。"
            )
        }
        if (occurrences > 1 && !replaceAll) {
            return fail(
                call,
                "old_string 在文件中出现 $occurrences 次、不唯一。请把上下文写长一点（多带几行），" +
                    "或传 replace_all=true 全部替换。"
            )
        }
        val updated = if (replaceAll) original.replace(oldStr, newStr) else original.replaceFirst(oldStr, newStr)
        val patch = AiPendingPatch(toolCallId = call.id, path = file.absolutePath, content = updated, original = original)
        if (!approve(call, "AI 请求修改文件", patch.summary, patch)) {
            return fail(call, "用户拒绝了该改动（未写入磁盘）：${patch.summary}")
        }
        runCatching { file.writeText(updated) }
            .getOrElse { return fail(call, "写入失败：${it.message ?: "未知错误"}") }
        return done(call, "已修改 ${relative(file)}：$occurrences 处替换\n${patch.summary}")
    }

    /** 全项目批量替换（跨文件重构）。默认只预览，确认后才写盘。 */
    suspend fun searchReplace(call: AiToolCall): AiToolResult {
        val find = call.arguments.optString("find").ifBlank { call.arguments.optString("pattern") }
        if (find.isBlank()) return fail(call, "缺少参数 find（要查找的内容）")
        val replace = call.arguments.optString("replace")
        val useRegex = call.arguments.optBoolean("regex", false)
        val dryRun = call.arguments.optBoolean("dry_run", true)
        val maxFiles = call.arguments.optInt("max_files", 40).coerceIn(1, 400)
        val scopeArg = call.arguments.optString("path").takeIf { it.isNotBlank() }
        val base = if (scopeArg == null) projectRootOrNull() else resolve(scopeArg)
        if (base == null || !base.exists()) return fail(call, "作用目录不存在：${scopeArg ?: "（未打开项目）"}")
        val regex = runCatching { if (useRegex) Regex(find) else Regex(Regex.escape(find)) }
            .getOrElse { return fail(call, "查找表达式不合法：${it.message ?: "无法解析"}") }

        val candidates = if (base.isFile) listOf(base)
        else base.walkTopDown().onEnter { !excluded(it) }
            .filter { it.isFile && !isBinary(it) && !excluded(it) }
            .take(maxFiles * 30).toList()

        val hits = ArrayList<Triple<File, Int, String>>()
        for (file in candidates) {
            if (hits.size >= maxFiles) break
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val matches = regex.findAll(text).count()
            if (matches > 0) hits += Triple(file, matches, text)
        }
        if (hits.isEmpty()) return done(call, "没有命中：$find\n（范围：${base.absolutePath}）")

        val summary = hits.joinToString("\n") { "  ${relative(it.first)}：${it.second} 处" }
        if (dryRun) {
            return done(
                call,
                "预览（**未写入**）：命中 ${hits.size} 个文件\n$summary\n\n确认无误后用 dry_run=false 执行替换。"
            )
        }
        if (!approve(call, "AI 请求批量替换", "将替换 ${hits.size} 个文件：\n$summary")) {
            return fail(call, "用户拒绝了批量替换。")
        }
        var changedFiles = 0
        var changedHits = 0
        for ((file, count, text) in hits) {
            runCatching { file.writeText(regex.replace(text, replace)) }.onSuccess {
                changedFiles++
                changedHits += count
            }
        }
        return done(call, "已替换 $changedFiles 个文件、约 $changedHits 处：\n$summary")
    }

    /** 批量读文件：路径列表或 glob。 */
    fun readFiles(call: AiToolCall): AiToolResult {
        val raw = call.arguments.optString("paths").ifBlank { call.arguments.optString("path") }
        if (raw.isBlank()) return fail(call, "缺少参数 paths（路径列表或 glob，如 **/*.kt）")
        val maxLines = call.arguments.optInt("max_lines_per_file", 300).coerceIn(10, 5000)
        val root = projectRootOrNull()
        val files = LinkedHashSet<File>()
        for (token in raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (token.contains('*') || token.contains('?')) {
                if (root == null) continue
                val regex = globToRegex(token)
                root.walkTopDown().onEnter { !excluded(it) }
                    .filter { it.isFile && !isBinary(it) && regex.containsMatchIn(relative(it)) }
                    .take(60).forEach { files += it }
            } else {
                resolve(token)?.takeIf { it.isFile }?.let { files += it }
            }
        }
        if (files.isEmpty()) return fail(call, "没有匹配到文件：$raw")
        val out = StringBuilder()
        for (file in files.take(20)) {
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            out.append("### ").append(relative(file)).append("（共 ").append(lines.size).append(" 行）\n")
            lines.take(maxLines).forEachIndexed { index, line ->
                out.append(index + 1).append(": ").append(line).append('\n')
            }
            if (lines.size > maxLines) out.append("…（已截断）\n")
            out.append('\n')
        }
        if (files.size > 20) out.append("（其余 ${files.size - 20} 个文件未展开）\n")
        return done(call, out.toString().take(60_000))
    }

    // ------------------------------------------------------------------ 文件管理

    /** 删除文件/目录（需确认）。 */
    suspend fun deletePath(call: AiToolCall): AiToolResult {
        val path = call.arguments.optString("path")
        if (path.isBlank()) return fail(call, "缺少参数 path")
        val file = resolveWritable(path) ?: return fail(call, "只允许删除项目内或 /sdcard 下的路径：$path")
        if (!file.exists()) return fail(call, "路径不存在：$path")
        val recursive = call.arguments.optBoolean("recursive", false)
        if (file.isDirectory && !recursive) return fail(call, "目标是目录，需传 recursive=true 才能删除：$path")
        val what = if (file.isDirectory) "目录" else "文件"
        if (!approve(call, "AI 请求删除", "删除$what：${file.absolutePath}")) {
            return fail(call, "用户拒绝了删除操作：$path")
        }
        val ok = runCatching { file.deleteRecursively() }.getOrDefault(false)
        return AiToolResult(
            call.id, call.name, ok,
            output = if (ok) "已删除$what：$path" else "删除失败：$path（可能被占用或无权限）"
        )
    }

    /** 移动 / 重命名（相当于 mv）。 */
    suspend fun moveFile(call: AiToolCall): AiToolResult {
        val src = call.arguments.optString("source").ifBlank { call.arguments.optString("src") }
        val dst = call.arguments.optString("destination").ifBlank { call.arguments.optString("dest") }
        if (src.isBlank() || dst.isBlank()) return fail(call, "需要 source 与 destination 两个参数")
        val source = resolveWritable(src) ?: return fail(call, "源路径不在允许范围内：$src")
        if (!source.exists()) return fail(call, "源路径不存在：$src")
        var dest = resolveWritable(dst) ?: return fail(call, "目标路径不在允许范围内：$dst")
        if (dest.isDirectory) dest = File(dest, source.name)
        if (dest.exists()) return fail(call, "目标已存在，拒绝覆盖：${dest.absolutePath}")
        if (!approve(call, "AI 请求移动/重命名", "${source.absolutePath}\n→ ${dest.absolutePath}")) {
            return fail(call, "用户拒绝了移动操作。")
        }
        dest.parentFile?.mkdirs()
        val renamed = runCatching { source.renameTo(dest) }.getOrDefault(false)
        val ok = renamed || runCatching {
            source.copyRecursively(dest, overwrite = false)
            source.deleteRecursively()
        }.getOrDefault(false)
        return AiToolResult(
            call.id, call.name, ok,
            output = if (ok) "已移动到：${dest.absolutePath}" else "移动失败：$src → $dst"
        )
    }

    // ------------------------------------------------------------------ 版本控制 / 网络 / 协作

    /** git 操作（在项目目录里通过用户态工具链通道执行）。 */
    suspend fun gitOp(call: AiToolCall): AiToolResult {
        val op = call.arguments.optString("op").ifBlank { call.arguments.optString("action") }
        if (op.isBlank()) return fail(call, "缺少参数 op（如 status / diff / log / add / commit / init）")
        val root = projectRootOrNull() ?: return fail(call, "未打开项目，git 不可用")
        val allowed = setOf(
            "status", "diff", "log", "add", "commit", "checkout", "branch", "stash", "init", "remote",
            "pull", "push", "show", "reset", "restore", "tag", "rev-parse", "ls-files", "blame", "fetch"
        )
        val safeOp = op.trim().substringBefore(' ').trim()
        if (safeOp !in allowed) {
            return fail(call, "不支持的 git 子命令：$safeOp（可用：" + allowed.sorted().joinToString("/") + "）")
        }
        val args = call.arguments.optString("args")
        val command = "git $safeOp" + (if (args.isBlank()) "" else " " + args.trim()) + " 2>&1"
        val out = runtime.execInToolchain(command, root.absolutePath, 120_000)
        return done(call, out.take(20_000))
    }

    /** 发真实 HTTP 请求（接口联调 / 取数据）。 */
    suspend fun httpRequest(call: AiToolCall): AiToolResult {
        val url = call.arguments.optString("url")
        if (url.isBlank()) return fail(call, "缺少参数 url")
        val method = call.arguments.optString("method").ifBlank { "GET" }.uppercase()
        val headers = call.arguments.optString("headers").takeIf { it.isNotBlank() }
        val body = call.arguments.optString("body").takeIf { it.isNotBlank() }
        val out = runCatching { runtime.httpRequest(method, url, headers, body) }
            .getOrElse { return fail(call, "请求失败：${it.message ?: "未知错误"}") }
        return done(call, out)
    }

    /** 向用户提问并挂起等待回答。 */
    suspend fun askUser(call: AiToolCall): AiToolResult {
        val question = call.arguments.optString("question").ifBlank { call.arguments.optString("prompt") }
        if (question.isBlank()) return fail(call, "缺少参数 question")
        val options = call.arguments.optString("options").ifBlank { call.arguments.optString("choices") }
            .split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val answer = runCatching { runtime.askUser(question, options) }
            .getOrElse { return fail(call, "提问失败：${it.message ?: "未知错误"}") }
        return done(call, "用户回答：$answer")
    }

    /** 列出技能与妙招（动手前先看有没有可复用的）。 */
    fun listSkills(call: AiToolCall): AiToolResult {
        val kind = call.arguments.optString("kind").ifBlank { "all" }
        return done(call, runtime.describeSkills(kind))
    }

    /** 删除技能或妙招。 */
    fun deleteSkill(call: AiToolCall): AiToolResult {
        val kind = call.arguments.optString("kind").ifBlank { "skill" }
        val id = call.arguments.optString("id").ifBlank { call.arguments.optString("name") }
        if (id.isBlank()) return fail(call, "缺少参数 id（技能/妙招的 id 或名称）")
        val out = runCatching { runtime.removeSkill(kind, id) }
            .getOrElse { return fail(call, "删除失败：${it.message ?: "未知错误"}") }
        return done(call, out, stateChanged = true)
    }

    // ------------------------------------------------------------------ 逆向能力

    /**
     * APK 逆向。
     *
     * 以前逆向能力只存在于「逆向工作台」界面与计划执行器的固定步骤里，AI 工作台完全够不到 ——
     * 用户说「分析下这个 APK / 把里面的接口抓出来」，AI 只能回答「请打开逆向工作台自己点」。
     */
    suspend fun apkReverse(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").ifBlank { "inspect" }.trim().lowercase()
        // toolchain 是纯自检动作，不需要 path，允许不带参数调用。
        val allowed = setOf("inspect", "unpack", "files", "read", "write", "rebuild", "evidence", "toolchain")
        if (action !in allowed) {
            return fail(call, "不支持的 action：$action（可用：" + allowed.sorted().joinToString("/") + "）")
        }
        val path = call.arguments.optString("path").takeIf { it.isNotBlank() }
        val file = call.arguments.optString("file").takeIf { it.isNotBlank() }
        val content = call.arguments.optString("content").takeIf { it.isNotBlank() }
        val out = call.arguments.optString("out").takeIf { it.isNotBlank() }
        if (action == "inspect" || action == "unpack") {
            val target = path ?: return fail(call, "缺少参数 path（要分析的 APK 路径）")
            val apk = resolve(target)
            if (apk == null || !apk.isFile) return fail(call, "APK 不存在：$target")
        }
        if (action == "read" || action == "write") {
            if (file.isNullOrBlank()) return fail(call, "action=$action 需要参数 file（工作区内的文件路径）")
        }
        if (action == "write" && content == null) {
            return fail(call, "action=write 需要参数 content（写入的新内容）")
        }
        val text = runCatching { runtime.reverseApk(action, path, file, content, out) }
            .getOrElse { return fail(call, "逆向操作失败：${it.message ?: "未知错误"}") }
        return done(call, text, stateChanged = action == "write" || action == "rebuild")
    }

    /** Web 逆向：查看内置浏览器捕获的网络记录，并可让 AI 分析。 */
    suspend fun webReverse(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").ifBlank { "records" }.trim().lowercase()
        // to_api：把浏览器抓到的接口直接沉淀成 API 端点，用户不必手工把 URL 抄到 API 页。
        val allowed = setOf("records", "analyze", "to_api", "clear")
        if (action !in allowed) {
            return fail(call, "不支持的 action：$action（可用：" + allowed.sorted().joinToString("/") + "）")
        }
        val url = call.arguments.optString("url").takeIf { it.isNotBlank() }
        val limit = call.arguments.optInt("limit", 40).coerceIn(1, 200)
        val text = runCatching { runtime.reverseWeb(action, url, limit) }
            .getOrElse { return fail(call, "Web 逆向失败：${it.message ?: "未知错误"}") }
        // to_api 会写入端点库，属于「有副作用」，要标 stateChanged 让界面刷新。
        return done(call, text, stateChanged = action == "clear" || action == "to_api")
    }

    /** API 逆向：接口清单 / 分析 / OpenAPI 导出 / MITM 代理开关。 */
    suspend fun apiReverse(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").ifBlank { "endpoints" }.trim().lowercase()
        val allowed = setOf("endpoints", "analyze", "openapi", "proxy_start", "proxy_stop", "proxy_status", "clear")
        if (action !in allowed) {
            return fail(call, "不支持的 action：$action（可用：" + allowed.sorted().joinToString("/") + "）")
        }
        val filter = call.arguments.optString("filter").ifBlank { call.arguments.optString("url") }
            .takeIf { it.isNotBlank() }
        val port = if (call.arguments.has("port")) call.arguments.optInt("port").takeIf { it in 1..65535 } else null
        val text = runCatching { runtime.reverseApi(action, filter, port) }
            .getOrElse { return fail(call, "API 逆向失败：${it.message ?: "未知错误"}") }
        return done(call, text, stateChanged = action == "proxy_start" || action == "proxy_stop" || action == "clear")
    }

    /**
     * 本机 MITM CA 证书：抓 HTTPS 的前置条件。
     *
     * 这是逆向链路里最容易把用户卡住的一环（「代理开了、站点能打开、却一条 HTTPS 都抓不到」），
     * 所以把它做成独立工具，让 AI 能在需要时自己检查证书状态、导出、甚至直接写系统库，
     * 而不是把问题甩回给用户。
     */
    suspend fun caCertificate(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").ifBlank { "status" }.trim().lowercase()
        val allowed = setOf("status", "export", "install_system", "install_user")
        if (action !in allowed) {
            return fail(call, "不支持的 action：$action（可用：" + allowed.sorted().joinToString("/") + "）")
        }
        val text = runCatching { runtime.caCertificate(action) }
            .getOrElse { return fail(call, "CA 操作失败：${it.message ?: "未知错误"}") }
        // install_system / install_user 改变了设备证书状态，需要刷新界面。
        return done(call, text, stateChanged = action.startsWith("install"))
    }
}
