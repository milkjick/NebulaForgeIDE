package com.nebulaforge.core.editor.intel

import java.io.File

/** 工作区里某个文件声明的顶层符号（用于「跨文件标识符补全」）。 */
data class WorkspaceSymbol(
    val name: String,
    val kind: CompletionKind,
    /** 声明的宿主绝对路径（映射后），用于候选的副标题与「跳转到定义」的兜底。 */
    val filePath: String,
    /** 所在文件/容器描述，显示为候选副标题。 */
    val detail: String,
    val weight: Int = 8
)

/**
 * 工作区符号索引：把「同工作区其他文件」里的类/函数/常量名收集起来，喂给本地补全。
 *
 * ## 为什么要单独做，不直接问 LSP
 * 内置语言服务器是**按需启动**的（没装 jdtls 就没有 Java 补全），而且启动后也要几百毫秒到数秒。
 * 需求要求「输入即弹候选」且**离线可用**，因此本地必须有一份跨文件索引兜底。
 *
 * ## 成本控制（移动端关键）
 * - 只扫源码扩展名（[SOURCE_EXTENSIONS]）；
 * - 跳过构建产物与依赖目录（[IGNORED_DIRS]），否则 `build/`、`node_modules/` 会拖死扫描；
 * - 单文件 > [MAX_FILE_BYTES] 只读前 [MAX_FILE_BYTES] 字节；
 * - 总文件数上限 [MAX_FILES]、总符号上限 [MAX_SYMBOLS]；
 * - 提取用**行级正则**，不做完整语法解析（正则漏掉的符号由 LSP 补齐，二者互补）。
 *
 * 线程约定：[rebuild] 是阻塞的，调用方必须在 IO 线程执行；[snapshot] 可任意线程读取。
 */
class WorkspaceSymbolIndex {

    @Volatile
    private var symbols: List<WorkspaceSymbol> = emptyList()

    @Volatile
    private var indexedInMillis: Long = 0

    @Volatile
    private var indexedRoot: String? = null

    fun snapshot(): List<WorkspaceSymbol> = symbols

    fun lastIndexedRoot(): String? = indexedRoot

    fun lastIndexedInMillis(): Long = indexedInMillis

    fun clear() {
        symbols = emptyList()
        indexedRoot = null
        indexedInMillis = 0
    }

    /** 全量重建索引。同项目重复调用会覆盖；不同项目调用会替换（单工程 IDE 语义）。 */
    fun rebuild(projectRoot: File) {
        val root = projectRoot.absoluteFile
        if (!root.isDirectory) return
        val started = System.currentTimeMillis()
        val found = LinkedHashMap<String, WorkspaceSymbol>()
        var scanned = 0
        root.walkTopDown()
            .onEnter { dir -> dir.name !in IGNORED_DIRS && dir.absolutePath.count { it == '/' } <= root.absolutePath.count { it == '/' } + 8 }
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .take(MAX_FILES)
            .forEach { file ->
                scanned++
                if (found.size >= MAX_SYMBOLS) return@forEach
                val text = runCatching { file.readText().take(MAX_BYTES) }.getOrNull() ?: return@forEach
                extract(file, text).forEach { symbol ->
                    // 同名符号保留权重更高的（局部/函数优先于常量），避免候选列表被重复项填满。
                    val existing = found[symbol.name]
                    if (existing == null || symbol.weight < existing.weight) found[symbol.name] = symbol
                }
            }
        symbols = found.values.sortedBy { it.name }
        indexedRoot = root.absolutePath
        indexedInMillis = System.currentTimeMillis() - started
        lastScannedFiles = scanned
    }

    @Volatile
    var lastScannedFiles: Int = 0
        private set

