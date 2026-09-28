package com.nebulaforge.app.ai

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 网页 AI 桥接（AI 聚合网关的核心）。
 *
 * 目标：把「只能在浏览器里用的网页 AI」（ChatGPT / Claude / Gemini / DeepSeek / 豆包 / 通义 / Kimi …）
 * 变成**工作台里可调用的一等公民** —— 用户配一次（可选登录），之后：
 *  1) 在 AI 工作台的「网桥」面板里直接问；
 *  2) AI 自己用工具 `ask_web_ai` 调它们（聚合调度：谁合适问谁）。
 *
 * 实现要点（为什么这么做）：
 * - **驱动真实网页**，不逆向接口：很多站点没有公开 API 或需要风控令牌，网页就是最稳的入口。
 * - **只靠「正文增量」取回答**，不依赖各站 DOM 结构：发送前记录 `document.body.innerText`，
 *   发送后轮询，等正文变长且连续两次不变 → 增量就是回答。站点改版也不会失效。
 * - **持久化 WebView 配置目录**：登录一次（在「网桥」面板里的浏览器里登录），之后桥接自动复用登录态。
 * - 所有 WebView 操作都在主线程；超时不抛异常，返回「已取到的部分 + 原因」。
 */
object WebAiBridge {

    const val PREFS = "nebula_web_ai_prefs"
    const val KEY_LIST = "nebula_web_ai_list"
    private const val KEY_PREFERRED = "nebula_web_ai_preferred"
    private const val KEY_LAST_OK = "nebula_web_ai_last_ok"
    private const val KEY_BAD = "nebula_web_ai_bad_sites"

    /** 一个网页 AI 的接入配置。 */
    data class WebAi(
        val id: String = java.util.UUID.randomUUID().toString().replace("-", "").take(8),
        val name: String = "",
        val url: String = "",
        val inputHint: String = "",
        val sendHint: String = "",
        val enabled: Boolean = true,
        val note: String = ""
    ) {
        val label: String get() = name.ifBlank { url.substringAfter("//").substringBefore('/').ifBlank { "未命名" } }
        val usable: Boolean get() = enabled && url.startsWith("http")
    }

    /** 常见网页 AI 预设：URL 直接可用；输入/发送选择器留空时走「通用策略」。 */
    fun presets(): List<WebAi> = listOf(
        WebAi(name = "ChatGPT", url = "https://chatgpt.com/", note = "需登录"),
        WebAi(name = "Claude", url = "https://claude.ai/new", note = "需登录"),
        WebAi(name = "Gemini", url = "https://gemini.google.com/app", note = "需登录"),
        WebAi(name = "DeepSeek", url = "https://chat.deepseek.com/", note = "需登录"),
        WebAi(name = "Kimi", url = "https://kimi.moonshot.cn/", note = "需登录"),
        WebAi(name = "通义千问", url = "https://tongyi.aliyun.com/qianwen/", note = "需登录"),
        WebAi(name = "豆包", url = "https://www.doubao.com/chat/", note = "需登录"),
        WebAi(name = "文心一言", url = "https://yiyan.baidu.com/", note = "需登录"),
        WebAi(name = "智谱清言", url = "https://chatglm.cn/main/alltoolsdetail", note = "需登录"),
        WebAi(name = "元宝", url = "https://yuanbao.tencent.com/chat", note = "需登录")
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<WebAi> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                WebAi(
                    id = o.optString("id"), name = o.optString("name"), url = o.optString("url"),
                    inputHint = o.optString("input"), sendHint = o.optString("send"),
                    enabled = o.optBoolean("enabled", true), note = o.optString("note")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, list: List<WebAi>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().put("id", a.id).put("name", a.name).put("url", a.url)
                    .put("input", a.inputHint).put("send", a.sendHint)
                    .put("enabled", a.enabled).put("note", a.note)
            )
        }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun addPresets(context: Context, presets: List<WebAi>) {
        val list = all(context).toMutableList()
        presets.forEach { p -> if (list.none { it.url == p.url }) list.add(p) }
        save(context, list)
    }

