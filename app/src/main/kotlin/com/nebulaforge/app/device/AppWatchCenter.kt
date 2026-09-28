package com.nebulaforge.app.device

import android.content.Context
import com.nebulaforge.core.device.DeviceAppSnapshot
import com.nebulaforge.core.device.DeviceAppsParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次状态变化事件。 */
data class AppWatchEvent(val atMillis: Long, val kind: String, val detail: String) {
    fun line(): String = "${AppWatchCenter.clock(atMillis)}  $kind  $detail"
}

/**
 * 手机后台软件扫描 + 「盯住某个软件」的持续监听。
 *
 * 为什么要有独立监听而不是让 AI 每次现抓：调试时真正关心的是**变化**
 * （「刚点完按钮它转后台了没」「它是不是被系统杀了」「这段时间它有没有写异常日志」），
 * 而每次现抓只能看到某一瞬间，还会因为抓取间隔太长而漏掉过程。
 *
 * 设计要点：
 *  - 单例 + 后台线程轮询（默认 4s），与 UI 面板、AI 工具**共用同一份状态**，谁先开都能看到；
 *  - 监听目标是「包名」，按项目记住（换项目自动切到该项目上次盯的软件）；
 *  - 每轮把「状态变化」记成事件（启动 / 转前台 / 转后台 / 被杀 / 崩溃），并把目标进程的
 *    logcat 增量收进环形缓冲；进程消失时若日志里出现 `FATAL EXCEPTION` / `ANR in <pkg>`
 *    就判定为崩溃而不是「正常退出」——这是「闪退了但不知道什么时候闪的」的关键线索；
 *  - 所有 shell 都经调用方传入的 exec（宿主会做权限与审计），本类不自己摸设备。
 */
object AppWatchCenter {

    private const val POLL_MS = 4_000L
    private const val EVENTS_MAX = 200
    private const val LOGS_MAX = 500
    private const val PREFS = "nebula_app_watch"

    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stampFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun clock(at: Long = System.currentTimeMillis()): String = synchronized(clockFmt) { clockFmt.format(Date(at)) }

    /** 包名合法性：只允许 JVM 包名/进程名允许的字符，避免把用户输入拼进 shell 造成注入。 */
    private val pkgRe = Regex("""[A-Za-z0-9_]{1,64}(?:\.[A-Za-z0-9_]{1,64})+""")
    fun isValidPackage(pkg: String): Boolean = pkgRe.matches(pkg.trim())

    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile var target: String = ""
        private set
    @Volatile private var targetProject: String? = null
    @Volatile private var lastLabel: String = DeviceAppsParser.NONE
    @Volatile private var lastForeground: String? = null
    @Volatile private var lastSnapshot: DeviceAppSnapshot? = null
    @Volatile private var lastError: String = ""
    @Volatile private var startedAt = 0L
    @Volatile private var pollCount = 0
    @Volatile private var lastPollAt = 0L
    private val events = java.util.ArrayDeque<AppWatchEvent>()
    private val logs = java.util.ArrayDeque<String>()
    @Volatile private var lastLogStamp = ""

    fun isRunning(): Boolean = running
    fun pollCountValue(): Int = pollCount
    fun targetPackage(): String = target
    fun foregroundPackage(): String? = lastForeground
    fun currentLabel(): String = lastLabel
    fun snapshot(): DeviceAppSnapshot? = lastSnapshot

    // ------------------------------------------------------------------ 读取

    /**
     * 一次性扫描后台软件。
     *
     * 两条通道：主通道 `dumpsys activity oom`（有 oomAdj / procState / PSS，能分清前台/可见/服务/缓存）；
     * 兜底 `ps`（拿不到状态，只能列进程，因此用 [installed] 过滤掉原生进程）。
     */
    fun scan(
        exec: (String, Long) -> String,
        includeSystem: Boolean = false,
        installed: Set<String> = emptySet()
    ): DeviceAppSnapshot {
        val cmd = "dumpsys activity oom 2>/dev/null; echo '---WIN---'; " +
            "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'; " +
            "echo '---ACT---'; dumpsys activity activities 2>/dev/null | grep -m2 -E 'topResumedActivity|mResumedActivity'"
        val raw = exec(cmd, 25_000L)
        val oom = raw.substringBefore("---WIN---")
        val win = raw.substringAfter("---WIN---", "").substringBefore("---ACT---")
        val act = raw.substringAfter("---ACT---", "")
        var source = "dumpsys activity oom"
        var procs = DeviceAppsParser.parseProcessDump(oom)
        if (procs.isEmpty()) {
            source = "ps（兜底：dumpsys 被限制，拿不到前后台状态）"
            procs = DeviceAppsParser.parseProcessList(exec("ps -A -o USER,PID,RSS,NAME 2>/dev/null", 20_000L))
        }
        val fg = DeviceAppsParser.foregroundPackage(win, act)
        val labels = appLabels(procs.map { it.packageName }.toSet())
        val apps = DeviceAppsParser.aggregate(
            procs,
            foregroundPackage = fg,
            includeSystem = includeSystem,
            labels = labels,
            knownPackages = if (source.startsWith("ps")) installed else emptySet()
        )
        val snap = DeviceAppSnapshot(fg, procs, apps, source)
        lastSnapshot = snap
        lastForeground = fg
        return snap
    }

