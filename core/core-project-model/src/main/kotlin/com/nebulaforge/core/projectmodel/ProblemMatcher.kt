package com.nebulaforge.core.projectmodel

/**
 * 从一行构建输出里解析出的「问题」。
 *
 * [filePath] 是**原始字符串**（可能相对路径 / guest 前缀 / `file://` URI），
 * 由上层用 `ProotPathMapper.resolve` 映射成宿主机真实文件后再决定是否可跳转 ——
 * 解析与映射分离，才能对「路径点不动」这类问题分别定位到底是解析错了还是映射错了。
 */
data class DetectedProblem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String,
    val severity: BuildError.Severity,
    val matcherName: String
)

/**
 * 通用 problemMatcher：把构建日志行翻译成结构化问题，供「问题」面板与编辑器跳转使用。
 *
 * ## 为什么不复用各 BuildSystem.parseErrors
 * `parseErrors(rawOutput)` 面向「一次性拿到全部输出」的场景；而构建面板是**流式**的，
 * 需要逐行匹配、去重、并把结果实时推给 DiagnosticStore。两者数据流不同，
 * 这里提供行级原语，BuildSystem 版本保留给 AI 修复链路使用。
 *
 * ## 覆盖范围（实测样例）
 * - Gradle/Android：`e: file:///path/Main.kt:12:5 Unresolved reference: foo`
 * - javac/kotlinc：`src/Main.kt:12:5: error: unresolved reference`
 * - gcc/clang：`src/main.c:12:5: error: 'x' undeclared`
 * - Dart：`lib/main.dart:12:5: Error: Undefined name 'foo'` 与 `error • msg • lib/main.dart:12:5`
 * - TypeScript：`src/a.ts(12,5): error TS2339: ...`
 * - Python：`  File "/path/a.py", line 12`
 * - Rust：`--> src/main.rs:12:5`
 * - Go：`./main.go:12:5: undefined: foo`
 */
object ProblemMatcher {

    const val GRADLE = "\$nebula-gradle"
    const val GCC = "\$nebula-gcc"
    const val DART = "\$nebula-dart"
    const val TSC = "\$nebula-tsc"
    const val PYTHON = "\$nebula-python"
    const val RUST = "\$nebula-rust"
    const val GO = "\$nebula-go"
    const val GENERIC = "\$nebula-generic"

    private val gradleFile = Regex("""^([ew]):\s*file://(\S+?):(\d+):(\d+)\s+(.*)$""")
    private val gradlePlain = Regex("""^(\S+\.(?:kt|kts|java|gradle)):(\d+):(\d+):\s*(error|warning|Error|Warning):\s*(.+)$""")
    private val gcc = Regex("""^(.+?):(\d+):(\d+):\s*(fatal error|error|warning|note):\s*(.*)$""")
    private val gccNoColumn = Regex("""^(.+?):(\d+):\s*(fatal error|error|warning):\s*(.*)$""")
    private val dartAnalyzer = Regex("""^\s*(?:error|warning|info)\s*•\s*(.*?)\s*•\s*(\S+?\.[A-Za-z]+):(\d+):(\d+)\s*(?:•.*)?$""")
    private val dartSimple = Regex("""^(\S+\.dart):(\d+):(\d+):\s*(Error|Warning|Info):\s*(.*)$""")
    private val tsc = Regex("""^(.+?)\((\d+),(\d+)\):\s*(error|warning)\s*(TS\d+)?:?\s*(.*)$""")
    private val python = Regex("""^\s*File "(.+?)",\s*line\s*(\d+)(?:,\s*in\s*(.*))?$""")
    private val rust = Regex("""^\s*-->\s*(\S+?):(\d+):(\d+)$""")
    private val go = Regex("""^(\S+?\.go):(\d+):(\d+):\s*(.*)$""")
    private val generic = Regex(
        """^(?:.*?[:\[\(]\s*)?(\S+?\.(?:kt|kts|java|dart|c|cc|cpp|cxx|h|hpp|py|ts|tsx|js|jsx|go|rs|swift|m|mm|rb|php|cs|scala|xml|json|gradle|kts))[:\(](\d+)[:,](\d+)\)?:?\s*(?:error|Error|ERROR|warning|Warning|WARNING|fatal error)?:?\s*(.*)$"""
    )

