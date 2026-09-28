package com.nebulaforge.app.build

import android.content.Context
import com.nebulaforge.core.environment.ProotPathMapper
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.ProblemMatcher
import com.nebulaforge.core.projectmodel.TaskDefinition
import com.nebulaforge.core.session.DiagnosticStore
import com.nebulaforge.core.session.IdeEvent
import com.nebulaforge.core.session.IdeSession
import com.nebulaforge.core.session.IdeSessionBus
import com.nebulaforge.core.session.SessionKind
import com.nebulaforge.core.session.SessionState
import com.nebulaforge.core.session.Severity
import com.nebulaforge.core.terminal.IdeTerminalSessionClient
import com.nebulaforge.core.toolchain.FlutterBuildShim
import com.nebulaforge.core.terminal.TerminalSessionManager
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 构建任务运行状态（单实例：面板上永远只展示「当前/最近一次」任务）。 */
data class TaskRunState(
    val running: Boolean = false,
    val taskId: String? = null,
    val label: String = "",
    /**
     * 产出这份输出的**工程根目录**。
     *
     * 构建输出跑在 pty 里，天然属于某个工程；而构建面板上的任务列表属于**当前打开的工程**。
     * 用户从工程 A 切到工程 B 的文件后，面板就会是「B 的任务列表 + A 的构建输出」，
     * 看起来就是「切换文件后输出不同步 / 对不上」。有了这个字段，面板才能标明输出归属、
     * 并给出「切到该工程」的入口。
     */
    val projectRoot: String = "",
    val sessionId: String? = null,
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    /** 进程退出码；null = 还没结束（或被 Ctrl-C 打断时为 130）。 */
    val exitCode: Int? = null,
    val success: Boolean? = null,
    val cancelled: Boolean = false,
    /** 从日志里匹配到的问题数（同步进「问题」面板）。 */
    val problemCount: Int = 0,
    val errorCount: Int = 0,
    /** 面向状态栏的一句话（中文）。 */
    val message: String = "就绪",
    /** 面板是否需要弹「构建完成」提示（消费后置 false）。 */
    val justFinished: Boolean = false
)

/**
 * VSCode 式「工作区任务」执行器：点构建 → 在 **pty 终端**里执行 tasks.json 的命令 →
 * 日志实时可见 → 编译诊断同步进「问题」面板。
 *
 * ## 为什么走 pty 而不是 ProcessBuilder
 * 1. 需求要求「复用现有终端」：同一个 [TerminalSessionManager] 会话既显示在构建面板，
 *    也能在「终端」工具窗里继续交互（VSCode 的做法）；
 * 2. 编译工具（Gradle、Flutter、clang）都会输出 **ANSI 颜色 / 进度条 / \r 刷新**，
 *    只有终端 emulator 能正确还原；用行管道收集会得到一堆 `\u001b[32m` 垃圾；
 * 3. 停止构建 = 向前台进程组发 SIGINT（写 `\u0003`），与用户在终端里按 Ctrl-C 完全一致，
 *    比 `Process.destroy()` 更接近真实 IDE 行为（gradle 会打印「Build cancelled」并清理 daemon）。
 *
 * ## 会话复用
 * 命令末尾**不 exit**，只打印退出标记；同一会话连续构建，终端里像 VSCode 一样顺序滚出多段日志，
 * 也不会把「终端」工具窗的会话列表刷屏（每次新建 pty 会多一条记录）。
 *
 * ## 输出采集
 * 从 emulator 的 `screen.transcriptText`（已解析 ANSI 的纯文本）做**增量 diff**，
 * 再逐行喂 [ProblemMatcher]。emulator 不存在时主动 [TerminalSession.initializeEmulator]，
 * 这样即使构建面板此刻不可见（用户切到别的工具窗），问题匹配依然工作。
 *
 * 线程：所有方法可从主线程调用；emulator 读取发生在 Sora/Termux 的屏幕更新回调（主线程）。
 */
/** 任务中心「历史任务」保留的最大条数（内存态，会话级）。 */
private const val HISTORY_LIMIT = 30

