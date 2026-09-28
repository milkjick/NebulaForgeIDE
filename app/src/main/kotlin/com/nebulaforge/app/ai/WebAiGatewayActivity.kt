package com.nebulaforge.app.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「AI 网关」的全屏浏览器宿主。
 *
 * 为什么必须是独立 Activity，而不是 Compose 里的一块区域：
 *   真机取证（logcat / huawei.webview 132）走过两次弯路 ——
 *   ① 直接把 WebView 挂在 Compose 面板区域：`page load start` 之后 28ms 就被
 *      `StopAllLoaders` / `DetachFromFrame`，url_request 报 -3 (ERR_ABORTED)，一直白屏；
 *   ② 改挂在 Compose `Dialog`（自带 Window 的浮层）：`onPageFinished`（面板状态已是
 *      「已加载：url」）之后**页面内容不合成**，只剩下 WebView 自己的白色底 → 用户看到
 *      「整屏空白」。原因是浮层窗口的内容以 RenderNode/图层方式合成，华为的 WebView
 *      实现不参与该合成路径（只画底色）。
 *   WebView 的规范宿主是有真实 window、开启硬件加速的 Activity —— 系统浏览器、
 *   各家 App 的内置浏览器、所有成熟实现都是这么做的，故这里改为全屏 Activity。
 *
 * 与离屏桥接（WebAiBridge.ask）共用同一个 WebView profile（同进程同一份 cookie/data），
 * 所以在这里登录一次，之后「试问」也是登录态。
 */
class WebAiGatewayActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** 【仅 debug】网关端到端探测：`--es probe "回复OK"` 触发一轮真实收发，结论写 gateway.log。 */
        const val EXTRA_PROBE = "probe"

        /** 【仅 debug】探测用的站点（站点 id / 名称 / URL 片段都认）。 */
        const val EXTRA_PROBE_SITE = "site"

        /** 打开全屏网关。context 不是 Activity 时补 NEW_TASK，避免 BadTokenException。 */
        fun open(context: Context, url: String) {
            val i = Intent(context, WebAiGatewayActivity::class.java).putExtra(EXTRA_URL, url)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
                .onFailure { Log.w("NbWebAi", "startActivity 失败：${it.message}") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var host: FrameLayout? = null
    private var view: WebView? = null
    private var btnUa: TextView? = null

    /** 空壳页自动救场记录：同一个 URL 只自动换一次 UA，避免无限重载。 */
    private var autoHealUrl: String? = null
    private var url: String = ""
    private var selfTestRound = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 键盘弹出时窗口 resize 而不是 pan：WebView 里的 InputConnection 才不会被反复重建
        // （真机现象是「每打一个字光标跳回行首」）。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // 显式要求硬件加速：WebView 依赖 HW 合成，窗口级关掉时表现为「只画白底」。
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        // 面板浏览时保持屏幕常亮（登录 OAuth 跳转较慢）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            // 【2.12.77】只带 --es probe 时自动取目标站点的 URL。
            // 以前 probe 必须额外带 --es url，缺了就**静默结束**（真机排查时白等一轮，日志里什么都没有）。
            val probePrompt = intent.getStringExtra(EXTRA_PROBE).orEmpty()
            if (probePrompt.isNotBlank()) {
                val site = intent.getStringExtra(EXTRA_PROBE_SITE).orEmpty()
                val all = WebAiBridge.all(this)
                url = (all.firstOrNull { it.id == site || it.label.equals(site, true) || it.url.contains(site, true) }
                    ?: all.firstOrNull { it.usable })?.url.orEmpty()
                Log.i("NbWebAi", "probe 未带 url，自动使用站点地址：${url.ifBlank { "(无可用站点)" }}")
            }
        }
        if (url.isBlank()) {
            Log.w("NbWebAi", "gateway 缺少 url 参数，直接结束")
            finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7FBFD"))
        }

        // ── 顶部工具条（原生控件，不依赖 Compose）───────────────────────────────
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
        }
        val title = TextView(this).apply {
            text = if (url.length > 46) url.take(46) + "…" else url
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
            )
            setOnClickListener { onClick() }
        }

        val btnReload = smallButton("重载") {
            Log.i("NbWebAi", "gateway reload url=$url")
            WebAiBridge.reload(this@WebAiGatewayActivity)
            refreshStatus()
        }
        val uaButton = smallButton("UA:手机") {}
        btnUa = uaButton
        uaButton.setOnClickListener {
            WebAiBridge.cycleUserAgent(this@WebAiGatewayActivity)   // 手机 ⇄ 桌面
            // 换 UA 之后同一个 URL 仍然可能是空壳页，但那已经是用户主动的选择 → 不再自动救。
            autoHealUrl = WebAiBridge.panelUrl
            syncUaButton()
            refreshStatus()
        }
        syncUaButton()
        val btnBrowser = smallButton("浏览器") {
            WebAiBridge.openInSystemBrowser(this@WebAiGatewayActivity, WebAiBridge.panelUrl ?: url)
        }
        val btnSelf = smallButton("自检") {
            // 轮转：内核自检 → 输入自检 → 回到目标页。用于区分「渲染问题」与「站点问题」。
            when (selfTestRound % 3) {
                0 -> WebAiBridge.panelSelfTest()
                1 -> WebAiBridge.panelInputSelfTest()
                else -> WebAiBridge.reload(this@WebAiGatewayActivity)
            }
            selfTestRound++
            refreshStatus()
        }
        val btnClose = smallButton("关闭") { finish() }

        bar.addView(title)
        bar.addView(btnReload)
        bar.addView(btnUa)
        bar.addView(btnBrowser)
        bar.addView(btnSelf)
        bar.addView(btnClose)

        // ── 状态行：白屏时能一眼看出是「加载中 / 报错 / 站点给了空正文」─────────────
        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(10), 0, dp(10), dp(4))
            maxLines = 2
        }
        statusView = status

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.WHITE)
        }
        host = frame

        root.addView(bar)
        root.addView(status)
        root.addView(frame)
        setContentView(root)

        attachWebView()
        handler.post(ticker)
        startProbeIfRequested(intent)
    }

    /**
     * 【仅 debug 构建】跑一轮真实的网页 AI 收发并把结果写进 gateway.log。
     *
     * 只在 debuggable 构建（内测包）里生效：release 包里这段代码走不进去。
     */
    private fun startProbeIfRequested(intent: Intent) {
        // 用运行期「是否 debuggable」判定，而不是 BuildConfig：这样不依赖 buildConfig 特性开关，
        // 语义也更准 —— release 包（不可调试）永远不会走这条探测路径。
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val prompt = intent.getStringExtra(EXTRA_PROBE).orEmpty()
        if (prompt.isBlank()) return
        val site = intent.getStringExtra(EXTRA_PROBE_SITE).orEmpty()
        val all = WebAiBridge.all(this)
        val target = all.firstOrNull { it.id == site || it.label.equals(site, true) || it.url.contains(site, true) }
            ?: all.firstOrNull { it.usable }
        if (target == null) {
            Log.w("NbWebAi", "probe：配置里没有可用站点，跳过")
            return
        }
        Log.i("NbWebAi", "probe 启动：${target.label} promptLen=${prompt.length}")
        statusView?.text = "探测中：${target.label} …"
        CoroutineScope(Dispatchers.Main).launch {
            // 给页面一点时间落地（面板刚挂载时站点还在水合，太早写入会白等）。
            delay(4_000)
            val started = System.currentTimeMillis()
            val result = runCatching {
                WebAiBridge.ask(this@WebAiGatewayActivity, target, prompt, 90_000)
            }.getOrElse { "✗ 探测异常：" + (it.message ?: it.javaClass.simpleName) }
            val elapsed = System.currentTimeMillis() - started
            Log.i("NbWebAi", "probe 结果（${elapsed}ms）：${result.replace("\n", " ").take(400)}")
            WebAiBridge.appendProbeLog(this@WebAiGatewayActivity, target, prompt, elapsed, result)
            finish()
        }
    }

    /** 取（或重建）面板 WebView 并挂到本 Activity 的内容区。 */
    private fun attachWebView() {
        val wv = WebAiBridge.ensurePanelWebView(this, url)
        if (wv == null) {
            // WebView 组件不可用：把原因显示出来，而不是留一片空白。
            statusView?.text = WebAiBridge.statusText()
            Log.w("NbWebAi", "gateway WebView 不可用：${WebAiBridge.unavailable}")
            return
        }
        // WebView 若还挂在旧宿主上（重建/重新打开），必须先摘下来，否则 addView 抛
        // 「The specified child already has a parent」。
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        host?.addView(wv)
        view = wv
        Log.i("NbWebAi", "gateway AndroidView 挂载完成 url=$url")
    }

    private val ticker = object : Runnable {
        override fun run() {
            // 渲染进程崩溃/被系统回收：WebAiBridge 会置重建标记，这里重建一次，
            // 否则用户永久停在白屏上（不重建的话页面也不会自己回来）。
            if (WebAiBridge.consumePanelRebuild()) {
                Log.w("NbWebAi", "gateway 检测到重建标记 → 重建 WebView")
                runCatching { view?.destroy() }
                runCatching { (view?.parent as? ViewGroup)?.removeView(view) }
                view = null
                attachWebView()
            }
            refreshStatus()
            maybeAutoHealBlank()
            handler.postDelayed(this, 900)
        }
    }

    /**
     * 空壳页自动救一次：页面「已加载」但正文只有几十字 → 换桌面 UA 重载。
     * 同一个 URL 只救一次（换过之后还空就老实把状态行留着，交给用户点「浏览器」）。
     */
    private fun maybeAutoHealBlank() {
        val url = WebAiBridge.panelUrl ?: return
        if (autoHealUrl == url) return
        if (!WebAiBridge.pageReady) return
        // 判据是**元素个数**而不是正文字数：真机上 DeepSeek 渲染完整也只有 19 字（UI 多是
        // placeholder/aria，不算 innerText），用字数判会把正常页面误救成「换 UA」。
        if (WebAiBridge.pageElementCount !in 1..40) return
        if (WebAiBridge.uaDesktop) return    // 已经桌面 UA 还空 → 再换回手机没意义
        autoHealUrl = url
        Log.w("NbWebAi", "面板元素仅 ${WebAiBridge.pageElementCount} 个（空壳页）→ 自动切桌面 UA 重载一次")
        WebAiBridge.cycleUserAgent(this)
        syncUaButton()
        refreshStatus()
    }

    private fun syncUaButton() {
        btnUa?.text = "UA:" + (if (WebAiBridge.uaDesktop) "桌面" else "手机")
    }

    private fun refreshStatus() {
        val s = WebAiBridge.statusText()
        if (statusView?.text?.toString() != s) {
            statusView?.text = s
            statusView?.setTextColor(
                if (WebAiBridge.lastError != null || WebAiBridge.unavailable != null)
                    Color.parseColor("#B91C1C") else Color.parseColor("#64748B")
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        // 只「摘下来」不销毁：WebView 实例与 cookie 一起留着，下次打开秒回（不重新登录）。
        WebAiBridge.detachPanelView()
        Log.i("NbWebAi", "gateway onDestroy（WebView 已摘下保留）")
        super.onDestroy()
    }
}