    /**
     * 尝试把 [line] 解析成问题。
     *
     * @param matcherNames tasks.json 里声明的 problemMatcher（仅作提示：非空时**优先**按声明的规则尝试，
     *                     但仍会用通用规则兜底，避免「用户少写一行 matcher 就什么都匹配不到」）
     */
    fun match(line: String, matcherNames: List<String> = emptyList()): DetectedProblem? {
        val text = line.trimEnd()
        // 工具链**守护进程噪声**不是用户代码的问题，却在真机上被当成错误推进「问题」面板：
        //   1) AGP 的 aapt2 包装器（proot 下用 /system/bin/sh）先打一行链接器告警，被解析成
        //      file=「AAPT2 toolchain Daemon #0: Unexpected error output: WARNING: linker: …」
        //      message=「section "system" not found」的 ERROR；
        //   2) aapt2 自己刷屏的 `No package ID 7f found for resource ID 0x…`。
        // 用户截图：构建**成功**却显示「问题 1/1」，点进去是这种无意义条目。这里直接挡掉。
        if ((text.contains("No package ID ") && text.contains("found for resource ID")) ||
            text.contains("linker: Warning: couldn't read") ||
            text.contains("AAPT2 toolchain Daemon") ||
            text.contains("Unexpected error output")
        ) {
            return null
        }
        if (text.isBlank()) return null
        // 明显不是诊断的噪声行直接跳过：纯分隔线、进度条残留、Too long 的容器日志。
        if (text.length > 2000) return null

        val ordered = buildList {
            matcherNames.forEach { add(it) }
            addAll(listOf(GRADLE, GCC, DART, TSC, PYTHON, RUST, GO, GENERIC))
        }.distinct()

        ordered.forEach { name ->
            val hit = when (name) {
                GRADLE -> gradleFile.find(text)?.let { m ->
                    problem(severityOf(m.groupValues[1]), m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5], GRADLE)
                } ?: gradlePlain.find(text)?.let { m ->
                    problem(severityOf(m.groupValues[4]), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[5], GRADLE)
                }
                GCC -> gcc.find(text)?.let { m ->
                    if (isGccNoise(m.groupValues[1])) null
                    else problem(severityOf(m.groupValues[4]), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[5], GCC)
                } ?: gccNoColumn.find(text)?.let { m ->
                    if (isGccNoise(m.groupValues[1])) null
                    else problem(severityOf(m.groupValues[3]), m.groupValues[1], m.groupValues[2], "0", m.groupValues[4], GCC)
                }
                DART -> dartAnalyzer.find(text)?.let { m ->
                    problem(severityOf(text), m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[1], DART)
                } ?: dartSimple.find(text)?.let { m ->
                    problem(severityOf(m.groupValues[4]), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[5], DART)
                }
                TSC -> tsc.find(text)?.let { m ->
                    problem(severityOf(m.groupValues[4]), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[6], TSC)
                }
                PYTHON -> python.find(text)?.let { m ->
                    problem(BuildError.Severity.ERROR, m.groupValues[1], m.groupValues[2], "1", text.trim(), PYTHON)
                }
                RUST -> rust.find(text)?.let { m ->
                    problem(BuildError.Severity.ERROR, m.groupValues[1], m.groupValues[2], m.groupValues[3], text.trim(), RUST)
                }
                GO -> go.find(text)?.let { m ->
                    problem(severityOf(text), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], GO)
                }
                GENERIC -> generic.find(text)?.let { m ->
                    problem(severityOf(m.groupValues[4] + text), m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], GENERIC)
                }
                else -> null
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun problem(
        severity: BuildError.Severity,
        file: String,
        line: String,
        column: String,
        message: String,
        matcher: String
    ): DetectedProblem? {
        val path = file.trim().trim('"', '\'', '(', '[', ',')
        val msg = message.trim()
        // 过滤「有文件行号但没有内容」的噪声；同时挡掉 `:0:0` 这类明显无效位置。
        if (msg.length < 2) return null
        val lineNo = line.toIntOrNull() ?: return null
        if (lineNo <= 0) return null
        return DetectedProblem(
            filePath = path,
            line = lineNo,
            column = (column.toIntOrNull() ?: 0).coerceAtLeast(1),
            message = msg,
            severity = severity,
            matcherName = matcher
        )
    }

    private fun severityOf(text: String): BuildError.Severity {
        val lower = text.lowercase()
        return if (lower.contains("error") || lower.contains("fatal")) BuildError.Severity.ERROR
        else BuildError.Severity.WARNING
    }

    /** 挡掉 gcc 输出里形如 `x86_64-linux-gnu-gcc: error: ...` 这类「工具自身报错」（不是源文件问题）。 */
    private fun isGccNoise(file: String): Boolean {
        val name = file.substringAfterLast('/')
        return !file.contains('.') || file.startsWith("clang") || name.startsWith("ld:") || name.startsWith("collect2")
    }
}