    private fun extract(file: File, text: String): List<WorkspaceSymbol> {
        val ext = file.extension.lowercase()
        val patterns = PATTERNS[ext] ?: PATTERNS["kt"]!!
        val out = ArrayList<WorkspaceSymbol>(32)
        text.lineSequence().forEach { raw ->
            val line = raw
            if (line.isBlank() || line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEach
            for ((regex, kind) in patterns) {
                val m = regex.find(line) ?: continue
                val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
                if (name.length < 2 || !name[0].isJavaIdentifierStart()) continue
                out.add(
                    WorkspaceSymbol(
                        name = name,
                        kind = kind,
                        filePath = file.absolutePath,
                        detail = "其他文件 · ${file.name}",
                        weight = if (kind == CompletionKind.CLASS) 9 else 8
                    )
                )
                break
            }
        }
        return out
    }

    companion object {
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "dart", "py", "ts", "tsx", "js", "jsx", "c", "cc", "cpp", "cxx",
            "h", "hpp", "go", "rs", "swift", "php", "rb", "cs", "lua"
        )
        val IGNORED_DIRS = setOf(
            ".git", "build", ".gradle", ".gradle-tmp", ".idea", "node_modules", ".dart_tool",
            "target", "dist", "out", ".cxx", "__pycache__", ".kotlin", "vendor", "Pods"
        )
        const val MAX_FILES = 500
        const val MAX_BYTES = 256 * 1024
        const val MAX_SYMBOLS = 4000


        /** 行级声明正则（组 1 = 名字）。缩进限制为「顶层或类内一层」，避免把局部变量误当公共符号。 */
        private val PATTERNS: Map<String, List<Pair<Regex, CompletionKind>>> = run {
            val kotlin = listOf(
                Regex("""^\s{0,4}(?:public|private|internal|protected|abstract|open|sealed|data|final|annotation|enum|value)?\s*class\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,4}(?:public|private|internal)?\s*(?:sealed\s+)?interface\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,4}(?:public|private|internal)?\s*(?:data\s+)?object\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,4}(?:public|private|internal|protected|suspend|inline|operator|override|abstract|open|tailrec|external)?\s*fun\s+(?:<[^>]*>\s*)?(\w+)""") to CompletionKind.FUNCTION,
                Regex("""^\s{0,4}(?:public|private|internal|protected|const|lateinit|override)?\s*val\s+(\w+)""") to CompletionKind.PROPERTY,
                Regex("""^\s{0,4}(?:public|private|internal|protected|const|lateinit|override)?\s*var\s+(\w+)""") to CompletionKind.PROPERTY
            )
            val java = listOf(
                Regex("""^\s{0,4}(?:public|private|protected|abstract|final|static|sealed|non-sealed)?\s*(?:class|interface|enum|record)\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,4}(?:public|private|protected|static|final|synchronized|abstract|native)?\s*[\w<>\[\],.\s]+\s+(\w+)\s*\(""") to CompletionKind.FUNCTION
            )
            val dart = listOf(
                Regex("""^\s{0,2}(?:abstract\s+)?(?:class|mixin|enum|extension)\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,2}(?:Future<[^>]*>|void|[\w<>,\?\s]+)\s+(\w+)\s*\(""") to CompletionKind.FUNCTION,
                Regex("""^\s{0,2}(?:final|const|var|late)\s+(\w+)""") to CompletionKind.PROPERTY
            )
            val python = listOf(
                Regex("""^class\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^def\s+(\w+)""") to CompletionKind.FUNCTION,
                Regex("""^([A-Za-z_]\w*)\s*=""") to CompletionKind.CONSTANT
            )
            val ts = listOf(
                Regex("""^\s{0,2}(?:export\s+)?(?:abstract\s+)?(?:class|interface|enum|type)\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s{0,2}(?:export\s+)?(?:async\s+)?function\s+(\w+)""") to CompletionKind.FUNCTION,
                Regex("""^\s{0,2}(?:export\s+)?(?:const|let|var)\s+(\w+)""") to CompletionKind.VARIABLE
            )
            val c = listOf(
                Regex("""^\s*(?:typedef\s+)?(?:struct|union|enum|class)\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s*#define\s+(\w+)""") to CompletionKind.CONSTANT,
                Regex("""^\s*[A-Za-z_][\w\s\*]*\s+(\w+)\s*\([^;]*\)\s*\{?""") to CompletionKind.FUNCTION
            )
            val go = listOf(
                Regex("""^func\s+(?:\([^)]*\)\s*)?(\w+)""") to CompletionKind.FUNCTION,
                Regex("""^type\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^(?:var|const)\s+(\w+)""") to CompletionKind.VARIABLE
            )
            val rust = listOf(
                Regex("""^\s*(?:pub\s+)?fn\s+(\w+)""") to CompletionKind.FUNCTION,
                Regex("""^\s*(?:pub\s+)?(?:struct|enum|trait|union)\s+(\w+)""") to CompletionKind.CLASS,
                Regex("""^\s*(?:pub\s+)?(?:const|static)\s+(\w+)""") to CompletionKind.CONSTANT
            )
            val script = listOf(
                Regex("""^\s*(?:function\s+)?(\w+)\s*\(\)\s*\{""") to CompletionKind.FUNCTION
            )
            mapOf(
                "kt" to kotlin, "kts" to kotlin, "java" to java, "dart" to dart, "py" to python,
                "ts" to ts, "tsx" to ts, "js" to ts, "jsx" to ts, "c" to c, "cc" to c, "cpp" to c,
                "cxx" to c, "h" to c, "hpp" to c, "go" to go, "rs" to rust, "sh" to script
            )
        }
    }
}