    /** 应用名映射（宿主用 PackageManager 查；查不到就退回包名，绝不编造）。 */
    private var labelProvider: ((Set<String>) -> Map<String, String>)? = null

    /** 由宿主（Application/面板）注入 PackageManager 查询能力：core 层不依赖 Android，查名放在 app 层。 */
    fun installLabelProvider(provider: (Set<String>) -> Map<String, String>) {
        labelProvider = provider
    }

    private fun appLabels(installed: Set<String>): Map<String, String> =
        if (installed.isEmpty()) emptyMap()
        else runCatching { labelProvider?.invoke(installed).orEmpty() }.getOrDefault(emptyMap())

    /** 渲染成 AI/用户可读文本。 */
    fun render(snapshot: DeviceAppSnapshot, limit: Int = 60): String = buildString {
        append("前台：").append(snapshot.foregroundPackage ?: "（未知）").append('\n')
        append("数据来源：").append(snapshot.source).append('\n')
        append("共 ").append(snapshot.processes.size).append(" 个进程 / ").append(snapshot.apps.size).append(" 个软件\n")
        snapshot.apps.take(limit).forEach { append("  ").append(it.line()).append('\n') }
        if (snapshot.apps.size > limit) append("  …还有 ").append(snapshot.apps.size - limit).append(" 个\n")
    }

    // ------------------------------------------------------------------ 监听

    /** 开始盯住某个包名。重复调用同一目标 = 幂等（已在跑就直接返回状态）。 */
    fun start(context: Context, exec: (String, Long) -> String, pkg: String, projectPath: String?): String {
        val clean = pkg.trim()
        if (!isValidPackage(clean)) return "包名不合法：$clean（应形如 com.example.app）"
        if (running && target == clean) return statusText()
        stopInternal()
        target = clean
        targetProject = projectPath
        lastLabel = DeviceAppsParser.NONE
        lastLogStamp = ""
        lastError = ""
        startedAt = System.currentTimeMillis()
        pollCount = 0
        synchronized(events) { events.clear() }
        synchronized(logs) { logs.clear() }
        setTarget(context, projectPath, clean)
        running = true
        val t = Thread({ loop(exec) }, "nebula-app-watch").apply { isDaemon = true }
        worker = t
        t.start()
        return "已开始监听 $clean（每 ${POLL_MS / 1000}s 一轮；状态变化、被杀、崩溃与目标日志都会记录）。"
    }

    fun stop(): String {
        if (!running) return "当前没有在监听。"
        val pkg = target
        stopInternal()
        return "已停止监听 $pkg（共 $pollCount 轮）。"
    }

    private fun stopInternal() {
        running = false
        runCatching { worker?.interrupt() }
        worker = null
    }

    fun statusText(): String = buildString {
        if (!running) {
            append("监听未开启")
            if (target.isNotBlank()) append("（上次目标：").append(target).append("，状态：").append(lastLabel).append("）")
            append('。')
        } else {
            append("正在监听 ").append(target)
            append(" · 当前状态：").append(lastLabel)
            append(" · 已轮询 ").append(pollCount).append(" 轮")
            append(" · 已运行 ").append((System.currentTimeMillis() - startedAt) / 1000).append("s")
            if (lastPollAt > 0) append(" · 最近一轮 ").append(clock(lastPollAt))
        }
        if (lastError.isNotBlank()) append("\n最近错误：").append(lastError)
    }

    fun eventsText(limit: Int = 40): String {
        val list = synchronized(events) { events.toList() }
        if (list.isEmpty()) return if (running) "暂无状态变化（目标一直未变化）。" else "暂无事件。"
        return list.takeLast(limit).joinToString("\n") { it.line() }
    }

    fun logsText(limit: Int = 120): String {
        val list = synchronized(logs) { logs.toList() }
        if (list.isEmpty()) return "暂无目标日志。"
        return list.takeLast(limit).joinToString("\n")
    }