    fun upsert(context: Context, ai: WebAi) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == ai.id }
        if (idx >= 0) list[idx] = ai else list.add(ai)
        save(context, list)
    }

    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.id == id })

    // ── 站点偏好与健康度（持久化）────────────────────────────────────────────────
    // 真机教训：网关原来只会「按列表顺序 + 上一轮粘性」选站。用户明明登录好的是 DeepSeek，
    // 网关却先去调 Claude —— 而 Claude/Gemini 在中国大陆直接是地区限制页
    // （claude.com/app-unavailable-in-region），消息填得进去、点发送永远不成功，
    // 用户看到的就是「消息无法发送」。所以必须让**用户的偏好**和**站点的健康度**参与选站。

    /** 用户指定的「首选」站点（跑任务优先用它）。 */
    fun preferred(context: Context): String? =
        prefs(context).getString(KEY_PREFERRED, null)?.takeIf { it.isNotBlank() }

    fun setPreferred(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id.isNullOrBlank()) remove(KEY_PREFERRED) else putString(KEY_PREFERRED, id)
        }.apply()
    }

    /** 上一次真正答出内容的站点：没有首选时优先复用它（登录态/页面都是热的，最快）。 */
    fun lastOk(context: Context): String? =
        prefs(context).getString(KEY_LAST_OK, null)?.takeIf { it.isNotBlank() }

    fun setLastOk(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_OK, id).apply()
    }

    /** 记下「这个站暂时不能用」及原因/到期时间，选站时沉到最后（全不可用时仍会尝试）。 */
    fun markBad(context: Context, id: String, reason: String, ttlMs: Long) {
        runCatching {
            val o = JSONObject(prefs(context).getString(KEY_BAD, null) ?: "{}")
            o.put(
                id,
                JSONObject().put("until", System.currentTimeMillis() + ttlMs)
                    .put("reason", reason.take(120))
            )
            prefs(context).edit().putString(KEY_BAD, o.toString()).apply()
        }
    }

    fun clearBad(context: Context, id: String) {
        runCatching {
            val o = JSONObject(prefs(context).getString(KEY_BAD, null) ?: "{}")
            if (o.has(id)) {
                o.remove(id)
                prefs(context).edit().putString(KEY_BAD, o.toString()).apply()
            }
        }
    }

    /** 该站当前是否被判定不可用；返回原因（未到期）或 null。 */
    fun badReason(context: Context, id: String): String? {
        val raw = prefs(context).getString(KEY_BAD, null) ?: return null
        return runCatching {
            val o = JSONObject(raw).optJSONObject(id) ?: return@runCatching null
            if (o.optLong("until") < System.currentTimeMillis()) null
            else o.optString("reason").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 跑任务时的选站顺序：**首选 → 上次成功 → 其它 → 暂时不可用**（同级保持原顺序）。
     * 这样用户把 DeepSeek 设为「首选」后，网关就再也不会先去撞 Claude 的地区限制页。
     */
    fun orderedForTask(context: Context): List<WebAi> {
        val ok = all(context).filter { it.usable }
        if (ok.isEmpty()) return ok
        val pref = preferred(context)
        val last = lastOk(context)
        val bad = ok.filter { badReason(context, it.id) != null }.map { it.id }.toSet()
        fun rank(a: WebAi): Int = when {
            a.id == pref -> 0
            a.id == last -> 1
            a.id in bad -> 4
            // 已知在中国大陆不可用（地区限制页 / 直连失败）：默认沉到国内可用站之后
            // —— 这样用户什么都不用配，网关第一次就会先用 DeepSeek/豆包/Kimi，而不是撞 Claude 的墙。
            CN_RESTRICTED.any { a.url.lowercase().contains(it) } -> 3
            else -> 2
        }
        // ★ 默认**直接排除**国内不可用站（Claude/ChatGPT/Gemini 的地区限制页）：它们页面看着有输入框、
        //   填得进去却永远出不了字，试一次就要白耗用户 20-45 秒（真机反馈：延迟很高、回答很慢）。
        //   只有用户显式设了「首选」，或它上次真的答出来过，才继续尝试。
        val blocked = ok.filter {
            CN_RESTRICTED.any { k -> it.url.lowercase().contains(k) } && it.id != pref && it.id != last
        }.map { it.id }.toSet()
        val pool = ok.filter { it.id !in blocked }
        return (if (pool.isEmpty()) ok else pool).sortedBy { rank(it) }
    }

    /** 在中国大陆默认打不开/会被地区限制的站点域名（只用于**排序**，不禁止用户主动选择）。 */
    private val CN_RESTRICTED = listOf(
        "claude.ai", "anthropic.com", "chatgpt.com", "openai.com",
        "gemini.google", "bard.google", "copilot.microsoft.com",
        "perplexity.ai", "grok.com", "x.ai", "poe.com"
    )


    /** 模型把站点当成「首选 / 默认 / preferred」来点名时的别名（网页 AI 经常这样写）。 */
    private fun String.isPreferredAlias(): Boolean {
        val t = trim().lowercase()
        return t in setOf("首选", "默认", "优先", "第一个", "第一", "preferred", "default", "primary", "auto", "自动", "任务站点")
    }

    fun resolve(context: Context, nameOrId: String): WebAi? {
        val list = all(context).filter { it.usable }
        if (nameOrId.isBlank() || nameOrId.isPreferredAlias()) {
            // 没点名站点（或点名「首选」）时**不要**盲取列表第一个：真机上第一个正是 Claude（中国大陆地区限制页），
            // 调用必然失败。改用「首选 → 上次成功 → 其它 → 暂时不可用」（orderedForTask）。
            return orderedForTask(context).firstOrNull()
        }
        return list.firstOrNull { it.id == nameOrId }
            ?: list.firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }
            ?: list.firstOrNull { it.name.contains(nameOrId, ignoreCase = true) }
            // 名字/id 都对不上时**兜底**：旧实现直接 return null，调用方只报「找不到站点」。
            // 但模型点名经常对不上真名（DeepSeek→"deepseek-chat"、Claude→"claude-3.5-sonnet"，
            // 或干脆写「首选」），于是用户每次点「问网页 AI」都失败——看起来就是「无法调用首选 AI」。
            // 现在退到 orderedForTask（首选排第一）：只要网桥里有可用站点就一定调得起来。
            ?: orderedForTask(context).firstOrNull()
    }

    // ── 驱动层 ────────────────────────────────────────────────────────────────

    /** 最近一次错误（面板上直接显示，避免「只有一片白」）。 */
    @Volatile var lastError: String? = null

    /** 加载进度 0..100。**注意**：不能叫 progress —— 在 `WebView.apply{}` 作用域里
     * `progress` 会被解析成 `WebView.getProgress()`（只读 val），赋值直接编译失败。 */
    @Volatile var loadProgress: Int = 0

    /** 当前 URL。 */
    @Volatile var currentUrl: String? = null

    /** 系统 WebView 不可用时的原因（华为部分机型会停用 WebView 组件）。 */
    @Volatile var unavailable: String? = null

    /** 上一次页面加载完成时正文（去空白）的字数。-1 = 还没探测。 */
    @Volatile var pageTextLen: Int = -1

    /** 上一次页面加载完成时的元素个数：判「空壳页」的可靠依据（-1 = 还没探测）。 */
    /** 单次 JS 求值的最长等待（回调不来就当页面无响应，宁可报错也不卡死界面）。 */
    private const val EVAL_TIMEOUT_MS = 6_000L

    /**
     * 「这轮消息到底送出去没有」的最长确认时间。
     *
     * 为什么必须有这道闸门（用户截图：一直停在「已提交，等待站点出字…（65s）」）：
     * [submitPrompt] 的判据（输入框被清空 / 节点被重建 / 出现「停止生成」）在真机上会被站点骗过 ——
     * 未登录、要过验证、被风控、或站点改版成空壳编辑器时，**站点同样会把输入框清空**，但页面上
     * 永远不会出现我们这条消息、更不会出字。旧实现这时只能一路等到 90s 超时才失败，三个站点就是
     * 4.5 分钟的黑等，用户看到的就是「网关发不出去、一直转圈」。
     * 现在最多等这么久：等不到「我们的消息出现在页面上 / 出现新消息节点」就**立刻失败并换站**。
     */
    private const val LANDED_WAIT_MS = 18_000L

    /** 确认消息落地后、重新取抓取基线前的沉降时间（等站点把气泡/懒渲染容器挂稳）。 */
    private const val SETTLE_MS = 800L

    /**
     * 落地后「站点开始出字」的等待窗口。
     *
     * ★ 真机取证（`files/logs/gateway.log`，2026-09-25 21:52 起）：原来这里是 **25s**，
     * 于是唯一一个登录态正常的站点（DeepSeek）**每一次**都被判成
     * 「blank-answer：消息已出现在页面上，但 25s 内站点没有任何出字」而被换掉 ——
     * 我们发过去的是几千字的系统提示词，站点要先排队，DeepSeek 还会先「深度思考」一段才吐第一个字，
     * 25s 根本不够。用户看到的就是「网关一个站点都调不通」。
     * 现在默认给 75s；只要站点自己还在出字（页面仍挂着「停止生成」）窗口就**自动续期**，
     * 上限是本次调用的 timeoutMs —— 慢站点不会再被我们自己的超时误杀，真死的站点仍会走到 deadline。
     */
    private const val NO_REPLY_WAIT_MS = 75_000L

    @Volatile var pageElementCount: Int = -1

    /** 上一次找输入框时页面上有多少个候选（失败文案里带上它，便于判断是「没有输入框」还是「选择器不对」）。 */
    @Volatile var lastInputCandidates: Int = -1

    /** 面板页面是否已加载完成（Activity 轮询用）。 */
    val pageReady: Boolean get() = pageDone.get()

    /** 正文开头片段（去空白，最多 120 字）：页面「已加载但没内容」时，这就是**直接证据**
     *  （能看出是登录页、Cloudflare 校验、还是「请开启 JavaScript」）。 */
    @Volatile var pageTextSnippet: String? = null

    /** 最近一次页面 JS 报错（控制台 error 级）。白屏时这是最直接的线索。 */
    @Volatile var lastConsoleError: String? = null

    /** 当前是否在使用「桌面版」UA（部分站点对移动 WebView 直接返回空壳页）。 */
    @Volatile var uaDesktop: Boolean = false

    private val pageDone = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // 便于 chrome://inspect 调试，也让 logcat 有内核侧线索（只影响调试，无副作用）。
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
    }

    /** 面板上一行诊断文字：白屏时能立刻判断是「加载中 / 报错 / 站点给空壳页 / WebView 不可用」。 */
    fun statusText(): String = when {
        unavailable != null -> unavailable!!
        lastError != null -> "✗ $lastError"
        panelView == null && shared == null -> "未开始"
        loadProgress in 1..99 -> "加载中 $loadProgress%"
        // 「已加载」但正文极少：HTTP 成功了，站点却给了空壳页（风控/需真实浏览器环境/需登录）。
        // 之前这里只说「已加载」，用户看到的是整屏白 —— 现在把**真实结论 + 页面开头**摆出来。
        pageDone.get() && pageElementCount in 1..40 ->
            "已加载，但页面几乎是空壳（元素仅 $pageElementCount 个 / 正文 $pageTextLen 字）" +
                (pageTextSnippet?.let { "：「${it.take(60)}」" } ?: "") +
                " → 站点很可能拒绝内置 WebView。点「UA:桌面」重载，或「浏览器」用系统浏览器打开。" +
                (lastConsoleError?.let { " JS 报错：${it.take(80)}" } ?: "")
        pageDone.get() ->
            "已加载：${currentUrl ?: ""}（正文 ${if (pageTextLen >= 0) "$pageTextLen 字" else "探测中"}" +
                " / 元素 ${if (pageElementCount >= 0) "$pageElementCount 个" else "探测中"}）"
        else -> "等待页面响应…（若一直白屏，点「重载」；仍白屏请点「浏览器」用系统浏览器打开）"
    }

    /** 「最近一次问答之后，站点页面上是否还留着这轮对话」。
     *  true = 后续可以只发**增量**（新的一轮用户消息），不必重贴 system + 全历史：
     *  站点自己的会话内存里已经有了，提交长度从 2 万字级降到几百字，响应明显更快。
     *  页面一旦被真正重新加载（onPageStarted）就置回 false。 */
    @Volatile var liveConversation: Boolean = false
        private set

    /** 上一轮**真正发出去**的那段提示词的指纹（[promptFingerprint]）。
     *  复用前用它验「页面上还留着上一轮的消息吗」：页面被站点重置成新对话/首页时，
     *  只发增量（「接着上一轮继续 + 最新一句」）会让站点在**没有上下文**的情况下作答 ——
     *  真机症状就是「答非所问」。验不过就重发完整提示。 */
    @Volatile private var lastPromptFp: String? = null


    /**
     * 页面探测：正文（去空白）字数 \u0001 元素个数 \u0001 开头摘要。
     *
     * 为什么必须同时看**元素个数**：真机上 DeepSeek 明明渲染完整（欢迎语+输入框+按钮都在），
     * `document.body.innerText` 却只有 19 个字（很多 UI 是 placeholder/aria，不算 innerText）。
     * 只看正文字数会把正常页面误判成「空壳页」——之前的错误结论就是这么来的。
     * 空壳页的真实特征是「元素个数极少」（几十个以内）。
     */
    private const val PROBE_JS =
        "(function(){var b=document.body;if(!b)return '-1\\u0001 0\\u0001';" +
            "var t=b.innerText||b.textContent||'';var c=t.replace(/\\s/g,'');" +
            "var n=b.getElementsByTagName('*').length;" +
            "return (''+c.length)+'\\u0001'+n+'\\u0001'+c.slice(0,120);})()"


    /** 用系统浏览器打开（站点拒绝内置 WebView 时的兜底，登录也更顺）。 */
    fun openInSystemBrowser(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(if (context !is android.app.Activity)
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK else 0)
            )
        }.onFailure { lastError = "无法拉起系统浏览器：${it.message}" }
    }

    /** 切换手机/桌面 UA 并重载：部分站点对移动 WebView 直接返回空壳页，换 UA 常能立刻出内容。 */
    fun cycleUserAgent(context: Context) {
        uaDesktop = !uaDesktop
        applyCurrentUa(context)
        reload(context)
    }

    /** 把当前 UA 模式应用到既有 WebView 实例（不重载）。 */
    private fun applyCurrentUa(context: Context) {
        val ua = buildUserAgent(context)
        listOfNotNull(panelView, shared).forEach { runCatching { it.settings.userAgentString = ua } }
        android.util.Log.i("NbWebAi", "UA -> ${if (uaDesktop) "桌面" else "手机"}：$ua")
    }

    /** 构造干净的移动/桌面 Chrome UA。
     *  内置 WebView 的默认 UA 带 `; wv` 尾巴，且部分 ROM 的 `userAgentString` 初始就是 null
     *  （旧代码 `?.replace(...).orEmpty()` 会把它**置成空串** → 站点直接返回 400/空壳页）。
     *  这里显式拼一个真实 Chrome 的 UA（版本号取自系统默认 UA，避免伪装成过旧内核）。 */
    private fun buildUserAgent(context: Context): String {
        val sys = runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }
            .getOrNull().orEmpty()
        val chrome = Regex("Chrome/([0-9][0-9.]*)").find(sys)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() } ?: "120.0.0.0"
        val rel = android.os.Build.VERSION.RELEASE ?: "12"
        val model = android.os.Build.MODEL ?: "Android"
        return if (uaDesktop) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android $rel; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Mobile Safari/537.36"
        }
    }


    /**
     * 「网页 AI 现在能不能跑」的前置体检：返回不可用的原因（可直接展示给用户）或 null。
     *
     * 2.12.77 真机取证：设备熄灭屏幕 / 被锁定时，Chromium 会冻结 renderer ——
     * 页面 JS 停跑（站点不吐字），evaluateJavascript 的回调也不来，主线程被占住，
     * 于是 `ask` 的 150s 总超时都失效（实测 346s 才返回），用户看到的就是「卡死 / 页面无响应」。
     * 与其白等几分钟，不如立刻说清楚该怎么做。
     */
    private fun screenGuardReason(context: Context): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && !pm.isInteractive) {
            return "屏幕已熄灭，网页 AI 会被系统冻结（既收不到回答、界面也点不动）。" +
                "请点亮屏幕、让应用保持在前台后重试；若希望熄屏也能用，请改用在线 API 服务商"
        }
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km != null && km.isKeyguardLocked) {
            return "设备已锁定，网页 AI 会被系统冻结。请先解锁并让应用保持在前台后重试"
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // 白屏根因之一：真机某些 ROM 的 WebView `userAgentString` 初始就是 null，
        // 之前用 ?.replace(...).orEmpty() 会把它**置成空串** → 站点直接拒绝渲染（纯白屏）。
        // 现在显式拼一个真实 Chrome UA（去掉带 `; wv` 的「WebView 指纹」尾巴），
        // 并支持在面板上一键切手机/桌面（见 cycleUserAgent）。
        runCatching { settings.userAgentString = buildUserAgent(context) }
            .onFailure { android.util.Log.w("NbWebAi", "设置 UA 失败（保留系统默认，绝不置空）：${it.message}") }
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.loadsImagesAutomatically = true
        settings.domStorageEnabled = true
        // ── 「页面不出字 + 调用无响应」的根因修复（2.12.77 真机取证）────────────────────
        // WebView 宿主不可见（应用退到后台 / 屏幕熄灭）时 Chromium 会**冻结 renderer**：
        //   ① 页面 JS 定时器停跑 → 站点前端不再渲染流式回答 → 轮询看到「正文长度始终不变」
        //      （真机日志：blank-answer「消息已出现在页面上，但 75s 内站点没有任何出字」）；
        //   ② evaluateJavascript 的回调排队不返回 → **主线程被占住** → 连 withTimeoutOrNull
        //      都失效（真机取证：ask 号称 150s 总超时，实际 elapsed=346352ms 才返回）。
        // 对策：把 renderer 钉在 IMPORTANT 优先级，并显式声明「不可见也不降级」，
        // 让离屏 / 后台调用仍能把页面 JS 推进下去。
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
                .onFailure { android.util.Log.w("NbWebAi", "设置 renderer 优先级失败：${it.message}") }
        }
        // ── 登录页白屏：三条真机实测的根因，逐条兜住 ────────────────────────────────
        // ① 第三方 Cookie：Google/Apple 账号登录与「用 X 登录」几乎都是跨站链路，
        //    WebView 默认**拒绝第三方 Cookie** → 登录回调页拿不到会话，停在空白页/登录循环。
        settings.javaScriptCanOpenWindowsAutomatically = true
        // ② window.open：OAuth 弹窗要走 window.open，默认被静默拦掉（既不报错也不渲染）→ 白屏。
        //    打开多窗口支持，并在 onCreateWindow 里把弹窗**转回本实例**加载（见下方 chromeClient）。
        settings.setSupportMultipleWindows(true)
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            // 华为等 ROM 上 DOM storage / cookie 需要显式 flush，否则冷启动会「登录态丢失」。
            cm.flush()
        }
        // 持久 client（不再每次 ask() 临时替换）：页面完成标记 + 错误上报，供休眠轮询与面板共用。
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                android.util.Log.i("NbWebAi", "onPageStarted $url panel=${view === panelView}")
                currentUrl = url; loadProgress = 1; lastError = null; pageDone.set(false)
                // 新页面开始加载：上一页的正文长度/JS 报错作废，避免状态行给出旧结论。
                pageTextLen = -1; pageElementCount = -1; pageTextSnippet = null; lastConsoleError = null
                // 页面被真正重新加载 → 站点里的会话上下文没了，之后的请求必须重贴完整提示。
                liveConversation = false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.i("NbWebAi", "onPageFinished $url panel=${view === panelView}")
                currentUrl = url; loadProgress = 100; pageDone.set(true)
                // 「已加载但整屏白」的判定关键：onPageFinished 只说明主文档完成，
                // SPA/被风控的站点此时正文可能是 0 字（用户看到的就是一片白）。
                // 探一次正文（去空白）字数，状态行据此给出真实结论。
                runCatching {
                    view?.evaluateJavascript(PROBE_JS) { r ->
                        // evaluateJavascript 回传的是 JSON 字符串字面量，用 JSONTokener 正确反转义
                        // （不要手写 replace：页面里的换行/引号/中文都可能带转义）。
                        val text = runCatching { org.json.JSONTokener(r).nextValue() as? String }
                            .getOrNull().orEmpty()
                        val parts = text.split('\u0001')
                        pageTextLen = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
                        pageElementCount = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
                        pageTextSnippet = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                        android.util.Log.i(
                            "NbWebAi",
                            "页面探测：正文 $pageTextLen 字 / 元素 $pageElementCount 个 开头=「${pageTextSnippet ?: ""}」 url=$url"
                        )
                    }
                }.onFailure { android.util.Log.w("NbWebAi", "正文探测失败：${it.message}") }
            }

            override fun onReceivedError(
                view: WebView?, request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    android.util.Log.w("NbWebAi", "onReceivedError ${error?.errorCode} ${error?.description} ${request.url}")
                    lastError = "加载失败 ${error?.errorCode}：${error?.description ?: ""}"
                    pageDone.set(true)
                }
            }
            // ③ 页面「能打开但一片白」还有一种常见原因：主文档返回 4xx/5xx（Cloudflare 拦截、
            //    地区限制、需要登录的入口）。默认 onReceivedError 不会为 HTTP 状态码触发，
            //    于是界面既不报错也不渲染 → 用户只看到白屏。这里显式上报状态码。
            override fun onReceivedHttpError(
                view: WebView?, request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    android.util.Log.w("NbWebAi", "onReceivedHttpError $code ${request.url}")
                    lastError = "站点返回 HTTP $code（可能被风控/地区限制，或该入口需要登录）"
                }
            }
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) {
                lastError = "证书校验失败：${error?.url ?: ""}"; handler?.cancel(); pageDone.set(true)
            }
            // 渲染进程崩溃/被系统回收（低内存）时必须处理：不处理的话整块区域会永久停在空白
            // 页面上，用户以为「白屏死机」。返回 true 表示「已处理」（否则系统会杀掉宿主进程），
            // 同时销毁这个实例并置重建标记，面板会据此重建一个干净的 WebView。
            override fun onRenderProcessGone(
                view: WebView?, detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                android.util.Log.e(
                    "NbWebAi",
                    "onRenderProcessGone crashed=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}"
                )
                lastError = "网页渲染进程${if (detail?.didCrash() == true) "崩溃" else "被系统回收"}（内存不足）。" +
                    "已自动重建，请重新加载/登录。"
                pageDone.set(true)
                if (view === panelView) {
                    panelView = null
                    panelRebuild.set(true)
                }
                if (view === shared) {
                    shared = null
                    sharedUrl = null
                }
                runCatching { view?.destroy() }
                return true
            }
        }
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                android.util.Log.i("NbWebAi", "onReceivedTitle $title panel=${view === panelView}")
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) { loadProgress = newProgress }
            // 之前这里把控制台消息全部吞掉：白屏时 logcat 一点线索都没有。
            // 现在 error 级别的消息记下来（状态行直接显示），其余只写 logcat。
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return true
                val line = "${msg.sourceId()}:${msg.lineNumber()} $text"
                if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    lastConsoleError = text.take(160)
                    android.util.Log.w("NbWebAi", "console.error $line")
                } else {
                    android.util.Log.i("NbWebAi", "console ${msg.messageLevel()} $line")
                }
                return true
            }
            // OAuth 弹窗（「使用 Google/Apple 账号登录」）走 window.open：默认被拦 → 白屏。
            // 标准做法：接住这次弹窗，用它的 WebViewTransport 交回一个「只用来抓目标地址」的
            // 临时 WebView，再把目标地址转回**当前实例**加载（登录态与后续跳转都在同一实例里，
            // 回调页也不会孤儿化）。
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = runCatching { WebView(context) }.getOrNull() ?: return false
                android.util.Log.i("NbWebAi", "onCreateWindow dialog=$isDialog gesture=$isUserGesture")
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?, request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val target = request?.url?.toString()
                        if (!target.isNullOrBlank() && target != "about:blank") {
                            android.util.Log.i("NbWebAi", "OAuth 弹窗转回主实例：$target")
                            runCatching { view?.loadUrl(target) }
                            runCatching { v?.destroy() }
                            return true
                        }
                        return false
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private var shared: WebView? = null
    private var sharedUrl: String? = null

    /** 最近一次真正跑完的 ask 用的是哪个实例：判断「页面上的对话还活着」必须按实例来，
     *  否则面板实例与离屏实例会互相把对方的 URL 状态当成自己的（复用判断会错）。 */
    private var lastAskView: WebView? = null

    // ── 离屏实例的宿主窗口（真机根因修复）────────────────────────────────────────
    // 取证：离屏 WebView **从未 attach 到任何窗口**时，Chromium 不做 vsync 调度 →
    // requestAnimationFrame 不触发 → DeepSeek / Kimi / 豆包 这类 Next.js/React SPA
    // 永远停在「框架根 div」不水合：DOM 只有 1 个元素、正文 0 字。
    // 症状：gateway 报「没找到输入框（正文0字/元素1个）」，而**同一个 URL** 在网关窗口里
    // （已挂载、正常渲染）有 223 个元素、输入框就摆在那儿。
    // 修复：把离屏实例挂到当前 Activity 的 content 上（1×1、alpha=0、不吃触摸/焦点）。
    // 已挂载即可获得 vsync，SPA 正常水合，用户看不见也不受影响。
    @Volatile private var hostActivity: android.app.Activity? = null

    fun onActivityResumed(a: android.app.Activity) {
        hostActivity = a
        attachSharedIfNeeded()
    }

    fun onActivityPaused(a: android.app.Activity) {
        if (hostActivity === a) hostActivity = null
    }

    fun onActivityDestroyed(a: android.app.Activity) {
        if (hostActivity === a) hostActivity = null
        // 宿主窗口没了：把实例摘下来（**保留**实例与页面状态），下次有 Activity 时再挂回去。
        runCatching { (shared?.parent as? android.view.ViewGroup)?.removeView(shared) }
    }

    /** 把离屏实例挂到当前 Activity（幂等）。返回是否处于「已挂载」状态。 */
    private fun attachSharedIfNeeded(): Boolean {
        val v = shared ?: return false
        if (v.parent != null) return true
        val a = hostActivity ?: return false
        return runCatching {
            val root = a.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return@runCatching false
            // 视口给正常尺寸（1×1 的视口会把站点压成畸形布局），但**整块平移到窗口之外**：
            // 既不参与绘制、也落在父容器 bounds 之外（触摸派发按 bounds 命中，不会抢用户的手势），
            // 同时因为仍在视图树里 → Chromium 正常排 vsync，SPA 才会水合。
            v.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            v.translationX = 30_000f
            v.alpha = 0f
            v.isClickable = false
            v.isFocusable = false
            v.setBackgroundColor(0)
            root.addView(v)
            android.util.Log.i(
                "NbWebAi",
                "离屏实例已挂到宿主窗口 ${a.javaClass.simpleName}（获得 vsync，SPA 才会水合）"
            )
            true
        }.getOrDefault(false)
    }

    /** 回收离屏实例（僵死/不水合时换一个干净的）。 */
    private fun recycleShared() {
        val v = shared ?: return
        shared = null
        sharedUrl = null
        if (lastAskView === v) lastAskView = null
        runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
        runCatching { v.stopLoading() }
        runCatching { v.destroy() }
        android.util.Log.i("NbWebAi", "离屏实例已回收重建")
    }

    /**
     * 供「网桥」面板里的可见浏览器使用：拿到同一个 WebView（登录态与桥接共用）。
     * 返回 null = 系统 WebView 不可用（此时面板显示原因，而不是一片空白）。
     */
    fun ensureWebView(context: Context, url: String?): WebView? {
        val view = shared ?: runCatching { newWebView(context) }
            .onFailure { unavailable = "系统 WebView 不可用（${it.message ?: it.javaClass.simpleName}）。" +
                "请在系统设置里启用 Android System WebView，或改用在线 API 服务商。" }
            .getOrNull()
            ?.also { shared = it }
        if (view != null && !url.isNullOrBlank() && view.url != url) {
            sharedUrl = url
            lastError = null; loadProgress = 1; view.loadUrl(url)
        }
        return view
    }

    // ── 面板专用 WebView 实例（与离屏桥接的 shared 分开） ──────────────────────────
    // 真机取证（logcat / huawei.webview 132）：面板复用离屏实例时，页面 `page load start`
    // 之后 28ms 就被 `StopAllLoaders` / `DetachFromFrame`，url_request 报 -3 (ERR_ABORTED)
    // → 一直是白屏。ERR_ABORTED 的含义是「加载被宿主打断」，不是网络也不是内核缺失；
    // 同一 WebView 被两条路径（离屏桥接 / 面板浏览）抢用时，对方一次 loadUrl 就能掐掉这一条。
    // 因此面板改用独立实例，互不打断。
    private var panelView: WebView? = null

    /** 面板视图需要重建（渲染进程崩溃/被回收后置位）。 */
    private val panelRebuild = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 取走「面板需要重建」标记：true = 请重建一次。 */
    fun consumePanelRebuild(): Boolean = panelRebuild.getAndSet(false)

    /** 面板上正在加载的 URL（重载用）。 */
    var panelUrl: String? = null
        private set

    /**
     * 面板里的可见浏览器：拿到**面板专用**的 WebView（登录态 cookie 与桥接共享同一 profile）。
     * 返回 null = 系统 WebView 不可用（面板会显示原因而不是一片空白）。
     */
    fun ensurePanelWebView(context: Context, url: String): WebView? {
        val view = panelView ?: runCatching { newWebView(context) }
            .onFailure {
                unavailable = "系统 WebView 不可用（${it.message ?: it.javaClass.simpleName}）。" +
                    "请在系统设置里启用 Android System WebView，或改用在线 API 服务商。"
            }
            .getOrNull()
            ?.also { panelView = it }
            ?: return null
        if (view.url != url) {
            panelUrl = url
            lastError = null; loadProgress = 1; pageDone.set(false)
            pageTextLen = -1; pageElementCount = -1; pageTextSnippet = null; lastConsoleError = null
            android.util.Log.i("NbWebAi", "panel loadUrl=$url")
            view.loadUrl(url)
        }
        return view
    }

    /** 面板上的「重新加载」：重新发起当前页请求（保留 cookie 登录态）。 */
    fun reload(context: Context) {
        val view = panelView ?: shared ?: return
        lastError = null
        loadProgress = 1
        pageDone.set(false)
        pageTextLen = -1; pageElementCount = -1; pageTextSnippet = null; lastConsoleError = null
        val target = panelUrl
        if (view === panelView && !target.isNullOrBlank()) {
            android.util.Log.i("NbWebAi", "panel reload=$target")
            view.loadUrl(target)
        } else {
            view.reload()
        }
    }

    /**
     * 只把面板视图从旧宿主摘下来（**不销毁**）：Activity 关闭时调用。
     * 实例与页面状态留着，下次打开直接挂到新的 Activity window 上（秒回、不用重新登录），
     * 同时避免「The specified child already has a parent」崩溃。
     */
    fun detachPanelView() {
        val view = panelView ?: return
        runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view) }
        android.util.Log.i("NbWebAi", "panel view detached（保留实例与页面状态）")
    }

    /** 关闭面板浏览器时销毁（cookie/登录态在 WebView profile 里，下次仍复用）。 */
    fun destroyPanelWebView() {
        val view = panelView ?: return
        panelView = null
        panelUrl = null
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
        android.util.Log.i("NbWebAi", "panel view destroyed")
    }

    /**
     * 内核自检：完全不联网，渲染一段本地 HTML。
     * 自检能显示而目标站白屏 → 问题在本机网络/站点风控；自检也白 → 渲染/窗口问题。
     */
    fun panelSelfTest() {
        val view = panelView ?: return
        lastError = null
        pageDone.set(false)
        loadProgress = 100
        view.loadDataWithBaseURL(
            null,
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "</head><body style='font:15px system-ui;padding:20px;background:#0F172A;color:#7DD3FC'>" +
                "<h2 style='color:#34D399'>内核自检 OK</h2>" +
                "<p style='color:#E2E8F0'>WebView 能渲染本地页面（与网络无关）。</p>" +
                "<p style='color:#94A3B8'>若本页可见、目标站白屏，说明是本机网络/站点风控，" +
                "不是渲染问题。</p></body></html>",
            "text/html", "utf-8", null
        )
        android.util.Log.i("NbWebAi", "panel selftest rendered")
    }

    /**
     * 输入自检：本地页面里放普通文本框 + 密码框。
     * 用于区分真机「每打一个字光标就回到行首」到底是**内核/输入窗口**问题，还是**目标站点**
     * 自己在每次重排时重建了 input（SPA 常见）。
     *   自检页里打字正常 → 站点问题；自检页里也乱跳 → 内核/窗口问题。
     */
    fun panelInputSelfTest() {
        val view = panelView ?: return
        lastError = null
        pageDone.set(false)
        loadProgress = 100
        view.loadDataWithBaseURL(
            null,
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "</head><body style='font:16px system-ui;padding:16px;background:#0F172A;color:#E2E8F0'>" +
                "<h3 style='color:#34D399'>输入自检</h3>" +
                "<p>1) 普通文本框</p>" +
                "<textarea rows='2' style='width:100%;font:16px system-ui;padding:8px'></textarea>" +
                "<p>2) 密码框</p>" +
                "<input type='password' style='width:100%;font:16px system-ui;padding:8px'>" +
                "<p style='color:#94A3B8'>两处都正常打字（光标停在末尾）→ 是目标网站自身的问题；" +
                "这里也乱跳 → 是内核/输入窗口的问题（换顶部「键盘」模式看看）。</p>" +
                "</body></html>",
            "text/html", "utf-8", null
        )
        android.util.Log.i("NbWebAi", "panel input selftest rendered")
    }

    /** 面板是否已创建过视图（诊断用）。 */
    fun panelViewAlive(): Boolean = panelView != null

    /**
     * 给无人值守调用（[ask]）取 WebView 实例。
     *
     * 提速点：如果「网桥」面板已经把**同一个站点**打开好并处于可输入状态，就直接复用那个实例 ——
     * 省掉一次整页加载（SPA 站点上最贵的一步）。
     * 两个安全条件：① 实例仍挂在活着的 Activity 上（parent != null，保证在正常渲染/跑 JS，
     * 不会被 Chromium 按后台页面降频）；② 当场探一次输入框确实已就绪（否则宁可重新加载）。
     */
    private suspend fun sharedForAsk(context: Context, ai: WebAi): WebView? {
        val pv = panelView
        if (pv != null && pv.parent != null && panelUrl == ai.url &&
            runCatching { probeInput(pv, ai) }.getOrDefault(false)
        ) {
            // 注意：这里**不再要求** `shared == null`。旧条件会让「上一轮留下的离屏实例」把热着的
            // 面板实例挡在外面，而那个离屏实例可能还没水合 → 真机上就表现为
            // 「网关页面明明开着、输入框就在那儿，却报找不到输入框」。
            android.util.Log.i("NbWebAi", "复用面板实例做网关调用：${ai.url}")
            return pv
        }
        android.util.Log.i("NbWebAi", "面板实例不可复用，改用独立实例：${ai.url}")
        shared?.let { if (it.parent == null) attachSharedIfNeeded() }
        return shared ?: runCatching { newWebView(context) }
            .onFailure {
                unavailable = "系统 WebView 不可用（${it.message ?: it.javaClass.simpleName}）。" +
                    "请在系统设置里启用 Android System WebView，或改用在线 API 服务商。"
            }
            .getOrNull()
            ?.also { shared = it }
            ?.also { attachSharedIfNeeded() }
    }

    /**
     * 问一个网页 AI，返回回答正文。
     *
     * @param onProgress 进度文案（界面可显示「已提交，等待回答…」）
     */
    suspend fun ask(
        context: Context,
        ai: WebAi,
        prompt: String,
        timeoutMs: Long = 120_000,
        onProgress: (String) -> Unit = {},
        /** 可选的「增量提示」：站点页面上这轮对话还活着时只发这一段（见 [liveConversation]）。 */
        deltaPrompt: String? = null
    ): String = withTimeoutOrNull(timeoutMs + 60_000) {
        askInner(context, ai, prompt, timeoutMs, onProgress, deltaPrompt)
    } ?: withContext(Dispatchers.Main) {
        // 总超时兜底：askInner 里任何一步卡住（典型是 evaluateJavascript 回调不来）都不能让调用方
        // 永远等下去 —— 真机表现就是「每失败一次工作台就卡住/异常」。这里回收实例，让下次能重来。
        recycleShared()
        liveConversation = false
        android.util.Log.w("NbWebAi", "ask 总超时（${ai.label}），已回收离屏实例")
        "✗ ${ai.label}：调用超时（页面无响应）。已重置网页实例，请重试；" +
            "若反复出现，请到「网桥」面板点「打开/登录」确认该站点能正常显示。"
    }

    private suspend fun askInner(
        context: Context,
        ai: WebAi,
        prompt: String,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
        deltaPrompt: String?
    ): String = withContext(Dispatchers.Main) {
        // ── 快速失败守卫（2.12.77 真机取证）──────────────────────────────────────────
        // 熄屏 / 锁屏时 Chromium 会冻结 renderer：页面不出字、evaluateJavascript 不回调，
        // 调用要几分钟才超时（真机取证 elapsed=346s），期间工作台完全点不动。
        // 这里**立刻**给出可执行指引，而不是让用户盯着「已提交，等待站点出字…」发呆。
        screenGuardReason(context)?.let { reason ->
            android.util.Log.w("NbWebAi", "ask 被前置守卫拦下：$reason")
            return@withContext "✗ ${ai.label}：$reason"
        }
        var view = sharedForAsk(context, ai)
        if (view == null) {
            return@withContext "✗ 系统 WebView 不可用" + (unavailable?.let { "：$it" } ?: "") +
                "。可先用「网桥」面板确认，或改用在线 API 服务商。"
        }
        // 让 WebView 组件进入「前台」状态：恢复 layout / 解析 / JS 定时器。
        // 这正是官方文档里「应用重新可见」时该做的事 —— 网关需要在离屏/后台也把页面 JS 跑起来，
        // 否则站点前端不会渲染流式回答（真机症状：正文长度始终不变、blank-answer）。
        runCatching { view.onResume(); view.resumeTimers() }
            .onFailure { android.util.Log.w("NbWebAi", "恢复 WebView 定时器失败：${it.message}") }

        // ① 提速关键：同一个站点、且页面上的对话还活着 → 只发增量，并且**不重新打开页面**
        //    （重开页面 = 重新登录 + 重新挂载 SPA + 站点把整段历史当新对话重读，最慢的一步）。
        lastInputCandidates = -1
        // 面板实例已经打开着同一个站点 → 视为「已加载好」：**绝不对它 loadUrl**
        // （对面板实例发起 loadUrl 会把面板自己的加载掐掉 —— 历史上白屏 ERR_ABORTED 的成因）。
        val panelHot = view === panelView && panelUrl == ai.url
        if (panelHot) {
            sharedUrl = ai.url
            lastAskView = view
        }
        // 「页面上的对话还活着」必须按**实例**判断：面板实例与离屏实例不能互相冒用 URL 状态。
        val sameView = view === lastAskView && sharedUrl == ai.url
        var reuse = sameView && liveConversation && !deltaPrompt.isNullOrBlank()
        // ② 复用前**验一下**：页面上还留着我们上一轮发出去的那段提示词吗？
        //    真机症状：页面看着还在（其实已被站点重置成新对话/首页），于是只发「接着上一轮继续 + 最新一句」
        //    —— 站点手里没有上下文，回答必然**答非所问**。验不过就退回发完整提示。
        if (reuse) {
            val keep = lastPromptFp
            val ok = if (keep.isNullOrBlank()) false else runCatching {
                evalJs(
                    view,
                    "document.body.innerText.replace(/\\s/g,'').indexOf(${org.json.JSONObject.quote(keep)})>=0"
                ).contains("true")
            }.getOrDefault(false)
            if (!ok) {
                android.util.Log.w("NbWebAi", "复用检查未过：页面里找不到上一轮的提示词 → 本轮改发完整提示")
                reuse = false
            }
        }
        val effective = if (reuse) deltaPrompt!! else prompt
        if (!reuse) lastPromptFp = promptFingerprint(effective)
        if (!sameView) {
            onProgress("打开 ${ai.label} …")
            var ready = loadAndReady(context, view, ai, effective, onProgress)
            if (!ready && view !== panelView && attachSharedIfNeeded()) {
                // 自愈①：离屏实例没挂宿主窗口 → 不排 vsync → SPA 不水合（DOM 停在 1 个元素）。
                // 挂上宿主后再试一次。
                onProgress("页面没渲染出来 → 挂宿主窗口后重试…")
                ready = loadAndReady(context, view, ai, effective, onProgress)
            }
            if (!ready && view !== panelView) {
                // 自愈②：实例僵死（渲染进程被回收后 loadUrl 静默失效）→ 换一个干净实例再来一次。
                recycleShared()
                val fresh = sharedForAsk(context, ai)
                if (fresh != null && fresh !== view) {
                    view = fresh
                    onProgress("实例异常 → 已重建后重试…")
                    ready = loadAndReady(context, view, ai, effective, onProgress)
                }
            }
            sharedUrl = ai.url
            lastAskView = view
            liveConversation = false
            if (!ready) {
                markBad(context, ai.id, "页面没能就绪", 10 * 60_000L)
                return@withContext notReadyMessage(ai)
            }
        }
        // 地区限制（Claude/Gemini 在中国大陆常见）必须**立刻换站**：这种页面看着有输入框，
        // 填进去也发不出去，再等就是把用户的时间耗在注定失败的站点上。
        regionBlockReason(view)?.let { why ->
            markBad(context, ai.id, why, 24 * 3600_000L)
            return@withContext "✗ ${ai.label}：$why。这是**地区限制**（不是没登录）→ " +
                "请在「网桥」面板把它「停用」，并把可用的站点（如 DeepSeek/豆包/Kimi）设为「首选」。"
        }
        val beforeLen = bodyLen(view)
        val beforeText = bodyTextLen(view)
        android.util.Log.i("NbWebAi", "ask ${ai.label} url=${view.url} bodyLen=$beforeLen textLen=$beforeText 候选输入框=$lastInputCandidates reuse=$reuse len=${effective.length}")
        onProgress("定位输入框…")
        if (!fillPrompt(view, ai, effective)) {
            markBad(context, ai.id, "找不到输入框/写不进去", 10 * 60_000L)
            return@withContext notReadyMessage(ai)
        }
        onProgress("提交，等待回答…")
        val preNodes = messageNodeCount(view)
        val fp = promptFingerprint(effective)
        val fpTail = promptFingerprintTail(effective)
        val submit = submitPrompt(view, ai, onProgress)
        if (submit.startsWith("✗")) {
            // 站点没接收这轮消息：不能算成功，也不能让用户白等 120s。
            liveConversation = false
            markBad(context, ai.id, if (submit.contains("尚未登录")) "可能未登录" else "消息无法提交", 10 * 60_000L)
            return@withContext submit
        }
        liveConversation = true
        lastAskView = view
        // ★★ 闸门一：确认「这条消息真的落到了页面上」。submitPrompt 的判据只能证明**我们这边**点了发送，
        //    证明不了站点收了这轮（未登录/要过验证/被风控时站点照样把输入框清空）。找不到我们这条消息
        //    就立刻失败换站，不再傻等 90s —— 用户截图里的「已提交，等待站点出字…（65s）」就是这么来的。
        val notLanded = waitLanded(view, ai, fp, fpTail, preNodes, LANDED_WAIT_MS, onProgress)
        if (notLanded != null) {
            liveConversation = false
            // 归因：页面上还挂着登录入口 → 几乎可以肯定是「没登录」，直接把结论和做法说清楚，
            // 而不是让用户对着「消息没能送出去」自己猜（这是真机上反馈最多的一条）。
            val wall = loginWallReason(view)
            markBad(context, ai.id, if (wall != null) "站点未登录" else "消息没能送出去", 10 * 60_000L)
            saveGatewayDiag(
                context, ai, view,
                "send-failed: $notLanded（提交信号=$submit nodes=$preNodes" +
                    (wall?.let { "，页面还挂着「$it」" } ?: "") + "）"
            )
            return@withContext if (wall != null) {
                "✗ ${ai.label}：**这个站点没登录** —— $notLanded，而且页面上还挂着「$wall」这类登录入口。" +
                    "未登录时站点**不会**接收任何消息（这就是之前一直「发不出去」的真实原因）。" +
                    "请到「网桥」面板打开 ${ai.label}，登录一次即可（登录态存在内置浏览器里，之后长期有效）；" +
                    "已自动改试下一个站点。当前页面：${view.url}。"
            } else {
                "✗ ${ai.label}：消息没能送出去 —— 输入框看起来已提交（$submit），但$notLanded。" +
                    "多半是**未登录 / 要过验证（验证码）/ 被风控**，或站点改版成了空壳编辑器。" +
                    "已自动改试下一个站点；若所有站点都这样，请在「网桥」面板打开 ${ai.label} 亲手发一句话确认能正常对话。" +
                    "当前页面：${view.url}。"
            }
        }
        // ★ 关键修正（真机症状：AI 只会把我们自己的提示词复述一遍、完全不执行任务）：
        // 提交成功后页面**立刻**会多出「我们自己刚发的那段提示词」气泡；旧实现仍用提交前的基线做
        // 增量抓取，于是「新增文本」= 提示词回显 → 被当成 AI 的回答返回，并且因为已有文本被当成
        // 已出字而立刻结束抓取（用户看到的就是「只会输出这一段文字」）。
        // 现在：等气泡落地 → **重新取基线** → 只把此后新增的内容算作回答。
        delay(SETTLE_MS)
        val ansLen = bodyLen(view)
        val ansText = bodyTextLen(view)
        // echoGuard 传**完整提示词**：里面既有「末尾比对」也有我们提示词的独有哨兵（【工具调用协议】等），
        // 只要抓到的内容还带着它们，就判定为回显 → 如实失败 → 上层换站重试。
        val answer = collectReply(view, ansLen, ansText, timeoutMs, onProgress, echoGuard = effective)
        if (answer.isBlank()) {
            // 没抓到正文，不能保证站点那边推进了对话，下一轮必须重贴完整提示。
            liveConversation = false
            val wall = loginWallReason(view)
            markBad(context, ai.id, "提交后没出正文", 10 * 60_000L)
            saveGatewayDiag(
                context, ai, view,
                "blank-answer: ${lastCollectNote ?: ""}" + (wall?.let { "，页面还挂着「$it」" } ?: "")
            )
            "✗ ${ai.label}：没抓到回答正文 —— 页面里只出现了「我们发过去的提示词回显」或站点首页/页脚" +
                "文字（多半是这轮没真正生成：未登录、要过验证、被风控，或提交其实没生效）。" +
                (wall?.let { "页面上还挂着「$it」这类登录入口 → 站点很可能**没登录 / 登录掉了**，请到「网桥」面板登录一次。" } ?: "") +
                (lastCollectNote?.let { "本轮取证：$it。" } ?: "") +
                "已自动改试下一个站点；若所有站都这样，请在「网桥」面板打开 ${ai.label} 确认已登录、" +
                "能正常对话。当前页面：${view.url}。"
        } else {
            // 这个站刚刚真的答出来了：记下来，下次优先用它（页面/登录态都是热的，最快）。
            setLastOk(context, ai.id)
            clearBad(context, ai.id)
            "[${ai.label}]\n$answer"
        }
    }

    /** 「没找到输入框」的统一说法：带出**真实证据**（正文多少字、页面开头写了什么）。
     *  旧文案只说「可能是登录页/改版」，用户无从判断；真机实测最常见的是站点给了空壳页
     *  （正文 20~30 字），这时正确出路是换 UA 重载 / 用系统浏览器打开 / 改用在线 API。 */
    private fun notReadyMessage(ai: WebAi): String {
        val n = pageTextLen
        val head = pageTextSnippet?.takeIf { it.isNotBlank() }
        val headText = head?.let { "，页面开头：「${it.take(60)}」" } ?: ""
        val el = pageElementCount
        val cand = lastInputCandidates
        val ev = "元素 ${if (el >= 0) "$el 个" else "未探测"} / 候选输入框 ${if (cand >= 0) "$cand 个" else "未探测"}" +
            " / 宿主窗口 ${if (shared?.parent != null || lastAskView === panelView) "已挂载" else "未挂载"}"
        return if (el in 1..40) {
            "✗ ${ai.label}：没找到输入框（正文 $n 字 / $ev$headText）。" +
                "页面几乎是空壳 → 站点很可能拒绝内置 WebView：请在网关里点「UA:桌面」重载，" +
                "或点「浏览器」用系统浏览器打开确认能正常显示。"
        } else {
            "✗ ${ai.label}：没找到输入框（正文 ${if (n >= 0) "$n 字" else "未探测"} / $ev$headText）。" +
                "页面有真实内容但挑不出输入框 → 大概是站点改版了；请在配置里手动填写输入框选择器，" +
                "或先用「网桥」面板打开它确认已登录。"
        }
    }


    /**
     * 打开站点并**等到「能输入」**，而不是等到 onPageFinished。
     * 真机上 onPageFinished 只代表主文档完成：SPA 的输入框可能更晚挂载；反过来也常见——
     * 输入框在 onPageFinished 之前就已就绪。所以这里直接轮询真正的前置条件（输入框可见），
     * 命中即返回。相比旧的「等 onPageFinished（最长 40s）+ 固定 sleep 2.5s」明显更快也更稳。
     *
     * 另外自带一次**空壳页自动救场**：移动 UA 被站点拒绝（正文只剩几十字）时换桌面 UA 重载一次。
     * 无人值守的调用里失败只会变成一句错误文案，所以这一次自动重试很划算。
     */
    private suspend fun loadAndReady(
        context: Context, view: WebView, ai: WebAi, prompt: String, onProgress: (String) -> Unit
    ): Boolean {
        var attempt = 0
        while (attempt < 2) {
            pageDone.set(false)
            lastError = null
            pageTextLen = -1
            pageElementCount = -1
            pageTextSnippet = null
            lastConsoleError = null
            view.loadUrl(ai.url)
            var waited = 0
            // 单次加载等待 15s（原来 25s）：等不到输入框就该快速换站/换 UA，而不是让用户干等。
            val budget = 15_000
            while (waited < budget) {
                if (probeInput(view, ai)) return true
                delay(350)
                waited += 350
                if (waited % 3_500 == 0) onProgress("等待 ${ai.label} 就绪…（${waited / 1000}s）")
            }
            // 空壳页自动救一次：移动 UA 被站点拒绝时换桌面 UA 常常立刻出内容。
            if (attempt == 0 && !uaDesktop && pageTextLen in 0..80) {
                onProgress("页面仅 ${pageTextLen} 字（空壳页）→ 换桌面 UA 重试…")
                uaDesktop = true
                applyCurrentUa(context)
                attempt++
                continue
            }
            break
        }
        return fillPrompt(view, ai, prompt)
    }

    /** 找输入框的 JS（只找不写）。 */
    // ───────────────────────── 输入框识别：打分挑选 ─────────────────────────
    /**
     * 在页面里**打分挑选**输入框，结果放进 window.__nbPickEl / window.__nbCands。
     *
     * 旧实现（按 `['div[contenteditable=true]','textarea','[role=textbox]',…]` 取列表里最后一个
     * 可见元素）太脆 —— ChatGPT 改版后就是它导致「找不到输入框」。改成打分后规则可解释、能加站点特例：
     * 可见基础分；位于页面下半部（聊天输入框都在下面）+40；可编辑 +40/+30；
     * placeholder/id/aria 命中输入类关键词 +15，「搜索」类关键词 -60；宽度越大越像聊天框；
     * 配置里的选择器命中额外 +500（用户手填的选择器永远优先）。
     */
    private val PICK_JS = """
(function(){
  var sel = window.__nbSel || '';
  function meta(e){
    return (((e.getAttribute && e.getAttribute('placeholder')) || '') + ' ' + (e.id || '') + ' ' +
            (e.className && e.className.toString ? e.className.toString() : '') + ' ' +
            ((e.getAttribute && e.getAttribute('aria-label')) || '')).toLowerCase();
  }
  function score(e){
    if (!e) return -1;
    var r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return -1;
    var s = 100;
    if (r.top > window.innerHeight * 0.35) s += 40;
    if (e.getAttribute('contenteditable') === 'true') s += 40;
    if (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') s += 30;
    if (e.readOnly || e.disabled) s -= 300;
    var t = meta(e);
    var good = ['message','chat','prompt','input','send','ask','输入','消息','提问','聊天'];
    var bad  = ['search','搜索','筛选','find'];
    for (var i = 0; i < good.length; i++) { if (t.indexOf(good[i]) >= 0) s += 15; }
    for (var j = 0; j < bad.length; j++)  { if (t.indexOf(bad[j])  >= 0) s -= 60; }
    s += Math.min(30, Math.round(r.width / 40));
    return s;
  }
  var out = [];
  var list = document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"],input[type="text"],input:not([type])');
  for (var i = 0; i < list.length; i++) { var sc = score(list[i]); if (sc >= 0) out.push({e: list[i], s: sc, src: 'auto'}); }
  if (sel) { try { var ex = document.querySelector(sel); if (ex) out.push({e: ex, s: score(ex) + 500, src: 'selector'}); } catch(e) {} }
  out.sort(function(a, b){ return b.s - a.s; });
  window.__nbCands = out.length;
  window.__nbPickEl = out.length ? out[0].e : null;
  if (!out.length) return 'NOINPUT|' + out.length;
  return 'OK|' + out.length + '|' + out[0].s + '|' + out[0].src;
})()
""".trimIndent()

    /** 探一次输入框："OK|候选数|分数|来源" / "NOINPUT|候选数"；顺带刷新 lastInputCandidates。 */
    private suspend fun pickInput(view: WebView, ai: WebAi): String {
        val sel = JSONObject.quote(ai.inputHint)
        val raw = evalJs(view, "(function(){window.__nbSel = $sel;})();" + PICK_JS).trim('"')
        lastInputCandidates = raw.split('|').getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        return raw
    }

    /** 只看「输入框在不在」：就绪轮询里不能反复写上万字提示（很慢）。 */
    private suspend fun probeInput(view: WebView, ai: WebAi): Boolean = pickInput(view, ai).startsWith("OK")

    /**
     * 连续多少次 evaluateJavascript 超时（成功即清零）。
     *
     * 2.12.77 真机取证：renderer 被系统冻结时，每一次 JS 调用都要等满 [EVAL_TIMEOUT_MS]，
     * 轮询几十次 → 一直拖到 `ask` 的总超时才返回（实测 elapsed=346352ms），期间主线程被反复占住。
     * 连续 3 次超时已足以判定「页面不响应」→ 立刻按失败收工并给出明确原因。
     */
    @Volatile private var evalTimeoutStreak = 0

    private suspend fun evalJs(view: WebView, js: String): String {
        val r = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching { view.evaluateJavascript(js) { res -> if (cont.isActive) cont.resume(res.orEmpty()) } }
                    .onFailure { if (cont.isActive) cont.resume("") }
            }
        }
        if (r == null) {
            // 真机坑：evaluateJavascript 的回调在「渲染进程被回收 / 页面被冻结 / 页面正在导航」时
            // **永远不来**。这里计数：连续超时说明页面真的不响应，交给 [collectReply] 提前收工。
            evalTimeoutStreak++
            android.util.Log.w(
                "NbWebAi",
                "evalJs 超时 ${EVAL_TIMEOUT_MS}ms（页面无响应，连续第 $evalTimeoutStreak 次）：${js.take(60)}"
            )
            return ""
        }
        evalTimeoutStreak = 0
        return r
    }

    /** 页面正文字数（innerText）：人眼可见的文字，用来判断「页面变了没有」。 */
    private suspend fun bodyLen(view: WebView): Int =
        evalJs(view, "document.body?document.body.innerText.length:0").trim('"').toDoubleOrNull()?.toInt() ?: 0

    /** 地区限制/不可用页面的特征串（Claude、Gemini、Copilot 等在中国大陆会直接给这类页面）。 */
    private val REGION_HINTS = listOf(
        "app-unavailable-in-region", "unavailable-in-region",
        "unavailable in your region", "not available in your region",
        "not available in your country", "currently unavailable in your region",
        "该地区", "当前地区不可用", "不支持您所在", "地区限制"
    )

    /**
     * 页面是不是「地区不可用」。返回原因（带证据）或 null。
     *
     * 真机取证：claude.com/app-unavailable-in-region —— 页面上**仍然有**看着像输入框的元素，
     * 我们填得进去，但点发送/回车永远不成功。旧逻辑会把它当「未登录」反复重试，
     * 白等一轮超时。这里直接按「地区限制」判定 → 立刻换站（见 [ask] 与聚合网关的选站）。
     */
    private suspend fun regionBlockReason(view: WebView): String? {
        val url = view.url.orEmpty()
        if (REGION_HINTS.any { url.lowercase().contains(it) }) return "当前地区不可用（页面：$url）"
        val head = unescapeJs(
            evalJs(view, "(document.body?document.body.innerText:'').slice(0,600)").trim('"')
        )
        val hit = REGION_HINTS.firstOrNull { head.lowercase().contains(it) }
        return if (hit != null) "当前地区不可用（页面提示：「${head.trim().take(60)}」）" else null
    }

    /** 页面全部文字（textContent，含隐藏节点）：比 innerText 敏感，作为增长判定的兜底信号。 */
    private suspend fun bodyTextLen(view: WebView): Int =
        evalJs(view, "document.body?document.body.textContent.length:0").trim('"').toDoubleOrNull()?.toInt() ?: 0

    /**
     * **对话区文字**（不含输入框 / composer）—— 一个 JS 表达式（IIFE），返回 `document.body` 里
     * 除输入控件之外的文字。
     *
     * ★★ 真机取证（`gateway.log`：Kimi 与 DeepSeek 都命中）：
     * `document.body.innerText` **包含 contenteditable 输入框里的文字** —— 而现在各家的输入框
     * 基本都是 `contenteditable div`（Kimi/豆包/千问/DeepSeek 全一样）。后果是：
     * 我们刚填进输入框、其实**根本没发出去**的那段提示词，会被 `innerText` 带出来 →
     * 「页面上已经出现我们那条消息」被判为真 → 送达闸门误判通过 → 白等一轮出字 →
     * 最后报「blank-answer / 页面有消息但站点不出字」。用户看到的就是「网关发不出去、还乱报原因」。
     *
     * 这里改成按 DOM 文本节点取值，跳过 `textarea/input/contenteditable/[role=textbox]` 子树，
     * 得到的才是**真正的对话区文字**：未登录时它就是站点首页的外壳（含「登录以同步」这类入口），
     * 一眼就能判定真实原因。
     */
    private val CONV_TEXT_JS = """
(function(){
  try{
    var b=document.body; if(!b) return '';
    function editable(n){
      while(n && n!==b){
        if(n.nodeType===1){
          var t=(n.tagName||'').toLowerCase();
          if(t==='textarea'||t==='input') return true;
          if(n.getAttribute&&n.getAttribute('contenteditable')==='true') return true;
          if(n.getAttribute&&n.getAttribute('role')==='textbox') return true;
        }
        n=n.parentNode;
      }
      return false;
    }
    function dead(n){
      while(n && n!==b){
        if(n.nodeType===1){
          var t=(n.tagName||'').toLowerCase();
          if(t==='style'||t==='script'||t==='noscript'||t==='template'||t==='head') return true;
          // 注意：**不要**过滤 aria-hidden —— 真机取证时发现流式答案容器/虚拟列表项常带这个属性，
          // 过滤它会把「正在生成的回答」本身滤掉，于是又变回「抓不到回答正文」。
        }
        n=n.parentNode;
      }
      return false;
    }
    var out=[]; var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null); var n;
    while(n=w.nextNode()){
      if(editable(n)||dead(n)) continue;
      var s=n.nodeValue;
      if(s&&s.replace(/\s/g,'').length>0) out.push(s);
    }
    return out.join('\n');
  }catch(e){ return ''; }
})()
""".trimIndent()

    /** 对话区文字（排除输入框），见 [CONV_TEXT_JS] 的取证说明。 */
    private suspend fun convText(view: WebView): String =
        runCatching { unescapeJs(evalJs(view, CONV_TEXT_JS).trim('"')) }.getOrDefault("")

    /** 对话区文字长度（去空白）。 */
    private suspend fun convLen(view: WebView): Int = convText(view).count { !it.isWhitespace() }

    /** 「这个站点还没登录」的页面特征串（页面上的登录入口文案）。 */
    private val LOGIN_HINTS = listOf(
        "登录以同步", "登录可同步", "登录后同步", "登录以继续", "请先登录", "立即登录",
        "登录/注册", "扫码登录", "手机号登录", "验证码登录", "未登录", "log in", "sign in"
    )

    /**
     * 页面是不是一副「未登录」的样子；命中则返回证据串（写进失败文案与取证日志）。
     *
     * 只用于**发送失败 / 没有回答之后**的归因（把真实原因摆给用户看），不在发送前拦截：
     * 不少站点把「登录」入口常驻在侧栏/页脚，拿它当发送前提会误伤已经登录好的站点。
     */
    private suspend fun loginWallReason(view: WebView): String? {
        val t = convText(view).lowercase()
        if (t.isBlank()) return null
        return LOGIN_HINTS.firstOrNull { t.contains(it.lowercase()) }
    }

    /** 输入框里已输入的字数（去空白）；-1 = 元素没了，-2 = 脚本异常。 */
    private suspend fun composerLen(view: WebView, ai: WebAi): Int {
        val js = "(function(){try{var e=window.__nbPickEl;if(!e||!e.isConnected){return '-1';}" +
            "var v=(e.value!==undefined&&e.value!==null)?e.value:(e.innerText||e.textContent||'');" +
            "return ''+(''+v).replace(/\\s/g,'').length;}catch(err){return '-2';}})()"
        return evalJs(view, js).trim('"').trim().toIntOrNull() ?: -2
    }

    /**
     * 写提示词进输入框，**写完必须验证**。
     *
     * 真机 DeepSeek 现象：「等待回答开始…」永远不动，用户看到「消息无法发送」。根因之一是
     * 文字其实没进 composer（React 没收到 input 事件 / SPA 重渲染把 textarea 换掉），
     * 之后点发送发出去的是空消息。所以这里写完立刻回读长度，失败再退到 execCommand 插入。
     */
    private suspend fun fillPrompt(view: WebView, ai: WebAi, prompt: String): Boolean {
        if (!pickInput(view, ai).startsWith("OK")) return false
        val p = JSONObject.quote(prompt)
        val js = """
(function(){
  var pp = $p;
  var el = window.__nbPickEl;
  if (!el) return 'NOINPUT';
  try { window.__nbPrompt = pp; } catch(e) {}
  try { el.scrollIntoView({block:'center'}); } catch(e) {}
  el.focus();
  if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, pp);
    el.dispatchEvent(new InputEvent('input', {bubbles:true, data:pp, inputType:'insertText'}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
  } else {
    el.innerHTML = '';
    var ok = false;
    try { ok = document.execCommand('insertText', false, pp); } catch(e) { ok = false; }
    if (!ok || el.innerText.replace(/\n/g,'').length < pp.length / 2) { el.textContent = pp; }
    el.dispatchEvent(new InputEvent('input', {bubbles:true, data:pp, inputType:'insertText'}));
  }
  var v = (el.value !== undefined && el.value !== null) ? el.value : (el.innerText || el.textContent || '');
  var n = ('' + v).replace(/\s/g,'').length;
  return n > 0 ? 'FILLED|' + n : 'EMPTY';
})()
""".trimIndent()
        if (evalJs(view, js).contains("FILLED")) return true
        // 退路：聚焦后用 execCommand 插入（textarea 上最接近真实键入的方式，React 一定收到）
        evalJs(
            view,
            "(function(){var el=window.__nbPickEl;if(!el)return 'NOINPUT';el.focus();" +
                "try{document.execCommand('selectAll',false,null);" +
                "document.execCommand('insertText',false,window.__nbPrompt||'');}catch(e){}return 'OK';})()"
        )
        delay(300)
        val n = composerLen(view, ai)
        android.util.Log.i("NbWebAi", "fillPrompt 退路 execCommand 后 composerLen=$n")
        return n > 1
    }

    /**
     * 提交（发送）这轮消息：多策略依次尝试，**每一步都验证**输入框是否被清空。
     *
     * 真机教训：旧实现只要「找到按钮点了」或「派发了回车」就当成功，完全不看站点有没有真的接收
     * —— 于是界面永远停在「等待回答开始…」。实际常见原因：SPA 重渲染后焦点丢到 body
     * （回车送到空气里）、发送按钮仍是 disabled（React 没收到 input 事件）、或者**根本没登录**。
     * 判定「已提交」的唯一可靠信号：**输入框内容被清空**（各站在发送成功后都会清空 composer）。
     */
    private suspend fun submitPrompt(view: WebView, ai: WebAi, onProgress: (String) -> Unit): String {
        val sel = JSONObject.quote(ai.inputHint)
        val sendSel = JSONObject.quote(ai.sendHint)

        // ① 先确认文字真的在输入框里（否则站点收到的是空消息，当然不理）。
        var filled = composerLen(view, ai)
        if (filled in 0..1) {
            evalJs(
                view,
                "(function(){var el=window.__nbPickEl;if(!el)return 'NOINPUT';el.focus();" +
                    "try{document.execCommand('selectAll',false,null);" +
                    "document.execCommand('insertText',false,window.__nbPrompt||'');}catch(e){}return 'OK';})()"
            )
            delay(400)
            filled = composerLen(view, ai)
        }
        if (filled == 0) {
            return "✗ ${ai.label}：文字没能写进输入框（站点可能换了编辑器）。" +
                "请在「网桥」面板打开它重试，或在配置里手动填写输入框选择器。"
        }

        // ② 依次尝试：站内选择器 → 关键词按钮 → 输入框旁的按钮 → 真实回车 → 表单 → 页面回车。
        //    判据**不能只看「输入框被清空」**：React/Vue 站点发送后常把输入框节点整体重建，
        //    我们抓到的那个节点变成 isConnected=false（composerLen 返回 -1）——旧逻辑把它当成
        //    「没清空」→ 判定失败、继续乱点页面。真机症状：消息其实发出去了却报「消息没能提交」。
        //    现在的判据（任一成立即算已提交）：输入框清空 / 输入框节点消失 / 出现「停止生成」按钮 /
        //    页面正文增长（新消息气泡出现）。
        val baseLen = bodyLen(view)
        val baseNodes = messageNodeCount(view)
        for (st in listOf("hint", "keyword", "sibling", "key", "form", "enter")) {
            if (st == "key") {
                dispatchEnter(view)   // 平台注入的真实按键（isTrusted），有些站点只认真实事件
            } else {
                evalJs(view, submitJs(st, sendSel, sel))
            }
            delay(if (st == "key" || st == "enter") 1_000 else 700)
            val state = submitState(view, baseLen, baseNodes)
            android.util.Log.i("NbWebAi", "submit 策略=$st 状态=${if (state.sent) "已提交(${state.how})" else "未见提交"} ${state.raw}")
            if (state.sent) {
                onProgress("已提交（${state.how}）")
                return "OK:${state.how}"
            }
        }
        val left = composerLen(view, ai)
        return "✗ ${ai.label}：消息没能提交（已试过点发送按钮 / 点旁边的按钮 / 真实回车，页面也没有任何" +
            "「新消息」迹象；输入框剩余内容 ${if (left == -1) "读不到（节点被换掉）" else "$left 字"}）。" +
            "最常见原因是**尚未登录**（站点要求登录后才允许发送），或站点改版。" +
            "当前页面：${view.url}。请在「网桥」面板打开它确认已登录后重试。"
    }

    /** 各提交策略的 JS。 */
    /** 一次提交尝试后的页面状态判定结果。 */
    private class SubmitState(val sent: Boolean, val how: String, val raw: String)

    /**
     * 判断「消息到底提交出去没有」。
     *
     * 单一判据（输入框被清空）在真实站点上会误判：SPA 发送后会重建输入框节点 →
     * 我们持有的元素 `isConnected=false`（旧代码 composerLen 返回 -1，被判成「没清空」）。
     * 这里综合四个信号：输入框清空 / 输入框节点消失 / 出现「停止生成」按钮 / **出现新消息节点**。
     *
     * ⚠️ 曾经用的「页面正文增长」信号已**删除**：SPA 首页水合也会让正文变长，会被误判成
     * 「已提交」→ 后面把「提示词回显 + 首页文字」当回答抓回来（真机症状：AI 只输出一段预设指令）。
     */
    private suspend fun submitState(view: WebView, baseLen: Int, baseNodes: Int): SubmitState {
        val raw = evalJs(view, SUBMIT_PROBE_JS)
        val json = unescapeJs(raw.trim('"')).trim()
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: return SubmitState(false, "", json.take(80))
        val left = o.optInt("left", -2)
        val gone = o.optBoolean("gone", false)
        val stop = o.optBoolean("stop", false)
        val len = o.optInt("len", 0)
        val nodes = o.optInt("nodes", 0)
        val how = when {
            gone -> "输入框已随页面重建（节点消失）"
            left in 0..1 -> "输入框已清空"
            stop -> "出现「停止生成」按钮"
            nodes > baseNodes -> "页面出现新消息节点（$baseNodes→$nodes）"
            else -> ""
        }
        return SubmitState(
            how.isNotEmpty(), how,
            "left=$left gone=$gone stop=$stop len=$len base=$baseLen nodes=$baseNodes→$nodes"
        )
    }

    /** 平台注入的真实回车（isTrusted=true）：部分站点的发送只认真实键盘事件。 */
    private fun dispatchEnter(view: WebView) {
        runCatching {
            val t = android.os.SystemClock.uptimeMillis()
            view.dispatchKeyEvent(
                android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0)
            )
            view.dispatchKeyEvent(
                android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0)
            )
        }.onFailure { android.util.Log.w("NbWebAi", "注入 Enter 失败：${it.message}") }
    }

    /** 归一化后判断候选文本是不是「我们自己发出去的提示词回显」（是则不能当回答用）。 */
    private fun looksLikeEcho(text: String, guard: String): Boolean {
        if (guard.isBlank()) return false
        val g = guard.filterNot { it.isWhitespace() }
        if (g.length < 24) return false
        // 取中段比对（避免站点在首尾加「复制/编辑」等按钮文字导致整段不相等）
        return text.filterNot { it.isWhitespace() }.contains(g.takeLast(60))
    }

    /** 从抓到的文本里剥掉提示词回显（含尾巴），只留站点新写出来的内容。**忽略空白**比对。 */
    private fun stripEcho(text: String, guard: String): String {
        if (guard.isBlank() || text.isBlank()) return text
        val (norm, map) = normalizeKeepMap(text)
        for (probe in listOf(guard.trim().takeLast(140), guard.trim().takeLast(60))) {
            val p = probe.filterNot { it.isWhitespace() }
            if (p.length < 20) continue
            val i = norm.lastIndexOf(p)
            if (i >= 0) {
                val end = i + p.length
                val rawEnd = if (end < map.size) map[end] else text.length
                return text.substring(rawEnd)
            }
        }
        return text
    }

    /**
     * 归一化（去掉所有空白）后的**下标映射**：`norm[0..]` 的第 i 个字对应 `raw[map[i]]`。
     *
     * 为什么需要它：站点渲染会把提示词里的空格/换行改掉（markdown 折叠、全角转换），
     * 旧实现用原文 `indexOf` 找提示词尾巴 → 找不到 → 回显没被剥掉，剩下站点首页文字被当成回答
     * （真机症状：任务台收到「整段预设指令 + 快速/进阶/探索灵感 + 备案号」）。
     */
    private fun normalizeKeepMap(s: String): Pair<String, IntArray> {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (!c.isWhitespace()) {
                map[sb.length] = i
                sb.append(c)
            }
        }
        return sb.toString() to map
    }

    /**
     * 我们自己提示词里的**独有哨兵**：抓到的「回答」里只要出现这些串，就说明它其实是
     * 「把提示词贴进页面后的回显」，绝不能交给上层（那样模型拿到的是一段没有任何工具调用的废话，
     * 用户看到的就是「任务完不成、工具不调用」）。
     */
    private val PROMPT_SENTINELS = listOf(
        "【工具调用协议】", "===== 工作区约定与工具协议", "可用工具：- read_file",
        "【工作方式】", "【当前环境】", "【边界】", "全自动约定（重要）", "【已启用妙招"
    )

    /** 命中即丢弃的**页面外壳行**（页脚备案号/版权行、登录引导行）。 */
    private val CHROME_STRONG = listOf(
        "内容由AI生成", "请仔细甄别", "京ICP备", "京公网安备", "违法和不良信息",
        "用户协议", "隐私政策", "意见反馈", "商务合作", "下载客户端", "下载App",
        // 真机新增（豆包/Kimi 等首页引导）：这些行出现就说明拿到的是站点外壳，不是回答。
        "立即登录", "扫码登录", "登录后同步", "登录后即可", "开启新对话", "新建对话",
        "历史对话", "免费使用", "手机号登录", "验证码登录", "第三方账号登录"
    )

    /** 只在**短行**（首页建议词/标签）时才丢弃的弱标记。 */
    private val CHROME_WEAK = listOf(
        "探索灵感", "深度研究", "选择项目", "快速", "进阶", "文档", "构建应用",
        "表格", "设计", "PPT", "插件", "联网检索", "写代码", "做PPT",
        // 真机新增（豆包首页推荐流：为你推荐 / 换一换 / 猜你想问 / 资讯…）
        "为你推荐", "换一换", "猜你想问", "热门话题", "推荐阅读", "大家都在问", "最新资讯",
        // 真机新增（2.12.75 取证：DeepSeek 整页可见 innerText 只有这一轮对话 + 输入框周边文案，
        // 偏移量切出来的尾巴正好全是这些行，于是「明明答了也判成站点外壳」）
        "深度思考", "智能搜索", "联网搜索", "复制", "下载", "重新生成", "分享",
        "登录", "登录以同步", "开启新对话", "新建会话", "新对话", "今天", "更多",
        "新建项目", "定时任务", "灵感库", "快速", "进阶"
    )

    /** 是不是「我们自己的提示词回显」（忽略空白比对）。 */
    private fun containsPromptEcho(text: String): Boolean {
        if (text.isBlank()) return false
        val n = text.filterNot { it.isWhitespace() }
        return PROMPT_SENTINELS.any { s -> n.contains(s.filterNot { it.isWhitespace() }) }
    }

    /** 去掉站点外壳行（页脚备案号、首页建议词），保留回答正文。 */
    private fun stripChrome(text: String): String =
        text.split('\n').filterNot { line ->
            val s = line.trim()
            s.isNotEmpty() && (CHROME_STRONG.any { s.contains(it) } || CHROME_WEAK.any { s == it })
        }.joinToString("\n").trim()

    /**
     * 抓不到回答时，剩下的几乎全是站点首页 UI。
     *
     * 判据刻意保守（宁可放过、不可误杀正常回答）：
     * 出现**强标记**（备案号/版权行）→ 直接判为页面外壳；否则要**至少 3 行且过半**是
     * 建议词那种短标签才算。
     */
    private fun chromeHeavy(text: String): Boolean {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        if (lines.any { l -> CHROME_STRONG.any { l.contains(it) } }) return true
        if (looksLikeSuggestionFeed(lines)) return true
        val weak = lines.count { l -> CHROME_WEAK.any { l == it } || (l.length <= 8 && CHROME_WEAK.any { l.contains(it) }) }
        return weak >= 3 && weak * 2 >= lines.size
    }

    /**
     * 首页「推荐问题 / 资讯流」的形状判据（真机症状：豆包首页 SSR 文字被当成 AI 回答）。
     *
     * 取证：抓到的是 `为你推荐 / 右手比左手力气大吗? / 100美元现在能兑换多少人民币? /
     * 什么一年有365天或366天? / 资讯：苹果正式推送 watchOS…` —— 一行「为你推荐」根本凑不到
     * [CHROME_WEAK] 的 3 行门槛，于是被当成合法回答交给上层：模型于是答非所问、也没有任何工具调用。
     * 判据：全是短句（没有一行超过 60 字）且有 ≥2 行是 4~30 字的问句 → 这是推荐流，不是回答。
     * 正常回答总有长句（说明/代码），不会被误杀。
     */
    private fun looksLikeSuggestionFeed(lines: List<String>): Boolean {
        if (lines.size < 3) return false
        if (lines.any { it.length > 60 }) return false
        val q = lines.count { l -> l.length in 4..30 && (l.endsWith("?") || l.endsWith("？")) }
        return q >= 2
    }

    /** 准入判定：这段文字能不能当「AI 的回答」用。 */
    private fun answerUsable(text: String, guard: String): Boolean {
        val t = text.trim()
        if (t.filterNot { it.isWhitespace() }.length < 8) return false
        if (containsPromptEcho(t)) return false
        if (looksLikeEcho(t, guard)) return false
        if (chromeHeavy(t)) return false
        return true
    }

    /**
     * 宽松准入：给「按内容取」的最后一道兜底用。
     *
     * 与 [answerUsable] 的唯一区别是**不再要求至少 8 个字**。真机取证：DeepSeek 对我们
     * 「9乘9等于多少？只回复阿拉伯数字」的回复就是两个字「81」——它被 8 字门槛挡掉，
     * 上层于是报「没抓到回答正文」。回显/外壳判据照旧生效，所以不会把首页文字放进来。
     */
    private fun answerUsableLoose(text: String, guard: String): Boolean {
        val t = text.trim()
        if (t.filterNot { it.isWhitespace() }.length < 2) return false
        if (containsPromptEcho(t)) return false
        if (looksLikeEcho(t, guard)) return false
        if (chromeHeavy(t)) return false
        return true
    }

    /**
     * 上层（任务台/工作台）的最终兜底：这段网页输出能不能当 AI 回答用？
     * 返回不可用的原因；正常返回 null。
     *
     * 真机教训：网页网关把「我们发过去的提示词回显 + 站点首页/页脚文字」当回答返回时，
     * 任务台会拿它当模型回复 —— 于是「AI 只会输出一段预设指令、任务完不成、工具不调用」。
     * 有了这道闸门，上层会**换下一个站点重试**，而不是把废话喂给模型。
     */
    fun replyRejectReason(text: String): String? {
        val t = text.trim()
        if (t.isBlank()) return "返回为空"
        if (containsPromptEcho(t)) return "只回显了我们发过去的提示词（站点没真正生成回答）"
        if (chromeHeavy(t)) return "只抓到站点首页/页脚文字（这轮没抓到回答）"
        return null
    }

    /**
     * 各站点「回答容器」选择器 → 返回**候选列表 JSON 数组**（每个选择器取最后若干可见节点，去重）。
     *
     * 为什么返回列表而不是单个：站点常把「用户自己发的消息」也放进 markdown/answer 容器里，
     * 所以「最后一个匹配节点」很可能就是**我们刚发出去的提示词气泡**。上层会从后往前挑
     * 第一个「不像回显、也不是首页文字」的候选（见 [answerUsable]）。
     */
    private val ANSWER_NODE_JS = """
(function(){
  try{
    var sels = ['[data-message-author-role="assistant"]','.ds-markdown','.markdown',
                '[class*="markdown"]','[class*="assistant"]','[class*="answer"]','[class*="reply"]',
                '.prose','.message-content'];
    var out = [];
    for (var i = 0; i < sels.length; i++) {
      var list = null;
      try { list = document.querySelectorAll(sels[i]); } catch(e) { continue; }
      for (var j = list.length - 1; j >= 0 && out.length < 12; j--) {
        var el = list[j];
        var r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) continue;
        var t = (el.innerText || '').trim();
        if (t.length < 4) continue;
        if (out.indexOf(t) >= 0) continue;
        out.push(t);
      }
      if (out.length >= 12) break;
    }
    return JSON.stringify(out);
  }catch(e){ return '[]'; }
})()
    """.trimIndent()

    /** 解析 [ANSWER_NODE_JS] 返回的候选数组。 */
    private fun parseCandidates(raw: String): List<String> = runCatching {
        val a = JSONArray(raw.trim())
        (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    // ── 锚点取答（真机根因修复②：站点首页文字被当回答）────────────────────────────
    // 取证：豆包首页的 SSR 文字（「为你推荐 / 右手比左手力气大吗? / 资讯：苹果推送 watchOS…」）
    // 被当成 AI 的回答返回给任务台 —— 模型拿到这段首页推荐流，于是「答非所问 + 没有任何工具调用」；
    // 又因为这段文字「看着像回答」，上层每次都换下一个站点重试，用户看到的就是「每次调用的 AI 都不同」。
    // 修复思路：**页面上必须存在我们自己刚发出去的那条消息**才算「站点真的接了这轮」，
    // 并且只接受**文档顺序在那条消息之后**的节点作为回答。找不到那条消息 = 这轮没提交成功 → 如实失败。

    /** 提示词指纹：站点会把长消息折叠显示，但**开头**一定在，所以取开头 40 个非空白字符最稳。 */
    private fun promptFingerprint(prompt: String): String =
        prompt.filterNot { it.isWhitespace() }.take(40)

    /** 备用指纹：开头对不上时再试结尾（有的站点把长消息折成只露结尾的卡片）。 */
    private fun promptFingerprintTail(prompt: String): String =
        prompt.filterNot { it.isWhitespace() }.takeLast(40)

    /**
     * 以「我们自己那条消息」为锚点的取答脚本，返回 JSON：`{"ok":true,"list":[...]}`，
     * 找不到锚点时返回 `{"ok":false,"why":"no-anchor"}`。
     *
     * 候选两层：① 已知回答容器且**排在锚点之后**（[ANSWER_NODE_JS] 的选择器 + 豆包/DeepSeek 专用容器）；
     * ② 结构兜底 —— 从锚点逐层向上，取「该层之后的兄弟块」（未知容器也能覆盖）。
     * 两层都过滤掉：含锚点指纹的（回显）、含输入框的（编辑器/侧栏）、侧栏/导航/推荐流一类 class 的。
     */
    private fun anchorAnswerJs(fp: String): String = """
(function(){
  try{
    var f = ${org.json.JSONObject.quote(fp)};
    if (!f) return JSON.stringify({ok:false,list:[],why:'empty-fp'});
    var norm = function(s){ return (s||'').replace(/\s/g,''); };
    var all = document.querySelectorAll('body *');
    var anchor = null, bestLen = -1;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var tag = (el.tagName || '').toLowerCase();
      if (tag === 'textarea' || tag === 'input') continue;
      if (el.isContentEditable) continue;
      if (el.closest && el.closest('textarea,input,[contenteditable="true"]')) continue;
      // 再排掉**包含**输入框的节点：站点常把「对话区 + 输入框」包在同一个容器里，那个容器的
      // innerText 同样带着「还躺在输入框里、根本没发出去的提示词」→ 会被当成锚点，
      // 于是「没送出去」被误判成「已送达」，最后只抓到一堆首页外壳文字（真机 blank-answer 现场）。
      if (el.querySelector && el.querySelector('textarea,input,[contenteditable="true"]')) continue;
      var t = el.innerText || '';
      if (!t) continue;
      var n = norm(t);
      if (n.indexOf(f) < 0) continue;
      if (anchor === null || n.length < bestLen) { anchor = el; bestLen = n.length; }
    }
    if (!anchor) return JSON.stringify({ok:false,list:[],why:'no-anchor'});
    var skipCls = /sidebar|side-|nav|history|menu|drawer|header|footer|banner|recommend|advert|search|popup|modal|toast/i;
    var cands = [];
    var push = function(el){
      if (!el || cands.indexOf(el) >= 0) return;
      var cls = (el.className && el.className.toString) ? el.className.toString() : '';
      if (skipCls.test(cls)) return;
      if (el.querySelector && el.querySelector('textarea,input,[contenteditable="true"]')) return;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      var t = (el.innerText || '').trim();
      if (t.length < 8 || t.length > 20000) return;
      if (norm(t).indexOf(f) >= 0) return;
      cands.push(el);
    };
    var sels = ['[data-message-author-role="assistant"]','.ds-markdown','.markdown',
                '[class*="markdown"]','[data-testid="message_text_content"]','[class*="flow-markdown"]',
                '[class*="assistant"]','[class*="answer"]','[class*="reply"]','.prose','.message-content'];
    for (var s = 0; s < sels.length; s++) {
      var list = null;
      try { list = document.querySelectorAll(sels[s]); } catch(e) { continue; }
      for (var j = 0; j < list.length; j++) {
        var e2 = list[j];
        try { if (anchor.compareDocumentPosition(e2) & Node.DOCUMENT_POSITION_FOLLOWING) push(e2); } catch(err) {}
      }
    }
    var node = anchor;
    for (var d = 0; d < 7 && node; d++) {
      var sib = node.nextElementSibling, added = 0;
      while (sib && added < 2) { push(sib); if (cands.indexOf(sib) >= 0) added++; sib = sib.nextElementSibling; }
      node = node.parentElement;
    }
    var out = [];
    for (var k = 0; k < cands.length; k++) out.push((cands[k].innerText || '').trim());
    return JSON.stringify({ok:true, list:out});
  }catch(e){ return JSON.stringify({ok:false,list:[],why:'js-err'}); }
})()
    """.trimIndent()

    /**
     * 页面里「对话气泡/消息节点」的数量。
     *
     * 为什么不能再用「页面正文增长」当提交信号：SPA 首页在后台**水合（hydration）**时也会让正文
     * 变长（首页建议词 + 页脚备案号一次性挂上来）。旧实现据此判定「已提交」，于是后续抓取拿到的是
     * 「我们贴进输入框的提示词 + 首页文字」——真机症状就是「AI 只输出一段预设指令」。
     * 只有**出现新的消息节点**才算真的发出去了。
     *
     * ⚠️ 本属性被 [SUBMIT_PROBE_JS] 用字符串模板引用，**必须声明在它之前**
     * （Kotlin 属性按声明顺序初始化，否则会嵌入 "null"）。
     */
    private val MESSAGE_NODE_COUNT_JS = """
(function(){
  var sels = ['.markdown','[class*="markdown"]','[class*="message"]','[class*="chat-item"]',
              '[class*="conversation"]','[class*="bubble"]','[data-message-author-role]'];
  var n = 0;
  for (var i = 0; i < sels.length; i++) {
    try { n += document.querySelectorAll(sels[i]).length; } catch(e) {}
  }
  return n;
})()
    """.trimIndent()

    /** 提交后的页面状态探针（返回 JSON 字符串）。 */
    private val SUBMIT_PROBE_JS = """
(function(){
  try{
    var el = window.__nbPickEl;
    var gone = (!el || !el.isConnected);
    var raw = gone ? '' : ((el.value !== undefined && el.value !== null) ? el.value : (el.innerText || el.textContent || ''));
    var left = gone ? -1 : ('' + raw).replace(/\s/g,'').length;
    var stop = false;
    var btns = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < btns.length; i++) {
      var r = btns[i].getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      var t = ((btns[i].getAttribute('aria-label') || '') + ' ' + (btns[i].innerText || '')).replace(/\s/g,'');
      if (/停止|中止|stop|生成中/.test(t)) { stop = true; break; }
    }
    return JSON.stringify({left:left, gone:gone, stop:stop,
      len:(document.body?document.body.innerText.length:0), nodes:${MESSAGE_NODE_COUNT_JS}});
  }catch(e){ return '{}'; }
})()
    """.trimIndent()

    /** 当前页面消息节点数量（提交前后比较用）。 */
    private suspend fun messageNodeCount(view: WebView): Int =
        evalJs(view, MESSAGE_NODE_COUNT_JS).trim('"').toDoubleOrNull()?.toInt() ?: 0

    // ── 「消息真的送到站点了吗」＋失败取证（用户截图：卡在「已提交，等待站点出字…（65s）」）──────
    // 取证结论：submitPrompt 的判据只能证明**我们这边**点了发送按钮/派发了回车，证明不了站点收下了这轮。
    // 未登录、要过验证、被风控、或站点改版成空壳编辑器时，站点**同样会把输入框清空**，但页面上
    // 永远不会出现我们那条消息、更不会出字。旧实现只能一路等到 90s 超时，用户看到的就是「一直转圈」。

    /** 最近一次抓取失败的取证说明（供失败文案引用，见 [collectReply]）。 */
    @Volatile private var lastCollectNote: String? = null

    /**
     * 页面的**对话区**里还能找到我们这条消息吗（[promptFingerprint] 的开头/结尾指纹，
     * 站点折叠长消息也能命中）。
     *
     * 只查对话区（见 [CONV_TEXT_JS]）：提示词还躺在输入框里**不算**「已经送出去」——
     * 未登录的站点上，我们填进 contenteditable 输入框的文字会被 `body.innerText` 带出来，
     * 旧实现据此误判「送达成功」，接着白等一轮出字。这是「网关报原因不准」的根因之一。
     */
    private suspend fun promptOnPage(view: WebView, fp: String, fpTail: String): Boolean {
        val js = "(function(){try{var t=(" + CONV_TEXT_JS + ").replace(/\\s/g,'');" +
            "return (t.indexOf(${JSONObject.quote(fp)})>=0||t.indexOf(${JSONObject.quote(fpTail)})>=0)?'1':'0';" +
            "}catch(e){return '-1';}})()"
        return evalJs(view, js).trim('"') == "1"
    }

    /**
     * 确认这轮消息**真的落到了页面上**：出现新消息节点，或页面上出现了我们这条消息本身。
     *
     * @return `null` = 已确认送达；否则返回给用户看的失败原因。
     */
    private suspend fun waitLanded(
        view: WebView,
        ai: WebAi,
        fp: String,
        fpTail: String,
        baseNodes: Int,
        waitMs: Long,
        onProgress: (String) -> Unit
    ): String? {
        val started = System.currentTimeMillis()
        var tick = 0
        // 提示词是否「一直原样躺在输入框里」。见下方快速失败分支。
        var stuckInComposer = 0
        while (System.currentTimeMillis() - started < waitMs) {
            delay(400)
            if (messageNodeCount(view) > baseNodes) return null
            if (promptOnPage(view, fp, fpTail)) return null
            // ★ 快速失败：提示词**还完整地躺在输入框里** = 站点根本没接收这轮（未登录 / 被风控 /
            //   发送键失效）。旧实现只能傻等满 18s；三个「没登录」的站点就是 54s 纯等待，
            //   用户看到的就是「网关一直转圈」。这里连查 8 次（约 3.2s，给慢站点留出重绘时间）
            //   都还在输入框里，就立刻认输换站。
            if (composerLen(view, ai) > 8) {
                stuckInComposer++
                if (stuckInComposer >= 8) {
                    return "输入框里还留着我们刚填进去的那条消息（站点没有接收这轮发送）"
                }
            } else {
                stuckInComposer = 0
            }
            if (tick % 8 == 0) {
                onProgress("确认消息已送达站点…（${(System.currentTimeMillis() - started) / 1000}s）")
            }
            tick++
        }
        return "在 ${waitMs / 1000}s 内页面上始终没有出现你发的那条消息"
    }

    /**
     * 网关失败取证：把「这次调用看到的页面」追加到 `<files>/logs/gateway.log`。
     *
     * 为什么需要：真机上「发不出去」的原因只有页面自己知道（地址/标题/元素数/开头文字/是否还在生成）。
     * 旧实现只把原因写进 logcat，缓冲一滚就没了，只能靠猜；有了这份持久证据，下一次迭代能直接判定
     * 是「没登录」「地区限制」还是「改版空壳」。日志超过 128KB 自动清空，不会无限增长。
     */
    /**
     * 【仅 debug 构建】把一次端到端探测的结果写进 gateway.log。
     *
     * 为什么需要它：网关的失败现场（未登录/风控/消息没落地）只有在**真机上跑一轮 ask** 才看得到，
     * 而用户手机通常拿不到 adb。有了这个入口，就能用
     * `am start ... --es probe "回复OK" --es site DeepSeek` 触发一轮真实收发，
     * 结论直接落在 files/logs/gateway.log 里 —— 排查不必再靠用户口述。
     */
    suspend fun appendProbeLog(
        context: Context,
        ai: WebAi,
        prompt: String,
        elapsedMs: Long,
        result: String
    ) {
        runCatching {
            val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) +
                " [PROBE][${ai.label}] elapsed=${elapsedMs}ms promptLen=${prompt.length}" +
                "\n    result=${result.replace("\n", " ").take(800)}\n"
            val dir = java.io.File(context.filesDir, "logs").apply { mkdirs() }
            val f = java.io.File(dir, "gateway.log")
            if (f.exists() && f.length() > 256 * 1024) f.writeText("")
            f.appendText(line)
        }
    }

    private suspend fun saveGatewayDiag(context: Context, ai: WebAi, view: WebView, note: String) {
        runCatching {
            val title = runCatching { evalJs(view, "document.title").trim('"') }.getOrDefault("").take(80)
            // 这段开头**必须排除输入框**：旧实现用 body.innerText，于是记下来的「页面开头」常常
            // 就是我们自己那几行提示词（还躺在 composer 里没发出去），把真正的现场信息挤掉了。
            val head = runCatching {
                unescapeJs(
                    evalJs(
                        view,
                        "(function(){var t=(" + CONV_TEXT_JS + ").replace(/\\s+/g,' ').trim();return t.slice(0,160);})()"
                    ).trim('"')
                )
            }.getOrDefault("").take(160)
            val generating = runCatching { isGenerating(view) }.getOrDefault(false)
            // tail：只看 head 会漏掉真正的现场（head 常年被侧栏/导航占满）。
            // 真机调试 DeepSeek「收到但不出字」时，正是靠 tail 才能看出页面尾部到底是
            // 「思考中…」还是「消息根本没发送成功」。
            val tail = runCatching {
                unescapeJs(
                    evalJs(
                        view,
                        "(function(){var t=(" + CONV_TEXT_JS + ").replace(/\\s+/g,' ').trim();return t.slice(-200);})()"
                    ).trim('"')
                )
            }.getOrDefault("").take(200)
            val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()) +
                " [${ai.label}] $note" +
                "\n    url=${view.url}" +
                "\n    title=$title nodes=${messageNodeCount(view)} textLen=${bodyTextLen(view)}" +
                " convLen=${convLen(view)} generating=$generating" +
                "\n    head=$head\n    tail=$tail\n"
            val dir = java.io.File(context.filesDir, "logs").apply { mkdirs() }
            val f = java.io.File(dir, "gateway.log")
            if (f.exists() && f.length() > 128 * 1024) f.writeText("")
            f.appendText(line)
        }
    }

    /**
     * 「站点还在出字」探针：页面上仍有「停止生成」按钮 = 这轮回答**没写完**。
     *
     * 为什么必须有它（真机症状，19 tokens 之谜）：站点是流式输出的，长回答里
     * 代码块/markdown 重排会让 `body.innerText` 长度**停顿 1 秒**，旧逻辑此时就把
     * 「第一句」当成完整回答返回了 —— 任务台于是收到一句客套话、自然一个工具调用都没有
     * （实测 ↑2776 tokens 发出 / ↓19 tokens 收回）。
     */
    private val GENERATING_PROBE_JS = """
(function(){
  try{
    var btns = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var r = b.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      var t = ((b.getAttribute('aria-label') || '') + ' ' + (b.innerText || '')).replace(/\s/g,'');
      if (/停止|中止|stop|生成中|停止回答/.test(t)) return '1';
    }
    // 兜底：有些站在「深度思考」阶段根本不挂停止按钮，只在正文里显示「思考中」。
    // 真机取证：DeepSeek 收到长提示词后 75s 一个字符都没吐（正文长度不变、按钮探针=false），
    // 只靠按钮探针就会被误判成「站点静默拒答」而换站 —— 于是永远拿不到回答。
    var body = (document.body ? document.body.innerText : '') || '';
    if (/思考中|正在思考|深度思考中|正在生成|生成回答中|正在回答|排队中|请稍候|Thinking|Generating/.test(body)) return '1';
    return '0';
  }catch(e){ return '-1'; }
})()
    """.trimIndent()

    /** 站点是否还在出字（拿不到探针结果时按「否」处理，避免把站点永远等下去）。 */
    private suspend fun isGenerating(view: WebView): Boolean =
        evalJs(view, GENERATING_PROBE_JS).trim('"') == "1"

    private fun submitJs(strategy: String, sendSel: String, sel: String): String = when (strategy) {
        "hint" -> """
(function(){
  var b = null;
  try { b = $sendSel ? document.querySelector($sendSel) : null; } catch(e) { b = null; }
  if (!b) return 'NOSEL';
  if (b.disabled) return 'DISABLED';
  try { b.click(); } catch(e) { return 'ERR'; }
  return 'CLICK';
})()
""".trimIndent()
        "keyword" -> """
(function(){
  var keys = ['send','submit','发送','提交'];
  var all = document.querySelectorAll('button,[role="button"],[data-testid*="send"],[data-testid*="submit"]');
  for (var i = all.length - 1; i >= 0; i--) {
    var e = all[i]; var r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0 || e.disabled) continue;
    var t = ((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('data-testid') || '') + ' ' +
             (e.title || '') + ' ' + (e.innerText || '')).toLowerCase();
    // 别把「深度思考 / 联网搜索 / 上传 / 语音」这类同级开关当成发送键（真机上误点过）。
    if (/深度思考|联网|搜索|上传|附件|语音|清空|新建|停止|停止生成|deepthink|search|upload|attach|voice/.test(t)) continue;
    for (var k = 0; k < keys.length; k++) { if (t.indexOf(keys[k]) >= 0) { try { e.click(); } catch(x) {} return 'CLICK'; } }
  }
  return 'NONE';
})()
""".trimIndent()
        "sibling" -> """
(function(){
  var el = window.__nbPickEl;
  if (!el) return 'NOINPUT';
  var box = (el.closest && el.closest('form')) || el.parentElement;
  for (var up = 0; up < 4 && box; up++) {
    var btns = box.querySelectorAll('button,[role="button"]');
    for (var i = btns.length - 1; i >= 0; i--) {
      var b = btns[i]; var r = b.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0 || b.disabled) continue;
      try { b.click(); return 'CLICK'; } catch(e) {}
    }
    box = box.parentElement;
  }
  return 'NONE';
})()
""".trimIndent()
        "form" -> """
(function(){
  var el = window.__nbPickEl;
  var f = (el && el.closest) ? el.closest('form') : null;
  if (!f) return 'NOFORM';
  try {
    if (typeof f.requestSubmit === 'function') { f.requestSubmit(); return 'SUBMIT'; }
    var sq = f.querySelector('button[type="submit"],input[type="submit"]');
    if (sq && !sq.disabled) { sq.click(); return 'CLICK'; }
  } catch(e) { return 'ERR'; }
  return 'NONE';
})()
""".trimIndent()
        else -> """
(function(){
  var el = window.__nbPickEl;
  if (!el) return 'NOINPUT';
  el.focus();
  ['keydown','keypress','keyup'].forEach(function(t){
    el.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
  });
  return 'ENTER';
})()
""".trimIndent()
    }

    /**
     * 取回答：轮询正文长度，等它「变长且连续几次稳定」→ 返回相对发送前的增量。
     * 不依赖站点 DOM，站点改版也不会失效。
     *
     * 提速点（旧版 1200ms × 3 次稳定 = 每轮结尾白等 3.6s）：
     * 轮询间隔降到 500ms、稳定判定 3 次（= 1.5s），并且**第一次读取不再额外 sleep 1.2s**
     * —— 生成开始的检测也提前了。整体每轮省 2~4s。
     */
    private suspend fun collectReply(
        view: WebView,
        beforeLen: Int,
        beforeText: Int,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
        /** 本轮发出去的**完整提示词**：既用于「末尾比对」，也用于识别我们提示词里的独有哨兵。 */
        echoGuard: String = ""
    ): String {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        // 「增长停了」≠「回答来了」：站点首页水合、**我们自己刚贴进去的提示词气泡**也会让正文增长。
        // 所以增长停下来后先看能不能抓到可用回答，抓不到就继续等（最多再等 graceMs）。
        val graceMs = (timeoutMs / 2).coerceIn(20_000L, 60_000L)
        var lastLen = beforeLen
        var lastText = beforeText
        var stable = 0
        var grew = false
        var ticks = 0L
        var partial = ""
        var lastProbe = 0L
        val pollMs = 500L
        lastCollectNote = null
        // 落地确认过了、站点却迟迟不出字（排队/限流/静默拒答）也要快速换站：否则用户又要盯着
        // 「已提交，等待站点出字…」看满 90s，三个站点就是 4.5 分钟。
        var noReplyDeadline = started + NO_REPLY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            // 站点自己还举着「停止生成」= 它正在干活（排队 / 深度思考 / 长回答），这时**绝不能**
            // 按「久久不出字」判失败：真机上 DeepSeek 就是被这个窗口误杀成 blank-answer 的。
            // 只要它在忙，或者正文已经在长，窗口就自动往后延（上限是本次调用的 deadline）。
            // 页面彻底不响应（renderer 被系统冻结：熄屏 / 退到后台）→ 立刻失败并说明原因，
            // 别让用户等满总超时（真机取证：346s 才返回，界面全程点不动）。
            if (evalTimeoutStreak >= 3) {
                lastCollectNote = "页面无响应（连续 $evalTimeoutStreak 次脚本调用超时）" +
                    "—— 多半是屏幕熄灭或应用退到了后台，系统把网页冻结了"
                android.util.Log.w("NbWebAi", "collectReply：$lastCollectNote，提前收工换站")
                recycleShared()
                break
            }
            val busy = runCatching { isGenerating(view) }.getOrDefault(false)
            if (busy || grew) noReplyDeadline = System.currentTimeMillis() + NO_REPLY_WAIT_MS
            if (!grew && !busy && System.currentTimeMillis() > noReplyDeadline) {
                lastCollectNote = "消息已出现在页面上，但 ${NO_REPLY_WAIT_MS / 1000}s 内站点没有任何出字" +
                    "（正文长度始终不变，多半是排队/限流/静默拒答）"
                android.util.Log.w("NbWebAi", "collectReply：${NO_REPLY_WAIT_MS / 1000}s 内始终没出字，提前收工换站")
                break
            }
            delay(pollMs)
            val now = bodyLen(view)
            val nowText = bodyTextLen(view)
            // 双指标：innerText 是人眼可见文字；textContent 含隐藏节点，更敏感。
            // 任一显著增长就算「站点开始出字」，避免站点把答案放进展开/懒渲染容器时漏判。
            // ★ 阈值只要求「至少多 1 个字」：真机取证里站点回了 2 个字的短答案（+2），
            //   旧阈值要求「> +2」→ 被判成「没有出字」，最后误报 blank-answer 并换站。
            if (now > lastLen || nowText > lastText) {
                grew = true
                stable = 0
                lastLen = now
                lastText = nowText
            } else if (grew) {
                stable++
                if (stable >= 2) {
                    val t = System.currentTimeMillis()
                    // 站点还在出字（页面仍有「停止生成」按钮）时**绝不许收工**：
                    // 真机症状是流式第一句出来后页面停顿 1s，我们把「第一句」当完整回答返回，
                    // 任务台收到一句客套话 —— 于是「不调工具、任务没做完」（↑2776 / ↓19 tokens）。
                    val generating = isGenerating(view)
                    if (t - lastProbe >= 1_200L) {
                        lastProbe = t
                        // ★ 只有**真的抓到可用回答**才收工。否则说明刚才的增长只是首页水合/提示词气泡
                        //   —— 旧实现在此直接收工，于是把「提示词回显 + 首页文字」当回答返回（真机症状：
                        //   AI 只输出一段预设指令、不调用任何工具）。
                        val early = readAnswer(view, beforeLen, beforeText, echoGuard)
                        // 「安静下来」+「站点不再出字」才认；或者回答已经很长（>400 字，明显写完了）。
                        val settled = stable >= 3
                        val bigEnough = early.count { !it.isWhitespace() } >= 400
                        if (early.isNotBlank() && !generating && (settled || bigEnough)) {
                            android.util.Log.i(
                                "NbWebAi",
                                "collectReply 抓到可用回答，用时 ${t - started}ms，${early.length} 字" +
                                    "（稳定=$stable 生成中=$generating）"
                            )
                            android.util.Log.i(
                                "NbWebAi",
                                "回答原文开头：${early.replace("\n", " ").take(160)}"
                            )
                            return early
                        }
                        if (early.isNotBlank()) {
                            android.util.Log.i(
                                "NbWebAi",
                                "暂不收工：生成中=$generating 稳定=$stable 已抓到=${early.length} 字"
                            )
                        }
                    }
                    // 还在出字就一路等到 deadline；确认不再出字了，才允许用 graceMs 兜底收工。
                    if (!generating && t - started > graceMs) {
                        android.util.Log.w("NbWebAi", "collectReply 宽限期内始终没出现可用回答，收工（按失败处理）")
                        break
                    }
                }
            }
            val sec = (System.currentTimeMillis() - started) / 1000
            // 进度行带一段实时正文尾巴：用户能立刻看到「站点在出字」，不必干等到整段抓完。
            if (grew && ticks % 3 == 0L) {
                partial = stripChrome(
                    stripEcho(
                        unescapeJs(evalJs(view, "document.body.innerText.substring($beforeLen)").trim('"')),
                        echoGuard
                    )
                ).trim().takeLast(90).replace("\n", " ")
            }
            onProgress(
                if (grew) "回答生成中（${sec}s）" + if (partial.isNotBlank()) "：$partial…" else "…"
                else "已提交，等待站点出字…（${sec}s）"
            )
            ticks++
        }
        if (!grew) {
            // ★ 真机教训（2.12.72 取证）：站点把答案吐在「提交后的一瞬间」时，长度基准里
            //   已经含了答案 → 轮询看着「长度始终不变」，但页面上确实有回答。
            //   旧代码在这里直接 return ""，于是**明明答了也报失败**（用户看到的就是这个）。
            //   现在先按锚点正式取一次：取到非空、且不像回显/首页外壳 → 照常成功返回。
            val rescued = runCatching { readAnswer(view, beforeLen, beforeText, echoGuard) }.getOrNull()
            if (!rescued.isNullOrBlank()) {
                android.util.Log.i(
                    "NbWebAi",
                    "collectReply：长度没增长，但锚点取到了回答（${rescued.length} 字）→ 收下，" +
                        "不再误报 blank-answer"
                )
                lastCollectNote = null
                return rescued
            }
            if (lastCollectNote == null) {
                lastCollectNote = "提交后正文长度一直没有变化（站点没有开始生成）"
            }
            return ""
        }
        android.util.Log.i(
            "NbWebAi",
            "collectReply 结束（到点/不再出字），用时 ${System.currentTimeMillis() - started}ms，" +
                "正文 $beforeLen→$lastLen / 全文 $beforeText→$lastText"
        )
        val tail = readAnswer(view, beforeLen, beforeText, echoGuard)
        android.util.Log.i("NbWebAi", "超时收工取回的原文（${tail.length} 字）开头：${tail.replace("\n", " ").take(160)}")
        return tail
    }

    /**
     * 从页面里读一次「AI 的回答」。三层取法，逐层放宽：
     * ① 站点自己的回答容器（[ANSWER_NODE_JS] 候选列表，从后往前挑第一个不像回显/首页文字的）
     * ② `body.innerText` 增量 ③ `body.textContent` 增量。
     * 三层全被判为回显/页面外壳文字 → 返回空串（**如实失败**，由上层换站重试并报真实原因）。
     */
    private suspend fun readAnswer(view: WebView, beforeLen: Int, beforeText: Int, echoGuard: String): String {
        // ⓪ 硬前提：页面上必须存在「我们自己刚发出去的那条消息」。找不到 = 这轮根本没提交成功
        //    （未登录/被风控/提交没生效/还停在首页）。此时**绝不允许**退化成「拿站点首页的推荐流
        //    当回答」——真机症状就是豆包首页文字被当回答，上层于是「答非所问 + 不调用工具 + 每次换站点」。
        val fps = listOf(promptFingerprint(echoGuard), promptFingerprintTail(echoGuard))
            .filter { it.length >= 8 }.distinct()
        var anchored: JSONObject? = null
        var why = "no-anchor"
        for (fp in fps) {
            val raw = unescapeJs(evalJs(view, anchorAnswerJs(fp)).trim('"'))
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (o.optBoolean("ok")) { anchored = o; break }
            why = o.optString("why", why)
        }
        if (anchored == null) {
            android.util.Log.w(
                "NbWebAi",
                "readAnswer：页面里找不到我们发出去的那条消息（$why）→ 这轮没提交成功，按失败处理"
            )
            return ""
        }
        val arr = anchored.optJSONArray("list")
        val cands = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it, "") }.filter { it.isNotBlank() }

        // ① 锚点之后的回答容器：真机症状是「最后一个匹配节点恰是我们自己刚发出去的提示词气泡」，
        //    所以候选要**从后往前**逐个过准入，而不是只看最后一个。
        for (c in cands.asReversed()) {
            val node = stripChrome(stripEcho(c, echoGuard)).trim()
            if (answerUsable(node, echoGuard)) return node.takeLast(12_000)
        }
        if (cands.isNotEmpty()) {
            android.util.Log.w(
                "NbWebAi",
                "readAnswer：锚点之后的候选都被判为回显/外壳，首条=${cands.first().take(140)}"
            )
        }

        val inner = stripChrome(
            stripEcho(
                unescapeJs(evalJs(view, "document.body.innerText.substring($beforeLen)").trim('"')).trim(),
                echoGuard
            )
        ).trim()
        if (answerUsable(inner, echoGuard)) return inner.takeLast(12_000)
        // innerText 没取到东西（站点把答案放进了隐藏/懒渲染容器）→ 退回 textContent 增量
        val full = stripChrome(
            stripEcho(
                unescapeJs(evalJs(view, "document.body.textContent.substring($beforeText)").trim('"')).trim(),
                echoGuard
            )
        ).trim()
        if (answerUsable(full, echoGuard)) return full.takeLast(12_000)
        // ④ 最后一道兜底（2.12.75 真机取证 → 这是「站点明明答了却报失败」的真凶）：
        //    站点把可见文字收敛成「只剩这一轮对话」的极短文本（真机 DeepSeek 整页 innerText 仅 99 字），
        //    而我们的轮询基准是在**回答已经落到页面上之后**才采样的 → `substring(beforeLen)`
        //    从答案**后面**开始切，切出来全是「深度思考 / 智能搜索」这类输入框周边文案，
        //    整段被判成「回显/外壳」，于是回答被丢掉。
        //    这里不再用任何偏移量，直接按内容取：整页可见文字 → 去回显 / 去外壳 → 剩下的就是回答。
        val whole = stripChrome(
            stripEcho(
                unescapeJs(
                    evalJs(view, "(document.body?document.body.innerText:'').slice(-4000)").trim('"')
                ).trim(),
                echoGuard
            )
        ).trim()
        if (answerUsableLoose(whole, echoGuard)) {
            android.util.Log.i(
                "NbWebAi",
                "readAnswer：偏移量取不到，但按内容取到了回答（${whole.length} 字）→ 收下"
            )
            return whole.takeLast(12_000)
        }
        android.util.Log.w(
            "NbWebAi",
            "readAnswer 抓到的内容判为回显/页面外壳文字，按失败处理：inner=${inner.take(160)} whole=${whole.take(160)}"
        )
        return ""
    }


    private fun unescapeJs(raw: String): String = runCatching {
        JSONArray("[\"x\"]") // 占位，避免未用导入告警
        raw.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
    }.getOrDefault(raw)
}
