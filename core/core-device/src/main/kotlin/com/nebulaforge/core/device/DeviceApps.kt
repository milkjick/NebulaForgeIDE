package com.nebulaforge.core.device

/**
 * 手机后台软件（应用进程）读取与解析。
 *
 * 为什么要单独做成一层纯函数：`dumpsys activity oom` 的输出格式随 Android 版本与厂商 ROM 变化
 * （列宽、oomAdj 是数字还是 fore/vis/cch 单词、有没有 PERS 前缀），而「现在谁在前台、谁退到后台、
 * 谁被缓存待回收」恰恰是开发调试的关键信息（自家 App 被系统杀掉 / 转后台后被冻结 / 闪退）。
 *
 * 职责划分：
 *  - [DeviceAppsParser.parseProcessDump]：解析 `dumpsys activity oom`（信息最全：oomAdj + procState + PSS）；
 *  - [DeviceAppsParser.parseProcessList]：解析 `ps -A -o USER,PID,RSS,NAME`（dumpsys 被限制时的兜底）；
 *  - [DeviceAppsParser.foregroundPackage]：从 `dumpsys window` / `dumpsys activity activities` 取前台包名；
 *  - [DeviceAppsParser.labelOf]：把 oomAdj / procState 翻译成人话状态；
 *  - [DeviceAppsParser.aggregate]：把「进程」归并成用户关心的「软件」（一个 App 常有多个进程）。
 *
 * 判定优先级（此处最容易做错）：前台 / 可见这类**语义**来自 oomAdj 与 procState；
 * 而 `cached=true` / `empty=true` 只表示「这个进程里当前没有活动组件」，**不能**拿来盖掉前台语义
 * —— 否则会出现「正在前台显示、用户正在用的 App 被标成空进程」这种一眼假的误报。
 */
data class DeviceAppProcess(
    val pid: Int,
    val processName: String,
    /** 进程名去掉 `:xxx` 后缀得到的包名。 */
    val packageName: String,
    val uid: String = "",
    val adj: String = "",
    val procState: String = "",
    val reason: String = "",
    val cached: Boolean = false,
    val empty: Boolean = false,
    /** lastPss，单位 MB；无数据为 0。 */
    val pssMb: Double = 0.0
) {
    val stateLabel: String get() = DeviceAppsParser.labelOf(adj, reason, procState, cached, empty)
    val foreground: Boolean get() = stateLabel == DeviceAppsParser.FOREGROUND

    /** 一行摘要（供 UI 与 AI 上下文复用）。 */
    fun line(): String = buildString {
        append(packageName)
        if (processName != packageName) append(" [").append(processName.substringAfter(':', "").ifBlank { processName }).append(']')
        append("  pid=").append(pid)
        append("  ").append(stateLabel)
        if (adj.isNotBlank()) append("  adj=").append(adj)
        if (pssMb > 0) append("  ").append(String.format("%.0f", pssMb)).append("MB")
    }
}

/** 归并后的「一个软件」（可能含多个进程）。 */
data class DeviceApp(
    val packageName: String,
    val processes: List<DeviceAppProcess>,
    val label: String = ""
) {
    val stateLabel: String get() = DeviceAppsParser.bestLabel(processes.map { it.stateLabel })
    val pid: Int get() = processes.firstOrNull { it.pid > 0 }?.pid ?: 0
    val memoryMb: Double get() = processes.sumOf { it.pssMb }
    val foreground: Boolean get() = stateLabel == DeviceAppsParser.FOREGROUND

    fun line(): String = buildString {
        append(if (label.isBlank()) packageName else "$label（$packageName）")
        append(" · ").append(stateLabel)
        if (pid > 0) append(" · pid=").append(pid)
        if (processes.size > 1) append(" · ").append(processes.size).append(" 个进程")
        if (memoryMb > 0) append(" · ").append(String.format("%.0f", memoryMb)).append("MB")
    }
}

/** 一次扫描结果。 */
data class DeviceAppSnapshot(
    val foregroundPackage: String?,
    val processes: List<DeviceAppProcess>,
    val apps: List<DeviceApp>,
    /** 数据来源说明，便于排查「为什么这次没有状态信息」。 */
    val source: String
)

object DeviceAppsParser {

    const val NONE = "未运行"
    const val FOREGROUND = "前台"
    const val FOREGROUND_SERVICE = "前台服务"
    const val VISIBLE = "可见（后台）"
    const val PERCEPTIBLE = "可感知（后台）"
    const val SERVICE = "服务（后台）"
    const val PERSISTENT = "常驻（后台）"
    const val CACHED = "缓存（后台，可被回收）"
    const val BACKGROUND = "后台"

    /** 状态优先级：数字越小越“靠前”，用于多进程归并时取最能代表该软件的状态。 */
    private val precedence = listOf(
        FOREGROUND, FOREGROUND_SERVICE, VISIBLE, PERCEPTIBLE, SERVICE, PERSISTENT, BACKGROUND, CACHED, NONE
    )