    fun events(): List<AppWatchEvent> = synchronized(events) { events.toList() }
    fun logs(): List<String> = synchronized(logs) { logs.toList() }

    private fun addEvent(kind: String, detail: String) {
        synchronized(events) {
            events.addLast(AppWatchEvent(System.currentTimeMillis(), kind, detail))
            while (events.size > EVENTS_MAX) events.removeFirst()
        }
    }

    private fun addLogs(lines: List<String>) {
        synchronized(logs) {
            lines.forEach { logs.addLast(it) }
            while (logs.size > LOGS_MAX) logs.removeFirst()
        }
    }

    private fun loop(exec: (String, Long) -> String) {
        while (running) {
            val t0 = System.currentTimeMillis()
            try {
                pollOnce(exec)
            } catch (t: InterruptedException) {
                return
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
            pollCount++
            lastPollAt = System.currentTimeMillis()
            val spent = System.currentTimeMillis() - t0
            val sleep = (POLL_MS - spent).coerceAtLeast(500L)
            try {
                Thread.sleep(sleep)
            } catch (t: InterruptedException) {
                return
            }
        }
    }

    private fun pollOnce(exec: (String, Long) -> String) {
        val pkg = target
        val raw = exec(
            "dumpsys activity oom 2>/dev/null; echo '---WIN---'; " +
                "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'",
            20_000L
        )
        val oom = raw.substringBefore("---WIN---")
        val win = raw.substringAfter("---WIN---", "")
        val procs = DeviceAppsParser.parseProcessDump(oom)
        val mine = procs.filter { it.packageName == pkg }
        val label = DeviceAppsParser.bestLabel(mine.map { it.stateLabel })
        val pid = mine.firstOrNull { it.pid > 0 }?.pid

        // 目标进程的增量日志（顺带用于判定「是崩溃还是正常退出」）。
        val fresh = if (pid != null) {
            val out = exec("logcat -d -t 250 --pid=$pid -v time 2>/dev/null", 15_000L)
            val all = out.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
            // `logcat -d` 首行常是 `--------- beginning of main` 这种无时间戳横幅；
            // 有带时间戳的行时丢掉横幅，否则增量去重会把横幅当成新日志反复收进环形缓冲。
            val lines = all.filter { stampOf(it) != null }.ifEmpty { all }
            val ts = lines.mapNotNull { stampOf(it) }
            val after = if (lastLogStamp.isEmpty()) lines else {
                val idx = ts.indexOfLast { it <= lastLogStamp }
                if (idx >= 0) lines.drop(idx + 1) else lines
            }
            ts.lastOrNull()?.let { lastLogStamp = it }
            after
        } else emptyList()
        if (fresh.isNotEmpty()) addLogs(fresh)

        val previous = lastLabel
        if (label != previous) {
            when {
                previous == DeviceAppsParser.NONE && label != DeviceAppsParser.NONE ->
                    addEvent("启动", "$pkg 开始运行（$label，pid=${pid ?: 0}）")
                previous != DeviceAppsParser.NONE && label == DeviceAppsParser.NONE -> {
                    val crash = fresh.firstOrNull {
                        it.contains("FATAL EXCEPTION") || it.contains("beginning of crash") ||
                            it.contains("ANR in $pkg") || it.contains("$pkg died") || it.contains("Force finishing")
                    }
                    if (crash != null) addEvent("崩溃/被杀", "$pkg 进程消失，日志命中：${crash.take(160)}")
                    else addEvent("退出", "$pkg 进程已结束")
                }
                else -> addEvent("状态变化", "$pkg：$previous → $label")
            }
            lastLabel = label
        }
        lastForeground = DeviceAppsParser.foregroundPackage(win, "")
    }

    /** 从 `MM-dd HH:mm:ss.SSS ...` 形式的 logcat 行里取时间戳（用于增量去重）。 */
    private val stampRe = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)""")
    private fun stampOf(line: String): String? = stampRe.find(line)?.groupValues?.get(1)

    // ------------------------------------------------------------------ 项目级记忆

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(projectPath: String?) = "target::" + (projectPath ?: "")

    /** 该项目上次盯的软件（换项目自动切回来，省得每次重新输包名）。 */
    fun targetFor(context: Context, projectPath: String?): String =
        runCatching { prefs(context).getString(key(projectPath), "").orEmpty() }.getOrDefault("")

    fun setTarget(context: Context, projectPath: String?, pkg: String) {
        runCatching { prefs(context).edit().putString(key(projectPath), pkg).apply() }
    }
}