class WorkspaceTaskRunner(
    private val context: Context,
    private val bus: IdeSessionBus,
    private val diagnostics: DiagnosticStore
) {

    private val manager = TerminalSessionManager.get(context)

    /**
     * 构建前「工具链接线」：解析项目环境、同步 local.properties、产出需注入的变量与问题清单。
     * 见 [com.nebulaforge.core.toolchain.BuildEnvironmentPreparer]。
     */
    private val preparer = com.nebulaforge.core.toolchain.BuildEnvironmentPreparer(context)

    private val _state = MutableStateFlow(TaskRunState())
    val state: StateFlow<TaskRunState> = _state.asStateFlow()

    /**
     * 构建/运行历史（新在前，最多 [HISTORY_LIMIT] 条）。
     *
     * 任务中心的「历史任务」分组直接消费它。以前没有这份数据：`state` 只保留
     * 「当前/最近一次」，任务一结束就被下一次覆盖，用户回看「刚才那几次构建成没成、
     * 用了多久、问题多少」完全是空的。只存内存不落盘——历史属于会话级信息，
     * 重启后从头开始即可，不值得为它引入持久化与清理策略。
     */
    private val _history = MutableStateFlow<List<TaskRunState>>(emptyList())
    val history: StateFlow<List<TaskRunState>> = _history.asStateFlow()

    /** 从历史里移除一条（任务中心逐条删除用）。key 取 startedAt：同一毫秒几乎不可能两次。 */
    fun forgetHistory(startedAt: Long) {
        _history.value = _history.value.filterNot { it.startedAt == startedAt }
    }

    /** 日志纯文本行（去 ANSI），用于「复制日志 / AI 修复 / 交付审计」。 */
    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private var session: IdeSession? = null
    private var ptyId: String? = null
    private var projectRoot: File? = null
    private var workingDir: File? = null
    private var matchers: List<String> = emptyList()
    /** 当前执行对应的会话类型（决定事件总线里这条会话算构建还是运行）。 */
    private var sessionKind: SessionKind = SessionKind.BUILD
    private var lastTranscript: String = ""

    /**
     * **本次运行**的唯一结束标记 token。
     *
     * 真机取证：结束标记写成固定字面量时，pty 回滚缓冲里上一次构建残留的
     * `__NEBULA_TASK_EXIT__:1` 会被下一次 [harvest]（`lastTranscript` 被重置为 `""`
     * 后必然整体重扫屏幕）当成「本次已经结束」，于是任何工程、任何任务都在 ~1s 内被判成
     * 「构建失败（退出码 1）」—— 用户看到的现象正是「所有项目都不能编译构建了」。
     * 把 token 拼进标记后，旧标记天然失配，只有本次真正打印的那一行才会命中。
     */
    private var runToken = ""
    private var exitPattern = Regex(EXIT_MARKER + ":(-?\\d+)")
    private val seenProblems = HashSet<String>()

    /** 看门狗作用域：任务运行期间兜底重扫屏幕（见 [startWatchdog]）。 */
    private val watchdog = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 当前看门狗协程；每次运行只允许有一个（见 [startWatchdog]）。 */
    private var watchdogJob: kotlinx.coroutines.Job? = null

    /**
     * 准备工作作用域（IO 线程）：工具链解析、写 local.properties、起 guest 进程、读整屏回滚文本。
     * 这些操作都是秒级的，绝不能占着 Compose 主线程 —— 否则「点构建」就是一次掉帧到假死。
     */
    private val prep = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前任务所在的 pty 会话（看门狗轮询用）。 */
    private var ptySession: TerminalSession? = null

    /**
     * 当前任务的**执行器**（core-exec 的嵌入式 PTY 执行器）。
     *
     * 真机根因：Termux 0.118 的 `TerminalSession` 把 pty 输出投递到**主线程 Handler** 才进
     * `TerminalEmulator`，而运行面板的采集依赖 `emulator.screen.transcriptText` —— 这条链路在
     * 真机上不产出任何文本，于是「运行/构建」永远停在「运行中 Ns」且零输出（终端页空白同源）。
     * [com.nebulaforge.core.exec.TermuxCommandExecutor] 的 reader 跑在 IO 线程，自带 token 行与
     * 真实退出码，彻底绕开 emulator / 主线程 Handler。
     */
    private var execJob: kotlinx.coroutines.Job? = null
    private var executor: com.nebulaforge.core.exec.TermuxCommandExecutor? = null
    private val seenOutput = ArrayList<String>()
    private var finished = false

    // ------------------------------------------------------- 失败自愈（自动重试一次）
    /** 上一次执行的调用参数：失败自愈需要**原样**再跑一遍。 */
    private var lastRoot: File? = null
    private var lastTaskId: String? = null
    private var lastLabel: String = ""
    private var lastCommandLine: String = ""
    private var lastKind: SessionKind = SessionKind.BUILD
    private var lastCwd: File? = null
    private var lastEnv: Map<String, String> = emptyMap()
    private var lastMatchers: List<String> = emptyList()

    /**
     * 本次「用户发起」的执行里，失败自愈是否已经重试过。
     * 只允许自动重试一次：再失败就交回用户，避免脏缓存 + 必败脚本组合成死循环。
     */
    private var selfHealRetried = false

    /**
     * 「本次运行还活着」的落盘标记。
     *
     * 真机取证（本机 EMUI/HarmonyOS）：`dumpsys activity exit-info com.nebulaforge.app` 里连着一串
     * `reason=10 (USER REQUESTED) ... iAwareF[SystemManager]`，间隔只有几分钟 —— 本应用会被
     * 华为「应用启动/耗电管理」反复**强制停止**。而构建跑在**应用进程的子进程树**里
     * （proot → bash → flutter → dart/gradle），应用一被停止整棵树一起消失：
     * 日志停在半路（本机实测停在 `> Task :app:compileDebugKotlin`）、**不产生任何错误输出**，
     * 用户看到的就是「长时间卡在 build 阶段」。
     *
     * Android 的 force-stop 会杀掉该包**全部**进程，所以前台服务只能降低概率、无法杜绝
     * （见 [TaskForegroundService] 注释）。因此这里退一步做**可解释的失败**：运行开始写标记，
     * 正常结束删标记；下次冷启动若发现标记还在而进程已不在，就直接在输出面板里说明
     * 「上次构建被系统中断」并给出规避办法，而不是让用户对着空白/半截日志猜。
     */
    private val runMarker: File = File(context.filesDir, "build-run.marker")

    /** 是否已经提示过「本应用未加入电池优化白名单」（每次进程生命周期只提示一次，不刷屏）。 */
    private var killRiskHinted = false

    init {
        reportOrphanedRun()
    }

    /**
     * 当前任务所在的 pty 会话 id，**以 StateFlow 暴露**。
     *
     * 面板必须订阅它来挂载终端视图：`_state`（running/sessionId）是在 [ensurePty] **之前**
     * 置位的，等到 pty 真正创建出来时不会再有状态变化——如果面板只在
     * `LaunchedEffect(running, sessionId)` 里读一次 id，就永远拿不到新建的会话，
     * 表现为「任务跑完了（退出码 0）但运行面板一片空白，看不到任何输出」。
     */
    private val _activePty = MutableStateFlow<String?>(null)
    val activePty: StateFlow<String?> = _activePty.asStateFlow()

    /** 当前绑定的 pty 会话 id（一次性读取用；响应式挂载请用 [activePty]）。 */
    fun ptySessionId(): String? = ptyId

    fun isRunning(): Boolean = _state.value.running

    // ------------------------------------------------------------------ 执行

    /**
     * 执行一个任务。
     *
     * @return false 表示被拒绝（已有任务在跑）
     */
    /** 执行工作区任务（构建/测试，来自 `.vscode/tasks.json`）。 */
    fun run(root: File, task: TaskDefinition): Boolean = start(
        root = root,
        taskId = task.id,
        label = task.label,
        commandLine = task.commandLine(),
        kind = SessionKind.BUILD,
        cwd = task.cwd?.let { File(root, it) }?.takeIf { it.isDirectory } ?: root,
        env = task.env,
        matchers = task.problemMatcher
    )

    /**
     * 执行一条**任意命令行**（运行配置 / AI 运行按钮用）。
     *
     * 与 [run] 共用同一套 pty 会话、退出标记、Ctrl-C 停止与问题匹配 —— 这样「构建」和「运行」
     * 在终端里的行为完全一致（颜色、进度条、交互式确认都能正常显示），
     * 也让两个面板不会各写一份进程管理代码。
     *
     * @param kind 会话类型：`RUN` 会让运行面板/Logcat 链路把它当运行会话，问题面板则按构建处理
     */
    fun runCommand(
        root: File,
        label: String,
        commandLine: String,
        kind: SessionKind = SessionKind.RUN,
        cwd: File? = null,
        env: Map<String, String> = emptyMap(),
        matchers: List<String> = emptyList()
    ): Boolean = start(
        root = root,
        taskId = label,
        label = label,
        commandLine = commandLine,
        kind = kind,
        cwd = cwd?.takeIf { it.isDirectory } ?: root,
        env = env,
        matchers = matchers
    )

    /**
     * 真正启动一次执行。构建与运行都走这里，保证状态机、日志采集、停止语义只有一份实现。
     *
     * @return false 表示被拒绝（已有任务在终端里跑）
     */
    private fun start(
        root: File,
        taskId: String?,
        label: String,
        commandLine: String,
        kind: SessionKind,
        cwd: File,
        env: Map<String, String>,
        matchers: List<String>,
        autoRetry: Boolean = false
    ): Boolean {
        if (_state.value.running) return false
        projectRoot = root
        workingDir = cwd
        this.matchers = matchers
        sessionKind = kind
        lastTranscript = ""
        // 记下调用参数：失败自愈（transforms 脏缓存）要原样重跑一遍。
        lastRoot = root
        lastTaskId = taskId
        lastLabel = label
        lastCommandLine = commandLine
        lastKind = kind
        lastCwd = cwd
        lastEnv = env
        lastMatchers = matchers
        // 用户新发起的一次执行才重置自愈计数；自愈重试自己带 autoRetry=true，不会把计数冲掉,
        // 否则脏缓存 + 必败命令会无限自动重试。
        if (!autoRetry) selfHealRetried = false
        // 每次运行换一个 token（见 runToken 注释）：这是「旧结束标记误判」的结构性修复。
        runToken = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        exitPattern = Regex(Regex.escape(EXIT_MARKER) + Regex.escape(runToken) + ":(-?\\d+)")
        seenProblems.clear()
        finished = false
        // 阶段/退出码哨兵：上次构建遗留的文件必须先删掉，否则新构建会被**旧退出码**瞬间判成「已结束」。
        runCatching {
            FlutterBuildShim.stageFile(context, root).delete()
            FlutterBuildShim.rcFile(context, root).delete()
        }

        logRun("START kind=$kind label=$label cwd=${cwd.absolutePath} cmd=$commandLine")
        val sess = IdeSession(kind = kind)
        session = sess
        bus.register(sess, root.absolutePath)
        diagnostics.clearSession(sess.id)
        bus.state(sess, SessionState.Preparing("准备执行「$label」"))

        _state.value = TaskRunState(
            running = true,
            taskId = taskId,
            label = label,
            projectRoot = root.absolutePath,
            sessionId = sess.id,
            startedAt = System.currentTimeMillis(),
            message = "准备中…",
            justFinished = false
        )

        // 运行开始落盘「活着」标记 + 一次性告知被杀风险（见 [runMarker] / [reportOrphanedRun]）。
        writeRunMarker(kind, label, root, sess.id)
        hintKillRiskIfNeeded()
        // ★ 真机根因（2.12.101 用户实测「Flutter 构建成功后安装弹窗没弹出 / 要等很久」）：
        //   [startWatchdog] 定义了却**从来没有被调用**（全文件只有它的定义处引用）。于是
        //   ① shim 写下的退出码哨兵没人读，② 阶段哨兵进度、停滞告警全部失效。
        //   run.log 铁证：`[nb-flutter] ⏱ 阶段 done/完成（exit=0）（已 160s）`（构建真的成功了）
        //   之后**没有** `进程已退出` 行，65s 后被 AI 循环的空闲检测判成卡死并 stop()，
        //   最终落成 `FINISH exit= cancelled=true message=已停止` —— 而产物识别要求
        //   `success && !cancelled`，installable 的 APK 就此永远不会被 off 出来（无安装入口）。
        //   把看门狗接上，退出码就同时有「token 行」和「哨兵文件」两条独立来源，不再单点。
        startWatchdog()

        // 任务期间持有前台服务（+ 唤醒锁）。真机故障：用户点运行后切到别的应用，EMUI/Android
        // 把本进程整组压进 freezer cgroup 冻结（取证见 TaskForegroundService 注释），任务从此
        // 停在「运行中 Ns」且零输出，用户以为「运行没反应」。前台服务让进程脱离 cached 状态即可根治。
        // 这里同步调用（只是一个 binder 调用，微秒级）；失败也只退化为旧行为，绝不阻断构建。
        runCatching {
            TaskForegroundService.begin(
                context,
                if (kind == SessionKind.RUN) "正在运行：$label" else "正在构建：$label"
            )
        }

        // 重活（工具链解析写文件、新建 guest 进程、读整屏回滚文本）**一律离开主线程**。
        // 真机现象：点一下「构建」，IDE 界面就整片卡住不动（像 ANR），只能点「等待」。
        // 根因就是这条链路原先在 Compose 的主线程上同步跑 —— 起 proot guest、读整屏
        // transcriptText 都是秒级阻塞。这里只保证 _state.running **同步**置位（挡住重复点击），
        // 真正的准备工作交给 IO 线程；进度通过 StateFlow / 事件总线回抛给 UI。
        prep.launch { prepareAndLaunch(sess, label, root, commandLine, cwd, env) }
        return true
    }

    /**
     * [start] 的后半段：工具链接线 → 组装命令行 → 幂等获取 pty → 起看门狗。
     *
     * 全程运行在 [prep]（IO 线程）。任何异常都收敛成一次明确的失败状态，
     * 不会让面板永远停在「准备中…」。
     */
    private suspend fun prepareAndLaunch(
        sess: IdeSession,
        label: String,
        root: File,
        commandLine: String,
        cwd: File,
        env: Map<String, String>
    ) {
        try {
        // 先吐一行进度再干活：prepare() 里含工具链解析与 Gradle 版本本地化（解包 136MB 这种一次性
        // 操作），而命令回显排在 prepare() 之后 —— 早先这一步会让面板**长时间空白**，用户只知道
        // 「点了构建没有任何输出」。有了这一行，即便准备耗时也能立刻看到面板在工作。
        emitOutput("[nebula] 正在准备工具链（仅本地，不联网）…")
        // 「编译构建 → 工具链」接线：构建/运行前解析项目工具链，把 JAVA_HOME / ANDROID_SDK_ROOT /
        // GRADLE_USER_HOME / NDK / build-tools 注入 guest，并为 AGP 工程写出 local.properties。
        // 没有这一步，Android 项目必然停在 `SDK location not found`，而用户看不出是工具链没接上。
        // 优先级：任务里手写的 env > 工具链解析结果 > 终端基线环境。
        val prepared = runCatching { preparer.prepare(root) }.getOrNull()
        val effectiveEnv = if (prepared == null) {
            env
        } else {
            LinkedHashMap(prepared.env).apply { putAll(env) }
        }

        // 构建前置闸门。此前 [ToolchainDoctor.preflight] 在整个构建路径上**没有任何调用方**
        // （只被首次启动向导用过一次），这正是「工具链明明装了却编不过」看不出来的原因：
        // 缺件时 Gradle 只会给 `SDK location not found`、`Could not find tools.jar` 这类
        // 间接报错，用户无法判断是代码问题还是工具链没装全。
        // 这里补一次体检，并刻意满足两个约束：
        //  ① 只用**已持久化的快照**（[ToolchainManager.statuses]，零成本，不起 guest 进程），
        //     全量重探会为每个组件起一次 proot guest，真机上是几十秒级，绝不能加在构建启动前；
        //  ② 快照未覆盖全部构建必需组件时不给结论——此时「缺失」只代表**还没探过**，
        //     把它报成缺件就是造谣；宁可什么都不说。
        // 只作为前言展示，不阻断构建：判定口径再严也会有假阳性（例如用户手动配了非常规 SDK 路径）。
        val preflight = runCatching {
            val snapshot = com.nebulaforge.core.toolchain.ToolchainManager(context).statuses.value
            val essential = com.nebulaforge.app.onboarding.ToolchainProgress.ESSENTIAL
            if (essential.all { component -> snapshot.any { it.component == component } }) {
                com.nebulaforge.core.toolchain.ToolchainDoctor(context).preflight(root, snapshot)
            } else {
                null
            }
        }.getOrNull()

        // 工具链接线结果（注入的变量、写出的 local.properties、缺失组件）以 `printf` 前言的形式
        // 打进终端，而不是只丢进内部文本缓冲：用户看「输出」标签就能知道这次构建用的是哪个 SDK/JDK，
        // 也解释了为什么某些变量被注入。前言由终端回显捕获，仍会进 problemMatcher/日志链路，只此一份。
        val preamble = buildList {
            prepared?.notes?.forEach { add("[toolchain] $it") }
            prepared?.warnings?.forEach { add("[toolchain] ⚠ $it") }
            preflight?.let { check ->
                if (check.ready) {
                    add("[toolchain] ✅ 构建前置检查通过（JDK / Android SDK / 命令行工具链齐备）")
                } else {
                    add("[toolchain] ⛔ 构建前置检查未通过，缺少 ${check.blockers.size} 项：")
                    check.blockers.take(8).forEach { add("[toolchain]     · $it") }
                    if (check.blockers.size > 8) add("[toolchain]     · …还有 ${check.blockers.size - 8} 项")
                    add("[toolchain]    修复入口：设置 → 真实工具链探测 → 「一键修复 Android 构建前置环境」")
                }
                check.warnings.forEach { add("[toolchain] ⚠ $it") }
                if (check.commands.isNotEmpty()) {
                    add("[toolchain] 建议的手工修复命令：")
                    check.commands.forEach { add("[toolchain]     $ $it") }
                }
            }
        }

        // tasks.json 与 AI 工作台允许用户直接写 `flutter ...`。内置任务虽然已经生成
        // `nb-flutter ...`，但这些外部入口以前会绕过外壳，导致 /storage 工程再次直接执行
        // noexec 的 android/gradlew。统一在真正启动前归一化，保留命令其余参数与日志语义。
        val effectiveCommand = normalizeBuildCommand(root, commandLine)
        if (effectiveCommand != commandLine) {
            emitOutput("[nebula] 已将 Flutter 命令切换到本地构建外壳（公共存储工程使用可执行镜像）")
            logRun("NORMALIZE flutter->nb-flutter root=${root.absolutePath}")
        }
        emitOutput("$ " + effectiveCommand)
        bus.state(sess, SessionState.Running("正在执行「$label」"))

        // 任务执行：走嵌入式执行器（reader 在 IO 线程、自带 token 与真实退出码），与 pty/emulator
        // 解耦；输出由 `output` 流渲染（见 TaskOutputList）。终端页另有自己的交互会话，互不影响。
        launchInGuest(sess, label, effectiveCommand, cwd, effectiveEnv, preamble)
        } catch (t: Throwable) {
            emitOutput("[nebula] 准备构建失败：" + (t.message ?: t.javaClass.simpleName))
            finish(exitCode = null, cancelled = false, message = "准备构建失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * 统一处理外部入口的 Flutter 命令。
     *
     * 只改写命令开头或 shell 链接操作符后的可执行词，并跳过已经使用 nb-flutter 的命令，
     * 避免把外壳内部的 fallback 再递归改写。analyze/test 也经过外壳，但不会被强制加平台参数；
     * 外壳自身负责根据目标决定是否需要 Android 镜像。
     */
    private fun normalizeBuildCommand(root: File, commandLine: String): String {
        val publicStorage = root.absolutePath.startsWith("/storage/") ||
            root.absolutePath.startsWith("/sdcard/") || root.absolutePath.startsWith("/mnt/sdcard/")
        if (!publicStorage) return commandLine
        if (File(root, "pubspec.yaml").isFile) {
            if (commandLine.contains("nb-flutter")) return commandLine
            val flutterToken = Regex("(^|[;&|()]\\s*)flutter(?=\\s|$)")
            return flutterToken.replace(commandLine) { match ->
                match.value.removeSuffix("flutter") + "nb-flutter"
            }
        }
        val gradleProject = File(root, "gradlew").isFile ||
            File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile ||
            File(root, "build.gradle").isFile || File(root, "build.gradle.kts").isFile
        if (!gradleProject || commandLine.contains("nb-gradle")) return commandLine
        val invokesGradle = Regex("(^|[;&|()]\\s*)(sh\\s+)?\\.?/?gradlew(?=\\s|$)|(^|[;&|()]\\s*)gradle(?=\\s|$)")
            .containsMatchIn(commandLine)
        if (!invokesGradle) return commandLine
        return "NEBULA_SOURCE_ROOT=${TaskDefinition.shellToken(root.absolutePath)} " +
            "NEBULA_PROJECT_NAME=${TaskDefinition.shellToken(root.name)} nb-gradle " +
            TaskDefinition.shellToken(commandLine)
    }

    /**
     * 用嵌入式执行器跑一次任务（构建/运行统一入口）。
     *
     * - 命令文本只作为**脚本**落盘，PTY 里只键入一行 `. '<脚本>'`：规避 PTY 行缓冲短写导致的
     *   脚本截断（旧实现「只回显到 `printf '`、exit=125、marker 从未打印」的根因）。
     * - `guestAware = true` 时由启动时注入的 [com.nebulaforge.core.exec.GuestRuntime] 把命令包进
     *   proot「前缀对齐」guest，工具链（JAVA_HOME/ANDROID_HOME…）随之进 guest。
     * - 退出码来自执行器打印的 token 行，不依赖屏幕回滚文本，因此**不会**再出现
     *   「旧标记误判秒退」「跑完了还显示运行中」。
     */
    private fun launchInGuest(
        sess: IdeSession,
        label: String,
        commandLine: String,
        cwd: File,
        env: Map<String, String>,
        preamble: List<String>
    ) {
        val inner = buildString {
            if (preamble.isNotEmpty()) {
                append("printf '%s\\n' ")
                    .append(preamble.joinToString(" ") { TaskDefinition.shellToken(it) })
                    .append("; ")
            }
            append(commandLine)
        }
        val runner = com.nebulaforge.core.exec.TermuxCommandExecutor(
            com.nebulaforge.core.environment.Environment.resolveShell(context),
            guestAware = true
        )
        executor = runner
        execJob = prep.launch {
            var code: Int? = null
            try {
                runner.execute(inner, cwd, env).collect { event ->
                    when (event) {
                        is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line -> ingest(event.text)
                        is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> {
                            code = event.exitCode
                            ingest("[nebula] 进程已退出（code=${event.exitCode}，${event.durationMs} ms）")
                        }
                    }
                }
            } catch (t: Throwable) {
                logRun("ERROR " + (t.message ?: t.javaClass.name))
                emitOutput("[nebula] 启动失败：" + (t.message ?: t.javaClass.simpleName))
                // Flow/PTY 异常时不会有 Finished 事件，必须显式按失败结束，
                // 不能把 null 继续传给 finish 形成“状态未知”的假结束。
                if (code == null) code = 1
            }
            if (!finished) {
                finish(
                    exitCode = code,
                    cancelled = _state.value.cancelled,
                    message = null
                )
            }
        }
    }

    /**
     * 兜底轮询：任务是否结束靠**屏幕文本**里的结束标记判定，而屏幕刷新回调有可能正好漏掉
     * 「最后一行输出」（真机现象：命令其实早就跑完，问题面板也已经列出结果，但顶部一直显示
     * 「运行中 102s」并挂着停止按钮）。这里在任务存活期间定期重扫屏幕，标记一出现立即结束。
     *
     * [harvest] 是幂等的（屏幕文本没变会直接返回），因此轮询不会产生重复输出或重复诊断。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = watchdog.launch {
            var lastStage = ""
            var lastLines = _output.value.size
            var lastOutputAt = System.currentTimeMillis()
            var lastWarnAt = 0L
            var tick = 0
            while (_state.value.running && !finished) {
                delay(WATCHDOG_INTERVAL_MS)
                if (finished || !_state.value.running) break
                ptySession?.let { runCatching { harvest(it) } }

                // 阶段哨兵（约每 3s 读一次，走应用私有目录，**不依赖 pty 屏幕抽水**）。
                // 真机取证：面板日志来自终端 emulator 的屏幕文本，抽水发生在主线程；主线程被
                // 不可中断 I/O 卡住时面板会永远停在最后一行（用户看到的「卡在工程镜像」），
                // 而 guest 其实可能已在跑 Gradle。读哨兵文件即可还原真实进度。
                tick++
                if (tick % 4 == 0) {
                    val root = projectRoot
                    if (root != null) {
                        val st = runCatching { FlutterBuildShim.stageFile(context, root).readText().trim() }.getOrNull()
                        if (!st.isNullOrBlank() && st != lastStage) {
                            lastStage = st
                            emitOutput("[nebula] ⏱ 阶段：" + st.replace('\n', ' '))
                        }
                        val rc = runCatching { FlutterBuildShim.rcFile(context, root).readText().trim() }.getOrNull()
                        val code = rc?.toIntOrNull()
                        // 只有阶段推进到 `done` 才认退出码哨兵：nb-flutter 是**先写 .rc、后回传产物**
                        // （见 shim 的 `printf … > .rc` 与随后的 `nb_stage artifact`），过早结束会让
                        // 「构建成功」那一刻去搜产物时 APK 还躺在镜像目录里，用户看到的仍是
                        // 「构建成功但没有安装入口」。要求 stage=done 就排掉了这个窗口。
                        val stageDone = st?.startsWith("done") == true
                        if (code != null && stageDone) {
                            emitOutput("[nebula] 退出码哨兵：$code（结束判定不再只靠屏幕标记）")
                            finish(exitCode = code, cancelled = false, message = null)
                            break
                        }
                    }
                }

                // 停滞告警：只提示不杀进程（慢构建不该被误杀），但让「安静」与「卡死」可区分。
                val lines = _output.value.size
                val now = System.currentTimeMillis()
                if (lines != lastLines) {
                    lastLines = lines
                    lastOutputAt = now
                } else if (now - lastOutputAt >= STALL_SILENCE_MS && now - lastWarnAt >= STALL_SILENCE_MS) {
                    lastWarnAt = now
                    val ran = if (_state.value.startedAt > 0) (now - _state.value.startedAt) / 1000 else 0L
                    emitOutput(
                        "[nebula] ⚠ 已 ${(now - lastOutputAt) / 1000}s 没有新输出（阶段=" +
                            lastStage.substringBefore(' ').ifBlank { "?" } + "，已运行 ${ran}s）。" +
                            "真机已知：读 /storage 时可能卡在内核不可中断 I/O（D 状态），可点「停止」后重试。"
                    )
                }
            }
        }
    }

    /**
     * 幂等获取构建用 pty：可复用时复用（同一个终端里顺序滚日志），否则新建并把屏幕输出接进来。
     */
    private fun ensurePty(root: File, commandLine: String): TerminalSession? {
        val existing = ptyId?.let { manager.attach(it) }
        val alive = existing?.takeIf { runCatching { it.isRunning }.getOrDefault(false) }
        if (alive != null) {
            ptySession = alive
            manager.write(ptyId!!, commandLine + "\n")
            return alive
        }
        val created = runCatching {
            manager.create(
                cwd = root.absolutePath,
                title = "构建",
                client = IdeTerminalSessionClient(),
                onFinished = { _, finishedSession -> onPtyFinished(finishedSession) }
            )
        }.getOrNull() ?: return null

        ptyId = created.first.id
        _activePty.value = created.first.id
        ptySession = created.second
        // 新会话自动设为活动会话：终端工具窗据此把视图切到这次构建，
        // 否则用户点「构建」后终端仍停在原来的 shell，误以为「构建没有输出」。
        runCatching { manager.markActive(created.first.id) }
        val session0 = created.second
        // 兜底初始化：没有终端视图时 emulator 为 null，日志与问题匹配都会丢。
        runCatching { session0.emulator ?: session0.initializeEmulator(100, 30) }
        manager.bindScreenUpdates(created.first.id) { s -> harvest(s) }
        manager.write(created.first.id, commandLine + "\n")
        return session0
    }

    /** 停止：执行器路径直接取消协程（执行器会给 guest 发 SIGTERM）；终端会话路径仍用 Ctrl-C。 */
    fun stop() {
        if (!_state.value.running) return
        _state.value = _state.value.copy(cancelled = true, message = "正在停止…")
        val job = execJob
        if (job != null) {
            runCatching { job.cancel() }
            finish(exitCode = null, cancelled = true, message = "已停止")
            return
        }
        val id = ptyId ?: run { finish(exitCode = null, cancelled = true, message = "已停止"); return }
        val sent = runCatching { manager.attach(id)?.write(CTRL_C) }.isSuccess
        if (!sent) {
            runCatching { manager.close(id) }
            finish(exitCode = null, cancelled = true, message = "已强制停止")
        }
    }

    fun clearOutput() {
        seenOutput.clear()
        _output.value = emptyList()
        _state.value = _state.value.copy(problemCount = 0, errorCount = 0)
        ptyId?.let { runCatching { manager.write(it, "clear\n") } }
    }

    /** 面板消费掉「刚结束」提示。 */
    fun acknowledgeFinish() {
        if (_state.value.justFinished) _state.value = _state.value.copy(justFinished = false)
    }

    // ------------------------------------------------------------------ 组装命令

    /**
     * 组装真正写进 pty 的命令行。
     *
     * 关键点：
     * - 先 `cd` 到目标目录（复用的会话可能停在别的目录）；
     * - 任务自带 env 以 `export` 前置，**在 guest 内生效**（构建工具需要 JAVA_HOME/ANDROID_HOME 等）；
     * - 末尾打印 `__NEBULA_TASK_EXIT__:<code>` 标记但**不退出 shell**：面板据此判定成功/失败，
     *   同时保留会话复用能力。
     */
    private fun buildLaunchCommand(
        commandLine: String,
        cwd: File,
        env: Map<String, String>,
        preamble: List<String> = emptyList(),
        runToken: String = ""
    ): String = buildString {
        env.forEach { (k, v) -> append("export ").append(k).append('=').append(TaskDefinition.shellToken(v)).append("; ") }
        if (preamble.isNotEmpty()) {
            // printf '%s\n' a b c → 每个参数单独一行；参数经 shellToken 单引号转义，路径含空格也安全。
            append("printf '%s\\n' ").append(preamble.joinToString(" ") { TaskDefinition.shellToken(it) }).append("; ")
        }
        append("cd ").append(TaskDefinition.shellToken(cwd.absolutePath)).append(" && { ")
        append(commandLine)
        // 结束标记：`__NEBULA_TASK_EXIT__<本次 token>:<退出码>`。
        // 为什么必须带 token：pty 会把整行命令**回显**到屏幕，harvest 会读到这行文本。写成固定
        // 字面量时，(a) 命令回显本身就含完整标记 → 命令刚下发、还没执行就被判「已结束」；
        // (b) 回滚缓冲里上一次构建留下的标记会被下一次 harvest 命中 → 任何任务都在 1s 内
        // 「失败（退出码 1）」。带 token 后，回显里只有 `%s:%s` 占位符，旧标记也天然失配。
        append(" ; }; __nebula_ec=\$?; printf '\\n__NEBULA_TASK_EXIT__%s:%s\\n' '")
        append(runToken)
        append("' \"\$__nebula_ec\"")
    }

    // ------------------------------------------------------------------ 输出采集

    @Synchronized
    private fun harvest(s: TerminalSession) {
        val emulator = s.emulator ?: return
        val text = runCatching { emulator.screen.transcriptText }.getOrNull() ?: return
        if (text.isEmpty() || text == lastTranscript) return
        val fresh = if (text.length > lastTranscript.length && text.startsWith(lastTranscript)) {
            text.substring(lastTranscript.length)
        } else {
            // 回滚缓冲被截断 / 被 resize 重排：整体重扫一次，重复问题由 seenProblems 去重。
            text
        }
        lastTranscript = text
        fresh.split('\n').forEach { ingest(it) }
    }

    /** 运行日志落盘（files/logs/run.log）：真机故障时无需用户复述、直接看日志定位。 */
    private fun logRun(line: String) {
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "run.log")
            if (f.length() > 1_000_000) f.delete()
            f.appendText("[${System.currentTimeMillis()}] $line\n")
        }
    }

    /**
     * 「Gradle 资源编译链被上一次构建写坏」的失败特征。
     * 只在最近 200 行里找，够覆盖一次构建的失败现场，也不会因为很久以前的报错误判。
     */
    private fun outputSignalsTransientCacheFailure(): Boolean {
        return _output.value.takeLast(200).any { looksLikeTransientCacheFailure(it) }
    }

    /** 命名与 Gradle/AGP 原始输出保持一致，便于以后按真机日志增补。 */
    private fun looksLikeTransientCacheFailure(line: String): Boolean {
        if (line.contains("AarResourcesCompilerTransform")) return true
        if (line.contains("Daemon startup failed")) return true
        if (line.contains("Timeout waiting to lock")) return true
        return line.contains("Could not resolve all files for configuration") && line.contains("transforms")
    }

    private fun ingest(raw: String) {
        val line = raw.trimEnd()
        if (line.isBlank()) return
        logRun("OUT $line")

        // 严格匹配 `marker:<数字>` 才认作结束标记：
        // 屏幕上除了真正的结束行，还可能出现命令行回显、用户手动敲的命令等含 marker 的文本，
        // 宽松匹配会让任务「还没跑就结束」（退出码为空）。匹配不到数字就当普通输出。
        val marker = exitPattern.find(line)
        if (marker != null) {
            finish(exitCode = marker.groupValues[1].toIntOrNull(), cancelled = _state.value.cancelled, message = null)
            return
        }

        // 同一行被 emulator 反复刷新（光标行重绘）时只记一次。
        if (seenOutput.lastOrNull() == line) return
        seenOutput.add(line)
        if (seenOutput.size > MAX_LINES) seenOutput.removeAt(0)
        _output.value = ArrayList(seenOutput)
        session?.let { bus.emit(IdeEvent.Output(it.id, line, stderr = false)) }
        updateMessage(line)

        val problem = ProblemMatcher.match(line, matchers) ?: return
        publish(problem)
    }

    private fun publish(problem: com.nebulaforge.core.projectmodel.DetectedProblem) {
        val resolved = ProotPathMapper.resolve(context, problem.filePath, projectRoot, workingDir)
        // 路径映射失败的条目**仍然展示**（文件列用原始字符串），只是不参与「点击跳转」——
        // 静默丢弃会让用户以为「编译器没报错」。
        val key = "${resolved?.absolutePath ?: problem.filePath}:${problem.line}:${problem.column}:${problem.message}"
        if (!seenProblems.add(key)) return
        val severity = if (problem.severity == BuildError.Severity.ERROR) Severity.ERROR else Severity.WARNING
        session?.let { s ->
            bus.emit(
                IdeEvent.Diagnostic(
                    sessionId = s.id,
                    file = resolved?.absolutePath ?: problem.filePath,
                    line = problem.line,
                    column = problem.column,
                    message = problem.message,
                    severity = severity
                )
            )
        }
        val current = _state.value
        _state.value = current.copy(
            problemCount = current.problemCount + 1,
            errorCount = current.errorCount + if (severity == Severity.ERROR) 1 else 0
        )
    }

    private fun emitOutput(line: String) {
        seenOutput.add(line)
        _output.value = ArrayList(seenOutput)
        session?.let { bus.emit(IdeEvent.Output(it.id, line, stderr = false)) }
        updateMessage(line)
    }

    /**
     * 把「最近一行有意义的输出」同步成任务副标题（面板那一行的 detail、任务通知都读 [TaskRunState.message]）。
     *
     * 真机现象：点一下「构建」，任务中心那一行从第一秒到最后一秒都写着「准备中…」，只有后面的
     * 秒数在涨（12 分 20 秒、25 分钟…）。用户唯一的判断依据就是这行字，于是只能认为「卡死了」，
     * 实际 guest 里的 Gradle 早就跑起来了，甚至已经跑完 —— 面板却没有任何一行字反映真实进度。
     * 根因：[TaskRunState.message] 只在 [start] 写初值（"准备中…"）与 [finish] 写结论，中间
     * **没有任何人更新它**；输出全部进了 `_output` 流，而副标题读的是另一个字段。
     * 这里让它跟上实际输出，watchdog 打进来的「⏱ 阶段：…」哨兵同理（走 [emitOutput]）。
     */
    private fun updateMessage(line: String) {
        val text = line.trim().removePrefix("[nebula]").trim()
        if (text.isEmpty()) return
        val shown = if (text.length > MESSAGE_MAX) text.take(MESSAGE_MAX - 1) + "…" else text
        // 用户已经点了「停止」：不要再让后续输出把副标题冲回日志行，否则文字与停止状态互相矛盾。
        if (_state.value.cancelled) return
        // 同一行会被 emulator 反复重绘；相同就不必再推一次 StateFlow（省掉无意义的重组）。
        if (_state.value.message == shown) return
        _state.value = _state.value.copy(message = shown)
    }

    // ------------------------------------------------------------------ 结束

    private fun onPtyFinished(finishedSession: TerminalSession) {
        if (finished) return
        if (!_state.value.running) return
        // 会话被外部关闭（用户在终端工具窗关掉）：按退出码判定，拿不到就当取消。
        val code = runCatching { finishedSession.exitStatus }.getOrNull()
        finish(exitCode = code, cancelled = _state.value.cancelled || code == null, message = null)
    }

    /**
     * 失败自愈：按上一次的调用参数原样重跑一次。
     *
     * @return false 表示面板正忙（已有任务在跑）或没有可重放的调用，重试没启动
     */
    private fun retryLast(): Boolean {
        val root = lastRoot ?: return false
        return start(
            root = root,
            taskId = lastTaskId,
            label = lastLabel,
            commandLine = lastCommandLine,
            kind = lastKind,
            cwd = lastCwd ?: root,
            env = lastEnv,
            matchers = lastMatchers,
            autoRetry = true
        )
    }

    // ------------------------------------------------------- 宿主被杀 → 可解释的失败

    /** 标记行字段里不允许出现分隔符。 */
    private fun markerSafe(s: String): String = s.replace('|', '/').replace('\n', ' ')

    /**
     * 冷启动时检查上次运行是否「无疾而终」。
     *
     * 只在**确实是另一个已消失的进程**留下标记时才报告，避免误报：
     *  - 标记里的 pid == 当前进程 → 是同进程内的残留（异常路径没删掉），静默清理；
     *  - `/proc/<pid>` 还在且 cmdline 仍是本包 → 上次的构建可能还活着（例如只是 Activity 重建），
     *    不打扰用户，也不删标记。
     */
    private fun reportOrphanedRun() {
        val marker = runMarker
        if (!marker.isFile) return
        val raw = runCatching { marker.readText() }.getOrNull()
        val parts = raw?.split('|')
        if (parts == null) { runCatching { marker.delete() }; return }

        val pid = parts.getOrNull(0)?.toIntOrNull()
        val startedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val kind = parts.getOrNull(2).orEmpty()
        val label = parts.getOrNull(3).orEmpty()
        val root = parts.getOrNull(4).orEmpty()
        val logPath = parts.getOrNull(5).orEmpty()
        val sessionId = parts.getOrNull(6).orEmpty()

        if (pid == null || pid == android.os.Process.myPid() || isProcessAlive(pid)) return
        runCatching { marker.delete() }

        val ran = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0L
        val what = if (kind == SessionKind.RUN.name) "运行" else "构建"
        val at = runCatching {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(if (startedAt > 0) startedAt else System.currentTimeMillis()))
        }.getOrDefault("未知")

        val lines = buildList {
            add("[nebula] ⚠ 上次${what}没有正常结束：宿主应用进程被系统停掉了（不是编译错误）。")
            add("[nebula]    任务：$label")
            if (root.isNotBlank()) add("[nebula]    工程：$root")
            add("[nebula]    开始于 $at，被中断前已运行约 ${ran}s")
            add("[nebula]    为什么没有报错：$what 跑在本应用的子进程里，应用被系统强制停止时整棵进程树一起被杀，来不及输出失败信息。")
            add("[nebula]    规避：设置 → 应用 → 星弦 IDE → 耗电管理/应用启动管理 → 关掉「自动管理」并允许后台运行；否则长${what}仍可能被中断。")
            if (logPath.isNotBlank()) add("[nebula]    中断前的完整日志：$logPath")
            add("[nebula]    —— 请重新发起本次$what。")
        }
        _output.value = lines
        _state.value = TaskRunState(
            running = false,
            label = label,
            projectRoot = root,
            sessionId = sessionId.ifBlank { null },
            startedAt = startedAt,
            durationMs = ran * 1000,
            success = false,
            exitCode = null,
            message = "上次${what}被系统中断（宿主进程被停止）",
            justFinished = false
        )
    }

    /** `/proc/<pid>` 在、且 cmdline 里仍是本包，才算「上次的进程还活着」。 */
    private fun isProcessAlive(pid: Int): Boolean {
        if (!File("/proc/$pid").exists()) return false
        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
        }.getOrDefault("")
        return cmdline.contains(context.packageName)
    }

    /** 运行开始写标记（[reportOrphanedRun] 的下一次启动靠它）。 */
    private fun writeRunMarker(kind: SessionKind, label: String, root: File, sessionId: String) {
        runCatching {
            runMarker.parentFile?.mkdirs()
            runMarker.writeText(
                listOf(
                    android.os.Process.myPid().toString(),
                    System.currentTimeMillis().toString(),
                    kind.name,
                    markerSafe(label),
                    markerSafe(root.absolutePath),
                    markerSafe(File(root, "nb-flutter.log").takeIf { it.isFile }?.absolutePath.orEmpty()),
                    markerSafe(sessionId)
                ).joinToString("|")
            )
        }
    }

    /** 正常结束（成功/失败/取消）删标记，避免下次启动误报。 */
    private fun clearRunMarker() {
        runCatching { if (runMarker.exists()) runMarker.delete() }
    }

    /**
     * 一次性提示：本应用若不在电池优化白名单里，长构建随时可能被系统回收。
     * 只提示、不弹系统对话框 —— 用户点构建时被一个系统权限弹窗打断体验更差。
     */
    private fun hintKillRiskIfNeeded() {
        if (killRiskHinted) return
        killRiskHinted = true
        val ignoring = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
        if (!ignoring) {
            emitOutput(
                "[nebula] ⚠ 本应用未加入「电池优化白名单」：构建较长时可能被系统强制停止，" +
                    "表现为日志停住且没有任何报错。建议在 设置 → 应用 → 星弦 IDE → 耗电管理中允许后台运行。"
            )
        }
    }

    private fun finish(exitCode: Int?, cancelled: Boolean, message: String?) {
        if (finished) return
        finished = true
        clearRunMarker()
        logRun("FINISH exit=$exitCode cancelled=$cancelled message=$message")
        // 任务结束（成功/失败/取消都走这里）立刻撤掉前台服务与唤醒锁，不留常驻通知、不额外耗电。
        runCatching { TaskForegroundService.end(context) }
        execJob = null
        executor = null
        val snapshot = _state.value
        val duration = if (snapshot.startedAt > 0) System.currentTimeMillis() - snapshot.startedAt else 0
        val success = exitCode == 0
        val what = if (sessionKind == SessionKind.RUN) "运行" else "构建"
        val text = message ?: when {
            cancelled -> "$what 已停止"
            success -> if (what == "运行") "运行已结束（退出码 0）" else "构建成功"
            exitCode != null -> "$what 失败（退出码 $exitCode）"
            else -> "$what 结束（状态未知）"
        }
        session?.let { s ->
            bus.state(
                s,
                when {
                    cancelled -> SessionState.Cancelled
                    success -> SessionState.Succeeded("${snapshot.label} 完成")
                    else -> SessionState.Failed(text, exitCode)
                }
            )
        }
        _state.value = snapshot.copy(
            running = false,
            exitCode = exitCode,
            success = success,
            cancelled = cancelled,
            durationMs = duration,
            message = text,
            justFinished = true
        )
        // 任务结束即入历史（任务中心的「历史任务」）：必须先 copy 再入，否则记的是运行中的旧快照。
        _history.value = (listOf(_state.value) + _history.value).take(HISTORY_LIMIT)

        // ---------------------------------------------------------------- 产物识别
        // 构建成功即定位本次产出的 APK，emit Artifact（IdeSessionBus 会落进
        // WorkspaceStateStore.lastArtifact）。构建面板的「产物 chip + 安装到本机」全靠它：
        // 以前只有「构建并实机运行」（RealAndroidBuildRunController）那条链路会 emit Artifact，
        // 面板里点构建永远没有产物，也就没有任何安装入口 —— 用户看到的正是「编完装不了」。
        // 只要本次任务成功结束就去定位产物（不再按 BUILD/RUN 分流）：运行类任务（flutter run /
        // gradle installDebug）同样会产出 APK；而「本次时间窗内新写出」这个条件本身就能保证
        // 不会把上一次构建的旧产物当成本次成果。
        if (success && !cancelled) {
            val root = projectRoot
            val since = snapshot.startedAt
            val sid = session?.id
            prep.launch {
                // 先快路径（只看 build/app/outputs 等产物输出目录，毫秒级），没命中才退回全树扫描。
                // 这里以前是全树扫描，而构建面板又自己扫了一遍 → 一秒钟能出的弹窗要等好几秒。
                val apk = runCatching {
                    BuildArtifactLocator.quickApk(context, root, sinceMs = since - 5_000L)
                        ?: BuildArtifactLocator.newestApk(context, root, sinceMs = since - 5_000L)
                }.getOrNull()
                if (apk != null) {
                    logRun("ARTIFACT ${apk.absolutePath}")
                    if (sid != null) bus.emit(IdeEvent.Artifact(sid, apk.absolutePath, "apk"))
                    // ★ 「安装到本机」的入口必须**落盘**，不能只活在界面的内存状态里：
                    //   构建要跑几分钟，用户会切去别的 App，系统常在构建刚结束那一刻回收本进程
                    //   （取证：run.log 的 FINISH exit=0 之后约 24s 就出现
                    //   "Force removing ActivityRecord … app died"，且 crash buffer 里没有 Java 异常）。
                    //   落盘之后由 InstallPromptHost 在用户回到应用时补弹，弹窗才不会再「莫名消失」。
                    runCatching {
                        InstallPromptStore(context).offer(
                            apk = apk,
                            projectRoot = root?.absolutePath,
                            sessionId = sid
                        )
                    }
                } else {
                    logRun("ARTIFACT 未找到本次产出的 APK（root=$root）")
                }
            }
        } else if (!cancelled && outputSignalsTransientCacheFailure()) {
            // ------------------------------------------------------------ 失败自愈
            // 真机取证（用户构建日志）：
            //   > Task :app:processDebugResources FAILED
            //   > Execution failed for AarResourcesCompilerTransform: .../transforms/<hash>/transformed/...
            //   > AAPT2 aapt2-8.6.0-11315950-linux Daemon #1: Daemon startup failed
            // 上一次构建把 transforms 缓存写到一半、或中断的构建留下 daemon 占着资源编译链，
            // 下一次构建就必然停在这里。清掉 transforms（**不动依赖缓存**）后即可恢复，
            // 并把结论直接写进日志，用户不用对着英文报错反复重试。
            fun cleanGradleTransforms(): Int {
                val caches = File(com.nebulaforge.core.environment.Environment.gradleUserHome(context), "caches")
                val dirs = caches.listFiles { f -> f.isDirectory }
                    ?.mapNotNull { File(it, "transforms").takeIf { t -> t.isDirectory } }
                    .orEmpty()
                var n = 0
                dirs.forEach { if (it.deleteRecursively()) n++ }
                return n
            }
            fun noteSelfHeal(cleaned: Int) {
                val text = "[nebula] 检测到资源编译缓存异常（AAPT2 守护进程启动失败 / transforms 脏），" +
                    "已清理 $cleaned 处缓存"
                logRun("NOTE $text")
                _output.value = ArrayList(_output.value).apply { add(text) }
            }
            fun note(text: String) {
                logRun("NOTE $text")
                _output.value = ArrayList(_output.value).apply { add(text) }
            }
            val cleaned = runCatching { cleanGradleTransforms() }.getOrDefault(0)
            logRun("SELFHEAL transforms-cleaned=$cleaned")
            noteSelfHeal(cleaned)
            // 只提示「再点一次构建」对用户不够：他看到的是一次失败，还得自己判断要不要重试。
            // 这里直接**自动重跑一次**（等 1.5s 让上一个 pty 与守护进程彻底退干净）。
            if (!selfHealRetried) {
                selfHealRetried = true
                note("[nebula] 正在自动重试一次构建…")
                prep.launch {
                    kotlinx.coroutines.delay(1500)
                    val ok = runCatching { retryLast() }.getOrDefault(false)
                    if (ok) {
                        logRun("SELFHEAL 已发起自动重试")
                    } else {
                        logRun("SELFHEAL 自动重试未启动（面板忙）")
                        note("[nebula] 自动重试没能启动，请点一次「构建」")
                    }
                }
            } else {
                note("[nebula] 自动重试过一次仍然失败：缓存已清理，请检查上方报错后再点「构建」")
            }
        }
    }

    companion object {
        /** 构建结束时打印的标记（避免与真实日志冲突，带双下划线命名空间）。 */
        const val EXIT_MARKER = "__NEBULA_TASK_EXIT__"

        /**
         * 结束标记前缀。真正的匹配式是**带本次运行 token** 的 [exitPattern]
         * （`__NEBULA_TASK_EXIT__<token>:<退出码>`）：固定字面量的标记会被 pty 回滚缓冲里
         * 上一次构建的旧标记误伤，导致新构建「秒退 1」。
         */
        const val EXIT_MARKER_HEAD = "__NEBULA_TASK_EXIT__"
        private const val CTRL_C = "\u0003"

        /** 看门狗重扫屏幕的间隔（毫秒）：太小浪费，太大让用户多等。 */
        private const val WATCHDOG_INTERVAL_MS = 700L

        /** 无新输出多久后开始告警（首次构建的 Gradle 配置期较安静，但脚本每 20s 有心跳行）。 */
        private const val STALL_SILENCE_MS = 45_000L
        private const val MAX_LINES = 8000

        /** 任务副标题（[updateMessage]）最多显示多少字符：面板那一行只显示一行，太长会被截断成没信息量的样子。 */
        private const val MESSAGE_MAX = 56
    }
}