    fun bestLabel(labels: List<String>): String =
        labels.filter { it != NONE }.minByOrNull { precedence.indexOf(it).let { i -> if (i < 0) precedence.size else i } }
            ?: NONE

    /** 判定优先级见文件头注释：先看 procState / oomAdj 的语义，最后才用 cached/empty 兜底。 */
    fun labelOf(adj: String, reason: String, procState: String, cached: Boolean, empty: Boolean): String {
        val a = adj.lowercase()
        val r = reason.lowercase()
        val st = procState.uppercase()
        return when {
            st == "TOP" || r.contains("top-activity") -> FOREGROUND
            st == "FGS" || st == "FGSERVICE" || r.contains("fg-service") -> FOREGROUND_SERVICE
            a == "fore" || a == "fg" || a == "top" || a == "vis" ||
                st in setOf("BTOP", "IMPF", "IMPB", "BFGS") -> VISIBLE
            a in setOf("percept", "prcp", "perceptible", "prcpsvc") -> PERCEPTIBLE
            a.startsWith("cch") || a == "prev" || a == "-10000" ||
                st in setOf("CEM", "CACC", "CRE", "LAST") -> CACHED
            a in setOf("svc", "psvc", "snr") -> SERVICE
            a == "pers" -> PERSISTENT
            cached || empty -> CACHED
            else -> BACKGROUND
        }
    }

    // `Proc #33: fore  TOP  LCM t: 0 22178:com.foo/u0a123 (top-activity)`
    // `PERS #84: pers   F/ /PER  LCMN  t: 0 3141:system/1000 (fixed)`
    // oomAdj 在不同 ROM 上可能是单词（fore/vis/cch）也可能是数字（-10000/1001）。
    private val record = Regex(
        """(?:PERS|Proc|SVC)\s+#\s*\d+:\s+(\S+)\s+.*?\st:\s*\d+\s+(\d+):([^\s/]+)(?:/(\S+))?\s*\((.*)\)"""
    )
    private val stateLine = Regex("""state:\s*cur=(\S+)""")
    private val cachedLine = Regex("""cached=(true|false)""")
    private val emptyLine = Regex("""empty=(true|false)""")
    private val pssLine = Regex("""lastPss=([0-9.]+)\s*(KB|MB|GB)?""", RegexOption.IGNORE_CASE)

    /**
     * 解析 `dumpsys activity oom`。
     *
     * 结构：每个进程一段，首行是 `Proc #n: <adj> <flag/state> <schedGroup> t: <mru> <pid>:<name>/<uid> (<reason>)`，
     * 后续几行给出 `oom:` / `state:` / `cached=` / `empty=`。按「行内能匹配到进程头」来分段。
     */
    fun parseProcessDump(text: String): List<DeviceAppProcess> {
        val out = mutableListOf<DeviceAppProcess>()
        var adj = ""
        var pid = 0
        var name = ""
        var uid = ""
        var reason = ""
        var cached = false
        var empty = false
        var procState = ""
        var pss: Double? = null

        fun flush() {
            if (pid > 0 || name.isNotEmpty()) {
                out += DeviceAppProcess(
                    pid = pid,
                    processName = name,
                    packageName = name.substringBefore(':'),
                    uid = uid,
                    adj = adj,
                    procState = procState,
                    reason = reason,
                    cached = cached,
                    empty = empty,
                    pssMb = pss ?: 0.0
                )
            }
            adj = ""; pid = 0; name = ""; uid = ""; reason = ""; cached = false; empty = false; procState = ""; pss = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val m = record.find(line)
            if (m != null && line.contains("#")) {
                flush()
                adj = m.groupValues[1]
                pid = m.groupValues[2].toIntOrNull() ?: 0
                name = m.groupValues[3]
                uid = m.groupValues[4]
                reason = m.groupValues[5]
                return@forEach
            }
            if (pid == 0 && name.isEmpty()) return@forEach
            stateLine.find(line)?.let { procState = it.groupValues[1] }
            cachedLine.find(line)?.let { cached = it.groupValues[1] == "true" }
            emptyLine.find(line)?.let { empty = it.groupValues[1] == "true" }
            pssLine.find(line)?.let { mm ->
                val v = mm.groupValues[1].toDoubleOrNull() ?: 0.0
                pss = when (mm.groupValues[2].uppercase()) {
                    "KB" -> v / 1024.0
                    "GB" -> v * 1024.0
                    else -> v
                }
            }
        }
        flush()
        return out
    }

    /**
     * 解析 `ps` 输出（兜底通道）。
     *
     * 兼容两种形态：带表头（`USER PID RSS NAME` 或 `USER PID PPID ... NAME`，按表头列位取字段）
     * 与不带表头（按「第一个数字字段是 PID、最后一个字段是 NAME」启发式取）。
     */
    fun parseProcessList(text: String): List<DeviceAppProcess> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        var pidIdx = 1
        var nameIdx = -1
        var userIdx = 0
        var start = 0
        val header = lines.first().split(Regex("\\s+"))
        if (header.any { it.equals("PID", true) }) {
            pidIdx = header.indexOfFirst { it.equals("PID", true) }
            nameIdx = header.indexOfFirst { it.equals("NAME", true) || it.equals("CMD", true) }
            userIdx = header.indexOfFirst { it.equals("USER", true) }
            start = 1
        }
        val out = mutableListOf<DeviceAppProcess>()
        for (i in start until lines.size) {
            val t = lines[i].split(Regex("\\s+"))
            val pid = t.getOrNull(pidIdx)?.toIntOrNull() ?: t.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull() ?: continue
            if (pid <= 0) continue
            val name = if (nameIdx >= 0 && nameIdx < t.size) t[nameIdx] else t.last()
            if (name.startsWith("[") || name == "ps") continue
            out += DeviceAppProcess(
                pid = pid,
                processName = name,
                packageName = name.substringBefore(':'),
                uid = if (userIdx in t.indices) t[userIdx] else "",
                adj = "",
                procState = "",
                reason = "ps"
            )
        }
        return out
    }

    private val focusRe = Regex("""mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.]+)/""")
    private val focusedAppRe = Regex("""mFocusedApp=ActivityRecord\{[^}]*?\s([A-Za-z0-9_.]+)/""")
    private val resumedRe = Regex("""mResumedActivity:?\s*ActivityRecord\{[^}]*?\s([A-Za-z0-9_.]+)/""")
    private val topResumedRe = Regex("""topResumedActivity=ActivityRecord\{[^}]*?\s([A-Za-z0-9_.]+)/""")

    /**
     * 前台包名：优先窗口焦点（最贴近「用户正在看谁」），再退到 Activity 栈顶。
     * 注意输入都来自 shell（uid=2000），在 Android 10+ 上仍可读，普通应用身份读不到这些信息。
     */
    fun foregroundPackage(windowDump: String, activityDump: String): String? {
        focusRe.find(windowDump)?.groupValues?.get(1)?.let { return it }
        focusedAppRe.find(windowDump)?.groupValues?.get(1)?.let { return it }
        topResumedRe.find(activityDump)?.groupValues?.get(1)?.let { return it }
        resumedRe.find(activityDump)?.groupValues?.get(1)?.let { return it }
        return null
    }

    /** 厂商/系统包前缀：默认视图里过滤掉，避免「后台软件」列表被系统进程淹没。 */
    private val systemPrefixes = listOf(
        "android", "com.android.", "com.google.", "com.huawei.", "com.hihonor.", "com.sec.android",
        "com.samsung.", "com.miui", "com.xiaomi.", "com.oppo", "com.coloros", "com.oplus",
        "com.vivo", "com.bbk.", "com.mediatek.", "com.qualcomm", "org.codeaurora", "com.oneplus.",
        "vendor.", "com.qti."
    )

    /** 看起来是不是「系统自带」（用于默认过滤；判断很宽松，只影响展示不影响到判断）。 */
    fun isSystemPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        if (p == "android" || p.startsWith("android.")) return true
        if (p.startsWith("system") || p.startsWith("com.aurora")) return true
        return systemPrefixes.any { p == it.trimEnd('.') || p.startsWith(it) }
    }

    /** 是不是应用进程（排除内核线程 `[xxx]`、init、logd 之类的非应用进程）。 */
    fun isAppProcess(process: DeviceAppProcess): Boolean {
        val n = process.processName
        if (n.startsWith("[") || n.startsWith("/")) return false
        return n.contains('.') || n.contains(':')
    }

    /**
     * 把进程归并成软件列表。
     *
     * @param includeSystem true = 连系统应用一起给（排查系统行为时用）；false = 只看第三方。
     * @param labels 可选：包名 → 应用名（由宿主用 PackageManager 查好传进来，core 层不依赖 Android）。
     * @param knownPackages 可选：只保留这里面的包名。只在**兜底通道**（`ps`，拿不到 oomAdj/procState，
     *        会把 `media.extractor` 这类原生进程也混进来）时使用：宿主把「已安装应用集合」传进来当过滤器。
     */
    fun aggregate(
        processes: List<DeviceAppProcess>,
        foregroundPackage: String? = null,
        includeSystem: Boolean = false,
        labels: Map<String, String> = emptyMap(),
        knownPackages: Set<String> = emptySet()
    ): List<DeviceApp> {
        val usable = processes.filter {
            it.pid > 0 && isAppProcess(it) && (knownPackages.isEmpty() || it.packageName in knownPackages)
        }
        val grouped = usable.groupBy { it.packageName }
        val out = grouped.map { (pkg, list) ->
            DeviceApp(pkg, list.sortedBy { it.pid }, labels[pkg].orEmpty())
        }.filter { includeSystem || !isSystemPackage(it.packageName) }
        return out.sortedWith(
            compareBy(
                { precedence.indexOf(it.stateLabel).let { i -> if (i < 0) precedence.size else i } },
                { if (it.packageName == foregroundPackage) 0 else 1 },
                { it.packageName }
            )
        )
    }
}
